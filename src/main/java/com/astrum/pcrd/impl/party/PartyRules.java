package com.astrum.pcrd.impl.party;

import com.astrum.pcrd.impl.attach.Party;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * "May this character go into this party slot?", and "what is the party afterwards" — as pure
 * decisions, in <b>one</b> place.
 *
 * <h2>Why one place, and what it replaces</h2>
 * The party is now edited from two directions: {@code /astrum party set|clear} (an operator tool) and
 * the 角色册 screen's payload (every player). Spec §6.3.1 requires those two to be <em>the same
 * rules</em>, and this project has already paid for duplicated rules three times — two copies of
 * "may I cast" (later collapsed into {@code CastGate}), two copies of the refusal window, two copies
 * of the rank scan. The failure is always identical: the copy nobody updated becomes the live one.
 * So the checks are a pure function over {@link Facts} (exactly the shape {@code CastGate} proved
 * works), the edit itself is a pure function over {@link Party}, and both callers go through
 * {@link PartyService}, which is the only code that resolves a player, a registry and a roster.
 *
 * <h2>Why the model, not a new rule, prevents two slots holding one character</h2>
 * {@link Party#with} already <b>moves</b>: placing a character that sits in another slot empties that
 * slot first. So "no duplicate" is not a refusal this class could add — a duplicate is unreachable by
 * construction, and a second copy of the rule here could only disagree with the data layer.
 * {@link #place} therefore reports <em>where the character came from</em> ({@link Edit#previousSlot()})
 * so the caller can say "moved out of slot 3" instead of "placed in slot 4", and the UI can show the
 * same fact. The one thing worth pinning is that it is a move: {@code PartyRulesTest} asserts the
 * character ends up in exactly one slot, whatever slot it started in.
 *
 * <h2>Order of the checks</h2>
 * Fixed, so two callers asking at the same moment agree: book readable, slot range, registry loaded,
 * character known, character owned. A quarantined book comes first because every other question about
 * it is unanswerable — {@code Roster.entries()} throws while quarantined — and refusing the write is
 * the only honest answer (spec §6.3.1: the screen shows 存档读取失败 and the server refuses writes).
 */
@ApiStatus.Internal
public final class PartyRules {

    /**
     * Why a party edit was refused. Each value has a translation under
     * {@code astrum.party.reason.<NAME>} through {@link #reasonKey}, so a new value cannot arrive
     * without a line for the player: {@code LangParityTest} derives the keys from this enum.
     */
    public enum Reason {
        /** The player's book is a save this build cannot read; nothing may be written. */
        BOOK_UNREADABLE,
        /** The slot is outside the five the party has. */
        SLOT_OUT_OF_RANGE,
        /** No character data is loaded (the registry is only bound while a world is loaded). */
        NO_REGISTRY,
        /** The slot names a character the loaded pack does not define. */
        CHARACTER_UNKNOWN,
        /** The character exists but is not in this player's book. */
        NOT_OWNED
    }

    /**
     * Everything the decision needs, already resolved by the caller.
     *
     * <p>Booleans rather than a {@code Roster} and a {@code Registry}: the caller resolves both once
     * (and knows how to do it without tripping a quarantine exception), and this record cannot
     * accidentally index a roster it has not read.
     *
     * @param slot          0-based party slot, expected in {@code [0, Party.SLOTS)}
     * @param bookReadable  whether the roster is readable (not quarantined)
     * @param registryLoaded whether {@code AstrumApi.characters()} is present
     * @param characterKnown whether the pack defines the requested character; {@code true} when the
     *                       request is a clear (no character named)
     * @param owned         whether the player's book holds the character; {@code true} for a clear
     */
    public record Facts(int slot, boolean bookReadable, boolean registryLoaded,
                        boolean characterKnown, boolean owned) {
    }

    /**
     * One party edit, as data: what the party was, what it is, and which slot the character left.
     *
     * @param before       the party before the edit
     * @param after        the party after the edit
     * @param slot         the slot the caller targeted
     * @param characterId  the character placed, or {@code null} when the slot was cleared
     * @param previousSlot where the character sat before, empty when it was not in the party
     */
    public record Edit(Party before, Party after, int slot, @Nullable ResourceLocation characterId,
                       OptionalInt previousSlot) {

        /** {@return whether this edit actually changed the party} */
        public boolean changed() {
            return !before.equals(after);
        }

        /** {@return whether the character came out of another slot, i.e. this was a move} */
        public boolean moved() {
            return previousSlot.isPresent() && previousSlot.getAsInt() != slot;
        }

        /** {@return whether this edit cleared a slot that held something} */
        public boolean cleared() {
            return characterId == null && changed();
        }
    }

    private PartyRules() {
    }

    /**
     * {@return the translation key for a refusal}
     *
     * <p>Derived from the enum name, so a rename or a new value cannot leave a stale key behind. Used
     * by the book screen and required to exist by {@code LangParityTest}.
     *
     * @param reason the refusal to name
     */
    public static String reasonKey(Reason reason) {
        return "astrum.party.reason." + reason.name();
    }

    /**
     * {@return an empty optional when the edit may proceed, or the first problem found}
     *
     * @param facts the resolved inputs
     */
    public static Optional<Reason> refusal(Facts facts) {
        Objects.requireNonNull(facts, "facts");
        if (!facts.bookReadable()) {
            return Optional.of(Reason.BOOK_UNREADABLE);
        }
        if (facts.slot() < 0 || facts.slot() >= Party.SLOTS) {
            return Optional.of(Reason.SLOT_OUT_OF_RANGE);
        }
        if (!facts.registryLoaded()) {
            return Optional.of(Reason.NO_REGISTRY);
        }
        if (!facts.characterKnown()) {
            return Optional.of(Reason.CHARACTER_UNKNOWN);
        }
        if (!facts.owned()) {
            return Optional.of(Reason.NOT_OWNED);
        }
        return Optional.empty();
    }

    /**
     * {@return the slot {@code characterId} currently occupies, or empty if it is not in the party}
     *
     * <p>The single implementation of "where is this character": {@code /astrum} used to carry its own
     * copy ({@code indexOfSlot}) to decide between "placed" and "moved", and the UI needs the same
     * answer to draw the 编队 checkmark. Two scans would eventually disagree about a hand-edited save.
     *
     * @param party       the party to search
     * @param characterId the character to look for
     */
    public static OptionalInt slotOf(Party party, ResourceLocation characterId) {
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(characterId, "characterId");
        for (int i = 0; i < Party.SLOTS; i++) {
            if (party.slots().get(i).filter(characterId::equals).isPresent()) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * {@return the edit that puts {@code characterId} in {@code slot}, or clears the slot when null}
     *
     * <p>A <b>move</b>, never a copy: {@link Party#with} empties the character's old slot, which is the
     * behaviour the screen promises ("已在别槽的角色自动移出旧槽"). The destination may be occupied —
     * replacing a slot's occupant is the ordinary way to build a party — so there is no "slot taken"
     * refusal; the displaced character simply ends up out of the party.
     *
     * <p>No range check here: {@link #refusal} is the gate, and {@link Party#with} throws on an illegal
     * slot, which is a programming error at this point rather than player input.
     *
     * @param party       the party before the edit
     * @param slot        a slot in {@code [0, Party.SLOTS)}
     * @param characterId the character to place, or {@code null} to clear the slot
     */
    public static Edit place(Party party, int slot, @Nullable ResourceLocation characterId) {
        Objects.requireNonNull(party, "party");
        if (slot < 0 || slot >= Party.SLOTS) {
            throw new IllegalArgumentException(
                    "astrum: slot " + slot + " is outside [0, " + Party.SLOTS + "); check refusal() first");
        }
        OptionalInt previous = characterId == null ? OptionalInt.empty() : slotOf(party, characterId);
        return new Edit(party, party.with(slot, characterId), slot, characterId, previous);
    }
}
