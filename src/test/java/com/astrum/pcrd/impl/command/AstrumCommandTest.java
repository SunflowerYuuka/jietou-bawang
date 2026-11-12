package com.astrum.pcrd.impl.command;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.ub.CastGate;
import java.util.OptionalInt;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pure pieces of {@code /astrum}: the slot convention and the affection clamp.
 *
 * <h2>Why the slot map is written out digit by digit</h2>
 * The command line is 1-based ({@code /astrum party set 1 …}) while the party, the wheel and the cast
 * path are 0-based. An off-by-one of exactly this kind has already shipped in this project — the wheel
 * used to send its digit straight through as a slot index, so {@code 1} cast the second character —
 * and a test written as {@code assertEquals(slot - 1, indexOf(slot))} would move with the bug instead
 * of catching it. So every digit is its own assertion, and the three ways to be out of range are
 * separate, exactly as {@code WheelSlotsTest} does it for the keyboard.
 */
class AstrumCommandTest {

    @Test
    void commandSlotOneIsIndexZero() {
        assertEquals(0, indexOf(1));
    }

    @Test
    void commandSlotTwoIsIndexOne() {
        assertEquals(1, indexOf(2));
    }

    @Test
    void commandSlotThreeIsIndexTwo() {
        assertEquals(2, indexOf(3));
    }

    @Test
    void commandSlotFourIsIndexThree() {
        assertEquals(3, indexOf(4));
    }

    @Test
    void commandSlotFiveIsIndexFour() {
        assertEquals(4, indexOf(5));
    }

    @Test
    void zeroIsNotACommandSlot() {
        assertTrue(AstrumCommand.slotIndex(0).isEmpty(),
                "slot 0 must not silently mean the first slot");
    }

    @Test
    void sixIsPastTheLastCommandSlot() {
        assertTrue(AstrumCommand.slotIndex(6).isEmpty(), "the party has five slots, numbered 1-5");
    }

    @Test
    void negativeSlotsHaveNoIndex() {
        assertTrue(AstrumCommand.slotIndex(-1).isEmpty());
        assertTrue(AstrumCommand.slotIndex(Integer.MIN_VALUE).isEmpty());
    }

    @Test
    void affectionIsAddedOnTopOfWhatThePlayerHas() {
        assertEquals(2, AstrumCommand.clampAffection(1, 1, 8));
        assertEquals(8, AstrumCommand.clampAffection(5, 3, 8));
    }

    @Test
    void affectionStopsAtTheTier3Threshold() {
        assertEquals(8, AstrumCommand.clampAffection(7, 5, 8),
                "affection beyond the tier-3 threshold buys nothing, so the command clamps to it");
        assertEquals(8, AstrumCommand.clampAffection(8, 1, 8));
    }

    @Test
    void affectionNeverGoesBelowZero() {
        assertEquals(0, AstrumCommand.clampAffection(1, -5, 8));
        assertEquals(0, AstrumCommand.clampAffection(0, -1, 8));
    }

    @Test
    void aCharacterWithoutATier3IsNotCapped() {
        assertEquals(50, AstrumCommand.clampAffection(0, 50, 0),
                "tier3Threshold <= 0 means the pack defines no tier 3, so there is nothing to clamp to");
    }

    @Test
    void aBareNameIsTakenToBeThisModsCharacter() {
        assertEquals(InternalIds.id("djeeta"), AstrumCommand.parseCharacterId("djeeta"),
                "an operator typing 'djeeta' means astrum:djeeta; requiring the namespace would break "
                        + "the common case");
        assertEquals(InternalIds.id("djeeta"), AstrumCommand.parseCharacterId(" astrum:djeeta "));
    }

    @Test
    void anExplicitNamespaceIsKept() {
        assertEquals(ResourceLocation.parse("minecraft:pig"),
                AstrumCommand.parseCharacterId("minecraft:pig"));
    }

    @Test
    void anUnparseableCharacterIdIsRejected() {
        assertNull(AstrumCommand.parseCharacterId(""));
        assertNull(AstrumCommand.parseCharacterId("   "));
        assertNull(AstrumCommand.parseCharacterId(null));
        assertNull(AstrumCommand.parseCharacterId("astrum:has spaces"));
    }

    /**
     * Every refusal the cast path can return needs a line of text.
     *
     * <p>The key is derived from the enum name, so this is the test that catches a new
     * {@code CastGate.Reason} arriving without a translation — the alternative is a player reading
     * {@code astrum.cast.reason.SOMETHING_NEW} where the reason should be.
     */
    @Test
    void everyCastRefusalIsTranslated() {
        for (CastGate.Reason reason : CastGate.Reason.values()) {
            assertTrue(AstrumCommand.LANG_KEYS.contains(AstrumCommand.reasonKey(reason)),
                    "no translation key is registered for CastGate.Reason." + reason.name());
        }
    }

    private static int indexOf(int commandSlot) {
        OptionalInt slot = AstrumCommand.slotIndex(commandSlot);
        assertTrue(slot.isPresent(), "command slot " + commandSlot + " must map to an index");
        return slot.getAsInt();
    }
}
