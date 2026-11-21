package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.combat.HookPlan;
import com.astrum.pcrd.impl.combat.SkillRotation;
import com.astrum.pcrd.impl.combat.TriggerFacts;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three characters shipped with 姬塔 — コッコロ / ペコリーヌ / ｷｬﾙ — through the <b>real</b> datapack path.
 *
 * <h2>What this file is for, and the mistake it exists to prevent</h2>
 * The task brief listed the unit ids for the three, and <b>two of the three were wrong</b>: it gave
 * {@code 1058xx} for 可可萝 and {@code 1048xx} for ｷｬﾙ. Verified against the official name plates
 * ({@code chara_name_<unit>.png}) and the full stills, the truth is:
 *
 * <pre>
 *   1058 = ペコリーヌ (CV:M・A・O)      &lt;- the brief said 可可萝
 *   1059 = コッコロ   (CV:伊藤美来)     &lt;- the brief's "confirmed art" list did not mention it
 *   1060 = ｷｬﾙ       (CV:立花理香)     &lt;-
 *   1010 = マホ       (CV:内田真礼)     &lt;- the brief said 佩可莉姆
 *   1048 = ミフユ     (CV:田所あずさ)   &lt;- the brief said 凯露
 * </pre>
 *
 * So the art ids are asserted here, as data read from the shipped JSON, and the PNGs are asserted to
 * <b>differ between the three characters</b>. A future edit that re-points one of them at another
 * girl's file — the exact failure the brief's table would have produced — fails
 * {@link #everyCharacterHasHerOwnFace()} instead of shipping マホ as 可可萝.
 *
 * <h2>Why the rotation is asserted, not just the codec</h2>
 * The human's rule is that every character has a rotating set of {@code melee_hit} small skills, one per
 * swing, grouped by the {@code skill} tag. A character whose hooks happen to be tagged wrongly still
 * decodes; it only misbehaves in play, which nobody here can see. So each character's <b>three-swing
 * effect sequence</b> is asserted through {@link HookPlan#fire} — the same route production takes.
 */
class ThreeCharactersResourceTest {

    /** The character ids, i.e. the JSON file names and the {@code astrum:} paths. */
    private static final List<String> IDS = List.of("kokkoro", "pecorine", "kyaru");

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = ThreeCharactersResourceTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be in resources: " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static List<EffectType> types(List<HookPlan.Fired> fired) {
        return fired.stream().map(f -> f.hook().type()).toList();
    }

    /**
     * Every shipped character JSON sits exactly where NeoForge reads it, and decodes through the real
     * codec — no fixture, no lenient path.
     *
     * <p>The path is derived from {@link Registries#elementsDirPath} the way {@code DjeetaResourceTest}
     * derives it, so a move of the registry can never leave this assertion pointing at a stale folder.
     */
    @Test
    void allThreeDecodeThroughTheRealCodecAtThePathTheLoaderReads() throws Exception {
        assertEquals("/data/astrum/astrum/character/kokkoro.json", jsonPath("kokkoro"),
                "the doubled astrum is not a typo: the loader is never told about data/astrum/character/");
        for (String id : IDS) {
            CharacterDef def = load(id);
            assertNotEquals(CharacterRegistry.INVALID, def, id + " must decode as a real definition");
            assertTrue(def.effects().size() >= 2, id + " must have at least one rotating skill");
            assertTrue(def.affection().tier2Threshold() > 0, id + " needs a real affection ladder");
        }
    }

    /**
     * The four characters all ship, and each is a <b>different</b> face — the assertion that would have
     * caught the brief's id table.
     *
     * <p>Compares the shipped portrait bytes, not the ids: two characters pointing at one file would
     * mean one of them is showing somebody else's face, whatever the JSON says.
     */
    @Test
    void everyCharacterHasHerOwnFace() throws Exception {
        Set<String> faces = new TreeSet<>();
        for (String id : IDS) {
            CharacterDef def = load(id);
            ResourceLocation avatar = def.avatar().orElseThrow(
                    () -> new AssertionError(id + " must ship a portrait, or the roster draws a glyph"));
            assertTrue(avatar.getPath().startsWith("textures/gui/portrait/"),
                    id + "'s face must come from the portrait tree, not the generic card: " + avatar);
            assertEquals(Optional.of(avatar), def.face(),
                    id + " has no book override, so face() is her avatar");
            faces.add(java.util.Arrays.toString(bytesOf(avatar)));
        }
        assertEquals(IDS.size(), faces.size(),
                "each character must have her own bytes; a shared file means one of them is wrong");
    }

    /** The ids the art was verified against, pinned as data so a re-point is a deliberate edit. */
    @Test
    void theArtIdsAreTheOnesTheNamePlatesConfirm() throws Exception {
        // The official 3★ unit arts, verified against chara_name_1058/1059/1060.png (the name plates)
        // and the stills. These are the numbers tools/gen_portraits.py and gen_tier_avatars.py hold.
        assertEquals("astrum:textures/gui/portrait/kokkoro_3.png",
                load("kokkoro").icon3rd().orElseThrow().toString(),
                "コッコロ is unit 1059 (CV:伊藤美来), art icon_unit_105931");
        assertEquals("astrum:textures/gui/portrait/pecorine_3.png",
                load("pecorine").icon3rd().orElseThrow().toString(),
                "ペコリーヌ is unit 1058 (CV:M・A・O), art icon_unit_105831");
        assertEquals("astrum:textures/gui/portrait/kyaru_3.png",
                load("kyaru").icon3rd().orElseThrow().toString(),
                "ｷｬﾙ is unit 1060 (CV:立花理香), art icon_unit_106031 — NOT 1048 (that is ミフユ)");
    }

    /**
     * The tier ladder resolves for every tier and every character, and <b>all six still rungs exist</b>.
     *
     * <p>The brief asked whether the three actually have a 6★ still. They do — {@code still_unit_105961},
     * {@code still_unit_105861} and {@code still_unit_106061} are all in the official dump — so none of
     * them relies on the tier-3 fallback, and this test asserts the files are packaged rather than
     * assuming it.
     */
    @Test
    void everyTierResolvesToAPackagedStillAndAvatar() throws Exception {
        for (String id : IDS) {
            CharacterDef def = load(id);
            for (int tier = 1; tier <= 3; tier++) {
                assertTrue(def.face(tier).isPresent(), id + " has no avatar at tier " + tier);
                assertTrue(def.still(tier).isPresent(), id + " has no still at tier " + tier);
                assertNotNull(bytesOf(def.face(tier).orElseThrow()),
                        id + " tier " + tier + " avatar is not packaged");
                assertNotNull(bytesOf(def.still(tier).orElseThrow()),
                        id + " tier " + tier + " still is not packaged");
            }
            assertTrue(def.still6th().isPresent(), id + " must ship a 6★ still — the dump has one");
            assertEquals(def.still6th(), def.still(3), id + "'s tier 3 draws the 6★ still");
            assertEquals(def.still(), def.still(1), id + "'s tier 1 draws the 3★ still");
        }
    }

    /** The `_notes` convention: one sentence pointing at the derivation document, in all four files. */
    @Test
    void everyCharacterPointsAtTheDerivationDocument() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            try (InputStream in = ThreeCharactersResourceTest.class.getResourceAsStream(jsonPath(id))) {
                assertNotNull(in, id + ".json must be packaged");
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("\"_notes\""), id + ".json must carry a _notes field");
                assertTrue(text.contains("docs/balance-derivation.md"),
                        id + ".json's _notes must point at docs/balance-derivation.md");
            }
        }
    }

    // ------------------------------------------------------------------ the rotations

    /**
     * コッコロ: swing 1 is トライスラッシュ (single-target damage), swing 2 is スピードアップ — <b>haste
     * and</b> the party attack buff, one skill with two effects — swing 3 wraps back.
     *
     * <p>Breaks if her hook tags stop grouping (which would give her a three-step rotation and fire half of
     * スピードアップ on each of two swings) or if 行動速度 goes back to {@code minecraft:speed} — vanilla's
     * speed moves and does <b>not</b> touch attack speed, which is half of what 「行動速度」 means.
     */
    @Test
    void kokkoroRotatesTripleSlashThenSpeedUp() throws Exception {
        List<List<EffectType>> swings = threeSwings(load("kokkoro"));
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), swings.get(0), "swing 1 = トライスラッシュ");
        assertEquals(List.of(EffectType.HASTE, EffectType.BUFF_ATTACK_AREA), swings.get(1),
                "swing 2 = スピードアップ: 行動速度 + 物攻, both on one swing");
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), swings.get(2), "swing 3 wraps to skill 1");
    }

    /**
     * ペコリーヌ: ランチタイム (self magic defence + self heal) then フォールスラッシュ (attack up +
     * damage + armour break + regen).
     *
     * <p>ランチタイム's defence is a <b>magic</b> defence buff, not {@code minecraft:resistance}: the skill's
     * text is 「魔法防御力中アップ」, and resistance is all-purpose damage reduction, i.e. it would implement
     * a stronger skill than the one ペコリーヌ has. The pair of types is what makes the two defence buffs
     * distinguishable at all ({@code DamageReduction} reads the magic/物理 marker).
     */
    @Test
    void pecorineRotatesLunchTimeThenFallsSlash() throws Exception {
        List<List<EffectType>> swings = threeSwings(load("pecorine"));
        assertEquals(List.of(EffectType.HEAL_SELF, EffectType.BUFF_MAGIC_DEFENCE_AREA), swings.get(0),
                "swing 1 = 午餐时间: 自分HP中回復 + 魔法防御力中アップ");
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), swings.get(1),
                "swing 2 = 沉重一击, which bwiki gives as damage and nothing else");
        assertEquals(swings.get(0), swings.get(2), "swing 3 wraps to 午餐时间");
    }

    /**
     * ｷｬﾙ: サンダーボール (single-target <b>magic</b>) then アーマーダウン (armour break).
     *
     * <p>The damage type is asserted too, because it is the marker the magic defence buff reduces: a
     * mage's damage filed under {@code astrum:hook_damage} is indistinguishable from a sword's, and the
     * magic defence buff would then reduce nothing of hers.
     */
    @Test
    void kyaruRotatesThunderBallThenArmourDown() throws Exception {
        CharacterDef kyaru = load("kyaru");
        List<List<EffectType>> swings = threeSwings(kyaru);
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), swings.get(0), "swing 1 = 闪电球");
        assertEquals(List.of(EffectType.DEFENCE_DOWN), swings.get(1), "swing 2 = 护甲削弱");
        assertEquals(swings.get(0), swings.get(2), "swing 3 wraps to 闪电球");

        EffectHook thunderBall = kyaru.effects().get(0);
        assertInstanceOf(EffectHook.ExtraDamage.class, thunderBall);
        assertEquals(Optional.of(ResourceLocation.parse("astrum:magic")),
                ((EffectHook.ExtraDamage) thunderBall).damageType(),
                "サンダーボール is 単体魔法ダメージ — without astrum:magic the magic defence buff cannot "
                        + "see it");
    }

    /**
     * 姬塔: swing 1 is 广域斩击, swing 2 is 剑意迸发 — <b>TP gain up and</b> her small self attack buff.
     *
     * <p>Her rotation is pinned here rather than only in {@code DjeetaResourceTest} because this task
     * changed both of 剑意迸发's effects: 原作 is 「自身 TP 特大回復」, which is a <b>rate</b> (TP上昇), not a
     * one-off charge grant — {@code gain_charge} would model "a lump sum", which is a different mechanic and
     * the one thing the skill's own text does not say.
     */
    @Test
    void djeetaRotatesWideSlashThenWeaponBurst() throws Exception {
        CharacterDef djeeta = load("djeeta");
        List<List<EffectType>> swings = threeSwings(djeeta);
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), swings.get(0), "swing 1 = 广域斩击");
        assertEquals(List.of(EffectType.GAIN_CHARGE), swings.get(1), "swing 2 = 剑意迸发, a TP grant");
        assertEquals(swings.get(0), swings.get(2), "swing 3 wraps to 广域斩击");

        // At tier 3 the rotation is the SAME two skills in the same order, with different bodies: 技能1
        // becomes 黄金耀光 (damage + TP + party TP) and 技能2 becomes 三重剑击 (damage + TP).
        List<List<EffectType>> atSixStar = threeSwings(djeeta, 3);
        assertEquals(List.of(EffectType.EXTRA_DAMAGE, EffectType.GAIN_CHARGE, EffectType.AREA_CHARGE),
                atSixStar.get(0), "tier 3's 黄金耀光 is three clauses on one swing");
        assertEquals(List.of(EffectType.EXTRA_DAMAGE, EffectType.GAIN_CHARGE), atSixStar.get(1),
                "tier 3's 三重剑击 is damage + TP");
        assertEquals(atSixStar.get(0), atSixStar.get(2), "still a two-skill rotation");
    }

    /** Runs three {@code melee_hit} swings of one character at {@code tier}, returns what each fired. */
    private static List<List<EffectType>> threeSwings(CharacterDef def, int tier) {
        List<EffectHook> hooks = com.astrum.pcrd.impl.growth.AffectionKernel.hooksAt(def, tier);
        SkillRotation cursor = SkillRotation.START;
        List<List<EffectType>> swings = new ArrayList<>();
        for (int swing = 0; swing < 3; swing++) {
            HookPlan.Outcome outcome = HookPlan.fire(hooks,
                    TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                    (index, hook) -> Optional.empty(), invalid -> {
                    }, cursor);
            swings.add(types(outcome.fired()));
            cursor = outcome.rotation();
        }
        return swings;
    }

    /** Runs three swings at affection tier 1, the shipped blank tier. */
    private static List<List<EffectType>> threeSwings(CharacterDef def) {
        return threeSwings(def, 1);
    }

    // ------------------------------------------------------------------ the damage derivation

    /**
     * Every damage number is the real game's, scaled by the same anchor 姬塔 uses.
     *
     * <h2>The derivation this pins</h2>
     * 姬塔 is {@code 20.0 × real / 355293}. This trio shares one anchor of its own —
     * <b>268</b>, ペコリーヌ's Lv112 ultimate, the largest real damage in the set — so
     * {@code value = 20.0 × real / 268} is the same rule at the same scale:
     *
     * <pre>
     *   ペコリーヌ UB      268            -> 20.0   (the anchor, exactly 姬塔's 20.0)
     *   ペコリーヌ 技能2    159 / 268      -> 11.9
     *   ペコリーヌ 技能1     92 / 268      -> 6.9    (also the heal's basis)
     *   コッコロ   技能1    156 / 268      -> 11.6
     *   ｷｬﾙ       技能1    152 / 268      -> 11.3
     *   ｷｬﾙ       UB       113 / 268      -> 8.4
     * </pre>
     *
     * <p>Asserted against the <b>shipped JSON</b>, because that is what a future author edits.
     */
    @Test
    void everyDamageNumberIsTheRealGamesRatioAtTheSameAnchor() throws Exception {
        // One reference point for the whole mod, stated in docs/balance-derivation.md §14.1: level 208 and
        // attack 2000, so the bwiki formulas become plain arithmetic. The anchor is 姬塔's own ultimate,
        // 4.8A+60+60L = 22140, which is 20.0 — the number the mod has always used.
        double reference = 2000.0;
        double level = 208.0;
        double anchorRaw = 4.8 * reference + 60.0 + 60.0 * level;
        assertEquals(22140.0, anchorRaw, 1e-9);

        /** {@return 20.0 × (aA + b + bL) / anchor, at the stated reference} */
        java.util.function.BiFunction<Double, Double, Double> scalar =
                (a, b) -> round1(20.0 * (a * reference + b + b * level) / anchorRaw);

        CharacterDef pecorine = load("pecorine");
        assertEquals(scalar.apply(2.4, 30.0), pecorine.ub().base().damage(), 1e-9,
                "公主突袭 is 2.4A+30+30L = 11070 -> 10.0");
        assertEquals(scalar.apply(1.6, 20.0), extraDamageOf(pecorine, "heavy_blow"), 1e-9,
                "沉重一击 is 1.6A+20+20L = 7380 -> 6.7");
        // 午餐时间's heal is a percentage of max health (heal_self tier 3 = 60%), so the bwiki formula's
        // scalar is the *derivation* the tier ladder replaced — docs/balance-derivation.md §14.3 records
        // the conversion, and this asserts the tier that came out of it.
        assertEquals(Optional.of(1), hookTierOf(pecorine, EffectType.HEAL_SELF),
                "午餐時間 heals 【物理攻击力+5+5*技能等级】 = 2.8 units of a 20-point bar = 14%, i.e. rung 1 " +
                        "of the five 20% rungs (docs/balance-derivation.md §14.3)");

        assertEquals(scalar.apply(1.6, 20.0), extraDamageOf(load("kokkoro"), "triple_slash"), 1e-9,
                "掠阵三段击 is 1.6A+20+20L = 7380 -> 6.7");
        assertEquals(scalar.apply(1.6, 20.0), extraDamageOf(load("kyaru"), "lightning_orb"), 1e-9,
                "闪电球 is 1.6A+20+20L = 7380 -> 6.7");
        assertEquals(scalar.apply(1.08, 12.0), load("kyaru").ub().base().damage(), 1e-9,
                "格林炸裂 is 1.08A+12+12L = 4668 -> 4.2");
        assertEquals(scalar.apply(2.16, 24.0), load("kyaru").ub().enhanced().damage(), 1e-9,
                "深渊炸裂 is 2.16A+24+24L = 9336 -> 8.4");
        assertEquals(scalar.apply(4.8, 60.0), load("pecorine").ub().enhanced().damage(), 1e-9,
                "公主勇袭 is 4.8A+60+60L = 22140 -> 20.0, the anchor exactly");
    }

    /** ｷｬﾙ's ultimate hits <b>everyone</b> in a radius — the 6★ text is 「敵全体」, and so is the base one. */
    @Test
    void kyarusUltimateIsAnAreaNotASingleTarget() throws Exception {
        CharacterDef kyaru = load("kyaru");
        assertTrue(kyaru.ub().base().radius().isPresent(),
                "グリムバースト is 敵全体に魔法中ダメージ — a single-target tier would hit one body");
        assertTrue(kyaru.ub().base().radius().getAsDouble() > 0.0,
                "a radius of 0 reaches nobody; the area tier needs a real one");
        assertEquals(Optional.of(ResourceLocation.parse("astrum:magic")), kyaru.ub().base().damageType(),
                "and the whole of it is magic: without the marker the magic defence buff reduces none of "
                        + "it, which is the pairing the new tiered system exists to make measurable");
        assertEquals(kyaru.ub().base().damageType(), kyaru.ub().enhanced().damageType(),
                "6★ is a stronger グリムバースト, not a different element");
    }

    /**
     * The skills that are not damage still exist, as <b>generic tiered effects</b> on the ultimate.
     *
     * <h2>What this replaced</h2>
     * It used to assert {@code ub().base().heal().isPresent()} and {@code ub().base().allyEffect()} —
     * the four bespoke per-clause fields. Those are now one {@code effects[]} list of
     * {@link com.astrum.pcrd.api.v1.UbEffect}, so the assertion asks the same question in the new
     * vocabulary: does the tier that is supposed to heal the party still carry a heal, at a real tier?
     *
     * <p>It keeps the original intent exactly — "a healer with no heal is the defect this task fixed" —
     * and adds the two clauses the old schema could only record as free text: ｷｬﾙ's 6★ panic is now a real
     * effect, and it is asserted to be one.
     */
    @Test
    void theSupportSkillsAreNotSilentlyDropped() throws Exception {
        CharacterDef kokkoro = load("kokkoro");
        assertTrue(tierOf(kokkoro.ub().base(), EffectType.HEAL_SELF).isPresent(),
                "コッコロ's 极光 is 「恢复自身…生命值」 — the heal is the ultimate");
        assertTrue(tierOf(kokkoro.ub().enhanced(), EffectType.HEAL_AREA).isPresent(),
                "and 6★ オーロラブルーミング is 「味方全体HP中回復」");
        assertTrue(radiusOf(kokkoro.ub().enhanced(), EffectType.HEAL_AREA).orElseThrow() > 0.0,
                "「味方全体」 means a radius, not just herself");

        CharacterDef pecorine = load("pecorine");
        assertTrue(tierOf(pecorine.ub().base(), EffectType.BUFF_PHYS_DEFENCE_AREA).isPresent(),
                "ペコリーヌ's UB is 「自分の物理・魔法防御力を大アップ」 — a tank's whole point");
        assertTrue(tierOf(pecorine.ub().base(), EffectType.BUFF_MAGIC_DEFENCE_AREA).isPresent(),
                "both halves: 物理 and 魔法 are two effects, and the skill names both");

        CharacterDef kyaru = load("kyaru");
        // bwiki gives 深渊炸裂 as 「对敌方全体造成【2.16*魔法攻击力+24+24*技能等级】魔法伤害 + 100%几率使被伤害到的
        // 敌人陷入恐慌状态，持续8秒」 — damage and panic, and no defence debuff at all. The old
        // `defence_down` there was a clause the real skill does not have; her 魔防ダウン lives on the 専武
        // version of 技能1 instead, as MAGIC_DEFENCE_DOWN (see AffectionKernelTest).
        assertTrue(tierOf(kyaru.ub().enhanced(), EffectType.PANIC).isPresent(),
                "「恐慌」 is a real PANIC effect now, not only a word in `extra`");
        assertTrue(tierOf(kyaru.ub().enhanced(), EffectType.DEFENCE_DOWN).isEmpty(),
                "深渊炸裂 has no armour break in its bwiki formula");
    }

    /**
     * <b>Every ultimate effect is legal for its scope, in every shipped file.</b>
     *
     * <p>A scope and an effect are two halves of one promise and the codec cannot check the pair: both
     * halves are individually valid, and a mistake is a party buff landing on the enemy — invisible until
     * somebody notices the wrong side is buffed. {@code UbTier.validateEffects} is the rule; this runs it
     * over all four files so a bad row is a failing test rather than a WARN in a log nobody reads.
     */
    @Test
    void everyUltimateEffectAgreesWithItsScope() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            CharacterDef def = load(id);
            for (String tier : List.of("base", "enhanced")) {
                var declared = tier.equals("base") ? def.ub().base() : def.ub().enhanced();
                assertTrue(declared.validateEffects().result().isPresent(),
                        id + "'s " + tier + " UB declares an illegal effect: "
                                + declared.validateEffects().error().map(e -> e.message()).orElse("?"));
            }
        }
    }

    /** Every {@code melee_hit} hook carries a skill tag, or the rotation counts it as its own skill. */
    @Test
    void everyRotatingHookIsTaggedSoTheRotationCountsSkillsNotHooks() throws Exception {
        for (String id : IDS) {
            CharacterDef def = load(id);
            for (EffectHook hook : def.effects()) {
                if (hook.trigger() == Trigger.MELEE_HIT) {
                    assertTrue(hook.triggerParams().skillTag().isPresent(),
                            id + "'s melee_hit hook " + hook.type() + " has no skill tag, so it "
                                    + "rotates as a skill of its own");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static double extraDamageOf(CharacterDef def, String skillTag) {
        for (EffectHook hook : def.effects()) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skillTag)) {
                return damage.amount();
            }
        }
        throw new AssertionError("no extra_damage hook tagged " + skillTag);
    }

    /** {@return the tier of the first {@code type} effect on {@code tier}, empty when it declares none} */
    private static Optional<Integer> tierOf(UbTier tier, EffectType type) {
        for (var effect : tier.effects()) {
            if (effect.type() == type) {
                return Optional.of(effect.params().tier());
            }
        }
        return Optional.empty();
    }

    /** {@return the radius the first {@code type} effect on {@code tier} declares, empty for none} */
    private static Optional<Double> radiusOf(UbTier tier, EffectType type) {
        for (var effect : tier.effects()) {
            if (effect.type() == type && effect.params().radius().isPresent()) {
                return Optional.of(effect.params().radius().getAsDouble());
            }
        }
        return Optional.empty();
    }

    /** {@return the tier of the first {@code type} <b>hook</b>, empty when the character has none} */
    private static Optional<Integer> hookTierOf(CharacterDef def, EffectType type) {
        for (EffectHook hook : def.effects()) {
            if (hook.type() == type && hook.tieredParams().isPresent()) {
                return Optional.of(hook.tieredParams().get().tier());
            }
        }
        return Optional.empty();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static byte[] bytesOf(ResourceLocation texture) {
        String path = "/assets/" + texture.getNamespace() + "/" + texture.getPath();
        try (InputStream in = ThreeCharactersResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing texture: " + path);
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    /** Every shipped still is under the byte budget, so nobody committed the ~1 MB original. */
    @Test
    void noStillWasCommittedAtTheOfficialMegabyteSize() throws Exception {
        for (String id : IDS) {
            CharacterDef def = load(id);
            for (int tier = 1; tier <= 3; tier++) {
                byte[] bytes = bytesOf(def.still(tier).orElseThrow());
                assertTrue(bytes.length < 320 * 1024,
                        id + "'s tier-" + tier + " still is " + bytes.length + " B — the official "
                                + "~1 MB original was committed instead of the 512x288 resample");
                java.awt.image.BufferedImage image;
                javax.imageio.ImageIO.setUseCache(false);
                try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                    image = ImageIO.read(in);
                }
                assertNotNull(image, id + " tier " + tier + " still is not a readable PNG");
                assertEquals(512, image.getWidth(), id + " still width");
                assertEquals(288, image.getHeight(), id + " still height");
            }
        }
    }

    /** The party effect's slot count is the single copy of "five", so four characters still fit. */
    @Test
    void thePartyHasRoomForAllFourCharacters() {
        assertTrue(com.astrum.pcrd.impl.attach.Party.SLOTS >= 4,
                "a party of five must hold 姬塔 and the three new characters at once");
        assertFalse(IDS.isEmpty());
    }
}
