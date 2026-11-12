package com.astrum.pcrd.impl.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stack arithmetic of spec §4.4/§5.2, without a game.
 *
 * <p>Three rules, each with a way to break it:
 * <ul>
 *   <li>stacks accumulate and stop at {@code max} — and {@code max} is <b>never</b> scaled by the
 *       diminishing multiplier (spec §5.2: 递减绝不作用于「质」);</li>
 *   <li>expiry is a comparison of ticks, not a timer — the game never schedules anything;</li>
 *   <li>detonation damage is {@code stacks × perStack × multiplier}: the multiplier moves the
 *       number, never the fact that the detonation happens.</li>
 * </ul>
 */
class StackKernelTest {

    @Test
    void stacksAccumulateUpToMax() {
        var s = new StackState(0, 100);
        assertEquals(3, StackKernel.add(s, 100, 3, 5));
        assertEquals(5, StackKernel.add(new StackState(4, 100), 101, 3, 5));
    }

    /** The cap is a mechanism (§5.2), so nothing in this path consults a multiplier. */
    @Test
    void maxIsTheCapEvenWhenOverfilled() {
        assertEquals(5, StackKernel.add(new StackState(5, 100), 100, 1, 5));
        assertEquals(0, StackKernel.add(new StackState(2, 100), 100, -9, 5));
    }

    @Test
    void aRecordedHitWritesTheTickAndTheClampedCount() {
        var next = StackKernel.hit(new StackState(4, 100), 137, 3, 5);
        assertEquals(5, next.stacks());
        assertEquals(137, next.lastHitTick());
        assertEquals(StackKernel.add(new StackState(4, 100), 137, 3, 5), next.stacks(),
                "add() and hit() must not disagree about the count");
    }

    @Test
    void expiresLazilyAfterTtl() {
        var s = new StackState(2, 100);
        assertFalse(StackKernel.isExpired(s, 100 + 79, 80));
        assertTrue(StackKernel.isExpired(s, 100 + 80, 80));
    }

    /** A tick counter that went backwards (a different timeline) must not pin a stack forever. */
    @Test
    void aStateFromTheFutureCountsAsExpired() {
        assertTrue(StackKernel.isExpired(new StackState(2, 500), 100, 80));
    }

    /** A ttl of zero is nonsense from a hand-edited config: it must not mean "never expires". */
    @Test
    void nonPositiveTtlExpiresImmediately() {
        assertTrue(StackKernel.isExpired(new StackState(2, 100), 100, 0));
        assertTrue(StackKernel.isExpired(new StackState(2, 100), 100, -5));
    }

    /** The "nothing stacked" sentinel is never alive, not even early in a world's life. */
    @Test
    void theEmptySentinelIsAlwaysExpired() {
        assertTrue(StackKernel.isExpired(StackState.EMPTY, 0, 80));
        assertTrue(StackKernel.isExpired(StackState.EMPTY, Long.MAX_VALUE / 2, 80));
    }

    @Test
    void detonateScalesWithStacksAndMultiplier() {
        assertEquals(6.0f, StackKernel.detonateDamage(3, 2.0, 1.0), 1e-6);
        assertEquals(4.2f, StackKernel.detonateDamage(3, 2.0, 0.7), 1e-6);
    }

    @Test
    void zeroStacksDetonatesForNothing() {
        assertEquals(0.0f, StackKernel.detonateDamage(0, 2.0, 1.0), 1e-6);
    }

    /** A rank-5 slot detonates for less, never for a negative amount that would heal the target. */
    @Test
    void aNegativeOrNonsenseInputNeverHealsTheTarget() {
        assertEquals(0.0f, StackKernel.detonateDamage(-2, 2.0, 1.0), 1e-6);
        assertEquals(0.0f, StackKernel.detonateDamage(3, -2.0, 1.0), 1e-6);
        assertEquals(0.0f, StackKernel.detonateDamage(3, 2.0, -1.0), 1e-6);
        assertEquals(0.0f, StackKernel.detonateDamage(3, 2.0, Double.NaN), 1e-6);
        assertEquals(0.0f, StackKernel.detonateDamage(3, Double.POSITIVE_INFINITY, 1.0), 1e-6);
    }
}
