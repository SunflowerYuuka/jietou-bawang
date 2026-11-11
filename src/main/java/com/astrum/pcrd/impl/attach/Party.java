package com.astrum.pcrd.impl.attach;

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
 * is never compacted away, because the index is the wheel position. A <b>malformed</b> entry is an
 * error, not an empty slot: turning a typo into "empty" would quietly unequip a character and then
 * save that opinion back over the player's data. Duplicates collapse keeping the earliest slot,
 * because the earliest slot is the one with the higher priority.
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
     * Copies the list, freezes it, and refuses a list that is not exactly {@link #SLOTS} long.
     *
     * <p>The length check lives on the record rather than only in {@link #of}, so no constructor
     * route can produce a four-slot party; a shorter list is only ever a caller's input to
     * {@link #of} or {@link #ofSlots}, both of which pad before calling this.
     */
    public Party {
        if (slots.size() != SLOTS) {
            throw new IllegalArgumentException(
                    "party must have exactly " + SLOTS + " slots, got " + slots.size());
        }
        List<Optional<ResourceLocation>> copy = new ArrayList<>(SLOTS);
        for (Optional<ResourceLocation> slot : slots) {
            copy.add(Objects.requireNonNull(slot, "slot"));
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
     * <p>Duplicates keep their earliest slot and later occurrences become empty, matching
     * {@link #with}. A list shorter than {@link #SLOTS} is padded at the end and a longer one is
     * truncated, so a hand-edited file still yields a valid party.
     *
     * @param values per-slot characters, index = slot; may be shorter or longer than {@link #SLOTS}
     */
    public static Party ofSlots(List<Optional<ResourceLocation>> values) {
        List<Optional<ResourceLocation>> slots = new ArrayList<>(SLOTS);
        Set<ResourceLocation> seen = new LinkedHashSet<>();
        for (int i = 0; i < SLOTS; i++) {
            Optional<ResourceLocation> value = i < values.size() ? values.get(i) : Optional.empty();
            if (value == null || value.isEmpty()) {
                slots.add(Optional.empty());
                continue;
            }
            slots.add(seen.add(value.get()) ? value : Optional.empty());
        }
        return new Party(slots);
    }

    /**
     * Decodes the slot strings, reporting a malformed one as a codec error.
     *
     * <p>{@link ResourceLocation#tryParse} returns {@code null} on garbage instead of throwing, and
     * this is where that becomes a {@code DataResult} error rather than a crash or a silent empty
     * slot. The whole list fails: dropping just the bad entry would save a party the player never
     * configured.
     *
     * <p>An empty string means "this slot is unused", and it stays at its index: the slots are
     * converted positionally through {@link #ofSlots}, never compacted.
     */
    private static DataResult<Party> decodeSlots(List<String> strings) {
        List<Optional<ResourceLocation>> slots = new ArrayList<>(SLOTS);
        for (String raw : strings) {
            if (raw == null || raw.isEmpty()) {
                slots.add(Optional.empty());
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(raw);
            if (id == null) {
                return DataResult.error(() -> "astrum: not a valid character id in party slot: '"
                        + raw + "'");
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
