package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * How tall the icon+label stack is allowed to be inside one segment, given the space that segment
 * actually has: the answer to the band-fit problem, as arithmetic.
 *
 * <h2>The problem this exists for</h2>
 * The band is 8% of the GUI height — <b>28.8 px</b> at 360 px of GUI height (1080p at GUI scale 3),
 * and the icon disc (radius 8) plus one 9 px line of text needs about 26 px. That is ~1 px of slack at
 * the size the human playtested, and below roughly 340 px of GUI height the stack would be taller than
 * the band and spill over its edges. That was a known latent clipping bug; carrying it into the overlay
 * knowingly is exactly what these two pure functions avoid.
 *
 * <h2>The decision</h2>
 * Two steps, in this order:
 *
 * <ol>
 *   <li><b>Shrink with the band.</b> The icon radius is derived from the space available
 *       ({@code bandWidth − 2·padding}, minus the gap and the text line), capped at the playtested
 *       8 px. At 360 px of GUI height this returns exactly the old look — nothing about the size the
 *       human approved changes — and at 240 px it produces a smaller icon and label rather than an
 *       overflowing one.</li>
 *   <li><b>Fall back to label-only</b> when even a minimal icon (3 px radius) plus a line of text does
 *       not fit. Below that the segment shows the character's name or the empty-slot dash with no disc,
 *       which is still readable at GUI heights where a 3 px icon would be a smudge.</li>
 * </ol>
 *
 * <p>Only {@code RING} needs this: {@code DISC} draws the stack at the sector centroid, where nothing
 * bounds its height — see {@link #unconstrained(int)}, used so both modes share one type and one set of
 * offsets.
 *
 * @param iconRadius   radius of the icon disc, {@code 0} when there is no icon
 * @param showIcon     whether an icon is drawn at all
 * @param iconOffsetY  vertical offset of the icon <em>centre</em> from the segment centre
 * @param labelOffsetY vertical offset of the label's top edge from the segment centre
 * @param textHeight   one line of the GUI font, in pixels, as used to size this layout
 */
@ApiStatus.Internal
public record WheelCellLayout(double iconRadius, boolean showIcon, double iconOffsetY,
        double labelOffsetY, int textHeight) {

    /** Space kept between the stack and the band's edges, in GUI pixels. */
    public static final double PADDING = 1.0;

    /** Space between the icon disc and the text line, in GUI pixels. */
    public static final double GAP = 1.0;

    /** Icon radius at the playtested size: the value {@code RING} has always drawn. */
    public static final double MAX_ICON_RADIUS = 8.0;

    /** Smallest icon radius worth drawing; below this the label is drawn alone. */
    public static final double MIN_ICON_RADIUS = 3.0;

    /**
     * Fits the stack into a band of the given width, shrinking it if necessary.
     *
     * @param bandWidth  the band's width in GUI pixels, i.e. {@link WheelGeometry.Ring#bandWidth()}
     * @param textHeight one line of the GUI font, in pixels
     */
    public static WheelCellLayout fitBand(double bandWidth, int textHeight) {
        double available = bandWidth - 2.0 * PADDING;
        if (available >= 2.0 * MAX_ICON_RADIUS + GAP + textHeight) {
            return stack(MAX_ICON_RADIUS, textHeight);
        }
        if (available >= 2.0 * MIN_ICON_RADIUS + GAP + textHeight) {
            return stack((available - GAP - textHeight) / 2.0, textHeight);
        }
        return labelOnly(textHeight);
    }

    /**
     * The full-size stack, with no band to fit into ({@code DISC}'s centroid placement).
     *
     * @param textHeight one line of the GUI font, in pixels
     */
    public static WheelCellLayout unconstrained(int textHeight) {
        return stack(MAX_ICON_RADIUS, textHeight);
    }

    /** {@return the label alone, vertically centred on the segment centre} */
    public static WheelCellLayout labelOnly(int textHeight) {
        return new WheelCellLayout(0.0, false, 0.0, -textHeight / 2.0, textHeight);
    }

    /** {@return the total height of the stack this layout draws, in pixels} */
    public double stackHeight() {
        return showIcon ? 2.0 * iconRadius + GAP + textHeight : textHeight;
    }

    /**
     * The full stack, centred on the segment centre: the icon above the middle, the label below it.
     *
     * <p>With {@code r = 8} and a 9 px line this reproduces the pre-overlay layout (icon centre 5 px
     * above the middle, label just under it), which is why the playtested look is preserved.
     */
    private static WheelCellLayout stack(double iconRadius, int textHeight) {
        double iconOffsetY = -(GAP + textHeight) / 2.0;
        double labelOffsetY = iconOffsetY + iconRadius + GAP;
        return new WheelCellLayout(iconRadius, true, iconOffsetY, labelOffsetY, textHeight);
    }
}
