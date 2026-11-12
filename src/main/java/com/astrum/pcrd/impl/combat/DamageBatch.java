package com.astrum.pcrd.impl.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The same-tick damage accumulator for one target (spec §5.1 rule 3).
 *
 * <h2>Why a batch at all</h2>
 * A five-slot wheel can put five {@code extra_damage} hooks on one swing. Applying them as five
 * {@code hurt()} calls costs five damage sequences, five sets of armour/effect maths and five
 * knockbacks — and, worse, five chances for a re-entrant hit. Merging by damage type makes the
 * flush exactly one {@code hurt()} per type, which is both the performance measure and the
 * recursion fuse the spec asks for.
 *
 * <h2>What this class is not</h2>
 * It decides nothing: no multiplier, no chance roll, no config, no "should this be flushed".
 * Every one of those is the caller's, so the accumulator can be tested without a game and the
 * ordering rule — the part that is easy to get wrong — is pinned by {@code DamageBatchTest}.
 *
 * <h2>Types</h2>
 * Only {@link ResourceLocation} and {@code float}: no {@code DamageSource}, no {@code Entity}, no
 * {@code Level}. A batch is a value, and this is the same purity rule the other kernels follow
 * ({@code PartyMultipliers}, {@code ChargeKernel}). The caller resolves a type id into whatever
 * registry object it needs at the point of the {@code hurt()} call.
 *
 * <h2>Insertion order, not sorted order</h2>
 * Entries keep the order in which their type was <b>first</b> added. Iteration order is therefore
 * the wheel order the hooks were evaluated in, which is what makes a flush reproducible and a log
 * line readable. A {@code HashMap} would reshuffle it, and sorting by id would move the primary
 * damage type to an arbitrary position.
 */
@ApiStatus.Internal
public final class DamageBatch {

    /** One damage type and the total amount accumulated for it. */
    public record Entry(ResourceLocation type, float amount) {
    }

    /** Type to running total, in first-insertion order. */
    private final Map<ResourceLocation, Float> totals = new LinkedHashMap<>();

    /**
     * Adds {@code amount} of {@code type}, merging into the existing total for that type.
     *
     * <p>Non-positive amounts are accepted rather than filtered: this is an accumulator, not a
     * policy, and silently dropping a value would make the total disagree with what the caller
     * computed. The flush site is where a zero or negative total is declined, because that is
     * where it costs a damage sequence.
     *
     * @param type   the damage type this amount belongs to
     * @param amount damage to add; may be any finite value
     */
    public void add(ResourceLocation type, float amount) {
        Objects.requireNonNull(type, "type");
        totals.merge(type, amount, Float::sum);
    }

    /**
     * {@return an immutable snapshot of the accumulated totals, in first-insertion order}
     *
     * <p>A snapshot, not a live view: a flush that is iterating entries while a later hook adds to
     * the same batch must not see the list change underneath it.
     */
    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(totals.size());
        totals.forEach((type, amount) -> entries.add(new Entry(type, amount)));
        return List.copyOf(entries);
    }

    /** {@return {@code true} when nothing has been added} */
    public boolean isEmpty() {
        return totals.isEmpty();
    }
}
