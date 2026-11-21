package com.astrum.pcrd.impl.party;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The roster's skill list names the current tier's skills.</b>
 *
 * <h2>What the human asked for, and why it needed a test</h2>
 * 「The roster screen must show the <b>current tier's skill names</b> (with {@code +} where applicable) — that
 * is the whole point of him asking.」
 *
 * <p>That is a rendering requirement with a data rule behind it, and the rule is where the defects live: a
 * book that shows 广域斩击+ at 6★ is showing the wrong skill, and nothing in the game would error. So the join
 * ({@link SkillRows}) is game-free and this file pins it against the four shipped characters at all three
 * tiers.
 *
 * <h2>Two properties, and why both are needed</h2>
 * <ol>
 *   <li><b>The name matches the tier</b> — {@code AffectionKernel.ladderFor(..).nameAt(tier)}, i.e. the same
 *       call the damage pipeline's hook resolution uses. A book naming a skill the game does not run is worse
 *       than no book.</li>
 *   <li><b>The hooks match the tier</b> — the row's hooks are the ones {@code AffectionKernel.hooksAt}
 *       resolved, so the icon and the name describe one skill set. A row whose name came from one tier and
 *       whose hooks came from another is the exact mis-join this file exists to catch.</li>
 * </ol>
 */
class SkillRowsTest {

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = SkillRowsTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static List<String> names(SkillRows rows) {
        return rows.rows().stream().map(SkillRows.Row::name).toList();
    }

    private static List<String> skills(SkillRows rows) {
        return rows.rows().stream().map(SkillRows.Row::skill).toList();
    }

    /**
     * <b>姬塔: three rungs, three different names, and the 6★ rename wins.</b>
     *
     * <p>This is the human's rule end to end: 广域斩击 → 广域斩击+ → 黄金耀光, with 剑意迸发 → 剑意迸发 →
     * 三重剑击, and the EX rung 龙卷的攻刃 → 龙卷的攻刃+ → 剑圣的天赋.
     */
    @Test
    void djeetaShowsThreeDifferentSkillNamesAtThreeTiers() throws Exception {
        CharacterDef djeeta = load("djeeta");
        assertEquals(List.of("广域斩击", "剑意迸发", "龙卷的攻刃", "疾风剑雨"), names(SkillRows.of(djeeta, 1)),
                "tier 1: base names, then the ultimate");
        assertEquals(List.of("广域斩击+", "剑意迸发", "龙卷的攻刃+", "疾风剑雨"), names(SkillRows.of(djeeta, 2)),
                "tier 2: only the skills that HAVE a plus rung gain it");
        assertEquals(List.of("黄金耀光", "三重剑击", "剑圣的天赋", "星河漫天"), names(SkillRows.of(djeeta, 3)),
                "tier 3: the renames beat the pluses, and the ultimate swaps too");
    }

    /** コッコロ: only the EX changes at tier 2, and nothing changes at tier 3. */
    @Test
    void kokkoroOnlyRenamesHerEx() throws Exception {
        CharacterDef kokkoro = load("kokkoro");
        assertEquals(List.of("掠阵三段击", "加速", "精灵的启示", "极光"), names(SkillRows.of(kokkoro, 1)));
        assertEquals(List.of("掠阵三段击", "加速", "精灵的启示+", "极光"), names(SkillRows.of(kokkoro, 2)));
        assertEquals(List.of("掠阵三段击", "加速", "精灵的启示+", "极光绽放"), names(SkillRows.of(kokkoro, 3)),
                "her 6★ renames only the ultimate");
    }

    /** ペコリーヌ: 午餐时间 gains a {@code +} (and a third clause), the ultimate renames at 6★. */
    @Test
    void pecorineGainsAPlusAndARenamedUltimate() throws Exception {
        CharacterDef pecorine = load("pecorine");
        assertEquals(List.of("午餐时间", "沉重一击", "公主之力", "公主突袭"), names(SkillRows.of(pecorine, 1)));
        assertEquals(List.of("午餐时间+", "沉重一击", "公主之力+", "公主突袭"),
                names(SkillRows.of(pecorine, 2)));
        assertEquals(List.of("午餐时间+", "沉重一击", "公主之力+", "公主勇袭"),
                names(SkillRows.of(pecorine, 3)));
    }

