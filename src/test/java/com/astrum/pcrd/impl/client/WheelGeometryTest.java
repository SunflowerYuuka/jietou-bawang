package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The radial menu's arithmetic, asserted instead of eyeballed (spec §5.5).
 *
 * <h2>Why this test exists at all</h2>
 * Four of the spec's requirements are claims about pixels that a screenshot can only illustrate, never
 * prove: ring radius 35–45% of screen height, band width ≈12%, interior completely empty, and a
 * hover that resolves to exactly one segment. All four are functions here, so all four are assertions:
 * a screen height in, a measurable radius out; a mouse position in, a segment (or none) out. What is
 * <b>not</b> covered — how it looks, whether it reads well — is left to a human, deliberately.
 */
class WheelGeometryTest {

    /** A 640×360 GUI (1080p at scale 3) — the size the numbers in the javadoc are quoted at. */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;

    private static WheelGeometry.Ring defaultRing() {
        return WheelGeometry.ring(WIDTH, HEIGHT, WheelGeometry.RADIUS_FRACTION_DEFAULT,
                WheelGeometry.BAND_FRACTION);
    }

    @Test
    void ringIsCentredAndMeasuredFromTheConfiguredFractions() {
        WheelGeometry.Ring ring = defaultRing();
        assertEquals(WIDTH / 2.0, ring.centerX(), 1e-9);
        assertEquals(HEIGHT / 2.0, ring.centerY(), 1e-9);
        // 0.40 of 360 is the radius through the middle of the band; the band is 0.12 × 360 wide.
        assertEquals(144.0, ring.midRadius(), 1e-9);
        assertEquals(43.2, ring.bandWidth(), 1e-9);
        assertEquals(122.4, ring.innerRadius(), 1e-9);
        assertEquals(165.6, ring.outerRadius(), 1e-9);
    }

    @Test
    void radiusFractionIsClampedIntoTheSpecWindow() {
        assertEquals(WheelGeometry.RADIUS_FRACTION_MIN, WheelGeometry.clampRadiusFraction(0.0), 1e-9);
        assertEquals(WheelGeometry.RADIUS_FRACTION_MIN, WheelGeometry.clampRadiusFraction(0.1), 1e-9);
        assertEquals(WheelGeometry.RADIUS_FRACTION_MAX, WheelGeometry.clampRadiusFraction(0.9), 1e-9);
        assertEquals(WheelGeometry.RADIUS_FRACTION_DEFAULT,
                WheelGeometry.clampRadiusFraction(Double.NaN), 1e-9);
        // A value inside the window is left alone: the config's 0.40 must reach the screen as 0.40.
        assertEquals(0.42, WheelGeometry.clampRadiusFraction(0.42), 1e-9);
    }

    @Test
    void bandAndAlphaAreClampedIntoTheirSpecWindows() {
        assertEquals(WheelGeometry.BAND_FRACTION_MIN, WheelGeometry.clampBandFraction(0.01), 1e-9);
        assertEquals(WheelGeometry.BAND_FRACTION_MAX, WheelGeometry.clampBandFraction(0.5), 1e-9);
        assertEquals(WheelGeometry.BAND_ALPHA_DEFAULT, WheelGeometry.clampAlpha(Double.NaN), 1e-9);
        assertEquals(0.25, WheelGeometry.clampAlpha(0.0), 1e-9);
        assertEquals(0.35, WheelGeometry.clampAlpha(1.0), 1e-9);
        assertEquals(0.30, WheelGeometry.clampAlpha(0.30), 1e-9);
    }

    @Test
    void theWheelAlwaysFitsOnScreen() {
        int[][] sizes = {{320, 240}, {427, 240}, {640, 360}, {854, 480}, {1280, 720}, {1920, 1080}};
        for (int[] size : sizes) {
            WheelGeometry.Ring ring = WheelGeometry.ring(size[0], size[1],
                    WheelGeometry.RADIUS_FRACTION_MAX, WheelGeometry.BAND_FRACTION);
            assertTrue(ring.outerRadius() <= size[1] / 2.0,
                    "ring outer radius must fit the screen height at " + size[0] + "x" + size[1]);
            assertTrue(ring.outerRadius() <= size[0] / 2.0,
                    "ring outer radius must fit the screen width at " + size[0] + "x" + size[1]);
            assertTrue(ring.innerRadius() > 0.0,
                    "the interior must survive the clamp at " + size[0] + "x" + size[1]);
        }
    }

