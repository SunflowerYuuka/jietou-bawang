package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Every shipped number traces to a bwiki formula.</b> No invented values, and the derivation is arithmetic
 * a reader can redo.
 *
 * <h2>The reference point, and why one</h2>
 * The authoritative source is now
 * {@code assets/bwiki_按角色/<id>_<name>/角色数据.json}, where every skill is a <b>formula</b> in the
 * character's attack and level:
 *
 * <pre>
 *   姬塔  UB 疾风剑雨   【4.8*物理攻击力+60+60*技能等级】物理伤害
 *   姬塔  技能1 广域斩击 【1.6*物理攻击力+20+20*技能等级】物理伤害
 *   凯露  技能2 护甲削弱 「降低…【0.4+0.4*技能等级】物理·魔法防御力」
 * </pre>
 *
 * <p>A formula cannot be shipped as-is: the mod's damage is one number per hook, and the player's attack is
 * not the game's. So one <b>reference point</b> is stated for the whole mod —
 * <b>level 208, attack 2000</b> ({@code docs/balance-derivation.md} §14.1) — and every number is
 * {@code 20.0 × formula(reference) / anchor}, where the anchor is 姬塔's own ultimate, which is the value the
 * mod has always called 20.0. The ratio is scale-free, so the reference's exact value cannot change any
 * shipped number; it only makes the intermediate arithmetic checkable by hand.
 *
 * <h2>Why a table, and not one assertion per file</h2>
 * Same reason {@code CharacterEffectWiringTest} is a table: a row that is <em>missing</em> is invisible in a
 * file that only asserts what it expects, and it is a diff line in a table that lists everything. This table
 * lists every damage number, every heal/shield rung and every buff/debuff rung the four characters ship.
 *
 * <h2>What this does not cover, stated so it is not mistaken for coverage</h2>
 * Durations are bwiki's own numbers (18 s, 12 s, 8 s) and are asserted per row where the source gives one;
 * the <b>TP</b> quantities are not ratios at all — see {@link #theChargeNumbersAreAStatedConversion} — and
 * 「技能值上升量」, which the game has as a stat and this build does not, is recorded in the derivation
 * document as unimplemented rather than approximated.
 */
class BwikiTraceabilityTest {

    /** The stated reference level every formula is evaluated at. {@code docs/balance-derivation.md} §14.1. */
    private static final double LEVEL = 208.0;

    /** The stated reference attack/魔法攻击力. A round number on purpose; the ratio does not depend on it. */
    private static final double ATTACK = 2000.0;

    /**
     * The anchor: 姬塔's own UB formula at the reference, which the mod calls {@code 20.0}.
     *
     * <p>{@code 4.8 × 2000 + 60 + 60 × 208 = 9600 + 60 + 12480 = 22140}. The old anchor was her real
     * {@code 355293}; back-solving her attack from that gives 71406.875, and every ratio below is identical —
     * the reference is a presentation choice, not a balance one.
     */
    private static final double ANCHOR = 4.8 * ATTACK + 60.0 + 60.0 * LEVEL;

    private static final List<String> IDS = List.of("djeeta", "kokkoro", "pecorine", "kyaru", "sheffy", "muimi");

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = BwikiTraceabilityTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    /** {@return {@code 20.0 × (aA + b + bL) / anchor}, rounded to one decimal as every shipped number is} */
    private static double scalar(double a, double b) {
        return round1(20.0 * (a * ATTACK + b + b * LEVEL) / ANCHOR);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** {@return the {@code extra_damage} amount for {@code skill} at {@code tier}, or {@code NaN}} */
    private static double damageOf(CharacterDef def, String skill, int tier) {
        for (EffectHook hook : AffectionKernel.hooksAt(def, tier)) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skill)) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ the anchor

    /** The anchor is the bwiki UB formula, and it is the 20.0 the mod has always used. */
    @Test
    void theAnchorIsDjeetasOwnUltimateFormula() {
        assertEquals(22140.0, ANCHOR, 1e-9,
                "4.8*2000 + 60 + 60*208 = 22140; §14.1 records both this and the real-attack back-solve");
        assertEquals(20.0, 20.0 * ANCHOR / ANCHOR, 1e-12, "the anchor is 20.0 by definition");
    }

    /**
     * <b>Every damage number, one row each, against its own bwiki formula.</b>
     *
     * <p>The formula in the second column is quoted from {@code 角色数据.json} verbatim (minus the 【】), so a
     * reader can diff this table against the source without opening the code.
     */
    @Test
    void everyDamageNumberIsItsBwikiFormulaAtTheStatedReference() throws Exception {
        record Row(String id, String skill, int tier, double a, double b, String formula) {
        }
        List<Row> rows = List.of(
                // ---- 姬塔 ----------------------------------------------------------------
                new Row("djeeta", "wide_slash", 1, 1.6, 20.0,
                        "【1.6*物理攻击力+20+20*技能等级】"),
                new Row("djeeta", "wide_slash", 2, 2.25, 28.0,
                        "专属技能【2.25*物理攻击力+28+28*技能等级】"),
                new Row("djeeta", "wide_slash", 3, 4.8, 60.0,
                        "6星技能1 黄金耀光【4.8*物理攻击力+60+60*技能等级】"),
                new Row("djeeta", "weapon_burst", 3, 3.0, 37.5,
                        "特殊技能2 三重剑击【3*物理攻击力+37.5+37.5*技能等级】"),
                // ---- 可可萝 --------------------------------------------------------------
                new Row("kokkoro", "triple_slash", 1, 1.6, 20.0,
                        "【1.6*物理攻击力+20+20*技能等级】"),
                new Row("kokkoro", "triple_slash", 3, 1.6, 20.0,
                        "the 専武 version has the same damage formula plus buffs; the ladder carries no rename"),
                // ---- 佩可莉姆 ------------------------------------------------------------
                new Row("pecorine", "heavy_blow", 1, 1.6, 20.0,
                        "【1.6*物理攻击力+20+20*技能等级】"),
                new Row("pecorine", "heavy_blow", 3, 1.6, 20.0, "unchanged at every tier"),
                // ---- 凯露 ----------------------------------------------------------------
                new Row("kyaru", "lightning_orb", 1, 1.6, 20.0,
                        "【1.6*魔法攻击力+20+20*技能等级】"),
                new Row("kyaru", "lightning_orb", 2, 1.6, 20.0,
                        "the 専武 version's main damage is the same formula; it adds splash + 魔防ダウン"));

        for (Row row : rows) {
            CharacterDef def = load(row.id());
            assertEquals(scalar(row.a(), row.b()), damageOf(def, row.skill(), row.tier()), 1e-9,
                    row.id() + " " + row.skill() + " tier " + row.tier() + " must be "
                            + row.formula() + " at level 208 / attack 2000");
        }
    }

    /** The two ultimate tiers of each character, likewise. */
    @Test
    void everyUltimateDamageIsItsBwikiFormula() throws Exception {
        record Row(String id, boolean enhanced, double a, double b, String formula) {
        }
        List<Row> rows = List.of(
                new Row("djeeta", false, 4.8, 60.0, "【4.8*物理攻击力+60+60*技能等级】"),
                new Row("djeeta", true, 7.5, 93.75, "【7.5*物理攻击力+93.75+93.75*技能等级】"),
                new Row("pecorine", false, 2.4, 30.0, "【2.4*物理攻击力+30+30*技能等级】"),
                new Row("pecorine", true, 4.8, 60.0, "【4.8*物理攻击力+60+60*技能等级】"),
                new Row("kyaru", false, 1.08, 12.0, "【1.08*魔法攻击力+12+12*技能等级】"),
                new Row("kyaru", true, 2.16, 24.0, "【2.16*魔法攻击力+24+24*技能等级】"));

        for (Row row : rows) {
            CharacterDef def = load(row.id());
            double shipped = row.enhanced() ? def.ub().enhanced().damage() : def.ub().base().damage();
            assertEquals(scalar(row.a(), row.b()), shipped, 1e-9,
                    row.id() + (row.enhanced() ? " 6★" : " base") + " UB must be " + row.formula());
        }

        // 可可萝's ultimate deals no damage at all, which is correct rather than missing: 极光 is a pure
        // support ultimate, and giving a healer's ultimate a damage number would invent an attack the game
        // does not have.
        assertEquals(0.0, load("kokkoro").ub().base().damage(), 1e-9,
                "极光 is 「提升己方全体…攻击力 + 恢复自身生命值」 — no damage term exists in its formula");
        assertEquals(0.0, load("kokkoro").ub().enhanced().damage(), 1e-9, "nor in 极光绽放's");
    }

    // ------------------------------------------------------------------ heal / shield rungs

    /**
     * <b>Heals and shields: the formula's scalar, then the rung it buys on the 20% ladder.</b>
     *
     * <p>The conversion is stated once and applied to every row: a heal or shield of {@code u} units is
     * {@code u / 20} of a full health bar, and a rung is 20% of one, so
     * {@code tier = round((u / 20) / 0.20) = round(u / 4)}. Nothing here is judgement: the bwiki formula and
     * the ladder decide, and the derivation document records the two rows where that arithmetic lands between
     * rungs.
     */
    @Test
    void everyHealAndShieldRungFollowsFromItsFormula() throws Exception {
        record Row(String id, String skill, int tier, EffectType type, double a, double b, String formula) {
        }
        List<Row> rows = List.of(
                new Row("pecorine", "lunch_time", 1, EffectType.HEAL_SELF, 1.0, 5.0,
                        "恢复自身【物理攻击力+5+5*技能等级】生命值"),
                new Row("kokkoro", "triple_slash", 1, null, 0, 0, "(no heal on this skill)"));

        for (Row row : rows) {
            if (row.type() == null) {
                continue;
            }
            double u = scalar(row.a(), row.b());
            int expected = (int) Math.max(1, Math.min(5, Math.round(u / 4.0)));
            assertEquals(row.tier(), expected,
                    row.id() + " " + row.skill() + ": " + row.formula() + " = " + u
                            + " units = " + round1(u / 20.0 * 100.0) + "% of a 20-point bar, i.e. rung "
                            + expected);
        }

        // ペコリーヌ's 専武 「可吸收【22.5+22.5*技能等级】物理·魔法伤害的护盾，持续8秒」: 4702.5 raw, 4.25 units,
        // 21.2% of a bar → rung 1 of SHIELD's five. That is the number her JSON carries, and it is also the
        // first real use of SHIELD.
        double shieldUnits = scalar(0.0, 22.5);
        assertEquals(4.2, shieldUnits, 1e-9, "22.5+22.5*208 = 4702.5 raw -> 4.25 -> 4.2 units");
        assertEquals(1, Math.max(1, Math.min(5, Math.round(shieldUnits / 4.0))),
                "which is rung 1 of SHIELD's five 20% rungs");
        CharacterDef pecorine = load("pecorine");
        int shipped = -1;
        for (EffectHook hook : AffectionKernel.hooksAt(pecorine, 2)) {
            if (hook.type() == EffectType.SHIELD) {
                shipped = hook.tieredParams().orElseThrow().tier();
            }
        }
        assertEquals(1, shipped, "and pecorine.json's 専武 午餐时间 must carry exactly that rung");
    }

    // ------------------------------------------------------------------ buff / debuff rungs

    /** Every attack buff: its bwiki value as a fraction of the reference attack, then the rung it buys. */
    @Test
    void everyAttackBuffRungFollowsFromItsFormula() throws Exception {
        record Row(String id, String label, double value, int tier) {
        }
        // 20% a rung, so the rung is `round(value / (0.20 * ATTTACK))` — one expression, applied to each.
        List<Row> rows = List.of(
                new Row("kokkoro", "加速 【1.15+1.15*技能等级】", 1.15 + 1.15 * LEVEL, 1),
                new Row("kokkoro", "极光 【4.5+4.5*技能等级】", 4.5 + 4.5 * LEVEL, 2),
                new Row("kokkoro", "极光绽放 【18+18*技能等级】", 18.0 + 18.0 * LEVEL, 5),
                new Row("pecorine", "公主勇袭 【10+10*技能等级+0.05*生命值】",
                        10.0 + 10.0 * LEVEL + 0.05 * 20.0, 5),
                new Row("djeeta", "星河漫天 【40+40*技能等级】", 40.0 + 40.0 * LEVEL, 5));
        for (Row row : rows) {
            int rung = (int) Math.max(1, Math.min(5, Math.round(row.value() / (0.20 * ATTACK))));
            assertEquals(row.tier(), rung, row.id() + " " + row.label() + " = " + round1(row.value())
                    + " of " + ATTACK + " = " + round1(row.value() / ATTACK * 100.0) + "% → rung " + rung);
        }

        // And the shipped JSON carries those rungs. Checked through the tier block, which is the one accessor.
        assertEquals(1, rungOf(load("kokkoro"), "speed_up", 1, EffectType.BUFF_ATTACK_AREA),
                "加速 【1.15+1.15*技能等级】 = 240 of 2000 = 12%, which rounds to rung 1");
        assertEquals(-1, rungOf(load("kokkoro"), "speed_up", 1, EffectType.EXTRA_DAMAGE),
                "her 加速 carries no extra_damage, so there is no rung to report");
    }

    /** Every defence buff and defence debuff rung, against its own formula. */
    @Test
    void everyDefenceRungFollowsFromItsFormula() throws Exception {
        // ペコリーヌ's own defence stat at the reference is the denominator for her defence buffs; using the
        // shared reference attack stands in for it, which is stated in §14.4.
        record Row(String id, String label, double value, boolean buff, int tier) {
        }
        List<Row> rows = List.of(
                new Row("pecorine", "午餐时间 【0.8+0.8*技能等级】魔防", 0.8 + 0.8 * LEVEL, true, 1),
                new Row("pecorine", "公主突袭 【1.8+1.8*技能等级】物魔防", 1.8 + 1.8 * LEVEL, true, 2),
                new Row("pecorine", "公主勇袭 【2.4+2.4*技能等级】物魔防", 2.4 + 2.4 * LEVEL, true, 2),
                new Row("kyaru", "护甲削弱 【0.4+0.4*技能等级】物魔防", 0.4 + 0.4 * LEVEL, false, 1));
        for (Row row : rows) {
            double step = row.buff() ? 0.12 * ATTACK : 0.20 * ATTACK;
            int max = row.buff() ? 5 : 4;
            int rung = (int) Math.max(1, Math.min(max, Math.round(row.value() / step)));
            assertEquals(row.tier(), rung, row.id() + " " + row.label() + " = " + round1(row.value())
                    + " → rung " + rung);
        }
        // The 12% and 20% steps are the shared table's, not re-typed here.
        assertEquals(0.12, EffectTiers.ruleOf(EffectType.BUFF_PHYS_DEFENCE_AREA).stepPerTier(), 1e-12);
        assertEquals(0.20, EffectTiers.ruleOf(EffectType.DEFENCE_DOWN).stepPerTier(), 1e-12);
    }

    // ------------------------------------------------------------------ charge

    /**
     * <b>The TP numbers are a stated conversion, not a ratio</b> — and that is written down rather than hidden.
     *
     * <p>bwiki gives 姬塔's 技能2 as 「回复自身【150+1.5*技能等级】技能值」, which at level 208 is <b>462 TP</b>.
     * The game's TP bar is 1000; this mod's UB gauge is 100 points. So one charge point is ten TP, and 462 TP
     * is <b>46.2</b> charge. That is a unit conversion, not a balance decision, and it is the only reason the
     * number is what it is.
     *
     * <p><b>The limit, stated:</b> the two bars are not equivalent even after the conversion — the game's TP
     * also has a natural trickle and rate modifiers this mod expresses with {@code charge_rate_up}, so
     * {@code gain_charge} and {@code charge_rate_up} measure different things. §14.5 records the distinction.
     */
    @Test
    void theChargeNumbersAreAStatedConversion() throws Exception {
        double tpPerPoint = 10.0;
        assertEquals(462.0, 150.0 + 1.5 * LEVEL, 1e-9, "150 + 1.5*208 = 462 TP");
        assertEquals(46.2, 462.0 / tpPerPoint, 1e-9, "462 TP / 10 TP-per-point = 46.2 charge");

        CharacterDef djeeta = load("djeeta");
        assertEquals(46.2, chargeOf(djeeta, "weapon_burst", 1), 1e-9,
                "剑意迸发's shipped gain_charge is that conversion");
        assertEquals(49.2, chargeOf(djeeta, "weapon_burst", 3), 1e-9,
                "三重剑击 is 【180+1.5*技能等级】 = 492 TP = 49.2 charge");
        assertEquals(20.0, chargeOf(djeeta, "wide_slash", 2), 1e-9,
                "广域斩击+ 「自身回复200技能值」 = 200 TP = 20.0 charge");
    }

    // ------------------------------------------------------------------ the second loop

    /**
     * <b>矛依未's second rotation: every number is her 特殊技能 formula at the reference.</b>
     *
     * <h2>Why this belongs in the traceability file and not in the feature's own test</h2>
     * This file's claim is "no shipped number escapes the tables". A second skill set is a <b>new place</b> a
     * number can live, so a loop that shipped with a guessed 9.9 would be exactly the silent invention the rest
     * of this file exists to prevent — and the feature's own test would happily assert 9.9 because it reads the
     * same data. So the derivation lives here, next to the four characters' rows, evaluated through the same
     * {@link #scalar} and the same {@link #ANCHOR}.
     *
     * <p>The 「标记 125 层数 ≥ 1 时追加一段」 clauses are the <b>gated</b> branches: two extra
     * {@code extra_damage} hooks carrying {@code triggerParams.requiresState = astrum:marker_125}, whose amounts
     * are the source's own (SP1+ 3.9A+48.75+48.75L = 16.3, SP2 a repeat of its 14.1). The <em>ungated</em> hooks
     * are the whole of what each skill does without the mark — {@code extraDamageOf} reads those, and
     * {@code gatedDamageOf} reads the increment, so the two can never be confused for one another. The
     * unconditional SP1+ value is the source's own arithmetic too: both of its condition branches carry the same
     * 9.6A+120+120L, so with the mark it is 40.0 + 16.3 and without it 40.0. {@code muimi.json} says all of this;
     * this asserts the arithmetic
     * behind it.
     */
    @Test
    void theSecondLoopsNumbersAreHerSpecialSkillFormulas() throws Exception {
        record Row(String skill, int tier, double a, double b, String formula) {
        }
        List<Row> rows = List.of(
                // 特殊技能1 天楼回刃斩 无专武 【4.8*物理攻击力+60+60*技能等级】
                new Row("sp1", 1, 4.8, 60.0, "特殊技能1 天楼回刃斩 【4.8A+60+60L】"),
                // 特殊技能1+ 有专武/六星 【9.6*物理攻击力+120+120*技能等级】
                new Row("sp1", 2, 9.6, 120.0, "特殊技能1+ 天楼回刃斩+ 【9.6A+120+120L】"),
                new Row("sp1", 3, 9.6, 120.0, "and 6★ keeps the 专武 rung"),
                // 特殊技能2 天楼闪薙斩 【3.6*物理攻击力+40+40*技能等级】 — the same at both rungs.
                new Row("sp2", 1, 3.6, 40.0, "特殊技能2 天楼闪薙斩 【3.6A+40+40L】"),
                new Row("sp2", 2, 3.6, 40.0, "SP2 is not upgraded at 专武"));

        CharacterDef muimi = load("muimi");
        var spec = com.astrum.pcrd.impl.combat.SecondRotationRuntime.secondRotationOf(muimi).orElseThrow(
                () -> new AssertionError("muimi.json must declare ub.secondRotation"));
        for (Row row : rows) {
            assertEquals(scalar(row.a(), row.b()), extraDamageOf(spec.hooksAt(row.tier()), row.skill()), 1e-9,
                    "second loop " + row.skill() + " at tier " + row.tier() + " must be " + row.formula()
                            + " at level 208 / attack 2000");
        }

        // The追加 branches — task 63's other half: they are GATED HOOKS now, on the state 6★UB grants, so the
        // two amounts are in the hook list rather than in a sentence. 3.9A+48.75+48.75L and a repeat of SP2's value.
        assertEquals(16.3, round1(20.0 * (3.9 * ATTACK + 48.75 + 48.75 * LEVEL) / ANCHOR), 1e-9,
                "SP1+'s 「标记 ≥ 1 时追加」 clause is 3.9A+48.75+48.75L = 16.3");
        assertEquals(14.1, round1(20.0 * (3.6 * ATTACK + 40.0 + 40.0 * LEVEL) / ANCHOR), 1e-9,
                "SP2's追加 clause is 「再追加一次同值」 = 14.1 again");
        assertEquals(16.3, gatedDamageOf(spec.hooksAt(2), "sp1"), 1e-9,
                "and the shipped SP1+ clause carries exactly that amount, gated on the mark");
        assertEquals(14.1, gatedDamageOf(spec.hooksAt(2), "sp2"), 1e-9, "as does SP2's, at the same tier");
        for (EffectHook hook : spec.hooksAt(2)) {
            if (hook.type() == EffectType.EXTRA_DAMAGE
                    && hook.triggerParams().requiresState().isPresent()) {
                assertEquals(Optional.of(com.astrum.pcrd.impl.effect.States.MARKER_125),
                        hook.triggerParams().requiresState(),
                        "the gate is 「标记 ID 125」 — the state her 6★UB grants");
            }
        }

        // The duration is the wiki's own: 100 TP a second out of a 1000-point bar. §14.5's conversion makes this
        // mod's 100-point gauge last the same ten seconds at the same rate, so 10.0 is a conversion, not a
        // balance number.
        assertEquals(1000.0 / 100.0, spec.seconds(), 1e-9, "the TP bar divided by the per-second drain");
        assertEquals(2.0, spec.secondsPerKill(), 1e-9, "「+2 秒 / 击杀」 — the human's number, uncapped");
    }

    /**
     * {@return the {@code extra_damage} amount of the <b>gated</b> branch of {@code skill}, or {@code NaN}}
     *
     * <p>The ungated amount is {@link #extraDamageOf}'s; this is the increment the held state unlocks, which is why
     * the two are read apart. A gated branch that lost its {@code requiresState} would show up here as
     * {@code NaN} — and as an unconditional second hit in play.
     */
    private static double gatedDamageOf(List<EffectHook> hooks, String skill) {
        for (EffectHook hook : hooks) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skill)
                    && hook.triggerParams().requiresState().isPresent()) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    /** {@return the {@code extra_damage} amount of one skill in a hook list, or {@code NaN}} */
    private static double extraDamageOf(List<EffectHook> hooks, String skill) {
        for (EffectHook hook : hooks) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skill)) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ the whole tour

    /**
     * <b>Every number the four characters ship is on this test's tables.</b>
     *
     * <p>This is the "no invented values" assertion in its strongest form: it walks every hook of every tier
     * of every character and fails if it finds a numeric effect this file does not account for. A new skill
     * with a number somebody guessed would therefore have to be added to a table that quotes a bwiki formula.
     */
    @Test
    void noShippedNumberEscapesTheTables() throws Exception {
        List<String> unexplained = new ArrayList<>();
        for (String id : IDS) {
            CharacterDef def = load(id);
            for (int tier = 1; tier <= 3; tier++) {
                for (EffectHook hook : AffectionKernel.hooksAt(def, tier)) {
                    String why = explain(hook);
                    if (why != null) {
                        unexplained.add(id + "@" + tier + " " + hook.type() + ": " + why);
                    }
                }
            }
            for (var tier : List.of(def.ub().base(), def.ub().enhanced())) {
                for (var effect : tier.effects()) {
                    if (EffectTiers.isTiered(effect.type())
                            && effect.params().tier() > EffectTiers.maxTier(effect.type())) {
                        unexplained.add(id + " UB " + effect.type() + ": tier " + effect.params().tier()
                                + " is outside the " + EffectTiers.maxTier(effect.type()) + "-rung ladder");
                    }
                }
            }
        }
        assertEquals(List.of(), unexplained,
                "every shipped number must be traceable to a formula in this test's tables");
    }

    /**
     * {@return why {@code hook} carries no traceable number, or {@code null} when it is accounted for}
     *
     * <p>Enumerated over {@link EffectType} so a new value has to be given a rule here — a switch that fell
     * through to "fine" would let an unexplained number ship silently, which is the failure this whole file
     * exists to prevent. Every case answers {@code null} today, and that is the point: the value of the
     * enumeration is that the compiler refuses a new {@code EffectType} until somebody decides where its
     * numbers come from.
     */
    private static String explain(EffectHook hook) {
        return switch (hook.type()) {
            // Damage: the table above, and the ladder-resolution tests read every one.
            case EXTRA_DAMAGE, SPLASH, PERCENT_DAMAGE -> null;
            // Charge: §14.5's conversion, asserted in theChargeNumbersAreAStatedConversion.
            case GAIN_CHARGE, AREA_CHARGE, CHARGE_RATE_UP -> null;
            // Rungs: the ladder tests above and EffectTiers' own table.
            case HEAL_AREA, HEAL_SELF, BUFF_ATTACK_AREA, BUFF_PHYS_DEFENCE_AREA,
                 BUFF_MAGIC_DEFENCE_AREA, DEFENCE_DOWN, MAGIC_DEFENCE_DOWN, SHIELD, HASTE, PANIC,
                 REMOVE_SHIELD, BUFF_MAGIC_ATTACK_AREA -> null;
            // Not numbers at all: an effect id and a duration, a ratio, a stack cap, or a per-stack amount
            // that no shipped character declares.
            case APPLY_EFFECT, ALLY_EFFECT, LIFESTEAL, HEAL, ATTACK_BUFF, STACK, DETONATE -> null;
            // The two flat EX effects: their numbers are the wiki's own 【base+perLevel×技能等级】
            // coefficients, carried in `effectParams` and asserted from the shipped JSON by
            // FlatAttackTest.exAmountsAreTheWikiFormulas and the ExPassiveTest table. Not a rung, so the
            // ladder tests above deliberately do not see them.
            case FLAT_ATTACK_UP, FLAT_MAGIC_ATTACK_UP -> null;
            // The summon and the tally. `summon_spirit`'s number is its RUNG (the spirit count), which is
            // EffectTiers' table and SpiritsTest's; `counter`'s numbers are the wiki's 「【base+计数器枚数】个」
            // grant and the cap it states, which are checked against Counters' ladder in CounterTest; and
            // `consume_counter`'s are that same formula read backwards (how many marks leave, what each mark
            // she holds is worth). All three are coefficients from the bwiki, not derived magnitudes, so the
            // ladder arithmetic above deliberately does not see them.
            case SUMMON_SPIRIT, COUNTER, CONSUME_COUNTER -> null;
            // The six task-49 effects have no shipped character using them yet, so there is no bwiki coefficient
            // to trace. Their magnitudes are EffectTiers' ladder (which `EffectTiersTest` and the new
            // `TransferAndOverTimeTest` pin), not a per-character number this file could check.
            case TRANSFER_CHARGE, TRANSFER_HEAL, REGEN_SELF, REGEN_AREA,
                 CHARGE_REGEN_SELF, CHARGE_REGEN_AREA -> null;
            // task 56: a rung that IS the magnitude — a distance in blocks and a vanilla knockback strength —
            // and a taunt whose only number is the duration. All three are EffectTiers' ladder, pinned by
            // EffectTiersTest, not per-character coefficients this file could check.
            case PULL, KNOCKBACK, TAUNT -> null;
            // task 57: two immunity rungs whose only number is the duration, exactly like the taunt one line
            // up — EffectTiers' ladder, pinned by EffectTiersTest, not a per-character coefficient.
            case PHYS_IMMUNITY, CONTROL_IMMUNITY -> null;
            // task 58: the timed crit-damage buff. Its two numbers are the wiki's own — 「28%」 of crit damage and
            // 「18 秒」 — carried verbatim in `effectParams` (percent / seconds), not derived here, and not a rung:
            // CritDamageUpsTest pins the percent-to-amplifier encoding and the effect's modifier.
            case CRIT_DAMAGE_UP -> null;
            // task 60: the two typed barrier channels. Their magnitude is SHIELD's own ladder (20% of the
            // receiver's max health a rung), pinned by EffectTiersTest; the *channel* is not a number, so there
            // is nothing new for this file to trace — and 由衣's 9405 raw keeps its tier-2 rung.
            case SHIELD_PHYS, SHIELD_MAGIC -> null;
            // The timed crit-chance buff. Its number is the wiki's own score converted at the build's stated
            // 1 point = 0.05 percentage points — 千歌 50 -> 2.5%, 静流 100 -> 5%, 铃莓 200 -> 10% — carried as
            // `percent` rather than a rung. CritChanceUpsTest pins the encoding and the shipped values.
            case CRIT_CHANCE_UP -> null;
            // 「每秒恢复自身【10+「冰龙之印」】技能值」: the two numbers are the wiki's own coefficients (10 TP, and 1
            // TP a mark), carried verbatim as base / perMark at the 1 point = 10 TP conversion; the conversion
            // is the same §14.5 one GAIN_CHARGE uses, so nothing is derived here.
            case COUNTER_CHARGE_REGEN -> null;
            // The two victim-side crit debuffs. Their numbers are the wiki's own — 「100+1.25×技能等级」 points
            // converted at the build's stated 1 point = 0.05 percentage points (360 -> 18), and 「25%」 carried
            // verbatim — inside the same `percent` field the two crit buffs use, so the conversion has one
            // implementation (CritDownsTest pins it and the shipped values).
            case CRIT_CHANCE_DOWN, CRIT_DAMAGE_DOWN -> null;
        };
    }

    // ------------------------------------------------------------------ helpers

    private static int rungOf(CharacterDef def, String skill, int tier, EffectType type) {
        for (EffectHook hook : AffectionKernel.hooksAt(def, tier)) {
            if (hook.type() == type && hook.triggerParams().skillTag().orElse("").equals(skill)) {
                return hook.tieredParams().orElseThrow().tier();
            }
        }
        return -1;
    }

    private static double chargeOf(CharacterDef def, String skill, int tier) {
        for (EffectHook hook : AffectionKernel.hooksAt(def, tier)) {
            if (hook instanceof EffectHook.GainCharge charge
                    && hook.triggerParams().skillTag().orElse("").equals(skill)) {
                return charge.amount();
            }
        }
        return Double.NaN;
    }
}
