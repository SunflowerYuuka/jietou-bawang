package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.GrowthDef;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>earning half</b> of levels: what a level costs, how a gain climbs it, how a kill's
 * experience is split, and what a level is worth in combat (spec §6.1, §6.2).
 *
 * <h2>Why the curve is a delegation and not the formula</h2>
 * {@link GrowthFacts#expToNext(int, GrowthDef)} already holds the one implementation of
 * {@code expBase * expPerLevel^(level-1)}, written for the 角色册 screen's experience bar. Two copies
 * of that rule would eventually disagree — the book would promise a level at one number and combat
 * would award it at another — and this project has shipped three bugs of exactly that shape. So
 * {@link #expToNext(int, GrowthDef)} here is a <em>delegation</em>, one line, and the javadoc on
 * {@code GrowthFacts} names this class as the caller it expected. Everything this class adds is what
 * {@code GrowthFacts} deliberately does not do: gaining experience.
 *
 * <h2>What a level is worth</h2>
 * Spec §6.1 says a level buys 数值 — attachment multiplier, UB damage, charge speed. The attachment
 * half lands here as {@link #levelMultiplier(int, GrowthDef)}, which is composed into the one
 * multiplier chain at {@code CombatEvents} (diminishing × level × affection hook modifier, see
 * {@code HookContext}). It is <b>linear</b> and deliberately small:
 *
 * <pre>levelMultiplier(level) = 1 + LEVEL_GROWTH_PER_LEVEL × (level − 1)</pre>
 *
 * <p>At the old {@code maxLevel} 30 that is {@code 1.58}; the cap is now 100 (task 36), where it is
 * {@code 2.98}. The number is small on purpose, and the constant is <b>unchanged</b> by the level-cap raise: this
 * factor multiplies <b>quantities</b> (damage, healing, charge), while the level's other contribution — the effect
 * <b>tiers</b> a level grants — is a different mechanism entirely, in {@code LevelBands}. Making one factor serve
 * both would double-count the level. {@code UB} damage is
 * not scaled by level yet (the showcase settles it from the datapack alone), and spec §5.3 requires a
 * UB to be worth roughly 15–25 seconds of attachment output; a steeper curve would silently break that
 * ratio from the other side. {@code LEVEL_GROWTH_PER_LEVEL} is a constant rather than a config option
 * because §8.2's tables have no 养成 section and {@code ConfigDefaultsTest.noKeysBeyondSpec} pins the
 * file's top-level shape to those tables — a new section would be a spec change.
 *
 * <h2>Hostile arithmetic</h2>
 * Saves, datapacks and config files are all editable by hand, so every entry point here normalises
 * what it is given instead of trusting it: a level below 1 is read as 1, a negative experience total
 * as 0, a {@code maxLevel} of 0 or less as "no cap", and a {@code NaN} or negative contribution weight
 * as no weight at all. Nothing throws, because all of this runs inside a death event.
 */
@ApiStatus.Internal
public final class ExperienceKernel {

    /**
     * How much a level adds to the attachment multiplier, as a fraction per level.
     *
     * @see ExperienceKernel for why it is linear and small, and why it is not a config option
     */
    public static final double LEVEL_GROWTH_PER_LEVEL = 0.02;

    /**
     * Experience per point of a victim's maximum health.
     *
     * <p>Scaling with the victim rather than a flat number per kill keeps a chicken from being worth
     * a wither, without a second datapack file: a 20-health zombie is worth 10, which is a tenth of
     * level 1's requirement.
     */
    public static final double KILL_EXP_PER_HEALTH = 0.5;

    /** The least any contributor to a kill earns, so "you helped" is never "you got nothing". */
    public static final int CONTRIBUTOR_FLOOR = 1;

    private ExperienceKernel() {
    }

    /**
     * {@return the experience {@code level} needs to reach the next one}
     *
     * <p>A delegation to {@link GrowthFacts#expToNext(int, GrowthDef)}, which owns the curve.
     *
     * @param level  the current level, normally {@code >= 1}
     * @param growth the character's curve
     */
    public static int expToNext(int level, GrowthDef growth) {
        return GrowthFacts.expToNext(level, growth);
    }

    /**
     * {@return {@code progress} with {@code amount} experience gained, carrying the remainder across
     * every level it crosses and stopping at {@code growth.maxLevel()}}
     *
     * <p>The remainder is the whole point: a 250-point gain at level 1 is not "level 2 with 250 in the
     * bar", it is level 3 with 35 left over (100, then 115, then 132 is too much). Level-up therefore
     * loops, and the loop is bounded by {@code maxLevel} rather than by the amount.
     *
     * <p>Two edge cases are answered rather than guessed:
     * <ul>
     *   <li><b>At the cap</b> the experience is dropped to 0. A character at {@code maxLevel} has no
     *       next level, so a growing number would be a bar that can never fill and a save field that
     *       grows for the rest of the world's life.</li>
     *   <li><b>A curve with no requirement</b> ({@code expBase <= 0}, which {@code GrowthFacts}
     *       answers as 0) cannot be climbed: the loop stops instead of spinning forever on
     *       {@code exp -= 0}.</li>
     * </ul>
     *
     * @param progress the character's stored progress; never mutated
     * @param amount   experience gained; non-positive means "no change" (but the result is normalised)
     * @param growth   the character's curve
     */
    public static CharacterProgress add(CharacterProgress progress, int amount, GrowthDef growth) {
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(growth, "growth");
        int level = Math.max(1, progress.level());
        int exp = Math.max(0, progress.exp());
        int cap = capOf(growth);
        if (level >= cap) {
            return new CharacterProgress(cap, 0, progress.affection());
        }
        if (amount > 0) {
            exp = (int) Math.min(Integer.MAX_VALUE, (long) exp + (long) amount);
        }
        while (level < cap) {
            int required = GrowthFacts.expToNext(level, growth);
            if (required <= 0 || exp < required) {
                break;
            }
            exp -= required;
            level++;
        }
        if (level >= cap) {
            exp = 0;
        }
        return new CharacterProgress(level, exp, progress.affection());
    }

    /**
     * {@return how much a level multiplies this character's attachment quantities}
     *
     * <pre>1 + LEVEL_GROWTH_PER_LEVEL × (level − 1), clamped to [1, maxLevel]</pre>
     *
     * <p>A character whose progress is unknown (an unreadable book, a party slot nobody owns) is not
     * given a level it might not have: the caller passes {@code 1.0} for that case, or
     * {@link #levelMultiplier(CharacterProgress, GrowthDef)} with a {@code null} progress.
     *
     * @param level  the character's level; below 1 is read as 1
     * @param growth the character's curve, used only for {@code maxLevel}
     */
    public static double levelMultiplier(int level, GrowthDef growth) {
        Objects.requireNonNull(growth, "growth");
        int cap = capOf(growth);
        int clamped = Math.clamp(level, 1, cap);
        return 1.0 + LEVEL_GROWTH_PER_LEVEL * (clamped - 1);
    }

    /**
     * {@return {@link #levelMultiplier(int, GrowthDef)} for a stored progress record, or the neutral
     * {@code 1.0} when the progress is unknown}
     *
     * <p>{@code null} is the "this build cannot say what level that is" answer — a quarantined book, a
     * party slot the roster does not own, a character the loaded pack no longer defines. It is
     * deliberately not {@code 0}: a zero multiplier would switch a character's hooks off, which is the
     * fail-visible half of the same mistake a default level would make.
     *
     * @param progress the stored progress, or {@code null} when unknown
     * @param growth   the character's curve
     */
    public static double levelMultiplier(CharacterProgress progress, GrowthDef growth) {
        return progress == null ? 1.0 : levelMultiplier(progress.level(), growth);
    }

    /**
     * {@return the experience one kill is worth, from the victim's maximum health}
     *
     * @param victimMaxHealth the killed entity's maximum health
     */
    public static int killExp(double victimMaxHealth) {
        if (!(victimMaxHealth > 0.0) || !Double.isFinite(victimMaxHealth)) {
            return CONTRIBUTOR_FLOOR;
        }
        double exp = victimMaxHealth * KILL_EXP_PER_HEALTH;
        if (exp >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(CONTRIBUTOR_FLOOR, (int) Math.round(exp));
    }

    /**
     * {@return {@code totalExp} split across {@code contributions}, damage-proportional, at least
     * {@link #CONTRIBUTOR_FLOOR} each}
     *
     * <h2>The rule</h2>
     * Every <b>key</b> of {@code contributions} is a character that contributed, and a contributor is
     * never paid nothing: its share is {@code floor(total × weight / Σweights)}, floored at
     * {@link #CONTRIBUTOR_FLOOR}. A weight of {@code 0.0} therefore still earns the floor — the entry
     * exists, so that character was there — which is why "contributed a little" and "contributed
     * nothing" are different states and cannot be collapsed into "the map has no key".
     *
     * <p>No contributors means no division and no invented recipient: the answer is an empty map, and
     * the caller awards nothing. Because of the floor, a split of a small pool across many contributors
     * can total more than {@code totalExp}; that is the deliberate price of "helping is never worth
     * zero", and with five wheel slots it is bounded by five points.
     *
     * <p>The result is an unmodifiable map in the order of {@code contributions}, so a log line and a
     * test see a stable order.
     *
     * @param totalExp      the pool to split; non-positive or an empty map yields an empty result
     * @param contributions character id to damage contributed; weights below zero or not finite count
     *                      as no weight
     */
    public static Map<ResourceLocation, Integer> distribute(int totalExp,
            Map<ResourceLocation, Double> contributions) {
        if (contributions == null || contributions.isEmpty() || totalExp <= 0) {
            return Map.of();
        }
        double totalWeight = 0.0;
        for (Double weight : contributions.values()) {
            totalWeight += weight(weight);
        }
        Map<ResourceLocation, Integer> split = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Double> entry : contributions.entrySet()) {
            int share;
            if (totalWeight <= 0.0) {
                share = CONTRIBUTOR_FLOOR;
            } else {
                double exact = (double) totalExp * weight(entry.getValue()) / totalWeight;
                share = exact >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.floor(exact);
            }
            split.put(entry.getKey(), Math.max(CONTRIBUTOR_FLOOR, share));
        }
        return Collections.unmodifiableMap(split);
    }

    /** {@return the highest level this curve allows; {@code maxLevel <= 0} means "no cap"} */
    private static int capOf(GrowthDef growth) {
        return growth.maxLevel() > 0 ? growth.maxLevel() : Integer.MAX_VALUE;
    }

    /** {@return {@code raw} as a usable weight: non-finite and negative answer 0} */
    private static double weight(Double raw) {
        if (raw == null || !Double.isFinite(raw) || raw <= 0.0) {
            return 0.0;
        }
        return raw;
    }
}
