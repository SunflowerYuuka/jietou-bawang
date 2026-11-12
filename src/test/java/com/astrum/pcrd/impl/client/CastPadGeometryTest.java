package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cast pad's arithmetic, asserted instead of eyeballed (spec §5.5: 释放键长按时给一个轻量的槽位高亮条,
 * 不画整圈轮盘).
 *
 * <h2>Why the pad needed its own geometry at all</h2>
 * The ring's numbers cannot describe a strip: {@link WheelGeometry} answers "which sector is this
 * angle", and a pad has no angles. What the pad does need is the same kind of claim — "five chips, in
 * party order, centred, clear of the hotbar, all on screen" — and those are assertions about
 * rectangles, so they live here as pure functions and are pinned in this test. {@code CastPadOverlay}
 * then draws rectangles at these coordinates and computes nothing.
 *
 * <h2>Why it is not the wheel drawn smaller</h2>
 * The human's requirement (spec §5.5) is that holding the cast key shows the <em>minimum</em> information
 * needed — which slot does {@code 1}–{@code 5} fire — with the least screen noise, because it appears
 * mid-combat. A ring answers "which direction am I sweeping"; the pad answers "which number is which
 * slot", so it is one row of numbered chips and nothing else.
 */
class CastPadGeometryTest {

    /** A 640×360 GUI (1080p at scale 3) — the size the numbers in the javadoc are quoted at. */
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;

    @Test
    void thePadHasOneChipPerPartySlot() {
        assertEquals(Party.SLOTS, CastPadGeometry.CHIPS);
        assertEquals(WheelGeometry.SEGMENTS, CastPadGeometry.CHIPS,
                "pad and ring index the same party slots, so they must agree on how many there are");
    }

    @Test
    void theStripIsCentredAndSitsBelowTheCrosshair() {
        assertEquals(0.0, CastPadGeometry.stripCenterX(WIDTH) - WIDTH / 2.0, 1e-9);
        assertTrue(CastPadGeometry.stripCenterY(HEIGHT) > HEIGHT / 2.0,
                "the pad sits below the screen centre, clear of the crosshair");
        assertEquals(HEIGHT / 2.0 + HEIGHT * CastPadGeometry.VERTICAL_OFFSET, CastPadGeometry.stripCenterY(HEIGHT),
                1e-9);
        assertTrue(CastPadGeometry.VERTICAL_OFFSET > 0.0 && CastPadGeometry.VERTICAL_OFFSET < 0.25,
                "a small offset: the pad is a hint, not a panel");
    }

    @Test
    void theStripWidthIsTheChipsPlusTheGaps() {
        assertEquals(CastPadGeometry.CHIPS * CastPadGeometry.CHIP_WIDTH
                        + (CastPadGeometry.CHIPS - 1) * CastPadGeometry.CHIP_GAP,
                CastPadGeometry.stripWidth(), 1e-9);
        assertEquals(136.0, CastPadGeometry.stripWidth(), 1e-9,
                "five 24 px chips with four 4 px gaps: small enough to read at a glance");
    }

    @Test
    void theChipsRunLeftToRightInPartyOrderWithoutOverlapping() {
        double previousRight = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < CastPadGeometry.CHIPS; index++) {
            CastPadGeometry.Chip chip = CastPadGeometry.chip(WIDTH, HEIGHT, index);
            assertEquals(CastPadGeometry.CHIP_WIDTH, chip.width(), 1e-9);
            assertEquals(CastPadGeometry.CHIP_HEIGHT, chip.height(), 1e-9);
            assertEquals(CastPadGeometry.stripCenterY(HEIGHT), chip.centerY(), 1e-9,
                    "every chip shares the strip's centre line");
            if (index > 0) {
                assertEquals(previousRight + CastPadGeometry.CHIP_GAP, chip.left(), 1e-9,
                        "chip " + index + " starts exactly one gap to the right of its predecessor");
            }
            assertTrue(chip.right() > chip.left());
            previousRight = chip.right();
            // The centre of every chip must be inside it, or a digit's number would be drawn off the chip.
            assertTrue(chip.centerX() > chip.left() && chip.centerX() < chip.right());
        }
    }

    @Test
    void theMiddleChipIsExactlyOnTheCentreLine() {
        CastPadGeometry.Chip middle = CastPadGeometry.chip(WIDTH, HEIGHT, CastPadGeometry.CHIPS / 2);
        assertEquals(WIDTH / 2.0, middle.centerX(), 1e-9,
                "with an odd number of chips one of them is centred, so the strip reads symmetric");
    }

    @Test
    void theWholeStripStaysOnScreenAndClearOfTheHotbar() {
        int[][] sizes = {{320, 240}, {427, 240}, {640, 360}, {854, 480}, {1280, 720}, {1920, 1080}};
        for (int[] size : sizes) {
            for (int index = 0; index < CastPadGeometry.CHIPS; index++) {
                CastPadGeometry.Chip chip = CastPadGeometry.chip(size[0], size[1], index);
                String where = size[0] + "x" + size[1] + " chip " + index;
                assertTrue(chip.left() >= 0.0, where + ": left edge on screen");
                assertTrue(chip.right() <= size[0], where + ": right edge on screen");
                assertTrue(chip.top() >= 0.0, where + ": top edge on screen");
                assertTrue(chip.bottom() <= size[1] - HudLayout.BOTTOM_RESERVED,
                        where + ": the pad must stay above the hotbar and the experience bar");
            }
        }
    }

    @Test
    void onlyPointsInsideAChipNameThatChip() {
        assertEquals(CastPadGeometry.NO_CHIP, CastPadGeometry.chipAt(WIDTH, HEIGHT, 0, 0));
        assertEquals(CastPadGeometry.NO_CHIP,
                CastPadGeometry.chipAt(WIDTH, HEIGHT, WIDTH / 2.0, HEIGHT - 1));
        // ... and a point inside a chip's own rectangle is that chip, which is what makes the
        // "no chip" answers above meaningful rather than a function that always says no.
        CastPadGeometry.Chip chip = CastPadGeometry.chip(WIDTH, HEIGHT, 3);
        assertEquals(3, CastPadGeometry.chipAt(WIDTH, HEIGHT, chip.centerX(), chip.centerY()));
        assertNotEquals(CastPadGeometry.NO_CHIP,
                CastPadGeometry.chipAt(WIDTH, HEIGHT, chip.left() + 0.5, chip.top() + 0.5));
    }
}
