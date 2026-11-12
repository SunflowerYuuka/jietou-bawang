package com.astrum.pcrd.impl.combat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The parts of {@link GuardedAreaChargeSink} that can be exercised without a game: its "never throw"
 * promise at the outermost boundary, and the per-receiver loop {@link GuardedAreaChargeSink#grantEach}
 * that the sink uses to join the budget to the credit step.
 *
 * <p>The rest of the sink is deliberately game-bound — it reads {@code ServerLevel.players()},
 * measures distances and writes player attachments — and is therefore not unit-tested here. Its two
 * decision rules are {@link AllySelector} and {@link AreaChargeBudget}, both proven headlessly; the
 * adapter that joins them is measured in a server run (see the task report, which states exactly how
 * far that measurement got and what it could not reach).
 */
class GuardedAreaChargeSinkTest {

    @Test
    void aNullRequestIsIgnoredRatherThanThrown() {
        var sink = new GuardedAreaChargeSink();
        assertDoesNotThrow(() -> sink.deliver(null),
                "deliver() 在伤害序列里被调用，任何输入都不得把异常抛回去");
    }

    /**
     * One withheld receiver must not end the call: the budget answers per receiver, and the next
     * receiver in the same delivery is still credited.
     *
     * <p>The withheld receiver is filled by a <b>different</b> caster first, so the pair cooldown for
     * this call's caster is fresh and only the per-second cap can withhold it.
     */
    @Test
    void aReceiverWithheldByTheBudgetDoesNotSuppressTheRestOfTheCall() {
        AreaChargeBudget budget = new AreaChargeBudget(() -> 20, () -> 6.0);
        UUID filler = UUID.randomUUID(), caster = UUID.randomUUID(),
                withheld = UUID.randomUUID(), credited = UUID.randomUUID();
        assertEquals(6.0, budget.grant(filler, withheld, 6.0, 100, 5), 1e-9);

        Map<UUID, Double> credits = GuardedAreaChargeSink.grantEach(
                List.of(withheld, credited), caster, 3.0, 100, 5, budget);

        assertFalse(credits.containsKey(withheld), "A 本秒已满，应被预算扣住");
        assertEquals(Map.of(credited, 3.0), credits, "B 仍须在同一轮交付中被记入，不能被 A 拖停");
    }

    /**
     * The sink is not a second diminishing-returns stage: the number it hands to the credit step is
     * exactly the already-scaled request. A multiplier added inside the sink would make this 3.7
     * become something smaller.
     */
    @Test
    void theAmountHandedToTheCreditStepIsTheRequestUnmodified() {
        AreaChargeBudget budget = new AreaChargeBudget(() -> 20, () -> 100.0);
        UUID caster = UUID.randomUUID(), receiver = UUID.randomUUID();

        Map<UUID, Double> credits = GuardedAreaChargeSink.grantEach(
                List.of(receiver), caster, 3.7, 100, 5, budget);

        assertEquals(3.7, credits.get(receiver), 1e-9,
                "请求值 3.7 已经含递减；sink 内不得再乘一次递减系数");
    }
}
