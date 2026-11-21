package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbSegment;
import com.astrum.pcrd.api.v1.UbTargetRule;
import com.astrum.pcrd.api.v1.UbTier;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>An ultimate's damage clause list, as the shipped data actually writers it.</b>
 *
 * <h2>Two jobs, and the order between them</h2>
 * <ol>
 *   <li><b>Nothing regressed.</b> Every character this build ships still decodes through
 *       {@link CharacterDef#CODEC} and still plans damage, and the ones that declare no clauses still declare
 *       none — i.e. they stay on the shorthand path whose arithmetic {@code UbSegmentPlanTest} pins against
 *       hand arithmetic. That is asserted <b>first</b>, because a schema change that quietly dropped a
 *       character would make every later assertion in this file look fine: a missing file asserts nothing.</li>
 *   <li><b>The six recorded gaps are closed, in the data.</b> 日和莉's two amounts, 美美's second-nearest and
 *       her second blast centre, 紬希's farthest/nearest displacement and her target-count scaling. Each was
 *       parked in {@code UbTier.extra}, which the book renders as 「尚未实现」 — so a closed gap that still
 *       carries its sentence is a lie on a player's screen, and the sentences are asserted <em>gone</em>.</li>
 * </ol>
 *
 * <h2>Why the numbers are pinned here rather than only in the derivation docs</h2>
 * The six values come from {@code docs/derivations-batch-a.md} §1/§5 and §C3, which are the human's records of
 * bwiki's formulas: 11070 → 10.0, 4981.5 → 4.5, 16605 → 15.0, 16605 → 15.0 (美美's second body), 20286 → 18.3,
 * 6642 → 6.0, 10147.5 → 9.2 + 0.5A per target. A doc cannot fail a build; this can.
 */
class UbSegmentsResourceTest {

    /** Every character this build ships. A new one belongs here as well as in the registry. */
    private static final List<String> IDS = List.of(
            "ayane", "chika", "christina", "djeeta", "hiyori", "kokkoro", "kurumi", "kyaru", "kyoka",
            "mimi", "misogi", "muimi", "nozomi", "pecorine", "rei", "rino", "saren", "sheffy", "shizuru",
            "suzume", "tsumugi", "yui");

    /** The three characters whose ultimates are clause lists now. */
    // task 64 added 咲恋: her UB damage carries the 「随损失的生命值」 term, and a shorthand `damage` has nowhere to
    // put a coefficient that only exists to be scaled. She is the fourth character to spell her ultimate as clauses.
    private static final List<String> CLAUSE_CHARACTERS = List.of("hiyori", "mimi", "tsumugi", "saren");

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = UbSegmentsResourceTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static List<UbTier> tiers(String id) throws Exception {
        CharacterDef def = load(id);
        return List.of(def.ub().base(), def.ub().enhanced());
    }

    /** {@return clause {@code index} of {@code tier}, failing the test rather than a later assertion} */
    private static UbSegment clause(UbTier tier, int index) {
        List<UbSegment> clauses = tier.segments();
        assertTrue(index < clauses.size(),
                "expected clause " + index + " but the tier declares " + clauses.size());
        return clauses.get(index);
    }

    // ------------------------------------------------------------------ 1. nothing regressed

    /**
     * <b>All 22 shipped characters decode, and each ultimate still plans damage.</b>
     *
     * <p>Decoding through the real codec — no fixture — and then asking the planner's own conversion
     * ({@link UbTier#damageSegments()}) for the clause list, which must hold at least one clause for every shipped
     * tier. A harm-only ultimate (千歌, 可可萝, 望, 优衣) declares {@code damage: 0.0} and still has its one
     * shorthand clause; it simply selects nobody, which is the behaviour it has always had.
     */
    @Test
    void everyShippedCharacterStillDecodesAndStillPlansDamage() throws Exception {
        for (String id : IDS) {
            CharacterDef def = load(id);
            assertNotNull(def.ub(), id + " must declare an ultimate");
            for (UbTier tier : List.of(def.ub().base(), def.ub().enhanced())) {
                assertNotNull(tier, id + " must declare both tiers");
                assertTrue(!tier.damageSegments().isEmpty(),
                        id + ": every tier converts to at least one clause, or it would plan nothing");
                assertTrue(tier.validateEffects().result().isPresent(),
                        id + ": " + tier.validateEffects().error().map(e -> e.message()).orElse("?"));
            }
        }
    }

