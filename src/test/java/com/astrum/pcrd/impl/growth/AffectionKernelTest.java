package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.HookModifier;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The earning half of affection (spec §6.2): which tier a number is in, and what each tier changes.
 *
 * <h2>Three rules, and why each one is pinned</h2>
 * <ol>
 *   <li><b>Tier 2 only scales what the pack named.</b> {@code tier2HookModifiers} is a closed list:
 *       a hook the pack did not list keeps {@code 1.0}. An implementation that scaled "the strongest
 *       hook" or applied {@code 1.5} to every hook would be a buff nobody wrote — §6.2's
 *       只改数值不改数量 means the tier changes numbers it declared, nothing else.</li>
 *   <li><b>Tier 3 replaces the ultimate and keeps tier 2's numbers.</b> A character that reaches 8
 *       must not lose the 1.5 it earned at 3.</li>
 *   <li><b>The ladder itself is {@link GrowthFacts}'.</b> {@code AffectionKernel.tier} is a
 *       delegation, and the test below proves it on thresholds the shipped pack does not use — if
 *       this class ever hard-codes 3 and 8, the book (which reads {@code GrowthFacts}) and the damage
 *       pipeline (which reads this) would disagree about what tier a character is.</li>
 * </ol>
 *
 * <p>The thresholds come from the shipped {@code djeeta.json} via {@link ShippedGrowth}; the literals
 * below (tier 2 at 3, tier 3 at 8, hook 1 × 1.5) are spec §6.4's statement of the same numbers.
 */
class AffectionKernelTest {

    private static final AffectionDef AFFECTION = ShippedGrowth.affection();

    @Test
    void theShippedAffectionIsTheOneTheSpecNames() {
        assertEquals(3, AFFECTION.tier2Threshold());
        assertEquals(8, AFFECTION.tier3Threshold());
        assertEquals(1, AFFECTION.tier2HookModifiers().size());
        assertEquals(1, AFFECTION.tier2HookModifiers().get(0).hookIndex());
        assertEquals(1.5, AFFECTION.tier2HookModifiers().get(0).mult(), 1e-9);
    }

    // -----------------------------------------------------------------------------------------
    // The ladder: boundaries, and the delegation that keeps one implementation of it.
    // -----------------------------------------------------------------------------------------

    @Test
    void theThresholdsAreInclusiveBoundaries() {
        assertEquals(1, AffectionKernel.tier(0, AFFECTION));
        assertEquals(1, AffectionKernel.tier(1, AFFECTION), "a freshly enrolled character is tier 1");
        assertEquals(1, AffectionKernel.tier(2, AFFECTION), "2 is one short of tier 2's threshold");
        assertEquals(2, AffectionKernel.tier(3, AFFECTION), "3 is tier 2 exactly");
        assertEquals(2, AffectionKernel.tier(7, AFFECTION), "7 is one short of tier 3's threshold");
        assertEquals(3, AffectionKernel.tier(8, AFFECTION), "8 is tier 3 exactly");
        assertEquals(3, AffectionKernel.tier(99, AFFECTION));
    }

    @Test
    void theKernelsLadderIsGrowthFactsLadder() {
        AffectionDef other = new AffectionDef(5, 9, List.of());
        for (int affection = 0; affection <= 12; affection++) {
            assertEquals(GrowthFacts.tier(affection, other),
                    AffectionKernel.tier(affection, other),
                    "affection " + affection + ": the kernel must delegate the ladder to GrowthFacts");
        }
    }

    /** What the growth view says next to the number: "next tier at 3", then 8, then nothing. */
    @Test
    void theNextThresholdWalksUpTheLadderForAGivenAffection() {
        assertEquals(OptionalInt.of(3), GrowthFacts.nextTierThreshold(2, AFFECTION));
        assertEquals(OptionalInt.of(8), GrowthFacts.nextTierThreshold(3, AFFECTION),
                "at tier 2 the thing to aim for is tier 3's threshold");
        assertEquals(OptionalInt.of(8), GrowthFacts.nextTierThreshold(7, AFFECTION));
        assertTrue(GrowthFacts.nextTierThreshold(8, AFFECTION).isEmpty(),
                "at the last tier there is no next threshold - a different sentence from '0 more'");
    }

    // -----------------------------------------------------------------------------------------
    // Tier 2's effect: exactly the hooks it listed, and nothing else.
    // -----------------------------------------------------------------------------------------

    @Test
    void tierTwoScalesOnlyTheHooksTheDatapackListed() {
        assertEquals(1.5, AffectionKernel.hookMultiplier(AFFECTION, 1, 2), 1e-9,
                "spec §6.4: tier 2 multiplies hook index 1 by 1.5");
        assertEquals(1.0, AffectionKernel.hookMultiplier(AFFECTION, 0, 2), 1e-9,
                "hook 0 is not in the list, so it stays exactly as declared");
        assertEquals(1.0, AffectionKernel.hookMultiplier(AFFECTION, 2, 2), 1e-9);
    }

