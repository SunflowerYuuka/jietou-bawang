package com.astrum.pcrd.impl.ub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The passive trickle behind {@code ubChargePerSecond} (spec §5.4 每秒微量自然回复，兜底), pinned at the
 * three places a "charge per second" can go wrong: the rate, the remainder, and the ceiling.
 *
 * <h2>Why the pure {@code step} is what is tested</h2>
 * {@link PassiveCharge#onPlayerTick} needs a {@code ServerPlayer}, a level, an attachment store and a
 * loaded config file; none of that can exist in a unit test, and a test that mocked them would assert its
 * own mock. Every rule that can be wrong, though, is in {@code step(charge, carry, perSecond)} — so the
 * listener is a three-line adapter over this method, and the method is exercised directly.
 *
 * <p>One run of ticks is simulated here in the same shape the listener runs it: take the state the
 * previous tick produced and feed it back in. That is what makes the remainder assertion meaningful —
 * asserting a single tick's float value would pass for an implementation that drops the remainder on the
 * next tick.
 */
class PassiveChargeTest {

    /** Runs {@code ticks} ticks from an empty gauge at {@code perSecond}, returning the final state. */
    private static PassiveCharge.Step run(int ticks, double perSecond) {
        PassiveCharge.Step state = new PassiveCharge.Step(0.0, 0.0);
        for (int i = 0; i < ticks; i++) {
            state = PassiveCharge.step(state.charge(), state.carry(), perSecond);
        }
        return state;
    }

    /**
     * The rate is the configured one: {@code 4}/s is {@code 0.2}/tick, so five ticks pay exactly one
     * point and twenty ticks pay four.
     *
     * <p>Two window lengths on purpose: one point could come from a lucky rounding at a single boundary,
     * while the second window also proves the accumulator keeps running after a payout instead of
     * restarting.
     */
    @Test
    void chargeAccruesAtTheConfiguredRate() {
        assertEquals(1.0, run(5, 4.0).charge(), 1e-9, "4/s is 0.2/tick: five ticks are one point");
        assertEquals(4.0, run(20, 4.0).charge(), 1e-9, "twenty ticks of 4/s are four points");
        assertEquals(20.0, run(20, 20.0).charge(), 1e-9, "20/s is 1/tick: twenty ticks are twenty points");
    }

    /**
     * A fraction is never lost: the default {@code 0.2}/s is {@code 0.01}/tick, and truncating each tick
     * would credit exactly nothing forever. The first point must arrive on the hundredth tick, and the
     * remainder after it must continue to accumulate rather than restart.
     */
    @Test
    void aFractionalRemainderIsNotLost() {
        // The first ninety-nine ticks pay nothing but must bank the fraction.
        PassiveCharge.Step before = run(99, 0.2);
        assertEquals(0.0, before.charge(), 1e-9, "0.01/tick cannot pay a whole point in 99 ticks");
        assertEquals(0.99, before.carry(), 1e-6, "and the fraction must have accumulated, not vanished");

        // The hundredth tick pays that banked point.
        PassiveCharge.Step hundred = PassiveCharge.step(before.charge(), before.carry(), 0.2);
        assertEquals(1.0, hundred.charge(), 1e-9, "the hundredth tick pays the banked fraction");
        assertTrue(hundred.carry() < 1e-6, "and leaves nothing behind, got " + hundred.carry());

        // Two hundred ticks are two points — the accumulator kept running after the first payout.
        assertEquals(2.0, run(200, 0.2).charge(), 1e-9);
        // A rate that is not a whole multiple of the tick deliberately still converges: 3/s is 0.15/tick.
        assertEquals(3.0, run(20, 3.0).charge(), 1e-9);
    }

    /** The cap is respected, and a full bar stops accruing instead of hoarding a carry. */
    @Test
    void theCapIsRespected() {
        // 20/s is 1 point per tick, so a gauge one half-point short lands exactly on the cap.
        PassiveCharge.Step full = PassiveCharge.step(99.5, 0.0, 20.0);
        assertEquals(ChargeKernel.MAX_CHARGE, full.charge(), 1e-9, "99.5 + 1 point stops at the cap");
        assertEquals(0.0, full.carry(), 1e-9);

        // At the cap already: no movement, and no remainder is banked for later.
        PassiveCharge.Step atCap = PassiveCharge.step(ChargeKernel.MAX_CHARGE, 0.7, 4.0);
        assertEquals(ChargeKernel.MAX_CHARGE, atCap.charge(), 1e-9);
        assertEquals(0.0, atCap.carry(), 1e-9, "a full bar must not hoard charge it cannot store");

        // Many ticks at the cap stay at the cap rather than overflowing past it.
        PassiveCharge.Step state = new PassiveCharge.Step(ChargeKernel.MAX_CHARGE, 0.0);
        for (int i = 0; i < 500; i++) {
            state = PassiveCharge.step(state.charge(), state.carry(), 20.0);
        }
        assertEquals(ChargeKernel.MAX_CHARGE, state.charge(), 1e-9);

        // And the last stretch to the cap is not lost to the ceiling: 200 ticks at the default rate
        // cannot pass it either.
        assertTrue(run(20_000, 100.0).charge() <= ChargeKernel.MAX_CHARGE);
    }

    /** {@code 0} is a supported setting: nothing accrues, and nothing is banked for a later change. */
    @Test
    void itDoesNotRunWhenTheOptionIsZero() {
        PassiveCharge.Step zero = run(1000, 0.0);
        assertEquals(0.0, zero.charge(), 1e-9, "a disabled trickle must not charge anything");
        assertEquals(0.0, zero.carry(), 1e-9);

        // A carry from an earlier tick must not be banked while the option is off: turning the trickle
        // back on later starts from zero, which is what the listener's carry.remove() guarantees.
        PassiveCharge.Step withCarry = PassiveCharge.step(10.0, 0.9, 0.0);
        assertEquals(10.0, withCarry.charge(), 1e-9);
        assertEquals(0.0, withCarry.carry(), 1e-9);

        // Non-finite and negative values are the same state as zero, not a way to drain the gauge.
        assertEquals(10.0, PassiveCharge.step(10.0, 0.5, -1.0).charge(), 1e-9);
        assertEquals(0.0, PassiveCharge.step(10.0, 0.5, -1.0).carry(), 1e-9);
        assertEquals(10.0, PassiveCharge.step(10.0, 0.5, Double.NaN).charge(), 1e-9);
        assertEquals(10.0, PassiveCharge.step(10.0, 0.5, Double.POSITIVE_INFINITY).charge(), 1e-9);
    }

    /**
     * The remainder the accumulator keeps is always a proper fraction, so it can never itself pay a point
     * twice — the invariant that makes "accumulate, pay whole points" safe.
     */
    @Test
    void theCarryIsAlwaysAFraction() {
        for (double rate : new double[] { 0.2, 0.7, 1.0, 3.0, 4.0, 7.5, 100.0 }) {
            PassiveCharge.Step state = new PassiveCharge.Step(0.0, 0.0);
            for (int tick = 0; tick < 200; tick++) {
                state = PassiveCharge.step(state.charge(), state.carry(), rate);
                assertTrue(state.carry() >= 0.0 && state.carry() < 1.0,
                        "carry for " + rate + "/s went out of [0, 1) at tick " + tick + ": " + state.carry());
            }
        }
    }
}
