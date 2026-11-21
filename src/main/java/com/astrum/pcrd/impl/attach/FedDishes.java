package com.astrum.pcrd.impl.attach;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The day each dish was last fed, saved with the player.</b>
 *
 * <h2>The defect this replaces</h2>
 * The 「每游戏日一次」 rule used to live in a table owned by the server-session listener
 * ({@code DishThrottle}), and that table was <b>cleared on {@code ServerStoppingEvent}</b>. So the rule was
 * really "once per game day, or once per server run, whichever comes first": a player could feed her whole
 * pantry, quit to the title screen, and feed it again — on the same world day, with no day boundary crossed.
 * A single-player session that ends is the ordinary case, not an edge case, so the allowance was mostly
 * decorative.
 *
 * <h2>Why an attachment rather than a file of its own</h2>
 * This project already persists everything per player through {@code AttachmentType} + a codec
 * ({@code Roster}, {@code Party}, {@code UbState}), and that machinery already answers the two questions a
 * separate file would reopen: <em>whose</em> data is it (the player's, so it travels with them) and <em>when</em>
 * is it written (NeoForge writes the attachment whenever the player is saved, so a crash costs at most the
 * current tick's change rather than the whole session).
 *
 * <h2>Bounded by construction, and why that matters to the old design</h2>
 * The old comment objected that a save "must not grow a row per dish per day for the lifetime of the world".
 * That objection is right, and this shape keeps it: the map is keyed by <b>dish</b> and holds the <b>last</b> day
 * only, so its size is the number of dish items that exist — a handful — no matter how many days the world
 * runs. Nothing accumulates, and no entry needs a cleanup pass: a stored day that is not today <em>is</em> the
 * expiry, which is exactly what {@link #allow} reads.
 */
@ApiStatus.Internal
public final class FedDishes {

    /** The persisted shape: dish id to the last game day it was fed, as an explicit ordered list of pairs. */
    public static final Codec<FedDishes> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // A list of pairs rather than a map codec, because a `ResourceLocation` is not a valid map key in
            // every ops this project encodes through, and because the order then makes two dumps comparable in
            // a test.
            Entry.CODEC.listOf().optionalFieldOf("lastFed", java.util.List.of()).forGetter(FedDishes::entries)
    ).apply(instance, FedDishes::fromEntries));

    /** One dish and the day it was last fed. */
    public record Entry(ResourceLocation dish, long day) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("dish").forGetter(Entry::dish),
                Codec.LONG.fieldOf("day").forGetter(Entry::day)
        ).apply(instance, Entry::new));

        public Entry {
            Objects.requireNonNull(dish, "dish");
        }
    }

    /** dish → the last day it was fed. Mutable in place; the attachment holds one instance per player. */
    private final Map<ResourceLocation, Long> lastFed = new HashMap<>();

    /** {@return an empty record, the default for a player who has never fed anything} */
    public static FedDishes empty() {
        return new FedDishes();
    }

    /** Rebuilds an instance from the persisted entries; a later duplicate for one dish wins. */
    private static FedDishes fromEntries(java.util.List<Entry> entries) {
        FedDishes dishes = new FedDishes();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry != null && entry.dish() != null) {
                    dishes.lastFed.put(entry.dish(), entry.day());
                }
            }
        }
        return dishes;
    }

    /** {@return the persisted entries, in a stable order so two dumps of one state compare equal} */
    public java.util.List<Entry> entries() {
        return lastFed.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey(java.util.Comparator.comparing(
                        ResourceLocation::toString)))
                .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * {@return whether this player may feed this dish on {@code gameDay}, remembering the answer when it is yes}
     *
     * <p>Consumes the allowance: the first call for a (dish, day) answers {@code true} and writes that day; the
     * next one for the same day answers {@code false}. A stored day that has moved on re-arms the allowance,
     * which is the whole of 「每游戏日一次」.
     *
     * <p><b>Nothing here knows about sessions.</b> That is the fix: the answer depends on the world's day and on
     * this player's saved state, and on nothing that ends when the server does.
     *
     * @param dish the dish item's id
     * @param day  the world's current day number
     */
    public boolean allow(ResourceLocation dish, long day) {
        if (dish == null) {
            return false;
        }
        Long previous = lastFed.get(dish);
        if (previous != null && previous == day) {
            return false;
        }
        lastFed.put(dish, day);
        return true;
    }

    /** {@return whether this player already fed this dish on {@code day}, without consuming anything} */
    public boolean alreadyFed(ResourceLocation dish, long day) {
        Long previous = dish == null ? null : lastFed.get(dish);
        return previous != null && previous == day;
    }

    /** {@return how many dishes this player has a record for; for the log and for tests} */
    public int size() {
        return lastFed.size();
    }

    /** Forgets every allowance for this player. Used by tests and by nothing in play. */
    public void clear() {
        lastFed.clear();
    }
}
