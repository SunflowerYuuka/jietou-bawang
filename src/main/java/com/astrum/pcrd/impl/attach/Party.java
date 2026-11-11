package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.Astrum;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The five UB wheel slots, in order.
 *
 * <p>Order is meaning, not presentation: slot 0 wins ties in the diminishing-return kernel, and the
 * same index is the wheel position (spec §4.3, §5.2). So the list is never sorted, never trimmed
 * from the front, and always exactly {@link #SLOTS} long — a shorter list would silently move every
 * character up a rank.
 *
 * <h2>Why construction goes through {@link #of} and {@link #ofSlots}</h2>
 * The two invariants — exactly five slots, and no character in two slots — are enforced in one
 * place so the later UI cannot create a state the data layer would reject. A record constructor
 * cannot express "pad to five", so the shape is a record and the entry points are {@link #of}
 * (pack characters in priority order), {@link #ofSlots} (per-slot values read off disk) and
 * {@link #empty}.
 *
 * <h2>What the codec does and does not tolerate</h2>
 * A short list (an older or hand-written file) is padded with empty slots at the end — that is a
 * lossless reading of what is there. A slot that is empty in the file stays empty at its index; it
 * is never compacted away, because the index is the wheel position. A <b>malformed</b> slot becomes
 * an empty slot <b>for that index only</b>, with a warning; the other four slots keep their
 * characters and their ranks. Failing the whole list is what an earlier version did, and it is
 * strictly more destructive: the attachment is dropped, {@link #empty()} is installed, and five
 * empty slots get saved, so one bad string destroys four readable characters. Duplicates collapse
 * keeping the earliest slot.
 *
 * @param slots exactly {@link #SLOTS} entries; an empty optional means an unused slot
 */
@ApiStatus.Internal
public record Party(List<Optional<ResourceLocation>> slots) {

    /** Slot count. Fixed by the design: five wheel positions, five diminishing ranks. */
    public static final int SLOTS = 5;

    /**
     * Disk codec: five strings, {@code ""} for an unused slot, positional through {@link #ofSlots}
     * on the way in.
     *
     * <p>Encoding always emits exactly {@link #SLOTS} elements, so a written file never depends on
     * the padding rule to stay the right length. Decoding is positional, so a character keeps the
     * slot it was saved in — the invariants hold for anything on disk, including a file a player
     * edited by hand, without reordering what is there.
     */
    public static final Codec<Party> CODEC = Codec.STRING.listOf().comapFlatMap(
            Party::decodeSlots, Party::encodeSlots);

    /**
     * Copies the list, freezes it, and enforces both invariants.
     *
     * <p>Length is checked first: a party that is not exactly {@link #SLOTS} long cannot express the
     * design at all, so there is no sensible repair. A shorter list is only ever a caller's input to
     * {@link #of} or {@link #ofSlots}, both of which pad before calling this.
     *
     * <p>Duplicates are resolved here too, not just in the factories: "a character may not occupy two
     * slots" is a data-layer rule (spec §4.3), and leaving it to the factory methods would mean any
     * later {@code new Party(...)} — a codec, a command, a UI reducer — could break it. The earliest
     * slot wins; later duplicates become empty slots. That is also what {@link #ofSlots} produces,
     * so there is exactly one dedupe rule in the class. {@link #with} is the deliberate exception:
     * it keeps the slot the caller *asked for* and empties the old one, which is a move, not a
     * dedupe — both produce a duplicate-free result.
     */
    public Party {
        if (slots.size() != SLOTS) {
            throw new IllegalArgumentException(
                    "party must have exactly " + SLOTS + " slots, got " + slots.size());
        }
        List<Optional<ResourceLocation>> copy = new ArrayList<>(SLOTS);
        Set<ResourceLocation> seen = new LinkedHashSet<>();
        for (Optional<ResourceLocation> slot : slots) {
            Optional<ResourceLocation> value = Objects.requireNonNull(slot, "slot");
            if (value.isEmpty()) {
                copy.add(Optional.empty());
            } else {
                copy.add(seen.add(value.get()) ? value : Optional.empty());
            }
        }
        slots = Collections.unmodifiableList(copy);
    }

    /** {@return a party with all five slots empty} */
    public static Party empty() {
        return new Party(Collections.nCopies(SLOTS, Optional.empty()));
    }

    /**
     * {@return a full five-slot party built from {@code characters}, in order}
     *
     * <p>Keeps the first {@link #SLOTS} distinct characters, drops later duplicates (their first
     * slot is the higher-priority one and is the one worth keeping), and pads the remainder with
     * empty slots.
     *
     * @param characters ids in priority order; may be any length, may contain duplicates
     */
    public static Party of(List<ResourceLocation> characters) {
        Set<ResourceLocation> distinct = new LinkedHashSet<>(characters);
        List<Optional<ResourceLocation>> slots = new ArrayList<>(SLOTS);
        for (ResourceLocation character : distinct) {
            if (slots.size() == SLOTS) {
                break;
            }
            slots.add(Optional.of(character));
        }
        while (slots.size() < SLOTS) {
            slots.add(Optional.empty());
        }
        return new Party(slots);
    }

    /** {@return the characters actually equipped, in slot order} */
    public List<ResourceLocation> equipped() {
        List<ResourceLocation> equipped = new ArrayList<>(SLOTS);
        for (Optional<ResourceLocation> slot : slots) {
            slot.ifPresent(equipped::add);
        }
        return List.copyOf(equipped);
    }

    /**
     * {@return this party with {@code character} placed in {@code slot}, or cleared when it is null}
     *
     * <p>Enforces the "one character, one slot" rule at the data layer: placing a character that
     * already sits in another slot empties that other slot first, so the result can never hold the
     * same character twice.
     *
     * @param slot      slot index in {@code [0, SLOTS)}
     * @param character the character to place, or {@code null} to clear the slot
     */
    public Party with(int slot, ResourceLocation character) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        List<Optional<ResourceLocation>> copy = new ArrayList<>(slots);
        if (character != null) {
            for (int i = 0; i < SLOTS; i++) {
                if (copy.get(i).filter(character::equals).isPresent()) {
                    copy.set(i, Optional.empty());
                }
            }
        }
        copy.set(slot, Optional.ofNullable(character));
        return new Party(copy);
    }

    /**
     * {@return a five-slot party from per-slot values, keeping each character where it was}
     *
     * <p>The counterpart of {@link #of} for callers that already know the slot of every character —
     * which is the case for anything read off disk. {@link #of} answers "pack these characters in
     * priority order"; this method answers "these characters sat in these slots", and the
     * difference is the whole point: slot index is wheel position and diminishing rank, so a
     * decode that re-packed would silently reorder the player's wheel every time the game loaded.
     *
     * <p>Duplicates keep their earliest slot and later occurrences become empty — the same rule the
     * canonical constructor applies, so this method never produces something the constructor would
     * then have to repair. ({@link #with} is a different operation: it is a <em>move</em>, so it
     * keeps the slot the caller asked for. Both end duplicate-free; they are not the same rule and
     * are not meant to be.)
     *
     * @param values per-slot characters, index = slot; may be shorter or longer than {@link #SLOTS}
     */
    public static Party ofSlots(List<Optional<ResourceLocation>> values) {
        List<Optional<ResourceLocation>> slots = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            Optional<ResourceLocation> value = i < values.size() ? values.get(i) : null;
            slots.add(value == null ? Optional.empty() : value);
        }
        return new Party(slots);
    }

    /**
     * Decodes the slot strings, dropping a malformed slot to empty <b>per slot</b> and warning.
     *
     * <p>{@link ResourceLocation#tryParse} returns {@code null} on garbage instead of throwing.
     *
     * <p>An earlier version failed the whole list instead, reasoning that "turning a typo into empty
     * would quietly unequip a character". That reasoning was backwards, and the failure mode is
     * strictly worse: a whole-list error makes {@code AttachmentHolder} drop the attachment, which
     * installs {@link #empty()} and then saves five empty slots — destroying four characters that
     * were perfectly readable, to punish one bad string. Per-slot repair loses at most the one slot
     * that was actually unreadable, and every other position keeps both its character and its wheel
     * rank. The loss is not silent: each dropped slot is logged.
     *
     * <p>A null or empty string is just an unused slot, and stays at its index.
     */
    private static DataResult<Party> decodeSlots(List<String> strings) {
        List<Optional<ResourceLocation>> slots = new ArrayList<>(SLOTS);
        for (int i = 0; i < strings.size(); i++) {
            String raw = strings.get(i);
            if (raw == null || raw.isEmpty()) {
                slots.add(Optional.empty());
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) {
                Astrum.LOGGER.warn("astrum: unreadable party slot {} ({}), treating it as empty; "
                        + "the other slots are kept", i, raw);
                slots.add(Optional.empty());
                continue;
            }
            slots.add(Optional.of(id));
        }
        return DataResult.success(ofSlots(slots));
    }

    /** {@return five strings, {@code ""} for an unused slot} */
    private static List<String> encodeSlots(Party party) {
        List<String> strings = new ArrayList<>(SLOTS);
        for (Optional<ResourceLocation> slot : party.slots) {
            strings.add(slot.map(ResourceLocation::toString).orElse(""));
        }
        return strings;
    }
}
