package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.config.HudAnchor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the UB gauge lands, and what it must never cover (spec §7: HUD 默认避开 hotbar 与右上角).
 *
 * <h2>Why a sweep and not one size</h2>
 * "Does not collide with the hotbar" depends on the GUI scale and window size: a 182 px hotbar
 * centred in a 320 px GUI leaves very different corners than one centred in a 1920 px GUI. A single
 * screenshot would prove one of those sizes; a loop over the realistic range proves the claim.
 */
class HudLayoutTest {

    private static final int[] WIDTHS = {320, 427, 512, 640, 854, 1280, 1920, 2560};
    private static final int[] HEIGHTS = {240, 360, 480, 720, 1080, 1440};

    @Test
    void defaultAnchorAvoidsTheHotbarAndTheTopRightCornerEverywhere() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                HudLayout.Rect widget = HudLayout.anchor(width, height);
                assertFalse(widget.intersects(HudLayout.hotbar(width, height)),
                        "default HUD must clear the hotbar at " + width + "x" + height
                                + " (widget " + widget + ")");
                assertFalse(widget.intersects(HudLayout.topRightCorner(width)),
                        "default HUD must clear the top-right corner at " + width + "x" + height
                                + " (widget " + widget + ")");
            }
        }
    }

    @Test
    void widgetStaysOnScreenEvenWhereTheSizingIsTight() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                HudLayout.Rect widget = HudLayout.anchor(width, height);
                assertTrue(widget.x() >= 0 && widget.right() <= width,
                        "widget overflows horizontally at " + width + "x" + height + ": " + widget);
                assertTrue(widget.y() >= 0 && widget.bottom() <= height,
                        "widget overflows vertically at " + width + "x" + height + ": " + widget);
            }
        }
    }

    @Test
    void bottomAnchorsStayAboveTheHotbarBand() {
        for (HudAnchor anchor : new HudAnchor[] {HudAnchor.BOTTOM_LEFT, HudAnchor.BOTTOM_RIGHT}) {
            HudLayout.Rect widget = HudLayout.anchor(640, 360, anchor, 0, 0,
                    HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT);
            assertTrue(widget.bottom() <= 360 - HudLayout.BOTTOM_RESERVED + HudLayout.MARGIN,
                    anchor + " must sit above the reserved bottom band: " + widget);
        }
    }

    @Test
    void cornersAreWhereTheyClaimToBe() {
        assertEquals(new HudLayout.Rect(HudLayout.MARGIN, HudLayout.MARGIN,
                        HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT),
                HudLayout.anchor(640, 360, HudAnchor.TOP_LEFT, 0, 0,
                        HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT));
        assertEquals(640 - HudLayout.MARGIN - HudLayout.WIDGET_WIDTH,
                HudLayout.anchor(640, 360, HudAnchor.BOTTOM_RIGHT, 0, 0,
                        HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT).x());
    }

    @Test
    void offsetsAreAppliedOnTopOfTheAnchor() {
        HudLayout.Rect moved = HudLayout.anchor(640, 360, HudAnchor.TOP_LEFT, 7, 11,
                HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT);
        assertEquals(HudLayout.MARGIN + 7, moved.x());
        assertEquals(HudLayout.MARGIN + 11, moved.y());
    }

    @Test
    void rectIntersectionExcludesTouchingEdges() {
        HudLayout.Rect left = new HudLayout.Rect(0, 0, 10, 10);
        assertTrue(left.intersects(new HudLayout.Rect(5, 5, 10, 10)));
        assertFalse(left.intersects(new HudLayout.Rect(10, 0, 10, 10)), "touching is not overlapping");
        assertFalse(left.intersects(new HudLayout.Rect(0, 10, 10, 10)));
    }

    @Test
    void chargeFractionClampsAndSurvivesGarbage() {
        assertEquals(0.0, HudLayout.chargeFraction(0.0f), 1e-9);
        assertEquals(0.5, HudLayout.chargeFraction(50.0f), 1e-9);
        assertEquals(1.0, HudLayout.chargeFraction(100.0f), 1e-9);
        assertEquals(1.0, HudLayout.chargeFraction(500.0f), 1e-9);
        assertEquals(0.0, HudLayout.chargeFraction(-20.0f), 1e-9);
        assertEquals(0.0, HudLayout.chargeFraction(Float.NaN), 1e-9);
    }

    @Test
    void chargeColourIsOpaqueAndEndsOnTheDocumentedColours() {
        assertEquals(0xFF, HudLayout.chargeColor(0.0) >>> 24);
        assertEquals(0xFF, HudLayout.chargeColor(1.0) >>> 24);
        assertEquals(HudLayout.CHARGE_LOW, HudLayout.chargeColor(0.0));
        assertEquals(HudLayout.CHARGE_FULL, HudLayout.chargeColor(1.0));
        assertEquals(HudLayout.CHARGE_LOW, HudLayout.chargeColor(-3.0), "out of range is clamped");
    }
}
