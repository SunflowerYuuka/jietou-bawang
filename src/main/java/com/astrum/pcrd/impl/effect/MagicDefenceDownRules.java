package com.astrum.pcrd.impl.effect;

import org.jetbrains.annotations.ApiStatus;

/**
 * The numbers behind {@link MagicDefenceDownEffect}: how a datapack <b>tier</b> becomes "this body takes more
 * magic damage".
 *
 * <h2>The encoding, stated once</h2>
 * The tier travels in the integer amplifier, exactly as it does for {@code DefenceDownRules}:
 *
 * <pre>
 *   amplifier = tier - 1                 // tier 1 -> amplifier 0
 *   penalty   = STEP × tier              // 20/40/60/80% more magic damage
 *   taken     = 1 + penalty              // 1.20 .. 1.80
 * </pre>
 *
 * <p>Four rungs and 20% each, topping at <b>+80%</b> at tier 4 — the human's instruction is 「Tiered 4 @ 20%,
 * same ladder as {@code DEFENCE_DOWN}」, and {@code EffectTiers} declares the same {@code Rule(4, 0.20)} for
 * both. {@code EffectTiersTest} asserts the two tables agree, so an author picking a rung sees one ladder.
 *
 * <h2>Why the quantity is a <em>damage taken</em> multiplier and not a fraction of an attribute</h2>
 * Because there is no magic armour attribute to take a fraction of. Minecraft has one {@code ARMOR}, and it
 * does not apply to damage types tagged {@code minecraft:bypasses_armor} — the mod files its magic damage as
 * {@code astrum:magic}, which is <b>not</b> in that tag, so {@code ARMOR} would in fact reduce it… by the
 * physical armour curve, which is precisely the attribute the physical debuff already lowers. Using it here
 * would make the two debuffs do the same thing and would leave 「魔法防御力ダウン」 implemented as a physical
 * armour break — the silent substitution {@link com.astrum.pcrd.api.v1.EffectType#MAGIC_DEFENCE_DOWN} forbids.
 *
 * <p>So the honest counterpart of "less magic defence" is "more magic damage taken", which is the exact
 * mirror of {@code DefenceRules.reductionFor} (a fraction <em>removed</em>) and is applied at the same one
 * place, {@code DamageReduction}. One rule, two directions.
 *
 * <h2>Amplifier 0 is a real debuff</h2>
 * {@code tierForAmplifier(0)} is tier 1 → +20%, never {@code +0%}. A defence-down hook that computed to
 * nothing would be a skill whose main function silently does nothing — the same property
 * {@code DefenceDownRules} documents, for the same reason.
 */
@ApiStatus.Internal
public final class MagicDefenceDownRules {

    /** How much more magic damage one tier causes, as a fraction: 20%. */
    public static final double STEP = 0.20;

    /** How many rungs this effect has. {@code EffectTiers} declares the same 4. */
    public static final int MAX_TIER = 4;

    /** The deepest penalty, +80% magic damage taken at tier 4. */
    public static final double MAX_PENALTY = STEP * MAX_TIER;

    /** The largest damage multiplier any tier produces: {@code 1 + MAX_PENALTY} = 1.8. */
    public static final double MAX_TAKEN_MULTIPLIER = 1.0 + MAX_PENALTY;

    /** Vanilla's own ceiling for an effect amplifier. */
    public static final int MAX_AMPLIFIER = 255;

    private MagicDefenceDownRules() {
    }

    /**
     * {@return the damage-increase {@code amplifier} expresses, as a positive fraction}
     *
     * <p>Never throws and never answers a non-positive value: this runs on the effect's own level curve,
     * where a hostile number in a pack must become a clamped penalty rather than an exception or — worse — a
     * magic damage <em>reduction</em> wearing a debuff's name.
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
        return STEP * Math.clamp(tier, 1, MAX_TIER);
    }

    /**
     * {@return the multiplier a 1-based tier applies to magic damage taken}
     *
     * <p>{@code 1.20} at tier 1 .. {@code 1.80} at tier 4. This is the number {@code DamageReduction}
     * multiplies by, and the number whose {@code -1} the log line reports as a percentage.
     *
     * @param tier the tier, 1-based; clamped into {@code [1, MAX_TIER]}
     */
    public static double takenMultiplierForTier(int tier) {
        return 1.0 + penaltyForTier(tier);
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
     * silent drop this project forbids — but {@code TieredEffects} logs the clamp once.
     *
     * @param tier the tier as written in the datapack
     */
    public static boolean isRepresentable(int tier) {
        return tier >= 1 && tier <= MAX_TIER;
    }
}
