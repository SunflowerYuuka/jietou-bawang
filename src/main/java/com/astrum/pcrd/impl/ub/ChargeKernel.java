package com.astrum.pcrd.impl.ub;

import org.jetbrains.annotations.ApiStatus;

/**
 * The UB charge arithmetic (spec §5.4), as pure functions: plain {@code double} in, plain
 * {@code double} out.
 *
 * <h2>Why nothing else lives here</h2>
 * There is no {@code ServerPlayer}, no {@code Level} and no config object in any signature. The
 * value that decides how fast the bar fills ({@code ubChargePerDamage}, {@code ubChargePerSecond}, …)
 * is read by the caller and passed in, so this class cannot cache a config value at class-init time
 * — the failure spec §8.4 warns about — and it stays testable without booting a game.
 *
 * <p>The same split keeps the player's one gauge out of here: {@code UbState.charge} is a single
 * value per player (controller ruling on spec §4.3/§5.4), never one per character, so the kernel has
 * no notion of a character at all.
 *
 * <h2>The charge-rate multiplier lives <em>here</em>, and that is the point</h2>
 * {@code CHARGE_RATE_UP} (「TP上昇」) multiplies how fast a player's gauge fills. Charge is credited
 * from exactly four sources, and all four already end in this class: <b>damage dealt</b>, <b>damage
 * taken</b>, <b>a kill</b> and the <b>passive trickle</b>. So the multiplier is applied by the four
 * {@code add*} methods and nowhere else — one rule, four beneficiaries, no per-source copy to forget.
 * The alternative (each source multiplying its own delta) is four chances for one source to be missed,
 * and the exact defect the brief's "prove all four honour it" test exists to catch.
 *
 * <p>Every {@code add*} therefore takes a {@code rate}: the player's <b>current</b> multiplier, resolved
 * once per credit by {@code ChargeRate.multiplierOf(player)} from the effects actually on the entity.
 * {@code 1.0} is the identity — a player with no {@code CHARGE_RATE_UP} effect is unaffected, which is
 * why every pre-existing caller keeps its numbers exactly.
 *
 * <h2>Clamping is part of every path</h2>
 * {@link #clamp} is not a helper a caller may forget: {@link #addDealt}, {@link #addTaken},
 * {@link #addKill} and {@link #add} all end in it. A gauge that could hold {@code 103.7} would make
 * {@link #isFull} false forever and the ultimate unreachable — a silent, permanent loss of the
 * character's core mechanic, which is the same reasoning {@code UbState}'s codec uses to reject a
 * hostile saved value.
 *
 * <p>The multiplier never bypasses that clamp: {@code 2.25×} on a full bar is still a full bar, and a
 * rate that would push the gauge past {@link #MAX_CHARGE} is clamped like any other gain. The
 * multiplier scales the <em>gain</em>, never the ceiling — the same "递减/加成作用于量，绝不作用于质"
 * rule the effect quantities follow.
 */
@ApiStatus.Internal
public final class ChargeKernel {

    /** Empty gauge: the floor every drain stops at. */
    public static final double MIN_CHARGE = 0.0;

    /** Full gauge: the value the ultimate consumes and the only value {@link #isFull} accepts. */
    public static final double MAX_CHARGE = 100.0;

    /**
     * The identity charge-rate multiplier: no {@code CHARGE_RATE_UP} effect, no change.
     *
     * <p>The value every pre-existing caller implicitly had, and the number {@link #rateFor} answers for
     * an entity with no instance of the effect.
     */
    public static final double NEUTRAL_RATE = 1.0;

    /**
     * What one {@code CHARGE_RATE_UP} level is worth: {@code +25%}.
     *
     * <p>{@link com.astrum.pcrd.api.v1.EffectTiers} declares the same step for that effect, and
     * {@code ChargeKernelTest} asserts the two agree — the tier table says what an author picks, this class
     * says what the engine computes, and they must be one number.
     */
    public static final double RATE_STEP = 0.25;

