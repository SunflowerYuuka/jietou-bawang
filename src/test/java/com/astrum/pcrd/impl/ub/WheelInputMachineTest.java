package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.ub.WheelInputMachine.Decision;
import com.astrum.pcrd.impl.ub.WheelInputMachine.State;
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
}
