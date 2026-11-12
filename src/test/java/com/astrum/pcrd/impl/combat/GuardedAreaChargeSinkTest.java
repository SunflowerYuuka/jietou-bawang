package com.astrum.pcrd.impl.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * The part of {@link GuardedAreaChargeSink} that can be exercised without a game: its "never throw"
 * promise at the outermost boundary.
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
}
