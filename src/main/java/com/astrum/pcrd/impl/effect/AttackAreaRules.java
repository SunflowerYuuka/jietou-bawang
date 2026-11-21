package com.astrum.pcrd.impl.effect;

import org.jetbrains.annotations.ApiStatus;

/**
 * The 5-tier ladder behind {@link AttackAreaEffect}: +20% attack damage a tier, to +100%.
 *
 * <h2>The ladder</h2>
 * <pre>
 *   tier 1  amplifier 0   +20%      tier 4  amplifier 3   +80%
 *   tier 2  amplifier 1   +40%      tier 5  amplifier 4   +100%  (double damage)
 *   tier 3  amplifier 2   +60%
 * </pre>
 *
 * <p>Tier 5 doubling a player's damage is <b>intended</b>: it is the same ceiling {@code AttackBuffRules}
 * allows through its own encoding ({@code 2.0} is well inside the 1.00–3.55 range the amplifier can
 * express), and the brief names it. What is <em>not</em> allowed is a rung beyond it, which the clamp
 * below refuses.
 *
 * <h2>The step is 20, not the 1/100 {@code AttackBuffRules} uses</h2>
 * That class encodes an arbitrary ratio a pack wrote ({@code 1.47} → amplifier 47), so it needs a fine
 * grid. This one encodes a <em>tier</em>, and there are exactly five — so amplifier {@code N - 1} is tier
 * {@code N} and the mapping is exact with no rounding at all. A coarse grid is a feature here: an author
 * cannot express a 37% buff, because the schema says tiers.
 */
@ApiStatus.Internal
public final class AttackAreaRules {

    /** How many rungs this effect has; {@code EffectTiers} declares the same 5. */
    public static final int MAX_TIER = 5;

    /** One rung's bonus on the <b>physical</b> stat, as a fraction of the attribute's total: 20%. */
    public static final double STEP = 0.20;

    /**
     * One rung's bonus on the <b>magic</b> stat: <b>26%</b> — a third more than {@link #STEP}.
     *
     * <h2>Why the two steps differ, in arithmetic</h2>
     * The human's rule is 「物理攻击提升和魔法攻击提升的两个buff分开，因为物理攻击通常情况下等同于攻击伤害，而
     * 魔法攻击的数值是0开始的，所以魔法攻击提升buff的数值会比物理攻击提升更高，大约5%-10%」, and when shown
     * 25% / 26% / 30% he chose <b>26%</b>.
     *
     * <p>The two rungs cannot be the same number because they are fractions of two different quantities:
     *
     * <ul>
     *   <li><b>physical</b> — {@code Attributes.ATTACK_DAMAGE}, whose base for a player is about
     *       {@code 1.0-2.0}. {@code ADD_MULTIPLIED_TOTAL} of {@code 0.20} is a real absolute gain.</li>
     *   <li><b>magic</b> — {@code astrum:magic_damage}, whose base is <b>0</b>
     *       ({@code MagicAttack.DEFAULT_VALUE}) and which is written as <b>flat points</b>,
     *       {@code fraction × 2000} ({@code MagicAttackAreaEffect.pointsForAmplifier}). A multiplicative
     *       modifier on a base of 0 changes nothing, which is why the magic side cannot use the same
     *       operation — see that class's javadoc.</li>
     * </ul>
     *
     * <p>So a magic rung is worth {@code 0.26 × 2000 = 520} points, giving a damage scale of
     * {@code 1 + 0.26N} at tier N against physical's {@code 1 + 0.20N}: at tier 5 that is
     * <b>2.60 vs 2.00</b>. The 2-percentage-point edge is {@code 2/20 = 10%} stronger per rung, i.e. the
     * top of his 5-10% band, and 26% is the midpoint in round numbers. He accepted the tier-5 gap
     * explicitly.
     *
     * <p>The pair stays distinguishable at <b>every</b> tier ({@code 1.2N} vs {@code 1.26N}), which is
     * what a shared step could not have been once the steps were allowed to differ at all.
     */
    public static final double MAGIC_STEP = 0.26;

    private AttackAreaRules() {
    }

    /**
     * {@return the fraction {@code amplifier} adds to <b>physical</b> attack damage}
     *
     * <p>Evaluated by vanilla on every attribute recomputation, so it must never throw and never answer a
     * smaller number than the one below it. A negative amplifier would be an attack <em>debuff</em> on a
     * beneficial effect, so it clamps to tier 1 — never to zero, because a registered buff that adds
     * nothing puts a HUD entry on the player saying something untrue.
     *
     * @param amplifier the instance's amplifier, {@code 0} = +20%
     */
    public static double fractionForAmplifier(int amplifier) {
        return STEP * (clampedTierFor(amplifier));
    }

    /**
     * {@return the fraction {@code amplifier} adds to <b>magic</b> attack, in points of
     * {@code astrum:magic_damage} divided by {@code MagicAttack.REFERENCE_ATTACK}}
     *
     * <p>Same amplifier-to-rung mapping as the physical half — one ladder, two steps — so tier N means
     * rung N on both stats and only the step differs. See {@link #MAGIC_STEP} for why it must.
     *
     * @param amplifier the instance's amplifier, {@code 0} = +26%
     */
    public static double magicFractionForAmplifier(int amplifier) {
        return MAGIC_STEP * (clampedTierFor(amplifier));
    }

    /**
     * {@return the amplifier a 1-based tier is expressed as}
     *
     * @param tier the tier, 1-based; clamped into {@code [1, MAX_TIER]}
     */
    public static int amplifierForTier(int tier) {
        return Math.max(0, Math.min(MAX_TIER, tier) - 1);
    }

    /** {@return the fraction tier {@code tier} adds to the physical stat} */
    public static double fractionForTier(int tier) {
        return fractionForAmplifier(amplifierForTier(tier));
    }

    /** {@return the fraction tier {@code tier} adds to the magic stat} */
    public static double magicFractionForTier(int tier) {
        return magicFractionForAmplifier(amplifierForTier(tier));
    }

    /**
     * {@return the 1-based rung an amplifier names, clamped into {@code [1, MAX_TIER]}}
     *
     * <p>Hoisted so both steps clamp identically: two copies of {@code Math.max(0, Math.min(...))} is how
     * the physical and magic halves would eventually disagree at the boundaries.
     */
    private static int clampedTierFor(int amplifier) {
        return Math.max(0, Math.min(MAX_TIER - 1, amplifier)) + 1;
    }
}
