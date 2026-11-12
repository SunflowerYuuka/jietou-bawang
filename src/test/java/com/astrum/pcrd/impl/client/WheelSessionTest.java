package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wheel's pick state: accumulation, the dead zone, and the two rules that are easy to get wrong
 * (spec §5.5).
 *
 * <p>The rules under test are the ones that make direction picking usable in combat:
 *
 * <ul>
 *   <li>a flick <b>spends</b> the accumulated movement, so a wrong pick can be corrected by flicking
 *       somewhere else instead of by sweeping around the wheel;</li>
 *   <li>a selection <b>survives</b> the mouse stopping, so the highlight and the preset the release-tap
 *       will fire do not flicker off the moment the wrist stops;</li>
 *   <li>nothing is selected while the accumulated movement is still inside the dead zone, so hand
 *       jitter cannot shuffle the preset;</li>
 *   <li>resetting (opening or closing the wheel) forgets both, so no pick and no leftover movement
 *       crosses from one wheel to the next.</li>
 * </ul>
 */
class WheelSessionTest {

    /** Comfortably past the dead zone, in degrees. */
    private static final double FLICK = 10.0;

    @Test
    void nothingIsSelectedBeforeAnyMovement() {
        WheelSession session = new WheelSession();
        assertEquals(WheelGeometry.NO_SEGMENT, session.hovered());
        assertEquals(WheelGeometry.NO_SEGMENT, session.accept(0.0, 0.0));
        assertEquals(WheelGeometry.NO_SEGMENT, session.hovered());
    }

    @Test
    void movementInsideTheDeadZoneSelectsNothingAndKeepsTheOldSelection() {
        WheelSession session = new WheelSession();
        assertEquals(1, session.accept(FLICK, 0.0), "right is segment 1");
        assertEquals(WheelGeometry.NO_SEGMENT, session.accept(0.5, -0.5),
                "jitter below the dead zone picks nothing");
        assertEquals(1, session.hovered(), "and leaves the selection alone");
    }

    @Test
    void smallMovementsAccumulateUntilTheyCrossTheDeadZone() {
        WheelSession session = new WheelSession();
        double half = WheelFlick.DEAD_ZONE_DEGREES * 0.6;
        assertEquals(WheelGeometry.NO_SEGMENT, session.accept(0.0, -half));
        assertEquals(0, session.accept(0.0, -half), "two halves make one flick");
    }

    @Test
    void aFlickSpendsTheAccumulatedMovement() {
        WheelSession session = new WheelSession();
        assertEquals(1, session.accept(FLICK, 0.0));
        // If the rightward movement were still accumulated, this small upward move would read as
        // up-right (segment 1 again) instead of as nothing at all.
        assertEquals(WheelGeometry.NO_SEGMENT, session.accept(0.0, -1.0));
        assertEquals(0, session.accept(0.0, -FLICK), "a fresh upward flick picks segment 0");
    }

    @Test
    void theSelectionSurvivesStillness() {
        WheelSession session = new WheelSession();
        assertEquals(3, session.accept(0.0, FLICK), "straight down settles on segment 3");
        session.accept(0.0, 0.0);
        session.accept(0.0, 0.0);
        assertEquals(3, session.hovered());
    }

    @Test
    void resetForgetsBothTheSelectionAndTheMovement() {
        WheelSession session = new WheelSession();
        double almost = WheelFlick.DEAD_ZONE_DEGREES * 0.9;
        session.accept(0.0, -almost);
        assertEquals(0, session.accept(0.0, -almost), "the accumulated movement is live");

        session.reset();
        assertEquals(WheelGeometry.NO_SEGMENT, session.hovered(), "the selection is gone");
        assertEquals(WheelGeometry.NO_SEGMENT, session.accept(0.0, -almost),
                "and so is the half-swallowed movement: one half alone is still inside the dead zone");
    }

    @Test
    void correctingAWrongPickTakesOnlyASecondFlick() {
        WheelSession session = new WheelSession();
        assertEquals(1, session.accept(FLICK, 0.0), "right");
        assertEquals(4, session.accept(-FLICK, 0.0), "and left, immediately: no need to unwind");
        assertEquals(4, session.hovered());
    }
}
