package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.RosterSyncPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>Which character the book shows when it opens</b> — the human's 「默认使用角色1当背景」.
 *
 * <h2>The rule, in the order it is applied</h2>
 * <ol>
 *   <li><b>party slot 1</b> (index 0), so the book opens on the character the player's first cast would use;</li>
 *   <li>otherwise the <b>first character in the roster</b>, so a player who has not made a party yet still sees a
 *       face rather than a blank panel;</li>
 *   <li>otherwise <b>nothing</b>, and the panel keeps its plain dark body.</li>
 * </ol>
 *
 * <h2>Why each rung is asserted separately</h2>
 * All three answer "a character" or "no character", so a broken chain still looks plausible: a default that
 * always took the roster's head would pass a test that only checked "something is selected" on a player with one
 * character, and it would be wrong for the player whose slot 1 is filled out of roster order — which is the normal
 * case after any party edit. So the fallback is proved by <em>withholding</em> each rung in turn.
 *
 * <p>Pure by construction: the two inputs are the shapes {@code ClientPartyCache} and {@code ClientRosterCache}
 * already hold, so this runs with no client, no registry and no world.
 */
class DefaultSelectionTest {

    private static final ResourceLocation SLOT_ONE = id("slot_one");
    private static final ResourceLocation SLOT_TWO = id("slot_two");
    private static final ResourceLocation ROSTER_HEAD = id("roster_head");
    private static final ResourceLocation ROSTER_SECOND = id("roster_second");

    /** Rung one: party slot 1 wins, even when it is not the roster's first character. */
    @Test
    void partySlotOneWins() {
        Optional<ResourceLocation> chosen = TierAvatar.defaultSelection(
                party(SLOT_TWO, SLOT_ONE), roster(ROSTER_HEAD, ROSTER_SECOND));

        assertEquals(Optional.of(SLOT_TWO), chosen,
                "「默认使用角色1当背景」: slot 1, not the roster's head and not slot 2");
    }

    /** Rung two: an empty slot 1 falls back to the roster's head, in roster order. */
    @Test
    void anEmptyFirstSlotFallsBackToTheRosterHead() {
        assertEquals(Optional.of(ROSTER_HEAD),
                TierAvatar.defaultSelection(party(null, SLOT_TWO), roster(ROSTER_HEAD, ROSTER_SECOND)),
                "the roster's order is the order they were enrolled, and the grid draws it first");
    }

    /** Rung three: no party and no roster is "nothing", not an invented character. */
    @Test
    void anEmptyBookSelectsNobody() {
        assertEquals(Optional.empty(), TierAvatar.defaultSelection(party(), roster()));
        assertEquals(Optional.empty(), TierAvatar.defaultSelection(party(null, null), roster()),
                "an empty party with an empty roster has nobody to show");
    }

    /**
     * Every degenerate input answers "nothing" rather than throwing.
     *
     * <p>These run on the screen's open path, where an exception costs the player the book.
     */
    @Test
    void hostileInputAnswersNothing() {
        assertEquals(Optional.empty(), TierAvatar.defaultSelection(null, null));
        assertEquals(Optional.empty(), TierAvatar.defaultSelection(null, roster()));
        assertEquals(Optional.empty(), TierAvatar.defaultSelection(party(), null));
        assertEquals(Optional.of(ROSTER_HEAD), TierAvatar.defaultSelection(null, roster(ROSTER_HEAD)),
                "a null party must still reach the roster rung");
        assertEquals(Optional.empty(), TierAvatar.defaultSelection(
                        Arrays.asList((Optional<ResourceLocation>) null), roster()),
                "a null first slot is 'empty', not a crash");
    }

    /** The selected id is a character id, not a texture: it is what the growth view looks up too. */
    @Test
    void theSelectionIsACharacterIdNotATexture() {
        Optional<ResourceLocation> chosen =
                TierAvatar.defaultSelection(party(SLOT_ONE), roster());
        assertEquals(Optional.of(SLOT_ONE), chosen);
        assertEquals("astrum", chosen.orElseThrow().getNamespace());
        assertEquals("slot_one", chosen.orElseThrow().getPath());
    }

    /** The unsynced tier is the first rung: a character with no progress row is drawn at 1★. */
    @Test
    void theUnsyncedTierIsTheFirstRung() {
        assertEquals(1, TierAvatar.UNSYNCED_TIER,
                "a character the client has no row for is drawn at the only tier nobody earned");
    }

    // -----------------------------------------------------------------------------------------
    // Builders mirroring the two caches' shapes
    // -----------------------------------------------------------------------------------------

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("astrum", path);
    }

    /** {@return a five-slot party, filled in order; {@code null} means an empty slot} */
    private static List<Optional<ResourceLocation>> party(ResourceLocation... filled) {
        List<Optional<ResourceLocation>> slots = new ArrayList<>();
        for (ResourceLocation character : filled) {
            slots.add(Optional.ofNullable(character));
        }
        while (slots.size() < 5) {
            slots.add(Optional.empty());
        }
        return slots;
    }

    /** {@return roster rows in the order given, with placeholder numbers} */
    private static List<RosterSyncPayload.Entry> roster(ResourceLocation... characters) {
        List<RosterSyncPayload.Entry> entries = new ArrayList<>();
        for (ResourceLocation character : characters) {
            entries.add(new RosterSyncPayload.Entry(character, 1, 0, 100, 1, 1));
        }
        return entries;
    }
}
