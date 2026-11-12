package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one multiplier chain: {@code diminishing × level × affectionHook} (spec §5.2, §6.1, §6.2).
 *
 * <h2>Why the arithmetic is pinned separately from the character data</h2>
 * {@code KillExperienceTest} proves that a level and a tier produce the right factors;
 * {@code MultiplierChainTest} proves that the factors <b>compose the way the design says</b>, and —
 * the part that matters — that a broken factor degrades to the party multiplier instead of switching
 * a character's hooks off. That second rule cannot be stated anywhere else: it is the difference
 * between "your growth data is odd, nothing happens" and "your character silently does nothing".
 *
 * <p>{@link MultiplierChain#compose} is the whole of the arithmetic, so it is tested directly rather
 * than through {@code effective}, which only adds the character-definition lookup.
 */
class MultiplierChainTest {

    @Test
    void theThreeFactorsMultiply() {
        assertEquals(1.05, MultiplierChain.compose(0.7, 1.5, 1.0), 1e-9);
        assertEquals(1.575, MultiplierChain.compose(1.0, 1.5, 1.05), 1e-9);
        assertEquals(0.735, MultiplierChain.compose(0.7, 1.05, 1.0), 1e-9,
                "a level-3 character on the second same-dimension slot");
    }

    /**
     * The tier-2 modifier is the one factor allowed <b>above</b> 1: it is a buff, and clamping it at
     * the declared value is the bug {@code EffectQuantities} warns about from the other side.
     */
    @Test
    void aBuffFactorIsNotClamped() {
        assertEquals(1.5, MultiplierChain.compose(1.0, 1.0, 1.5), 1e-9);
        assertTrue(MultiplierChain.compose(1.0, 1.58, 1.5) > 1.5);
    }

    /** A neutral growth changes nothing at all: this is the shipped level-1 tier-1 character. */
    @Test
    void aNeutralGrowthLeavesThePartyMultiplierAlone() {
        assertEquals(0.7, MultiplierChain.effective(0.7, GrowthMultiplier.NEUTRAL, 1), 1e-9);
        assertEquals(1.0, MultiplierChain.effective(1.0, null, 0), 1e-9);
    }

    /**
     * Hostile data must not switch a character off: a {@code NaN} or infinite factor is dropped and the
     * diminishing multiplier alone is used. The alternative — returning the product — would make every
     * hook of that character queue {@code NaN} damage, and {@code EffectQuantities.scaled} would answer
     * {@code 0} for all of them, i.e. a broken datapack would silently mute a character.
     */
    @Test
    void aBrokenGrowthFactorFallsBackToThePartyMultiplier() {
        assertEquals(0.7, MultiplierChain.compose(0.7, Double.NaN, 1.0), 1e-9);
        assertEquals(0.7, MultiplierChain.compose(0.7, 1.0, Double.NaN), 1e-9);
        assertEquals(0.7, MultiplierChain.compose(0.7, Double.POSITIVE_INFINITY, 1.0), 1e-9);
        assertEquals(0.7, MultiplierChain.compose(0.7, -1.0, 1.0), 1e-9,
                "a negative growth factor would invert every quantity, so it is refused");
        assertEquals(0.7, MultiplierChain.compose(0.7, 0.0, 1.0), 1e-9,
                "0.0 is the one value that would switch the hooks off");
    }

    /** A broken <em>diminishing</em> factor is not rescued: that is the party table's number. */
    @Test
    void aBrokenDiminishingFactorAnswersZero() {
        assertEquals(0.0, MultiplierChain.compose(Double.NaN, 1.5, 1.5), 1e-9);
        assertEquals(0.0, MultiplierChain.compose(-1.0, 1.5, 1.5), 1e-9);
    }

    /**
     * An overflow is refused for the same reason a {@code NaN} is: {@code Infinity} would make the
     * executor's own filter answer {@code 0} and mute the hook.
     */
    @Test
    void anOverflowIsRefusedRatherThanPassedOn() {
        assertEquals(0.5, MultiplierChain.compose(0.5, Double.MAX_VALUE, Double.MAX_VALUE), 1e-9);
    }
}
