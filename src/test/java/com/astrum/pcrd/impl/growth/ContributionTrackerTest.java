package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.impl.InternalIds;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger behind §6.1's 按玩家造成的伤害占比分摊经验: who hurt a victim, per character, until the kill
 * settles it.
 *
 * <h2>The three lifetimes, and the one that leaks if it is wrong</h2>
 * {@code take} is a <b>take</b>, not a read: the kill consumes the entry, so a second death event for
 * the same victim (a mod reviving it, a totem) cannot pay the same damage out twice. A pair the ledger
 * never recorded pays nothing — that is what stops a player who happened to be credited with a kill
 * from collecting experience for damage someone else's character dealt. And because a mob that is
 * damaged and then despawns never fires the death hook, the table prunes itself on the next recorded
 * hit rather than from a tick listener.
 *
 * <p>The ledger is static and process-local, so every test clears it on the way in and on the way out;
 * a test that left a pair behind would quietly change the next test's answer.
 */
class ContributionTrackerTest {

    private static final UUID ATTACKER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID OTHER_ATTACKER = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID OTHER_VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000f6");
    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KATALINA = InternalIds.id("katalina");

    @BeforeEach
    void forgetWhatCameBefore() {
        ContributionTracker.clear();
    }

    @AfterEach
    void leaveNothingBehind() {
        ContributionTracker.clear();
    }

    @Test
    void damageAccumulatesPerCharacter() {
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 3.0, 10L);
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 2.0, 12L);
        ContributionTracker.note(ATTACKER, VICTIM, KATALINA, 1.5, 13L);

        Map<ResourceLocation, Double> owed = ContributionTracker.take(ATTACKER, VICTIM);
        assertEquals(5.0, owed.get(DJEETA), 1e-9, "two hits by one character are one contribution");
        assertEquals(1.5, owed.get(KATALINA), 1e-9);
        assertEquals(2, owed.size());
    }

    /** The kill settles the ledger: the damage is paid once and the entry is gone. */
    @Test
    void takingAKillClearsIt() {
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 4.0, 10L);
        assertEquals(4.0, ContributionTracker.take(ATTACKER, VICTIM).get(DJEETA), 1e-9);
        assertTrue(ContributionTracker.take(ATTACKER, VICTIM).isEmpty(),
                "a second death event for the same victim must not pay the same damage again");
        assertEquals(0, ContributionTracker.size());
    }

    /**
     * The duplicate-payment guard in one line: an attacker credited with a kill it never touched gets
     * an empty map, which the caller splits into nothing.
     */
    @Test
    void anAttackerWhoNeverTouchedTheVictimEarnsNothing() {
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 4.0, 10L);
        assertTrue(ContributionTracker.take(OTHER_ATTACKER, VICTIM).isEmpty(),
                "experience is paid for damage recorded, not for the kill credit alone");
        assertTrue(ContributionTracker.take(ATTACKER, OTHER_VICTIM).isEmpty(),
                "and never for a different victim");
        assertEquals(4.0, ContributionTracker.take(ATTACKER, VICTIM).get(DJEETA), 1e-9,
                "the refused takes must not have consumed the real one");
    }

    /** A character that only charged the gauge never dealt damage, so it never appears. */
    @Test
    void aCharacterThatOnlyChargedHasNoEntry() {
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 4.0, 10L);
        Map<ResourceLocation, Double> owed = ContributionTracker.take(ATTACKER, VICTIM);
        assertFalse(owed.containsKey(KATALINA));
        assertTrue(ContributionTracker.take(ATTACKER, VICTIM).isEmpty());
    }

    @Test
    void nothingRecordedMeansAnEmptyAnswerRatherThanACrash() {
        assertTrue(ContributionTracker.take(ATTACKER, VICTIM).isEmpty());
        assertTrue(ContributionTracker.take(null, VICTIM).isEmpty());
        assertTrue(ContributionTracker.take(ATTACKER, null).isEmpty());
    }

    @Test
    void nonsenseIsIgnoredRatherThanRecordedAsDamage() {
        ContributionTracker.note(null, VICTIM, DJEETA, 1.0, 10L);
        ContributionTracker.note(ATTACKER, null, DJEETA, 1.0, 10L);
        ContributionTracker.note(ATTACKER, VICTIM, null, 1.0, 10L);
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 0.0, 10L);
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, -3.0, 10L);
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, Double.NaN, 10L);
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, Double.POSITIVE_INFINITY, 10L);
        assertEquals(0, ContributionTracker.size());
        assertTrue(ContributionTracker.take(ATTACKER, VICTIM).isEmpty());
    }

    @Test
    void clearingDropsEveryPair() {
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 4.0, 10L);
        ContributionTracker.note(OTHER_ATTACKER, OTHER_VICTIM, DJEETA, 1.0, 10L);
        assertEquals(2, ContributionTracker.size());
        ContributionTracker.clear();
        assertEquals(0, ContributionTracker.size());
        assertTrue(ContributionTracker.take(ATTACKER, VICTIM).isEmpty());
    }

    /**
     * The leak guard: a mob damaged and then despawned, dunked in lava or unloaded never fires a death
     * hook, so its pair would stay in the table for the life of the server. The prune runs on the next
     * recorded hit — the only moment the cost is already paid — and only once the table has grown past
     * {@link ContributionTracker#PRUNE_THRESHOLD}.
     */
    @Test
    void aPairOlderThanTheStaleWindowIsPrunedOnTheNextHit() {
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 5.0, 0L);
        long muchLater = ContributionTracker.STALE_TICKS * 10L;
        for (int i = 0; i <= ContributionTracker.PRUNE_THRESHOLD + 1; i++) {
            ContributionTracker.note(UUID.randomUUID(), OTHER_VICTIM, DJEETA, 1.0, muchLater);
        }
        assertTrue(ContributionTracker.take(ATTACKER, VICTIM).isEmpty(),
                "a hit ten stale-windows ago cannot belong to a live fight");
        assertTrue(ContributionTracker.size() > 0, "the fresh pairs must survive the prune");
        assertTrue(ContributionTracker.size() <= ContributionTracker.PRUNE_THRESHOLD + 2,
                "the table is bounded by the prune, got " + ContributionTracker.size());
    }

    /** A pair that is still fresh is not pruned just because the table is large. */
    @Test
    void aLivePairSurvivesThePrune() {
        long now = ContributionTracker.STALE_TICKS * 10L;
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 5.0, now);
        for (int i = 0; i <= ContributionTracker.PRUNE_THRESHOLD + 1; i++) {
            ContributionTracker.note(UUID.randomUUID(), OTHER_VICTIM, DJEETA, 1.0, now);
        }
        assertEquals(5.0, ContributionTracker.take(ATTACKER, VICTIM).get(DJEETA), 1e-9);
    }

    /** The caller splits experience from this map and logs it; it must not be writable. */
    @Test
    void theTakenMapIsAnImmutableCopy() {
        ContributionTracker.note(ATTACKER, VICTIM, DJEETA, 4.0, 10L);
        Map<ResourceLocation, Double> owed = ContributionTracker.take(ATTACKER, VICTIM);
        assertThrows(UnsupportedOperationException.class, () -> owed.put(KATALINA, 1.0));
    }
}
