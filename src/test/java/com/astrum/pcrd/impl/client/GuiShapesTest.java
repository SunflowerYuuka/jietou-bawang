package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
