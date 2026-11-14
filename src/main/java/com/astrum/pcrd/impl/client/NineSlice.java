package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.client.BookLayout.Box;
import com.astrum.pcrd.impl.item.BookTheme;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * The nine destination rectangles of a nine-slice panel, as pure arithmetic.
 *
 * <h2>Why this is a class and not nine {@code blit} calls in the screen</h2>
 * A nine-slice panel is drawn by pasting four corners at their natural size, stretching the four edges
 * along one axis and stretching the centre along both. Every seam in that picture is an arithmetic
 * mistake — a centre one pixel too wide leaves a one-pixel column of whatever was behind the panel, and
 * a corner that starts one pixel late overwrites the edge's first column. This project cannot take
 * screenshots, so "there is no seam" has to be an assertion; {@code NineSliceTest} asserts it by tiling,
 * not by looking.
 *
 * <h2>The layout, and the one property that matters</h2>
 * <pre>
 *   +--------+------------------+--------+
 *   |   TL   |        T         |   TR   |   slice x slice  |  centre width  |  slice x slice
 *   +--------+------------------+--------+
 *   |    L   |        C         |    R   |   centre height
 *   +--------+------------------+--------+
 *   |   BL   |        B         |   BR   |
 *   +--------+------------------+--------+
 * </pre>
 * The property, asserted for every case in the test, is that the nine boxes <b>tile the target
 * exactly</b>: each row's widths sum to the target width, each column's heights sum to the target
 * height, no two boxes overlap, and every box stays inside the target. Half-open rectangles
 * ({@link Box}) are what make that expressible: {@code T.right() == TR.x()} rather than "about equal".
 *
 * <h2>Degenerate sizes, and what is clamped</h2>
 * The slice is clamped to {@code min(slice, width / 2, height / 2)} (integer division). Two consequences,
 * both deliberate:
 * <ul>
 *   <li><b>A target exactly {@code 2 * slice} across</b> keeps its slice and gets a <b>zero-width</b>
 *       centre. That is correct, not a bug: there is no middle to stretch, and the two corners plus the
 *       edge already cover the target. A zero-width box draws nothing and is skipped by the caller.</li>
 *   <li><b>A target smaller than {@code 2 * slice}</b> is not allowed to produce negative widths.
 *       Clamping the slice (rather than only clamping the centre) keeps the four corners square, so a
 *       cramped panel still looks like a frame instead of a smear. A one-pixel target yields slice
 *       {@code 0}: the centre then covers the whole box and the eight slices vanish.</li>
 * </ul>
 * Nothing here is clamped to "at least one pixel"; a zero is a real answer and the screen skips it.
 * Silently widening a zero to one would put a seam <em>outside</em> the target, which is the bug this
 * class exists to prevent.
 */
@ApiStatus.Internal
public final class NineSlice {

    /** How many pieces one panel is made of. */
    public static final int PIECES = 9;

    /**
     * The slice size the <b>comfortable</b> panel art is drawn at, in GUI pixels.
     *
     * <p>The art is nine 64x64 tiles cut from one 192x192 frame, and each tile's ornament ink lives only
     * in its <b>outer ~8 pixels</b> — the rest of the tile is the body's flat gradient. So the number
     * here is really a <em>compression ratio</em> for that 8-pixel band: at the previous 18 the whole
     * 64-pixel tile was squeezed 3.6× and what should be a crisp filigree edge blurred into the body
     * colour, which is exactly what the human reported (「角色册的UI很粗糙，上下像是被压缩了」).
     *
     * <p>30 is 64 / 30 ≈ 2.1×, inside the 26–34 band an earlier audit recommended. {@code 26} (2.5×)
     * was the conservative end and {@code 34} (1.9×) the aggressive one; 30 is chosen because the
     * padding must stay two pixels ahead of it and 34 would have pushed the comfortable panel past a
     * 640x360 window, which is what 1280x720 at GUI scale 2 gives.
     *
     * <p>The <b>compact</b> tier keeps 18 and is not a lapse: {@link BookLayout.Metrics#COMPACT} exists
     * for windows that cannot fit the comfortable panel at all, and there the body is worth more than
     * the ornament. See {@link BookLayout.Metrics#slice()} — the slice is a per-tier number, and this
     * constant is the comfortable one, which the class-level default and every drawing call site read
     * through the layout.
     */
    public static final int DEFAULT_SLICE = 30;

    /** Source edge of one nine-slice piece, in pixels: the art is nine 64x64 tiles of a 192x192 frame. */
    public static final int PIECE_PIXELS = 64;