    /**
     * The highest amplifier {@code CHARGE_RATE_UP} may reach: {@code EffectTiers} gives it 5 rungs, and five
     * rungs above amplifier 0 is amplifier {@code 4}. Kept here so the level curve clamps to the ladder rather
     * than to vanilla's 255, which would let a hostile pack ask for a 64× charge rate.
     */
    public static final int MAX_AMPLIFIER_LEVEL = 4;

    /** Minecraft's fixed tick rate. The single place the {@code 20} appears. */
    private static final double TICKS_PER_SECOND = 20.0;

    private ChargeKernel() {
    }

    /**
     * {@return the charge after applying {@code dealt} damage at {@code perDamage} charge each}
     *
     * <p>Damage dealt is the main charge source (spec §5.4).
     *
     * @param rate the player's current charge-rate multiplier, {@code 1.0} for none
     */
    public static double addDealt(double charge, double dealt, double perDamage, double rate) {
        return add(charge, dealt * perDamage, rate);
    }

    /**
     * {@return the charge after applying {@code taken} damage at {@code perTaken} charge each}
     *
     * <p>Damage taken is the secondary source, deliberately at a lower coefficient than damage dealt
     * so a player cannot farm charge by standing in fire at the same rate as by fighting.
     *
     * @param rate the player's current charge-rate multiplier, {@code 1.0} for none
     */
    public static double addTaken(double charge, double taken, double perTaken, double rate) {
        return add(charge, taken * perTaken, rate);
    }

    /**
     * {@return the charge after a kill, which grants a flat {@code perKill}}
     *
     * @param rate the player's current charge-rate multiplier, {@code 1.0} for none
     */
    public static double addKill(double charge, double perKill, double rate) {
        return add(charge, perKill, rate);
    }

    /**
     * {@return the charge after a raw delta, scaled by {@code rate} and clamped}
     *
     * <p>This is the primitive every other {@code add*} composes, and the form the charge event uses:
     * {@code UbChargeEvent.amount()} is a delta, so applying a listener's rewritten amount goes
     * through here rather than through damage-shaped arithmetic.
     *
     * <p><b>The one place the {@code CHARGE_RATE_UP} multiplier is applied.</b> {@code UbCharge.apply}
     * resolves the player's rate once and calls this, so every source that credits charge — all four of
     * them — is multiplied by it without any of them knowing the effect exists.
     *
     * @param delta charge points to apply; negative values drain
     * @param rate  the player's current charge-rate multiplier, {@code 1.0} for none
     */
    public static double add(double charge, double delta, double rate) {
        return clamp(charge + scaledByRate(delta, rate));
    }

    /**
     * {@return the rate that is safe arithmetic: {@code rate} itself, or {@link #NEUTRAL_RATE}}
     *
     * <p>{@code NaN}, an infinity and a non-positive rate all answer the identity. This is not
     * defensiveness for its own sake: the rate comes from an effect instance whose amplifier a pack
     * controls, and a {@code NaN} rate would make every comparison false — a gauge that neither fills nor
     * resets — while a {@code 0} rate would silently switch charging <em>off</em>, which is the same
     * class of silent loss {@link #clamp} exists to prevent.
     *
     * @param rate a declared multiplier
     */
    public static double sanitizeRate(double rate) {
        return Double.isFinite(rate) && rate > 0.0 ? rate : NEUTRAL_RATE;
    }

    /**
     * {@return {@code delta × rate}, with a sanitised rate}
     *
     * <p>Extracted so {@link #add} and the shaped {@code add*} methods cannot disagree about the guard.
     *
     * @param delta charge points before the multiplier
     * @param rate  the player's current charge-rate multiplier
     */
    public static double scaledByRate(double delta, double rate) {
        return delta * sanitizeRate(rate);
    }

