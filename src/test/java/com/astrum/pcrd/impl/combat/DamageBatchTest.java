package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.InternalIds;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
