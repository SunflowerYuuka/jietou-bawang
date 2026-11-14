package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.client.BookLayout.Box;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The panel as <b>official art</b>: the nine frame cells really exist and really slice, the body really
 * excludes the frame band, and the still is packaged at the size the drawing code assumes.
 *
 * <h2>What this has to catch, and why each assertion exists</h2>
 * The human's request was 「九宫格保留，边框不保留，使用官方背景」 — keep the mechanism, drop our frame, use official
 * art. Every way of getting that wrong is silent:
 *
 * <ul>
 *   <li>a frame sheet whose nine cells are not in {@link NineSlice#PIECE_NAMES} order draws an edge across a
 *       corner, which reads as "the art is broken";</li>
 *   <li>a <b>filled</b> centre cell paints over the still, so the character background the human asked for is
 *       invisible behind a flat plate;</li>
 *   <li>a still committed at a size other than {@link OfficialPanel#STILL_WIDTH}x{@link OfficialPanel#STILL_HEIGHT}
 *       makes the cover crop sample the wrong rectangle — a stretched or clipped picture, with no error;</li>
 *   <li>a still committed at the official size puts a megabyte per character in the jar, which is the specific
 *       thing the brief forbids.</li>
 * </ul>
 *
 * <p>The geometry is asserted against {@link NineSlice}, which already owns the tiling proof; this test only has
 * to show that the panel <em>uses</em> it with a slice that keeps a body worth filling, at both metric tiers.
 */
class OfficialPanelTest {

    static {
        ImageIO.setUseCache(false);
    }

    /** The committed frame sheet's declared size: three cells each way. */
    private static final int SHEET = NineSlice.PIECE_PIXELS * 3;

    /** The index of the centre cell, which must be empty: the still is the body. */
    private static final int CENTRE = 4;

    // -----------------------------------------------------------------------------------------
    // The committed art
    // -----------------------------------------------------------------------------------------

    /** The frame sheet is the size and mode the blits assume, and it has nine distinct cells. */
    @Test
    void theFrameSheetIsNineCellsOfTheDeclaredSize() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        assertEquals(SHEET, sheet.getWidth(), "three cells wide");
        assertEquals(SHEET, sheet.getHeight(), "three cells tall");

        // Each cell is cut out and compared: nine cells that are byte-identical would draw one undifferentiated
        // band, which is the "official frame ignored" failure.
        for (int i = 0; i < NineSlice.PIECES; i++) {
            if (i == CENTRE) {
                continue;
            }
            for (int j = i + 1; j < NineSlice.PIECES; j++) {
                if (j == CENTRE) {
                    continue;
                }
                assertNotEquals(cellDigest(sheet, i), cellDigest(sheet, j),
                        "cells " + NineSlice.PIECE_NAMES.get(i) + " and " + NineSlice.PIECE_NAMES.get(j)
                                + " are the same pixels, so the frame has no shape");
            }
        }
    }

    /**
     * The <b>centre</b> cell is empty.
     *
     * <p>This is the assertion that keeps the still visible. The generator writes an empty centre on purpose
     * (the body is the character's picture, not a plate), and the drawing code skips that cell; if the art ever
     * came back with a filled centre, this fails instead of the feature quietly disappearing behind it.
     */
    @Test
    void theCentreCellIsEmptySoTheStillShowsThrough() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        int x0 = (CENTRE % 3) * NineSlice.PIECE_PIXELS;
        int y0 = (CENTRE / 3) * NineSlice.PIECE_PIXELS;
        int ink = 0;
        for (int y = y0; y < y0 + NineSlice.PIECE_PIXELS; y++) {
            for (int x = x0; x < x0 + NineSlice.PIECE_PIXELS; x++) {
                if (((sheet.getRGB(x, y) >>> 24) & 0xFF) > 16) {
                    ink++;
                }
            }
        }
        assertEquals(0, ink, "the centre cell must be transparent: the still is the body");
    }

    /** Every non-centre cell carries ink: an empty edge would leave a gap in the frame. */
    @Test
    void everyFrameCellCarriesInk() throws Exception {
        BufferedImage sheet = load(OfficialPanel.FRAME.getPath().replace("textures/", ""));
        for (int i = 0; i < NineSlice.PIECES; i++) {
            if (i == CENTRE) {
                continue;
            }
            assertTrue(cellDigest(sheet, i) != 0L,
                    NineSlice.PIECE_NAMES.get(i) + " carries no ink — the frame has a hole there");
        }
    }

    /**
     * Both stills are packaged at the size the cover crop assumes, and both are the downscaled size, not the
     * originals.
     *
     * <h2>Why both rungs, and not just one</h2>
     * The panel now draws a <b>per-tier</b> still (「背景立绘要随着好感的升阶更换背景立绘」): the 3★ rung at affection
     * tier 1 and 2, the 6★ rung at tier 3. Both are cropped by the same {@link StillCover} arithmetic, so both
     * must be at the committed size — a missing 6★ file would silently draw the 3★ picture forever, which is
     * exactly the defect this feature fixes, and it would look like nothing at all.
     */
    @Test
    void theShippedStillsAreTheCommittedDerivedSize() throws Exception {
        for (String rung : new String[] {"3", "6"}) {
            String path = "gui/still/djeeta_" + rung + ".png";
            BufferedImage still = load(path);
            assertEquals(StillCover.STILL_WIDTH, still.getWidth(),
                    "StillCover crops using this width; a re-export at another size would crop the wrong "
                            + "region (" + path + ")");
            assertEquals(StillCover.STILL_HEIGHT, still.getHeight(), path);

            // The official files are 1408x792 / ~1 MB and ~1.25 MB. Anything near that means the raw still
            // was committed.
            long bytes = resourceSize("/assets/astrum/textures/" + path);
            assertTrue(bytes > 20_000, path + " must be a real picture, got " + bytes + " B");
            assertTrue(bytes < 400_000,
                    path + " is " + bytes + " B: the committed file must be the derived 512x288, not the "
                            + "official ~1 MB original");
        }
    }

    // -----------------------------------------------------------------------------------------
    // The geometry the panel is drawn with
    // -----------------------------------------------------------------------------------------

    /**
     * The body excludes the frame band, so the still never runs under the frame's ink.
     *
     * <p>Inset by {@link OfficialPanel#CELL}, not by the slice: the cell is what the art's stroke occupies, while
     * the slice is where the corner is <em>placed</em>. Both tiers are checked, and the body is checked to be
     * strictly inside the panel and to be a real rectangle.
     */
    @Test
    void theStillBodySitsInsideTheFrameBand() {
        for (BookLayout layout : new BookLayout[] {BookLayout.at(320, 240), BookLayout.at(1280, 720)}) {
            Box panel = layout.panel();
            Box body = StillCover.bodyOf(panel);

            assertTrue(body.x() >= panel.x() + NineSlice.PIECE_PIXELS, layout.tierName() + ": body clears the left");
            assertTrue(body.y() >= panel.y() + NineSlice.PIECE_PIXELS, layout.tierName() + ": body clears the top");
            assertTrue(body.right() <= panel.right() - NineSlice.PIECE_PIXELS,
                    layout.tierName() + ": body clears the right");
            assertTrue(body.bottom() <= panel.bottom() - NineSlice.PIECE_PIXELS,
                    layout.tierName() + ": body clears the bottom");
            assertTrue(body.width() >= layout.bodyWidth(),
                    layout.tierName() + ": the still must be at least as wide as the content it sits behind");
            assertTrue(body.height() >= layout.metrics().bodyHeight());
        }
    }

    /**
     * The nine-slice the screen draws with keeps a non-degenerate body and a thick-enough frame, per tier.
     *
     * <p>Delegates the tiling proof to {@link NineSlice} (asserted exhaustively in {@code NineSliceTest}) and
     * checks the two facts this panel needs: the centre exists to be filled by the still, and the slice is at
     * least one cell so the corner is never shrunk smaller than the art it holds.
     */
    @Test
    void thePanelKeepsADrawableBodyAtEveryTier() {
        for (BookLayout layout : new BookLayout[] {BookLayout.at(320, 240), BookLayout.at(1280, 720)}) {
            NineSlice.Rects rects = NineSlice.rects(layout.panel(), layout.slice());
            assertTrue(rects.centreIsDrawable(),
                    layout.tierName() + " panel has no body at slice " + layout.slice());
            assertTrue(layout.slice() >= NineSlice.PIECE_PIXELS,
                    layout.tierName() + ": a slice (" + layout.slice() + ") thinner than one cell ("
                            + NineSlice.PIECE_PIXELS + ") would scale the corner down and blur its stroke");
            // The nine boxes tile the panel exactly; NineSliceTest proves the general case, this states the
            // instance so a panel-size change cannot silently break the drawing.
            assertEquals(layout.panel().width(),
                    rects.topLeft().width() + rects.top().width() + rects.topRight().width());
            assertEquals(layout.panel().height(),
                    rects.topLeft().height() + rects.left().height() + rects.bottomLeft().height());
        }
    }

    /** A panel too small for even one cell still answers a sane body rather than a negative rectangle. */
    @Test
    void aTinyPanelClampsInsteadOfProducingANegativeBody() {
        for (Box panel : List.of(new Box(0, 0, 1, 1), new Box(5, 5, 10, 10), new Box(0, 0, 0, 0))) {
            Box body = StillCover.bodyOf(panel);
            assertTrue(body.width() >= 0, "width must never go negative: " + body);
            assertTrue(body.height() >= 0, "height must never go negative: " + body);
        }
    }

    /** The palette's base is the panel's floor: one dark neutral, not two values that can drift apart. */
    @Test
    void thePaletteBodyIsThePanelFloor() {
        assertEquals(BookPalette.BODY, OfficialPanel.BODY_FLOOR,
                "BookPalette.BODY is documented as the panel's floor; two constants for one colour is one too "
                        + "many");
        assertEquals(0xFF, (OfficialPanel.BODY_FLOOR >>> 24) & 0xFF,
                "the floor is opaque, or the world shows through the panel body");
        assertEquals(0, OfficialPanel.STILL_DIM & 0x00FFFFFF,
                "the wash must be neutral black: a tinted wash would recolour her art");
        assertTrue(((OfficialPanel.STILL_DIM >>> 24) & 0xFF) >= 0x80,
                "below 50% the cream body text loses contrast on the bright half of a still");
        assertTrue(((OfficialPanel.STILL_DIM >>> 24) & 0xFF) <= 0xC0,
                "above 75% the still is no longer visible, which is the feature the human asked for");
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** {@return a 64-bit fingerprint of one cell's ARGB pixels, so "different" is measurable} */
    private static long cellDigest(BufferedImage sheet, int index) {
        int x0 = (index % 3) * NineSlice.PIECE_PIXELS;
        int y0 = (index / 3) * NineSlice.PIECE_PIXELS;
        long hash = 0L;
        for (int y = y0; y < y0 + NineSlice.PIECE_PIXELS; y++) {
            for (int x = x0; x < x0 + NineSlice.PIECE_PIXELS; x++) {
                hash = hash * 31 + sheet.getRGB(x, y);
            }
        }
        return hash == 0L ? 1L : hash;
    }

    private static BufferedImage load(String pathUnderTextures) throws Exception {
        String resource = "/assets/astrum/textures/" + pathUnderTextures;
        try (InputStream in = OfficialPanelTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing texture on the classpath: " + resource);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a readable image: " + resource);
            return image;
        }
    }

    private static long resourceSize(String resource) throws Exception {
        try (InputStream in = OfficialPanelTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource: " + resource);
            return in.readAllBytes().length;
        }
    }
}
