package com.astrum.pcrd.impl.client;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four corner cut-outs of the 角色册 frame: <b>the tables are the measurement of the shipped art</b>,
 * and the floor they drive stays inside the silhouette.
 *
 * <h2>What went wrong, and why this is the test that would have caught it</h2>
 * The human's screenshot showed solid black blocks in all four corners where the rounded ornament should
 * be. Two candidate causes were on the table — the corner cells containing dark pixels, or the blit
 * mis-handling alpha — and the answer was neither: the cells are <b>anti-aliased arcs on transparency</b>
 * (the outermost row of the top-left cell is alpha 0 for its first seven pixels), and the panel painted an
 * <b>opaque floor over the whole panel rectangle</b>. The floor filled the wedge the art leaves empty.
 *
 * <p>So the assertions here are about the two halves of that: that the art really is a curve on
 * transparency (which is why regenerating the sheet at 32px would have fixed nothing), and that the floor
 * never covers a pixel outside the arc.
 *
 * <h2>Why the tables are re-measured rather than trusted</h2>
 * {@link CornerCutout} is four constant arrays. A constant that a test merely agrees with is a constant
 * nobody checked, so every one of them is recomputed here from the committed PNG by an independent walk
 * (this class reads the alpha channel itself). If the frame art is ever re-exported with a different arc,
 * this fails and says which corner and which row, instead of the panel quietly growing its blocks back.
 */
class CornerCutoutTest {

    /** The cell edge in the sheet; {@link NineSlice#PIECE_PIXELS}. */
    private static final int CELL = NineSlice.PIECE_PIXELS;

    /** The comfortable tier's slice: the size the panel is actually drawn at. */
    private static final int SLICE = NineSlice.SHIPPED_SLICE;

    static {
        ImageIO.setUseCache(false);
    }

