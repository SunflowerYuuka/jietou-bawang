package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.GrowthDef;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The display half of growth: the curve's requirement, the tier ladder, and bar fractions that are
 * always inside the frame.
 *
 * <h2>Why these numbers are pinned</h2>
 * The 角色册 screen draws an experience bar and says which tier a character is in. Both are rules, and
 * this project has shipped three bugs from having two copies of one rule — so the two live here, once,
 * with the datapack's own numbers ({@code expBase 100}, {@code expPerLevel 1.15},
 * {@code tier2Threshold 3}, {@code tier3Threshold 8}) as the fixture. Task 15's
 * {@code ExperienceKernel}/{@code AffectionKernel} are expected to delegate to these methods rather
 * than restate them; if they do not, these assertions are the ones that will disagree.
 */
class GrowthFactsTest {

    private static final GrowthDef GROWTH = new GrowthDef(100, 1.15, 30);

    private static final AffectionDef AFFECTION = AffectionDef.thresholds(3, 8);

    @Test
    void firstLevelNeedsTheBase() {
        assertEquals(100, GrowthFacts.expToNext(1, GROWTH));
    }

    @Test
    void theRequirementGrowsGeometrically() {
        assertEquals(115, GrowthFacts.expToNext(2, GROWTH));
        assertEquals(132, GrowthFacts.expToNext(3, GROWTH));
    }

    @Test
    void aLevelBelowOneIsReadAsTheFirstLevel() {
        assertEquals(GrowthFacts.expToNext(1, GROWTH), GrowthFacts.expToNext(0, GROWTH));
        assertEquals(GrowthFacts.expToNext(1, GROWTH), GrowthFacts.expToNext(-7, GROWTH));
    }

    @Test
    void aCurveWithNoBaseNeedsNothing() {
        assertEquals(0, GrowthFacts.expToNext(5, new GrowthDef(0, 1.15, 30)));
        assertEquals(0, GrowthFacts.expToNext(5, new GrowthDef(-10, 1.15, 30)));
    }

    @Test
    void tiersFollowTheDatapackThresholds() {
        assertEquals(1, GrowthFacts.tier(0, AFFECTION));
        assertEquals(1, GrowthFacts.tier(2, AFFECTION));
        assertEquals(2, GrowthFacts.tier(3, AFFECTION));
        assertEquals(2, GrowthFacts.tier(7, AFFECTION));
        assertEquals(3, GrowthFacts.tier(8, AFFECTION));
        assertEquals(3, GrowthFacts.tier(99, AFFECTION));
    }

    @Test
    void aThresholdOfZeroMeansThatTierDoesNotExist() {
        AffectionDef noTier3 = new AffectionDef(3, 0, List.of());
        assertEquals(2, GrowthFacts.tier(1000, noTier3),
                "a pack without tier 3 must not report tier 3 however high the number climbs");
        AffectionDef noTiers = new AffectionDef(0, 0, List.of());
        assertEquals(1, GrowthFacts.tier(1000, noTiers));
    }

    @Test
    void nextTierWalksUpTheLadderAndStopsAtTheTop() {
        assertEquals(OptionalInt.of(3), GrowthFacts.nextTierThreshold(1, AFFECTION));
        assertEquals(OptionalInt.of(8), GrowthFacts.nextTierThreshold(3, AFFECTION));
        assertTrue(GrowthFacts.nextTierThreshold(8, AFFECTION).isEmpty(),
                "at the last tier there is nothing further to reach - a different sentence from '0 more'");
    }

    @Test
    void expFractionIsThePartOfTheRequirementThatIsFilled() {
        assertEquals(0.0, GrowthFacts.expFraction(0, 100), 1e-9);
        assertEquals(0.5, GrowthFacts.expFraction(50, 100), 1e-9);
        assertEquals(1.0, GrowthFacts.expFraction(100, 100), 1e-9);
    }

    @Test
    void expFractionNeverLeavesTheBar() {
        assertEquals(1.0, GrowthFacts.expFraction(250, 100), 1e-9,
                "a hand-edited save can hold more exp than the level needs; the bar just fills");
        assertEquals(0.0, GrowthFacts.expFraction(-5, 100), 1e-9);
        assertEquals(GrowthFacts.NO_PROGRESS, GrowthFacts.expFraction(50, 0), 1e-9,
                "no requirement means no bar, not a division by zero");
    }

    @Test
    void affectionFractionRunsToTheLastTier() {
        assertEquals(0.125, GrowthFacts.affectionFraction(1, AFFECTION), 1e-9,
                "a freshly enrolled character sits at affection 1 of 8");
        assertEquals(1.0, GrowthFacts.affectionFraction(8, AFFECTION), 1e-9);
        assertEquals(1.0, GrowthFacts.affectionFraction(12, AFFECTION), 1e-9);
    }

    @Test
    void aPackWithoutALastTierShowsAFullAffectionBar() {
        assertEquals(GrowthFacts.FULLY_GROWN,
                GrowthFacts.affectionFraction(1, new AffectionDef(3, 0, List.of())), 1e-9);
    }

    @Test
    void theSnapshotHelperAgreesWithTheLevelBasedCurve() {
        CharacterProgress progress = new CharacterProgress(2, 10, 1);
        assertEquals(GrowthFacts.expToNext(2, GROWTH), GrowthFacts.expToNext(progress, GROWTH));
    }
}