    /**
     * {@return the charge-rate multiplier a {@code CHARGE_RATE_UP} amplifier stands for}
     *
     * <p>The one amplifier-to-multiplier mapping, so the effect's registration and the executor read the
     * same numbers. <b>Amplifier 0 is tier 1, i.e. {@code 1.25×}</b>: the same "the lowest rung is already one
     * step" rule {@code DefenceRules} and {@code AttackAreaRules} follow, and the reason is the same — a
     * registered buff whose weakest instance did nothing would put a HUD entry on the player saying something
     * untrue. "No bonus" is not a value of this function at all; it is
     * {@link #NEUTRAL_RATE}, which {@code ChargeRates.multiplierOf} answers when the player has <b>no
     * instance</b> of the effect. Two different questions, two different answers.
     *
     * <p>An amplifier outside vanilla's range clamps into {@code [0, MAX_AMPLIFIER_LEVEL]}, and a negative one
     * cannot produce a rate below tier 1 — a "TP up" that made charge <em>slower</em> would be a debuff
     * wearing the buff's name.
     *
     * @param amplifier the instance's amplifier, {@code 0} = tier 1 = {@code 1.25×}
     */
    public static double rateForAmplifier(int amplifier) {
        int clamped = Math.max(0, Math.min(MAX_AMPLIFIER_LEVEL, amplifier));
        return NEUTRAL_RATE + (clamped + 1) * RATE_STEP;
    }

    /**
     * {@return the amplifier that expresses {@code rate}}
     *
     * <p>The inverse of {@link #rateForAmplifier}, and the function the effect's level curve uses, so the
     * mapping exists once. Rounded to the nearest level rather than truncated, for the reason
     * {@code AttackBuffRules.amplifierFor} gives: a truncation makes two different tiers the same.
     *
     * <p>{@code rate <= 1.0} answers {@code 0} — the weakest rung — rather than a negative amplifier, for the
     * same "a rate below the identity is a debuff" reason {@link #sanitizeRate} refuses one.
     *
     * @param rate a multiplier, {@code 1.25} = tier 1
     */
    public static int amplifierForRate(double rate) {
        if (!Double.isFinite(rate) || rate <= NEUTRAL_RATE) {
            return 0;
        }
        double levels = Math.round((rate - NEUTRAL_RATE) / RATE_STEP) - 1;
        return (int) Math.max(0.0, Math.min(MAX_AMPLIFIER_LEVEL, levels));
    }

    /**
     * {@return the per-tick share of {@code perSecond}}
     *
     * <p>The division by {@link #TICKS_PER_SECOND} happens here and nowhere else: callers pass the
     * config's per-second number and add the result once per tick. Dividing again at the call site
     * would multiply the trickle by 400.
     *
     * <p>{@code impl.ub.PassiveCharge} is the production caller: it accumulates this share per tick and
     * credits whole points, so the 0.01 a default {@code ubChargePerSecond} of 0.2 produces is not
     * rounded away. {@code ChargeKernelTest} pins the division; the accumulation is pinned by
     * {@code PassiveChargeTest}.
     */
    public static double naturalRegen(double perSecond) {
        return perSecond / TICKS_PER_SECOND;
    }

    /**
     * {@return {@code charge} pulled into {@code [0, 100]}}
     *
     * <p>{@code NaN} answers the floor rather than propagating. It is the one value that makes every
     * comparison false, so a gauge holding it would neither fill, reset, nor report full — a
     * permanent silent loss of the mechanic with no error anywhere. Infinity clamps like any other
     * out-of-range number.
     */
    public static double clamp(double charge) {
        if (Double.isNaN(charge)) {
            return MIN_CHARGE;
        }
        if (charge < MIN_CHARGE) {
            return MIN_CHARGE;
        }
        return Math.min(charge, MAX_CHARGE);
    }

    /**
     * {@return {@code true} only when the gauge holds exactly {@link #MAX_CHARGE}}
     *
     * <p>Not a threshold: {@code 99.999} is not full, because a threshold would let the ultimate fire
     * on a bar the HUD draws as one pixel short of full.
     */
    public static boolean isFull(double charge) {
        return charge == MAX_CHARGE;
    }
}
