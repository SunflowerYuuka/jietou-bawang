package com.astrum.pcrd.impl.combat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec §7.4's two guards against the N-djeeta charge loop, as arithmetic with an injected clock.
 *
 * <h2>Why the clock is a parameter</h2>
 * Pass ticks and seconds in rather than reading a level: the rule is "judge staleness when called",
 * so the call is the only moment that matters, and a test can place two calls 5 ticks apart or on
 * either side of a second boundary without sleeping. Nothing here polls.
 *
 * <h2>What each guard is for</h2>
 * The <b>pair cooldown</b> stops one caster from repeatedly filling the same receiver; the
 * <b>per-second cap</b> stops a crowd of casters from doing collectively what no single caster may do
 * alone. The burst test uses a different caster each iteration precisely because the pair cooldown
 * must not be the thing that bounds it.
 */
class AreaChargeBudgetTest {

    @AfterEach
    void clearTuning() {
        AreaChargeTuning.clearOverride();   // the default constructor reads the global seam
    }

    @Test
    void pairCooldownBlocksRapidRefill() {
        var b = new AreaChargeBudget();
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 100, 5), 1e-9);
        assertEquals(0.0, b.grant(caster, receiver, 3.0, 105, 5), 1e-9, "同一对、CD 20 tick 内");
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 121, 5), 1e-9, "过了 CD，同一秒内仍受每秒上限约束");
    }

    @Test
    void perSecondCapLimitsBurst() {
        var b = new AreaChargeBudget();
        UUID receiver = UUID.randomUUID();
        double total = 0;
        for (int i = 0; i < 10; i++) {
            UUID caster = UUID.randomUUID();   // 每次不同施放者，绕开成对 CD
            total += b.grant(caster, receiver, 3.0, 100 + i, 5);
        }
        assertEquals(6.0, total, 1e-9, "10 个不同施放者的 3.0 必须恰好封顶在 6.0，实际 " + total);
    }

    // ------------------------------------------------------------------ the edges the brief implies

    /**
     * Both knobs are read on <b>every</b> grant rather than captured when the budget is built
     * (spec §8.4 guardrail 1). The injected suppliers change between calls, so a cached field would
     * answer the old numbers on calls 2 and 4.
     */
    @Test
    void bothKnobsAreReadOnEveryGrantRatherThanFrozen() {
        AtomicInteger cooldown = new AtomicInteger(20);
        AtomicReference<Double> cap = new AtomicReference<>(6.0);
        var b = new AreaChargeBudget(cooldown::get, cap::get);
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();

        assertEquals(3.0, b.grant(caster, receiver, 3.0, 100, 5), 1e-9);
        cooldown.set(0);   // a reload drops the pair cooldown
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 101, 5), 1e-9, "新 CD 必须在本次调用就生效");
        assertEquals(0.0, b.grant(caster, receiver, 3.0, 102, 5), 1e-9, "6.0 已经用完");
        cap.set(9.0);       // a reload raises the ceiling
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 103, 5), 1e-9, "新上限必须在本次调用就生效");
    }

    /**
     * A grant the cap refuses must not also arm the pair cooldown: the receiver would otherwise be
     * punished twice for a limit that is not the pair's.
     *
     * <p>The second call is from a <b>different</b> caster, so the pair cooldown cannot be the thing
     * that refuses it; the receiver is already at its 6.0 ceiling for that second, so only the cap
     * can answer 0. The third call is 19 ticks after that refused attempt (and 20 after the first
     * grant), so a pair cooldown armed by the refusal would block it; the cap window has rolled to
     * the next second, so nothing else can.
     */
    @Test
    void aGrantTheCapRefusesDoesNotArmThePairCooldown() {
        var b = new AreaChargeBudget();
        UUID firstCaster = UUID.randomUUID(), refusedCaster = UUID.randomUUID(),
                receiver = UUID.randomUUID();
        assertEquals(6.0, b.grant(firstCaster, receiver, 6.0, 100, 5), 1e-9);
        assertEquals(0.0, b.grant(refusedCaster, receiver, 3.0, 101, 5), 1e-9,
                "接收者本秒已满，不同施放者应被每秒上限拒绝（不是成对 CD）");
        assertEquals(3.0, b.grant(refusedCaster, receiver, 3.0, 120, 6), 1e-9,
                "被每秒上限拒绝的那次不得再占住成对 CD");
    }

    /** A receiver's budget is its own: one receiver hitting the cap must not starve another. */
    @Test
    void theCapIsPerReceiverNotGlobal() {
        var b = new AreaChargeBudget();
        UUID caster = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        assertEquals(6.0, b.grant(caster, first, 6.0, 100, 5), 1e-9);
        assertEquals(3.0, b.grant(caster, second, 3.0, 100, 5), 1e-9);
    }

    /** A zero cooldown means no cooldown (the config range admits 0), not "always blocked". */
    @Test
    void aZeroCooldownMeansNoCooldown() {
        var b = new AreaChargeBudget(() -> 0, () -> 100.0);
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 100, 5), 1e-9);
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 100, 5), 1e-9);
    }

    /** Nonsense requests grant nothing rather than poisoning the table with NaN. */
    @Test
    void nonPositiveOrNonFiniteRequestsGrantNothing() {
        var b = new AreaChargeBudget();
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();
        assertEquals(0.0, b.grant(caster, receiver, 0.0, 100, 5), 1e-9);
        assertEquals(0.0, b.grant(caster, receiver, -1.0, 100, 5), 1e-9);
        assertEquals(0.0, b.grant(caster, receiver, Double.NaN, 100, 5), 1e-9);
        assertEquals(0.0, b.grant(caster, receiver, Double.POSITIVE_INFINITY, 100, 5), 1e-9);
    }

    /** A clock that jumps backwards (a new world's game time) must not lock a pair out forever. */
    @Test
    void aBackwardsClockIsTreatedAsStaleNotAsAnEternalCooldown() {
        var b = new AreaChargeBudget();
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 10_000, 500), 1e-9);
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 5, 0), 1e-9);
    }

    /** A stopped server forgets its windows, so the next world is not charged for the last one. */
    @Test
    void resetClearsBothGuards() {
        var b = new AreaChargeBudget();
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();
        assertEquals(6.0, b.grant(caster, receiver, 6.0, 100, 5), 1e-9);
        b.reset();
        assertEquals(6.0, b.grant(caster, receiver, 6.0, 100, 5), 1e-9,
                "reset 之后同一 tick、同一秒都必须重新给满");
    }

    /**
     * The default constructor must go through {@link AreaChargeTuning}, not read a config value of
     * its own: an operator override of the pair cooldown has to be visible to the budget on the very
     * next grant, and the overridden cap has to bound that grant. The cap is deliberately {@code 4.0}
     * rather than the 6.0 fallback: with the fallback the second grant would be 3.0, so the asserted
     * 1.0 can only come from the override. The cooldown is {@code 1}, not the 20-tick fallback: with
     * the fallback the second grant would be 0.
     */
    @Test
    void theBudgetReadsItsKnobsThroughTheTuningSeam() {
        AreaChargeTuning.override(1, 4.0);
        var b = new AreaChargeBudget();
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();
        assertEquals(3.0, b.grant(caster, receiver, 3.0, 100, 5), 1e-9);
        assertEquals(1.0, b.grant(caster, receiver, 3.0, 101, 5), 1e-9,
                "override 的 1 tick CD 必须已放行；override 的 4.0 上限必须把 3.0 截成 1.0");
        assertEquals(0.0, b.grant(caster, receiver, 3.0, 102, 5), 1e-9, "override 的 4.0 上限必须封顶");
    }
}
