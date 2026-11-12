package com.astrum.pcrd.impl.combat;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-target stack table: one entry per (attacker, stack kind), expired lazily (spec §4.4).
 *
 * <p>Two properties are the whole point and the reason this is tested without a game:
 * <ul>
 *   <li><b>per-attacker isolation</b> — two players hitting one target keep separate stacks, so a
 *       shared target cannot be farmed by someone else's hits;</li>
 *   <li><b>lazy expiry</b> — nothing here is on a timer. An entry changes state only when it is
 *       looked up, and a table that is never touched again costs nothing but the memory it sits in.</li>
 * </ul>
 */
class StackTableTest {

    private static final ResourceLocation POISON = ResourceLocation.fromNamespaceAndPath("astrum", "poison");
    private static final ResourceLocation BLEED = ResourceLocation.fromNamespaceAndPath("astrum", "bleed");

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void eachAttackerKeepsItsOwnStacksOnOneTarget() {
        var table = new StackTable();
        assertEquals(1, table.hit(new StackKey(alice, POISON), 100, 1, 5, 80).stacks());
        assertEquals(2, table.hit(new StackKey(alice, POISON), 101, 1, 5, 80).stacks());
        assertEquals(1, table.hit(new StackKey(bob, POISON), 102, 1, 5, 80).stacks(),
                "bob's first hit must not inherit alice's two stacks");
        assertEquals(3, table.hit(new StackKey(alice, POISON), 103, 1, 5, 80).stacks());
        assertEquals(2, table.hit(new StackKey(bob, POISON), 104, 1, 5, 80).stacks(),
                "bob counts his own two hits, not alice's three");
    }

    @Test
    void oneAttackerKeepsEachStackKindSeparate() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 1, 5, 80);
        table.hit(new StackKey(alice, POISON), 101, 1, 5, 80);
        assertEquals(1, table.hit(new StackKey(alice, BLEED), 102, 1, 5, 80).stacks());
        assertEquals(2, table.read(new StackKey(alice, POISON), 103, 80).stacks());
    }

    /** The plan's lazy-expiry rule, on the table: the age is judged at the lookup, not by a timer. */
    @Test
    void anIdleStackExpiresOnlyWhenItIsNextLookedAt() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 3, 5, 80);
        assertEquals(3, table.read(new StackKey(alice, POISON), 179, 80).stacks(), "still alive at +79");
        assertEquals(0, table.read(new StackKey(alice, POISON), 180, 80).stacks(), "gone at +80");
        assertEquals(0, table.size(), "an expired entry is dropped at the lookup that found it");
    }

    @Test
    void aHitAfterExpiryStartsOverInsteadOfContinuing() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 4, 5, 80);
        assertEquals(1, table.hit(new StackKey(alice, POISON), 200, 1, 5, 80).stacks());
    }

    @Test
    void aHitJustInsideTheTtlKeepsAccumulating() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 1, 5, 80);
        assertEquals(2, table.hit(new StackKey(alice, POISON), 179, 1, 5, 80).stacks());
    }

    @Test
    void maxCapsTheCountAndTheTickIsRecorded() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 4, 5, 80);
        assertEquals(5, table.hit(new StackKey(alice, POISON), 120, 3, 5, 80).stacks());
        assertEquals(120, table.read(new StackKey(alice, POISON), 121, 80).lastHitTick());
    }

    /** A detonation consumes the stacks; the next read sees an empty (not a negative) stack. */
    @Test
    void drainingRemovesTheStacksAndIsIdempotent() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 3, 5, 80);
        assertEquals(3, table.drain(new StackKey(alice, POISON), 110, 80));
        assertEquals(0, table.drain(new StackKey(alice, POISON), 111, 80));
        assertEquals(0, table.read(new StackKey(alice, POISON), 112, 80).stacks());
    }

    @Test
    void drainingAnExpiredStackYieldsNothing() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 3, 5, 80);
        assertEquals(0, table.drain(new StackKey(alice, POISON), 500, 80));
    }

    /**
     * A table on a long-lived entity cannot grow without bound just because nobody reads it: keys
     * nobody has touched for a whole ttl are dropped before a new key is added, and past that the
     * least recently hit key goes. Nothing sweeps — this happens on the insert that would have
     * overflowed.
     */
    @Test
    void anUnreadTableStaysBounded() {
        var table = new StackTable();
        for (int i = 0; i < StackTable.MAX_ENTRIES * 4; i++) {
            table.hit(new StackKey(UUID.randomUUID(), POISON), 100 + i, 1, 5, 80);
        }
        assertTrue(table.size() <= StackTable.MAX_ENTRIES, "size=" + table.size());
    }

    @Test
    void expiryOfOneKeyDoesNotTouchAnother() {
        var table = new StackTable();
        table.hit(new StackKey(alice, POISON), 100, 2, 5, 80);
        table.hit(new StackKey(bob, POISON), 170, 2, 5, 80);
        assertEquals(0, table.read(new StackKey(alice, POISON), 200, 80).stacks());
        assertEquals(2, table.read(new StackKey(bob, POISON), 200, 80).stacks());
    }

    @Test
    void anEmptyTableReadsAsEmptyRatherThanNull() {
        var table = new StackTable();
        StackState state = table.read(new StackKey(alice, POISON), 0, 80);
        assertTrue(state.isEmpty());
        assertFalse(state.stacks() > 0);
        assertEquals(StackState.EMPTY, state);
    }
}
