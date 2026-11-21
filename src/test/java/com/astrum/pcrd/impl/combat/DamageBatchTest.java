package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.InternalIds;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageBatchTest {

    static final ResourceLocation HOOK = InternalIds.id("hook_damage");
    static final ResourceLocation MAGIC = ResourceLocation.parse("minecraft:magic");

    /** Spec §5.1 rule 3: N hooks of the same type are ONE entry, so the flush is one hurt(). */
    @Test void mergesSameDamageTypeIntoSingleEntry() {
        var batch = new DamageBatch();
        batch.add(HOOK, 2.0f);
        batch.add(HOOK, 3.5f);
        assertEquals(1, batch.entries().size());
        assertEquals(5.5f, batch.entries().get(0).amount(), 1e-6);
    }

    @Test void keepsDistinctTypesSeparateAndOrdered() {
        var batch = new DamageBatch();
        batch.add(HOOK, 1f);
        batch.add(MAGIC, 2f);
        assertEquals(2, batch.entries().size());
        assertEquals(HOOK, batch.entries().get(0).type());
        assertEquals(MAGIC, batch.entries().get(1).type());
    }

    @Test void emptyBatchHasNoEntries() {
        assertTrue(new DamageBatch().entries().isEmpty());
    }

    /**
     * A type merges into the position where it was <b>first</b> seen, not the position of its last
     * contribution: the order has to be stable while hooks are still accumulating.
     */
    @Test void mergeKeepsFirstInsertionPosition() {
        var batch = new DamageBatch();
        batch.add(HOOK, 1f);
        batch.add(MAGIC, 2f);
        batch.add(HOOK, 3f);
        assertEquals(List.of(HOOK, MAGIC), batch.entries().stream().map(DamageBatch.Entry::type).toList());
        assertEquals(4f, batch.entries().get(0).amount(), 1e-6);
    }

    /** The flush iterates what it was handed; a later {@code add} must not rewrite that list. */
    @Test void entriesIsASnapshot() {
        var batch = new DamageBatch();
        batch.add(HOOK, 1f);
        List<DamageBatch.Entry> snapshot = batch.entries();
        batch.add(HOOK, 1f);
        assertEquals(1f, snapshot.get(0).amount(), 1e-6);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new DamageBatch.Entry(HOOK, 1f)));
    }

    // ------------------------------------------------------------------ the per-skill crit multiplier

    /**
     * <b>An amount carries the crit multiplier of the skill that authored it.</b>
     *
     * <p>The entry keeps it because the flush happens at the end of the tick, after every skill that fired,
     * and {@code Crit.factorFor} needs the number of the skill <em>this</em> amount came from. The untagged
     * amount keeps {@code empty}, which means the caster's global {@code astrum:crit_damage} — every hook
     * that does not declare one (task 55's default).
     */
    @Test void anEntryCarriesItsSkillsCritMultiplier() {
        var batch = new DamageBatch();
        batch.add(HOOK, 1f);
        batch.tagged(OptionalDouble.of(4.0)).add(HOOK, 2f);

        List<DamageBatch.Entry> entries = batch.entries();
        assertEquals(2, entries.size(), "different multipliers are different entries");
        assertTrue(entries.get(0).critMultiplier().isEmpty(), "the untagged amount uses the global");
        assertEquals(4.0, entries.get(1).critMultiplier().orElseThrow(), 1e-9);
        assertEquals(2f, entries.get(1).amount(), 1e-6);
    }

    /**
     * <b>Same type and same multiplier still merge into one entry</b> — spec §5.1 rule 3 survives the new key.
     *
     * <p>A skill fires several hooks and they share its multiplier, so the one-{@code hurt()}-per-type rule
     * is untouched for every real pack. Only two <em>different</em> skills with different multipliers filing
     * one type in one tick split, and two hits is the honest answer there: merging would apply one skill's
     * number to the other's damage.
     */
    @Test void sameTypeAndSameTagMerge() {
        var batch = new DamageBatch();
        var tagged = batch.tagged(OptionalDouble.of(4.0));
        tagged.add(HOOK, 1f);
        tagged.add(HOOK, 2.5f);
        assertEquals(1, batch.entries().size());
        assertEquals(3.5f, batch.entries().get(0).amount(), 1e-6);
        assertEquals(4.0, batch.entries().get(0).critMultiplier().orElseThrow(), 1e-9);
    }

    /** An absent tag is the batch itself: the common case allocates no view and no second entry. */
    @Test void anEmptyTagIsTheSameBatch() {
        var batch = new DamageBatch();
        assertSame(batch, batch.tagged(OptionalDouble.empty()));
        assertSame(batch, batch.tagged(null));
    }

    /** A tagged view shares the hit's totals, so entries joined it rather than living beside it. */
    @Test void aTaggedViewWritesIntoTheSameBatch() {
        var batch = new DamageBatch();
        var tagged = batch.tagged(OptionalDouble.of(3.5));
        tagged.add(HOOK, 1f);
        assertEquals(1, batch.entries().size(), "the original batch sees the tagged amount");
        assertEquals(3.5, batch.entries().get(0).critMultiplier().orElseThrow(), 1e-9);
    }
}
