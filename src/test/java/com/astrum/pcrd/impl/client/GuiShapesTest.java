package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The one place every drawn pixel's coordinates come from: angles to GUI points.
 *
 * <h2>Why four assertions and a cross-check</h2>
 * {@link GuiShapes#pointX} and {@link GuiShapes#pointY} are pure and trivially simple, which is exactly
 * why nothing tested them — and every quad of the wheel, every HUD ring and every cooldown arc is made
 * of their output. One flipped sign (a {@code +cos} instead of {@code -cos}, or {@code sin}/{@code cos}
 * swapped) does not throw, does not warn, and produces a wheel drawn upside down or mirrored: a bug that
 * would otherwise only ever be caught by a human looking at the screen.
 *
 * <p>The cardinal directions pin the convention (0° up, 90° right, clockwise, GUI y grows downwards) and
 * the last test pins that {@link GuiShapes} and {@link WheelGeometry} — which agree on paper — actually
 * agree numerically, so the two cannot drift apart.
 */
class GuiShapesTest {

    private static final double CENTRE_X = 100.0;
    private static final double CENTRE_Y = 50.0;
    private static final double RADIUS = 10.0;
    private static final double EPSILON = 1.0e-9;

    @Test
    void zeroDegreesIsStraightUp() {
        assertEquals(CENTRE_X, GuiShapes.pointX(CENTRE_X, RADIUS, 0.0), EPSILON);
        assertEquals(CENTRE_Y - RADIUS, GuiShapes.pointY(CENTRE_Y, RADIUS, 0.0), EPSILON,
                "up is smaller y in GUI space");
    }

    @Test
    void ninetyDegreesIsToTheRight() {
        assertEquals(CENTRE_X + RADIUS, GuiShapes.pointX(CENTRE_X, RADIUS, 90.0), EPSILON);
        assertEquals(CENTRE_Y, GuiShapes.pointY(CENTRE_Y, RADIUS, 90.0), EPSILON);
    }

    @Test
    void oneHundredEightyDegreesIsStraightDown() {
        assertEquals(CENTRE_X, GuiShapes.pointX(CENTRE_X, RADIUS, 180.0), EPSILON);
        assertEquals(CENTRE_Y + RADIUS, GuiShapes.pointY(CENTRE_Y, RADIUS, 180.0), EPSILON);
    }

    @Test
    void twoHundredSeventyDegreesIsToTheLeft() {
        assertEquals(CENTRE_X - RADIUS, GuiShapes.pointX(CENTRE_X, RADIUS, 270.0), EPSILON);
        assertEquals(CENTRE_Y, GuiShapes.pointY(CENTRE_Y, RADIUS, 270.0), EPSILON);
    }

    @Test
    void theAngleConventionAgreesWithWheelGeometry() {
        WheelGeometry.Ring ring = WheelGeometry.ring(320, 240, WheelGeometry.RADIUS_FRACTION_DEFAULT,
                WheelGeometry.BAND_FRACTION);

        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            double degrees = slot * WheelGeometry.sectorDegrees();
            assertEquals(WheelGeometry.segmentCenterX(ring, slot),
                    GuiShapes.pointX(ring.centerX(), ring.midRadius(), degrees), EPSILON,
                    "slot " + slot + " x: GuiShapes must draw where WheelGeometry says");
            assertEquals(WheelGeometry.segmentCenterY(ring, slot),
                    GuiShapes.pointY(ring.centerY(), ring.midRadius(), degrees), EPSILON,
                    "slot " + slot + " y: GuiShapes must draw where WheelGeometry says");
        }
    }

    /**
     * The quad corners, in the order they are fed to the buffer.
     *
     * <p>Order is not a detail here — see the next test — so it is pinned by coordinates: a 0°..90°
     * slice from radius 1 to radius 2 around the origin must be
     * {@code inner@0°, inner@90°, outer@90°, outer@0°}.
     */
    @Test
    void theQuadCornersAreTheSliceCornersInDrawingOrder() {
        double[][] quad = GuiShapes.ringQuad(0.0, 0.0, 1.0, 2.0, 0.0, 90.0);

        assertEquals(4, quad.length);
        assertPoint(quad[0], 0.0, -1.0, "inner start: straight up at radius 1");
        assertPoint(quad[1], 1.0, 0.0, "inner end: to the right at radius 1");
        assertPoint(quad[2], 2.0, 0.0, "outer end: to the right at radius 2");
        assertPoint(quad[3], 0.0, -2.0, "outer start: straight up at radius 2");
    }

    /**
     * <b>The winding test.</b> {@code RenderType.gui()} is a {@code CompositeState} that sets only a
     * shader, a transparency and a depth-test state, so it keeps the builder's default
     * {@code cullState = RenderStateShard.CULL}: back faces are culled, and a quad wound the other way is
     * not drawn at all.
     *
     * <p>{@code GuiGraphics.fill} emits {@code (x1,y1) (x1,y2) (x2,y2) (x2,y1)} and draws. So the sign of
     * the signed area of <em>that</em> rectangle is the sign every shape here must have. The ball was
     * dropped in the first version of this class — its quads were the mirror image — and the visible
     * result was that the whole wheel plate, and the HUD's cooldown ring, were simply absent while icons,
     * text and vanilla fills drew normally (found by reading a pixel back in a probe; no screenshot).
     *
     * <p>This test is deliberately expressed against vanilla's own corner order rather than against
     * "clockwise": the projection flips y, so hand-reasoning about clockwise/counter-clockwise in GUI
     * space is exactly the way to get it wrong again.
     */
    @Test
    void theQuadWindingMatchesVanillasGuiRectangle() {
        // GuiGraphics.fill(...): (x1,y1) (x1,y2) (x2,y2) (x2,y1) with x1<x2 and y1<y2.
        double[][] vanillaRectangle = {{10.0, 20.0}, {10.0, 30.0}, {40.0, 30.0}, {40.0, 20.0}};
        double vanillaSign = Math.signum(GuiShapes.signedArea(vanillaRectangle));
        assertNotEquals(0.0, vanillaSign, "sanity: the reference rectangle must not be degenerate");

        // One step is 6°, so a real draw call never asks for more: `band` splits the sweep first (pinned
        // by theSliceCountFollowsTheStepSize). A single quad spanning a full turn would be degenerate —
        // its two ends coincide — which is why it is not in this list.
        for (double start : new double[] {0.0, -36.0, 90.0, 180.0, 270.0}) {
            for (double sweep : new double[] {6.0, 36.0, 72.0, -6.0, -72.0}) {
                double[][] quad = GuiShapes.ringQuad(CENTRE_X, CENTRE_Y, 0.0, RADIUS, start,
                        start + sweep);
                assertEquals(vanillaSign, Math.signum(GuiShapes.signedArea(quad)),
                        "a band slice from " + start + "° by " + sweep
                                + "° must wind like vanilla's rectangle, or RenderType.gui()'s cull state"
                                + " discards it entirely");
                assertNotEquals(0.0, GuiShapes.signedArea(quad),
                        "a slice with a real width must have a non-zero area");
            }
        }
    }

    /** The slice count, which is also the vertex count the drawing code reports for its log line. */
    @Test
    void theSliceCountFollowsTheStepSize() {
        assertEquals(60, GuiShapes.stepsFor(0.0, 360.0), "a full turn is 60 slices of 6°");
        assertEquals(12, GuiShapes.stepsFor(-36.0, 36.0), "one wheel sector is 72°");
        assertEquals(15, GuiShapes.stepsFor(90.0, 0.0), "a reversed sweep is counted by its magnitude");
        assertEquals(1, GuiShapes.stepsFor(10.0, 10.0), "a zero sweep is still one quad, never none");
        assertEquals(4, GuiShapes.VERTICES_PER_QUAD);
    }

    private static void assertPoint(double[] actual, double x, double y, String what) {
        assertEquals(x, actual[0], EPSILON, what + " (x)");
        assertEquals(y, actual[1], EPSILON, what + " (y)");
    }
}


