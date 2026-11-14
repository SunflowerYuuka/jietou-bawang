package com.astrum.pcrd.impl.client;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the wheel draws for a character, and what makes a tier-3 character recognizable.
 *
 * <p>Spec §6.2 requires the enhanced icon, for a reason worth restating: without it the player cannot
 * tell that affection tier 3 changed anything, and the whole affection track reads as a number that
 * goes up. The pack ships no UI art yet, so the difference has to exist in the drawing rules rather
 * than only in a texture — which is exactly what this test pins: the enhanced icon differs from the
 * base one in tint, in ring count and in marker, and stays opaque either way (spec §5.5).
 */
class WheelIconTest {

    private static final ResourceLocation PORTRAIT = ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/portrait/djeeta.png");

    @Test
    void tierThreeDiffersFromBaseInEveryVisiblePart() {
        WheelIcon base = WheelIcon.of(Optional.of(PORTRAIT), false);
        WheelIcon enhanced = WheelIcon.of(Optional.of(PORTRAIT), true);

        assertNotEquals(base.tint(), enhanced.tint(), "the enhanced icon must not be the same colour");
        assertNotEquals(base.rings(), enhanced.rings(), "the enhanced icon must have a distinct shape");
        assertNotEquals(base.marker(), enhanced.marker(), "the enhanced icon must carry a marker");
        assertFalse(base.enhanced());
        assertTrue(enhanced.enhanced());
    }

    @Test
    void bothVariantsAreOpaque() {
        for (boolean enhanced : new boolean[] {false, true}) {
            WheelIcon icon = WheelIcon.of(Optional.empty(), enhanced);
            assertEquals(0xFF, icon.tint() >>> 24, "icons stay opaque (spec §5.5: 图标与文字不透明)");
        }
    }

    @Test
    void thePortraitIsPassedThroughUnchangedOrAbsent() {
        assertEquals(Optional.of(PORTRAIT), WheelIcon.of(Optional.of(PORTRAIT), false).texture());
        assertEquals(Optional.of(PORTRAIT), WheelIcon.of(Optional.of(PORTRAIT), true).texture(),
                "tier 3 changes the frame around the art, not the art itself");
        assertEquals(Optional.empty(), WheelIcon.of(Optional.empty(), true).texture());
    }

    @Test
    void aCharacterWithoutArtStillHasSomethingToDraw() {
        WheelIcon icon = WheelIcon.of(Optional.empty(), false);
        assertEquals(WheelIcon.BASE_RINGS, icon.rings(), "a procedurally drawn icon still has a shape");
        assertTrue(icon.tint() != 0, "and a colour");
    }

    // -----------------------------------------------------------------------------------------
    // The book's square frame — the fix for 「角色册里面头像的那个圆圈」
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The square frame never covers the picture, and never leaves the slot.</b>
     *
     * <h2>What the human saw</h2>
     * 「角色册里面头像的那个圆圈」 — a ring drawn round each roster avatar. In the book the portrait is blitted as a
     * <b>square</b> whose edge is the cell's {@code iconDiameter}, and the ring used to be a circle of radius
     * {@code iconDiameter / 2} centred on it. A circle of that radius touches a square of that side at the four
     * edge midpoints and <b>cuts through the corners</b>: the strokes crossed her face and the picture's corners
     * poked out past the ring. Round peg, square hole.
     *
     * <p>So the frame is a square now, nested inside the slot, and the picture gives up one pixel per stroke.
     * These two assertions are the whole fix: no stroke pixel can land on the picture, and no stroke pixel can
     * land outside the rectangle the layout reserved for the icon (which is what keeps it off the level text and
     * off the neighbouring cell at the compact tier, where the gap is one pixel).
     */
    @Test
    void theSquareFrameNeverCoversThePictureOrLeavesTheSlot() {
        for (int slotEdge : new int[] {20, 40, 16, 9}) {
            for (int rings = WheelIcon.BASE_RINGS; rings <= WheelIcon.ENHANCED_RINGS; rings++) {
                int inset = WheelIconDraw.insetFor(rings);
                int pictureX = 100 + inset;
                int pictureY = 50 + inset;
                int pictureEdge = Math.max(1, slotEdge - 2 * inset);
                assertTrue(pictureEdge > 1,
                        "slot " + slotEdge + " with " + rings + " rings keeps a " + pictureEdge
                                + "px picture");
                for (int frameIndex = 0; frameIndex < rings; frameIndex++) {
                    WheelIconDraw.Frame frame = WheelIconDraw.frameAt(100, 50, slotEdge, frameIndex);
                    assertTrue(frame.clears(pictureX, pictureY, pictureEdge),
                            "slot " + slotEdge + " frame " + frameIndex + " (" + frame
                                    + ") must clear the picture");
                    assertTrue(frame.inside(100, 50, slotEdge),
                            "slot " + slotEdge + " frame " + frameIndex + " must stay inside the slot");
                }
            }
        }
    }

