package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.impl.InternalIds;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The earning half of levels (spec §6.1, §6.2): what a level costs, how a gain climbs the curve, how a
 * kill's experience is split, and what a level is worth.
 *
 * <h2>What these tests are really guarding</h2>
 * Two things, and neither is arithmetic for its own sake.
 *
 * <ol>
 *   <li><b>The curve is not restated here.</b> {@code ExperienceKernel.expToNext} is a delegation to
 *       {@link GrowthFacts}, and the delegating test below is written so a reimplementation fails:
 *       it runs a curve ({@code 7 / 1.7 / 9}) that no copy of the shipped {@code 100 / 1.15 / 30}
 *       would reproduce.</li>
 *   <li><b>Level-up carries the remainder.</b> The single most expensive thing to get wrong in this
 *       class: a 250-point gain at level 1 is level 3 with 35 left, not "level 2 with 250 in the
 *       bar", and not seven levels. The remainder loop is what the book's experience bar renders.</li>
 * </ol>
 *
 * <p>Curve and tier numbers come from the shipped {@code djeeta.json} via {@link ShippedGrowth}; the
 * literals in the assertions are the spec's worked examples (§6.4).
 */
class ExperienceKernelTest {

    private static final GrowthDef GROWTH = ShippedGrowth.growth();

    /** 姬塔's curve is the spec's: the fixture is only useful if it says so. */
    @Test
    void theShippedCurveIsTheOneTheSpecNames() {
        assertEquals(100, GROWTH.expBase());
        assertEquals(1.15, GROWTH.expPerLevel(), 1e-9);
        assertEquals(30, GROWTH.maxLevel());
    }

    // -----------------------------------------------------------------------------------------
    // The curve: a delegation to GrowthFacts, not a second implementation (plan RULING, Task 15).
    // -----------------------------------------------------------------------------------------

    @Test
    void firstLevelNeedsTheBase() {
        assertEquals(100, ExperienceKernel.expToNext(1, GROWTH));
    }

    @Test
    void theRequirementGrowsGeometrically() {
        assertEquals(115, ExperienceKernel.expToNext(2, GROWTH));
        assertEquals(132, ExperienceKernel.expToNext(3, GROWTH));
    }

    /**
     * The delegation, proven on a curve the shipped pack does not use: if this class ever grows its
     * own {@code expBase * expPerLevel^(level-1)}, these two answers stop agreeing — the book would
     * promise a level at a number combat does not award.
     */
    @Test
    void theKernelsCurveIsGrowthFactsCurve() {
        GrowthDef other = new GrowthDef(7, 1.7, 9);
        for (int level = 1; level <= 9; level++) {
            assertEquals(GrowthFacts.expToNext(level, other), ExperienceKernel.expToNext(level, other),
                    "level " + level + ": ExperienceKernel must delegate to GrowthFacts");
            assertEquals(GrowthFacts.expToNext(level, GROWTH), ExperienceKernel.expToNext(level, GROWTH),
                    "level " + level + ": the shipped curve must agree too");
        }
    }

    // -----------------------------------------------------------------------------------------
    // Level-up: the remainder crosses several levels in one gain.
    // -----------------------------------------------------------------------------------------

    @Test
    void aGainCarriesItsRemainderAcrossSeveralLevelUps() {
        var p = ExperienceKernel.add(new CharacterProgress(1, 0, 1), 250, GROWTH);
        // 250 - 100 (level 2) = 150; 150 - 115 (level 3) = 35; 35 < 132 (level 4) stops.
        assertEquals(3, p.level());
        assertEquals(35, p.exp());
    }

    @Test
    void aGainOfExactlyOneRequirementLandsWithAnEmptyBar() {
        var p = ExperienceKernel.add(new CharacterProgress(1, 0, 1), 100, GROWTH);
        assertEquals(2, p.level());
        assertEquals(0, p.exp());
        assertEquals(3, ExperienceKernel.add(new CharacterProgress(1, 0, 1), 215, GROWTH).level());
    }

    @Test
    void aGainBelowTheRequirementOnlyFillsTheBar() {
        var p = ExperienceKernel.add(new CharacterProgress(1, 40, 1), 59, GROWTH);
        assertEquals(1, p.level());
        assertEquals(99, p.exp());
    }

