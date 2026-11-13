package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.ub.UbParticles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Holds the <b>generated particle atlases</b> to the property the task exists for: no cell may show a
 * square edge, and every cell must have a soft, non-empty glow.
 *
 * <h2>Why an image test at all</h2>
 * The defect being replaced was a hard rectangle pasted over the screen. A sprite with one opaque
 * pixel on its border renders as a literal square — the same defect at the scale of a particle — and
 * neither the generator's log nor a code read can catch a regression in the committed PNG. Reading the
 * shipped file and asserting that the whole outer ring is <b>exactly</b> transparent is the one check
 * that does. {@code tools/gen_ub_particles.py} prints the same number on every run, so a regeneration
 * that breaks it fails loudly in both places.
 *
 * <p>The softness is asserted too, not just the zero: the next rings in already have a small bound. A
 * future regeneration that simply thresholded the image (alpha 0 on the border, alpha 255 one pixel
 * in) would pass a border-only test and still read as a square. The generator's smoothstep ramp is
 * what makes those rings almost transparent.
 *
 * <p>The JSONs are checked from the same test because they are the other half of the same promise: a
 * particle type whose {@code textures} entry does not resolve draws the purple-black missing texture,
 * which looks like a broken effect rather than a missing file. And the cell counts are asserted
 * against the Java constants, so an atlas and its {@code getU0/getU1} arithmetic cannot drift apart.
 */
class UbParticleAtlasTest {

    static {
        // ImageIO otherwise caches the decoded stream in a temp file, which makes a plain image read
        // depend on the runner's temp directory being writable. Decoding from memory removes that.
        ImageIO.setUseCache(false);
    }

    /** One cell; the generator's {@code TILE}. Cells are square, the strip is one cell tall. */
    private static final int TILE = 64;

    @Test
    void eachAtlasIsExactlyTheCellsItsParticleClassIndexes() throws IOException {
        assertAtlasMatches("ub_spark", UbSparkParticle.TILES, 10);
        assertAtlasMatches("ub_mote", UbMoteParticle.TILES, 6);
    }

    @Test
    void everyCellBorderIsExactlyTransparent() throws IOException {
        for (String name : new String[] {"ub_spark", "ub_mote"}) {
            BufferedImage image = atlas(name);
            int cells = cellCount(name);
            for (int cell = 0; cell < cells; cell++) {
                for (int y = 0; y < TILE; y++) {
                    for (int x = 0; x < TILE; x++) {
                        if (x == 0 || x == TILE - 1 || y == 0 || y == TILE - 1) {
                            int alpha = alphaAt(image, cell, x, y);
                            assertEquals(0, alpha, name + " cell " + cell + " has alpha " + alpha
                                    + " on its border at (" + x + "," + y + ") — that is a square edge");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theFadeIsSoftNotJustAZeroBorder() throws IOException {
        for (String name : new String[] {"ub_spark", "ub_mote"}) {
            BufferedImage image = atlas(name);
            int cells = cellCount(name);
            for (int cell = 0; cell < cells; cell++) {
                // The next rings in must still be effectively transparent: a hard one-pixel cut-out
                // would put full-strength glow one pixel from the edge.
                for (int ring = 1; ring <= 2; ring++) {
                    int worst = maxOnRing(image, cell, ring);
                    assertTrue(worst <= 24, name + " cell " + cell + " ring " + ring
                            + " reaches alpha " + worst + " — the border fade is too abrupt");
                }
                // ... and the cell must still contain real light, or "transparent" would be vacuous.
                int peak = peakAlpha(image, cell);
                assertTrue(peak >= 96, name + " cell " + cell + " has no visible glow (peak alpha "
                                + peak + ")");
            }
        }
    }

    @Test
    void everyParticleJsonNamesATextureThatExists() throws IOException {
        for (String name : new String[] {"ub_spark", "ub_mote"}) {
            JsonObject json = json("/assets/astrum/particles/" + name + ".json");
            JsonArray textures = json.getAsJsonArray("textures");
            assertNotNull(textures, name + ".json must have a textures array");
            assertTrue(textures.size() >= 1, name + ".json names no texture");
            for (var element : textures) {
                String id = element.getAsString();
                assertTrue(id.startsWith("astrum:"), "particle textures stay in the mod namespace: " + id);
                String path = "/assets/astrum/textures/particle/"
                        + id.substring("astrum:".length()) + ".png";
                try (InputStream in = UbParticleAtlasTest.class.getResourceAsStream(path)) {
                    assertNotNull(in, name + ".json names " + id + " but " + path + " is missing");
                }
            }
        }
        // The file names themselves follow the registered type ids, so a rename cannot leave the JSON
        // behind while the registry and the log lines disagree with it.
        assertNotNull(UbParticleAtlasTest.class.getResourceAsStream(
                "/assets/astrum/particles/" + UbParticles.SPARK_ID.getPath() + ".json"));
        assertNotNull(UbParticleAtlasTest.class.getResourceAsStream(
                "/assets/astrum/particles/" + UbParticles.MOTE_ID.getPath() + ".json"));
    }

    // ------------------------------------------------------------------ helpers

    private static void assertAtlasMatches(String name, int cellsInCode, int expectedCells)
            throws IOException {
        assertEquals(expectedCells, cellsInCode,
                name + ": the generator writes " + expectedCells + " cells");
        BufferedImage image = atlas(name);
        assertEquals(TILE * cellsInCode, image.getWidth(), name + " must be " + cellsInCode
                + " cells of " + TILE + "px");
        assertEquals(TILE, image.getHeight(), name + " must be one cell tall");
    }

    /** {@return the cell count an atlas was generated with, read from its own pixel width} */
    private static int cellCount(String name) throws IOException {
        return atlas(name).getWidth() / TILE;
    }

    private static BufferedImage atlas(String name) throws IOException {
        String path = "/assets/astrum/textures/particle/" + name + ".png";
        try (InputStream in = UbParticleAtlasTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing generated atlas: " + path);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a readable PNG: " + path);
            return image;
        }
    }

    private static JsonObject json(String path) throws IOException {
        try (InputStream in = UbParticleAtlasTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing particle JSON: " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    /** {@return the alpha of cell-local (x, y), 0-255} */
    private static int alphaAt(BufferedImage image, int cell, int x, int y) {
        return (image.getRGB(cell * TILE + x, y) >>> 24) & 0xFF;
    }

    /** {@return the highest alpha on the square ring {@code ring} pixels in from the cell edge} */
    private static int maxOnRing(BufferedImage image, int cell, int ring) {
        int worst = 0;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                if (x == ring || x == TILE - 1 - ring || y == ring || y == TILE - 1 - ring) {
                    worst = Math.max(worst, alphaAt(image, cell, x, y));
                }
            }
        }
        return worst;
    }

    private static int peakAlpha(BufferedImage image, int cell) {
        int peak = 0;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                peak = Math.max(peak, alphaAt(image, cell, x, y));
            }
        }
        return peak;
    }
}