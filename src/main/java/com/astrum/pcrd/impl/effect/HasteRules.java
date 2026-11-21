package com.astrum.pcrd.impl.effect;

import org.jetbrains.annotations.ApiStatus;

/**
 * The 3-tier ladder behind {@link HasteEffect}: what one rung is worth, on <b>both</b> attributes.
 *
 * <h2>The ladder</h2>
 * <pre>
 *   tier 1  amplifier 0   +20% movement, +20% attack speed
 *   tier 2  amplifier 1   +40%, +40%
 *   tier 3  amplifier 2   +60%, +60%
 * </pre>
 *
 * <p>Three rungs, not five: 「行动速度」 is a small utility buff in the shipped characters' text
 * (「味方全体の行動速度小アップ」), and the brief fixes the count. The step is 20% because it is the same
 * step vanilla's {@code speed} uses ({@code +20%} a level), so a tier-1 haste and a {@code speed I} move
 * the party at the same pace — the mod's tier simply also carries the attack half.
 *
 * <h2>Why the amplifier is the tier minus one</h2>
 * Vanilla's amplifier is 0-based and an author counts tiers from 1. Doing the conversion in one place
 * means no caller has to remember it, and the alternative — letting the pack write the amplifier — would
 * make tier and amplifier two competing scales for one number.
 */
@ApiStatus.Internal
public final class HasteRules {

    /** How many rungs this effect has. {@code EffectTiers} declares the same 3. */
    public static final int MAX_TIER = 3;

    /** One rung's bonus, as a fraction of the attribute's total: 20%. */
    public static final double STEP = 0.20;

    private HasteRules() {
    }

    /**
     * {@return the fraction {@code amplifier} adds to <b>each</b> of the two attributes}
     *
     * <p>Never throws and never answers a negative value: this is the level curve of a modifier, evaluated
     * on every attribute recomputation, so a hostile amplifier must become a clamped bonus rather than a
     * movement <em>penalty</em> on a buff.
     *
     * @param amplifier the instance's amplifier, {@code 0} = one step
     */
    public static double fractionForAmplifier(int amplifier) {
        int clamped = Math.max(0, Math.min(MAX_TIER - 1, amplifier));
        return STEP * (clamped + 1);
    }

    /**
     * {@return the amplifier a 1-based tier is expressed as}
     *
     * @param tier the tier, 1-based; clamped into {@code [1, MAX_TIER]}
     */
    public static int amplifierForTier(int tier) {
        return Math.max(0, Math.min(MAX_TIER, tier) - 1);
    }

    /** {@return the fraction tier {@code tier} adds to each attribute} */
    public static double fractionForTier(int tier) {
        return fractionForAmplifier(amplifierForTier(tier));
    }
}
