package com.astrum.pcrd.impl.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
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
 * <h2>The second key: a skill's crit multiplier</h2>
 * Since task 55 an amount can carry the crit multiplier of the skill that authored it
 * ({@code EffectHook#critMultiplier}), because 「本技能暴击 4 倍」 is a property of the amount and not of
 * the type. Two amounts that must crit differently therefore <b>cannot</b> be merged into one entry: the
 * flush multiplies an entry by its own factor, so merging would force one skill's number onto the other's
 * damage. The entry key is {@code (damage type, crit multiplier)} — the same type still merges into
 * <b>one</b> {@code hurt()} while the multiplier is the same (which is every hook of one skill, and every
 * hook in a pack that never uses the channel), and splits into two only when two different skills with two
 * different multipliers happen to file damage under one type in one tick. Shipped data never does; if it
 * ever does, two {@code hurt()} calls are the honest answer rather than one hit wearing the wrong number.
 *
 * <h2>What this class is not</h2>
 * It decides nothing: no multiplier, no chance roll, no config, no "should this be flushed". Every one of
 * those is the caller's, so the accumulator can be tested without a game and the ordering rule — the part
 * that is easy to get wrong — is pinned by {@code DamageBatchTest}.
 *
 * <h2>Types</h2>
 * Only {@link ResourceLocation}, {@code float} and {@link OptionalDouble}: no {@code DamageSource}, no
 * {@code Entity}, no {@code Level}. A batch is a value, and this is the same purity rule the other kernels
 * follow ({@code PartyMultipliers}, {@code ChargeKernel}). The caller resolves a type id into whatever
 * registry object it needs at the point of the {@code hurt()} call.
 *
 * <h2>Insertion order, not sorted order</h2>
 * Entries keep the order in which their key was <b>first</b> added. Iteration order is therefore the wheel
 * order the hooks were evaluated in, which is what makes a flush reproducible and a log line readable. A
 * {@code HashMap} would reshuffle it, and sorting by id would move the primary damage type to an arbitrary
 * position.
 */
@ApiStatus.Internal
public final class DamageBatch {

    /**
     * One damage type, the total accumulated for it, and the crit multiplier its skill authored.
     *
     * <p>{@code critMultiplier} is empty for "use the caster's global {@code astrum:crit_damage}", which is
     * every amount queued before task 55 and every amount whose hook declares no value.
     */
    public record Entry(ResourceLocation type, float amount, OptionalDouble critMultiplier) {

        public Entry {
            critMultiplier = critMultiplier == null ? OptionalDouble.empty() : critMultiplier;
        }

        /** The pre-{@code critMultiplier} shape: an amount that crits at the caster's global multiplier. */
        public Entry(ResourceLocation type, float amount) {
            this(type, amount, OptionalDouble.empty());
        }
    }

    /** The merge key: a type merges only with the same type <b>and</b> the same crit multiplier. */
    private record Key(ResourceLocation type, OptionalDouble critMultiplier) {
    }

    /** Key to running total, in first-insertion order. A {@code tagged} view shares this map. */
    private final Map<Key, Float> totals;

    /** The multiplier every {@link #add} of this view is filed under; empty for the hit's own batch. */
    private final OptionalDouble tag;

    public DamageBatch() {
        this(new LinkedHashMap<>(), OptionalDouble.empty());
    }

    private DamageBatch(Map<Key, Float> totals, OptionalDouble tag) {
        this.totals = totals;
        this.tag = tag == null ? OptionalDouble.empty() : tag;
    }

    /**
     * {@return a view of this batch that files every further {@link #add} under {@code critMultiplier}}
     *
     * <h2>Why a view rather than a parameter on {@code add}</h2>
     * The effect executors call {@code ctx.batch().add(type, amount)} for the damage they author; they do
     * not know, and should not have to know, which crit multiplier the skill being executed carries. The
     * hook is the one thing the caller of an executor <em>does</em> know, so {@code HookContext#forHook}
     * hands the executor a batch already tagged with that hook's value. One executor signature unchanged,
     * one place that knows the rule.
     *
     * <p>An empty (or equal) tag answers {@code this}, so the common case allocates nothing and the hit's
     * own batch is never wrapped for nothing.
     *
     * @param critMultiplier the skill's own multiplier; empty for the caster's global
     */
    public DamageBatch tagged(OptionalDouble critMultiplier) {
        OptionalDouble normalized = critMultiplier == null ? OptionalDouble.empty() : critMultiplier;
        if (normalized.equals(tag)) {
            return this;
        }
        return new DamageBatch(totals, normalized);
    }

    /**
     * Adds {@code amount} of {@code type}, merging into the existing total for this type and multiplier.
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
        totals.merge(new Key(type, tag), amount, Float::sum);
    }

    /**
     * {@return an immutable snapshot of the accumulated totals, in first-insertion order}
     *
     * <p>A snapshot, not a live view: a flush that is iterating entries while a later hook adds to
     * the same batch must not see the list change underneath it.
     */
    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(totals.size());
        totals.forEach((key, amount) -> entries.add(new Entry(key.type(), amount, key.critMultiplier())));
        return List.copyOf(entries);
    }

    /** {@return {@code true} when nothing has been added} */
    public boolean isEmpty() {
        return totals.isEmpty();
    }
}