    @Test
    void levelUpKeepsAffectionAndTheStoredProgressIsNotMutated() {
        var before = new CharacterProgress(1, 0, 5);
        var after = ExperienceKernel.add(before, 250, GROWTH);
        assertEquals(5, after.affection(), "levelling must not reset the affection the player earned");
        assertEquals(new CharacterProgress(1, 0, 5), before, "the argument is a value, not a buffer");
    }

    // -----------------------------------------------------------------------------------------
    // The cap, and the edge cases that would otherwise loop or grow a save field forever.
    // -----------------------------------------------------------------------------------------

    @Test
    void aCharacterAtTheCapDoesNotGainAndDoesNotKeepASwellingNumber() {
        var p = ExperienceKernel.add(new CharacterProgress(30, 0, 4), 99_999, GROWTH);
        assertEquals(30, p.level());
        assertEquals(0, p.exp(), "at maxLevel there is no next level, so the bar must stay empty");
        assertEquals(4, p.affection());
    }

    @Test
    void anOvershootingGainStopsAtTheCapRatherThanClimbingPastIt() {
        var p = ExperienceKernel.add(new CharacterProgress(1, 0, 1), 1_000_000, GROWTH);
        assertEquals(30, p.level(), "the loop is bounded by maxLevel, not by the amount");
        assertEquals(0, p.exp());
    }

    @Test
    void aCurveWithNoBaseStopsInsteadOfSpinning() {
        // A hand-edited pack: expBase 0 means "this level needs nothing", which no loop may divide by.
        var p = ExperienceKernel.add(new CharacterProgress(1, 0, 1), 500, new GrowthDef(0, 1.15, 30));
        assertEquals(1, p.level(), "a curve that cannot be climbed must leave the level alone");
        assertEquals(500, p.exp(), "nothing can consume the gain, so it is carried, not silently dropped");
    }

    @Test
    void aCurveWithNoMaxLevelIsNotCappedAtOne() {
        var p = ExperienceKernel.add(new CharacterProgress(1, 0, 1), 100, new GrowthDef(100, 1.15, 0));
        assertEquals(2, p.level(), "maxLevel <= 0 means 'no cap', not 'cannot level'");
    }

    @Test
    void hostileStoredProgressIsNormalised() {
        var p = ExperienceKernel.add(new CharacterProgress(0, -30, 1), 0, GROWTH);
        assertEquals(1, p.level(), "there is no level 0");
        assertEquals(0, p.exp(), "a negative total is read as no progress");
    }

    @Test
    void aNonPositiveGainChangesNothingButNormalises() {
        var p = ExperienceKernel.add(new CharacterProgress(5, 40, 2), -1_000, GROWTH);
        assertEquals(5, p.level());
        assertEquals(40, p.exp());
        assertEquals(2, p.affection());
    }

    // -----------------------------------------------------------------------------------------
    // distribute: §6.1's split of a kill across the characters that did the damage.
    // -----------------------------------------------------------------------------------------

    @Test
    void theSplitIsProportionalToContributedDamage() {
        ResourceLocation a = InternalIds.id("djeeta");
        ResourceLocation b = InternalIds.id("katalina");
        Map<ResourceLocation, Integer> split = ExperienceKernel.distribute(100, Map.of(a, 90.0, b, 10.0));
        assertEquals(90, split.get(a));
        assertEquals(10, split.get(b));
    }

    @Test
    void aContributorIsNeverPaidNothing() {
        ResourceLocation tiny = InternalIds.id("tiny");
        Map<ResourceLocation, Integer> split =
                ExperienceKernel.distribute(100, Map.of(tiny, 0.0001, InternalIds.id("big"), 999.0));
        assertEquals(ExperienceKernel.CONTRIBUTOR_FLOOR, split.get(tiny),
                "a character that was there is never worth zero, however small its share");
    }

    /** A weight of 0.0 is still a contributor: the key exists, so the character was in the fight. */
    @Test
    void aZeroWeightContributorStillGetsTheFloor() {
        ResourceLocation a = InternalIds.id("djeeta");
        assertEquals(1, ExperienceKernel.distribute(10, Map.of(a, 0.0)).get(a));
    }

    @Test
    void noContributorsMeansNoDivisionAndNoRecipient() {
        assertTrue(ExperienceKernel.distribute(10, Map.of()).isEmpty());
        assertEquals(Map.of(), ExperienceKernel.distribute(10, null));
    }

