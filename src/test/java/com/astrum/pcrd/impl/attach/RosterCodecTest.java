package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.InternalIds;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RosterCodecTest {
    @Test void rosterRoundTrips() {
        var r = new Roster(Map.of(InternalIds.id("djeeta"), new CharacterProgress(7, 12, 2)), 1);
        var enc = Roster.CODEC.encodeStart(JsonOps.INSTANCE, r).getOrThrow();
        assertEquals(Optional.of(r), Roster.CODEC.parse(JsonOps.INSTANCE, enc).result());
    }
    @Test void partyAlwaysHasFiveSlots() {
        var p = Party.of(List.of(InternalIds.id("djeeta")));
        assertEquals(5, p.slots().size());
        assertEquals(Optional.of(InternalIds.id("djeeta")), p.slots().get(0));
        assertTrue(p.slots().subList(1, 5).stream().allMatch(Optional::isEmpty));
    }
    @Test void sameCharacterCannotOccupyTwoSlots() {
        var d = InternalIds.id("djeeta");
        var p = Party.of(List.of(d, d));
        assertEquals(1, p.slots().stream().filter(s -> s.equals(Optional.of(d))).count());
    }
    @Test void unknownDataVersionIsRejectedNotCrashed() {
        var json = JsonParser.parseString("{\"dataVersion\":99,\"entries\":{}}");
        assertTrue(Roster.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
    }

    // ---------------------------------------------------------------------------------------
    // Everything below is what a save file can actually do to these codecs. playerdata is NBT,
    // so the JsonOps tests above are necessary but not sufficient evidence of a safe save.
    // ---------------------------------------------------------------------------------------

    /** The real serialization path: {@code AttachmentType.serialize(Codec)} writes with NbtOps. */
    @Test void rosterSurvivesTheNbtRoundTripTheSaveFileUses() {
        var r = new Roster(Map.of(InternalIds.id("djeeta"), new CharacterProgress(7, 12, 2)), 1);
        var enc = Roster.CODEC.encodeStart(NbtOps.INSTANCE, r).getOrThrow();
        assertEquals(Optional.of(r), Roster.CODEC.parse(NbtOps.INSTANCE, enc).result(), enc.toString());
        // The version must be in the tag, otherwise a future reader cannot tell what it is.
        assertTrue(((CompoundTag) enc).contains("dataVersion"), enc.toString());
    }

    @Test void rosterKeepsAllThreeProgressFields() {
        var r = new Roster(Map.of(InternalIds.id("djeeta"), new CharacterProgress(3, 40, 1)), 1);
        var enc = Roster.CODEC.encodeStart(NbtOps.INSTANCE, r).getOrThrow();
        var back = Roster.CODEC.parse(NbtOps.INSTANCE, enc).getOrThrow().entries().get(InternalIds.id("djeeta"));
        assertEquals(3, back.level());
        assertEquals(40, back.exp());
        assertEquals(1, back.affection());
    }

    @Test void partySurvivesTheNbtRoundTripIncludingEmptySlots() {
        var p = Party.of(List.of(InternalIds.id("djeeta")));
        var enc = Party.CODEC.encodeStart(NbtOps.INSTANCE, p).getOrThrow();
        assertEquals(Optional.of(p), Party.CODEC.parse(NbtOps.INSTANCE, enc).result(), enc.toString());
    }

    @Test void encodedPartyIsAlwaysFiveElements() {
        var enc = Party.CODEC.encodeStart(NbtOps.INSTANCE, Party.empty()).getOrThrow();
        assertEquals(5, ((ListTag) enc).size(), enc.toString());
    }

    // ---------------------------------------------------------------------------------------
    // Slot POSITION is meaning (wheel position + diminishing priority), so a save must never
    // move a character to a different slot. These three pin that down.
    // ---------------------------------------------------------------------------------------

    @Test void aCharacterInSlotTwoComesBackInSlotTwo() {
        var p = Party.empty().with(2, InternalIds.id("djeeta"));
        var enc = Party.CODEC.encodeStart(NbtOps.INSTANCE, p).getOrThrow();
        assertEquals(Optional.of(p), Party.CODEC.parse(NbtOps.INSTANCE, enc).result(), enc.toString());
    }

    @Test void decodedEmptyLeadingSlotIsHolesNotACompactedList() {
        var json = JsonParser.parseString("[\"\",\"\",\"astrum:djeeta\",\"\",\"\"]");
        var p = Party.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(Optional.empty(), p.slots().get(0));
        assertEquals(Optional.of(InternalIds.id("djeeta")), p.slots().get(2));
    }

    @Test void decodedDuplicateKeepsTheEarlierSlotAndEmptiesTheLaterOne() {
        var json = JsonParser.parseString("[\"astrum:djeeta\",\"\",\"astrum:djeeta\",\"\",\"\"]");
        var p = Party.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(Optional.of(InternalIds.id("djeeta")), p.slots().get(0));
        assertEquals(Optional.empty(), p.slots().get(2));
    }

    @Test void withMovesACharacterInsteadOfDuplicatingIt() {
        var d = InternalIds.id("djeeta");
        var p = Party.empty().with(0, d).with(3, d);
        assertEquals(Optional.empty(), p.slots().get(0));
        assertEquals(Optional.of(d), p.slots().get(3));
    }

    /** A cooldown tick beyond int range must not be truncated: cdEndTick is a long on disk too. */
    @Test void ubStateKeepsACooldownBeyondIntRange() {
        var s = new UbState(0.0f, 4, 5_000_000_000L);
        var enc = UbState.CODEC.encodeStart(NbtOps.INSTANCE, s).getOrThrow();
        assertEquals(5_000_000_000L, UbState.CODEC.parse(NbtOps.INSTANCE, enc).getOrThrow().cdEndTick());
    }

    @Test void decodedShortListIsPaddedToFive() {
        var json = JsonParser.parseString("[\"astrum:djeeta\"]");
        var p = Party.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(5, p.slots().size());
        assertEquals(Optional.of(InternalIds.id("djeeta")), p.slots().get(0));
    }

    @Test void partyOfMoreThanFiveKeepsTheFirstFive() {
        var many = new ArrayList<net.minecraft.resources.ResourceLocation>();
        for (int i = 0; i < 7; i++) {
            many.add(InternalIds.id("c" + i));
        }
        var p = Party.of(many);
        assertEquals(5, p.slots().size());
        assertEquals(Optional.of(InternalIds.id("c4")), p.slots().get(4));
        assertEquals(Optional.empty(), p.slots().get(4).filter(id -> id.equals(InternalIds.id("c5"))));
    }

    /** A typo in a slot must not silently become an empty slot: that is silent data loss. */
    @Test void partyRejectsAGarbageSlotInsteadOfSilentlyEmptyingIt() {
        var json = JsonParser.parseString("[\"not a valid id\",\"\",\"\",\"\",\"\"]");
        assertTrue(Party.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
    }

    @Test void ubStateRoundTripsThroughNbt() {
        var s = new UbState(12.5f, 2, 1234L);
        var enc = UbState.CODEC.encodeStart(NbtOps.INSTANCE, s).getOrThrow();
        assertEquals(Optional.of(s), UbState.CODEC.parse(NbtOps.INSTANCE, enc).result(), enc.toString());
        var back = UbState.CODEC.parse(NbtOps.INSTANCE, enc).getOrThrow();
        assertEquals(12.5f, back.charge());
        assertEquals(2, back.selectedSlot());
        assertEquals(1234L, back.cdEndTick());
    }

    @Test void addOrIgnoreAddsANewCharacter() {
        var r = Roster.empty().addOrIgnore(InternalIds.id("djeeta"), new CharacterProgress(1, 0, 0));
        assertEquals(Optional.of(new CharacterProgress(1, 0, 0)),
                Optional.ofNullable(r.entries().get(InternalIds.id("djeeta"))));
    }

    @Test void addOrIgnoreNeverOverwritesExistingProgress() {
        var r = Roster.empty().addOrIgnore(InternalIds.id("djeeta"), new CharacterProgress(5, 10, 2));
        var again = r.addOrIgnore(InternalIds.id("djeeta"), new CharacterProgress(1, 0, 0));
        assertEquals(new CharacterProgress(5, 10, 2), again.entries().get(InternalIds.id("djeeta")));
    }

    /** Add-or-ignore must not hand back a mutable alias of the map it was given. */
    @Test void rosterCopiesItsMapSoLaterMutationCannotReachIt() {
        var backing = new LinkedHashMap<net.minecraft.resources.ResourceLocation, CharacterProgress>();
        backing.put(InternalIds.id("djeeta"), new CharacterProgress(1, 0, 0));
        var r = new Roster(backing, 1);
        backing.clear();
        assertEquals(1, r.entries().size());
        assertThrows(UnsupportedOperationException.class,
                () -> r.entries().put(InternalIds.id("x"), new CharacterProgress(1, 0, 0)));
    }

    /** Writing a version this build cannot read back would be the corruption this codec exists to stop. */
    @Test void encodingAnUnknownDataVersionFailsInsteadOfWritingIt() {
        var r = new Roster(Map.of(), 99);
        assertTrue(Roster.CODEC.encodeStart(JsonOps.INSTANCE, r).error().isPresent());
    }

    @Test void versionZeroFromThePlaceholderIsRejectedToo() {
        var json = JsonParser.parseString("{\"dataVersion\":0,\"entries\":{}}");
        assertTrue(Roster.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
    }

    @Test void rosterWithNoEntriesDecodes() {
        var json = JsonParser.parseString("{\"dataVersion\":1,\"entries\":{}}");
        assertEquals(Optional.of(Roster.empty()), Roster.CODEC.parse(JsonOps.INSTANCE, json).result());
    }
}