    /**
     * <b>The characters that never wrote a clause list still write none</b> — which is what keeps them on the
     * shorthand path, and therefore unchanged.
     */
    @Test
    void onlyTheClauseCharactersDeclareClauses() throws Exception {
        for (String id : IDS) {
            for (UbTier tier : tiers(id)) {
                if (CLAUSE_CHARACTERS.contains(id)) {
                    assertTrue(!tier.segments().isEmpty(), id + " spells its damage as clauses");
                } else {
                    assertTrue(tier.segments().isEmpty(),
                            id + " must stay on the shorthand: hits × damage on the nearest body, or in the radius");
                }
            }
        }
    }

    /**
     * <b>The shorthand is still there where it was</b>: a tier with no clauses keeps the numbers it shipped with.
     *
     * <p>姬塔's ultimate is the fixture the first version of this planner was written against: 4 × 20.0 at 5★ and
     * 4 × 31.3 at 6★, both on the nearest body. If a later edit made {@code hits} or {@code damage} optional
     * <em>and unused</em> for a tier that declares no clauses, these numbers would silently become a no-damage
     * ultimate.
     */
    @Test
    void theShorthandTiersKeepTheirNumbers() throws Exception {
        CharacterDef djeeta = load("djeeta");
        assertEquals(4, djeeta.ub().base().hits());
        assertEquals(20.0, djeeta.ub().base().damage(), 1e-9);
        assertEquals(4, djeeta.ub().enhanced().hits());
        assertEquals(31.3, djeeta.ub().enhanced().damage(), 1e-9);
        // And the conversion carries them onto the one clause, so the planner and the book read the same numbers.
        UbSegment implicit = djeeta.ub().enhanced().damageSegments().get(0);
        assertEquals(4, implicit.hits());
        assertEquals(31.3, implicit.damage().orElseThrow(), 1e-9);
        assertEquals(UbTargetRule.NEAREST, implicit.target(), "no radius is the nearest-body form");
    }

    // ------------------------------------------------------------------ 2. the six gaps are gone

    /**
     * <b>The six {@code extra} sentences are gone.</b>
     *
     * <p>Each of them described a clause this build could not express. All six are clause lists now, so a
     * sentence describing the gap would render as 「尚未实现」 on the 技能详情 page for work that is done.
     */
    @Test
    void theSixRecordedGapsNoLongerAppearAsUnimplemented() throws Exception {
        for (String id : CLAUSE_CHARACTERS) {
            for (UbTier tier : tiers(id)) {
                assertTrue(tier.extra().isEmpty(),
                        id + " must not keep an `extra` sentence for a gap it just closed: "
                                + tier.extra().orElse(""));
            }
        }
    }

    /**
     * <b>日和莉: the nearest body for one amount, everything in a radius for another — at both rungs.</b>
     *
     * <p>Base 「对最近的敌人 2.4A+30+30L = 11070 → 10.0」 and 「对范围半径400内全部敌人 1.08A+13.5+13.5L = 4981.5
     * → 4.5」; 6★ 「4.8A+60+60L = 22140 → 20.0」 and 「3.6A+45+45L = 16605 → 15.0」 with the radius reading 600 →
     * 6.0 blocks, the same 100-units-to-a-block scale the rest of her data uses.
     */
    @Test
    void hiyoriStrikesTheNearestBodyAndEveryoneInTheBlast() throws Exception {
        CharacterDef hiyori = load("hiyori");
        assertSegments(hiyori.ub().base(), new double[] {10.0, 4.5},
                new UbTargetRule[] {UbTargetRule.NEAREST, UbTargetRule.ALL_IN_RADIUS},
                new double[] {Double.NaN, 4.0});
        assertSegments(hiyori.ub().enhanced(), new double[] {20.0, 15.0},
                new UbTargetRule[] {UbTargetRule.NEAREST, UbTargetRule.ALL_IN_RADIUS},
                new double[] {Double.NaN, 6.0});
    }