    /**
     * The slice the <b>comfortable</b> tier's frame is drawn at, named for the tests and the generator.
     *
     * <p>Equal to {@link #DEFAULT_SLICE} by definition — it exists so a test can say "the shipped frame
     * thickness" without depending on which value happens to be the class default.
     */
    public static final int SHIPPED_SLICE = DEFAULT_SLICE;

    private NineSlice() {
    }

    /**
     * The nine destination rectangles of a panel filling {@code target}.
     *
     * @param target    the panel's outer rectangle
     * @param sliceSize the wanted corner/edge thickness in GUI pixels; clamped, never negative
     * @return the nine boxes in row-major order, matching {@link BookTheme.Part}
     */
    public static Rects rects(Box target, int sliceSize) {
        int slice = clampSlice(target, sliceSize);
        int x0 = target.x();
        int y0 = target.y();
        int x1 = x0 + slice;
        int x2 = target.right() - slice;
        int y1 = y0 + slice;
        int y2 = target.bottom() - slice;
        int centreWidth = target.width() - 2 * slice;
        int centreHeight = target.height() - 2 * slice;
        return new Rects(
                new Box(x0, y0, slice, slice),
                new Box(x1, y0, centreWidth, slice),
                new Box(x2, y0, slice, slice),
                new Box(x0, y1, slice, centreHeight),
                new Box(x1, y1, centreWidth, centreHeight),
                new Box(x2, y1, slice, centreHeight),
                new Box(x0, y2, slice, slice),
                new Box(x1, y2, centreWidth, slice),
                new Box(x2, y2, slice, slice));
    }

    /**
     * {@return the slice size actually used, clamped so no rectangle can have a negative extent}
     *
     * <p>{@code min(wanted, width / 2, height / 2)}, floored at zero. Halving (rather than subtracting)
     * is what keeps the four corners the same size as the four edges' thickness; a target of 31 pixels
     * therefore gets slice 15 and a one-pixel centre column, not slice 20 and an eleven-pixel deficit.
     *
     * @param target    the panel's outer rectangle
     * @param sliceSize the wanted thickness
     */
    public static int clampSlice(Box target, int sliceSize) {
        int half = Math.min(target.width() / 2, target.height() / 2);
        return Math.max(0, Math.min(sliceSize, half));
    }

    /**
     * One panel's nine rectangles.
     *
     * <p>Named components rather than a {@code Box[]}: the screen blits nine different textures, and
     * "which one is the left edge" is exactly the kind of positional question an array index answers
     * wrongly once and silently forever.
     *
     * @param topLeft     top-left corner
     * @param top         top edge, stretched horizontally
     * @param topRight    top-right corner
     * @param left        left edge, stretched vertically
     * @param centre      body, stretched both ways; zero-sized on a degenerate target
     * @param right       right edge, stretched vertically
     * @param bottomLeft  bottom-left corner
     * @param bottom      bottom edge, stretched horizontally
     * @param bottomRight bottom-right corner
     */
    public record Rects(Box topLeft, Box top, Box topRight, Box left, Box centre, Box right,
            Box bottomLeft, Box bottom, Box bottomRight) {

        /** {@return the nine boxes in row-major order, matching {@link BookTheme.Part#values()}} */
        public List<Box> all() {
            return List.of(topLeft, top, topRight, left, centre, right, bottomLeft, bottom,
                    bottomRight);
        }

        /**
         * {@return the box for piece {@code index} in {@link BookTheme.Part#values()} order}
         *
         * @param index {@code 0}–{@code 8}
         */
        public Box piece(int index) {
            return switch (index) {
                case 0 -> topLeft;
                case 1 -> top;
                case 2 -> topRight;
                case 3 -> left;
                case 4 -> centre;
                case 5 -> right;
                case 6 -> bottomLeft;
                case 7 -> bottom;
                case 8 -> bottomRight;
                default -> throw new IndexOutOfBoundsException("nine-slice piece " + index);
            };
        }

        /** {@return whether the centre has a drawable area, i.e. the target was wider and taller than 2 slices} */
        public boolean centreIsDrawable() {
            return centre.width() > 0 && centre.height() > 0;
        }

        /**
         * {@return whether every piece that must be drawn has a positive area}
         *
         * <p>The centre is exempt: a target exactly two slices across legitimately has no centre. Used
         * by the screen's own log line, because "the panel we just drew" is the one case no test can
         * cover — the size comes from the player's window.
         */
        public boolean frameIsDrawable() {
            return topLeft.width() > 0 && topLeft.height() > 0 && centre.width() >= 0
                    && centre.height() >= 0;
        }
    }
}
