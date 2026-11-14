package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.client.BookLayout.Box;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The still background's <b>cover crop</b>: the source rectangle that fills a box without distorting the picture.
 *
 * <h2>Why the crop is arithmetic and not two multiplications at the call site</h2>
 * The committed still is 512x288 (16:9) and the panel's body is roughly 1.37:1, so a background has to lose
 * something. "Cover" means: scale until the box is filled, crop the overflow symmetrically. Three numbers have to
 * agree — the source offset, the source extent, and the fact that the extent lies inside the source — and every
 * mistake is silent:
 *
 * <ul>
 *   <li>a crop that keeps the full source but a stretched destination <b>squashes her face</b>;</li>
 *   <li>an off-centre crop <b>cuts her head off</b> (every official still puts the character in the middle);</li>
 *   <li>a crop that runs past the source edge samples transparent pixels, which draws as a black bar on a HUD
 *       surface that is composited over the world.</li>
 * </ul>
 *
 * <p>So the properties are asserted rather than described: the crop keeps the source's aspect within a pixel, it
 * is centred, it is always inside the source, and both panel tiers produce a non-empty crop.
 */
class StillCoverTest {

    /** The committed still's size, mirrored here so the numbers below are the shipped ones. */
    private static final int SOURCE_W = StillCover.STILL_WIDTH;

    private static final int SOURCE_H = StillCover.STILL_HEIGHT;

    // -----------------------------------------------------------------------------------------
    // Aspect and containment
    // -----------------------------------------------------------------------------------------

    /**
     * The crop's extent along the cropped axis is the <b>rounded ideal</b>: the whole point is that no axis is
     * scaled differently from the other, and that reduces to one arithmetic claim per branch.
     *
     * <p>Stated on the pixel rather than on the ratio, because a ratio bound is either vacuous for a wide box or
     * unattainable for a thin one: a 4-pixel-wide crop of a 1:81 box can only hit its target aspect to within
     * 12%. What is asserted here is exact and is the real invariant — the crop's width (when the box is wider
     * than the art) is {@code round(sourceHeight * destAspect)}, and its height (when the box is taller) is
     * {@code round(sourceWidth / destAspect)} — so a crop of the wrong axis, a squashed crop, and a crop that
     * ignored the destination all fail.
     */
    @Test
    void theCropFollowsTheDestinationAspectByRounding() {
        for (int width = 8; width <= 900; width += 7) {
            for (int height = 8; height <= 700; height += 11) {
                StillCover.Crop crop = StillCover.of(SOURCE_W, SOURCE_H, width, height);
                double destAspect = width / (double) height;
                double sourceAspect = SOURCE_W / (double) SOURCE_H;

                if (destAspect > sourceAspect) {
                    // Wider than the art: full width, height = round(SOURCE_W / destAspect).
                    assertEquals(SOURCE_W, crop.width(),
                            width + "x" + height + ": a wider box keeps the full width");
                    int ideal = (int) Math.round(SOURCE_W / destAspect);
                    assertTrue(Math.abs(crop.height() - ideal) <= 1,
                            width + "x" + height + " should crop the height to ~" + ideal + ", got " + crop);
                } else {
                    assertEquals(SOURCE_H, crop.height(),
                            width + "x" + height + ": a taller box keeps the full height");
                    int ideal = (int) Math.round(SOURCE_H * destAspect);
                    assertTrue(Math.abs(crop.width() - ideal) <= 1,
                            width + "x" + height + " should crop the width to ~" + ideal + ", got " + crop);
                }
            }
        }
    }

    /** The crop never leaves the source: a sample outside it reads as a transparent (black) bar. */
    @Test
    void theCropIsAlwaysInsideTheSource() {
        for (int width = 1; width < 1200; width += 13) {
            for (int height = 1; height < 900; height += 17) {
                StillCover.Crop crop = StillCover.of(SOURCE_W, SOURCE_H, width, height);
                assertTrue(crop.x() >= 0 && crop.y() >= 0, "negative origin: " + crop);
                assertTrue(crop.right() <= SOURCE_W, "crop runs past the source's right edge: " + crop);
                assertTrue(crop.bottom() <= SOURCE_H, "crop runs past the source's bottom edge: " + crop);
                assertTrue(crop.width() >= 1 && crop.height() >= 1, "an empty crop draws nothing: " + crop);
            }
        }
    }

