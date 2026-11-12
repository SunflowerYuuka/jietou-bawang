package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All eight {@link Trigger} values, as pure yes/no answers.
 *
 * <p>Before this existed, six of the eight answered {@code false} unconditionally: a data author could
 * write {@code kill} or {@code combo_n} and get silence with no diagnostic. The switch in
 * {@link TriggerFacts#observes} is exhaustive and has no {@code default}, so a ninth trigger stops
 * compiling rather than quietly becoming another no-op.
 */
class TriggerFactsTest {

    private static final ResourceLocation POISON = ResourceLocation.fromNamespaceAndPath("astrum", "poison");
    private static final ResourceLocation BLEED = ResourceLocation.fromNamespaceAndPath("astrum", "bleed");

    private static TriggerParams stackParams(ResourceLocation stackId) {
        return new TriggerParams(0, Optional.of(stackId));
    }

    /** A plain melee hit observes exactly the two hit-shaped triggers it can claim. */
    @Test
    void aMeleeHitObservesOnlyMeleeAndNothingElse() {
        TriggerFacts facts = TriggerFacts.forHit(true, false, false, 1, false);
        assertTrue(facts.observes(Trigger.MELEE_HIT, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.PROJECTILE_HIT, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.CRIT, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.KILL, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.LOW_HEALTH, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.BLOCK, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.COMBO_N, new TriggerParams(3, Optional.empty())));
        assertFalse(facts.observes(Trigger.STACK_FULL, stackParams(POISON)));
    }

    @Test
    void aProjectileHitObservesProjectile() {
        TriggerFacts facts = TriggerFacts.forHit(false, true, false, 1, false);
        assertTrue(facts.observes(Trigger.PROJECTILE_HIT, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.MELEE_HIT, TriggerParams.NONE));
    }

    @Test
    void aCriticalHitObservesCritInAdditionToTheHitItself() {
        TriggerFacts facts = TriggerFacts.forHit(true, false, true, 1, false);
        assertTrue(facts.observes(Trigger.CRIT, TriggerParams.NONE));
        assertTrue(facts.observes(Trigger.MELEE_HIT, TriggerParams.NONE), "a crit is still a melee hit");
    }

    @Test
    void aKillObservesOnlyKill() {
        TriggerFacts facts = TriggerFacts.forKill();
        assertTrue(facts.observes(Trigger.KILL, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.MELEE_HIT, TriggerParams.NONE),
                "the killing blow already fired its own melee hook; kill must not replay it");
        assertFalse(facts.observes(Trigger.COMBO_N, new TriggerParams(1, Optional.empty())));
    }

    @Test
    void aBlockObservesBlock() {
        TriggerFacts facts = TriggerFacts.forBlock();
        assertTrue(facts.observes(Trigger.BLOCK, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.KILL, TriggerParams.NONE));
    }

    /** "Low health" is a property of the attacker at the moment of the hit, not an event of its own. */
    @Test
    void lowHealthIsHalfOrLessOfMaxHealth() {
        assertTrue(TriggerFacts.isLowHealth(10.0F, 20.0F), "exactly half counts as low");
        assertTrue(TriggerFacts.isLowHealth(4.0F, 20.0F));
        assertFalse(TriggerFacts.isLowHealth(11.0F, 20.0F));
        assertFalse(TriggerFacts.isLowHealth(20.0F, 20.0F));
        assertFalse(TriggerFacts.isLowHealth(20.0F, 0.0F), "no maximum health is not low health");
        assertTrue(TriggerFacts.forHit(true, false, false, 1, true).observes(Trigger.LOW_HEALTH,
                TriggerParams.NONE));
    }

    /**
     * {@code combo_n: 3} pays out on the 3rd, 6th, 9th… consecutive hit — "every third hit", not "every
     * hit from the third onwards", which would make the payout permanent and the diminishing design
     * pointless.
     */
    @Test
    void comboNpaysOutOnEveryNthConsecutiveHit() {
        for (int hits = 1; hits <= 9; hits++) {
            TriggerFacts facts = TriggerFacts.forHit(true, false, false, hits, false);
            boolean expected = hits % 3 == 0;
            assertTrue(facts.observes(Trigger.COMBO_N, new TriggerParams(3, Optional.empty())) == expected,
                    "combo chain at hit " + hits);
        }
    }

    @Test
    void aZeroOrNegativeComboCountPaysOutEveryHit() {
        TriggerFacts facts = TriggerFacts.forHit(true, false, false, 4, false);
        assertTrue(facts.observes(Trigger.COMBO_N, TriggerParams.NONE), "hits=0 means n=1");
    }

    @Test
    void comboNeverFiresForANonHitEvent() {
        assertFalse(TriggerFacts.forKill().observes(Trigger.COMBO_N, TriggerParams.NONE));
        assertFalse(TriggerFacts.forHit(true, false, false, 0, false)
                .observes(Trigger.COMBO_N, TriggerParams.NONE), "a chain of zero is not a multiple");
    }

    @Test
    void stackFullOnlyForTheStackThatReachedMax() {
        TriggerFacts facts = TriggerFacts.forStackFull(POISON);
        assertTrue(facts.observes(Trigger.STACK_FULL, stackParams(POISON)));
        assertFalse(facts.observes(Trigger.STACK_FULL, stackParams(BLEED)),
                "a full poison stack must not fire a bleed watcher");
        assertTrue(facts.observes(Trigger.STACK_FULL, TriggerParams.NONE),
                "a watcher that names no stack accepts any stack reaching its cap");
        assertFalse(facts.observes(Trigger.KILL, TriggerParams.NONE));
        assertFalse(TriggerFacts.forHit(true, false, false, 1, false)
                .observes(Trigger.STACK_FULL, stackParams(POISON)), "an ordinary hit is not a full stack");
    }
}
