package com.astrum.pcrd.impl.client;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UB gauge's <b>official-art bar</b>, asserted as arithmetic and as a shipped file.
 *
 * <h2>Why the bar needed a test at all</h2>
 * It used to be one {@code fill} rectangle; it is now two official-art layers with the fill
 * <b>clipped</b> to the charge. Three rectangles have to agree — the track's, the fill's destination,
 * and the fill's <b>source crop</b> — and this project cannot see the screen to check them. So:
 *
 * <ul>
 *   <li>the <b>geometry</b> is {@link GaugeArt}'s and is asserted here at several fractions, including
 *       the two ends and the pathological inputs a server can actually send;</li>
 *   <li>the <b>source crop</b> is asserted to be the same fraction as the destination, because that is
 *       the difference between the art being <em>clipped</em> and the art being <em>squashed</em>;</li>
 *   <li>the <b>committed PNGs</b> are read: both must be 256x24 RGBA, they must differ in colour, and they must
 *       share a silhouette — a track and a fill cut from one file would draw a bar that never fills, and a pair
 *       with different shapes cannot have the fill sit inside the trough.</li>
 * </ul>
 *
 * <h2>What changed, and why the test changed with it</h2>
 * The bar used to be the <em>square</em> result-screen gauge tile, stretched into a bar: 「UB槽的UI不行，用官方的」.
 * It is now the game's own horizontal fever bar ({@code pct_fever_bar.png}), so the asserted size moved with the
 * art and the "same silhouette" assertion is new — it is the property that makes this pair a gauge rather than two
 * unrelated pictures. The fractions and the clip arithmetic are unchanged, because they were never about the art.
 *
 * <p>What this cannot say is whether it <em>reads</em> as a PCR gauge on a real screen. That is the
 * human's check, and it is in the task report as such.
 */
class GaugeArtTest {

    static {
        ImageIO.setUseCache(false);
    }

    private static final int BAR = HudLayout.BAR_WIDTH;

    // ------------------------------------------------------------------ the fill fraction

    /**
     * The bar's fill is the charge, at every fraction a server can produce.
     *
     * <p>Monotonic and exact at the ends: 0% is empty (an honest empty bar), 100% is the whole bar, and
     * the midpoint is half. The middle values are asserted as a range rather than an equality because
     * the GUI can only draw whole pixels — but the range is tight, because a bar that lags the number by
     * more than a pixel is a bar the player cannot read.
     */
    @Test
    void theFillIsTheChargeToWithinAPixel() {
        assertEquals(0, GaugeArt.fillWidth(0.0, BAR));
        assertEquals(BAR, GaugeArt.fillWidth(1.0, BAR));
        assertEquals(BAR / 2, GaugeArt.fillWidth(0.5, BAR));
        for (int percent = 0; percent <= 100; percent++) {
            double fraction = percent / 100.0;
            int filled = GaugeArt.fillWidth(fraction, BAR);
            assertTrue(Math.abs(filled - fraction * BAR) <= 1,
                    "at " + percent + "% the bar draws " + filled + " of " + BAR + " pixels");
        }
    }

    /** Every fraction above zero fills something: a bar that shows 3% as nothing looks broken. */
    @Test
    void anyChargeAtAllIsVisible() {
        assertEquals(1, GaugeArt.fillWidth(0.001, BAR));
        assertEquals(1, GaugeArt.fillWidth(1.0 / BAR / 4.0, BAR));
    }

    /**
     * Pathological input is refused rather than drawn.
     *
     * <p>{@code NaN} is what a lost packet or a divided-by-zero produces, and it must not read as
     * <em>full</em> — {@code Math.min(1.0, NaN)} is {@code NaN}, and a naive cast would be 0, which is
     * at least empty; the explicit check is what makes that a decision instead of an accident.
     */
    @Test
    void garbageChargeDrawsAnEmptyBar() {
        assertEquals(0, GaugeArt.fillWidth(Double.NaN, BAR));
        assertEquals(0, GaugeArt.fillWidth(-0.5, BAR));
        assertEquals(0, GaugeArt.fillWidth(0.0, 0), "a zero-width bar has nothing to fill");
        assertEquals(BAR, GaugeArt.fillWidth(4.0, BAR), "over 100% clamps, it does not overflow");
    }

