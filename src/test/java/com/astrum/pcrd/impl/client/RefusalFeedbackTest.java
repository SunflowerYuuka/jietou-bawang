package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.ub.CastGate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "never fail silently" contract, in the part of it that can be checked: every refusal has a
 * sound, the sound differs per reason, and the shake is bounded and ends where it started.
 *
 * <p>Spec §5.4 forbids a silent failure; a test cannot hear the sound, but it can prove the five
 * reasons do not collapse into one indistinguishable buzz, and that "is this refusal still showing?"
 * agrees with the window it was given.
 */
class RefusalFeedbackTest {

    @Test
    void everyReasonHasItsOwnPitch() {
        Set<Float> pitches = new HashSet<>();
        for (CastGate.Reason reason : CastGate.Reason.values()) {
            float pitch = RefusalFeedback.pitchFor(reason);
            assertTrue(pitch > 0.0f, reason + " must be audible");
            pitches.add(pitch);
        }
        assertEquals(CastGate.Reason.values().length, pitches.size(),
                "each refusal must sound different from the others");
    }

    @Test
    void theTwoRefusalsAPlayerActuallyMeetsSoundDifferent() {
        assertNotEquals(RefusalFeedback.pitchFor(CastGate.Reason.NOT_CHARGED),
                RefusalFeedback.pitchFor(CastGate.Reason.COOLDOWN));
    }

    @Test
    void windowIsHalfASecondAndInclusiveOfItsLastTick() {
        assertEquals(10, RefusalFeedback.WINDOW_TICKS);
        assertTrue(RefusalFeedback.isActive(100L, 100L, RefusalFeedback.WINDOW_TICKS));
        assertTrue(RefusalFeedback.isActive(110L, 100L, RefusalFeedback.WINDOW_TICKS));
        assertFalse(RefusalFeedback.isActive(111L, 100L, RefusalFeedback.WINDOW_TICKS));
        assertFalse(RefusalFeedback.isActive(99L, 100L, RefusalFeedback.WINDOW_TICKS),
                "a refusal stamped in the future is not shown (it is a reload artefact, not an event)");
    }

    @Test
    void shakeIsBoundedAndEndsAtRest() {
        assertEquals(0, RefusalFeedback.shakeOffset(0L, RefusalFeedback.WINDOW_TICKS));
        assertEquals(0, RefusalFeedback.shakeOffset(RefusalFeedback.WINDOW_TICKS,
                RefusalFeedback.WINDOW_TICKS));
        assertEquals(0, RefusalFeedback.shakeOffset(RefusalFeedback.WINDOW_TICKS + 1L,
                RefusalFeedback.WINDOW_TICKS));
        assertTrue(RefusalFeedback.shakeOffset(1L, RefusalFeedback.WINDOW_TICKS) > 0,
                "the slot must move on the second frame, or the shake is invisible");
        for (long age = 0; age <= RefusalFeedback.WINDOW_TICKS; age++) {
            assertTrue(Math.abs(RefusalFeedback.shakeOffset(age, RefusalFeedback.WINDOW_TICKS))
                    <= RefusalFeedback.SHAKE_PIXELS, "shake must stay within its amplitude");
        }
    }
}