    /** キャル: 闪电球 gains a {@code +}, the ultimate renames at 6★. */
    @Test
    void kyaruGainsAPlusAndARenamedUltimate() throws Exception {
        CharacterDef kyaru = load("kyaru");
        assertEquals(List.of("闪电球", "护甲削弱", "黑暗之蚀", "格林炸裂"), names(SkillRows.of(kyaru, 1)));
        assertEquals(List.of("闪电球+", "护甲削弱", "黑暗之蚀+", "格林炸裂"), names(SkillRows.of(kyaru, 2)));
        assertEquals(List.of("闪电球+", "护甲削弱", "黑暗之蚀+", "深渊炸裂"), names(SkillRows.of(kyaru, 3)));
    }

    /**
     * <b>A row's hooks are the ones its tier resolves to</b> — the name and the body cannot come from
     * different rungs.
     *
     * <h2>The EX row is excluded, and that exclusion is the point</h2>
     * An EX row's hooks come from her <b>passive clauses</b>, not from {@code AffectionKernel.hooksAt} — EX has no
     * trigger (that is why it is a passive), so it is not in the resolved rotation at all. Counting it here would
     * assert that a skill which never fires is part of the firing list. What that row <em>does</em> carry is
     * asserted by {@link #theExRowCarriesHerPassiveClauses}.
     */
    @Test
    void everyRowCarriesItsOwnTiersHooks() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            CharacterDef def = load(id);
            for (int tier = 1; tier <= 3; tier++) {
                List<com.astrum.pcrd.api.v1.EffectHook> resolved = AffectionKernel.hooksAt(def, tier);
                int claimed = 0;
                for (SkillRows.Row row : SkillRows.of(def, tier).rows()) {
                    if (row.ultimate() || row.skill().equals(com.astrum.pcrd.api.v1.SkillLadder.EX_SKILL)) {
                        continue;
                    }
                    claimed += row.hooks().size();
                    for (var hook : row.hooks()) {
                        assertTrue(resolved.contains(hook),
                                id + "@" + tier + ": " + row.skill() + " claims a hook the tier does not run");
                    }
                }
                assertEquals(resolved.size(), claimed,
                        id + "@" + tier + ": every resolved hook must appear in exactly one row");
            }
        }
    }

    /** The ultimate is last, is flagged as such, and carries the name its own tier declares. */
    @Test
    void theUltimateIsTheLastRowAndIsFlagged() throws Exception {
        CharacterDef djeeta = load("djeeta");
        for (int tier = 1; tier <= 3; tier++) {
            SkillRows rows = SkillRows.of(djeeta, tier);
            SkillRows.Row last = rows.rows().get(rows.size() - 1);
            assertTrue(last.ultimate(), "tier " + tier + ": the ultimate is the last row");
            assertEquals(AffectionKernel.ubKey(), last.skill());
            assertEquals(tier >= 3 ? "星河漫天" : "疾风剑雨", last.name(),
                    "tier " + tier + ": the ultimate's name follows the same tier the skills do");
            assertTrue(last.hooks().isEmpty(), "the ultimate is not one of her rotating hooks");
        }
    }

    /**
     * <b>The EX row carries her passive's clauses.</b>
     *
     * <h2>What changed, and why the old assertion was deleted rather than adjusted</h2>
     * This test used to assert the opposite — {@code theExRowIsNamedButAppliesNothing}, with the comment
     * 「EX has no trigger in this build, so it is named but not fired」. That was true then and is not true now: EX
     * is a <b>passive applied on party enrolment</b>, so the row is how a player sees what her EX does. A test
     * that kept asserting "no hooks" would be asserting the gap this task closed.
     *
     * <p>So the assertion is now: the row has a name, and its hooks are the clauses of the rung that same tier
     * resolves — one row type, one renderer, and the data the book draws is the data enrolment applies.
     */
    @Test
    void theExRowCarriesHerPassiveClauses() throws Exception {
        CharacterDef djeeta = load("djeeta");
        SkillRows.Row ex = SkillRows.of(djeeta, 2).rows().stream()
                .filter(row -> row.skill().equals("ex")).findFirst().orElseThrow();
        assertEquals("龙卷的攻刃+", ex.name());
        assertEquals(1, ex.hooks().size(), "龙卷的攻刃+ grants one thing: the attack formula");
        // Task 39: the clause is a FLAT point formula now, not a percentage rung, because the bwiki
        // formula is 【240+15×技能等级】. The row still has a drawable hook, which is what this assertion is
        // about — the icon source — and its amount is the formula the passive applies.
        assertEquals(com.astrum.pcrd.api.v1.EffectType.FLAT_ATTACK_UP, ex.firstHook().orElseThrow().type(),
                "and it is a drawable hook, so the row has an icon source rather than falling back");
        assertEquals(new com.astrum.pcrd.api.v1.FlatAmount(240, 15),
                ex.firstHook().orElseThrow().flatAmount().orElseThrow(),
                "the formula the row shows is the formula the passive applies");
    }

    /**
     * <b>The {@code +} rung's extra clause reaches the row.</b> 可可萝's 精灵的启示+ adds
     * 「并小幅提升自身魔法防御力」, so the row is two lines at tier 2 and one at tier 1.
     */
    @Test
    void kokkorosExRowGainsItsSecondClauseAtTierTwo() throws Exception {
        CharacterDef kokkoro = load("kokkoro");
        SkillRows.Row tier1 = SkillRows.of(kokkoro, 1).rows().stream()
                .filter(row -> row.skill().equals("ex")).findFirst().orElseThrow();
        SkillRows.Row tier2 = SkillRows.of(kokkoro, 2).rows().stream()
                .filter(row -> row.skill().equals("ex")).findFirst().orElseThrow();
        assertEquals(1, tier1.hooks().size(), "the base EX is the attack rung alone");
        assertEquals(2, tier2.hooks().size(), "精灵的启示+ adds the magic-defence clause");
    }

    /** 佩可莉姆's EX is a vanilla max-health effect, and its row still gets a drawable hook. */
    @Test
    void pecorinesExRowIsDrawableEvenThoughItIsAVanillaEffect() throws Exception {
        CharacterDef pecorine = load("pecorine");
        SkillRows.Row ex = SkillRows.of(pecorine, 1).rows().stream()
                .filter(row -> row.skill().equals("ex")).findFirst().orElseThrow();
        assertEquals("公主之力", ex.name());
        assertEquals(1, ex.hooks().size());
        assertTrue(ex.firstHook().orElseThrow() instanceof com.astrum.pcrd.api.v1.EffectHook.AllyEffect,
                "a named vanilla effect is drawn through ally_effect's picture and border");
    }

    /** A null definition draws nothing rather than throwing: this is a render path. */
    @Test
    void aNullDefinitionIsEmpty() {
        assertTrue(SkillRows.of(null, 2).isEmpty());
        assertEquals(SkillRows.NONE, SkillRows.of(null, 1));
    }

    /** The rotation order is the list order: a player reading the book top to bottom reads her rotation. */
    @Test
    void theRowOrderIsTheRotationOrder() throws Exception {
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru")) {
            CharacterDef def = load(id);
            List<String> rows = skills(SkillRows.of(def, 1));
            assertFalse(rows.isEmpty());
            // One row per ROTATING SKILL, plus one per declared-but-unapplied rung (her EX), plus the
            // ultimate. The rotation count alone would not do: the book must also show the EX skill, whose
            // whole contribution is its name changing at 专武.
            long exRungs = def.affection().skills().stream().filter(SkillRowsTest::isExOnly).count();
            assertEquals(com.astrum.pcrd.impl.combat.SkillGroups.of(def.effects()).count() + exRungs + 1,
                    rows.size(),
                    id + ": rotating skills + declared-only skills + the ultimate");
        }
    }

    /** {@return whether {@code ladder} is the EX ladder — the one skill that is a passive rather than a trigger} */
    private static boolean isExOnly(com.astrum.pcrd.api.v1.SkillLadder ladder) {
        return ladder.isEx();
    }
}