    /**
     * The crop is <b>centred</b>: the two margins along the cropped axis are equal to within a pixel.
     *
     * <p>This is what keeps the character's face in the picture. An off-centre crop is the failure that would look
     * deliberate — "the still is cropped to her shoulder" — and no exception or log line would say otherwise.
     */
    @Test
    void theCropIsCentred() {
        for (int width = 20; width <= 800; width += 23) {
            for (int height = 20; height <= 600; height += 19) {
                StillCover.Crop crop = StillCover.of(SOURCE_W, SOURCE_H, width, height);
                if (crop.width() < SOURCE_W) {
                    int left = crop.x();
                    int right = SOURCE_W - crop.right();
                    assertTrue(Math.abs(left - right) <= 1,
                            "horizontal crop is off centre: " + left + " left, " + right + " right, " + crop);
                }
                if (crop.height() < SOURCE_H) {
                    int top = crop.y();
                    int bottom = SOURCE_H - crop.bottom();
                    assertTrue(Math.abs(top - bottom) <= 1,
                            "vertical crop is off centre: " + top + " top, " + bottom + " bottom, " + crop);
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // The two panel tiers, and the cases worth naming
    // -----------------------------------------------------------------------------------------

    /** Both panel tiers fill their body: a tall box crops the sides, a wide one crops top and bottom. */
    @Test
    void bothPanelTiersGetAFullBodyCrop() {
        for (BookLayout layout : new BookLayout[] {BookLayout.at(320, 240), BookLayout.at(1280, 720)}) {
            Box body = StillCover.bodyOf(layout.panel());
            StillCover.Crop crop = StillCover.of(SOURCE_W, SOURCE_H, body);
            assertTrue(crop.width() > 0 && crop.height() > 0, layout.tierName() + ": " + crop);

            // The panel body is taller than 16:9 on both tiers, so the full height is kept and the sides go.
            assertEquals(SOURCE_H, crop.height(),
                    layout.tierName() + ": a body taller than the art keeps the art's full height");
            assertTrue(crop.width() <= SOURCE_W);
        }
    }

    /** The same aspect reproduces the whole source: nothing is cropped when there is nothing to crop. */
    @Test
    void anIdenticalAspectUsesTheWholeSource() {
        assertEquals(new StillCover.Crop(0, 0, SOURCE_W, SOURCE_H),
                StillCover.of(SOURCE_W, SOURCE_H, SOURCE_W, SOURCE_H));
        // …and any scaled copy of that aspect, too.
        StillCover.Crop half = StillCover.of(SOURCE_W, SOURCE_H, SOURCE_W / 2, SOURCE_H / 2);
        assertEquals(new StillCover.Crop(0, 0, SOURCE_W, SOURCE_H), half);
    }

    /** A box wider than the art crops top and bottom, keeping the full width. */
    @Test
    void aWiderBoxCropsTheTopAndBottom() {
        StillCover.Crop crop = StillCover.of(512, 288, 1000, 100);
        assertEquals(512, crop.width(), "a wider-than-art box keeps the art's full width");
        assertTrue(crop.height() < 288, "and crops the height: " + crop);
        assertEquals(0, crop.x(), "nothing is cropped horizontally");
        assertTrue(crop.y() > 0, "and what is cropped is split evenly, so y > 0");
    }

    /**
     * Degenerate input is refused rather than drawn.
     *
     * <p>The background is blitted every frame, so a divide-by-zero here costs the player their screen; a 1x1 crop
     * costs them a pixel. The layout cannot produce these sizes, which is exactly why the behaviour is pinned: a
     * future caller (a HUD preview, a config-screen thumbnail) can.
     */
    @Test
    void degenerateSizesAnswerATinyCropInsteadOfThrowing() {
        for (int[] args : new int[][] {{0, 288, 100, 100}, {512, 0, 100, 100}, {512, 288, 0, 100},
                {512, 288, 100, 0}, {-5, 288, 100, 100}, {512, 288, 100, -1}}) {
            StillCover.Crop crop = StillCover.of(args[0], args[1], args[2], args[3]);
            assertEquals(1, crop.width(), "degenerate " + java.util.Arrays.toString(args) + " -> " + crop);
            assertEquals(1, crop.height());
        }
    }
}