    /**
     * The clamp has to be reached by something, or it is untested code: at the spec's upper radius on
     * a short screen the outer edge would land outside the frame, and the band is pulled in instead.
     */
    @Test
    void anOversizedRingIsPulledBackInsideTheFrame() {
        WheelGeometry.Ring ring = WheelGeometry.ring(320, 240, WheelGeometry.RADIUS_FRACTION_MAX,
                WheelGeometry.BAND_FRACTION);
        assertEquals(120.0, ring.outerRadius(), 1e-9, "the outer edge sits exactly on the screen edge");
        assertEquals(91.2, ring.innerRadius(), 1e-9);
        assertEquals(28.8, ring.bandWidth(), 1e-9, "clamping the radius must not change the band width");
    }

    /**
     * Spec §5.5: 环内区域完全留空 — the crosshair and the enemy behind it must stay uncovered.
     *
     * <p>The assertion is that the region answers "no segment", which is exactly what the screen uses
     * to decide whether anything is hovered or drawn there.
     */
    @Test
    void interiorAnswersNoSegment() {
        WheelGeometry.Ring ring = defaultRing();
        assertEquals(WheelGeometry.NO_SEGMENT, WheelGeometry.segmentAt(ring, WIDTH / 2.0, HEIGHT / 2.0),
                "the very centre must belong to nobody");
        assertEquals(WheelGeometry.NO_SEGMENT,
                WheelGeometry.segmentAt(ring, WIDTH / 2.0, HEIGHT / 2.0 - ring.innerRadius() + 0.01));
        assertEquals(WheelGeometry.NO_SEGMENT,
                WheelGeometry.segmentAt(ring, WIDTH / 2.0 - ring.midRadius() * 0.5, HEIGHT / 2.0));
    }

    @Test
    void outsideTheBandAnswersNoSegment() {
        WheelGeometry.Ring ring = defaultRing();
        assertEquals(WheelGeometry.NO_SEGMENT,
                WheelGeometry.segmentAt(ring, WIDTH / 2.0, HEIGHT / 2.0 - ring.outerRadius() - 0.01));
        assertEquals(WheelGeometry.NO_SEGMENT, WheelGeometry.segmentAt(ring, 0, 0),
                "a corner of the screen is nowhere near the band");
    }

    @Test
    void slotZeroIsAtTheTopAndTheWheelReadsClockwise() {
        WheelGeometry.Ring ring = defaultRing();
        double mid = ring.midRadius();
        assertEquals(0, WheelGeometry.segmentAt(ring, WIDTH / 2.0, HEIGHT / 2.0 - mid));
        assertEquals(1, WheelGeometry.segmentAt(ring, WIDTH / 2.0 + mid, HEIGHT / 2.0));
        assertEquals(3, WheelGeometry.segmentAt(ring, WIDTH / 2.0, HEIGHT / 2.0 + mid),
                "straight down is the boundary between slots 2 and 3, settled to 3");
        assertEquals(4, WheelGeometry.segmentAt(ring, WIDTH / 2.0 - mid, HEIGHT / 2.0));
    }

    @Test
    void everySegmentCentreHoversItsOwnSegment() {
        WheelGeometry.Ring ring = defaultRing();
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            assertEquals(slot, WheelGeometry.segmentAt(ring, WheelGeometry.segmentCenterX(ring, slot),
                    WheelGeometry.segmentCenterY(ring, slot)),
                    "the point the label is drawn at must be the segment it belongs to");
        }
    }

    @Test
    void anglesNormaliseAndLandOnTheRightSegment() {
        assertEquals(0, WheelGeometry.segmentAtAngle(0.0));
        assertEquals(0, WheelGeometry.segmentAtAngle(359.9));
        assertEquals(0, WheelGeometry.segmentAtAngle(-10.0), "350° is 10° left of the top");
        assertEquals(1, WheelGeometry.segmentAtAngle(36.0), "the boundary opens the next segment");
        assertEquals(0, WheelGeometry.segmentAtAngle(35.9));
        assertEquals(1, WheelGeometry.segmentAtAngle(450.0), "more than a full turn is normalised");
    }

    @Test
    void segmentWedgesAreEvenlySizedAndCoverTheCircle() {
        double sector = WheelGeometry.sectorDegrees();
        assertEquals(360.0 / WheelGeometry.SEGMENTS, sector, 1e-9);
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            assertEquals(slot * sector - sector / 2.0, WheelGeometry.segmentStartDegrees(slot), 1e-9);
            assertEquals(slot * sector + sector / 2.0, WheelGeometry.segmentEndDegrees(slot), 1e-9);
            assertEquals(sector, WheelGeometry.segmentEndDegrees(slot)
                    - WheelGeometry.segmentStartDegrees(slot), 1e-9);
        }
    }
}
