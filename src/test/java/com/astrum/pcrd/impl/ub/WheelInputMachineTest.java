package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.ub.WheelInputMachine.Decision;
import com.astrum.pcrd.impl.ub.WheelInputMachine.State;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The press-hold-release machine behind the UB cast key, tested as a pure state machine.
 *
 * <p>The one rule that is easy to get wrong is the release-time decision: a release inside the hold
 * threshold is a cast, a release after the wheel opened is only a close — never a cast — and the
 * hold must never fire on the press frame.
 */
class WheelInputMachineTest {
    @Test void quickReleaseIsTapCast() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        assertEquals(Decision.TAP_CAST, m.onRelease(1150));
        assertEquals(State.IDLE, m.state());
    }
    @Test void holdingPastThresholdOpensWheel() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        assertEquals(Decision.NONE, m.poll(1199));
        assertEquals(Decision.OPEN_WHEEL, m.poll(1200));
        assertEquals(State.WHEEL, m.state());
    }
    @Test void releaseAfterWheelOpenedOnlyCloses() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.CLOSE, m.onRelease(1400));
        assertEquals(State.IDLE, m.state());
    }
    @Test void digitInsideWheelCastsThatSlotAndCloses() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        m.poll(1300);
        m.onDigit(3);
        assertEquals(Decision.CAST_SLOT, m.lastDecision());
        assertEquals(3, m.castSlot().orElseThrow());
        assertEquals(State.IDLE, m.state());
    }
    @Test void hotbarDigitsAreCapturedOnlyWhileWheelIsOpen() {
        var m = new WheelInputMachine();
        assertFalse(m.capturesHotbarDigits());
        m.onPress(1000); m.poll(1300);
        assertTrue(m.capturesHotbarDigits());
        m.onRelease(1400);
        assertFalse(m.capturesHotbarDigits());
    }
    @Test void strayReleaseWhileIdleDoesNothing() {
        assertEquals(Decision.NONE, new WheelInputMachine().onRelease(500));
    }
    /**
     * A dropped frame means {@code poll} never ran between the press and the release, so the machine
     * is still {@code PENDING} at ≥ 200 ms when the key comes up. That path must close, not cast:
     * a long hold is never a tap, however late the announcement of the wheel was.
     */
    @Test void aLongHoldReleasedWithoutAPollClosesInsteadOfCasting() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        assertEquals(Decision.CLOSE, m.onRelease(1300));
        assertEquals(State.IDLE, m.state());
        assertTrue(m.castSlot().isEmpty(), "a closed gesture must not leave a slot to cast");
    }

    /**
     * A close is exactly one transition: a second release finds nothing to do.
     *
     * <p>This is what lets the overlay derive "is it up?" from this machine alone. Several close paths
     * can fire in the same tick — Escape's cancelled pause menu (a {@code ScreenEvent}) and the key event
     * that follows it, a digit cast and a tick that notices the world went away — and each of them ends
     * in {@code onRelease}. If a repeat release moved the machine again, or reported a second
     * {@link Decision#CLOSE}, the overlay would tear down twice and a later gesture could be swallowed.
     */
    @Test void aSecondReleaseAfterTheWheelClosedIsNothing() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.CLOSE, m.onRelease(1400));
        assertEquals(Decision.NONE, m.onRelease(1401), "the second close path must find nothing to do");
        assertEquals(State.IDLE, m.state());
        assertFalse(m.capturesHotbarDigits());
    }

    /** The same for the digit path: one pick, one cast slot, and nothing left over afterwards. */
    @Test void aSecondReleaseAfterADigitCastIsNothing() {
        var m = new WheelInputMachine();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.CAST_SLOT, m.onDigit(2));
        assertEquals(Decision.NONE, m.onRelease(1400));
        assertEquals(State.IDLE, m.state());
        assertEquals(Optional.of(2), m.castSlot(), "the pick is still readable, but the wheel is down");
    }
}