    // ------------------------------------------------------------------ the clip

    /**
     * The source crop is the same fraction as the destination — that is the clip.
     *
     * <p>If the crop stayed full width while the destination shrank, the gold gradient would be
     * <b>squashed</b> into a shorter bar and the art's own rounded end would land in the middle of the
     * gauge. Sampling only the left part is what makes a partial bar look like a partially filled
     * channel instead of a smaller copy of a full one.
     */
    @Test
    void theSourceCropTracksTheDestinationSoTheArtClipsRatherThanSquashes() {
        for (int percent = 5; percent <= 100; percent += 5) {
            double fraction = percent / 100.0;
            int source = GaugeArt.sourceWidth(fraction);
            double sourceFraction = source / (double) GaugeArt.SOURCE_WIDTH;
            assertTrue(Math.abs(sourceFraction - fraction) <= 1.0 / GaugeArt.SOURCE_WIDTH,
                    "at " + percent + "% the crop is " + source + "/" + GaugeArt.SOURCE_WIDTH
                            + " but the destination is " + fraction);
        }
        assertEquals(0, GaugeArt.sourceWidth(0.0));
        assertEquals(GaugeArt.SOURCE_WIDTH, GaugeArt.sourceWidth(1.0));
    }

    // ------------------------------------------------------------------ the shipped files

    /** Both committed textures are the size the HUD's blit assumes. */
    @Test
    void bothGaugeTexturesArePackagedAtTheDeclaredSize() throws Exception {
        BufferedImage track = load(GaugeArt.TRACK_PATH);
        BufferedImage fill = load(GaugeArt.FILL_PATH);
        assertEquals(GaugeArt.SOURCE_WIDTH, track.getWidth());
        assertEquals(GaugeArt.SOURCE_HEIGHT, track.getHeight());
        assertEquals(GaugeArt.SOURCE_WIDTH, fill.getWidth());
        assertEquals(GaugeArt.SOURCE_HEIGHT, fill.getHeight());
    }

    /**
     * The track and the fill are different pictures, and both carry real pixels.
     *
     * <p>A pair cut from one source file would draw a bar that "fills" invisibly — the classic
     * silent-no-op of a two-layer gauge. The alpha check is the other half: a fully transparent texture
     * draws nothing at all, which is indistinguishable from a bar that was never rendered.
     */
    @Test
    void theTrackAndTheFillAreDifferentArtWithRealPixels() throws Exception {
        BufferedImage track = load(GaugeArt.TRACK_PATH);
        BufferedImage fill = load(GaugeArt.FILL_PATH);

        int trackInk = opaquePixels(track);
        int fillInk = opaquePixels(fill);
        assertTrue(trackInk > track.getWidth() * 2, "the track must be drawn, not transparent");
        assertTrue(fillInk > fill.getWidth() * 2, "the fill must be drawn, not transparent");
        // The two layers are deliberately the SAME shape (that is the point of a trough), so their ink counts
        // must agree — see theTrackAndTheFillShareASilhouette. What must differ is the colour: a fill that was
        // the trough's own neutral would draw a bar that "fills" invisibly.
        assertEquals(trackInk, fillInk,
                "the pair shares a silhouette, so the same pixels are above the alpha threshold in both");
        assertNotEquals(averageArgb(track), averageArgb(fill),
                "the fill must differ from its track in colour, or the charge is invisible");
    }

