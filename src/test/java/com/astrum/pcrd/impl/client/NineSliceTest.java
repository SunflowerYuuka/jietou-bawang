package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.client.BookLayout.Box;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nine-slice seam maths, asserted instead of looked at.
 *
 * <h2>Why this is the important test of the task</h2>
 * A nine-slice panel is nine blits whose edges must meet exactly. Every classic failure is an off-by-one
 * that a screenshot would show as a one-pixel line and a reader of the code would not see at all:
 *
 * <ul>
 *   <li>the centre computed as {@code width - slice} instead of {@code width - 2*slice} — one column of
 *       world through the panel;</li>
 *   <li>the right edge placed at {@code x + width} rather than {@code x + width - slice} — half the frame
 *       drawn outside the panel;</li>
 *   <li>a "helpful" {@code max(1, ...)} on the centre — a stretched panel that no longer covers its own
 *       rectangle.</li>
 * </ul>
 *
 * <p>So the assertions here are not "each box has the size I typed"; they are the properties that make
 * seams impossible: <b>the nine boxes tile the target</b> (row sums, column sums, no overlap, all inside),
 * plus the three sizes the brief names — exactly {@code 2*slice}, a normal size, and smaller than
 * {@code 2*slice}.
 *
 * <p>All arithmetic, no client: {@code NineSlice} has no Minecraft imports, which is what lets this run
 * in a JVM with no GUI.
 */
class NineSliceTest {

    /** A slice that is not a divisor of anything below, so a rounding bug cannot hide. */
    private static final int SLICE = 18;

    private static NineSlice.Rects rects(int width, int height) {
        return NineSlice.rects(new Box(10, 20, width, height), SLICE);
    }

    // -----------------------------------------------------------------------------------------
    // The degenerate case: a target exactly 2 x slice across
    // -----------------------------------------------------------------------------------------

    /**
     * Exactly {@code 2*slice} wide and tall: the four corners and the four edges fill the target and the
     * centre has <b>zero</b> width and height.
     *
     * <p>Zero, not one and not negative. This is the correct answer — there is no middle to stretch — and
     * the screen skips boxes with no area, so the panel is the frame and nothing else. Asserting the
     * number rather than "does not throw" is the point: a {@code max(1, ...)} would draw the centre one
     * pixel over the two opposite corners.
     */
    @Test
    void atExactlyTwoSlicesTheCentreIsZeroSizedAndNothingOverlaps() {
        NineSlice.Rects r = rects(2 * SLICE, 2 * SLICE);
        assertEquals(SLICE, r.topLeft().width(), "the corner keeps its slice");
        assertEquals(SLICE, r.topLeft().height());
        assertEquals(0, r.centre().width(), "no middle to stretch at exactly 2*slice");
        assertEquals(0, r.centre().height(), "no middle to stretch at exactly 2*slice");
        assertFalse(r.centreIsDrawable());
        assertTrue(r.frameIsDrawable(), "a 2*slice panel is still a drawable frame");

        assertEquals(9, r.all().size());
        assertTilesTarget(r.all(), new Box(10, 20, 2 * SLICE, 2 * SLICE));
    }

    /** The same degenerate case in one axis only: a thin panel keeps a real centre in the other. */
    @Test
    void oneThinAxisOnlyCollapsesThatAxisOfTheCentre() {
        NineSlice.Rects r = rects(2 * SLICE, 100);
        assertEquals(0, r.centre().width(), "the narrow axis has no middle");
        assertEquals(100 - 2 * SLICE, r.centre().height(), "the tall axis still stretches");
        assertFalse(r.centreIsDrawable(), "a zero-width centre is not drawable even though it is tall");
        assertTilesTarget(r.all(), new Box(10, 20, 2 * SLICE, 100));
    }

    // -----------------------------------------------------------------------------------------
    // The normal case
    // -----------------------------------------------------------------------------------------

    /**
     * A normal panel: the slice at all four corners, the remainder split between the three columns and
     * rows, and every boundary an exact integer.
     *
     * <p>That exactness is the assertion. With half-open boxes, "no seam" is
     * {@code topLeft.right() == top.x() == topRight.x() - 0} — an equality, not a tolerance.
     */
    @Test
    void aNormalSizePutsTheSliceAtTheCornersAndStretchesTheRest() {
        int width = 253;
        int height = 177;
        NineSlice.Rects r = rects(width, height);

        assertEquals(new Box(10, 20, SLICE, SLICE), r.topLeft());
        assertEquals(new Box(10 + SLICE, 20, width - 2 * SLICE, SLICE), r.top());
        assertEquals(new Box(10 + width - SLICE, 20, SLICE, SLICE), r.topRight());
        assertEquals(new Box(10, 20 + SLICE, SLICE, height - 2 * SLICE), r.left());
        assertEquals(new Box(10 + SLICE, 20 + SLICE, width - 2 * SLICE, height - 2 * SLICE),
                r.centre());
        assertEquals(new Box(10 + width - SLICE, 20 + SLICE, SLICE, height - 2 * SLICE), r.right());
        assertEquals(new Box(10, 20 + height - SLICE, SLICE, SLICE), r.bottomLeft());
        assertEquals(new Box(10 + SLICE, 20 + height - SLICE, width - 2 * SLICE, SLICE), r.bottom());
        assertEquals(new Box(10 + width - SLICE, 20 + height - SLICE, SLICE, SLICE),
                r.bottomRight());

        assertTrue(r.centreIsDrawable());
        assertTilesTarget(r.all(), new Box(10, 20, width, height));
    }

