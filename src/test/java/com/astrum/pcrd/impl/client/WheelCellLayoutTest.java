package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The band-fit rule: the icon+label stack must never be taller than the band it is drawn in.
 *
 * <h2>Why this is arithmetic and not a screenshot</h2>
 * The band is 8% of the GUI height — 28.8 px at 360 px, and the pre-overlay stack needed ~26 px, i.e.
 * ~1 px of slack. At smaller GUI heights the stack was taller than the band and overflowed its edges;
 * that was a known latent clipping bug carried by a constant icon radius. So the size is now derived
 * from the band, and the property "the stack fits" is asserted over a sweep of GUI heights instead of
 * being assumed from the one size the human playtested.
 */
class WheelCellLayoutTest {

    /** GUI height the numbers in the javadoc are quoted at: 1080p at GUI scale 3. */
    private static final int GUI_HEIGHT = 360;

    /** The vanilla font's line height, which is what the label really occupies. */
    private static final int TEXT_HEIGHT = 9;

    private static double bandAt(int guiHeight) {
        return WheelGeometry.ring(guiHeight * 16 / 9, guiHeight,
                WheelGeometry.RADIUS_FRACTION_DEFAULT, WheelGeometry.BAND_FRACTION).bandWidth();
    }

    @Test
    void atThePlaytestedSizeNothingShrinks() {
        WheelCellLayout layout = WheelCellLayout.fitBand(bandAt(GUI_HEIGHT), TEXT_HEIGHT);
        assertTrue(layout.showIcon());
        assertEquals(WheelCellLayout.MAX_ICON_RADIUS, layout.iconRadius(), 1e-9,
                "360 px of GUI height must keep the icon size the human approved");
        assertEquals(TEXT_HEIGHT, layout.textHeight());
        assertTrue(layout.stackHeight() <= bandAt(GUI_HEIGHT),
                "stack " + layout.stackHeight() + " px must fit the band " + bandAt(GUI_HEIGHT) + " px");
    }

    /** The 1 px the old constants had at the playtested size, now stated instead of assumed. */
    @Test
    void thePlaytestedSizeReallyIsTight() {
        double band = bandAt(GUI_HEIGHT);
        WheelCellLayout layout = WheelCellLayout.fitBand(band, TEXT_HEIGHT);
        double slack = band - layout.stackHeight();
        assertTrue(slack > 0.0, "there must be slack at all");
        assertTrue(slack < 3.0, "the fit at 360 px is tight, which is why the shrink rule exists: "
                + slack + " px");
    }

    @Test
    void aThinnerBandShrinksTheIconInsteadOfOverflowing() {
        double band = bandAt(240);
        WheelCellLayout layout = WheelCellLayout.fitBand(band, TEXT_HEIGHT);
        assertTrue(layout.showIcon(), "240 px of GUI height can still afford a smaller icon");
        assertTrue(layout.iconRadius() < WheelCellLayout.MAX_ICON_RADIUS,
                "the icon must shrink with the band, was " + layout.iconRadius());
        assertTrue(layout.iconRadius() >= WheelCellLayout.MIN_ICON_RADIUS);
        assertTrue(layout.stackHeight() <= band,
                "stack " + layout.stackHeight() + " px must fit the band " + band + " px");
    }

    @Test
    void aBandTooThinForEvenAMinimalIconFallsBackToTheLabelAlone() {
        double band = 10.0;
        WheelCellLayout layout = WheelCellLayout.fitBand(band, TEXT_HEIGHT);
        assertFalse(layout.showIcon(), "no icon at 10 px of band");
        assertEquals(0.0, layout.iconRadius(), 1e-9);
        assertEquals(-TEXT_HEIGHT / 2.0, layout.labelOffsetY(), 1e-9,
                "the label is centred on the segment instead");
        assertEquals(TEXT_HEIGHT, layout.stackHeight(), 1e-9);
    }

    /**
     * The whole point: over every GUI height a player can plausibly have, whatever this returns fits.
     *
     * <p>Only "no icon and not even a text line fits" is exempt, which happens below ~150 px of GUI
     * height — a window smaller than the game's own minimum, where nothing can be drawn legibly anyway.
     */
    @Test
    void theStackNeverExceedsItsBandAcrossGuiHeights() {
        for (int guiHeight = 120; guiHeight <= 1080; guiHeight += 10) {
            double band = bandAt(guiHeight);
            WheelCellLayout layout = WheelCellLayout.fitBand(band, TEXT_HEIGHT);
            assertTrue(layout.stackHeight() <= band,
                    "at " + guiHeight + " px of GUI height the stack is " + layout.stackHeight()
                            + " px but the band is only " + band + " px");
            if (layout.showIcon()) {
                assertTrue(layout.iconRadius() <= WheelCellLayout.MAX_ICON_RADIUS + 1e-9,
                        "the icon never grows past the playtested size");
                assertTrue(layout.iconRadius() >= WheelCellLayout.MIN_ICON_RADIUS - 1e-9,
                        "an icon is either a real one or none at all");
            }
        }
    }

    /** {@code DISC} draws at the centroid, where nothing bounds the height: always the full stack. */
    @Test
    void theDiscModeStackIsAlwaysFullSize() {
        WheelCellLayout layout = WheelCellLayout.unconstrained(TEXT_HEIGHT);
        assertTrue(layout.showIcon());
        assertEquals(WheelCellLayout.MAX_ICON_RADIUS, layout.iconRadius(), 1e-9);
        assertEquals(WheelCellLayout.fitBand(bandAt(GUI_HEIGHT), TEXT_HEIGHT), layout,
                "the same stack as RING at the playtested size, so the two modes look like one design");
    }

    /** The stack is centred: icon centre above the middle, label top below it. */
    @Test
    void theStackIsVerticallyCentredOnTheSegment() {
        WheelCellLayout layout = WheelCellLayout.unconstrained(TEXT_HEIGHT);
        double top = layout.iconOffsetY() - layout.iconRadius();
        double bottom = layout.labelOffsetY() + layout.textHeight();
        assertEquals(-bottom, top, 1e-9, "as much above the middle as below it");
        assertEquals(-5.0, layout.iconOffsetY(), 1e-9, "the pre-overlay icon offset, preserved");
    }
}