    @Test
    void tierOneScalesNothing() {
        assertEquals(1.0, AffectionKernel.hookMultiplier(AFFECTION, 0, 1), 1e-9);
        assertEquals(1.0, AffectionKernel.hookMultiplier(AFFECTION, 1, 1), 1e-9,
                "the blank tier: the character works, its numbers are as written");
    }

    @Test
    void tierThreeKeepsTheNumbersTierTwoEarned() {
        assertEquals(1.5, AffectionKernel.hookMultiplier(AFFECTION, 1, 3), 1e-9,
                "reaching tier 3 must not cost the multiplier earned at tier 2");
    }

    /** A pack that lists one index twice means both: the factors compound rather than one winning. */
    @Test
    void aRepeatedIndexCompounds() {
        AffectionDef twice = new AffectionDef(3, 8,
                List.of(new HookModifier(1, 1.5), new HookModifier(1, 1.5)));
        assertEquals(2.25, AffectionKernel.hookMultiplier(twice, 1, 2), 1e-9);
    }

    @Test
    void hostileModifiersLeaveTheHookAtItsDeclaredValue() {
        // A datapack is data: 0 would switch a hook off, -1 would invert it, NaN would poison every
        // later multiplication. None of those is a buff, so none of them is obeyed.
        AffectionDef hostile = new AffectionDef(3, 8, List.of(
                new HookModifier(1, 0.0), new HookModifier(1, -1.0), new HookModifier(1, Double.NaN)));
        assertEquals(1.0, AffectionKernel.hookMultiplier(hostile, 1, 2), 1e-9);
        assertEquals(1.0, AffectionKernel.hookMultiplier(new AffectionDef(3, 8,
                List.of(new HookModifier(-3, 1.5))), 0, 2), 1e-9, "a negative index matches nothing");
        assertEquals(1.0, AffectionKernel.hookMultiplier(null, 1, 2), 1e-9);
    }

    // -----------------------------------------------------------------------------------------
    // Tier 3's effect: the enhanced ultimate.
    // -----------------------------------------------------------------------------------------

    @Test
    void tierThreeIsTheEnhancedUltimateAndTierTwoIsNot() {
        assertTrue(AffectionKernel.usesEnhancedUb(3));
        assertFalse(AffectionKernel.usesEnhancedUb(2), "tier 2 is a numbers tier, not an UB tier");
        assertFalse(AffectionKernel.usesEnhancedUb(1));
        assertTrue(AffectionKernel.usesEnhancedUb(4), "a save above the last tier still gets the better UB");
    }

    /** A pack with no tier 3 must never produce the enhanced ultimate, however high affection climbs. */
    @Test
    void aPackWithoutATierThreeNeverUsesTheEnhancedUltimate() {
        AffectionDef noTier3 = new AffectionDef(3, 0, List.of(new HookModifier(1, 1.5)));
        int tier = AffectionKernel.tier(1_000, noTier3);
        assertEquals(2, tier);
        assertFalse(AffectionKernel.usesEnhancedUb(tier));
    }

    // -----------------------------------------------------------------------------------------
    // Writing affection: the one cap there is.
    // -----------------------------------------------------------------------------------------

    @Test
    void affectionRisesByOneAndStopsAtTheLastTier() {
        assertEquals(2, AffectionKernel.clampAffection(1, 1, 8));
        assertEquals(8, AffectionKernel.clampAffection(7, 1, 8));
        assertEquals(8, AffectionKernel.clampAffection(8, 1, 8),
                "affection above tier 3 buys nothing (spec §6.2), so it is capped there");
    }

    @Test
    void affectionNeverGoesNegative() {
        assertEquals(0, AffectionKernel.clampAffection(0, -5, 8));
        assertEquals(0, AffectionKernel.clampAffection(2, -2, 8));
        assertEquals(5, AffectionKernel.clampAffection(5, 0, 8));
    }

    @Test
    void aPackWithoutATierThreeHasNothingToCapTo() {
        assertEquals(15, AffectionKernel.clampAffection(5, 10, 0));
        assertEquals(0, AffectionKernel.clampAffection(5, -10, 0));
    }

    /** Widened to long before adding: two large ints must not overflow into a negative affection. */
    @Test
    void theCapSurvivesAnOverflowingDelta() {
        assertEquals(8, AffectionKernel.clampAffection(Integer.MAX_VALUE, Integer.MAX_VALUE, 8));
        assertEquals(Integer.MAX_VALUE,
                AffectionKernel.clampAffection(Integer.MAX_VALUE, Integer.MAX_VALUE, 0));
    }
}
