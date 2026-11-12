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
 * <h2>Clamping is part of every path</h2>
 * {@link #clamp} is not a helper a caller may forget: {@link #addDealt}, {@link #addTaken},
 * {@link #addKill} and {@link #add} all end in it. A gauge that could hold {@code 103.7} would make
 * {@link #isFull} false forever and the ultimate unreachable — a silent, permanent loss of the
 * character's core mechanic, which is the same reasoning {@code UbState}'s codec uses to reject a
 * hostile saved value.
 */
@ApiStatus.Internal
public final class ChargeKernel {

    /** Empty gauge: the floor every drain stops at. */
    public static final double MIN_CHARGE = 0.0;

    /** Full gauge: the value the ultimate consumes and the only value {@link #isFull} accepts. */
    public static final double MAX_CHARGE = 100.0;

    /** Minecraft's fixed tick rate. The single place the {@code 20} appears. */
    private static final double TICKS_PER_SECOND = 20.0;

    private ChargeKernel() {
    }

    /**
     * {@return the charge after applying {@code dealt} damage at {@code perDamage} charge each}
     *
     * <p>Damage dealt is the main charge source (spec §5.4).
     */
    public static double addDealt(double charge, double dealt, double perDamage) {
        return add(charge, dealt * perDamage);
    }

    /**
     * {@return the charge after applying {@code taken} damage at {@code perTaken} charge each}
     *
     * <p>Damage taken is the secondary source, deliberately at a lower coefficient than damage dealt
     * so a player cannot farm charge by standing in fire at the same rate as by fighting.
     */
    public static double addTaken(double charge, double taken, double perTaken) {
        return add(charge, taken * perTaken);
    }

    /** {@return the charge after a kill, which grants a flat {@code perKill}} */
    public static double addKill(double charge, double perKill) {
        return add(charge, perKill);
    }

    /**
     * {@return the charge after a raw delta, clamped}
     *
     * <p>This is the primitive every other {@code add*} composes, and the form the charge event uses:
     * {@code UbChargeEvent.amount()} is a delta, so applying a listener's rewritten amount goes
     * through here rather than through damage-shaped arithmetic.
     *
     * @param delta charge points to apply; negative values drain the gauge
     */
    public static double add(double charge, double delta) {
        return clamp(charge + delta);
    }

    /**
     * {@return the per-tick share of {@code perSecond}}
     *
     * <p>The division by {@link #TICKS_PER_SECOND} happens here and nowhere else: callers pass the
     * config's per-second number and add the result once per tick. Dividing again at the call site
     * would multiply the trickle by 400.
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
