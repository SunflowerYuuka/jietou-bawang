package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.client.BookLayout.Box;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>source rectangle</b> to sample so a picture covers a box without being distorted — the pure half
 * of the 角色册's still background.
 *
 * <h2>The word "cover", made arithmetic</h2>
 * The human asked for 「完全立绘使用still_unit_编号」 and 「显示完全的角色立绘当背景」: the selected
 * character's full-body still fills the panel. The still is 16:9 (512x288 committed) and the panel's body
 * is roughly 1.37:1, so <b>something</b> has to give. Three options, and only one is right:
 *
 * <ul>
 *   <li><b>stretch</b> the whole still into the box — the character is squashed vertically. Wrong: the
 *       art is a likeness of a person, and a 16:9→1.37 squeeze is visible on a face.</li>
 *   <li><b>contain</b> — fit the whole still and leave bars. Wrong here: the panel's job is to be filled,
 *       and letterboxing a background reads as "the picture failed to load".</li>
 *   <li><b>cover</b> — scale until the box is filled and crop the overflow symmetrically. Chosen: the
 *       aspect of every drawn pixel is preserved, and the crop is centred, so her face (which is in the
 *       middle of every official still) survives.</li>
 * </ul>
 *
 * <h2>Why this is a class and not two multiplications at the call site</h2>
 * The same reason {@link NineSlice} exists: three numbers must agree (source offset, source extent, and
 * the fact that the extent is inside the source), a mistake in any of them draws a stretched or
 * out-of-bounds crop, and this project cannot look at a screen to notice. {@code StillCoverTest} asserts
 * the properties instead: the crop keeps the source's aspect within a pixel, it is centred, it always
 * lies inside the source, and the two panel tiers both produce a non-empty crop.
 */
@ApiStatus.Internal
public final class StillCover {

    /**
     * The still's committed width in pixels: the derived 512x288 copy {@code tools/gen_still_backgrounds.py}
     * writes, not the official 1408x792 original.
     *
     * <p>Lives here rather than on the drawing class because every number in this file is about <em>where the
     * still's pixels come from</em>: the crop's arithmetic needs the source size, and keeping the two apart is how
     * a re-export at another size silently crops the wrong region.
     */
    public static final int STILL_WIDTH = 512;

    /** The still's committed height in pixels. */
    public static final int STILL_HEIGHT = 288;

    /**
     * How far the still's box is inset from the panel's outer edge, in GUI pixels: one frame cell.
     *
     * <p>The frame's ink occupies {@link NineSlice#PIECE_PIXELS} pixels on each side, so the picture stops there
     * rather than running under the stroke. This is deliberately <b>not</b> the slice: the slice is where the
     * corner is <em>placed</em> on screen (30 in the comfortable tier), while the cell is what the art's stroke
     * actually covers. Using the slice would leave a band of floor between the picture and the frame.
     */
    public static final int BODY_INSET = NineSlice.PIECE_PIXELS;

    private StillCover() {
    }

    /**
     * {@return the box the still is drawn in: the panel inset by one frame cell}
     *
     * <p>Pinned for degenerate panels too: the inset is clamped to half the smaller side, so a one-pixel panel
     * answers a zero-sized box rather than a negative one.
     *
     * @param panel the panel's outer rectangle
     */
    public static Box bodyOf(Box panel) {
        int inset = Math.max(0, Math.min(BODY_INSET,
                Math.min(panel.width() / 2, panel.height() / 2)));
        return new Box(panel.x() + inset, panel.y() + inset, panel.width() - 2 * inset,
                panel.height() - 2 * inset);
    }

    /**
     * A crop of a source image, in source pixels.
     *
     * @param x      left edge of the crop
     * @param y      top edge of the crop
     * @param width  crop width, at least 1
     * @param height crop height, at least 1
     */
    public record Crop(int x, int y, int width, int height) {

        /** {@return the first column past the crop} */
        public int right() {
            return x + width;
        }

        /** {@return the first row past the crop} */
        public int bottom() {
            return y + height;
        }

        /** {@return the crop's aspect ratio, width / height} */
        public double aspect() {
            return width / (double) height;
        }
    }

    /**
     * {@return the source crop that covers a {@code destWidth x destHeight} box}
     *
     * <p>Degenerate input is refused rather than drawn: a source or destination with no area, and a
     * {@code NaN} or infinite size, all answer a 1x1 crop at the origin. The caller cannot reach those
     * cases from the layout (both tiers are real rectangles), but a background is drawn every frame, and
     * a crash there costs the player their screen while a 1x1 crop costs them one pixel.
     *
     * @param sourceWidth  the texture's pixel width
     * @param sourceHeight the texture's pixel height
     * @param destWidth    the box's width in GUI pixels
     * @param destHeight   the box's height in GUI pixels
     */
    public static Crop of(int sourceWidth, int sourceHeight, int destWidth, int destHeight) {
        if (!isUsable(sourceWidth) || !isUsable(sourceHeight) || !isUsable(destWidth)
                || !isUsable(destHeight)) {
            return new Crop(0, 0, 1, 1);
        }
        double sourceAspect = sourceWidth / (double) sourceHeight;
        double destAspect = destWidth / (double) destHeight;
        if (destAspect > sourceAspect) {
            // The box is wider than the art: keep the full width and crop top and bottom.
            int height = Math.max(1, (int) Math.round(sourceWidth / destAspect));
            height = Math.min(height, sourceHeight);
            return new Crop(0, (sourceHeight - height) / 2, sourceWidth, height);
        }
        // Taller or equal: keep the full height and crop the sides.
        int width = Math.max(1, (int) Math.round(sourceHeight * destAspect));
        width = Math.min(width, sourceWidth);
        return new Crop((sourceWidth - width) / 2, 0, width, sourceHeight);
    }

    /**
     * {@return the same crop for a box, so the screen does not pass two numbers it already has}
     *
     * @param sourceWidth  the texture's pixel width
     * @param sourceHeight the texture's pixel height
     * @param box          the destination
     */
    public static Crop of(int sourceWidth, int sourceHeight, Box box) {
        return of(sourceWidth, sourceHeight, box.width(), box.height());
    }

    /** {@return whether a size can be cropped from} */
    private static boolean isUsable(int size) {
        return size > 0;
    }
}
