package com.astrum.pcrd.impl.ub;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The <b>resolution</b> of who a cast's burst belongs to: the victims, deduped, the caster removed,
 * and the unusable dropped.
 *
 * <h2>Why this is separate from the geometry</h2>
 * {@code UbBurstTargetsTest} proves where the points go; this file proves <em>whose</em> they are.
 * The two questions fail differently: a wrong centre is a visible bug on one body, while a wrong
 * victim set is a burst on somebody who was never hit — which is the same class of bug as the one
 * being fixed, only quieter.
 *
 * <h2>The honour system this replaces</h2>
 * {@code UbDamageSettlement} records a victim only when {@code hurt()} answered {@code true}, so the
 * input of {@link UbBurstVictims#resolve} is already "what actually landed". This test therefore
 * drives the <b>ledger</b> directly, with the same {@code record(...)} call the settlement makes —
 * which is the seam that keeps the emitter from re-deriving targets that the settlement already
 * knows better.
 */
class UbBurstVictimsTest {

    private static final UUID CASTER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000000ca");

    private static UbBurstVictims.Victim at(UUID id, double x, double z) {
        return new UbBurstVictims.Victim(id, x, 64.0, z, 1.8);
    }

    private static List<UUID> ids(List<UbBurstVictims.Victim> victims) {
        return victims.stream().map(UbBurstVictims.Victim::id).toList();
    }

    /**
     * The ledger's own promise: it records what it is given, repeats included.
     *
     * <p>Repeats are kept on purpose — "four hits on one body" is a fact the settlement has, and the
     * decision "that is one burst" belongs to {@link #resolve}.
     */
    @Test
    void theLedgerKeepsEveryLandedHitIncludingRepeats() {
        UbBurstVictims.Ledger ledger = new UbBurstVictims.Ledger();
        ledger.record(at(BOB, 4.0, 0.0));
        ledger.record(at(BOB, 4.0, 0.0));
        ledger.record(at(CAROL, 0.0, 5.0));

        assertEquals(3, ledger.landed().size());
        assertEquals(List.of(BOB, CAROL), ids(UbBurstVictims.resolve(ledger.landed(), CASTER)),
                "两下打在同一个身体上，去重之后是两个身体");
        assertFalse(ledger.isEmpty());
    }

    /** Deduped by body, in first-hit order: the burst does not multiply with the tier's hit count. */
    @Test
    void resolveKeepsOneEntryPerBodyInFirstHitOrder() {
        assertEquals(List.of(BOB, CAROL), ids(UbBurstVictims.resolve(List.of(
                at(BOB, 4.0, 0.0), at(CAROL, 0.0, 5.0), at(BOB, 4.0, 0.0)), CASTER)));
    }

    /** The caster is never a burst centre, even when the settlement reports them. */
    @Test
    void resolveDropsTheCaster() {
        assertEquals(List.of(BOB), ids(UbBurstVictims.resolve(List.of(
                at(CASTER, 0.0, 0.0), at(BOB, 4.0, 0.0)), CASTER)));
        assertEquals(List.of(), UbBurstVictims.resolve(List.of(at(CASTER, 0.0, 0.0)), CASTER));
    }

    /**
     * A victim whose coordinates are not real numbers is dropped.
     *
     * <p>Vanilla would happily write a {@code NaN} into the packet and every client's particle engine
     * would then hold a particle that never renders and never dies. The null {@code height} check is
     * the same rule: a body with no bounding box cannot have a chest to put the ring around.
     */
    @Test
    void resolveDropsVictimsWithUnusableCoordinates() {
        List<UbBurstVictims.Victim> bad = List.of(
                new UbBurstVictims.Victim(BOB, Double.NaN, 64.0, 0.0, 1.8),
                new UbBurstVictims.Victim(CAROL, 0.0, 64.0, 4.0, Double.POSITIVE_INFINITY),
                new UbBurstVictims.Victim(UUID.randomUUID(), 0.0, 64.0, 1.0, 0.0));

        assertEquals(List.of(), UbBurstVictims.resolve(bad, CASTER));
    }

    /** Degenerate inputs answer an empty list rather than throwing: this runs on a paid-for cast. */
    @Test
    void resolveNeverThrows() {
        assertEquals(List.of(), UbBurstVictims.resolve(null, CASTER));
        assertEquals(List.of(), UbBurstVictims.resolve(List.of(), CASTER));
        assertEquals(List.of(), UbBurstVictims.resolve(java.util.Arrays.asList(null, null), CASTER));
        assertEquals(1, UbBurstVictims.resolve(List.of(at(BOB, 1.0, 1.0)), null).size(),
                "a null caster id just means no id is excluded");
    }

    /** The chest the ring sits around is 55% of the body, the fraction the old burst already used. */
    @Test
    void theChestIsAFixedFractionOfTheBody() {
        UbBurstVictims.Victim tall = new UbBurstVictims.Victim(BOB, 0.0, 64.0, 0.0, 2.0);
        assertEquals(64.0 + 1.1, tall.chestY(), 1e-9);
        assertTrue(tall.chestY() > tall.y(), "胸口必须高于脚");
    }
}
