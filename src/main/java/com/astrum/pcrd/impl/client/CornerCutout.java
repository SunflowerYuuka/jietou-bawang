package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * How much of each rounded corner of the 角色册 frame is <b>outside</b> the art, in GUI pixels.
 *
 * <h2>The defect this exists to fix</h2>
 * The human's screenshot showed <b>solid black blocks in all four corners</b> of the book panel, where the
 * frame's rounded ornament should be. The measurement that explains it: the frame art's corner cells are
 * <b>anti-aliased arcs on transparency</b> — the outermost row of the top-left cell is alpha 0 for its
 * first seven pixels before the stroke begins — and {@link OfficialPanel} painted an <b>opaque floor over
 * the whole panel rectangle</b>, corner cut-outs included. That floor filled exactly the area the art
 * leaves empty, and at a 30-pixel slice each 16-pixel cell is magnified 1.9x, which is what turned the
 * wedge into the solid block he photographed. The art was never the problem; the fill under it was.
 *
 * <h2>What "cleared" means</h2>
 * The panel is still a rectangle and its body has to be opaque, or the world shows through the centre.
 * What must stop is the floor painting <em>outside the art's silhouette</em>. So the floor is painted in
 * horizontal spans and, in each row of a corner band, it starts a number of pixels in from the panel edge
 * — that number is what {@link #cleared} returns. Rows between the two corner bands are not inset at all.
 *
 * <h2>Why a table and not a formula</h2>
 * A clean {@code R - sqrt(R^2 - (R-y)^2)} arc does not describe this art: the measured silhouette is
 * stepped ({@code 7 5 4 4 2 2 1 1 0 …}), because it is a hand-drawn one-pixel stroke that was resampled
 * with a box filter. A formula would either leave a rim of floor outside the curve or clip the stroke, and
 * a test cannot see either. The tables <em>are</em> the measurement, taken from the committed sheet by
 * {@link #measured}, and {@code CornerCutoutTest} re-measures the shipped PNG and fails if the two ever
 * disagree — so a re-export of the frame cannot silently invalidate them.
 *
 * <h2>Which way the scaling error is allowed to go</h2>
 * The cell's sixteen measured rows are stretched onto the slice the panel is drawn at, and the result is
 * rounded <b>up</b>. That is deliberate and it is the one asymmetry in this class: clearing one pixel too
 * many costs a pixel of floor under the stroke's own anti-aliased edge, which is invisible; clearing one
 * pixel too few puts a dark speck in the corner, which is the defect. So the tables answer "at least this
 * much", never "at most".
 */
@ApiStatus.Internal
public final class CornerCutout {

    /**
     * The transparent run at the start of each row of the sheet's top-left corner cell, row 0 outermost.
     *
     * <p>Measured from {@code assets/astrum/textures/gui/book_panel.png} — the 16x16 cell at (0,0) of the
     * 48x48 sheet — as "how many pixels of alpha 0 before the first inked pixel". The raw walk gives
     * {@code 7 5 4 4 2 2 1 1 0 0 0 0 0 1 0 0}; the values shown are that walk after {@link #widened}
     * (see there for why the trailing {@code 1} is lifted to the whole tail). A real curve, not a block,
     * which is precisely why the block had to come from the fill rather than from a regenerated sheet.
     */
    static final int[] TOP_LEFT = {7, 5, 4, 4, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0};

    /** The top-right corner cell's run, mirrored: index 0 here is the panel's right edge. */
    static final int[] TOP_RIGHT = {6, 5, 4, 4, 2, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0};

    /** The bottom-left corner cell's run, mirrored: index 0 here is the panel's bottom edge. */
    static final int[] BOTTOM_LEFT = {6, 5, 4, 4, 2, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0};

    /** The bottom-right corner cell's run, mirrored on both axes. */
    static final int[] BOTTOM_RIGHT = {5, 5, 5, 2, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0};

    /** Index of the top-left corner in {@link NineSlice#PIECE_NAMES}. */
    public static final int TOP_LEFT_INDEX = 0;

    /** Index of the top-right corner in {@link NineSlice#PIECE_NAMES}. */
    public static final int TOP_RIGHT_INDEX = 2;

    /** Index of the bottom-left corner in {@link NineSlice#PIECE_NAMES}. */
    public static final int BOTTOM_LEFT_INDEX = 6;

    /** Index of the bottom-right corner in {@link NineSlice#PIECE_NAMES}. */
    public static final int BOTTOM_RIGHT_INDEX = 8;

    private CornerCutout() {
    }

    /** {@return the four corner indices, in {@link NineSlice#PIECE_NAMES} order} */
    public static int[] corners() {
        return new int[] {TOP_LEFT_INDEX, TOP_RIGHT_INDEX, BOTTOM_LEFT_INDEX, BOTTOM_RIGHT_INDEX};
    }

    /** {@return whether {@code piece} is one of the four corners} */
    public static boolean isCorner(int piece) {
        return piece == TOP_LEFT_INDEX || piece == TOP_RIGHT_INDEX || piece == BOTTOM_LEFT_INDEX
                || piece == BOTTOM_RIGHT_INDEX;
    }

    /**
     * {@return the measured table for one of the four corners}
     *
     * @param piece one of the four {@code *_INDEX} constants
     */
    public static int[] table(int piece) {
        return switch (piece) {
            case TOP_LEFT_INDEX -> TOP_LEFT;
            case TOP_RIGHT_INDEX -> TOP_RIGHT;
            case BOTTOM_LEFT_INDEX -> BOTTOM_LEFT;
            case BOTTOM_RIGHT_INDEX -> BOTTOM_RIGHT;
            default -> throw new IllegalArgumentException("not a corner piece: " + piece);
        };
    }

    /**
     * {@return how many pixels of {@code row} the body floor must leave unpainted}
     *
     * <p>{@code row} is counted <b>inward from the panel's outer edge</b> of that corner: {@code 0} is the
     * outermost row of the corner band — the panel's top edge for the two top corners, its bottom edge for
     * the two bottom ones. A {@code row} outside {@code [0, slice)} is refused rather than answered: the
     * middle of the panel is not a corner and must be filled edge to edge, so a silent 0 there would hide
     * exactly the kind of off-by-one this class exists to prevent.
     *
     * @param piece the corner, one of the four {@code *_INDEX} constants
     * @param row   0-based row within the corner band, from the panel edge inward
     * @param slice the nine-slice thickness the panel is drawn at, {@link BookLayout#slice()}
     */
    public static int cleared(int piece, int row, int slice) {
        if (slice <= 0) {
            return 0;
        }
        if (row < 0 || row >= slice) {
            throw new IllegalArgumentException(
                    "row " + row + " is outside the corner band [0," + slice + ")");
        }
        int[] table = table(piece);
        // Which of the cell's own sixteen rows this GUI row samples. Floor division, so the outermost GUI
        // rows all take the cell's outermost — widest — row rather than a narrower one further in.
        int source = Math.min(table.length - 1, row * table.length / slice);
        // Round up: see "which way the scaling error is allowed to go" in the class javadoc.
        int scaled = (table[source] * slice + NineSlice.PIECE_PIXELS - 1) / NineSlice.PIECE_PIXELS;
        return Math.min(slice, scaled);
    }

    /**
     * {@return the transparent run at the start of every row of one corner cell, exactly as measured}
     *
     * <p>The honest number, with no tidying up: this is what the art says. The caller passes the cell
     * already normalised to "row 0 is the panel's outer edge, index 0 is the panel's left edge", so this
     * needs no knowledge of which corner it is looking at.
     *
     * <p>Not monotone — the bottom-right cell reads {@code 5 4 5 2 …}, because a hand-drawn arc does not
     * step evenly. See {@link #widened} for the form the tables use.
     *
     * @param alpha the corner cell's alpha, row-major, {@code cell * cell} entries, 0-255
     * @param cell  the cell's edge in pixels, {@link NineSlice#PIECE_PIXELS}
     */
    static int[] measured(int[] alpha, int cell) {
        if (alpha == null || alpha.length != cell * cell) {
            throw new IllegalArgumentException("expected a " + cell + "x" + cell + " alpha cell");
        }
        int[] runs = new int[cell];
        for (int v = 0; v < cell; v++) {
            int u = 0;
            while (u < cell && alpha[v * cell + u] == 0) {
                u++;
            }
            runs[v] = u;
        }
        return runs;
    }

    /**
     * {@return {@code runs} made non-increasing by lifting each row to the widest row inside it}
     *
     * <h2>Why widening is safe, and why it is needed</h2>
     * Widening only ever asks the floor to clear <em>more</em>, so the guarantee "the floor never paints
     * outside the silhouette" survives it untouched.
     *
     * <p>It is needed because the measured walk is not monotone: the bottom-right cell goes
     * {@code 5 4 5 2 …}, so taking each row at its own word would put a one-pixel shelf into the arc, and
     * {@link #cleared} — which samples one cell row per GUI row — could then widen going inward as the
     * slice shrinks. Lifting every row to the maximum of itself and everything below it removes both
     * problems at the cost of a pixel of floor under the anti-aliased edge, which is invisible.
     *
     * @param runs one corner's raw transparent runs, row 0 outermost
     */
    static int[] widened(int[] runs) {
        int[] out = runs.clone();
        for (int v = out.length - 2; v >= 0; v--) {
            out[v] = Math.max(out[v], out[v + 1]);
        }
        return out;
    }
}
