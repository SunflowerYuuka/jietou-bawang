package com.astrum.pcrd.impl.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §5.2's "只削数值，不削机制" as arithmetic: the multiplier moves a quantity, and the only
 * quantities that exist are damage, healing, charge and duration.
 *
 * <p>The duration tests are the interesting ones. "Does the debuff land at all?" is mechanism, so a
 * scaled duration must never round down to zero ticks — a rank-5 slot with a 1-tick debuff hook has
 * to still apply the effect, briefly, rather than silently not applying it.
 */
class EffectQuantitiesTest {

    @Test
    void scalesAQuantityByTheMultiplier() {
        assertEquals(2.8f, EffectQuantities.scaled(4.0, 0.7), 1e-6);
        assertEquals(4.0f, EffectQuantities.scaled(4.0, 1.0), 1e-6);
        assertEquals(0.216f, EffectQuantities.scaled(4.0, 0.054), 1e-6);
    }

    @Test
    void durationIsScaledButNeverToZero() {
        assertEquals(70, EffectQuantities.durationTicks(100, 0.7));
        assertEquals(1, EffectQuantities.durationTicks(1, 0.1),
                "a scaled duration of 0 ticks would switch the debuff off — that is mechanism");
        assertEquals(0, EffectQuantities.durationTicks(0, 0.7), "0 declared is 0, not 1");
    }

    @Test
    void nonsenseInputAnswersZeroInsteadOfNaNOrANegative() {
        assertEquals(0.0f, EffectQuantities.scaled(-2.0, 1.0), 1e-6);
        assertEquals(0.0f, EffectQuantities.scaled(4.0, Double.NaN), 1e-6);
        assertEquals(0.0f, EffectQuantities.scaled(4.0, -1.0), 1e-6);
        assertEquals(0, EffectQuantities.durationTicks(-5, 0.7));
        assertEquals(0, EffectQuantities.durationTicks(100, Double.NaN));
    }

    /**
     * A multiplier below 1 is a falloff, so it must never <em>raise</em> a quantity — and a multiplier
     * of exactly 1 must leave it untouched. Deliberately no upper clamp at {@code 1.0}: the affection
     * system (spec §6.2) hands hook modifiers above 1.0 through the same arithmetic, and capping here
     * would silently disable them.
     */
    @Test
    void aFalloffOnlyFalls() {
        for (double multiplier : new double[] { 1.0, 0.7, 0.49, 0.343, 0.1 }) {
            assertTrue(EffectQuantities.scaled(4.0, multiplier) <= 4.0f, "multiplier " + multiplier);
            assertTrue(EffectQuantities.durationTicks(100, multiplier) <= 100, "multiplier " + multiplier);
        }
        assertEquals(6.0f, EffectQuantities.scaled(4.0, 1.5), 1e-6, "an affection bonus must survive");
    }
}