    /**
     * The enhanced frame is drawn inside the base one, so the two strokes are two lines and not one.
     *
     * <p>Tier 3's second frame is half of the visible difference between a base and an enhanced character (the
     * {@code II} marker is the other half); two strokes at the same place would draw one line and leave the
     * marker carrying the whole distinction.
     */
    @Test
    void theEnhancedFrameIsNestedInsideTheBaseFrame() {
        WheelIconDraw.Frame base = WheelIconDraw.frameAt(0, 0, 40, 0);
        WheelIconDraw.Frame enhanced = WheelIconDraw.frameAt(0, 0, 40, 1);

        assertTrue(enhanced.left() > base.left(), "the enhanced frame moves in on the left");
        assertTrue(enhanced.top() > base.top(), "and on the top");
        assertTrue(enhanced.right() < base.right(), "and on the right");
        assertTrue(enhanced.bottom() < base.bottom(), "and on the bottom");
        assertEquals(base.width() - 2, enhanced.width(), "one pixel per side between the two strokes");
    }

    /**
     * The picture keeps a real size even in the smallest slot this project draws into.
     *
     * <p>The frames are nested <b>inside</b> the slot (see {@code WheelIconDraw}: at the compact tier the roster
     * cell leaves the text column one pixel, so a frame drawn outside the picture would land on the level text).
     * That costs the picture one pixel per side per stroke, so an enhanced icon loses two pixels per side.
     *
     * <p>The tightest case is the compact party slot: {@code slotSize} 18 minus 2 pixels of plate per side is a
     * 14-pixel picture, and two strokes leave 10. Ten pixels of a 64-pixel portrait is small but it is the same
     * slot that is only 14 pixels wide; the alternative — no frame, or a frame that crosses her face — is what
     * the human rejected. This asserts the loss is bounded and the picture is not swallowed.
     */
    @Test
    void thePictureSurvivesTheFramesAtTheSmallestSlot() {
        for (BookLayout layout : new BookLayout[] {BookLayout.at(320, 240), BookLayout.at(1280, 720)}) {
            for (int rings : new int[] {WheelIcon.BASE_RINGS, WheelIcon.ENHANCED_RINGS}) {
                int slot = Math.min(layout.cellIconDiameter(), layout.slotIconDiameter());
                int inset = WheelIconDraw.insetFor(rings);
                int picture = slot - 2 * inset;

                assertEquals(slot - 2 * rings, picture,
                        layout.tierName() + ": one pixel per side per stroke, no more");
                assertTrue(picture >= slot - 2 * WheelIcon.ENHANCED_RINGS,
                        layout.tierName() + ": the frames must not eat more than one pixel per stroke");
                assertTrue(picture >= 10, layout.tierName() + ": an enhanced icon keeps a " + picture
                        + "px picture of the " + slot + "px slot; the portrait must stay recognizable");
            }
        }
    }
}
