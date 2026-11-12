package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.UbCastFeedbackPayload;
import com.astrum.pcrd.impl.ub.CastGate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The refusal cache and its window, exercised through the method production actually calls.
 *
 * <h2>Why this test exists next to {@code RefusalFeedbackTest}</h2>
 * The window rule used to exist twice: {@code RefusalFeedback.isActive} (tested, but with no production
 * caller) and an inline {@code age < 0 || age > window} here (live, untested). A reader could not tell
 * which one the HUD obeyed, and a later edit could have changed either. Now there is one implementation
 * — {@link RefusalFeedback#isActive} — and this test drives it through {@link ClientCastFeedback#active},
 * so the rule that is tested is the rule that runs.
 */
class ClientCastFeedbackTest {

    @Test
    void aRefusalIsLiveForItsWholeWindowAndNotOneTickLonger() {
        ClientCastFeedback.accept(new UbCastFeedbackPayload(CastGate.Reason.NOT_CHARGED), 100L);

        assertTrue(ClientCastFeedback.active(100L, RefusalFeedback.WINDOW_TICKS).isPresent(),
                "the tick it arrived on is inside the window");
        assertTrue(ClientCastFeedback.active(110L, RefusalFeedback.WINDOW_TICKS).isPresent(),
                "the last tick of the window is inclusive");
        assertTrue(ClientCastFeedback.active(111L, RefusalFeedback.WINDOW_TICKS).isEmpty(),
                "one tick later it is gone, or the shake would never end");
        assertTrue(ClientCastFeedback.active(99L, RefusalFeedback.WINDOW_TICKS).isEmpty(),
                "a refusal stamped in the future is a reload artefact, not an event");
    }

    @Test
    void theWindowRuleIsTheOneRefusalFeedbackOwns() {
        ClientCastFeedback.accept(new UbCastFeedbackPayload(CastGate.Reason.COOLDOWN), 500L);

        for (long now = 480L; now <= 520L; now++) {
            assertEquals(RefusalFeedback.isActive(now, 500L, RefusalFeedback.WINDOW_TICKS),
                    ClientCastFeedback.active(now, RefusalFeedback.WINDOW_TICKS).isPresent(),
                    "at tick " + now + " the cache and RefusalFeedback.isActive must agree");
        }
    }

    @Test
    void theReasonAndItsTimestampSurviveTheCache() {
        ClientCastFeedback.accept(new UbCastFeedbackPayload(CastGate.Reason.SLOT_EMPTY), 7L);

        ClientCastFeedback.Refusal refusal =
                ClientCastFeedback.active(7L, RefusalFeedback.WINDOW_TICKS).orElseThrow();
        assertEquals(CastGate.Reason.SLOT_EMPTY, refusal.reason(),
                "the sound is chosen from this reason, so it must not be lost");
        assertEquals(7L, refusal.gameTime(),
                "the timestamp is what makes the sound fire exactly once");
    }
}
