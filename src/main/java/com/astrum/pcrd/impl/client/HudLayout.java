package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.config.HudAnchor;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where the UB gauge sits, as arithmetic (spec §7: HUD 位置可配置、可整体关闭、默认避开 hotbar 与右上角).
 *
 * <h2>Why the reserved rectangles are here</h2>
 * "Does not collide with the hotbar" is a claim about two rectangles, so it is written as two
 * rectangles and asserted in {@code HudLayoutTest} across a sweep of GUI sizes: the default anchor
 * never intersects the vanilla hotbar ({@value #HOTBAR_WIDTH}×{@value #HOTBAR_HEIGHT}, bottom
 * centre) nor the top-right corner where Jade/AppleSkin draw. A screenshot would show one size; the
 * test covers the whole range a player can actually run.
 *
 * <p>The same numbers are used by {@link UbHud} to lay the widget out, so the assertion and the
 * drawing cannot drift apart.
 */
@ApiStatus.Internal
public final class HudLayout {

    /** Gap between the widget and the screen edge. */
    public static final int MARGIN = 6;

    /**
     * Height reserved at the bottom of the screen that a bottom anchor must stay above.
     *
     * <p>Vanilla's hotbar is 22 px tall and the experience bar sits just above it; 32 px clears both
     * plus the selected-item name shadow, at every GUI scale, without needing to know whether the
     * player has the experience bar shown.
     */
    public static final int BOTTOM_RESERVED = 32;

    /** Vanilla hotbar width in GUI pixels. */
    public static final int HOTBAR_WIDTH = 182;

    /** Vanilla hotbar height in GUI pixels. */
    public static final int HOTBAR_HEIGHT = 22;

    /** Horizontal extent of the top-right corner reserved for other mods' HUDs. */
    public static final int TOP_RIGHT_RESERVED_WIDTH = 150;

    /** Vertical extent of the top-right corner reserved for other mods' HUDs. */
    public static final int TOP_RIGHT_RESERVED_HEIGHT = 48;

    /** Gauge bar width in GUI pixels. */
    public static final int BAR_WIDTH = 90;

    /** Gauge bar height in GUI pixels. */
    public static final int BAR_HEIGHT = 5;

    /** Diameter of the cooldown ring in GUI pixels. */
    public static final int RING_DIAMETER = 13;

    /** Gap between the cooldown ring and the gauge bar. */
    public static final int GAP = 3;

    /** Total widget width: cooldown ring, gap, bar. */
    public static final int WIDGET_WIDTH = RING_DIAMETER + GAP + BAR_WIDTH;

    /** Total widget height: the taller of the ring and the bar. */
    public static final int WIDGET_HEIGHT = Math.max(RING_DIAMETER, BAR_HEIGHT);

    private HudLayout() {
    }

    /**
     * A rectangle in GUI pixels.
     *
     * @param x      left edge
     * @param y      top edge
     * @param width  width
     * @param height height
     */
    public record Rect(int x, int y, int width, int height) {

        /** {@return the right edge, exclusive} */
        public int right() {
            return x + width;
        }

        /** {@return the bottom edge, exclusive} */
        public int bottom() {
            return y + height;
        }

        /**
         * {@return whether this rectangle overlaps {@code other}, touching edges excluded}
         *
         * @param other the rectangle to compare against
         */
        public boolean intersects(Rect other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }
    }

    /** {@return the vanilla hotbar's rectangle for a given GUI size} */
    public static Rect hotbar(int screenWidth, int screenHeight) {
        return new Rect(screenWidth / 2 - HOTBAR_WIDTH / 2, screenHeight - HOTBAR_HEIGHT,
                HOTBAR_WIDTH, HOTBAR_HEIGHT);
    }

    /** {@return the top-right corner other HUD mods use, for a given GUI size} */
    public static Rect topRightCorner(int screenWidth) {
        return new Rect(screenWidth - TOP_RIGHT_RESERVED_WIDTH, 0,
                TOP_RIGHT_RESERVED_WIDTH, TOP_RIGHT_RESERVED_HEIGHT);
    }

    /**
     * Places the widget.
     *
     * @param screenWidth  GUI-scaled screen width
     * @param screenHeight GUI-scaled screen height
     * @param anchor       configured corner
     * @param offsetX      configured extra x, positive right
     * @param offsetY      configured extra y, positive down
     * @param width        widget width, normally {@link #WIDGET_WIDTH}
     * @param height       widget height, normally {@link #WIDGET_HEIGHT}
     * @return the widget's rectangle, clamped so it stays on screen
     */
    public static Rect anchor(int screenWidth, int screenHeight, HudAnchor anchor, int offsetX,
            int offsetY, int width, int height) {
        int x = switch (anchor) {
            case BOTTOM_LEFT, TOP_LEFT -> MARGIN;
            case BOTTOM_RIGHT, TOP_RIGHT -> screenWidth - MARGIN - width;
        };
        int y = switch (anchor) {
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - MARGIN - BOTTOM_RESERVED - height;
            case TOP_LEFT, TOP_RIGHT -> MARGIN;
        };
        return new Rect(clamp(x + offsetX, 0, Math.max(0, screenWidth - width)),
                clamp(y + offsetY, 0, Math.max(0, screenHeight - height)), width, height);
    }

    /**
     * Places the widget where the default anchor puts it.
     *
     * @param screenWidth  GUI-scaled screen width
     * @param screenHeight GUI-scaled screen height
     */
    public static Rect anchor(int screenWidth, int screenHeight) {
        return anchor(screenWidth, screenHeight, HudAnchor.BOTTOM_LEFT, 0, 0, WIDGET_WIDTH, WIDGET_HEIGHT);
    }

    /**
     * {@return how much of the gauge bar a charge fills, {@code 0.0}–{@code 1.0}}
     *
     * @param charge gauge value, nominally {@code 0..100}; anything outside is clamped rather than
     *               drawn overflowing
     */
    public static double chargeFraction(float charge) {
        if (Float.isNaN(charge)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, charge / 100.0));
    }

    /** Bar colour with an empty gauge. */
    public static final int CHARGE_LOW = 0xFF3FA34D;

    /** Bar colour with a full gauge: gold, the same family as the wheel's ready outline. */
    public static final int CHARGE_FULL = 0xFFFFC93C;

    /**
     * {@return the bar colour for a fill fraction}
     *
     * <p>Green to gold, alpha always {@code FF}: the gauge is information, so it stays opaque, and it
     * never changes hue for anything but the fraction — a colour that also encoded the cooldown would
     * make two different problems look the same.
     *
     * @param fraction filled fraction, {@code 0.0}–{@code 1.0}; clamped
     */
    public static int chargeColor(double fraction) {
        double t = Math.max(0.0, Math.min(1.0, fraction));
        int r = lerp((CHARGE_LOW >> 16) & 0xFF, (CHARGE_FULL >> 16) & 0xFF, t);
        int g = lerp((CHARGE_LOW >> 8) & 0xFF, (CHARGE_FULL >> 8) & 0xFF, t);
        int b = lerp(CHARGE_LOW & 0xFF, CHARGE_FULL & 0xFF, t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, double t) {
        return (int) Math.round(from + (to - from) * t);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
