package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.api.v1.UbDef;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.astrum.pcrd.impl.party.BookText;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What the 技能详情 page says</b>, pinned against the four shipped characters.
 *
 * <h2>The four questions this file answers</h2>
 * <ol>
 *   <li><b>Which versions of a skill are columns</b> — the rungs the ladder declares, no more: a skill with no
 *       {@code +} version is one column rather than three identical ones, and 姬塔's 技能1 is three because her
 *       data really has three (广域斩击 → 广域斩击+ → 黄金耀光).</li>
 *   <li><b>Which of them is the one that fires</b> — {@link SkillDetailModel.Entry#current()}, the highest rung
 *       she has reached. Getting this wrong is the defect the page exists to avoid: a page that calls the base
 *       version current at 6★ tells a player she casts a skill she outgrew.</li>
 *   <li><b>Which are greyed</b> — the rungs above hers, with 「未解锁」, which is the spec's 「让玩家知道后面还有」.</li>
 *   <li><b>What the numbers are</b> — the resolved tier (declared + level band, clamped by the effect's own
 *       ladder) and the {@code effectParams}, formatted per parameter rather than per skill. EX is asserted to
 *       get <b>no</b> band, which the human ruled explicitly.</li>
 * </ol>
 *
 * <p>And the <b>ultimate</b>, which is a column of the same page built from a different record: its name comes
 * from the same {@code AffectionKernel.skillNamesAt} the cast pad reads, its damage is {@code hits × damage}, and
 * its later rung is the one 6★ casts. Its tests are {@link #theUltimateIsTheFirstColumn} onwards.
 *
 * <p>Every fact here comes from the shipped JSON, decoded through {@code CharacterDef.CODEC}: a test that built
 * its own definition could pass while the real pack drew something else. The one exception is
 * {@link #anUltimateWithNoEffectsStillShowsItsDamage}, which builds a {@code UbDef} directly because the shipped
 * characters all declare effects on at least one rung — and because {@code teamChargeBonus} is declared by none of
 * them, so only a synthetic tier can pin that row.
 */
class SkillDetailModelTest {

    /**
     * {@return {@code base} with {@code extra} added to its base ultimate}
     *
     * <p>Round-trips through the real codec rather than rebuilding the record, so the fixture cannot disagree with
     * the schema: a field the decoder rejects would fail here rather than in a shipped pack.
     */
    private static CharacterDef withExtra(CharacterDef base, String extra) {
        com.google.gson.JsonElement encoded =
                CharacterDef.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, base).getOrThrow();
        com.google.gson.JsonObject root = encoded.getAsJsonObject();
        root.getAsJsonObject("ub").getAsJsonObject("base").addProperty("extra", extra);
        return CharacterDef.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, root).getOrThrow();
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = SkillDetailModelTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static List<String> names(SkillDetailModel model) {
        return model.entries().stream().map(SkillDetailModel.Entry::name).toList();
    }

    private static SkillDetailModel.Entry find(SkillDetailModel model, String name) {
        return model.entries().stream().filter(entry -> entry.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " is not a column of " + names(model)));
    }

    // ---------------------------------------------------------------------------------------
    // The ultimate: one column, first, built from its UbTier
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The ultimate is the first column, and it comes from the character's own ultimate tier.</b>
     *
     * <p>It used to be missing from this page entirely, which was a regression: the book is the only place in the
     * UI that describes a character, and its headline skill has to be there.
     */
    @Test
    void theUltimateIsTheFirstColumn() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            for (int tier = 1; tier <= 3; tier++) {
                SkillDetailModel model = SkillDetailModel.of(load(id), tier, 50);
                SkillDetailModel.Entry first = model.entry(0);
                assertNotNull(first);
                assertEquals(AffectionKernel.ubKey(), first.skill(), id + "@" + tier + ": the ultimate is first");
                assertTrue(first.ultimate(), id + "@" + tier + ": and it is flagged as the ultimate");
                assertTrue(first.current(), id + "@" + tier + ": the UB she is on is the one that fires");
                assertFalse(first.locked(), id + "@" + tier + ": her own ultimate is never 'not yet'");
                assertFalse(first.rows().isEmpty(), id + "@" + tier + ": a UB column is never a bare name");
            }
        }
    }

    /**
     * <b>Its damage row is {@code hits × damage}</b>, with the hit count and the per-hit figure beside it — four
     * hits of 20 reads differently from one hit of 80, and the total is what a player compares.
     */
    @Test
    void theUltimateShowsItsTotalDamage() throws Exception {
        SkillDetailModel.Entry ub = SkillDetailModel.of(load("djeeta"), 1, 30).entry(0);
        assertEquals("疾风剑雨", ub.name(), "her 5★ ultimate");
        assertEquals(1, ub.rows().size(), "the base tier declares no effects: one damage row, and nothing else");
        SkillDetailModel.Row damage = ub.rows().get(0);
        assertEquals(EffectType.EXTRA_DAMAGE, damage.type(), "the damage family's picture");
        assertEquals(BookText.LABEL_DAMAGE, damage.labelKey());
        assertEquals(BookText.LABEL_DAMAGE, damage.headingKey(), "the panel heads it 「伤害」");
        assertFalse(damage.tiered(), "damage is not a rung");
        Map<String, String> values = numbers(damage);
        assertEquals("80", values.get(BookText.LABEL_VALUE), "4 hits × 20");
        assertEquals("4", values.get(BookText.LABEL_HITS));
        assertEquals("20", values.get(BookText.LABEL_PER_HIT));
    }

    /**
     * <b>A tier that spells its damage as clauses gets one row per clause, labelled by its target rule.</b>
     *
     * <p>日和莉's base ultimate is two numbers on two kinds of receiver: 「对最近的敌人 10.0」 and 「对半径400内全部敌人
     * 4.5」. One damage row could only ever show one of them — which is precisely why the other was parked in
     * {@code extra} and rendered as 「尚未实现」. Now the page draws both, and the label of each row is the clause's
     * {@link com.astrum.pcrd.api.v1.UbTargetRule}, because "which bodies" is the fact a player cannot recover from
     * two bare numbers.
     */
    @Test
    void aClauseListedUltimateDrawsOneRowPerClause() throws Exception {
        SkillDetailModel.Entry ub = SkillDetailModel.of(load("hiyori"), 1, 30).entry(0);
        assertEquals(2, ub.rows().size(), "two clauses, two rows");

        SkillDetailModel.Row nearest = ub.rows().get(0);
        assertEquals(BookText.targetRuleShortKey(com.astrum.pcrd.api.v1.UbTargetRule.NEAREST),
                nearest.labelKey(), "the row's short label is the rule");
        assertEquals(BookText.targetRuleKey(com.astrum.pcrd.api.v1.UbTargetRule.NEAREST),
                nearest.headingKey(), "and the panel heads it with the rule's full name");
        assertEquals(EffectType.EXTRA_DAMAGE, nearest.type(), "the damage family's picture");
        Map<String, String> hit = numbers(nearest);
        assertEquals("10", hit.get(BookText.LABEL_VALUE), "the nearest-target amount");
        assertEquals("1", hit.get(BookText.LABEL_HITS));
        assertEquals("10", hit.get(BookText.LABEL_PER_HIT));
        assertNull(hit.get(BookText.LABEL_RADIUS), "that clause declares no radius: it hits one body");

        SkillDetailModel.Row blast = ub.rows().get(1);
        assertEquals(BookText.targetRuleKey(com.astrum.pcrd.api.v1.UbTargetRule.ALL_IN_RADIUS),
                blast.headingKey());
        Map<String, String> area = numbers(blast);
        assertEquals("4.5", area.get(BookText.LABEL_VALUE), "the blast amount");
        assertEquals("4", area.get(BookText.LABEL_RADIUS), "范围半径400 is four blocks");
    }

    /**
     * <b>A clause that carries effects rather than damage draws both</b> — the rule, and the mechanic.
     *
     * <p>紬希's 「将最远的敌人拉近 300」 is a clause whose whole content is a pull: its row is the rule 「最远的敌人」,
     * and the pull itself is the row beneath it, through the same {@link SkillDetailModel.Row} a tier-level effect
     * uses. A page that drew only damage would show an ultimate that deals 6.0 to a group and never mentions that
     * the farthest body is dragged three blocks.
     */
    @Test
    void anEffectClauseDrawsItsRuleAndItsMechanic() throws Exception {
        SkillDetailModel.Entry ub = SkillDetailModel.of(load("tsumugi"), 1, 30).entry(0);

        SkillDetailModel.Row farthest = ub.rows().stream()
                .filter(row -> BookText.targetRuleKey(com.astrum.pcrd.api.v1.UbTargetRule.FARTHEST)
                        .equals(row.headingKey()))
                .findFirst().orElseThrow(() -> new AssertionError("no 「最远的敌人」 row among " + ub.rows()));
        assertTrue(farthest.numbers().isEmpty(), "that clause deals no damage, so it states no amount");

        SkillDetailModel.Row pull = ub.rows().stream()
                .filter(row -> row.type() == EffectType.PULL).findFirst()
                .orElseThrow(() -> new AssertionError("the pull clause's mechanic is not drawn"));
        assertEquals(3, pull.tier(), "PULL's rung is a distance in blocks: 拉近 300 is three");
        assertTrue(ub.rows().stream().anyMatch(row -> row.type() == EffectType.KNOCKBACK),
                "「击退最近的敌人 300」 is drawn too");
        assertTrue(ub.rows().stream().anyMatch(row -> row.type() == EffectType.PANIC),
                "and the tier's own 束缚 is still a row of its own");
    }

    /** The radius and the damage type are rows only when the tier declares them — a magic UB reads differently. */
    @Test
    void theUltimateShowsItsRadiusAndDamageTypeWhenItHasThem() throws Exception {
        SkillDetailModel.Entry ub = SkillDetailModel.of(load("kyaru"), 1, 30).entry(0);
        assertEquals("格林炸裂", ub.name());
        Map<String, String> damage = numbers(ub.rows().get(0));
        assertEquals("4.2", damage.get(BookText.LABEL_VALUE), "1 hit × 4.2");
        assertEquals("6", damage.get(BookText.LABEL_RADIUS), "a six-block blast");
        assertEquals("astrum:magic", damage.get(BookText.LABEL_DAMAGE_TYPE),
                "a magical ultimate says so, or the magic defence buff has nothing to reduce");
        Map<String, String> physical = numbers(SkillDetailModel.of(load("djeeta"), 1, 30).entry(0).rows().get(0));
        assertNull(physical.get(BookText.LABEL_RADIUS), "姬塔's ultimate declares no radius");
        assertNull(physical.get(BookText.LABEL_DAMAGE_TYPE), "nor a damage type: it is filed as ub_damage");
    }

    /** 6★ casts the enhanced tier, and its rows are the enhanced tier's. */
    @Test
    void theEnhancedUltimateIsTheOneSixStarsCast() throws Exception {
        SkillDetailModel base = SkillDetailModel.of(load("djeeta"), 2, 30);
        SkillDetailModel enhanced = SkillDetailModel.of(load("djeeta"), 3, 30);
        assertEquals("疾风剑雨", base.entry(0).name(), "tier 2 is still the 5★ ultimate");
        assertEquals("星河漫天", enhanced.entry(0).name(), "6★ renames it");
        // The enhanced tier is stronger and carries a party attack buff the base does not have.
        assertEquals("125.2", numbers(enhanced.entry(0).rows().get(0)).get(BookText.LABEL_VALUE),
                "4 hits × 31.3");
        assertEquals(1, base.entry(0).rows().size());
        assertEquals(2, enhanced.entry(0).rows().size(), "the damage row plus the attack buff");
        SkillDetailModel.Row buff = enhanced.entry(0).rows().get(1);
        assertEquals(EffectType.BUFF_ATTACK_AREA, buff.type());
        assertEquals(5, buff.tier(), "declared at tier 5, the top of its ladder");
        assertEquals("V", numbers(buff).get(BookText.LABEL_TIER), "shown as the roman numeral it is");
        assertEquals("18", numbers(buff).get(BookText.LABEL_SECONDS));
    }

    /**
     * <b>An ultimate's effects go through the same band composition as a hook's</b> — and the ladder wins.
     *
     * <p>キャル's 6★ 深渊炸裂 applies {@code panic}, which has a single rung, so a level-95 character's +3 band is
     * refused by the ladder: the row stays on tier I and the panel says nothing about a band that changed
     * nothing. That is the same {@code TieredAmounts} path {@code UbDamageSettlement} settles the cast through —
     * the page cannot describe a rung the fight would not use.
     */
    @Test
    void theUltimatesEffectsPassThroughTheOneBandComposition() throws Exception {
        SkillDetailModel.Entry ub = SkillDetailModel.of(load("kyaru"), 3, 95).entry(0);
        assertEquals("深渊炸裂", ub.name());
        SkillDetailModel.Row panic = ub.rows().stream()
                .filter(row -> row.type() == EffectType.PANIC).findFirst().orElseThrow();
        assertEquals(1, panic.tier(), "panic is one rung, so a +3 band clamps back to it");
        assertNull(numbers(panic).get(BookText.LABEL_BAND),
                "and the panel does not claim a band that moved no rung");
    }

    /**
     * <b>A clause this build cannot express is shown, not dropped.</b>
     *
     * <p>{@code UbTier.extra} exists so an unimplemented clause stays visible in the data. The page repeats it
     * rather than quietly rendering a stronger ultimate with no sign of the part that does not happen.
     *
     * <p>The value is asserted <b>against the data</b>, not against a spelling, so this test pins the join and
     * leaves the wording to the data it belongs to. The one word that must survive is the mechanism's name,
     * because a note that stopped naming it would no longer tell a reader <em>what</em> is missing.
     *
     * <p><b>Why 咲恋 and not 绫音.</b> This test used キャル's 6★ clause — 「击晕 8 秒 → panic 固定 1.5 秒」 — as
     * its first fixture, and that is exactly the sentence task 60 deleted: the panic's duration is the pack's now,
     * so the 8 seconds <em>are</em> what the skill does. It then used 绫音's 「击飞高度 800 未实现」, and task 63
     * deleted that one too — the launch height has a channel of its own, so it is a number in her data rather than
     * a sentence. 咲恋's ultimate is the honest fixture: 「【0.2×损失生命值】」 scales with a health bar this build
     * has no reader for, so the clause is still <em>recorded</em> rather than expressed. The note's job is to keep
     * a real gap visible, not to keep a closed one alive.
     */
    @Test
    void anUnimplementedClauseBecomesANoteRow() throws Exception {
        // <b>The fixture is synthetic now, and that is the good news.</b> This test used to read 咲恋's `extra`
        // because hers was the last shipped gap; task 64 gave her lost-health scaling a channel, so the shipped
        // data carries <b>no</b> `extra` at all. The mechanism still exists — a pack can write one tomorrow — so
        // the fixture is built by adding the sentence to a real character's JSON rather than by deleting a test
        // that would then never run again.
        String extra = "这一条本 mod 还没有承载机制（仅用于测试）";
        CharacterDef saren = withExtra(load("saren"), extra);
        SkillDetailModel.Entry ub = SkillDetailModel.of(saren, 1, 30).entry(0);
        SkillDetailModel.Row note = ub.rows().stream().filter(row -> row.type() == null).findFirst()
                .orElseThrow(() -> new AssertionError("no note row among " + ub.rows()));
        assertEquals(BookText.LABEL_UNIMPLEMENTED, note.labelKey());
        assertEquals(BookText.LABEL_UNIMPLEMENTED, note.headingKey());
        assertEquals(List.of(extra), note.numbers().stream()
                .map(SkillDetailModel.Number::value).toList(),
                "the note row repeats the pack's own sentence, so the gap is legible and cannot drift");
        assertTrue(extra.contains("承载机制"),
                "the note must still name the quantity it cannot express, got: " + extra);
        // And it is not invented for a tier that has nothing unimplemented. 绫音 is such a tier now: her launch
        // height is `launch: 1.0` on her knockback, so her page has no note row at all (task 63).
        assertTrue(SkillDetailModel.of(load("ayane"), 1, 30).entry(0).rows().stream()
                        .noneMatch(row -> row.type() == null),
                "绫音's tiers have no unimplemented clause left — the launch height is a field now");
        assertTrue(SkillDetailModel.of(load("sheffy"), 1, 30).entry(0).rows().stream()
                .noneMatch(row -> row.type() == null),
                "雪菲's tiers have no unimplemented clause left — her regen is implemented now");
    }

    /**
     * <b>A UB with no effects still renders its damage row</b>, and its team charge grant and unimplemented tag
     * become rows of their own.
     *
     * <p>Built by hand because no shipped character declares {@code teamChargeBonus}: the row exists in the schema
     * and has to be drawn when a pack uses it. The synthetic tier also pins the level band on a UB clause
     * ({@code 4 + 3} clamped to the ladder's 5) without hunting for a shipped rung that happens to be near the top.
     */
    @Test
    void anUltimateWithNoEffectsStillShowsItsDamage() {
        UbTier base = new UbTier("ub_cast", 2, 5.0, OptionalDouble.of(8.0),
                Optional.of(ResourceLocation.parse("astrum:magic")), List.of(),
                OptionalDouble.of(12.0), Optional.of("barrier_break_plus_max_hp"), Optional.of("测试必杀"));
        UbEffect buff = new UbEffect(UbEffect.Scope.ALLY, EffectType.BUFF_ATTACK_AREA,
                TieredParams.of(4, 18.0, 8.0));
        UbTier enhanced = new UbTier("ub_cast", 3, 7.5, OptionalDouble.empty(), Optional.empty(),
                List.of(buff), OptionalDouble.empty(), Optional.empty(), Optional.of("测试必杀+"));
        UbDef ub = new UbDef(base, enhanced);

        SkillDetailModel.Entry entry = SkillDetailModel.ubColumn(ub, "测试必杀", 1, 95);
        assertNotNull(entry);
        assertTrue(entry.ultimate());
        List<SkillDetailModel.Row> rows = entry.rows();
        assertEquals(3, rows.size(), "damage, team charge, unimplemented tag — no effects on the base tier");

        Map<String, String> damage = numbers(rows.get(0));
        assertEquals("10", damage.get(BookText.LABEL_VALUE), "2 hits × 5");
        assertEquals("8", damage.get(BookText.LABEL_RADIUS));
        assertEquals("astrum:magic", damage.get(BookText.LABEL_DAMAGE_TYPE));

        SkillDetailModel.Row charge = rows.get(1);
        assertEquals(EffectType.AREA_CHARGE, charge.type(), "a team charge grant is an area_charge in hook terms");
        assertEquals(BookText.effectShortKey(EffectType.AREA_CHARGE), charge.labelKey());
        assertEquals(List.of("12"), charge.numbers().stream()
                .map(SkillDetailModel.Number::value).toList());

        assertEquals(BookText.LABEL_UNIMPLEMENTED, rows.get(2).labelKey());

        // The enhanced rung at level 95: declared tier 4 + band 3, clamped by buff_attack_area's five rungs.
        SkillDetailModel.Entry top = SkillDetailModel.ubColumn(ub, "测试必杀+", 3, 95);
        assertNotNull(top);
        assertEquals("测试必杀+", top.name(), "the name is the caller's — resolved by AffectionKernel");
        SkillDetailModel.Row raised = top.rows().stream().filter(row -> row.type() == EffectType.BUFF_ATTACK_AREA)
                .findFirst().orElseThrow();
        assertEquals(5, raised.tier(), "4 + 3 clamps to the ladder's top rung");
        assertEquals("+1", numbers(raised).get(BookText.LABEL_BAND),
                "and the band row reports the one rung the ladder let through, not the three her level offered");
    }

    /** A pack that names no ultimate, or defines none, gets no column — and no crash. */
    @Test
    void anUltimateWithNoNameOrNoDefinitionIsNotAColumn() {
        UbTier base = new UbTier("ub_cast", 1, 1.0, OptionalDouble.empty(), List.of());
        UbDef ub = new UbDef(base, base);
        assertNull(SkillDetailModel.ubColumn(null, "名字", 1, 30), "no ub block: no column");
        assertNull(SkillDetailModel.ubColumn(ub, null, 1, 30), "no name: nothing to title it with");
        assertNull(SkillDetailModel.ubColumn(ub, "  ", 1, 30), "a blank name is no name");
        assertNotNull(SkillDetailModel.ubColumn(ub, "名字", 1, 30));
    }

    // ---------------------------------------------------------------------------------------
    // Which versions are columns
    // ---------------------------------------------------------------------------------------

    /**
     * <b>佩可莉姆 at tier 1</b>: the ultimate first, then 午餐时间's {@code +} rung as a greyed column; the skills
     * without a {@code +} version are one column each.
     */
    @Test
    void pecorineAtTierOneShowsHerPlusRungsAsLockedColumns() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("pecorine"), 1, 30);
        assertEquals(List.of("公主突袭", "午餐时间", "午餐时间+", "沉重一击", "公主之力", "公主之力+"),
                names(model), "her ultimate, then her skill versions in her own rotation order");
        assertTrue(find(model, "午餐时间").current());
        assertFalse(find(model, "午餐时间").locked());
        assertTrue(find(model, "午餐时间+").locked(), "the + rung is above tier 1, so it is greyed");
        assertTrue(find(model, "沉重一击").current(), "a skill with no + version is current as it stands");
        assertEquals(1, model.entries().stream().filter(entry -> entry.skill().equals("heavy_blow"))
                .count(), "a skill whose name never changes is ONE column, not three identical ones");
        assertTrue(find(model, "公主之力+").locked());
    }

    /** <b>At tier 3 the ladder is climbed</b>: the top rung is current, the ones she outgrew are neither. */
    @Test
    void theCurrentColumnIsTheHighestRungSheHasReached() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("pecorine"), 3, 30);
        SkillDetailModel.Entry plus = find(model, "午餐时间+");
        assertTrue(plus.current(), "at tier 3 the + version is the one that fires");
        assertFalse(plus.locked());
        SkillDetailModel.Entry base = find(model, "午餐时间");
        assertFalse(base.current(), "the base version is not what she casts at tier 3");
        assertFalse(base.locked(), "and it is not 'locked' either — she has outgrown it");
        assertEquals("", base.markerKey(), "an outgrown version wears no marker at all");
        assertEquals(BookText.KEY_SKILL_DETAIL_ACTIVE, plus.markerKey());
    }

    /**
     * <b>姬塔's 技能1 has three columns, and only the 6★ rename is current.</b>
     *
     * <p>广域斩击 → 广域斩击+ → 黄金耀光 is the human's own rule, and a rename supersedes a {@code +}. The page must
     * show all three so the progression is visible, and must call exactly one of them current.
     */
    @Test
    void djeetaShowsThreeVersionsOfSkillOneWithOnlyTheRenameCurrent() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("djeeta"), 3, 30);
        List<String> first = model.entries().stream()
                .filter(entry -> entry.skill().equals("wide_slash"))
                .map(SkillDetailModel.Entry::name).toList();
        assertEquals(List.of("广域斩击", "广域斩击+", "黄金耀光"), first);
        assertTrue(find(model, "黄金耀光").current(), "the rename is what 6★ fires");
        assertFalse(find(model, "广域斩击+").current(), "a rename supersedes the +");
        assertFalse(find(model, "广域斩击").current());
        assertTrue(model.entries().stream().noneMatch(SkillDetailModel.Entry::locked),
                "at tier 3 nothing on her ladder is above her");
    }

    /** A locked column wears 「未解锁」 and nothing else claims to be current in its place. */
    @Test
    void aLockedColumnWearsTheLockedMarker() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("kyaru"), 1, 30);
        for (SkillDetailModel.Entry entry : model.entries()) {
            if (entry.locked()) {
                assertEquals(BookText.KEY_SKILL_DETAIL_LOCKED, entry.markerKey());
                assertFalse(entry.current(), "a rung above hers cannot also be the one that fires");
            } else if (entry.current()) {
                assertEquals(BookText.KEY_SKILL_DETAIL_ACTIVE, entry.markerKey());
            }
        }
        assertEquals(1, model.entries().stream().filter(entry -> entry.skill().equals("lightning_orb"))
                .filter(SkillDetailModel.Entry::current).count(),
                "exactly one version of a skill is the current one");
    }

    /** The ultimate is a column now — and only one, however many rungs it declares. */
    @Test
    void theUltimateIsExactlyOneColumn() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            for (int tier = 1; tier <= 3; tier++) {
                SkillDetailModel model = SkillDetailModel.of(load(id), tier, 50);
                assertEquals(1, model.entries().stream().filter(SkillDetailModel.Entry::ultimate).count(),
                        id + "@" + tier + ": one ultimate column, at the rung she is on");
            }
        }
    }

    /** Every column of every shipped character shows at least one row, and every row has numbers to open. */
    @Test
    void everyColumnOfEveryShippedCharacterHasRows() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            CharacterDef def = load(id);
            for (int tier = 1; tier <= 3; tier++) {
                SkillDetailModel model = SkillDetailModel.of(def, tier, 60);
                assertFalse(model.isEmpty(), id + "@" + tier + " must have columns");
                for (SkillDetailModel.Entry entry : model.entries()) {
                    assertFalse(entry.rows().isEmpty(),
                            id + "@" + tier + ": " + entry.name() + " would draw a column with no rows");
                    for (SkillDetailModel.Row row : entry.rows()) {
                        assertFalse(row.numbers().isEmpty(),
                                id + "@" + tier + ": " + entry.name() + "'s " + row.labelKey()
                                        + " has no numbers to show");
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The resolved tier: level band in, EX out
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The declared tier plus the level band, clamped by the effect's own ladder.</b>
     *
     * <p>佩可莉姆's 午餐时间 declares {@code buff_magic_defence_area} at tier 1. A level-95 character adds three
     * bands, so the page must show tier 4 — and the numbers panel must say where the extra rungs came from.
     * At level 10 there is no band and the tier stays 1.
     */
    @Test
    void theResolvedTierIncludesTheLevelBand() throws Exception {
        CharacterDef pecorine = load("pecorine");
        SkillDetailModel dignified = SkillDetailModel.of(pecorine, 1, 95);
        SkillDetailModel fresh = SkillDetailModel.of(pecorine, 1, 10);
        assertEquals(4, tierOf(dignified, "午餐时间", EffectType.BUFF_MAGIC_DEFENCE_AREA),
                "declared 1 + a level-95 band of 3");
        assertEquals(1, tierOf(fresh, "午餐时间", EffectType.BUFF_MAGIC_DEFENCE_AREA),
                "a level-10 character is in the first band, so nothing is added");
        assertEquals("IV", find(dignified, "午餐时间").rows().stream()
                .filter(row -> row.type() == EffectType.BUFF_MAGIC_DEFENCE_AREA)
                .findFirst().orElseThrow().tierText());
        assertTrue(numbers(row(dignified, "午餐时间", EffectType.BUFF_MAGIC_DEFENCE_AREA))
                        .containsKey(BookText.LABEL_BAND),
                "the panel says how many rungs the level added");
        assertFalse(numbers(row(fresh, "午餐时间", EffectType.BUFF_MAGIC_DEFENCE_AREA))
                .containsKey(BookText.LABEL_BAND),
                "and says nothing when it added none");
    }

    /**
     * <b>The band cannot put a rung past the effect's own ladder.</b>
     *
     * <p>The clamp is {@code EffectTiers.resolve}'s, not this page's — asserted here only to prove the page goes
     * through it rather than adding a band of its own: a level-100 character on a four-rung debuff must still
     * show a tier the ladder has.
     */
    @Test
    void theBandCannotExceedTheEffectsOwnLadder() throws Exception {
        CharacterDef kyaru = load("kyaru");
        SkillDetailModel model = SkillDetailModel.of(kyaru, 1, 100);
        for (SkillDetailModel.Entry entry : model.entries()) {
            for (SkillDetailModel.Row row : entry.rows()) {
                if (!row.tiered()) {
                    continue;
                }
                assertTrue(row.tier() <= EffectTiers.maxTier(row.type()),
                        entry.name() + ": tier " + row.tier() + " is past "
                                + row.type() + "'s ladder of " + EffectTiers.maxTier(row.type()));
            }
        }
    }

    /**
     * <b>EX gets no level band</b> — the human's explicit ruling.
     *
     * <p>コッコロ's 精灵的启示+ adds a {@code buff_magic_defence_area} clause at tier 1. The same effect declared on a
     * normal skill at level 95 lands on tier 4 (see above); the EX clause must stay on tier 1, or her passive
     * would quietly grow with her level, which is the rule the ruling forbids.
     */
    @Test
    void exGetsNoLevelBand() throws Exception {
        CharacterDef kokkoro = load("kokkoro");
        SkillDetailModel high = SkillDetailModel.of(kokkoro, 2, 95);
        SkillDetailModel low = SkillDetailModel.of(kokkoro, 2, 5);
        assertEquals(1, tierOf(high, "精灵的启示+", EffectType.BUFF_MAGIC_DEFENCE_AREA),
                "an EX clause keeps its declared tier at level 95");
        assertEquals(1, tierOf(low, "精灵的启示+", EffectType.BUFF_MAGIC_DEFENCE_AREA));
        assertEquals(high.entries().stream().filter(SkillDetailModel.Entry::ex)
                        .flatMap(entry -> entry.rows().stream())
                        .map(SkillDetailModel.Row::tier).toList(),
                low.entries().stream().filter(SkillDetailModel.Entry::ex)
                        .flatMap(entry -> entry.rows().stream())
                        .map(SkillDetailModel.Row::tier).toList(),
                "two levels 90 apart must produce the same EX rungs");
        assertFalse(numbers(row(high, "精灵的启示+", EffectType.BUFF_MAGIC_DEFENCE_AREA))
                        .containsKey(BookText.LABEL_BAND),
                "and the panel says the level added nothing");
    }

    /** The EX point formula is evaluated at her level, through {@code FlatAmount} — not restated here. */
    @Test
    void theExPointFormulaIsEvaluatedAtHerLevel() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("djeeta"), 2, 60);
        Map<String, String> values = numbers(row(model, "龙卷的攻刃+", EffectType.FLAT_ATTACK_UP));
        assertEquals("240", values.get(BookText.LABEL_BASE));
        assertEquals("15", values.get(BookText.LABEL_PER_LEVEL));
        assertEquals("1140", values.get(BookText.LABEL_VALUE), "240 + 15 × 60, at FlatAmount's own arithmetic");
    }

    // ---------------------------------------------------------------------------------------
    // The numbers come from the params
    // ---------------------------------------------------------------------------------------

    /** A damage hook shows its {@code amount}, formatted, under the value label. */
    @Test
    void aDamageHookShowsTheAmountFromItsParams() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("pecorine"), 1, 30);
        assertEquals("6.7", numbers(row(model, "沉重一击", EffectType.EXTRA_DAMAGE)).get(BookText.LABEL_VALUE),
                "extra_damage's 6.7 points, straight out of the JSON");
    }

    /** A tiered effect shows the rung and the fraction the rung is worth, from {@code EffectTiers}. */
    @Test
    void aTieredHookShowsItsRungAndItsFraction() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("pecorine"), 1, 10);
        Map<String, String> values = numbers(row(model, "午餐时间", EffectType.BUFF_MAGIC_DEFENCE_AREA));
        assertEquals("I", values.get(BookText.LABEL_TIER));
        assertEquals("12%", values.get(BookText.LABEL_RATE), "one rung of the 12%-per-rung defence ladder");
    }

    /**
     * <b>Every key the panel can emit is translated in both languages.</b>
     *
     * <p>{@code LangParityTest} proves the two files agree with each other and hold every key
     * {@code BookText.LANG_KEYS} names; this test proves the model only ever asks for keys from that set. Between
     * them, a panel line cannot reach a player as a raw {@code astrum.book.detail.something}.
     */
    @Test
    void everyKeyThePanelAsksForIsTranslated() throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru", "chika", "kyoka", "rino")) {
            for (int tier = 1; tier <= 3; tier++) {
                SkillDetailModel model = SkillDetailModel.of(load(id), tier, 42);
                for (SkillDetailModel.Entry entry : model.entries()) {
                    if (!entry.markerKey().isEmpty()) {
                        keys.add(entry.markerKey());
                    }
                    for (SkillDetailModel.Line line : entry.numberPanel()) {
                        if (!line.key().isEmpty()) {
                            keys.add(line.key());
                        }
                    }
                    for (SkillDetailModel.Row row : entry.rows()) {
                        keys.add(row.labelKey());
                        keys.add(row.headingKey());
                        row.icon().ifPresent(type -> keys.add(BookText.effectShortKey(type)));
                    }
                }
            }
        }
        // The two rows only a synthetic tier can produce, so the shipped data cannot cover them.
        keys.add(BookText.LABEL_UNIMPLEMENTED);
        keys.add(BookText.effectShortKey(EffectType.AREA_CHARGE));
        assertTrue(keys.size() > 20, "sanity: the shipped characters exercise this many keys, got " + keys);
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!BookText.LANG_KEYS.contains(key)) {
                missing.add(key);
            }
        }
        assertEquals(List.of(), missing, "the detail page asks for keys no language file is required to hold");
    }

    /** The panel is a flat list, and its line count is exactly that list's length. */
    @Test
    void thePanelLineCountMatchesThePanel() throws Exception {
        for (SkillDetailModel.Entry entry : SkillDetailModel.of(load("pecorine"), 2, 30).entries()) {
            List<SkillDetailModel.Line> panel = entry.numberPanel();
            assertEquals(entry.numberLineCount(), panel.size());
            assertEquals(SkillDetailModel.Line.Kind.NAME, panel.get(0).kind());
            assertEquals(entry.name(), panel.get(0).value());
            assertEquals(1 + entry.rows().size()
                            + entry.rows().stream().mapToInt(row -> row.numbers().size()).sum(),
                    panel.size());
        }
    }

    /** A {@code null} definition draws nothing rather than throwing: this is a render path. */
    @Test
    void aNullDefinitionIsEmpty() {
        assertEquals(SkillDetailModel.EMPTY, SkillDetailModel.of(null, 2, 50));
        assertTrue(SkillDetailModel.of(null, 2, 50).isEmpty());
        assertEquals(List.of(), SkillDetailModel.EMPTY.columns());
    }

    /** The columns handed to the layout are the entries, in order, titled as the page draws them. */
    @Test
    void theColumnsTheLayoutSeesAreTheEntries() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("pecorine"), 2, 30);
        List<SkillDetailLayout.Column> columns = model.columns();
        assertEquals(model.size(), columns.size());
        for (int index = 0; index < columns.size(); index++) {
            assertEquals(model.entry(index).name(), columns.get(index).title());
            assertEquals(model.entry(index).rows().size(), columns.get(index).rows());
        }
        assertTrue(columns.get(0).rows() > 0, "the ultimate's column has rows of its own");
    }

    /** The formatting rules the panel relies on, in one place. */
    @Test
    void numbersAndNumeralsReadTheWayAPlayerWantsThem() {
        assertEquals("I", SkillDetailModel.roman(1));
        assertEquals("V", SkillDetailModel.roman(5));
        assertEquals("7", SkillDetailModel.roman(7), "past the ladder's own length, the number is honest");
        assertEquals("9", SkillDetailModel.number(9.0), "no trailing .0");
        assertEquals("9.4", SkillDetailModel.number(9.4));
        assertEquals("20", SkillDetailModel.number(20.0));
        assertEquals("—", SkillDetailModel.number(Double.NaN));
        assertEquals("20%", SkillDetailModel.percent(0.2));
        assertEquals("130%", SkillDetailModel.percent(1.3));
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static int tierOf(SkillDetailModel model, String entryName, EffectType type) {
        return row(model, entryName, type).tier();
    }

    private static SkillDetailModel.Row row(SkillDetailModel model, String entryName, EffectType type) {
        return find(model, entryName).rows().stream().filter(row -> row.type() == type)
                .findFirst().orElseThrow(() -> new AssertionError(entryName + " has no " + type + " row"));
    }

    // ---------------------------------------------------------------------------------------
    // Task 58: the three crit numbers the book did not show
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The per-skill crit multiplier is a row</b> (task 55's channel, which had none).
     *
     * <p>镜华's 6★ 「本技能出现暴击时，造成的伤害值从 2 倍提升至 4 倍」 lives in the data as
     * {@code critMultiplier: 4.0}; before this task it was invisible, so a player could not tell her 6★ crit from
     * anybody else's. It is drawn on the damage it modifies — the ultimate's damage row, and the {@code extra_damage}
     * row of her 专属 {@code ice_lance} — and a skill that declares none shows no row at all.
     */
    @Test
    void thePerSkillCritMultiplierIsARow() throws Exception {
        SkillDetailModel sixStar = SkillDetailModel.of(load("kyoka"), 3, 60);
        assertEquals("\u00d74", numbers(row(sixStar, "宇宙蓝色行星", EffectType.EXTRA_DAMAGE))
                .get(BookText.LABEL_CRIT_MULTIPLIER),
                "her 6★ ultimate's crit is 4×, and the damage row is where that number belongs");
        // The 5★ ultimate declares none, so it must show none: the global 1.5× still applies to it.
        SkillDetailModel fiveStar = SkillDetailModel.of(load("kyoka"), 1, 60);
        assertNull(numbers(row(fiveStar, "宇宙蓝色闪光", EffectType.EXTRA_DAMAGE))
                .get(BookText.LABEL_CRIT_MULTIPLIER),
                "a tier that declares no multiplier must not show one — absence means the global applies");

        // Her 专属 `extra_damage` carries the same channel, at the 专武 rung.
        SkillDetailModel upgraded = SkillDetailModel.of(load("kyoka"), 2, 60);
        assertEquals("\u00d74", numbers(row(upgraded, "冰枪突刺+", EffectType.EXTRA_DAMAGE))
                .get(BookText.LABEL_CRIT_MULTIPLIER));
        // ... and the base version, which declares nothing, shows nothing.
        SkillDetailModel base = SkillDetailModel.of(load("kyoka"), 1, 60);
        assertNull(numbers(row(base, "冰枪突刺", EffectType.EXTRA_DAMAGE))
                .get(BookText.LABEL_CRIT_MULTIPLIER));
    }

    /**
     * <b>The character's own 暴击率 is a row in the passive column.</b>
     *
     * <p>「角色册显示新数值」. The chance is not one skill's number: it is the attribute she grants the player while
     * she holds a party slot, so it is drawn with the passives — the EX column — and its value is the one the applier
     * reads ({@code CritChances.chanceOf}), so the book cannot state a chance the game does not grant.
     */
    @Test
    void theCharactersOwnCritChanceIsARow() throws Exception {
        // Every value now includes the 400-point gear share this build stands in for, because it ships no
        // equipment — see CritChances.GEAR_POINTS. So the book states the number the applier really grants.
        assertEquals("22.5%", passiveNumbers(SkillDetailModel.of(load("chika"), 1, 42))
                .get(BookText.LABEL_CRIT_CHANCE), "千歌's 50-point score plus the gear share -> 22.5%");
        assertEquals("23.8%", passiveNumbers(SkillDetailModel.of(load("rino"), 1, 42))
                .get(BookText.LABEL_CRIT_CHANCE), "璃乃's 75-point score plus the share -> 23.75%, shown as 23.8%");
        assertEquals("22%", passiveNumbers(SkillDetailModel.of(load("djeeta"), 1, 42))
                .get(BookText.LABEL_CRIT_CHANCE), "a kit with no crit clause is on the low band");
        assertEquals("30%", passiveNumbers(SkillDetailModel.of(load("suzume"), 1, 42))
                .get(BookText.LABEL_CRIT_CHANCE), "铃莓's 200-point score plus the share -> 30%");
    }

    /** A character who declares no chance gets no row: an honest absence, not a 0% line. */
    @Test
    void anUndeclaredCritChanceDrawsNoRow() throws Exception {
        CharacterDef djeeta = load("djeeta");
        CharacterDef bare = new CharacterDef(djeeta.schemaVersion(), djeeta.displayName(), djeeta.dimension(),
                djeeta.portrait(), djeeta.model(), djeeta.effects(), djeeta.ub(), djeeta.growth(),
                new com.astrum.pcrd.api.v1.AffectionDef(djeeta.affection().tier2Threshold(),
                        djeeta.affection().tier3Threshold(), djeeta.affection().skills()),
                djeeta.skillName(), djeeta.avatar(), djeeta.icon1st(), djeeta.icon3rd(), djeeta.icon6th(),
                djeeta.still(), djeeta.still6th());
        assertEquals(0.0, com.astrum.pcrd.impl.effect.CritChances.chanceOf(bare), 1e-12,
                "the rebuilt definition declares no chance");
        assertNull(passiveNumbers(SkillDetailModel.of(bare, 1, 42)).get(BookText.LABEL_CRIT_CHANCE),
                "no declaration is no row");
    }

    /**
     * <b>千歌's timed crit-damage buff shows its percentage</b>, not a tier.
     *
     * <p>{@code crit_damage_up} is the one party effect whose magnitude is the wiki's own percentage rather than a
     * rung, so the panel shows 「暴击伤害 28%」 beside its 18 seconds — and no 「I」, because a tier a pack never wrote
     * would be an invented number.
     */
    @Test
    void theTimedCritDamageBuffShowsItsPercent() throws Exception {
        SkillDetailModel model = SkillDetailModel.of(load("chika"), 3, 60);
        SkillDetailModel.Row row = find(model, "风灵交响乐").rows().stream()
                .filter(one -> one.type() == EffectType.CRIT_DAMAGE_UP).findFirst()
                .orElseThrow(() -> new AssertionError("her 6★ ultimate must show the crit-damage buff"));
        Map<String, String> values = numbers(row);
        assertEquals("28%", values.get(BookText.LABEL_CRIT_DAMAGE));
        assertEquals("18", values.get(BookText.LABEL_SECONDS));
        assertEquals("8", values.get(BookText.LABEL_RADIUS));
        assertNull(values.get(BookText.LABEL_TIER), "the percentage is not a rung, so no tier row");
        assertEquals(0, row.tier(), "and the row wears no tier marker");
    }

    /** The four labels the new rows ask for are in the key set {@code LangParityTest} walks. */
    @Test
    void theNewCritLabelsAreTranslated() {
        for (String key : List.of(BookText.LABEL_CRIT_MULTIPLIER, BookText.LABEL_CRIT_CHANCE,
                BookText.LABEL_CRIT_DAMAGE, BookText.LABEL_PASSIVE)) {
            assertTrue(BookText.LANG_KEYS.contains(key), key + " must be in BookText.LANG_KEYS");
        }
    }

    /** {@return the numbers of the first row of {@code model} that carries {@code label}, across every column} */
    private static Map<String, String> passiveNumbers(SkillDetailModel model) {
        Map<String, String> values = new LinkedHashMap<>();
        for (SkillDetailModel.Entry entry : model.entries()) {
            for (SkillDetailModel.Row row : entry.rows()) {
                for (SkillDetailModel.Number number : row.numbers()) {
                    values.putIfAbsent(number.labelKey(), number.value());
                }
            }
        }
        return values;
    }

    private static Map<String, String> numbers(SkillDetailModel.Row row) {
        Map<String, String> values = new LinkedHashMap<>();
        for (SkillDetailModel.Number number : row.numbers()) {
            values.put(number.labelKey(), number.value());
        }
        return values;
    }
}