    /**
     * <b>美美: the second-nearest body for 15.0, and a blast centred on the second body for 18.3.</b>
     *
     * <p>Base 「对第二近的一名敌人造成 3.6A+45+45L = 16605 → 15.0」 beside the nearest body's 7.8; 6★
     * 「前方范围900内 2.4A+30+30L = 11070 → 10.0」 (radius 6.0, the mod's largest area rung, as the derivation
     * recorded) and 「以前方第2名角色为中心、范围275内 4.5A+54+54L = 20286 → 18.3」 — 275 hundredths is 2.75 blocks,
     * and the centre is the <em>second</em> body, which is why that clause is not an
     * {@link UbTargetRule#ALL_IN_RADIUS}.
     */
    @Test
    void mimiStrikesTheSecondNearestAndBlastsAroundIt() throws Exception {
        CharacterDef mimi = load("mimi");
        assertSegments(mimi.ub().base(), new double[] {7.8, 15.0},
                new UbTargetRule[] {UbTargetRule.NEAREST, UbTargetRule.SECOND_NEAREST},
                new double[] {Double.NaN, Double.NaN});
        assertSegments(mimi.ub().enhanced(), new double[] {10.0, 18.3},
                new UbTargetRule[] {UbTargetRule.ALL_IN_RADIUS, UbTargetRule.AROUND_SECOND_NEAREST},
                new double[] {6.0, 2.75});
    }

    /**
     * <b>紬希: the farthest body pulled, the nearest pushed, and an amount that grows with the count.</b>
     *
     * <p>Base 「致死束缚 1.44A+18+18L = 6642 → 6.0」 blasts everyone in 6 blocks and panics them (the tier's own
     * effect list), then a {@code farthest} clause pulls that one body 3 blocks (「拉近 300」 → PULL rung 3, whose
     * rung <em>is</em> a distance in blocks) and a {@code nearest} clause knocks that one away (「击退 300」 →
     * KNOCKBACK rung 1, i.e. vanilla's own per-hit strength — the smallest rung, and the honest reading of a
     * three-block push).
     *
     * <p>6★ 「2.2A+27.5+27.5L = 10147.5 → 9.2」 plus 「+0.5A×目标数量」: {@code 0.5A = 1000 raw = 0.9} in this mod's
     * units, and the formula is {@code 9.2 + 0.9 × n}, so the authored amount is {@code 10.1} (n = 1) and each
     * extra body adds {@code 0.9}. Both halves are asserted, because a pack that wrote {@code 10.1} with no
     * scaling would look identical at one target and be wrong at every other count.
     */
    @Test
    void tsumugiPullsTheFarthestPushesTheNearestAndScalesWithTheCount() throws Exception {
        CharacterDef tsumugi = load("tsumugi");

        UbTier base = tsumugi.ub().base();
        assertEquals(3, base.segments().size(), "blast, farthest pull, nearest knockback");
        assertSegment(clause(base, 0), UbTargetRule.ALL_IN_RADIUS, 6.0, 6.0);
        assertSegment(clause(base, 1), UbTargetRule.FARTHEST, null, null);
        assertSegment(clause(base, 2), UbTargetRule.NEAREST, null, null);
        assertEffect(clause(base, 1), EffectType.PULL, 3);
        assertEffect(clause(base, 2), EffectType.KNOCKBACK, 1);
        // The panic is the tier's, not a clause's: every body the blast hit cannot act, which is what 束缚 is.
        assertTrue(base.effects().stream().anyMatch(effect -> effect.type() == EffectType.PANIC),
                "「束缚」 stays on the tier, because it applies to every body the ultimate hit");
        assertEquals(0.0, base.damageSegments().get(0).perExtraTarget(), 1e-9,
                "the base tier's count does not scale: the formula has no target term");

        UbTier enhanced = tsumugi.ub().enhanced();
        assertEquals(3, enhanced.segments().size());
        assertSegment(clause(enhanced, 0), UbTargetRule.ALL_IN_RADIUS, 6.0, 10.1);
        assertEquals(0.9, clause(enhanced, 0).perExtraTarget(), 1e-9, "+0.5A = 0.9 in this mod's units");
        assertSegment(clause(enhanced, 1), UbTargetRule.FARTHEST, null, null);
        assertSegment(clause(enhanced, 2), UbTargetRule.NEAREST, null, null);
        assertEffect(clause(enhanced, 1), EffectType.PULL, 3);
        assertEffect(clause(enhanced, 2), EffectType.KNOCKBACK, 1);
    }

