package com.astrum.pcrd.impl.party;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared party rules: who may be placed where, and what "move a character from slot A to slot B"
 * means.
 *
 * <h2>Why the move cases are the important ones</h2>
 * "A character cannot occupy two slots" is the rule most likely to be re-implemented, because the
 * natural client-side way to write it is "copy the character into the clicked slot" — which produces a
 * party the data layer then silently repairs by dropping the later duplicate, i.e. the character moves
 * on its own the next time the save is read. {@link PartyRules#place} goes through {@code Party.with},
 * which empties the old slot on purpose, so every case here is asserted by <b>counting occurrences</b>
 * of the character across the five slots rather than by looking at the target slot alone.
 *
 * <p>The three move shapes:
 * <ul>
 *   <li>target empty, character elsewhere → the character is in the target and nowhere else;</li>
 *   <li>target already holds it → unchanged (a click on the slot it already occupies is a no-op, not a
 *       second copy and not an error);</li>
 *   <li>target holds somebody else → the character moves in, the displaced one leaves the party.</li>
 * </ul>
 */
class PartyRulesTest {

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KOKKORO = InternalIds.id("kokkoro");
    private static final ResourceLocation YUI = InternalIds.id("yui");

    // ---------------------------------------------------------------------------------------
    // refusal(): one assertion per reason, plus the order the checks run in
    // ---------------------------------------------------------------------------------------

    @Test
    void aLegalEditOnAnOwnedCharacterIsAccepted() {
        assertEquals(Optional.empty(), PartyRules.refusal(new PartyRules.Facts(0, true, true, true, true)));
    }

    @Test
    void everySlotOfThePartyIsLegal() {
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            assertEquals(Optional.empty(),
                    PartyRules.refusal(new PartyRules.Facts(slot, true, true, true, true)),
                    "slot " + slot + " is one of the five and must be accepted");
        }
    }

    @Test
    void aSlotOutsideThePartyIsRefused() {
        for (int slot : new int[] {-1, Party.SLOTS, Party.SLOTS + 3, Integer.MIN_VALUE,
                Integer.MAX_VALUE}) {
            assertEquals(Optional.of(PartyRules.Reason.SLOT_OUT_OF_RANGE),
                    PartyRules.refusal(new PartyRules.Facts(slot, true, true, true, true)),
                    "slot " + slot + " is not part of the party");
        }
    }

    @Test
    void anUnownedCharacterIsRefused() {
        assertEquals(Optional.of(PartyRules.Reason.NOT_OWNED),
                PartyRules.refusal(new PartyRules.Facts(2, true, true, true, false)));
    }

    @Test
    void aCharacterThePackDoesNotDefineIsRefused() {
        assertEquals(Optional.of(PartyRules.Reason.CHARACTER_UNKNOWN),
                PartyRules.refusal(new PartyRules.Facts(2, true, true, false, true)));
    }

    @Test
    void noLoadedCharacterDataIsRefused() {
        assertEquals(Optional.of(PartyRules.Reason.NO_REGISTRY),
                PartyRules.refusal(new PartyRules.Facts(2, true, false, true, true)));
    }

    @Test
    void anUnreadableBookIsRefusedBeforeAnythingElseIsAsked() {
        // Even with an impossible slot and an unowned character: a quarantined book is the state the
        // player has to be told about, and nothing about its contents is knowable.
        assertEquals(Optional.of(PartyRules.Reason.BOOK_UNREADABLE),
                PartyRules.refusal(new PartyRules.Facts(99, false, false, false, false)));
    }

    @Test
    void anOutOfRangeSlotIsReportedBeforeTheCharacterIsLookedAt() {
        // Order matters for the player's sentence: "no such slot" is true and useful, "not owned" is a
        // guess about a request that could never have succeeded.
        assertEquals(Optional.of(PartyRules.Reason.SLOT_OUT_OF_RANGE),
                PartyRules.refusal(new PartyRules.Facts(-1, true, false, false, false)));
    }

    @Test
    void everyRefusalHasItsOwnTranslationKey() {
        for (PartyRules.Reason reason : PartyRules.Reason.values()) {
            assertTrue(PartyRules.reasonKey(reason).startsWith("astrum.party.reason."),
                    "unexpected key shape for " + reason);
            assertTrue(PartyRules.reasonKey(reason).endsWith(reason.name()),
                    "the key must be derived from the reason name, so a rename cannot leave a stale key");
            assertTrue(BookText.LANG_KEYS.contains(PartyRules.reasonKey(reason)),
                    "no translation is registered for " + reason);
        }
    }

    // ---------------------------------------------------------------------------------------
    // slotOf(): the one implementation of "where is this character"
    // ---------------------------------------------------------------------------------------

    @Test
    void slotOfFindsTheCharacterWhereverItSits() {
        Party party = party(null, DJEETA, null, YUI, null);
        assertEquals(OptionalInt.of(1), PartyRules.slotOf(party, DJEETA));
        assertEquals(OptionalInt.of(3), PartyRules.slotOf(party, YUI));
    }

    @Test
    void slotOfIsEmptyForACharacterOutsideTheParty() {
        assertTrue(PartyRules.slotOf(party(DJEETA), KOKKORO).isEmpty());
        assertTrue(PartyRules.slotOf(Party.empty(), DJEETA).isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // place(): the move
    // ---------------------------------------------------------------------------------------

    @Test
    void placingACharacterInAnEmptySlotPutsItThereAndNowhereElse() {
        PartyRules.Edit edit = PartyRules.place(party(DJEETA), 2, KOKKORO);

        assertEquals(Optional.of(KOKKORO), at(edit.after(), 2));
        assertEquals(1, occurrences(edit.after(), KOKKORO));
        assertFalse(edit.moved(), "it was not in the party before, so this is not a move");
        assertTrue(edit.changed());
    }

    @Test
    void movingACharacterEmptiesTheSlotItCameFrom() {
        PartyRules.Edit edit = PartyRules.place(party(DJEETA, null, KOKKORO), 4, DJEETA);

        assertEquals(Optional.of(DJEETA), at(edit.after(), 4), "the character is in the target slot");
        assertEquals(Optional.empty(), at(edit.after(), 0), "and out of the slot it came from");
        assertEquals(1, occurrences(edit.after(), DJEETA),
                "a move, not a copy: the character may not occupy two slots");
        assertEquals(OptionalInt.of(0), edit.previousSlot());
        assertTrue(edit.moved());
    }

    @Test
    void movingOntoAnOccupiedSlotEvictsTheOtherCharacter() {
        PartyRules.Edit edit = PartyRules.place(party(DJEETA, KOKKORO, YUI), 1, DJEETA);

        assertEquals(Optional.of(DJEETA), at(edit.after(), 1));
        assertEquals(1, occurrences(edit.after(), DJEETA));
        assertEquals(0, occurrences(edit.after(), KOKKORO), "the displaced character leaves the party");
        assertEquals(Optional.of(YUI), at(edit.after(), 2), "and the far slot is untouched");
        assertTrue(edit.moved());
    }

    @Test
    void placingACharacterWhereItAlreadyIsChangesNothing() {
        Party party = party(null, DJEETA);
        PartyRules.Edit edit = PartyRules.place(party, 1, DJEETA);

        assertEquals(party, edit.after());
        assertFalse(edit.changed());
        assertFalse(edit.moved(), "the same slot is not a move");
        assertEquals(OptionalInt.of(1), edit.previousSlot());
    }

    @Test
    void clearingASlotRemovesOnlyThatCharacter() {
        PartyRules.Edit edit = PartyRules.place(party(DJEETA, KOKKORO), 0, null);

        assertEquals(Optional.empty(), at(edit.after(), 0));
        assertEquals(Optional.of(KOKKORO), at(edit.after(), 1));
        assertTrue(edit.cleared());
        assertTrue(edit.changed());
        assertTrue(edit.previousSlot().isEmpty(), "a clear names no character");
    }

    @Test
    void clearingAnEmptySlotChangesNothing() {
        PartyRules.Edit edit = PartyRules.place(party(DJEETA), 3, null);

        assertFalse(edit.changed());
        assertFalse(edit.cleared());
    }

    @Test
    void placeRefusesASlotThePartyDoesNotHave() {
        // The gate is refusal(); reaching place() with a bad slot is a programming error, and Party.with
        // would throw anyway. Asserted so the contract is explicit rather than implied.
        assertThrows(IllegalArgumentException.class, () -> PartyRules.place(Party.empty(), 5, DJEETA));
        assertThrows(IllegalArgumentException.class, () -> PartyRules.place(Party.empty(), -1, DJEETA));
    }

    /** A five-slot party from per-slot values; {@code null} means empty. */
    private static Party party(ResourceLocation... slots) {
        List<Optional<ResourceLocation>> values = new java.util.ArrayList<>(Party.SLOTS);
        for (int i = 0; i < Party.SLOTS; i++) {
            ResourceLocation value = i < slots.length ? slots[i] : null;
            values.add(Optional.ofNullable(value));
        }
        return Party.ofSlots(values);
    }

    /** The contents of one slot, as {@link Party} exposes them. */
    private static Optional<ResourceLocation> at(Party party, int slot) {
        return party.slots().get(slot);
    }

    /** How many of the five slots hold {@code characterId}. */
    private static int occurrences(Party party, ResourceLocation characterId) {
        int found = 0;
        for (Optional<ResourceLocation> slot : party.slots()) {
            if (slot.filter(characterId::equals).isPresent()) {
                found++;
            }
        }
        return found;
    }
}