    /**
     * The four corners are the same size and the four edges are aligned with them — the property that
     * makes an asymmetric panel impossible.
     */
    @Test
    void theFourCornersAgreeAndTheEdgesLineUpWithThem() {
        NineSlice.Rects r = rects(300, 200);
        List<Box> corners = List.of(r.topLeft(), r.topRight(), r.bottomLeft(), r.bottomRight());
        for (Box corner : corners) {
            assertEquals(r.topLeft().width(), corner.width(), "all corners are the same width");
            assertEquals(r.topLeft().height(), corner.height(), "all corners are the same height");
        }
        assertEquals(r.topLeft().y(), r.top().y(), "the top edge starts on the corners' top row");
        assertEquals(r.topLeft().bottom(), r.top().bottom(), "and ends on their bottom row");
        assertEquals(r.topLeft().x(), r.left().x(), "the left edge starts on the corners' left column");
        assertEquals(r.topLeft().right(), r.left().right());
        // The vertical edges span only the body, so they end where the bottom corners begin — not at
        // the panel's bottom edge, which is what the bottom corners cover.
        assertEquals(r.left().bottom(), r.bottomLeft().y(), "the left edge ends where BL begins");
        assertEquals(r.right().bottom(), r.bottomRight().y(), "the right edge ends where BR begins");
        assertEquals(r.top().right(), r.topRight().x(), "the top edge ends where TR begins");
        assertEquals(r.bottom().right(), r.bottomRight().x(), "the bottom edge ends where BR begins");
    }

    // -----------------------------------------------------------------------------------------
    // Smaller than 2 x slice: clamp, do not go negative
    // -----------------------------------------------------------------------------------------

    /**
     * A target narrower than two slices must not produce a negative centre.
     *
     * <p>The clamp is {@code min(slice, width / 2, height / 2)} — the slice shrinks, so the four corners
     * stay square and the panel stays a frame. Only the <em>slice</em> is clamped; the centre is then
     * simply whatever is left, which on an odd width is one pixel.
     */
    @Test
    void aTargetSmallerThanTwoSlicesShrinksTheSliceInsteadOfGoingNegative() {
        NineSlice.Rects r = rects(9, 7);
        assertEquals(3, NineSlice.clampSlice(new Box(10, 20, 9, 7), SLICE),
                "clamped to the smaller half: min(18, 9/2=4, 7/2=3) = 3");
        assertEquals(3, r.topLeft().width(), "and the frame is drawn at that clamped size");
        assertEquals(3, r.centre().width(), "9 - 2*3 = 3: still a real middle, just a small one");
        assertEquals(1, r.centre().height(), "7 - 2*3 = 1: the odd pixel lands in the centre");
        assertNoNegativeExtent(r);
        assertTilesTarget(r.all(), new Box(10, 20, 9, 7));
    }

    /** The clamp in numbers, because "slightly smaller" is where the arithmetic is easiest to get wrong. */
    @Test
    void theClampIsHalfTheSmallerSide() {
        assertEquals(SLICE, NineSlice.clampSlice(new Box(0, 0, 2 * SLICE, 1000), SLICE),
                "exactly enough room keeps the wanted slice");
        assertEquals(SLICE, NineSlice.clampSlice(new Box(0, 0, 2 * SLICE + 1, 1000), SLICE));
        assertEquals(SLICE - 1, NineSlice.clampSlice(new Box(0, 0, 2 * SLICE - 1, 1000), SLICE),
                "one pixel short of two slices loses a pixel of slice, not two");
        assertEquals(0, NineSlice.clampSlice(new Box(0, 0, 1, 1), SLICE),
                "a one-pixel box has no room for any slice");
        assertEquals(0, NineSlice.clampSlice(new Box(0, 0, 1, 1000), SLICE),
                "the narrow axis is what clamps");
        assertEquals(0, NineSlice.clampSlice(new Box(0, 0, 1000, 1), SLICE));
    }

