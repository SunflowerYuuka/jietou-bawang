package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.InternalIds;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
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
        // c5 and c6 are the ones that must be gone; assert on the absent ids, not on slot 4 again.
        assertTrue(p.equipped().stream().noneMatch(id -> id.equals(InternalIds.id("c5"))));
        assertTrue(p.equipped().stream().noneMatch(id -> id.equals(InternalIds.id("c6"))));
        assertEquals(5, p.equipped().size());
    }

    /**
     * One unreadable slot must cost one slot, not five. A whole-list error drops the attachment,
     * installs Party.empty() and saves five empty slots — destroying four readable characters.
     */
    @Test void oneGarbageSlotCostsOnlyThatSlot() {
        var json = JsonParser.parseString(
                "[\"astrum:a\",\"not a valid id\",\"astrum:c\",\"astrum:d\",\"astrum:e\"]");
        var p = Party.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(Optional.of(InternalIds.id("a")), p.slots().get(0));
        assertEquals(Optional.empty(), p.slots().get(1));
        assertEquals(Optional.of(InternalIds.id("c")), p.slots().get(2));
        assertEquals(Optional.of(InternalIds.id("d")), p.slots().get(3));
        assertEquals(Optional.of(InternalIds.id("e")), p.slots().get(4));
        assertEquals(4, p.equipped().size());
    }

    /** The record constructor is a public entry point; it may not build a duplicate-slot party. */
    @Test void recordConstructorRefusesToHoldTheSameCharacterTwice() {
        var d = InternalIds.id("djeeta");
        var raw = new ArrayList<Optional<net.minecraft.resources.ResourceLocation>>();
        raw.add(Optional.of(d));
        raw.add(Optional.empty());
        raw.add(Optional.of(d));
        raw.add(Optional.empty());
        raw.add(Optional.empty());
        var p = new Party(raw);
        assertEquals(1, p.slots().stream().filter(s -> s.equals(Optional.of(d))).count());
        assertEquals(Optional.of(d), p.slots().get(0));
    }

    @Test void recordConstructorStillRefusesThreeSlots() {
        assertThrows(IllegalArgumentException.class, () -> new Party(List.of(
                Optional.of(InternalIds.id("a")), Optional.empty(), Optional.empty())));
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

    /**
     * A roster whose version this build cannot write back must be unconstructible, not merely
     * unencodable: the death-copy path encodes with no try/catch, so an encode throw there would
     * propagate out of PlayerList.respawn and crash the server mid-respawn.
     */
    @Test void anUnwritableDataVersionCannotEvenBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new Roster(Map.of(), 99));
        assertThrows(IllegalArgumentException.class, () -> new Roster(Map.of(), 0));
    }

    @Test void versionZeroFromThePlaceholderIsRejectedToo() {
        var json = JsonParser.parseString("{\"dataVersion\":0,\"entries\":{}}");
        assertTrue(Roster.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
    }

    @Test void rosterWithNoEntriesDecodes() {
        var json = JsonParser.parseString("{\"dataVersion\":1,\"entries\":{}}");
        assertEquals(Optional.of(Roster.empty()), Roster.CODEC.parse(JsonOps.INSTANCE, json).result());
    }

    // ---------------------------------------------------------------------------------------
    // Quarantine: a save from a future build must SURVIVE, not be wiped. "Reject the parse" alone
    // is destructive, because AttachmentHolder rebuilds the attachment compound from the in-memory
    // map only, so an attachment that failed to read is simply absent from what gets written back.
    // ---------------------------------------------------------------------------------------

    /** A future version is refused by the schema codec, but the serializer keeps it verbatim. */
    @Test void aFutureVersionIsQuarantinedNotDropped() {
        var future = new CompoundTag();
        future.putInt("dataVersion", 99);
        future.put("entries", new CompoundTag());

        var r = Roster.SERIALIZER.read(null, future, RegistryAccess.EMPTY);
        assertTrue(r.isQuarantined(), "an unreadable tag must be quarantined, got " + r);

        // The whole point: what gets written back is the original tag, not an empty book.
        var written = Roster.SERIALIZER.write(r, RegistryAccess.EMPTY);
        assertEquals(future, written);
    }

    /**
     * A quarantined roster must not look like an empty fresh one. Reporting "version 1, no
     * characters" is exactly what a new player looks like, so a caller that forgets to check
     * isQuarantined() would silently believe an empty book.
     */
    @Test void everyDataAccessorRefusesAQuarantinedRoster() {
        var r = Roster.quarantined(new CompoundTag());
        assertTrue(r.isQuarantined());
        assertThrows(IllegalStateException.class, r::entries);
        assertThrows(IllegalStateException.class, r::dataVersion);
        assertThrows(IllegalStateException.class, () -> r.progressOf(InternalIds.id("djeeta")));
    }

    /**
     * The 2-arg constructor is the natural way to write a mutator, and it produces an unquarantined
     * roster. Round-tripping a quarantined roster through it must not be possible — that is the
     * "write {dataVersion:1, entries:{partial}} over a version-99 tag" destruction.
     */
    @Test void aQuarantinedRosterCannotBeLaunderedIntoALiveOneViaItsAccessors() {
        var r = Roster.quarantined(new CompoundTag());
        // The tempting mutator spelling: copy the two components into a fresh roster.
        assertThrows(IllegalStateException.class,
                () -> new Roster(r.entries(), r.dataVersion()));
    }

    /** The live path is unaffected: the 2-arg constructor still works for ordinary rosters. */
    @Test void theTwoArgConstructorStillBuildsALiveRoster() {
        var r = new Roster(Map.of(InternalIds.id("djeeta"), new CharacterProgress(3, 4, 5)), 1);
        assertFalse(r.isQuarantined());
        assertEquals(1, r.entries().size());
        assertEquals(1, r.dataVersion());
    }

    /** A readable tag is unaffected: quarantine is never applied to a version this build knows. */
    @Test void aKnownVersionIsNotQuarantined() {
        var r = new Roster(Map.of(InternalIds.id("djeeta"), new CharacterProgress(7, 12, 2)), 1);
        var tag = Roster.SERIALIZER.write(r, RegistryAccess.EMPTY);
        var back = Roster.SERIALIZER.read(null, tag, RegistryAccess.EMPTY);
        assertFalse(back.isQuarantined());
        assertEquals(r, back);
    }

    /** Even gameplay writes during a downgraded session must not overwrite the newer book. */
    @Test void aQuarantinedRosterKeepsWritingTheOriginalEvenAfterAddOrIgnore() {
        var original = new CompoundTag();
        original.putInt("dataVersion", 99);
        original.putString("futureField", "keep me");

        var r = Roster.quarantined(original);
        var touched = r.addOrIgnore(InternalIds.id("djeeta"), new CharacterProgress(1, 0, 0));
        assertTrue(touched.isQuarantined());
        assertEquals(original, Roster.SERIALIZER.write(touched, RegistryAccess.EMPTY));
    }
}
