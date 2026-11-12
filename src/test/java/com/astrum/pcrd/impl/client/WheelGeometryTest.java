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
 * band is 0.23–0.31 of the height), a selection that resolves to exactly one segment (or none), and —
 * since the wheel got its two layouts — where a sector's <em>centroid</em> is. All of them are functions
 * here, so all of them are assertions: a screen height in, a measurable radius out; a position in, a
 * segment (or none) out. What is <b>not</b> covered — how it looks, whether it reads well — is left to a
 * human, deliberately.
 *
 * <p>"The interior is empty" is <b>not</b> asserted here and never was: it is a {@code RING}-only
 * statement about painting, and the overlay fills the middle in {@code DISC} (spec §5.5). What is
 * asserted, in both modes, is that the interior answers {@link WheelGeometry#NO_SEGMENT} — the centre
 * dead zone — because that is what keeps the preset from flickering when the player moves through the
 * middle. Painting is not selecting.
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
     * The band that actually gets drawn at the default, as a fraction of the screen height.
     *
     * <p>This is the shape the human asked for after seeing the first cut (spec §5.5): a mid radius of
     * 27%, an 8% band, so the band occupies 23–31% of the height — and the whole outer edge (plus the
     * 1 px outline) still on screen, i.e. inside half the height and half the width. The band is common
     * to both layouts; last assertion pins that it is far thinner than the radius, so a "fat ring"
     * cannot pass as this shape.
     */
    @Test
    void drawnBandAtTheDefaultIsAThinBand() {
        WheelGeometry.Ring ring = defaultRing();

        assertEquals(0.27, ring.midRadius() / HEIGHT, 1e-9, "mid radius is 27% of the height");
        assertEquals(0.08, ring.bandWidth() / HEIGHT, 1e-9, "the band is 8% of the height");
        assertEquals(0.23, ring.innerRadius() / HEIGHT, 1e-9, "the band starts at 23% of the height");
        assertEquals(0.31, ring.outerRadius() / HEIGHT, 1e-9, "the band ends at 31% of the height");

        assertTrue(ring.outerRadius() <= HEIGHT / 2.0, "the outer edge stays on screen vertically");
        assertTrue(ring.outerRadius() <= WIDTH / 2.0, "the outer edge stays on screen horizontally");
        assertTrue(ring.innerRadius() > ring.bandWidth() * 2.0,
                "the centre region must dominate the band: " + ring.innerRadius()
                        + " px of centre vs " + ring.bandWidth() + " px of band");
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
        // The alpha window is exactly the range the config declares (0.00 ~ 1.00), so the two ends are
        // legal values and must arrive unchanged. The old clamp was 0.45 ~ 0.70: 1.0 came back as 0.70
        // and 0.0 as 0.45, i.e. a legal config value was silently rewritten and a player could not reach
        // a fully opaque plate no matter what he typed. That is the regression these two lines pin.
        assertEquals(0.0, WheelGeometry.clampAlpha(0.0), 1e-9);
        assertEquals(1.0, WheelGeometry.clampAlpha(1.0), 1e-9);
        assertEquals(0.75, WheelGeometry.clampAlpha(0.75), 1e-9);
        // Any other value inside the window is left alone too: 0.30 and 0.90 reach the screen as typed.
        assertEquals(0.30, WheelGeometry.clampAlpha(0.30), 1e-9);
        assertEquals(0.90, WheelGeometry.clampAlpha(0.90), 1e-9);
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
     * The <b>centre dead zone</b>, in both layouts: the middle belongs to nobody (spec §5.5).
     *
     * <p>This assertion is deliberately independent of whether the middle is painted. {@code DISC}
     * paints it; {@code RING} leaves it clear; both must answer {@code NO_SEGMENT} there, or moving
     * through the centre would shuffle the preset. {@code WheelGeometry.segmentAt} is the rule the
     * overlay uses for selection, so it is the rule tested.
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

    /**
     * The two layouts differ only in where the plate starts (spec §5.5).
     *
     * <p>{@code DISC} fills the whole sector down to the centre, {@code RING} starts at the band's inner
     * edge. The band itself — {@code innerRadius..outerRadius} — is identical in both, which is why the
     * ring does not move when the player switches mode.
     */
    @Test
    void thePlateStartsAtTheCentreForDiscAndAtTheBandForRing() {
        WheelGeometry.Ring ring = defaultRing();
        assertEquals(0.0, WheelGeometry.plateInnerRadius(ring, true), 1e-9,
                "DISC: one complete disc, sectors reach the centre");
        assertEquals(ring.innerRadius(), WheelGeometry.plateInnerRadius(ring, false), 1e-9,
                "RING: the plate is the band, so the middle stays clear");
    }

    /**
     * {@code DISC} puts each sector's icon and label at that sector's <b>centroid</b> (spec §5.5:
     * 扇形自身的中心点, `(2/3)·R·sinα/α`).
     *
     * <p>Pinned as the formula's value at this wheel's sector angle (α = 36° → ≈ 0.6237 R, not 0.5 R),
     * then per sector: right distance, right bisector. The distinction matters because 0.5 R is the
     * {@code RING} band's mid radius in disguise — a "centroid" that silently equalled it would make the
     * two layouts look identical at the centre and be impossible to spot without eyes.
     */
    @Test
    void discIconsSitAtTheSectorCentroid() {
        WheelGeometry.Ring ring = defaultRing();
        double expectedFraction = (2.0 / 3.0) * Math.sin(Math.toRadians(36.0)) / Math.toRadians(36.0);
        assertEquals(expectedFraction, WheelGeometry.centroidRadiusFraction(), 1e-12);
        assertEquals(0.6237, WheelGeometry.centroidRadiusFraction(), 1e-4);
        assertEquals(ring.outerRadius() * expectedFraction, WheelGeometry.centroidRadius(ring), 1e-9);
        assertTrue(WheelGeometry.centroidRadius(ring) > 0.6 * ring.outerRadius()
                        && WheelGeometry.centroidRadius(ring) < 0.65 * ring.outerRadius(),
                "the centroid is near 0.62 R, not the 0.5 R halfway point and not the band's 0.87 R");

        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            double dx = WheelGeometry.segmentCentroidX(ring, slot) - ring.centerX();
            double dy = WheelGeometry.segmentCentroidY(ring, slot) - ring.centerY();
            assertEquals(expectedFraction * ring.outerRadius(), Math.sqrt(dx * dx + dy * dy), 1e-9,
                    "slot " + slot + " must sit at the centroid radius");
            // clockwiseDegreesFromUp answers in (-180, 180], so normalise before comparing angles.
            double degrees = WheelGeometry.clockwiseDegreesFromUp(dx, dy);
            double normalised = (degrees % 360.0 + 360.0) % 360.0;
            assertEquals(slot * WheelGeometry.sectorDegrees(), normalised, 1e-9,
                    "slot " + slot + " must sit on its own bisector");
            assertEquals(slot, WheelGeometry.segmentAtAngle(degrees),
                    "and that direction must select it, which is what the overlay does");
        }
    }

    /** The formula is the textbook one: it tends to 2/3 R as the sector narrows, and is not 1/2. */
    @Test
    void theCentroidFormulaIsTheRealOne() {
        assertEquals(2.0 / 3.0, WheelGeometry.centroidRadiusFraction(1.0e-4), 1e-6);
        assertEquals(0.66663, WheelGeometry.centroidRadiusFraction(1.0), 1e-4);
        assertEquals(0.42441, WheelGeometry.centroidRadiusFraction(90.0), 1e-4);
        assertEquals(2.0 / 3.0 * Math.sin(Math.PI / 2.0) / (Math.PI / 2.0),
                WheelGeometry.centroidRadiusFraction(90.0), 1e-12);
        assertTrue(WheelGeometry.centroidRadiusFraction(36.0) < WheelGeometry.centroidRadiusFraction(10.0),
                "a wider sector pulls its centroid inwards");
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
