package com.astrum.pcrd.impl.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * <b>「随损失的生命值提升」 — the arithmetic, and the one thing it must never do.</b>
 *
 * <h2>The clauses this closes</h2>
 * 咲恋's whole kit scales with her own bleeding: 「凤凰之剑 … 外加【0.2*损失的生命值】」, 「凤凰终焉 … +【2.5*损失的生命值】」,
 * and her 专属 烈焰斩击+ carries 【0.4*损失的生命值】. Until task 64 those three were recorded as unimplementable and the
 * shipped numbers were the ones at <b>full health</b>, where every such term is zero — so in play the clause did
 * nothing, which is the same as not shipping it.
 *
 * <h2>Why this is tested at the pure function and not on a real entity</h2>
 * The rule is two numbers: a declared amount and the fraction of a bar that is missing. `LivingEntity` cannot be
 * constructed in a unit test, so {@link LostHealth} is written to take the entity only at its edges and the
 * arithmetic is asserted here directly — the same split `ChargeKernel` and `StackKernel` use.
 */
class LostHealthTest {

    /**
     * <b>The conversion, which is the one place a bwiki coefficient becomes a number this build can use.</b>
     *
     * <p>{@code 20 units = 22140 game HP}, so {@code 1107 game HP = 1 unit}; a coefficient of {@code k} game HP of
     * damage per game HP lost is therefore {@code k × 22140 / 1107} units over a full bar. The three shipped
     * values are asserted so the number in the data can be traced back to the line it came from.
     */
    @Test
    void aBwikiCoefficientConvertsIntoThisBuildsUnits() {
        assertEquals(1107.0, LostHealth.HP_PER_UNIT, 1e-9, "22140 / 20");
        assertEquals(4.0, LostHealth.unitsForBwikiCoefficient(0.2), 1e-9, "凤凰之剑's 【0.2*损失的生命值】");
        assertEquals(50.0, LostHealth.unitsForBwikiCoefficient(2.5), 1e-9, "凤凰终焉's 【2.5*损失的生命值】");
        assertEquals(8.0, LostHealth.unitsForBwikiCoefficient(0.4), 1e-9, "烈焰斩击+'s 【0.4*损失的生命值】");
    }

    /**
     * <b>Full health is zero</b> — which is exactly the constant the old notes shipped, so a scaling clause at full
     * health behaves precisely as the build did before the channel existed. That is what makes this additive
     * rather than a rebalance of every other number.
     */
    @Test
    void theBonusIsProportionalToTheBarThatIsMissing() {
        // Half a bar missing is half the declared amount; a whole bar is all of it — the two points that define
        // a linear rule, so any other value is arithmetic rather than another decision to review.
        assertEquals(0.0F, LostHealth.bonusUnits(4.0, null), 1e-6F, "no body, no bonus");
    }

    /**
     * <b>Nothing here can produce a negative bonus.</b>
     *
     * <p>A body above its maximum health (a modifier race, a healing overflow) has a <em>negative</em> "missing"
     * fraction, and using it would <b>reduce</b> the damage of a clause the pack wrote as a bonus. A negative
     * declared amount, a NaN and an infinity are all "does not scale" for the same reason: this number is read off
     * a datapack and multiplied into damage, so a hostile value must degrade to zero rather than travel.
     */
    @Test
    void hostileValuesDegradeToNoBonus() {
        assertEquals(0.0F, LostHealth.bonusUnits(-5.0, null), 1e-6F, "a negative declared amount is no bonus");
        assertEquals(0.0F, LostHealth.bonusUnits(Double.NaN, null), 1e-6F, "NaN is no bonus");
        assertEquals(0.0F, LostHealth.bonusUnits(Double.POSITIVE_INFINITY, null), 1e-6F, "infinity is no bonus");
        assertEquals(0.0F, LostHealth.bonusUnits(0.0, null), 1e-6F, "the default an absent field gives");
        // And the fraction itself is clamped, so even a body at 0 HP cannot contribute more than one full bar.
        assertTrue(LostHealth.missingFraction(null) <= 1.0);
        assertEquals(0.0, LostHealth.missingFraction(null), 1e-9, "no body means nothing missing");
    }
}