    /**
     * The trough and the fill share a <b>silhouette</b>: the fill can sit inside the track it was cut from.
     *
     * <p>This is what makes the pair a gauge. The trough is the official bar's own alpha, painted one dark colour;
     * the fill is the same alpha with the art's colours. If the two alphas diverged — the obvious way being a
     * re-export of one file and not the other — the "empty" channel and the "filled" bar would have different
     * outlines, and a half-full gauge would show a crescent of trough inside the fill.
     */
    @Test
    void theTrackAndTheFillShareASilhouette() throws Exception {
        BufferedImage track = load(GaugeArt.TRACK_PATH);
        BufferedImage fill = load(GaugeArt.FILL_PATH);
        for (int y = 0; y < track.getHeight(); y++) {
            for (int x = 0; x < track.getWidth(); x++) {
                int trackAlpha = (track.getRGB(x, y) >>> 24) & 0xFF;
                int fillAlpha = (fill.getRGB(x, y) >>> 24) & 0xFF;
                assertEquals(trackAlpha, fillAlpha,
                        "alpha differs at " + x + "," + y + " — the fill would not sit inside the trough");
            }
        }
        // And the trough really is one colour, so an empty bar cannot read as half-charged.
        java.util.Set<Integer> troughColours = new java.util.HashSet<>();
        for (int y = 0; y < track.getHeight(); y++) {
            for (int x = 0; x < track.getWidth(); x++) {
                if (((track.getRGB(x, y) >>> 24) & 0xFF) > 200) {
                    troughColours.add(track.getRGB(x, y) & 0x00FFFFFF);
                }
            }
        }
        assertEquals(1, troughColours.size(),
                "the trough must be a single neutral, got " + troughColours.size() + " colours");
    }

    /** The committed art is the fever bar, not the square gauge tile it replaced: a bar is wider than it is tall. */
    @Test
    void theArtIsABarNotASquareTile() {
        assertTrue(GaugeArt.SOURCE_WIDTH > GaugeArt.SOURCE_HEIGHT * 4,
                "a square tile stretched into a bar is exactly what the human rejected; the committed art is "
                        + GaugeArt.SOURCE_WIDTH + "x" + GaugeArt.SOURCE_HEIGHT);
        assertTrue(HudLayout.BAR_WIDTH > HudLayout.BAR_HEIGHT * 4,
                "and the HUD draws it as a bar");
    }

    /** The HUD's own numbers still make a drawable bar, so the blit cannot be handed an empty rect. */
    @Test
    void theHudBarHasRoomForTheArt() {
        assertTrue(HudLayout.BAR_HEIGHT >= 8,
                "the official gauge has a rim, a body and a core; " + HudLayout.BAR_HEIGHT
                        + " pixels collapses them into one line");
        assertTrue(HudLayout.RING_DIAMETER >= 14,
                "the preset face is drawn inside the ring and must be recognisable: "
                        + HudLayout.RING_DIAMETER);
        assertTrue(HudLayout.WIDGET_HEIGHT == Math.max(HudLayout.RING_DIAMETER, HudLayout.BAR_HEIGHT));
        GaugeArt.Rect track = new GaugeArt.Rect(0, 0, HudLayout.BAR_WIDTH, HudLayout.BAR_HEIGHT);
        assertTrue(track.isDrawable());
        assertEquals(HudLayout.BAR_WIDTH, track.right());
        assertEquals(HudLayout.BAR_HEIGHT, track.bottom());
    }

    // ------------------------------------------------------------------ helpers

    private static BufferedImage load(String path) throws Exception {
        try (InputStream in = GaugeArtTest.class.getResourceAsStream("/assets/astrum/" + path)) {
            assertNotNull(in, "the gauge texture must be packaged: /assets/astrum/" + path);
            return ImageIO.read(in);
        }
    }

    /** {@return how many pixels are more than a quarter opaque} */
    private static int opaquePixels(BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) > 64) {
                    count++;
                }
            }
        }
        return count;
    }

    /** {@return the mean RGB of the opaque pixels, as one packed int, for a "which is brighter" check} */
    private static int averageArgb(BufferedImage image) {
        long sum = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) > 64) {
                    sum += ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
                    count++;
                }
            }
        }
        return count == 0 ? 0 : (int) (sum / count);
    }
}
