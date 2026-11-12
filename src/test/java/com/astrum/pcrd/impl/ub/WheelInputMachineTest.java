package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.ub.WheelInputMachine.Decision;
import com.astrum.pcrd.impl.ub.WheelInputMachine.Purpose;
import com.astrum.pcrd.impl.ub.WheelInputMachine.State;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The press-hold-release machine behind the two UB keys, tested as a pure state machine in <b>both</b>
 * of its purposes (spec §5.5, 2026-11-12: one key for casting, one for changing the preset).
 *
 * <h2>The whole transition table, twice</h2>
 * The keys share every timing rule — press arms, a poll or a release past
 * {@link WheelInputMachine#HOLD_THRESHOLD_MILLIS} is the hold, a hold never fires on its first frame —
 * and differ only in what the two meanings become: the cast key's hold is a slot <em>pad</em> and its
 * digit casts, the wheel key's hold is the ring and its digit only moves the preset. So the table is
 * pinned once per purpose, and the asymmetry itself is an assertion: a hold in {@link Purpose#CAST}
 * answers {@link Decision#OPEN_CAST_PAD} where the same input answers {@link Decision#OPEN_WHEEL} in
 * {@link Purpose#PRESET}, and a digit answers {@link Decision#CAST_SLOT} versus
 * {@link Decision#SET_PRESET}.
 *
 * <p>The rule that is easy to get wrong is the release-time decision: a release inside the threshold
 * is a cast on the cast key and <b>nothing at all</b> on the wheel key, a release after the overlay
 * opened is only a close — never a cast — and the hold must never fire on the press frame.
 */
class WheelInputMachineTest {

    private static WheelInputMachine cast() {
        return new WheelInputMachine(Purpose.CAST);
    }

    private static WheelInputMachine preset() {
        return new WheelInputMachine(Purpose.PRESET);
    }

    // ---------------------------------------------------------------- the shape of the machine

    @Test
    void everyMachineKnowsWhichKeyItServes() {
        assertEquals(Purpose.CAST, cast().purpose());
        assertEquals(Purpose.PRESET, preset().purpose());
        assertEquals(State.IDLE, cast().state());
        assertEquals(Decision.NONE, cast().lastDecision(), "nothing has been decided yet");
        assertTrue(cast().pickedSlot().isEmpty());
    }

    // ---------------------------------------------------------------- CAST purpose

    @Test void castQuickReleaseIsTapCast() {
        var m = cast();
        m.onPress(1000);
        assertEquals(Decision.TAP_CAST, m.onRelease(1150));
        assertEquals(State.IDLE, m.state());
        assertEquals(Purpose.CAST, m.purpose());
    }

    @Test void castHoldingPastThresholdOpensThePad() {
        var m = cast();
        m.onPress(1000);
        assertEquals(Decision.NONE, m.poll(1199), "one millisecond early is still pending");
        assertEquals(Decision.OPEN_CAST_PAD, m.poll(1200), "exactly at 200 ms is a hold");
        assertEquals(State.ACTIVE, m.state());
        assertEquals(Decision.NONE, m.poll(1300), "the pad opens once, not once per poll");
    }

    @Test void castReleaseAfterThePadOpenedOnlyCloses() {
        var m = cast();
        m.onPress(1000);
        assertEquals(Decision.OPEN_CAST_PAD, m.poll(1300));
        assertEquals(Decision.CLOSE, m.onRelease(1400));
        assertEquals(State.IDLE, m.state());
        assertTrue(m.pickedSlot().isEmpty(), "closing without a pick must not leave a slot to cast");
    }

    @Test void castDigitInsideThePadCastsThatSlotAndCloses() {
        var m = cast();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.CAST_SLOT, m.onDigit(3));
        assertEquals(Optional.of(3), m.pickedSlot());
        assertEquals(State.IDLE, m.state());
    }

    /** A digit on the cast key is a cast: it must never be reported as a preset change. */
    @Test void castDigitIsNeverAPresetChange() {
        var m = cast();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.CAST_SLOT, m.onDigit(1));
        assertFalse(m.lastDecision() == Decision.SET_PRESET);
    }

    // ---------------------------------------------------------------- PRESET purpose

    /** The wheel key only serves a hold (spec §5.5: 点按 = 无动作). */
    @Test void presetQuickReleaseDoesNothing() {
        var m = preset();
        m.onPress(1000);
        assertEquals(Decision.NONE, m.onRelease(1150));
        assertEquals(State.IDLE, m.state());
        assertTrue(m.pickedSlot().isEmpty());
    }

    @Test void presetHoldingPastThresholdOpensTheWheel() {
        var m = preset();
        m.onPress(1000);
        assertEquals(Decision.NONE, m.poll(1199));
        assertEquals(Decision.OPEN_WHEEL, m.poll(1200));
        assertEquals(State.ACTIVE, m.state());
        assertEquals(Decision.NONE, m.poll(1300), "the wheel opens once, not once per poll");
    }

    @Test void presetReleaseAfterTheWheelOpenedOnlyCloses() {
        var m = preset();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.CLOSE, m.onRelease(1400));
        assertEquals(State.IDLE, m.state());
    }

    @Test void presetDigitSetsThePresetAndClosesWithoutCasting() {
        var m = preset();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.SET_PRESET, m.onDigit(4),
                "a digit on the wheel key is a preset change, a distinct decision from CAST_SLOT");
        assertEquals(Optional.of(4), m.pickedSlot());
        assertEquals(State.IDLE, m.state());
    }

    /** A digit on the wheel key must never be a cast (spec §5.5: 轮盘键永远不释放 UB). */
    @Test void presetDigitIsNeverACast() {
        var m = preset();
        m.onPress(1000);
        m.poll(1300);
        assertEquals(Decision.SET_PRESET, m.onDigit(2));
        assertFalse(m.lastDecision() == Decision.CAST_SLOT);
    }

    // ---------------------------------------------------------------- rules both purposes share

    @Test void hotbarDigitsAreCapturedOnlyWhileTheOverlayIsUp() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            assertFalse(m.capturesHotbarDigits(), m.purpose() + ": nothing is up yet");
            m.onPress(1000);
            assertFalse(m.capturesHotbarDigits(), m.purpose() + ": a press alone does not block digits");
            m.poll(1300);
            assertTrue(m.capturesHotbarDigits(), m.purpose() + ": the overlay blocks 1–5");
            m.onRelease(1400);
            assertFalse(m.capturesHotbarDigits(), m.purpose() + ": the overlay is down again");
        }
    }

    @Test void aPressDoesNotDecideAnything() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            m.onPress(1000);
            assertEquals(Decision.NONE, m.lastDecision(), m.purpose() + ": a hold never fires on its first frame");
            assertEquals(State.PENDING, m.state());
        }
    }

    /** A key repeat while pending must not push the hold clock forward, or a hold never becomes one. */
    @Test void aRepeatedPressDoesNotRestartTheHoldClock() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            m.onPress(1000);
            m.onPress(1180);
            assertEquals(State.PENDING, m.state());
            assertEquals(expectedOpen(m.purpose()), m.poll(1250),
                    m.purpose() + ": the clock still runs from the first press");
        }
    }

    @Test void aRepeatedPressWhileTheOverlayIsUpIsIgnored() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            m.onPress(1000);
            m.poll(1300);
            m.onPress(1400);
            assertEquals(State.ACTIVE, m.state(), m.purpose() + ": a repeat must not restart the gesture");
        }
    }

    @Test void strayInputWhileIdleDoesNothing() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            assertEquals(Decision.NONE, m.onRelease(500), m.purpose() + ": a release with nothing held");
            assertEquals(Decision.NONE, m.onDigit(2), m.purpose() + ": a digit with no overlay up");
            assertEquals(Decision.NONE, m.poll(600), m.purpose() + ": a poll with nothing pending");
            assertEquals(State.IDLE, m.state());
            assertTrue(m.pickedSlot().isEmpty());
        }
    }

    /**
     * A dropped frame means {@code poll} never ran between the press and the release, so the machine is
     * still {@code PENDING} at ≥ 200 ms when the key comes up. That path must close, not cast and not
     * set a preset: a long hold is never a tap, however late the announcement of the overlay was.
     */
    @Test void aLongHoldReleasedWithoutAPollCloses() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            m.onPress(1000);
            assertEquals(Decision.CLOSE, m.onRelease(1300), m.purpose().toString());
            assertEquals(State.IDLE, m.state());
            assertTrue(m.pickedSlot().isEmpty(), "a closed gesture must not leave a slot to use");
        }
    }

    /**
     * A close is exactly one transition: a second release finds nothing to do.
     *
     * <p>This is what lets both overlays derive "is it up?" from their own machine. Several close paths
     * can fire in the same tick — Escape's cancelled pause menu (a {@code ScreenEvent}) and the key event
     * that follows it, a digit pick and a tick that notices the world went away — and each of them ends
     * in {@code onRelease}. If a repeat release moved the machine again, or reported a second
     * {@link Decision#CLOSE}, an overlay would tear down twice and a later gesture could be swallowed.
     */
    @Test void aSecondReleaseAfterTheOverlayClosedIsNothing() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            m.onPress(1000);
            m.poll(1300);
            assertEquals(Decision.CLOSE, m.onRelease(1400));
            assertEquals(Decision.NONE, m.onRelease(1401), "the second close path must find nothing to do");
            assertEquals(State.IDLE, m.state());
            assertFalse(m.capturesHotbarDigits());
        }
    }

    @Test void aSecondReleaseAfterAPickIsNothing() {
        for (WheelInputMachine m : new WheelInputMachine[] {cast(), preset()}) {
            m.onPress(1000);
            m.poll(1300);
            assertEquals(expectedPick(m.purpose()), m.onDigit(2));
            assertEquals(Decision.NONE, m.onRelease(1400));
            assertEquals(State.IDLE, m.state());
            assertEquals(Optional.of(2), m.pickedSlot(), "the pick stays readable, but the overlay is down");
        }
    }

    /** The two purposes differ in exactly two decisions, and nowhere else in the table. */
    @Test void theTwoPurposesDifferOnlyInTheHoldAndTheDigit() {
        assertEquals(Decision.OPEN_CAST_PAD, hold(cast()));
        assertEquals(Decision.OPEN_WHEEL, hold(preset()));
        assertEquals(Decision.NONE, tap(preset()), "the wheel key's tap is deliberately empty");
        assertEquals(Decision.TAP_CAST, tap(cast()));
        assertEquals(Decision.CLOSE, holdThenRelease(preset()));
        assertEquals(Decision.CLOSE, holdThenRelease(cast()));
        assertEquals(Decision.SET_PRESET, holdThenDigit(preset(), 1));
        assertEquals(Decision.CAST_SLOT, holdThenDigit(cast(), 1));
    }

    private static Decision expectedOpen(Purpose purpose) {
        return purpose == Purpose.CAST ? Decision.OPEN_CAST_PAD : Decision.OPEN_WHEEL;
    }

    private static Decision expectedPick(Purpose purpose) {
        return purpose == Purpose.CAST ? Decision.CAST_SLOT : Decision.SET_PRESET;
    }

    private static Decision hold(WheelInputMachine m) {
        m.onPress(1000);
        return m.poll(1200);
    }

    private static Decision tap(WheelInputMachine m) {
        m.onPress(1000);
        return m.onRelease(1100);
    }

    private static Decision holdThenRelease(WheelInputMachine m) {
        hold(m);
        return m.onRelease(1500);
    }

    private static Decision holdThenDigit(WheelInputMachine m, int digit) {
        hold(m);
        return m.onDigit(digit);
    }
}
