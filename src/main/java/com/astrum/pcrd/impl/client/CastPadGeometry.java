package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.ub.WheelSlots;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where the cast pad's five chips are on screen (spec §5.5: 释放键长按 → 轻量的槽位高亮条，不画整圈轮盘).
 *
 * <h2>Why the pad has its own geometry, and not a smaller ring</h2>
 * {@link WheelGeometry} answers angle questions — which sector is this direction, where is a sector's
 * centroid — because the ring is a radial menu. The pad is a <b>row</b>: it exists to answer one
 * question mid-combat ("which number fires which slot?") with the least screen noise, so it has no
 * angles and no radius, and reusing the ring's arithmetic would mean pretending it does. What it does
 * share with the ring is the slot order: chip {@code i} is party slot {@code i}, the same index the
 * ring draws at sector {@code i} and the same one {@code UbCastPayload} carries.
 *
 * <h2>Why the numbers are here and not in the renderer</h2>
 * "Five chips, centred, in party order, clear of the hotbar, on screen at every GUI size" is a claim
 * about rectangles, which a screenshot can illustrate but not prove. As constants and pure functions
 * it is asserted in {@code CastPadGeometryTest} across a sweep of GUI sizes, and {@code CastPadOverlay}
 * is then only rectangles and text at these coordinates.
 *
 * <h2>The layout</h2>
 * <pre>
 *   ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐      each chip: a digit, an icon or glyph, and a plate
 *   │ 1  │ │ 2  │ │ 3  │ │ 4  │ │ 5  │      that is brighter when the slot is occupied
 *   └────┘ └────┘ └────┘ └────┘ └────┘
 *            centred, one VERTICAL_OFFSET below the crosshair
 * </pre>
 *
 * <p>Below the centre and not under the hotbar: the player's eyes are on the crosshair while holding a
 * combat key, so the pad belongs in peripheral vision just under it — and the hotbar is already dense
 * with vanilla information. The strip is small on purpose ({@value #CHIP_WIDTH}×{@value #CHIP_HEIGHT}
 * per chip), so it reads as a hint rather than a menu.
 */
@ApiStatus.Internal
public final class CastPadGeometry {

    /** Chips on the strip: one per {@link Party} slot, exactly like the ring's segments. */
    public static final int CHIPS = Party.SLOTS;

    /** Width of one chip, in GUI pixels. */
    public static final double CHIP_WIDTH = 24.0;

    /** Height of one chip, in GUI pixels: enough for one digit over one icon. */
    public static final double CHIP_HEIGHT = 28.0;

    /** Gap between two chips, in GUI pixels. */
    public static final double CHIP_GAP = 4.0;

    /**
     * How far below the screen centre the strip's centre line sits, as a fraction of screen height.
     *
     * <p>A small offset on purpose: the pad must not cover the crosshair, must not leave peripheral
     * vision, and must not drift towards the hotbar as the window grows. 0.12 of 360 px (a 1080p window
     * at GUI scale 3) is 43 px — just under the crosshair.
     */
    public static final double VERTICAL_OFFSET = 0.12;

    /** Returned by {@link #chipAt} when the point is not over any chip. */
    public static final int NO_CHIP = -1;

    private CastPadGeometry() {
    }

    /**
     * One chip's rectangle, in GUI pixels.
     *
     * @param centerX centre x
     * @param centerY centre y
     * @param width   width
     * @param height  height
     */
    public record Chip(double centerX, double centerY, double width, double height) {

        /** {@return the left edge} */
        public double left() {
            return centerX - width / 2.0;
        }

        /** {@return the right edge} */
        public double right() {
            return centerX + width / 2.0;
        }

        /** {@return the top edge} */
        public double top() {
            return centerY - height / 2.0;
        }

        /** {@return the bottom edge} */
        public double bottom() {
            return centerY + height / 2.0;
        }

        /**
         * {@return whether a point falls inside this chip}
         *
         * @param x GUI x
         * @param y GUI y
         */
        public boolean contains(double x, double y) {
            return x >= left() && x <= right() && y >= top() && y <= bottom();
        }
    }

    /** {@return the whole strip's width in GUI pixels: five chips and the gaps between them} */
    public static double stripWidth() {
        return CHIPS * CHIP_WIDTH + (CHIPS - 1) * CHIP_GAP;
    }

    /** {@return the strip's centre x, i.e. the screen centre: the pad is horizontally centred} */
    public static double stripCenterX(int screenWidth) {
        return screenWidth / 2.0;
    }

    /** {@return the strip's centre y: below the crosshair by {@link #VERTICAL_OFFSET}} */
    public static double stripCenterY(int screenHeight) {
        return screenHeight / 2.0 + screenHeight * VERTICAL_OFFSET;
    }

    /**
     * {@return the chip for one slot}
     *
     * @param screenWidth  GUI-scaled screen width
     * @param screenHeight GUI-scaled screen height
     * @param index        slot index in {@code [0, CHIPS)}
     */
    public static Chip chip(int screenWidth, int screenHeight, int index) {
        double left = stripCenterX(screenWidth) - stripWidth() / 2.0 + index * (CHIP_WIDTH + CHIP_GAP);
        return new Chip(left + CHIP_WIDTH / 2.0, stripCenterY(screenHeight), CHIP_WIDTH, CHIP_HEIGHT);
    }

    /**
     * {@return the digit that fires a slot, {@code 1}–{@code 5}}
     *
     * <p>Read out of {@link WheelSlots#FIRST_DIGIT} rather than written as {@code index + 1} here: the
     * pad shows the number the player must press, so it must use the same convention the input path
     * decodes — a pad drawing "1" over the slot that {@code 2} fires would be worse than no pad.
     *
     * @param index slot index in {@code [0, CHIPS)}
     */
    public static int digit(int index) {
        return WheelSlots.FIRST_DIGIT + index;
    }

    /**
     * Which chip a point is over.
     *
     * @param screenWidth  GUI-scaled screen width
     * @param screenHeight GUI-scaled screen height
     * @param x            GUI x of the point
     * @param y            GUI y of the point
     * @return the slot index in {@code [0, CHIPS)}, or {@link #NO_CHIP}
     */
    public static int chipAt(int screenWidth, int screenHeight, double x, double y) {
        for (int index = 0; index < CHIPS; index++) {
            if (chip(screenWidth, screenHeight, index).contains(x, y)) {
                return index;
            }
        }
        return NO_CHIP;
    }
}
