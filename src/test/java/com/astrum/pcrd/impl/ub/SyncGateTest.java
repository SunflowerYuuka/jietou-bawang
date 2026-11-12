package com.astrum.pcrd.impl.ub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "should a packet go out now" decision, tested as a pure function so it cannot hide inside a
 * listener body.
 *
 * <p>Two rules have to hold at once, and each has its own test:
 * <ul>
 *   <li><b>Only on change.</b> An unchanged gauge must not produce traffic, however many ticks
 *       pass.</li>
 *   <li><b>At most once per 5-tick window.</b> A gauge that changes every tick (it does, once the
 *       trickle is live) must still cost at most four packets per second, not twenty.</li>
 * </ul>
 *
 * <p>{@code lastSyncTick = -1}, {@code lastSentCharge = Float.NaN}, {@code lastSentCdEndTick =
 * Long.MIN_VALUE} mean "this client has not been told anything yet"; the NaN sentinel compares
 * unequal to every real charge, so the first window always pushes.
 */
class SyncGateTest {

    private static final int NEVER = -1;
    private static final float NEVER_CHARGE = Float.NaN;
    private static final long NEVER_CD = Long.MIN_VALUE;

    @Test void sendsWhenTheGaugeChanged() {
        assertTrue(SyncGate.shouldSync(10, 5, 12.5f, 0L, 10.0f, 0L));
    }

    @Test void sendsWhenOnlyTheCooldownChanged() {
        assertTrue(SyncGate.shouldSync(10, 5, 10.0f, 140L, 10.0f, 0L));
    }

    @Test void doesNotSendWhenNothingChanged() {
        assertFalse(SyncGate.shouldSync(10, 5, 10.0f, 0L, 10.0f, 0L));
    }

    /**
     * Even a changed gauge must not be pushed twice in one tick: two mutations inside the same tick
     * are one visible state, and the second call is what a careless second listener would do.
     */
    @Test void doesNotSendTwiceInTheSameTick() {
        assertFalse(SyncGate.shouldSync(10, 10, 12.5f, 0L, 10.0f, 0L));
    }

    /** Between windows the gate stays shut, so the next push lands on the next multiple of five. */
    @Test void doesNotSendBetweenWindows() {
        assertFalse(SyncGate.shouldSync(11, 5, 12.5f, 0L, 10.0f, 0L));
        assertTrue(SyncGate.shouldSync(15, 5, 12.5f, 0L, 10.0f, 0L));
    }

    /** A fresh connection is owed the current gauge even if the value is the default zero. */
    @Test void firstPushAlwaysSends() {
        assertTrue(SyncGate.shouldSync(0, NEVER, 0.0f, 0L, NEVER_CHARGE, NEVER_CD));
        assertTrue(SyncGate.shouldSync(5, NEVER, 0.0f, 0L, NEVER_CHARGE, NEVER_CD));
    }

    /**
     * The measurement that would fail if the gate sent every tick: 20 ticks of a gauge that changes
     * on every single tick. Four pushes, not twenty. A gate that ignored the interval — or a caller
     * that pushed from the mutation site instead of the window — reports 20 here.
     */
    @Test void twentyChangingTicksCostFourPackets() {
        int pushes = 0;
        int lastSyncTick = NEVER;
        float lastCharge = NEVER_CHARGE;
        long lastCd = NEVER_CD;
        for (int tick = 0; tick < 20; tick++) {
            float charge = tick; // changes every tick, like the live trickle does
            if (SyncGate.shouldSync(tick, lastSyncTick, charge, 0L, lastCharge, lastCd)) {
                pushes++;
                lastSyncTick = tick;
                lastCharge = charge;
                lastCd = 0L;
            }
        }
        assertEquals(4, pushes, "one push per 5-tick window; 20 would mean a packet every tick");
    }

    /** And the mirror image: a still gauge costs nothing at all over the same twenty ticks. */
    @Test void twentyUnchangedTicksCostNothing() {
        int pushes = 0;
        int lastSyncTick = 5;
        float lastCharge = 42.0f;
        long lastCd = 0L;
        for (int tick = 5; tick < 25; tick++) {
            if (SyncGate.shouldSync(tick, lastSyncTick, 42.0f, 0L, lastCharge, lastCd)) {
                pushes++;
                lastSyncTick = tick;
                lastCharge = 42.0f;
                lastCd = 0L;
            }
        }
        assertEquals(0, pushes);
    }
}
