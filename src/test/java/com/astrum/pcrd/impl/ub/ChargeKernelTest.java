package com.astrum.pcrd.impl.ub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The charge arithmetic from spec §5.4, pinned to the numbers the brief names.
 *
 * <p>Every assertion here is about a plain {@code double}; nothing in this test boots a level, a
 * player or a config file, because {@link ChargeKernel} must not need any of them.
 */
class ChargeKernelTest {

    @Test void damageGrantsCharge() {
        assertEquals(2.5, ChargeKernel.addDealt(0.5, 2.0, 1.0), 1e-9);
    }

    @Test void damageTakenUsesLesserCoefficient() {
        assertEquals(0.99, ChargeKernel.addTaken(0.33, 2.0, 0.33), 1e-9);
    }

    @Test void killGrantsFlat() {
        assertEquals(20.0, ChargeKernel.addKill(0.0, 20.0), 1e-9);
    }

    @Test void naturalRegenIsPerTick() {
        assertEquals(0.01, ChargeKernel.naturalRegen(0.2), 1e-9);
    }

    @Test void chargeNeverExceedsHundred() {
        assertEquals(100.0, ChargeKernel.clamp(140.0), 1e-9);
        assertEquals(0.0, ChargeKernel.clamp(-5.0), 1e-9);
    }

    @Test void fullOnlyAtHundred() {
        assertTrue(ChargeKernel.isFull(100.0));
        assertFalse(ChargeKernel.isFull(99.99));
    }

    /**
     * Clamping is the kernel's job on <em>every</em> path, not just when a caller remembers to ask.
     * A gauge that can hold 103.7 would make {@code isFull} false forever and the ultimate
     * unreachable — the same silent, permanent loss of the mechanic that {@code UbState}'s codec
     * rejects on disk.
     */
    @Test void accumulationClampsAtTheCeiling() {
        assertEquals(100.0, ChargeKernel.addDealt(99.0, 5.0, 1.0), 1e-9);
        assertEquals(100.0, ChargeKernel.addKill(95.0, 20.0), 1e-9);
    }

    /** A drain must stop at the floor instead of going negative. */
    @Test void drainStopsAtZero() {
        assertEquals(0.0, ChargeKernel.addTaken(0.1, -5.0, 1.0), 1e-9);
        assertEquals(0.0, ChargeKernel.add(0.1, -0.5), 1e-9);
    }

    /**
     * {@code NaN} is the one value that makes every comparison false, so a gauge holding it would
     * neither fill nor reset. The kernel answers the floor instead of propagating it.
     */
    @Test void aNonFiniteGaugeCollapsesToTheFloor() {
        assertEquals(0.0, ChargeKernel.clamp(Double.NaN), 1e-9);
        assertEquals(100.0, ChargeKernel.clamp(Double.POSITIVE_INFINITY), 1e-9);
        assertEquals(0.0, ChargeKernel.clamp(Double.NEGATIVE_INFINITY), 1e-9);
    }

    /** `full` is an exact value, not a threshold — 99.9999 is not full and 100.0001 is not either. */
    @Test void fullIsAnExactValueNotAThreshold() {
        assertTrue(ChargeKernel.isFull(ChargeKernel.MAX_CHARGE));
        assertFalse(ChargeKernel.isFull(100.000001));
        assertFalse(ChargeKernel.isFull(99.999999));
    }

    /** One second's worth of trickle is exactly twenty ticks' worth: the division happens here once. */
    @Test void regenDividesByTwentyExactlyOnce() {
        assertEquals(1.0, ChargeKernel.naturalRegen(20.0), 1e-9);
        assertEquals(0.0, ChargeKernel.naturalRegen(0.0), 1e-9);
        assertEquals(ChargeKernel.naturalRegen(0.2) * 20.0, 0.2, 1e-9);
    }
}