    // -----------------------------------------------------------------------------------------
    // The art: the corners are curves on transparency, not blocks
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The corner cells are transparent outside the arc</b> — the measurement that named the real cause.
     *
     * <p>Dumps the transparent-run table for all four corners and asserts the shape of it: the outermost
     * row loses at least five pixels to transparency, and the widening envelope shrinks toward the panel
     * centre. If a corner cell were an opaque block, every run would be 0 and this fails with the numbers
     * attached — which is the reading that would justify regenerating the sheet instead of fixing the fill.
     */
    @Test
    void theCornerCellsAreArcsOnTransparencyNotBlocks() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        StringBuilder dump = new StringBuilder("\ncorner alpha runs (16px cells, raw walk):\n");
        for (int piece : CornerCutout.corners()) {
            int[] runs = rawRunsOf(sheet, piece);
            int[] widened = CornerCutout.widened(CornerCutout.measured(alphasOf(sheet, piece), CELL));
            dump.append(String.format("  %-2s raw     %s%n      widened %s%n",
                    NineSlice.PIECE_NAMES.get(piece), Arrays.toString(runs), Arrays.toString(widened)));

            assertTrue(runs[0] >= 5, NineSlice.PIECE_NAMES.get(piece) + ": the outermost row must be "
                    + "mostly transparent for the corner to be a curve, measured " + runs[0]
                    + " — a fully inked first row means the cell is a block, not an arc");
            for (int v = 1; v < CELL; v++) {
                assertTrue(widened[v] <= widened[v - 1], NineSlice.PIECE_NAMES.get(piece) + " widened row "
                        + v + " widens going inward (" + widened[v - 1] + " -> " + widened[v]
                        + "): the corner is not a convex arc");
            }
        }
        System.out.println(dump);
    }

    /**
     * The innermost row of every corner is fully inked.
     *
     * <p>Separate from the arc check because it is a different fact: the widening envelope may keep a
     * one-pixel margin at the band's inner end (the art's own anti-aliased dip), while this pins that the
     * inner end is solid — otherwise the frame would show a gap where the corner meets the straight edges.
     */
    @Test
    void theInnermostCornerRowsAreFullyInked() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        for (int piece : CornerCutout.corners()) {
            int[] runs = rawRunsOf(sheet, piece);
            assertEquals(0, runs[CELL - 1], NineSlice.PIECE_NAMES.get(piece) + ": the innermost row must "
                    + "be fully inked, measured " + runs[CELL - 1] + "px of transparency");
        }
    }

    /**
     * <b>The committed tables equal a fresh measurement of the committed art.</b>
     *
     * <p>The assertion that keeps {@link CornerCutout}'s constants honest, so a re-exported frame fails
     * instead of sliding the cut-outs off the new arc. The comparison is against
     * {@code measured → widened}, which is the pipeline the tables were produced by.
     */
    @Test
    void theTablesAreTheMeasurementOfTheShippedSheet() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        for (int piece : CornerCutout.corners()) {
            int[] measured = CornerCutout.widened(
                    CornerCutout.measured(alphasOf(sheet, piece), CELL));
            assertEquals(Arrays.toString(measured), Arrays.toString(CornerCutout.table(piece)),
                    NineSlice.PIECE_NAMES.get(piece) + ": the table in CornerCutout must be the measurement "
                            + "of the shipped sheet");
        }
    }

    /**
     * The before/after numbers the report quotes, printed so they are reproducible from the build log.
     */
    @Test
    void dumpCornerTablesAndClearedSpans() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        System.out.println("== before: transparent run per row of each 16px corner cell (row 0 outermost)");
        for (int piece : CornerCutout.corners()) {
            System.out.printf("  %-2s alpha    %s%n", NineSlice.PIECE_NAMES.get(piece),
                    Arrays.toString(rawRunsOf(sheet, piece)));
        }
        System.out.println("== after: floor inset per GUI row, slice " + SLICE);
        for (int piece : CornerCutout.corners()) {
            StringBuilder line = new StringBuilder("  " + NineSlice.PIECE_NAMES.get(piece) + " ");
            for (int row : new int[] {0, 1, 2, 4, 8, SLICE - 1}) {
                line.append("row").append(row).append('=')
                        .append(CornerCutout.cleared(piece, row, SLICE)).append("  ");
            }
            System.out.println(line);
        }
        // The one number that answers the screenshot directly: at the drawn size the outermost row of the
        // top-left corner leaves ceil(7 * 30 / 16) = 14 of its 30 pixels unpainted, where it used to leave
        // none and painted a solid block across all 30.
        assertEquals(14, CornerCutout.cleared(CornerCutout.TOP_LEFT_INDEX, 0, SLICE),
                "the outermost row of the top-left corner clears ceil(7 * 30 / 16) = 14px of a 30px band");
        assertEquals(0, CornerCutout.cleared(CornerCutout.TOP_LEFT_INDEX, SLICE - 1, SLICE),
                "the innermost row of the band is fully inked, so it clears nothing");
    }

    // -----------------------------------------------------------------------------------------
    // The fill: inside the silhouette, and nothing else
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The cleared span starts at or beyond the art's own ink</b> — the defect, as an assertion.
     *
     * <p>For every corner and every GUI row of the band, the art's ink start is mapped into GUI pixels and
     * compared with the cleared amount. The floor must never begin before the ink: that gap is precisely
     * the black wedge in the screenshot, so this test cannot pass while the defect exists.
     *
     * <p>One-directional on purpose: the floor may leave an anti-aliased pixel unpainted (that shows the
     * dimmed world, which is what a soft edge already does), but it may not paint where the art has
     * nothing. The round-up in {@link CornerCutout#cleared} is what makes that hold.
     */
    @Test
    void theFloorNeverPaintsOutsideTheArc() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        for (int piece : CornerCutout.corners()) {
            int[] runs = rawRunsOf(sheet, piece);
            for (int row = 0; row < SLICE; row++) {
                int cleared = CornerCutout.cleared(piece, row, SLICE);
                int source = Math.min(CELL - 1, row * CELL / SLICE);
                int inkGui = runs[source] * SLICE / CELL;
                assertTrue(cleared >= inkGui, NineSlice.PIECE_NAMES.get(piece) + " row " + row
                        + ": the floor starts at " + cleared + "px but the art's ink starts at " + inkGui
                        + "px — that gap is a black block");
            }
        }
    }

    /**
     * The cleared amount is monotone non-increasing across the band, so a corner reads as one arc.
     *
     * <p>No rounding artefact may make row {@code n+1} clear more than row {@code n}: that would put a
     * one-pixel shelf in the curve.
     */
    @Test
    void theClearedSpanNeverWidensGoingInward() {
        for (int piece : CornerCutout.corners()) {
            for (int slice : new int[] {NineSlice.PIECE_PIXELS, 18, 30, 47}) {
                for (int row = 1; row < slice; row++) {
                    assertTrue(CornerCutout.cleared(piece, row, slice)
                                    <= CornerCutout.cleared(piece, row - 1, slice),
                            NineSlice.PIECE_NAMES.get(piece) + " row " + row + " at slice " + slice
                                    + " clears more than the row above it");
                }
            }
        }
    }

    /** A row outside the band, or a non-corner piece, is refused rather than answered with a guess. */
    @Test
    void aRowOutsideTheBandIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CornerCutout.cleared(CornerCutout.TOP_LEFT_INDEX, SLICE, SLICE));
        assertThrows(IllegalArgumentException.class,
                () -> CornerCutout.cleared(CornerCutout.TOP_LEFT_INDEX, -1, SLICE));
        assertThrows(IllegalArgumentException.class,
                () -> CornerCutout.cleared(1, 0, SLICE), "piece 1 is the top edge, not a corner");
        assertEquals(0, CornerCutout.cleared(CornerCutout.TOP_LEFT_INDEX, 0, 0),
                "a zero slice has no corner band to clear");
    }

    /**
     * The four corners are <b>four different arcs</b>.
     *
     * <p>The art is hand-drawn and the measured runs differ (7, 6, 6, 5 on the outermost row). One shared
     * table would either clip the widest corner's stroke or leave the narrowest one's wedge unpainted, so
     * the difference is asserted rather than the equality.
     */
    @Test
    void theFourCornersAreUnlikeEachOther() {
        assertNotEquals(14, CornerCutout.cleared(CornerCutout.TOP_RIGHT_INDEX, 0, SLICE),
                "the top-right arc is measured 6px, not the top-left's 7px");
        for (int a : CornerCutout.corners()) {
            for (int b : CornerCutout.corners()) {
                if (a < b) {
                    assertNotEquals(Arrays.toString(CornerCutout.table(a)),
                            Arrays.toString(CornerCutout.table(b)),
                            NineSlice.PIECE_NAMES.get(a) + " and " + NineSlice.PIECE_NAMES.get(b)
                                    + " share one table, but the art's four arcs differ");
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * {@return the transparent run at the start of each row of a corner cell, row 0 = panel outer edge}
     *
     * <p>Independent of {@link CornerCutout#measured}: walks the alpha channel directly and mirrors per
     * corner, so the two implementations agreeing is evidence rather than a tautology.
     */
    private static int[] rawRunsOf(BufferedImage sheet, int piece) {
        int ox = (piece % 3) * CELL;
        int oy = (piece / 3) * CELL;
        boolean flipX = piece == CornerCutout.TOP_RIGHT_INDEX || piece == CornerCutout.BOTTOM_RIGHT_INDEX;
        boolean flipY = piece == CornerCutout.BOTTOM_LEFT_INDEX || piece == CornerCutout.BOTTOM_RIGHT_INDEX;
        int[] runs = new int[CELL];
        for (int v = 0; v < CELL; v++) {
            int u = 0;
            while (u < CELL) {
                int x = ox + (flipX ? CELL - 1 - u : u);
                int y = oy + (flipY ? CELL - 1 - v : v);
                if (((sheet.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    break;
                }
                u++;
            }
            runs[v] = u;
        }
        return runs;
    }

    /** {@return the corner cell's alpha, row-major, flipped so row 0 is the panel's outer edge} */
    private static int[] alphasOf(BufferedImage sheet, int piece) {
        int ox = (piece % 3) * CELL;
        int oy = (piece / 3) * CELL;
        boolean flipX = piece == CornerCutout.TOP_RIGHT_INDEX || piece == CornerCutout.BOTTOM_RIGHT_INDEX;
        boolean flipY = piece == CornerCutout.BOTTOM_LEFT_INDEX || piece == CornerCutout.BOTTOM_RIGHT_INDEX;
        int[] alpha = new int[CELL * CELL];
        for (int v = 0; v < CELL; v++) {
            for (int u = 0; u < CELL; u++) {
                int x = ox + (flipX ? CELL - 1 - u : u);
                int y = oy + (flipY ? CELL - 1 - v : v);
                alpha[v * CELL + u] = (sheet.getRGB(x, y) >>> 24) & 0xFF;
            }
        }
        return alpha;
    }

    /**
     * Loads one of the generated art textures from the classpath, falling back to the source tree.
     *
     * <p>The art is generated, so a machine that has never run {@code tools/gen_*.py} has no file to
     * measure; that is a missing precondition, not a defect, and the test skips rather than fails. The art
     * itself is still packaged for tests — {@code build.gradle} excludes it from the <b>jar</b>, not from
     * {@code processResources} — so the classpath normally answers and the fallback is belt and braces.
     */
    private static BufferedImage load(String pathUnderTextures) throws Exception {
        String resource = "/assets/astrum/textures/" + pathUnderTextures;
        InputStream found = CornerCutoutTest.class.getResourceAsStream(resource);
        if (found == null) {
            java.nio.file.Path onDisk = java.nio.file.Path.of("src/main/resources/assets/astrum/textures",
                    pathUnderTextures);
            if (java.nio.file.Files.isRegularFile(onDisk)) {
                found = java.nio.file.Files.newInputStream(onDisk);
            }
        }
        if (found == null) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "art not generated on this machine: " + resource);
        }
        try (InputStream in = found) {
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a readable image: " + resource);
            return image;
        }
    }
}