    /**
     * <b>Every clause in the shipped data carries the reach its rule needs, and no more.</b>
     *
     * <p>A clause that needs a radius but declares none would select nobody at cast time — silently, because a
     * clause that lands on nobody is not an error anywhere. A clause that needs none and declares one is legal
     * (a rank inside a reach) but nobody does it yet, so the data is asserted to be exactly the two area rules
     * plus the three rank rules with no radius.
     */
    @Test
    void everyClauseHasTheReachItsRuleNeeds() throws Exception {
        for (String id : IDS) {
            for (UbTier tier : tiers(id)) {
                for (UbSegment segment : tier.segments()) {
                    if (segment.target().needsRadius()) {
                        assertTrue(segment.radius().isPresent(),
                                id + ": " + segment.target() + " cannot work without a radius");
                        assertTrue(segment.radius().orElseThrow() > 0.0,
                                id + ": a radius of 0 or less reaches nobody");
                    }
                }
            }
        }
    }

    /**
     * <b>No effect clause in the shipped data is empty.</b>
     *
     * <p>A clause with no damage exists to carry effects (紬希's pull and knockback). One that carries neither
     * would be a clause in the file that does nothing at all — the shape of a half-finished edit.
     */
    @Test
    void everyClauseEitherDealsDamageOrCarriesEffects() throws Exception {
        for (String id : IDS) {
            for (UbTier tier : tiers(id)) {
                for (UbSegment segment : tier.segments()) {
                    assertTrue(segment.dealsDamage() || segment.carriesEffects(),
                            id + ": a clause dealing no damage must at least carry effects, or delete it");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Asserts a tier's clause list: amounts, rules, radii (NaN = "no radius declared"). */
    private static void assertSegments(UbTier tier, double[] amounts, UbTargetRule[] rules,
            double[] radii) {
        assertEquals(amounts.length, tier.segments().size(), "clause count");
        for (int index = 0; index < amounts.length; index++) {
            assertSegment(tier.segments().get(index), rules[index],
                    Double.isNaN(radii[index]) ? null : radii[index], amounts[index]);
        }
    }

    private static void assertSegment(UbSegment segment, UbTargetRule rule, Double radius,
            Double amount) {
        assertEquals(rule, segment.target(), "target rule");
        if (radius == null) {
            assertTrue(segment.radius().isEmpty(), "expected no radius, got " + segment.radius());
        } else {
            assertEquals(radius, segment.radius().orElseThrow(), 1e-9, "radius");
        }
        if (amount == null) {
            assertTrue(segment.damage().isEmpty(), "an effect clause states no amount");
        } else {
            assertEquals(amount, segment.damage().orElseThrow(), 1e-9, "amount");
        }
        assertEquals(1, segment.hits(), "every shipped clause lands once");
    }

    private static void assertEffect(UbSegment segment, EffectType type, int tier) {
        List<String> found = new ArrayList<>();
        for (UbEffect effect : segment.effects()) {
            found.add(effect.scope() + ":" + effect.type() + "@" + effect.params().tier());
        }
        assertEquals(1, segment.effects().size(), "one effect per displacement clause, got " + found);
        UbEffect effect = segment.effects().get(0);
        assertEquals(UbEffect.Scope.VICTIM, effect.scope(), "a clause selects enemies, so its effects are victim-scoped");
        assertEquals(type, effect.type());
        assertEquals(tier, effect.params().tier());
    }
}
