package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.astrum.pcrd.api.v1.SkillUpgrade;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.api.v1.UbDef;
import com.astrum.pcrd.api.v1.UbTier;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The affection ladder: which tier a number is in, and <b>which skill set that tier runs</b>.
 *
 * <h2>What replaced what</h2>
 * This file used to pin {@code hookMultiplier(def, hookIndex, tier)} — tier 2 scaled the hooks the pack named,
 * by a factor. That model is gone (see {@link AffectionKernel}). The three properties it used to assert are
 * still worth asserting, but the third is now a different question:
 *
 * <ol>
 *   <li><b>The ladder itself is {@link GrowthFacts}'</b>, and {@code AffectionKernel.tier} delegates — one
 *       implementation, asserted by {@code AffectionKernelTest.tierIsTheGrowthFactsLadder}.</li>
 *   <li><b>Tier 1 is the blank tier</b> — no hook changes, which is now "the resolved list is the same list
 *       instance" rather than "every multiplier is 1.0".</li>
 *   <li><b>Tier 3 supersedes tier 2's {@code +}</b> — the human's rule, and the reason the ladder is data.
 *       This is the assertion the old file could not make at all.</li>
 * </ol>
 *
 * <h2>And the four shipped characters, at all three tiers</h2>
 * {@link #djeetaTierThreeRenamesBeatTierTwoPluses} and its siblings read the <b>shipped JSON</b> through the
 * real codec, because that is what a future author edits. A drift between the data and this understanding is
 * then a failing test rather than a surprise in play.
 */
class AffectionKernelTest {

    private static final AffectionDef AFFECTION = AffectionDef.thresholds(3, 8);

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = AffectionKernelTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be on the test classpath: " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    // ------------------------------------------------------------------ the ladder

    /**
     * {@code AffectionKernel.tier} is {@code GrowthFacts.tier} and nothing else.
     *
     * <p>The point is not that two methods agree today; it is that there is one implementation of "which tier
     * is this number in". A book that says tier 2 while the damage pipeline runs tier 1 is the duplication
     * this project has shipped three times, and it starts with a second copy of this ladder.
     */
    @Test
    void tierIsTheGrowthFactsLadder() {
        for (int affection = 0; affection <= 12; affection++) {
            assertEquals(GrowthFacts.tier(affection, AFFECTION), AffectionKernel.tier(affection, AFFECTION),
                    "affection " + affection);
        }
        assertEquals(1, AffectionKernel.tier(2, AFFECTION), "2 is one short of tier 2's threshold");
        assertEquals(2, AffectionKernel.tier(3, AFFECTION), "3 is tier 2 exactly");
        assertEquals(2, AffectionKernel.tier(7, AFFECTION), "7 is one short of tier 3's threshold");
        assertEquals(3, AffectionKernel.tier(8, AFFECTION), "8 is tier 3 exactly");
    }

    /** A threshold of {@code 0} is "the pack defines no such tier", so it can never be reached. */
    @Test
    void aZeroThresholdDefinesNoTier() {
        AffectionDef noTier3 = AffectionDef.thresholds(3, 0);
        assertEquals(2, AffectionKernel.tier(1_000, noTier3), "no tier 3 means tier 2 is the top");
        assertFalse(AffectionKernel.usesEnhancedUb(AffectionKernel.tier(1_000, noTier3)));
    }

    /** Tier 3 casts the enhanced ultimate; {@code >=} so a save above the ladder still gets it. */
    @Test
    void tierThreeUsesTheEnhancedUltimate() {
        assertTrue(AffectionKernel.usesEnhancedUb(3));
        assertFalse(AffectionKernel.usesEnhancedUb(2), "tier 2 is the 専武 rung, not an ultimate rung");
        assertFalse(AffectionKernel.usesEnhancedUb(1));
        assertTrue(AffectionKernel.usesEnhancedUb(4), "a save above the last tier still gets the better UB");
    }

    /** The affection cap: floored at 0, capped at the tier-3 threshold, and overflow-safe. */
    @Test
    void affectionIsClampedToTheLadderAndCannotOverflow() {
        assertEquals(2, AffectionKernel.clampAffection(1, 1, 8));
        assertEquals(8, AffectionKernel.clampAffection(7, 1, 8));
        assertEquals(8, AffectionKernel.clampAffection(8, 1, 8), "already at the top: nothing more to earn");
        assertEquals(0, AffectionKernel.clampAffection(0, -5, 8));
        assertEquals(0, AffectionKernel.clampAffection(2, -2, 8));
        assertEquals(5, AffectionKernel.clampAffection(5, 0, 8));
        assertEquals(15, AffectionKernel.clampAffection(5, 10, 0), "no tier 3: nothing to cap to");
        assertEquals(8, AffectionKernel.clampAffection(Integer.MAX_VALUE, Integer.MAX_VALUE, 8));
        assertEquals(Integer.MAX_VALUE,
                AffectionKernel.clampAffection(Integer.MAX_VALUE, Integer.MAX_VALUE, 0),
                "widened to long before adding, so two max ints cannot wrap negative");
    }

    // ------------------------------------------------------------------ the ladder as data

    /**
     * <b>Tier 3 supersedes tier 2's {@code +}</b> — the human's rule, in one assertion.
     *
     * <p>「A rename supersedes a {@code +}: if 姬塔's 技能1 has both 广域斩击+ (tier 2) and 黄金耀光 (tier 3), at tier 3
     * it is 黄金耀光, not 广域斩击+.」
     *
     * <p>Written against {@link SkillLadder#nameAt} because that is where the ordering lives: asking tier 3
     * <b>first</b> and only falling back to tier 2 <i>is</i> the rule. A ladder that asked tier 2 first would
     * show the {@code +} name at 6★ — the exact defect, and one that would look like a data-entry mistake
     * forever.
     */
    @Test
    void tierThreeRenamesBeatTierTwoPluses() {
        SkillLadder ladder = new SkillLadder("wide_slash", "广域斩击",
                Optional.of(SkillUpgrade.named("广域斩击+")),
                Optional.of(SkillUpgrade.named("黄金耀光")));
        assertEquals("广域斩击", ladder.nameAt(1), "tier 1 is the base name");
        assertEquals("广域斩击+", ladder.nameAt(2), "tier 2 gains the plus");
        assertEquals("黄金耀光", ladder.nameAt(3), "tier 3 renames, and the rename wins");
        assertEquals("黄金耀光", ladder.nameAt(4), "above the last tier still resolves to the top rung");
    }

    /** A ladder with no tier 3 keeps showing its {@code +} at tier 3 — コッコロ's 技能1 does not rename at 6★. */
    @Test
    void aLadderWithoutARenameKeepsItsPlusAtTierThree() {
        SkillLadder ladder = new SkillLadder("triple_slash", "掠阵三段击",
                Optional.of(SkillUpgrade.named("掠阵三段击+")), Optional.empty());
        assertEquals("掠阵三段击", ladder.nameAt(1));
        assertEquals("掠阵三段击+", ladder.nameAt(2));
        assertEquals("掠阵三段击+", ladder.nameAt(3));
    }

    /** A ladder with nothing at all never changes: one name, every tier. */
    @Test
    void aFixedLadderNeverMoves() {
        SkillLadder ladder = SkillLadder.fixed("speed_up", "加速");
        for (int tier = 1; tier <= 3; tier++) {
            assertEquals("加速", ladder.nameAt(tier), "tier " + tier);
            assertTrue(ladder.upgradeAt(tier).isEmpty());
        }
    }

    /** A blank rung name is not a name: the base name is used, rather than an empty row in the book. */
    @Test
    void aBlankRungNameFallsBackToTheBaseName() {
        SkillLadder ladder = new SkillLadder("ex", "龙卷的攻刃",
                Optional.of(new SkillUpgrade("   ", List.of())), Optional.empty());
        assertEquals("龙卷的攻刃", ladder.nameAt(2), "a blank tier-2 name is treated as absent");
    }

    // ------------------------------------------------------------------ the resolution, on real data

    /**
     * <b>姬塔 at all three tiers</b>: 技能1 is 广域斩击 → 广域斩击+ → 黄金耀光, and the tier-3 body is the 4.8
     * coefficient, not a scaled 2.25.
     *
     * <h2>Why the damage number is asserted, not just the name</h2>
     * A name-only test would pass on data where the {@code +} rung's <em>effects</em> were never written —
     * the book would say 黄金耀光 and the swing would still do 6.7. So each tier's damage is checked against
     * its own bwiki formula (§14 of {@code docs/balance-derivation.md}) = 20.0 × raw / 22140, at the stated
     * reference attack 2000 and level 208.
     */
    @Test
    void djeetaTierThreeRenamesBeatTierTwoPluses() throws Exception {
        CharacterDef djeeta = load("djeeta");
        assertEquals("广域斩击", nameOf(djeeta, "wide_slash", 1));
        assertEquals("广域斩击+", nameOf(djeeta, "wide_slash", 2));
        assertEquals("黄金耀光", nameOf(djeeta, "wide_slash", 3), "the rename beats the plus");

        assertEquals(6.7, damageAt(djeeta, "wide_slash", 1), 1e-9, "1.6A+20+20L = 7380 -> 6.7");
        assertEquals(9.4, damageAt(djeeta, "wide_slash", 2), 1e-9, "2.25A+28+28L = 10352 -> 9.4");
        assertEquals(20.0, damageAt(djeeta, "wide_slash", 3), 1e-9, "4.8A+60+60L = 22140 -> 20.0");

        // 技能2: 剑意迸发 has no 専武 version, so tier 2 is unchanged; tier 3 renames to 三重剑击.
        assertEquals("剑意迸发", nameOf(djeeta, "weapon_burst", 1));
        assertEquals("剑意迸发", nameOf(djeeta, "weapon_burst", 2), "her 技能2 has no PLUS rung");
        assertEquals("三重剑击", nameOf(djeeta, "weapon_burst", 3));
        assertEquals(List.of(EffectType.GAIN_CHARGE), typesAt(djeeta, "weapon_burst", 1));
        assertEquals(List.of(EffectType.EXTRA_DAMAGE, EffectType.GAIN_CHARGE),
                typesAt(djeeta, "weapon_burst", 3), "三重剑击 is damage + a bigger TP grant");
        assertEquals(12.5, damageAt(djeeta, "weapon_burst", 3), 1e-9, "3A+37.5+37.5L = 13837.5 -> 12.5");

        // EX: named at every rung, applied at none (no battle-start trigger), and still visible as a name.
        assertEquals("龙卷的攻刃", nameOf(djeeta, "ex", 1));
        assertEquals("龙卷的攻刃+", nameOf(djeeta, "ex", 2));
        assertEquals("剑圣的天赋", nameOf(djeeta, "ex", 3), "her EX renames at 6 star");
    }

    /**
     * <b>コッコロ</b>: only her EX changes at tier 2, and her 技能1 has a 専武 rung the human's table does not
     * put on the ladder — so at every tier her 技能1 is the same skill.
     */
    @Test
    void kokkoroOnlyRenamesHerExAtTierTwo() throws Exception {
        CharacterDef kokkoro = load("kokkoro");
        assertEquals("精灵的启示", nameOf(kokkoro, "ex", 1));
        assertEquals("精灵的启示+", nameOf(kokkoro, "ex", 2));
        assertEquals("精灵的启示+", nameOf(kokkoro, "ex", 3), "she has no 6 star EX rename");
        assertEquals("掠阵三段击", nameOf(kokkoro, "triple_slash", 1));
        assertEquals("掠阵三段击", nameOf(kokkoro, "triple_slash", 3),
                "the ladder carries no 専武 rung for this skill — see balance-derivation §14.6");
        assertEquals(6.7, damageAt(kokkoro, "triple_slash", 3), 1e-9, "1.6A+20+20L = 7380 -> 6.7");
    }

    /**
     * <b>ペコリーヌ</b>: tier 2 adds the 専武 clause set (including the shield), tier 3 renames only her ultimate.
     */
    @Test
    void pecorineTierTwoAddsTheUniqueWeaponClauses() throws Exception {
        CharacterDef pecorine = load("pecorine");
        assertEquals("午餐时间", nameOf(pecorine, "lunch_time", 1));
        assertEquals("午餐时间+", nameOf(pecorine, "lunch_time", 2));
        assertEquals("午餐时间+", nameOf(pecorine, "lunch_time", 3), "no 6 star rename for her 技能1");

        assertEquals(List.of(EffectType.HEAL_SELF, EffectType.BUFF_MAGIC_DEFENCE_AREA),
                typesAt(pecorine, "lunch_time", 1));
        assertEquals(List.of(EffectType.BUFF_MAGIC_DEFENCE_AREA, EffectType.HEAL_SELF, EffectType.SHIELD),
                typesAt(pecorine, "lunch_time", 2),
                "the 専武 version gains 「可吸收…物理魔法伤害的护盾」 — that is what SHIELD is for");
        assertEquals("公主之力", nameOf(pecorine, "ex", 1));
        assertEquals("公主之力+", nameOf(pecorine, "ex", 2));
        assertEquals(6.7, damageAt(pecorine, "heavy_blow", 3), 1e-9, "1.6A+20+20L = 7380 -> 6.7");
    }

    /**
     * <b>キャル</b>: tier 2 gains the 専武 clause set (range magic damage + 魔防ダウン), tier 3 renames her ultimate.
     */
    @Test
    void kyaruTierTwoAddsRangeMagicAndAMagicDefenceDebuff() throws Exception {
        CharacterDef kyaru = load("kyaru");
        assertEquals("闪电球", nameOf(kyaru, "lightning_orb", 1));
        assertEquals("闪电球+", nameOf(kyaru, "lightning_orb", 2));
        assertEquals("闪电球+", nameOf(kyaru, "lightning_orb", 3), "no 6 star rename for her 技能1");

        assertEquals(List.of(EffectType.EXTRA_DAMAGE), typesAt(kyaru, "lightning_orb", 1));
        assertEquals(List.of(EffectType.EXTRA_DAMAGE, EffectType.SPLASH, EffectType.MAGIC_DEFENCE_DOWN),
                typesAt(kyaru, "lightning_orb", 2),
                "「对前方范围所有敌人造成魔法伤害 + 降低其魔法防御力」 — that is what MAGIC_DEFENCE_DOWN is for");
        assertEquals("黑暗之蚀", nameOf(kyaru, "ex", 1));
        assertEquals("黑暗之蚀+", nameOf(kyaru, "ex", 2));
        assertEquals("护甲削弱", nameOf(kyaru, "armour_break", 3), "her 技能2 never changes");
    }

    // ------------------------------------------------------------------ mechanics of the resolution

    /** Tier 1 hands back the <b>same list instance</b>: the blank tier costs nothing and allocates nothing. */
    @Test
    void tierOneIsTheBaseListUnchanged() throws Exception {
        CharacterDef djeeta = load("djeeta");
        assertSame(djeeta.effects(), AffectionKernel.hooksAt(djeeta, 1),
                "tier 1 must not even copy the list");
    }

    /** A tier that changes something hands back a <b>different</b> list — the upgrade really took effect. */
    @Test
    void anUpgradedTierIsADifferentList() throws Exception {
        CharacterDef djeeta = load("djeeta");
        assertNotSame(djeeta.effects(), AffectionKernel.hooksAt(djeeta, 2));
        assertNotSame(AffectionKernel.hooksAt(djeeta, 2), AffectionKernel.hooksAt(djeeta, 3));
    }

    /**
     * <b>The rotation's period does not change with the tier.</b>
     *
     * <p>This is the property that lets {@code SkillRotation} stay the single rotation implementation: the
     * resolved set has the same <em>skills</em>, in the same order, so a cursor means the same thing before and
     * after a tier change. A ladder whose rung reordered or dropped a skill would silently change which skill a
     * given cursor lands on — a player would upgrade her affection and start casting the wrong skill.
     */
    @Test
    void theRotationPeriodIsTheSameAtEveryTier() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            CharacterDef def = load(id);
            List<String> tier1 = skillOrder(AffectionKernel.hooksAt(def, 1));
            List<String> tier2 = skillOrder(AffectionKernel.hooksAt(def, 2));
            List<String> tier3 = skillOrder(AffectionKernel.hooksAt(def, 3));
            assertEquals(tier1, tier2, id + ": tier 2 must not change the skill order");
            assertEquals(tier1, tier3, id + ": tier 3 must not change the skill order");
        }
    }

    /** A {@code null} definition is empty, not an exception: this is on a damage path. */
    @Test
    void aNullDefinitionResolvesToNothing() {
        assertEquals(List.of(), AffectionKernel.hooksAt(null, 2));
        assertEquals(Optional.empty(), AffectionKernel.ladderFor(null, "wide_slash"));
    }

    /** A ladder for a skill no hook carries is simply never matched — harmless, and not an error. */
    @Test
    void aLadderForAnUnknownSkillIsIgnored() {
        CharacterDef def = new CharacterDef(1, "probe", Dimension.OUTPUT, Optional.empty(), Optional.empty(),
                List.of(new EffectHook.ExtraDamage(Trigger.MELEE_HIT, skillParams("real"), 1.0, 5.0)),
                new UbDef(new UbTier("a", 1, 1.0, OptionalDouble.empty(), List.of()),
                        new UbTier("b", 1, 1.0, OptionalDouble.empty(), List.of())),
                new GrowthDef(100, 1.15, 30),
                new AffectionDef(3, 8, List.of(new SkillLadder("typo", "Never",
                        Optional.of(SkillUpgrade.named("Never+")), Optional.empty()))),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        List<EffectHook> hooks = AffectionKernel.hooksAt(def, 3);
        assertEquals(1, hooks.size());
        assertEquals(5.0, ((EffectHook.ExtraDamage) hooks.get(0)).amount(), 1e-9,
                "the unmatched ladder must not touch a hook it does not name");
    }

    // ------------------------------------------------------------------ helpers

    private static TriggerParams skillParams(String tag) {
        return new TriggerParams(0, Optional.empty(), Optional.of(tag));
    }

    /** {@return the name the shipped definition gives {@code skill} at {@code tier}} */
    private static String nameOf(CharacterDef def, String skill, int tier) {
        SkillLadder ladder = def.affection().ladderFor(skill).orElseThrow(
                () -> new AssertionError(def.displayName() + " declares no ladder for " + skill));
        return ladder.nameAt(tier);
    }

    /** {@return the {@code extra_damage} amount for {@code skill} at {@code tier}, or {@code NaN}} */
    private static double damageAt(CharacterDef def, String skill, int tier) {
        for (EffectHook hook : AffectionKernel.hooksAt(def, tier)) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skill)) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    /** {@return the effect types {@code skill} fires at {@code tier}, in firing order} */
    private static List<EffectType> typesAt(CharacterDef def, String skill, int tier) {
        return AffectionKernel.hooksAt(def, tier).stream()
                .filter(hook -> hook.triggerParams().skillTag().orElse("").equals(skill))
                .map(EffectHook::type)
                .toList();
    }

    /** {@return the skill tags in first-appearance order — the rotation's order} */
    private static List<String> skillOrder(List<EffectHook> hooks) {
        java.util.LinkedHashSet<String> order = new java.util.LinkedHashSet<>();
        for (EffectHook hook : hooks) {
            hook.triggerParams().skillTag().ifPresent(order::add);
        }
        return List.copyOf(order);
    }
}
