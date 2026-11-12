package com.astrum.pcrd.impl.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which events pay experience, as one rule.
 *
 * <h2>Why this needs pinning</h2>
 * {@code CombatEvents.collect} serves four entry points — a landed hit, a kill, a shield block and the
 * {@code stack_full} re-entry — and growth's contribution ledger must be fed by the one that removed
 * health. Each other path has a specific failure:
 *
 * <ul>
 *   <li><b>The kill path</b> carries {@code dealt = 0} (the target is already dead). Recording it would
 *       a) pay for damage nobody dealt and b) re-create the ledger entry {@code KillExperience.settle}
 *       had just consumed, so a second death event for that victim would pay the same fight twice.</li>
 *   <li><b>A shield block</b> carries the damage <em>prevented</em>, which is not damage dealt.</li>
 *   <li><b>A hit that removed nothing</b> — fully absorbed, or nonsense — has no share to attribute.</li>
 * </ul>
 *
 * <p>{@link CombatEvents#paysExperience} is the predicate the handler branches on, so this test and the
 * event listener cannot drift apart.
 */
class ContributionRecordTest {

    @Test
    void aLandedHitThatRemovedHealthPays() {
        assertTrue(CombatEvents.paysExperience(4.92F, TriggerFacts.forHit(true, false, false, 0, false)));
        assertTrue(CombatEvents.paysExperience(0.01F, TriggerFacts.forHit(true, false, false, 0, false)),
                "even a scratch is a contribution");
    }

    @Test
    void theKillPathDoesNotPay() {
        assertFalse(CombatEvents.paysExperience(0.0F, TriggerFacts.forKill()),
                "the target is already dead: there is no health to attribute");
    }

    @Test
    void aShieldBlockDoesNotPay() {
        assertFalse(CombatEvents.paysExperience(6.0F, TriggerFacts.forBlock()),
                "blocked damage was prevented, not dealt");
    }

    @Test
    void aHitThatRemovedNoHealthDoesNotPay() {
        TriggerFacts hit = TriggerFacts.forHit(true, false, false, 0, false);
        assertFalse(CombatEvents.paysExperience(0.0F, hit));
        assertFalse(CombatEvents.paysExperience(-1.0F, hit), "a negative is nonsense, not a contribution");
        assertFalse(CombatEvents.paysExperience(Float.NaN, hit));
        assertFalse(CombatEvents.paysExperience(1.0F, null), "no facts means nothing to attribute");
    }

    /** {@code stack_full} is a re-entry of the same hit, so it is a hit for this purpose. */
    @Test
    void aStackFullReEntryIsStillAHit() {
        assertTrue(CombatEvents.paysExperience(2.0F,
                TriggerFacts.forStackFull(com.astrum.pcrd.impl.InternalIds.id("poison"))));
    }
}
