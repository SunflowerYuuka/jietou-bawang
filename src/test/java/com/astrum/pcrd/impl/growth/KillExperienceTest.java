package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.api.v1.HookModifier;
import com.astrum.pcrd.impl.InternalIds;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two wirings that are arithmetic: what a kill is worth and how it is split, and what a level and
 * a tier are worth as a multiplier.
 *
 * <h2>Why these are tests and not just lines in an event handler</h2>
 * Both rules are one design decision each — "the pool comes from the victim's <b>maximum</b> health"
 * and "growth multiplies the party multiplier" — and both would otherwise live inside
 * {@code CombatEvents}, where nothing can be asserted without a server. {@code ShippedGrowth} reads the
 * real {@code djeeta.json}, so the worked examples below are the numbers the mod actually ships rather
 * than a copy of the design that could drift from it.
 *
 * <p>The split itself ({@link ExperienceKernel#distribute}) and the curve
 * ({@link ExperienceKernel#add}) have their own tests; what is pinned here is only the composition.
 */
class KillExperienceTest {

    /** 姬塔's shipped curve, read from the datapack. */
    private static final GrowthDef GROWTH = ShippedGrowth.growth();

    /** 姬塔's shipped affection: tier 2 at 3, tier 3 at 8, hook 1 × 1.5 at tier 2. */
    private static final AffectionDef AFFECTION = ShippedGrowth.affection();

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KATALINA = InternalIds.id("katalina");

    // ---------------------------------------------------------------------------------------------
    // Kill → exp
    // ---------------------------------------------------------------------------------------------

    /**
     * The formula, as one worked example: a 20-health victim is worth
     * {@code round(20 × 0.5) = 10} experience, and a character that did all the damage takes all of it.
     */
    @Test
    void aSoloKillPaysTheVictimsHealthTimesTheRate() {
        Map<ResourceLocation, Integer> shares =
                KillExperience.award(20.0, Map.of(DJEETA, 6.0));
        assertEquals(10, shares.get(DJEETA), "20 health x 0.5 = 10 exp, all to the only contributor");
        assertEquals(1, shares.size());
    }

    /** Two characters split by the damage they dealt, floored at 1 each. */
    @Test
    void aSharedKillSplitsByContribution() {
        Map<ResourceLocation, Integer> shares =
                KillExperience.award(20.0, Map.of(DJEETA, 9.0, KATALINA, 1.0));
        assertEquals(9, shares.get(DJEETA), "90% of a 10-point pool");
        assertEquals(1, shares.get(KATALINA), "10% floors to 1 rather than 0");
    }

    /**
     * A bigger victim is worth more, and a tiny one is worth the floor: the point of scaling with
     * {@code maxHealth} is that a chicken is not a wither.
     */
    @Test
    void thePoolScalesWithTheVictimsMaximumHealth() {
        assertTrue(KillExperience.award(100.0, Map.of(DJEETA, 1.0)).get(DJEETA)
                        > KillExperience.award(20.0, Map.of(DJEETA, 1.0)).get(DJEETA),
                "a 100-health victim must out-pay a 20-health one");
        assertEquals(ExperienceKernel.CONTRIBUTOR_FLOOR,
                KillExperience.award(0.0, Map.of(DJEETA, 1.0)).get(DJEETA),
                "a victim with no health pool still pays the floor, never zero");
    }

    /** Nobody contributed → nobody is paid, and no recipient is invented. */
    @Test
    void noContributorIsPaidNothing() {
        assertTrue(KillExperience.award(20.0, Map.of()).isEmpty());
        assertTrue(KillExperience.award(20.0, null).isEmpty());
    }

    /**
     * The whole point of the floor: a character that scratched the victim still earns something, so
     * "you helped" is never "you got nothing" — even when the pool is small enough to floor everyone.
     */
    @Test
    void everyContributorEarnsAtLeastTheFloor() {
        Map<ResourceLocation, Integer> shares =
                KillExperience.award(2.0, Map.of(DJEETA, 50.0, KATALINA, 50.0));
        assertEquals(1, shares.get(DJEETA));
        assertEquals(1, shares.get(KATALINA));
    }

    /**
     * A kill's experience, fed through the kernel, is what actually moves a level: 10 solo kills of a
     * 20-health mob take 姬塔 from level 1 (needing 100) to level 2.
     */
    @Test
    void tenKillsOfAZombieLevelsTheCharacterFromOneToTwo() {
        CharacterProgress progress = new CharacterProgress(1, 0, 1);
        for (int kill = 0; kill < 10; kill++) {
            int share = KillExperience.award(20.0, Map.of(DJEETA, 6.0)).get(DJEETA);
            progress = ExperienceKernel.add(progress, share, GROWTH);
        }
        assertEquals(2, progress.level(), "100 exp is exactly level 1's requirement");
        assertEquals(0, progress.exp(), "and nothing is carried");
    }

    // ---------------------------------------------------------------------------------------------
    // Growth → multiplier
    // ---------------------------------------------------------------------------------------------

    /**
     * Level 1, tier 1 is the neutral case: a freshly enrolled character changes nothing.
     * That is §6.2's 白板 tier stated as a number.
     */
    @Test
    void aFreshCharacterHasANeutralMultiplier() {
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(1, 0, 1),
                definition(AFFECTION));
        assertEquals(1.0, growth.level(), 1e-9);
        assertEquals(1, growth.tier());
        assertEquals(1.0, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth, 1), 1e-9);
    }

    /** A level raises the multiplier linearly by {@link ExperienceKernel#LEVEL_GROWTH_PER_LEVEL}. */
    @Test
    void aLevelRaisesTheMultiplier() {
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(16, 0, 1),
                definition(AFFECTION));
        assertEquals(1.0 + ExperienceKernel.LEVEL_GROWTH_PER_LEVEL * 15, growth.level(), 1e-9);
        assertEquals(growth.level(),
                com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth, 0), 1e-9,
                "with no diminishing and no tier-2 modifier, the chain is the level factor alone");
    }

    /**
     * The tier-2 rule in one place: the hook the pack listed goes up by its declared factor, a hook it
     * did not list stays exactly where it was (spec §6.2's 只改数值不改数量).
     */
    @Test
    void tierTwoScalesOnlyTheListedHook() {
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(1, 0, 3),
                definition(AFFECTION));
        assertEquals(2, growth.tier(), "affection 3 is the shipped tier-2 threshold");
        assertEquals(1.5, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth, 1), 1e-9,
                "hook index 1 is the one the pack lists at 1.5");
        assertEquals(1.0, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth, 0), 1e-9,
                "hook index 0 was not listed, so it must not move");
        assertEquals(1.0, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth, 2), 1e-9);
    }

    /**
     * Tier 3 is above tier 2: the character keeps the numbers it earned <b>and</b> is flagged for the
     * enhanced ultimate. Losing the tier-2 modifier on the way to tier 3 would be a downgrade.
     */
    @Test
    void tierThreeKeepsTheTierTwoNumbersAndFlagsTheEnhancedUb() {
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(1, 0, 8),
                definition(AFFECTION));
        assertEquals(3, growth.tier());
        assertEquals(1.5, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth, 1), 1e-9,
                "tier 3 must not lose tier 2's hook modifier");
        assertTrue(AffectionKernel.usesEnhancedUb(growth.tier()));
    }

    /** An unknown character is neutral rather than switched off. */
    @Test
    void anUnknownCharacterIsNeutral() {
        assertEquals(GrowthMultiplier.NEUTRAL, GrowthMultiplier.of(null, definition(AFFECTION)));
        assertEquals(GrowthMultiplier.NEUTRAL, GrowthMultiplier.of(new CharacterProgress(5, 0, 8), null));
        assertEquals(1.0, GrowthMultiplier.NEUTRAL.level(), 1e-9);
        assertEquals(0.7, com.astrum.pcrd.impl.combat.MultiplierChain.effective(
                0.7, GrowthMultiplier.NEUTRAL, 1), 1e-9,
                "neutral growth must change nothing: the party multiplier passes through untouched");
    }

    /** A definition with no tier 2 and no tier 3 scales nothing, whatever the affection is. */
    @Test
    void aDefinitionWithoutTiersScalesNothing() {
        AffectionDef bare = new AffectionDef(0, 0, List.of());
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(1, 0, 99), definition(bare));
        assertEquals(GrowthFacts.FIRST_TIER, growth.tier(), "threshold 0 can never be reached");
        assertEquals(1.0, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth, 1), 1e-9);
    }

    /** A minimum {@link CharacterDef} carrying only the two halves growth reads. */
    private static CharacterDef definition(AffectionDef affection) {
        return new CharacterDef(1, "test", com.astrum.pcrd.api.v1.Dimension.CHARGE,
                java.util.Optional.empty(), java.util.Optional.empty(), List.of(),
                new com.astrum.pcrd.api.v1.UbDef(
                        new com.astrum.pcrd.api.v1.UbTier("base", 1, 20.0,
                                java.util.OptionalDouble.empty(), java.util.OptionalDouble.empty(), 0,
                                java.util.OptionalDouble.empty(), java.util.Optional.empty()),
                        new com.astrum.pcrd.api.v1.UbTier("enhanced", 4, 20.0,
                                java.util.OptionalDouble.empty(), java.util.OptionalDouble.empty(), 0,
                                java.util.OptionalDouble.empty(), java.util.Optional.empty())),
                GROWTH, affection);
    }
}
