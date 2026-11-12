package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The radial menu's arithmetic, asserted instead of eyeballed (spec §5.5).
 *
 * <h2>Why this test exists at all</h2>
 * Four of the spec's requirements are claims about pixels that a screenshot can only illustrate, never
 * prove: ring radius 20–33% of screen height (default 27%), band width 6–10% (default 8%, so the drawn
 * annulus is 0.23–0.31 of the height), interior completely empty, and a hover that resolves to exactly
 * one segment. All four are functions here, so all four are assertions: a screen height in, a
 * measurable radius out; a mouse position in, a segment (or none) out. What is <b>not</b> covered — how
 * it looks, whether it reads well — is left to a human, deliberately.
 *
 * <p>The numbers below are the post-playtest ones (spec §5.5, 2026-11-12): the first cut's 0.40 radius
 * and 0.12 band were reported as about 1.5× too large, and 0.30 alpha as nearly invisible. Every
 * assertion that encoded one of those old values moved with the spec; none was dropped.
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
        // 0.27 of 360 is the radius through the middle of the band; the band is 0.08 × 360 wide.
        assertEquals(97.2, ring.midRadius(), 1e-9);
        assertEquals(28.8, ring.bandWidth(), 1e-9);
        assertEquals(82.8, ring.innerRadius(), 1e-9);
        assertEquals(111.6, ring.outerRadius(), 1e-9);
    }

    /**
     * The annulus that actually gets drawn at the default, as a fraction of the screen height.
     *
     * <p>This is the shape the human asked for after seeing the first cut (spec §5.5): a mid radius of
     * 27%, an 8% band, so the band occupies 23–31% of the height — and the whole outer edge (plus the
     * 1 px outline) still on screen, i.e. inside half the height and half the width. The interior it
     * leaves, 23% of the height in every direction, is the thing kept empty for the crosshair; the last
     * assertion pins that the empty hole is far larger than the band, so a "fat ring with a pinhole"
     * cannot pass as this shape.
     */
    @Test
    void drawnAnnulusAtTheDefaultIsAThinRingLeavingTheCentreOpen() {
        WheelGeometry.Ring ring = defaultRing();

        assertEquals(0.27, ring.midRadius() / HEIGHT, 1e-9, "mid radius is 27% of the height");
        assertEquals(0.08, ring.bandWidth() / HEIGHT, 1e-9, "the band is 8% of the height");
        assertEquals(0.23, ring.innerRadius() / HEIGHT, 1e-9, "the band starts at 23% of the height");
        assertEquals(0.31, ring.outerRadius() / HEIGHT, 1e-9, "the band ends at 31% of the height");

        assertTrue(ring.outerRadius() <= HEIGHT / 2.0, "the outer edge stays on screen vertically");
        assertTrue(ring.outerRadius() <= WIDTH / 2.0, "the outer edge stays on screen horizontally");
        assertTrue(ring.innerRadius() > ring.bandWidth() * 2.0,
                "the empty interior must dominate the band: " + ring.innerRadius()
                        + " px of hole vs " + ring.bandWidth() + " px of band");
    }

    @Test
    void radiusFractionIsClampedIntoTheSpecWindow() {
        assertEquals(WheelGeometry.RADIUS_FRACTION_MIN, WheelGeometry.clampRadiusFraction(0.0), 1e-9);
        assertEquals(WheelGeometry.RADIUS_FRACTION_MIN, WheelGeometry.clampRadiusFraction(0.1), 1e-9);
        assertEquals(WheelGeometry.RADIUS_FRACTION_MAX, WheelGeometry.clampRadiusFraction(0.9), 1e-9);
        assertEquals(WheelGeometry.RADIUS_FRACTION_DEFAULT,
                WheelGeometry.clampRadiusFraction(Double.NaN), 1e-9);
        // The shipped numbers survive the clamp untouched: 0.20 is the low edge, 0.33 the high one,
        // and the default 0.27 is strictly inside so a playtest tune of it is not silently clipped.
        assertEquals(0.20, WheelGeometry.clampRadiusFraction(WheelGeometry.RADIUS_FRACTION_MIN), 1e-9);
        assertEquals(0.33, WheelGeometry.clampRadiusFraction(WheelGeometry.RADIUS_FRACTION_MAX), 1e-9);
        assertEquals(0.27, WheelGeometry.clampRadiusFraction(WheelGeometry.RADIUS_FRACTION_DEFAULT),
                1e-9);
        // Any other value inside the window is left alone too: 0.30 must reach the screen as 0.30.
        assertEquals(0.30, WheelGeometry.clampRadiusFraction(0.30), 1e-9);
    }

    @Test
    void bandAndAlphaAreClampedIntoTheirSpecWindows() {
        assertEquals(WheelGeometry.BAND_FRACTION_MIN, WheelGeometry.clampBandFraction(0.01), 1e-9);
        assertEquals(WheelGeometry.BAND_FRACTION_MAX, WheelGeometry.clampBandFraction(0.5), 1e-9);
        assertEquals(WheelGeometry.BAND_ALPHA_DEFAULT, WheelGeometry.clampAlpha(Double.NaN), 1e-9);
        assertEquals(0.45, WheelGeometry.clampAlpha(0.0), 1e-9);
        assertEquals(0.70, WheelGeometry.clampAlpha(1.0), 1e-9);
        assertEquals(0.55, WheelGeometry.clampAlpha(0.55), 1e-9);
        // 0.30 was the old default and is now below the spec floor: it must be lifted, not honoured.
        assertEquals(0.45, WheelGeometry.clampAlpha(0.30), 1e-9);
        // The band is measured against the height, and the default 0.08 is inside its own window.
        assertEquals(0.08, WheelGeometry.clampBandFraction(WheelGeometry.BAND_FRACTION), 1e-9);
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
     * The clamp has to be reached by something, or it is untested code.
     *
     * <p>At the post-playtest numbers the clamp is no longer reachable on a landscape window — 0.33 of
     * the height plus half an 0.08 band is 0.37, comfortably inside the frame — so it is exercised where
     * it still bites: a window narrower than it is tall. The outer edge is pulled back to half the
     * width, and the band keeps its width, which is the point of clamping the radius rather than the
     * band.
     */
    @Test
    void anOversizedRingIsPulledBackInsideTheFrame() {
        WheelGeometry.Ring ring = WheelGeometry.ring(160, 360, WheelGeometry.RADIUS_FRACTION_MAX,
                WheelGeometry.BAND_FRACTION);
        assertEquals(80.0, ring.outerRadius(), 1e-9, "the outer edge sits exactly on the screen edge");
        assertEquals(51.2, ring.innerRadius(), 1e-9);
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
