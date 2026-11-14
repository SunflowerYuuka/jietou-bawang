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

    /**
     * Gauge bar height in GUI pixels.
     *
     * <p>Raised from 5 when the bar became official art ({@code GaugeArt}): the PCR gauge's gold rim and
     * white core are three distinct bands, and at 5 pixels they collapsed into one yellow line. 10 is
     * the smallest height that keeps all three readable while leaving the widget shorter than the
     * hotbar it sits above. The committed texture is 128x16, so it is downscaled 16 → 10, not crushed.
     */
    public static final int BAR_HEIGHT = 10;

    /**
     * Diameter of the cooldown ring in GUI pixels — and of the preset portrait drawn inside it.
     *
     * <p>Raised from 13 for the same reason as {@link #BAR_HEIGHT}: the human asked for the current
     * preset's face in this circle (「UB条最左边的圆圈，显示的应该是当前预设的角色的头像」), and a face is
     * not recognisable at 9 pixels. At 16 the face is 12 pixels across, which is the size the party
     * effect's own HUD icon is drawn at.
     */
    public static final int RING_DIAMETER = 16;

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

    /**
     * The bar has <b>no colour of its own</b> any more — and that is the fix, not an omission.
     *
     * <p>This class used to expose a {@code CHARGE_LOW → CHARGE_FULL} green-to-gold ramp
     * ({@code chargeColor(fraction)}) that the HUD painted over the gauge art as a translucent wash, to give
     * the bar a cold-to-hot reading. When the bar became the game's own <b>fever bar</b>
     * ({@code pct_fever_bar.png}), that reading moved into the art: the official sprite is a rainbow ramp
     * across its own width, so a wash over it muted exactly the gradient the human asked to see
     * (「UB槽的UI不行，用官方的」). The wash is gone, the ramp is therefore unreachable, and a tested-but-unused
     * ramp is worse than no ramp — it reads as "the tint is still there somewhere".
     *
     * <p>What is left is the one reading the art does not carry: {@code ready}, the state
     * {@code UbHud} draws as a gold outline when the bar is full <em>and</em> the cooldown has ended. That colour
     * lives in {@code UbHud.READY_OUTLINE}, next to the four {@code fill} calls that use it, rather than here as a
     * second constant nothing derives from.
     */

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