    /** A one-pixel target: everything collapses into the centre, and nothing has a negative size. */
    @Test
    void aOnePixelTargetIsAllCentreAndNothingElse() {
        NineSlice.Rects r = rects(1, 1);
        assertEquals(0, NineSlice.clampSlice(new Box(0, 0, 1, 1), SLICE));
        assertNoNegativeExtent(r);
        assertEquals(new Box(10, 20, 1, 1), r.centre(), "the centre is the whole box");
        assertEquals(0, r.topLeft().width(), "and every other piece has vanished");
        assertTilesTarget(r.all(), new Box(10, 20, 1, 1));
    }

    /** A zero or negative slice is legal and means "no frame": the centre covers the target. */
    @Test
    void aNonPositiveSliceMeansNoFrame() {
        for (int slice : new int[] {0, -5}) {
            NineSlice.Rects r = NineSlice.rects(new Box(3, 4, 50, 40), slice);
            assertEquals(0, r.topLeft().width(), "slice " + slice + " must not draw a frame");
            assertEquals(new Box(3, 4, 50, 40), r.centre());
            assertTilesTarget(r.all(), new Box(3, 4, 50, 40));
        }
    }

    /** A zero-sized target is not an exception: every piece is empty and the tiling still holds trivially. */
    @Test
    void aZeroSizedTargetProducesOnlyEmptyBoxes() {
        NineSlice.Rects r = rects(0, 0);
        assertNoNegativeExtent(r);
        for (Box box : r.all()) {
            assertEquals(0, box.width());
            assertEquals(0, box.height());
        }
    }

    // -----------------------------------------------------------------------------------------
    // The order the caller indexes by
    // -----------------------------------------------------------------------------------------

    /**
     * {@link NineSlice.Rects#piece(int)} agrees with {@link NineSlice.Rects#all()}, and the order is
     * {@link NineSlice#PIECE_NAMES}.
     *
     * <p>The screen blits nine <em>different</em> cells of one frame sheet into nine boxes, so this order is
     * the contract between the layout and the art: a swapped pair draws the left edge across the top, which
     * reads as "the art is broken" rather than "the index is wrong". The names used to live on the deleted
     * {@code BookTheme} enum; they live on {@link NineSlice} now, next to the rectangles they index.
     */
    @Test
    void thePieceOrderIsTheDocumentedOne() {
        assertEquals(NineSlice.PIECES, NineSlice.PIECE_NAMES.size());
        assertEquals(List.of("tl", "t", "tr", "l", "c", "r", "bl", "b", "br"), NineSlice.PIECE_NAMES,
                "the nine suffixes are the nine-slice order, top-left first and row-major");

        NineSlice.Rects r = rects(300, 200);
        for (int i = 0; i < NineSlice.PIECES; i++) {
            assertEquals(r.all().get(i), r.piece(i), "all()[" + i + "] must be piece(" + i + ")");
        }
        assertThrows(IndexOutOfBoundsException.class, () -> r.piece(9));
        assertThrows(IndexOutOfBoundsException.class, () -> r.piece(-1));
    }

    // -----------------------------------------------------------------------------------------
    // The properties, as one helper so every size above is checked the same way
    // -----------------------------------------------------------------------------------------

    /**
     * Asserts that {@code pieces} tile {@code target}: no hole, no overlap, nothing outside.
     *
     * <p>Three separate assertions because they fail for different bugs: the area sum catches a hole or
     * an overlap of equal size; the pairwise test catches an overlap the sum happens to balance; the
     * inside test catches a piece that escaped the panel entirely.
     */
    private static void assertTilesTarget(List<Box> pieces, Box target) {
        assertEquals(NineSlice.PIECES, pieces.size());
        long area = 0;
        for (Box piece : pieces) {
            assertNotNull(piece);
            assertTrue(piece.width() >= 0 && piece.height() >= 0, "negative extent: " + piece);
            assertTrue(piece.x() >= target.x() && piece.right() <= target.right(),
                    piece + " sticks out horizontally of " + target);
            assertTrue(piece.y() >= target.y() && piece.bottom() <= target.bottom(),
                    piece + " sticks out vertically of " + target);
            area += (long) piece.width() * piece.height();
        }
        assertEquals((long) target.width() * target.height(), area,
                "the nine pieces must cover the target exactly once: " + pieces);
        for (int a = 0; a < pieces.size(); a++) {
            for (int b = a + 1; b < pieces.size(); b++) {
                assertFalse(overlaps(pieces.get(a), pieces.get(b)),
                        "pieces " + a + " and " + b + " overlap: " + pieces.get(a) + " / "
                                + pieces.get(b));
            }
        }
    }

    private static void assertNoNegativeExtent(NineSlice.Rects r) {
        for (Box box : r.all()) {
            assertTrue(box.width() >= 0, "negative width: " + box);
            assertTrue(box.height() >= 0, "negative height: " + box);
        }
    }

    /** Half-open overlap, the same predicate {@code BookLayoutTest} uses for its cells. */
    private static boolean overlaps(Box a, Box b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }
}
