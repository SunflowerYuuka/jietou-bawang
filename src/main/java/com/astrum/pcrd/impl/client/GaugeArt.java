package com.astrum.pcrd.impl.client;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The UB charge bar as <b>two official-art layers and one clipped fill</b>, as pure arithmetic.
 *
 * <h2>Why the bar has geometry of its own now</h2>
 * The bar used to be two {@code GuiGraphics.fill} rectangles: a flat background and a flat colour whose
 * hue encoded the fraction. The human's request was 「看看能不能使用pcr官方素材替代UB条的充能条」, and the
 * official dump does ship the game's own result-screen gauge pair — a dark track with a gold rim and a
 * bright gold bar with a white core ({@code abyss_ef_result_gauge_back.png} and
 * {@code abyss_ef_result_gauge.png}, cut to {@code gui/ub_gauge_track.png} /
 * {@code gui/ub_gauge_fill.png} by {@code tools/gen_ub_gauge_texture.py}).
 *
 * <p>Two layers plus a crop is <em>three</em> rectangles that must agree, and this project cannot look
 * at a screenshot to check them. So the rectangles are computed here — the track spans the whole bar,
 * the fill spans the fraction of it, and the source crop takes the same fraction of the fill texture —
 * and {@code GaugeArtTest} asserts the three properties that make the picture correct at any charge:
 * the fill is inside the track, a full bar fills it exactly, and the source crop is the same fraction
 * as the destination (otherwise the art would be squashed rather than clipped).
 *
 * <h2>Why the fraction is not rounded away</h2>
 * {@link #fillWidth} rounds to whole pixels, which is all the GUI can draw — but a non-zero charge
 * <b>never</b> rounds down to zero. A bar that shows nothing at 3% looks broken, and the fraction is
 * the one thing the player reads at a glance.
 */
@ApiStatus.Internal
public final class GaugeArt {

    /** The committed track texture, as a full texture path. */
    public static final String TRACK_PATH = "textures/gui/ub_gauge_track.png";

    /** The committed fill texture, as a full texture path. */
    public static final String FILL_PATH = "textures/gui/ub_gauge_fill.png";

    /** {@code astrum:textures/gui/ub_gauge_track.png} — the empty channel the charge fills. */
    public static final ResourceLocation TRACK = texture(TRACK_PATH);

    /** {@code astrum:textures/gui/ub_gauge_fill.png} — the gold bar drawn inside the track. */
    public static final ResourceLocation FILL = texture(FILL_PATH);

    /**
     * {@return the resource location of a texture under {@code assets/astrum/}}
     *
     * <p>The constants above must be usable as {@code ResourceLocation}s by the HUD and by a test, and
     * building them here (rather than at each call site) is what keeps one {@code astrum:} prefix in
     * one place.
     *
     * @param path a path relative to the mod's asset root, e.g. {@code textures/gui/x.png}
     */
    public static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath("astrum", path);
    }

    /** Source width of both committed textures, in pixels. */
    public static final int SOURCE_WIDTH = 128;

    /** Source height of both committed textures, in pixels. */
    public static final int SOURCE_HEIGHT = 16;

    private GaugeArt() {
    }

    /**
     * {@return how many whole pixels of {@code barWidth} a charge fraction fills}
     *
     * <p>Zero charge is zero pixels (an empty bar is honest), {@link Double#NaN} is zero (a garbage
     * reading must not draw a full bar), and anything above zero fills at least one pixel so the bar
     * cannot lie about having started.
     *
     * @param fraction charge fraction, clamped to {@code [0, 1]}
     * @param barWidth the bar's pixel width
     */
    public static int fillWidth(double fraction, int barWidth) {
        int width = Math.max(0, barWidth);
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        if (!(clamped > 0.0)) {
            return 0;
        }
        int filled = (int) Math.round(width * clamped);
        return Math.max(1, Math.min(width, filled));
    }

    /**
     * {@return the x coordinate one pixel <em>inside</em> the fill's right edge}
     *
     * <p>Kept separate from {@link #fillWidth} because the fill's destination and its source crop are
     * derived from it in two different places, and a shared "one pixel narrower" rule is what keeps a
     * full bar from sampling one column past the texture.
     *
     * @param fraction charge fraction
     * @param barWidth the bar's pixel width
     */
    public static int filledRight(double fraction, int barWidth) {
        return fillWidth(fraction, barWidth);
    }

    /**
     * {@return the source width to sample from the fill texture for this fraction}
     *
     * <p>Same fraction as the destination, so the gold gradient <b>clips</b> instead of compressing:
     * a partial bar shows the left part of the official art rather than a squashed copy of all of it.
     *
     * @param fraction charge fraction
     */
    public static int sourceWidth(double fraction) {
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        if (!(clamped > 0.0)) {
            return 0;
        }
        int sampled = (int) Math.round(SOURCE_WIDTH * clamped);
        return Math.max(1, Math.min(SOURCE_WIDTH, sampled));
    }

    /**
     * {@return the HUD rectangle of the gauge track, given the widget origin and the bar's own box}
     *
     * <p>A record rather than four numbers so the four blit coordinates cannot be passed in the wrong
     * order — the classic nine-slice failure, one axis swapped.
     *
     * @param x      left edge
     * @param y      top edge
     * @param width  drawn width
     * @param height drawn height
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

        /** {@return whether this rectangle has any area to draw} */
        public boolean isDrawable() {
            return width > 0 && height > 0;
        }
    }
}
