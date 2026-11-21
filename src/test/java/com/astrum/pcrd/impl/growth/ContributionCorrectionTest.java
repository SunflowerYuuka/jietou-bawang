package com.astrum.pcrd.impl.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * <b>Who gets the experience when the thing dies.</b>
 *
 * <h2>The defect these tests pin</h2>
 * A credit has to be on the ledger <b>before</b> {@code hurt()} runs, because a killing blow settles the ledger
 * from inside that call. But the amount known at that moment is a projection, and {@code hurt()} can refuse the
 * damage outright or reduce it. The projection could not be taken back, so a UB that took no health still shared
 * the experience of a kill somebody else earned.
 *
 * <p>{@link ContributionTracker#correct} is the repair, and these tests are its contract: a hit that did nothing
 * leaves nothing behind, a hit that landed for less is lowered to what it really took, and a kill that already
 * settled is <b>not</b> rewritten afterwards.
 */
class ContributionCorrectionTest {

    private static final ResourceLocation SERENON = ResourceLocation.parse("astrum:saren");
    private static final ResourceLocation KYOKA = ResourceLocation.parse("astrum:kyoka");

    private static UUID victim() {
        return UUID.randomUUID();
    }

    /**
     * <b>The bug case: damage fully cancelled.</b>
     *
     * <p>{@code hurt()} answering false (invulnerability, a cancelled event, an already-dead body) removes the
     * credit, so the character is not in the split at all. Before the fix she was in it for the full projection.
     */
    @Test
    void aHitThatDealtNothingLeavesNoCredit() {
        UUID attacker = UUID.randomUUID();
        UUID target = victim();
        ContributionTracker.note(attacker, target, SERENON, 15.0, 100L);
        assertEquals(Map.of(SERENON, 15.0), ContributionTracker.take(attacker, target),
                "the projection is recorded first, because a killing blow settles during hurt()");

        // A different fight, to keep the first assertion's ledger clean.
        UUID attacker2 = UUID.randomUUID();
        UUID target2 = victim();
        ContributionTracker.note(attacker2, target2, SERENON, 15.0, 100L);
        ContributionTracker.correct(attacker2, target2, SERENON, 0.0);
        assertEquals(Map.of(), ContributionTracker.take(attacker2, target2),
                "a UB that took no health must not appear in the split at all");
    }

    /** A hit reduced by armour keeps only what it really took. */
    @Test
    void aReducedHitIsLoweredToWhatItTook() {
        UUID attacker = UUID.randomUUID();
        UUID target = victim();
        ContributionTracker.note(attacker, target, SERENON, 15.0, 100L);
        ContributionTracker.correct(attacker, target, SERENON, 4.25);
        assertEquals(Map.of(SERENON, 4.25), ContributionTracker.take(attacker, target));
    }

    /**
     * <b>A settled kill is not rewritten.</b>
     *
     * <p>The death handler runs inside {@code hurt()} and calls {@link ContributionTracker#take}, which removes the
     * pair. The settlement in {@code UbDamageSettlement} then skips its correction on a dying victim for the same
     * reason — and even if it did call, this is the behaviour it would meet: a no-op, not a second payout and not a
     * retroactive reduction of experience already granted.
     */
    @Test
    void aSettledKillIsNotRewrittenAfterwards() {
        UUID attacker = UUID.randomUUID();
        UUID target = victim();
        ContributionTracker.note(attacker, target, SERENON, 15.0, 100L);
        Map<ResourceLocation, Double> settled = ContributionTracker.take(attacker, target);
        assertEquals(Map.of(SERENON, 15.0), settled, "the kill pays the projection that was on the ledger");

        ContributionTracker.correct(attacker, target, SERENON, 0.0);
        assertEquals(Map.of(), ContributionTracker.take(attacker, target),
                "the pair is gone, so a correction after settlement cannot resurrect or re-open it");
    }

    /**
     * <b>A correction never invents damage.</b>
     *
     * <p>The projection is the optimistic bound, so a measurement above it is a bug in the caller — and raising the
     * credit would hand experience for damage nobody dealt. Lowering is the only direction.
     */
    @Test
    void aCorrectionCannotRaiseTheCredit() {
        UUID attacker = UUID.randomUUID();
        UUID target = victim();
        ContributionTracker.note(attacker, target, SERENON, 5.0, 100L);
        ContributionTracker.correct(attacker, target, SERENON, 99.0);
        assertEquals(Map.of(SERENON, 5.0), ContributionTracker.take(attacker, target),
                "a measurement above the projection is ignored, not trusted");
    }

    /**
     * Two characters on one hit are corrected independently, and a correction for a character that never dealt
     * damage adds nothing.
     */
    @Test
    void correctionsArePerCharacter() {
        UUID attacker = UUID.randomUUID();
        UUID target = victim();
        ContributionTracker.note(attacker, target, SERENON, 10.0, 100L);
        ContributionTracker.note(attacker, target, KYOKA, 6.0, 100L);

        ContributionTracker.correct(attacker, target, SERENON, 2.0);
        ContributionTracker.correct(attacker, target, ResourceLocation.parse("astrum:yui"), 3.0);

        Map<ResourceLocation, Double> settled = ContributionTracker.take(attacker, target);
        assertEquals(2.0, settled.get(SERENON), 1e-9);
        assertEquals(6.0, settled.get(KYOKA), 1e-9, "the other character's credit is untouched");
        assertFalse(settled.containsKey(ResourceLocation.parse("astrum:yui")),
                "correcting a character that dealt nothing must not create an entry");
        assertTrue(settled.size() == 2, "and the split has exactly the two that really dealt damage");
    }
}
