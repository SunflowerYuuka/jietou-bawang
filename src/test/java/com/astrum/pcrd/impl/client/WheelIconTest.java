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
}
