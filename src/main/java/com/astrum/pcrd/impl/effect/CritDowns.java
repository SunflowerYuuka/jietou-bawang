package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>The victim-side crit debuffs</b> — the applier for {@code EffectType.CRIT_CHANCE_DOWN} and
 * {@code EffectType.CRIT_DAMAGE_DOWN}, i.e. the first writers of {@code astrum:crit_chance} /
 * {@code astrum:crit_damage} on the body that was <b>hit</b>.
 *
 * <h2>Why one class for both halves</h2>
 * The two clauses of 未奏希's 6★ ultimate are one sentence with two numbers (「降低…物理暴击 18 秒、暴击时造成的伤害降低
 * 25%」), they share a receiver rule (the body the skill landed on) and they share a clock. Two appliers would be two
 * copies of the same five steps — holder, clock, amplifier, warn-once, apply — that would drift the first time one of
 * them changed. The two <b>types</b> stay separate for the reason {@code EffectType} gives: vanilla keys effects by
 * type, so one type would merge the halves into a single instance with a single expiry.
 *
 * <h2>What it actually does, stated plainly</h2>
 * It puts a negative attribute modifier on the victim, carried by a real {@code MobEffect} of this mod's own:
 *
 * <pre>
 *   astrum:crit_chance_down   ADD_VALUE              −amplifier × 0.001  (a step is 0.1 percentage point)
 *   astrum:crit_damage_down   ADD_MULTIPLIED_TOTAL    −amplifier × 0.01   (a step is 1 percent)
 * </pre>
 *
 * <p>{@code Crit.chanceOf} / {@code Crit.damageOf} read those attributes off the entity, so the winning value really
 * does fall — the debuff is not a second, parallel "reduced crit" flag that the damage path would have to learn.
 *
 * <h3>Two ceilings, and they are honest ceilings</h3>
 * <ul>
 *   <li>{@code astrum:crit_chance} is a {@code RangedAttribute} with a floor of {@code 0.0}, so a debuff larger than
 *       the target's chance lands it at <b>exactly zero</b> — it cannot go negative, and 未奏希's 18 points against a
 *       shipped 22-25% character leaves a few percent rather than a hole.</li>
 *   <li>{@code astrum:crit_damage} has a floor of {@code 1.0}, so 25% off never turns a crit into a weaker hit than the
 *       authored number.</li>
 * </ul>
 *
 * <h3>The one thing that can mask it, named rather than hidden</h3>
 * {@code Crit.chanceOf} and {@code Crit.damageOf} take the <b>best single source</b> over this mod's attribute and
 * every installed foreign crit attribute ({@code attributeslib}, {@code apothic_attributes},
 * {@code irons_spellbooks}). A debuff written to <em>our</em> attribute therefore lowers the winning number whenever
 * ours is the winning one, and a pack whose foreign crit attribute is higher will keep that higher value and the
 * debuff will read as doing nothing. That is the existing "best source" rule (<code>Crit</code>'s javadoc, the
 * human's 「B取最高」), not a second rule introduced here; the alternative — summing sources so a debuff always bites
 * — would make a full party's crit chance add up, which is the defect the ruling removed. None of the three foreign
 * attributes exists in the pack this build was tested against.
 *
 * <h2>Real removal, no tick listener</h2>
 * Vanilla calls {@code MobEffect.removeAttributeModifiers} when the instance expires or is removed, keyed by the fixed
 * modifier id declared in {@link CritDownEffect}. So the modifier cannot outlive its effect, and this mod contains no
 * expiry code, no sweep and no per-entity state. A recast <b>refreshes</b> rather than compounding for the same reason
 * {@code CritChanceUps} refreshes: one fixed id per (stat, effect).
 */
@ApiStatus.Internal
public final class CritDowns {

    /** Registry path of the crit-chance debuff: {@code astrum:crit_chance_down}. */
    public static final String CHANCE_PATH = "crit_chance_down";

    /** Registry path of the crit-damage debuff: {@code astrum:crit_damage_down}. */
    public static final String DAMAGE_PATH = "crit_damage_down";

    /** The chance debuff's registry id. */
    public static final ResourceLocation CHANCE_ID = InternalIds.id(CHANCE_PATH);

    /** The damage debuff's registry id. */
    public static final ResourceLocation DAMAGE_ID = InternalIds.id(DAMAGE_PATH);

    /** {@code effect.astrum.crit_chance_down} — the key vanilla resolves for the debuff's name. */
    public static final String CHANCE_NAME_KEY = "effect." + CHANCE_ID.toLanguageKey();

    /** {@code effect.astrum.crit_damage_down}. */
    public static final String DAMAGE_NAME_KEY = "effect." + DAMAGE_ID.toLanguageKey();

    /** Amplifier steps to one percentage point of <b>chance</b>: ten, i.e. a step is 0.1 point. */
    public static final double STEPS_PER_PERCENT = 10.0;

    /** The fraction of crit chance one amplifier step removes. */
    public static final double FRACTION_PER_STEP = 0.001;

    /**
     * The fraction of crit damage one amplifier step removes: {@code 0.01}, i.e. a step is <b>one percent</b>.
     *
     * <p>Whole percents, not tenths, and that is not an inconsistency: the two stats are read on different scales.
     * A chance is a probability in {@code [0, 1]}, so 2.5 percentage points has to be expressible; a crit multiplier
     * is a factor around 1.5, and 0.5% of it is below what anybody can see. These are the same two steps
     * {@code CritChanceUps} and {@code CritDamageUps} use in the other direction, so a buff and a debuff of one stat
     * cannot disagree about what a rung means.
     */
    public static final double DAMAGE_FRACTION_PER_STEP = 0.01;

    private CritDowns() {
    }

    /** {@return the crit-chance debuff's holder, or empty before the registry is up} */
    public static Optional<Holder<MobEffect>> chanceHolder() {
        return PartyEffects.critChanceDownHolder();
    }

    /** {@return the crit-damage debuff's holder, or empty before the registry is up} */
    public static Optional<Holder<MobEffect>> damageHolder() {
        return PartyEffects.critDamageDownHolder();
    }

    /**
     * {@return the amplifier that expresses {@code percent} percentage points of chance reduction}
     *
     * <p>Never throws: a hostile number in a pack becomes "no grant" (amplifier 0, refused by the caller) rather than
     * an exception on a damage sequence.
     *
     * @param percent the decrease in crit chance, in percentage points ({@code 18.0} = −18%)
     */
    public static int amplifierForChance(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0) {
            return 0;
        }
        return (int) Math.max(0.0, Math.min(AttackBuffRules.MAX_AMPLIFIER,
                Math.round(percent * STEPS_PER_PERCENT)));
    }

    /**
     * {@return the amplifier that expresses {@code percent} percent of crit-damage reduction}
     *
     * @param percent the decrease in crit damage, in percent of the crit multiplier ({@code 25.0} = −25%)
     */
    public static int amplifierForDamage(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0) {
            return 0;
        }
        return (int) Math.max(0.0, Math.min(AttackBuffRules.MAX_AMPLIFIER, Math.round(percent)));
    }

    /** {@return the fraction of crit chance {@code amplifier} removes — the effect's own modifier curve} */
    public static double chanceFractionFor(int amplifier) {
        int clamped = Math.max(0, Math.min(AttackBuffRules.MAX_AMPLIFIER, amplifier));
        return clamped * FRACTION_PER_STEP;
    }

    /** {@return the fraction of crit damage {@code amplifier} removes — the effect's own modifier curve} */
    public static double damageFractionFor(int amplifier) {
        int clamped = Math.max(0, Math.min(AttackBuffRules.MAX_AMPLIFIER, amplifier));
        return clamped * DAMAGE_FRACTION_PER_STEP;
    }

    /** {@return {@code amplifier} as the percentage points of chance a player would read} */
    public static double chancePercentForAmplifier(int amplifier) {
        return chanceFractionFor(amplifier) * 100.0;
    }

    /** {@return {@code amplifier} as the percentage of crit damage a player would read} */
    public static double damagePercentForAmplifier(int amplifier) {
        return damageFractionFor(amplifier) * 100.0;
    }

    /**
     * Applies the crit-chance debuff to one body.
     *
     * @param victim      the body the skill landed on
     * @param percent     the decrease, in percentage points
     * @param seconds     how long it lasts
     * @param characterId the character whose clause asked, for the log line
     * @return whether the victim holds the debuff after the call — a refresh counts
     */
    public static boolean applyChance(@Nullable LivingEntity victim, double percent, double seconds,
            @Nullable ResourceLocation characterId) {
        return apply(victim, chanceHolder(), amplifierForChance(percent), percent, seconds,
                chanceFractionFor(amplifierForChance(percent)), characterId, CHANCE_ID, "chance");
    }

    /**
     * Applies the crit-damage debuff to one body.
     *
     * @param victim      the body the skill landed on
     * @param percent     the decrease, in percent of the crit multiplier
     * @param seconds     how long it lasts
     * @param characterId the character whose clause asked, for the log line
     * @return whether the victim holds the debuff after the call
     */
    public static boolean applyDamage(@Nullable LivingEntity victim, double percent, double seconds,
            @Nullable ResourceLocation characterId) {
        return apply(victim, damageHolder(), amplifierForDamage(percent), percent, seconds,
                damageFractionFor(amplifierForDamage(percent)), characterId, DAMAGE_ID, "damage");
    }

    /**
     * The five shared steps: refuse a malformed number, resolve the holder, convert the clock, apply, report.
     *
     * <p>One body per call, because these are victim effects — the receiver set is the hit's target, decided by the
     * caller ({@code EffectExecutors} for a swing, {@code UbDamageSettlement} for an ultimate). There is deliberately
     * no ally sweep here: a crit debuff on one's own party would be a nerf with extra steps.
     */
    private static boolean apply(@Nullable LivingEntity victim, Optional<Holder<MobEffect>> effect,
            int amplifier, double percent, double seconds, double fraction, @Nullable ResourceLocation characterId,
            ResourceLocation id, String stat) {
        if (victim == null) {
            return false;
        }
        if (!Double.isFinite(percent) || !(percent > 0.0)) {
            Astrum.LOGGER.warn("astrum: {} wants {}% of crit {} down, which is not a debuff (it must be above 0); "
                    + "clause skipped. Check the character's effectParams.", id, percent, stat);
            return false;
        }
        if (amplifier <= 0) {
            Astrum.LOGGER.warn("astrum: {} of {} wants {}% of crit {}, which rounds to no modifier at all; the "
                    + "smallest expressible decrease is {}%.", id, characterId, percent, stat,
                    Math.max(100.0 * FRACTION_PER_STEP, 100.0 * DAMAGE_FRACTION_PER_STEP));
            return false;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            Astrum.LOGGER.warn("astrum: {} of {} wants {} seconds, so there is nothing to apply; clause skipped.",
                    id, characterId, seconds);
            return false;
        }
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: {} is not registered yet, so the debuff of {} was not applied; this means "
                    + "the mob effect registry missed {}.", id, characterId, id);
            return false;
        }
        try {
            // Amplifier, not amount: the effect's modifier curve turns the amplifier into the negative fraction, so a
            // recast under the same fixed modifier id refreshes instead of compounding (see CritDownEffect).
            victim.addEffect(new MobEffectInstance(effect.get(), ticks, amplifier, false, true, true));
            boolean holds = victim.hasEffect(effect.get());
            Astrum.LOGGER.debug("astrum: {} on {} -> crit {} -{}% ({} ticks, amplifier {}) holds={}", id,
                    victim.getName().getString(), stat, Math.round(fraction * 100.0), ticks, amplifier, holds);
            return holds;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: {} of {} threw on {}; the rest of the cast is unaffected", id,
                    characterId, victim.getName().getString(), e);
            return false;
        }
    }
}
