package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The direction→segment mapping and its dead zone, one assertion per segment (spec §5.5).
 *
 * <h2>Why this is the part that has to be a test</h2>
 * The mouse behaviour of the non-modal overlay is "flick a direction, pick a segment". On a real client
 * that is a claim about a moving hand that nobody can replay from a log; what <em>can</em> be pinned is
 * the arithmetic: a direction in, exactly one segment out, nothing inside the dead zone, and the same
 * answer however far the mouse was thrown. Each of those is written out below rather than looped over a
 * formula, so a future slip in the angle convention cannot hide behind the loop that produced it.
 */
class WheelFlickTest {

    /** Degrees that are unambiguously outside the dead zone, for the direction cases. */
    private static final double FAR = 20.0;

    @Test
    void upPicksTheFirstSegment() {
        assertEquals(0, WheelFlick.segmentForDelta(0.0, -FAR));
    }

    @Test
    void rightPicksTheSecondSegment() {
        assertEquals(1, WheelFlick.segmentForDelta(FAR, 0.0));
    }

    /**
     * Straight down is the seam between segments 2 and 3, and it settles on 3 — the same boundary fact
     * {@code WheelGeometryTest.slotZeroIsAtTheTopAndTheWheelReadsClockwise} pins for a position, seen
     * here through a direction. Deliberately spelled out: five segments from 12 o'clock do not line up
     * with the cardinal directions, so "down = 2" is the intuitive guess and the wrong answer.
     */
    @Test
    void straightDownSettlesOnTheThirdSegment() {
        assertEquals(3, WheelFlick.segmentForDelta(0.0, FAR));
        assertEquals(2, WheelFlick.segmentForDelta(FAR, FAR), "down-right is segment 2");
    }

    @Test
    void leftPicksTheFifthSegment() {
        assertEquals(4, WheelFlick.segmentForDelta(-FAR, 0.0));
    }

    @Test
    void upAndLeftPicksTheFifthSegment() {
        assertEquals(4, WheelFlick.segmentForDelta(-FAR, -FAR));
    }

    /** Every segment is reachable by flicking along its own bisector, not just the four cardinals. */
    @Test
    void everySegmentIsReachableAlongItsOwnBisector() {
        WheelGeometry.Ring ring = WheelGeometry.ring(640, 360, WheelGeometry.RADIUS_FRACTION_DEFAULT,
                WheelGeometry.BAND_FRACTION);
        for (int segment = 0; segment < WheelGeometry.SEGMENTS; segment++) {
            // Direction of the segment's own centre point, relative to the wheel's centre.
            double dx = WheelGeometry.segmentCentroidX(ring, segment) - ring.centerX();
            double dy = WheelGeometry.segmentCentroidY(ring, segment) - ring.centerY();
            assertEquals(segment, WheelFlick.segmentForDelta(dx, dy),
                    "flicking towards segment " + segment + " must pick segment " + segment);
        }
    }

    @Test
    void aStillMouseSelectsNothing() {
        assertEquals(WheelGeometry.NO_SEGMENT, WheelFlick.segmentForDelta(0.0, 0.0));
    }

    @Test
    void jitterInsideTheDeadZoneSelectsNothing() {
        double almost = WheelFlick.DEAD_ZONE_DEGREES * 0.9;
        assertEquals(WheelGeometry.NO_SEGMENT, WheelFlick.segmentForDelta(almost, 0.0));
        assertEquals(WheelGeometry.NO_SEGMENT, WheelFlick.segmentForDelta(0.0, -almost));
        assertEquals(WheelGeometry.NO_SEGMENT, WheelFlick.segmentForDelta(almost / 2.0, almost / 2.0));
        assertTrue(WheelFlick.isInsideDeadZone(almost, 0.0));
    }

    @Test
    void theDeadZoneBoundaryIsInclusive() {
        assertFalse(WheelFlick.isInsideDeadZone(WheelFlick.DEAD_ZONE_DEGREES, 0.0),
                "exactly at the threshold is a real movement, not jitter");
        assertEquals(1, WheelFlick.segmentForDelta(WheelFlick.DEAD_ZONE_DEGREES, 0.0));
    }

    /**
     * Distance must not change the answer: this is the property that stops a fast flick from landing
     * somewhere other than a slow one, and the reason there is no "too far" answer to clamp.
     */
    @Test
    void magnitudeDoesNotChangeTheSegment() {
        for (int segment = 0; segment < WheelGeometry.SEGMENTS; segment++) {
            double angle = segment * WheelGeometry.sectorDegrees();
            double dx = Math.sin(Math.toRadians(angle));
            double dy = -Math.cos(Math.toRadians(angle));
            assertEquals(segment, WheelFlick.segmentForDelta(dx * 3.0, dy * 3.0),
                    "a tiny flick and a huge sweep must agree on segment " + segment);
            assertEquals(segment, WheelFlick.segmentForDelta(dx * 500.0, dy * 500.0),
                    "a tiny flick and a huge sweep must agree on segment " + segment);
        }
    }

    @Test
    void notANumberIsTreatedAsNoMovement() {
        assertTrue(WheelFlick.isInsideDeadZone(Double.NaN, 1.0));
        assertEquals(WheelGeometry.NO_SEGMENT, WheelFlick.segmentForDelta(Double.NaN, Double.NaN));
    }

    /**
     * Yaw wraps at ±180, so a right turn across the seam must read as the small turn it was, not as a
     * ~360° flick the other way.
     */
    @Test
    void yawDeltaWrapsAcrossTheSeam() {
        assertEquals(2.0, WheelFlick.yawDelta(179.0f, -179.0f), 1e-6);
        assertEquals(-2.0, WheelFlick.yawDelta(-179.0f, 179.0f), 1e-6);
        assertEquals(10.0, WheelFlick.yawDelta(10.0f, 20.0f), 1e-6);
        assertEquals(-10.0, WheelFlick.yawDelta(20.0f, 10.0f), 1e-6);
        assertEquals(0.0, WheelFlick.yawDelta(37.5f, 37.5f), 1e-9);
    }

    @Test
    void wrapDegreesKeepsHalfOpenInterval() {
        assertEquals(180.0, WheelFlick.wrapDegrees(180.0), 1e-9);
        assertEquals(180.0, WheelFlick.wrapDegrees(-180.0), 1e-9, "the seam belongs to the right side");
        assertEquals(170.0, WheelFlick.wrapDegrees(530.0), 1e-9);
        assertEquals(-170.0, WheelFlick.wrapDegrees(-530.0), 1e-9);
    }
}
