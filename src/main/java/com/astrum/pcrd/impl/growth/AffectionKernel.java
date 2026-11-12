package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.HookModifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>earning half</b> of affection: which tier a number is in, and what each tier changes
 * (spec §6.2).
 *
 * <h2>Why the ladder is a delegation</h2>
 * {@link GrowthFacts#tier(int, AffectionDef)} already holds the one implementation of the three-tier
 * ladder, written for the 角色册 screen. {@link #tier(int, AffectionDef)} here is a delegation, one
 * line, for the same reason {@code ExperienceKernel.expToNext} is: a book that says "tier 2" while the
 * damage pipeline still scales as tier 1 is the duplication bug this project has shipped three times.
 * What this class adds is the part {@code GrowthFacts} does not do — turning a tier into an effect.
 *
 * <h2>What each tier buys</h2>
 * <ul>
 *   <li><b>Tier 1 — 白板.</b> No hook is scaled. The character still works: its hooks run and its
 *       <em>base</em> ultimate is castable (spec §6.2).</li>
 *   <li><b>Tier 2 — 数值.</b> Only the hooks <em>named</em> in {@code tier2HookModifiers} are scaled,
 *       by {@code hookIndex}. A hook the pack did not list keeps {@code 1.0}: this tier never adds an
 *       effect, and it never touches a hook that was not declared it would (§6.2's 只改数值不改数量).</li>
 *   <li><b>Tier 3 — 换 UB.</b> {@link #usesEnhancedUb(int)} answers {@code true}, so the cast path
 *       swaps in {@code ub.enhanced} and its own animation. The tier-2 hook modifiers <b>keep
 *       applying</b>: tier 3 is above tier 2, and a character that reaches it must not lose the
 *       numbers it earned on the way.</li>
 * </ul>
 *
 * <h2>One implementation of "is this tier 3?"</h2>
 * {@code UbService.atTier3} (what the wheel icon and the cast path ask) resolves it as
 * {@code AffectionKernel.usesEnhancedUb(AffectionKernel.tier(affection, def))} — the same two calls
 * the combat pipeline makes. That is deliberate: the roster threshold, the wheel icon and the ultimate
 * the player gets are three views of one answer.
 *
 * <h2>Hostile data</h2>
 * A datapack can name a negative index (never matches), a {@code mult} of {@code 0} or {@code -1} (a
 * buff that would disable or invert a hook) or {@code NaN} (which would poison every later
 * multiplication). Each is ignored rather than obeyed, so a broken modifier leaves the hook at its
 * declared value instead of switching it off.
 */
@ApiStatus.Internal
public final class AffectionKernel {

    private AffectionKernel() {
    }

    /**
     * {@return the affection tier {@code affection} is in: 1, 2 or 3}
     *
     * <p>A delegation to {@link GrowthFacts#tier(int, AffectionDef)}, which owns the ladder.
     *
     * @param affection the character's affection
     * @param def       that character's affection definition
     */
    public static int tier(int affection, AffectionDef def) {
        return GrowthFacts.tier(affection, def);
    }

    /**
     * {@return the factor tier 2 applies to hook {@code hookIndex}, or {@code 1.0} when it is not
     * scaled}
     *
     * <p>Only hooks listed in {@code def.tier2HookModifiers()} are scaled, and only from tier 2 up.
     * A pack that lists one index twice compounds the factors ({@code 1.5} twice is {@code 2.25})
     * rather than guessing which entry the author meant.
     *
     * @param def       the character's affection definition
     * @param hookIndex index into the character's {@code effects[]} — the same index
     *                  {@link HookModifier#hookIndex()} names
     * @param tier      the character's tier, from {@link #tier(int, AffectionDef)}
     */
    public static double hookMultiplier(AffectionDef def, int hookIndex, int tier) {
        if (def == null || tier < 2) {
            return 1.0;   // tier 1 is the blank tier: nothing is scaled, nothing is switched off
        }
        double multiplier = 1.0;
        for (HookModifier modifier : def.tier2HookModifiers()) {
            if (modifier == null || modifier.hookIndex() != hookIndex) {
                continue;
            }
            if (!Double.isFinite(modifier.mult()) || modifier.mult() <= 0.0) {
                continue;   // hostile data: leave the hook at its declared value
            }
            multiplier *= modifier.mult();
        }
        return multiplier;
    }

    /**
     * {@return whether a character at {@code tier} casts the enhanced ultimate}
     *
     * <p>Spec §6.2: tier 3 replaces the ultimate and its animation. The comparison is {@code >=}
     * rather than {@code ==} so a save that somehow holds a tier above the last one still gets the
     * enhanced ultimate rather than falling back to the base one.
     *
     * @param tier the character's tier, from {@link #tier(int, AffectionDef)}
     */
    public static boolean usesEnhancedUb(int tier) {
        return tier >= GrowthFacts.MAX_TIER;
    }

    /**
     * {@return {@code current + delta} affection, floored at 0 and capped at the tier-3 threshold}
     *
     * <p>Moved here from {@code AstrumCommand}, which now delegates to it, because affection is
     * written from three places (the command, a fed dish, and whatever Task 16 adds) and the cap is a
     * rule of the affection system rather than of the command that happens to be the oldest writer.
     *
     * <p>Capped because affection above tier 3 buys nothing (spec §6.2 — tier 3 is the last tier), so
     * a number beyond it would be a value the game can never produce. A threshold of {@code 0} means
     * the pack defines no tier 3, and then there is nothing to cap to.
     *
     * <p>Widened to {@code long} before adding: two large {@code int}s would otherwise overflow into a
     * negative affection, which is the one value a roster should never hold.
     *
     * @param current        affection the player has now
     * @param delta          change requested; may be negative
     * @param tier3Threshold the definition's tier-3 threshold, or {@code 0} for none
     */
    public static int clampAffection(int current, int delta, int tier3Threshold) {
        long updated = (long) current + (long) delta;
        if (updated < 0L) {
            updated = 0L;
        }
        if (tier3Threshold > 0 && updated > tier3Threshold) {
            updated = tier3Threshold;
        }
        return (int) Math.min(Integer.MAX_VALUE, updated);
    }
}
