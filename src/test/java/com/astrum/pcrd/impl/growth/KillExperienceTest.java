package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.impl.InternalIds;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals(1.0, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth), 1e-9);
    }

    /** A level raises the multiplier linearly by {@link ExperienceKernel#LEVEL_GROWTH_PER_LEVEL}. */
    @Test
    void aLevelRaisesTheMultiplier() {
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(16, 0, 1),
                definition(AFFECTION));
        assertEquals(1.0 + ExperienceKernel.LEVEL_GROWTH_PER_LEVEL * 15, growth.level(), 1e-9);
        assertEquals(growth.level(),
                com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth), 1e-9,
                "with no diminishing and no tier-2 modifier, the chain is the level factor alone");
    }

    /**
     * <b>A tier no longer multiplies anything.</b>
     *
     * <p>This method used to be {@code tierTwoScalesOnlyTheListedHook}: the pack listed a hook index and a
     * factor, and the chain applied the factor to that hook alone. That model is gone. A tier now
     * <em>selects a skill set</em>, so the multiplier is a function of the level only — and the selection is
     * asserted where it belongs, in {@code AffectionKernelTest} and
     * {@code ThreeCharactersResourceTest.djeetaRotatesWideSlashThenWeaponBurst}.
     *
     * <p>The assertion is kept as a <b>negative</b> one on purpose: a future edit that reintroduced an
     * affection factor into the chain would change this number, and this is the only place that would notice.
     */
    @Test
    void aTierDoesNotMultiplyAnythingAnyMore() {
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(1, 0, 3),
                definition(AFFECTION));
        assertEquals(2, growth.tier(), "affection 3 is the shipped tier-2 threshold");
        assertEquals(1.0, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth), 1e-9,
                "tier 2 replaces skills; it does not scale them");
    }

    /**
     * Tier 3 casts the enhanced ultimate and runs the 6★ skill set — neither of which is a multiplier.
     *
     * <h2>Why this is where the old "keeps tier 2's numbers" test went</h2>
     * That test asserted the tier-2 modifier survived into tier 3. There is no modifier now; what tier 3
     * actually does is swap the ultimate <em>and</em> resolve the ladders a rung deeper. Both halves are
     * asserted here, on the shipped 姬塔 data, so "tier 3 is strictly above tier 2" stays a claim with
     * evidence.
     */
    @Test
    void tierThreeSwapsTheUltimateAndDeepensTheSkillSet() throws Exception {
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(1, 0, 8), definition(AFFECTION));
        assertEquals(3, growth.tier());
        assertTrue(AffectionKernel.usesEnhancedUb(growth.tier()));

        com.astrum.pcrd.api.v1.CharacterDef djeeta = shippedDjeeta();
        assertEquals("黄金耀光",
                com.astrum.pcrd.impl.growth.AffectionKernel.ladderFor(djeeta, "wide_slash")
                        .orElseThrow().nameAt(growth.tier()),
                "tier 3 does not scale 广域斩击+, it renames it");
        assertEquals(20.0, firstDamage(
                com.astrum.pcrd.impl.growth.AffectionKernel.hooksAt(djeeta, growth.tier())), 1e-9,
                "and the body is 黄金耀光's 4.8 coefficient, not 广域斩击+'s 2.25");
    }

    /** {@return the shipped 姬塔 definition, read through the real codec} */
    private static com.astrum.pcrd.api.v1.CharacterDef shippedDjeeta() throws Exception {
        String path = "/data/astrum/" + net.minecraft.core.registries.Registries.elementsDirPath(
                com.astrum.pcrd.api.v1.RegistryKeys.CHARACTERS) + "/djeeta.json";
        try (java.io.InputStream in = KillExperienceTest.class.getResourceAsStream(path)) {
            var json = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            return com.astrum.pcrd.api.v1.CharacterDef.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    /** {@return the first {@code extra_damage} amount in {@code hooks}} */
    private static double firstDamage(java.util.List<com.astrum.pcrd.api.v1.EffectHook> hooks) {
        for (com.astrum.pcrd.api.v1.EffectHook hook : hooks) {
            if (hook instanceof com.astrum.pcrd.api.v1.EffectHook.ExtraDamage damage) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    /** An unknown character is neutral rather than switched off. */
    @Test
    void anUnknownCharacterIsNeutral() {
        assertEquals(GrowthMultiplier.NEUTRAL, GrowthMultiplier.of(null, definition(AFFECTION)));
        assertEquals(GrowthMultiplier.NEUTRAL, GrowthMultiplier.of(new CharacterProgress(5, 0, 8), null));
        assertEquals(1.0, GrowthMultiplier.NEUTRAL.level(), 1e-9);
        assertEquals(0.7, com.astrum.pcrd.impl.combat.MultiplierChain.effective(
                0.7, GrowthMultiplier.NEUTRAL), 1e-9,
                "neutral growth must change nothing: the party multiplier passes through untouched");
    }

    /**
     * A definition with no tier 2 and no tier 3 resolves to tier 1, so it runs her <b>base</b> skill set.
     *
     * <p>This used to assert "nothing is scaled". With the multiplier gone the assertion is the same fact in
     * the vocabulary that remains: a threshold of {@code 0} can never be reached, so the tier is 1 and
     * {@code AffectionKernel.hooksAt} answers her untiered skills.
     */
    @Test
    void aDefinitionWithoutTiersRunsTheBaseSkillSet() {
        AffectionDef bare = new AffectionDef(0, 0, List.of());
        CharacterDef def = definition(bare);
        GrowthMultiplier growth = GrowthMultiplier.of(new CharacterProgress(1, 0, 99), def);
        assertEquals(GrowthFacts.FIRST_TIER, growth.tier(), "threshold 0 can never be reached");
        assertSame(def.effects(), com.astrum.pcrd.impl.growth.AffectionKernel.hooksAt(def, growth.tier()),
                "no reachable tier means her base hooks, untouched");
        assertEquals(1.0, com.astrum.pcrd.impl.combat.MultiplierChain.effective(1.0, growth), 1e-9);
    }

    /** A minimum {@link CharacterDef} carrying only the two halves growth reads. */
    private static CharacterDef definition(AffectionDef affection) {
        return new CharacterDef(1, "test", com.astrum.pcrd.api.v1.Dimension.CHARGE,
                java.util.Optional.empty(), java.util.Optional.empty(), List.of(),
                new com.astrum.pcrd.api.v1.UbDef(
                        new com.astrum.pcrd.api.v1.UbTier("base", 1, 20.0,
                                java.util.OptionalDouble.empty(), List.of()),
                        new com.astrum.pcrd.api.v1.UbTier("enhanced", 4, 20.0,
                                java.util.OptionalDouble.empty(), List.of())),
                GROWTH, affection,
                java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty());
    }
}
