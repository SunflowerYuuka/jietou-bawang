package com.astrum.pcrd.impl.effect;

import org.jetbrains.annotations.ApiStatus;

/**
 * The numbers behind {@link DefenceDownEffect}: how a datapack <b>tier</b> becomes an armour penalty.
 *
 * <h2>The encoding, stated once</h2>
 * Vanilla carries a per-instance knob in exactly one field — the integer amplifier. So the tier travels
 * there and the arithmetic is fixed here:
 *
 * <pre>
 *   amplifier = tier - 1                 // tier 1 -> amplifier 0
 *   penalty   = STEP × tier              // amplifier 0 -> 20% of armour
 * </pre>
 *
 * <p>Four rungs and 20% each, <b>topping at 80%</b> at tier 4. The 20% step matches the generic ladder
 * ({@code EffectTiers} declares the same 4 × 0.20), and 80% is deliberately not 100: removing an armour
 * attribute entirely would make the victim's armour <em>negative</em> on a mob with a weak base, and an
 * armour break should not be a one-shot. {@code EffectTiersTest} asserts the two tables agree.
 *
 * <h2>What changed from the previous encoding</h2>
 * This class used to hold {@code STEP = 0.15} and {@code penalty = (amplifier + 1) × STEP}, i.e. an
 * amplifier ladder with no top. The generic-effect task replaced both: the rung is now a <b>tier</b> (an
 * author's unit, 1-based) and the ladder has a declared end, so {@code "tier": 5} on this effect is a
 * <em>clamped</em> value with a warning rather than a fifth, larger penalty nobody designed.
 *
 * <p>Amplifier {@code 0} is still the weakest debuff the effect can express and is still a real penalty —
 * the property that matters, and the opposite of {@code AttackBuffRules} where amplifier 0 is "+0%". A
 * defence-down hook that computed to nothing would be a skill whose main function silently does nothing.
 */
@ApiStatus.Internal
public final class DefenceDownRules {

    /** How much of the target's armour one tier removes, as a fraction: 20%. */
    public static final double STEP = 0.20;

    /** How many rungs this effect has. {@code EffectTiers} declares the same 4. */
    public static final int MAX_TIER = 4;

    /** The deepest penalty, 80% of armour at tier 4. */
    public static final double MAX_PENALTY = STEP * MAX_TIER;

    /** Vanilla's own ceiling for an effect amplifier. */
    public static final int MAX_AMPLIFIER = 255;

    private DefenceDownRules() {
    }

    /**
     * {@return the armour penalty {@code amplifier} expresses, as a positive fraction}
     *
     * <p>Never throws and never answers a non-positive value: this runs on the effect's level curve, where
     * a hostile number in a pack must become a clamped penalty rather than an exception or — worse — an
     * armour <em>buff</em> from a negative amplifier.
     *
     * @param amplifier the instance's amplifier; values below {@code 0} are treated as {@code 0}
     */
    public static double penaltyForAmplifier(int amplifier) {
        return STEP * tierForAmplifier(amplifier);
    }

    /**
     * {@return the penalty a 1-based <b>tier</b> applies}
     *
     * @param tier the tier, 1-based; clamped into {@code [1, MAX_TIER]}
     */
    public static double penaltyForTier(int tier) {
        int clamped = Math.max(1, Math.min(MAX_TIER, tier));
        return STEP * clamped;
    }

    /** {@return the amplifier a 1-based tier is expressed as} */
    public static int amplifierForTier(int tier) {
        return Math.max(0, Math.min(MAX_TIER, tier) - 1);
    }

    /** {@return the tier an amplifier expresses — the inverse of {@link #amplifierForTier}} */
    public static int tierForAmplifier(int amplifier) {
        int clamped = Math.max(0, Math.min(MAX_AMPLIFIER, amplifier));
        return Math.max(1, Math.min(MAX_TIER, clamped + 1));
    }

    /**
     * {@return whether a declared tier is one this effect can express unchanged}
     *
     * <p>False for anything outside {@code [1, MAX_TIER]}, so an author who wrote {@code 7} is told rather
     * than quietly given the ceiling. The hook still runs at the clamped tier — refusing it would be the
     * silent drop this project forbids — but {@code DefenceDown}'s executor logs the clamp once.
     *
     * @param tier the tier as written in the datapack
     */
    public static boolean isRepresentable(int tier) {
        return tier >= 1 && tier <= MAX_TIER;
    }
}