    @Test
    void aNonPositivePoolPaysNobody() {
        ResourceLocation a = InternalIds.id("djeeta");
        assertTrue(ExperienceKernel.distribute(0, Map.of(a, 5.0)).isEmpty());
        assertTrue(ExperienceKernel.distribute(-50, Map.of(a, 5.0)).isEmpty());
    }

    /**
     * The documented price of the floor: splitting a pool smaller than the party pays out more than
     * the pool. Bounded by the five wheel slots, so it is a decision rather than a leak.
     */
    @Test
    void theFloorCanTotalMoreThanThePool() {
        Map<ResourceLocation, Double> five = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            five.put(InternalIds.id("c" + i), 1.0);
        }
        Map<ResourceLocation, Integer> split = ExperienceKernel.distribute(3, five);
        assertEquals(5, split.size());
        assertEquals(1, split.get(InternalIds.id("c0")));
        assertEquals(5, split.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void hostileWeightsCountAsNoWeightAndDoNotPoisonTheSplit() {
        ResourceLocation sane = InternalIds.id("sane");
        ResourceLocation negative = InternalIds.id("negative");
        ResourceLocation nan = InternalIds.id("nan");
        Map<ResourceLocation, Double> contributions = new LinkedHashMap<>();
        contributions.put(sane, 100.0);
        contributions.put(negative, -5.0);
        contributions.put(nan, Double.NaN);
        Map<ResourceLocation, Integer> split = ExperienceKernel.distribute(100, contributions);
        assertEquals(100, split.get(sane));
        assertEquals(1, split.get(negative));
        assertEquals(1, split.get(nan));
    }

    @Test
    void theSplitIsAnImmutableValue() {
        ResourceLocation a = InternalIds.id("djeeta");
        var split = ExperienceKernel.distribute(10, Map.of(a, 1.0));
        assertThrows(UnsupportedOperationException.class, () -> split.put(InternalIds.id("x"), 1));
    }

    // -----------------------------------------------------------------------------------------
    // What a kill is worth, and what a level is worth.
    // -----------------------------------------------------------------------------------------

    @Test
    void aKillIsWorthItsVictimsHealthTimesTheRate() {
        assertEquals(10, ExperienceKernel.killExp(20.0), "a 20-health zombie is a tenth of level 1");
        assertEquals(ExperienceKernel.CONTRIBUTOR_FLOOR, ExperienceKernel.killExp(0.0));
        assertEquals(ExperienceKernel.CONTRIBUTOR_FLOOR, ExperienceKernel.killExp(Double.NaN));
        assertEquals(ExperienceKernel.CONTRIBUTOR_FLOOR, ExperienceKernel.killExp(-5.0));
        assertEquals(Integer.MAX_VALUE, ExperienceKernel.killExp(Double.MAX_VALUE));
    }

    @Test
    void aLevelAddsASmallLinearMultiplier() {
        assertEquals(1.0, ExperienceKernel.levelMultiplier(1, GROWTH), 1e-9);
        assertEquals(1.0, ExperienceKernel.levelMultiplier(0, GROWTH), 1e-9, "no level 0: read as 1");
        assertEquals(1.0 + ExperienceKernel.LEVEL_GROWTH_PER_LEVEL * 14,
                ExperienceKernel.levelMultiplier(15, GROWTH), 1e-9);
        assertEquals(1.58, ExperienceKernel.levelMultiplier(30, GROWTH), 1e-9,
                "spec §6.4's cap of 30 makes the multiplier 1.58");
        assertEquals(1.58, ExperienceKernel.levelMultiplier(999, GROWTH), 1e-9,
                "a hand-edited level above maxLevel is clamped, not multiplied");
    }

    /** A character whose progress this build cannot read is neutral, not off. */
    @Test
    void anUnknownCharacterIsNeutralRatherThanSwitchedOff() {
        assertEquals(1.0, ExperienceKernel.levelMultiplier(null, GROWTH), 1e-9);
        assertEquals(ExperienceKernel.levelMultiplier(15, GROWTH),
                ExperienceKernel.levelMultiplier(new CharacterProgress(15, 0, 1), GROWTH), 1e-9);
    }
}
