package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.InternalIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandedHitTest {

    static final ResourceLocation HOOK = InternalIds.id("hook_damage");
    static final ResourceLocation PLAYER_ATTACK = ResourceLocation.parse("minecraft:player_attack");

    /**
     * The two rules the entry point applies to one landed hit, pinned together because they were
     * once the wrong way round: our own damage must <b>still earn UB charge</b> (spec §5.4 counts
     * 造成伤害 with no carve-out for mod-applied damage) while never re-entering hook evaluation.
     */
    @Test void ourDamageCreditsChargeButDoesNotRunHooks() {
        var plan = LandedHit.plan(HOOK);
        assertTrue(plan.creditsCharge(), "attachment damage is damage dealt and must charge the gauge");
        assertFalse(plan.runsHooks(), "our own damage must never re-trigger attachment hooks");
    }

    @Test void vanillaDamageCreditsChargeAndRunsHooks() {
        var plan = LandedHit.plan(PLAYER_ATTACK);
        assertTrue(plan.creditsCharge());
        assertTrue(plan.runsHooks());
    }

    /** A damage holder with no key cannot be ours, so it takes the ordinary path. */
    @Test void unknownTypeIsTreatedAsVanilla() {
        var plan = LandedHit.plan(null);
        assertTrue(plan.creditsCharge());
        assertTrue(plan.runsHooks());
    }

    /**
     * {@code LivingDamageEvent.Post.getNewDamage()} is post-absorption damage and is <b>not</b>
     * clamped to the remaining health, so a 40-damage crit on a 3-hp mob reports 40. Charge must
     * follow health actually removed, not the number that was asked for.
     */
    @Test void overkillIsClampedToRemainingHealth() {
        assertEquals(3.0F, LandedHit.healthLost(40.0F, 3.0F), 1e-6);
    }

    @Test void normalHitPassesThrough() {
        assertEquals(5.0F, LandedHit.healthLost(5.0F, 20.0F), 1e-6);
        assertEquals(20.0F, LandedHit.healthLost(20.0F, 20.0F), 1e-6);
    }

    @Test void nonPositiveDamageIsNothing() {
        assertEquals(0.0F, LandedHit.healthLost(0.0F, 20.0F), 1e-6);
        assertEquals(0.0F, LandedHit.healthLost(-3.0F, 20.0F), 1e-6);
        assertEquals(0.0F, LandedHit.healthLost(Float.NaN, 20.0F), 1e-6);
    }
}
