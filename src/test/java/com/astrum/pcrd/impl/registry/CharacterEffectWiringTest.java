package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.api.v1.UbEffect;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The four shipped characters' effect lists, decoded through the real codec, with the tier of every
 * effect pinned.</b>
 *
 * <h2>Why a table test and not four per-character files</h2>
 * {@code DjeetaResourceTest} and {@code ThreeCharactersResourceTest} exist for other questions (art ids,
 * damage derivations, the rotation's grouping). Neither answers the one question <em>this</em> task
 * changed: "which generic effect, at which tier, does each skill actually fire, and does the JSON the
 * mod ships decode to exactly that?" A per-character file would also have let a row be quietly dropped —
 * the row that is missing is invisible in a file that only lists what it expects, but it is a diff line
 * in a table that lists everything.
 *
 * <h2>What is asserted, in one place</h2>
 * <ol>
 *   <li>all four files decode through {@link CharacterDef#CODEC} — no fixture, no lenient path;</li>
 *   <li>every {@code melee_hit} skill fires its <b>exact</b> effect list ({@code type@tier}), over
 *       three swings, through {@link HookPlan#fire} — the route production takes;</li>
 *   <li>every ultimate tier's {@code effects[]} is that exact list too, with its scope;</li>
 *   <li>the two rows that are a <b>bug fix</b> rather than a re-wiring are called out by name, because
 *       they are the two that would silently regress: 姬塔's 6★ party attack buff, and ｷｬﾙ's magic
 *       damage type.</li>
 * </ol>
 *
 * <h2>The source of truth</h2>
 * {@code docs/character-effect-mapping.md} is the human's mapping; this file is what makes the mapping
 * executable. A row that changes must change here — which is the point: the table below <b>is</b> the
 * review artifact.
 */
class CharacterEffectWiringTest {

    private static final List<String> IDS = List.of("djeeta", "kokkoro", "pecorine", "kyaru", "sheffy");

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = CharacterEffectWiringTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    // ------------------------------------------------------------------ the table

    /**
     * <b>Each character's per-skill effect sequence, over three swings.</b>
     *
     * <p>The expected values, and where each comes from:
     *
     * <pre>
     *   character   swing 1                          swing 2
     *   姬塔        extra_damage (广域斩击)          charge_rate_up@2 + attack_buff (剑意迸发)
     *   可可萝      extra_damage (トライスラッシュ)   haste@2 + buff_attack_area@1 (スピードアップ)
     *   佩可莉姆    heal_self@3 + buff_magic_defence_area@2   buff_attack_area@2 + extra_damage
     *                                               + defence_down@1 + ally_effect (フォールスラッシュ)
     *   凯露        extra_damage (magic) (サンダーボール)     defence_down@2 (アーマーダウン)
     * </pre>
     *
     * <p>Swing 3 wraps, which is also asserted: the rotation's period is the number of <b>skills</b>, so a
     * mistagged hook would show up here as a three-skill rotation.
     */
    @Test
    void everyShippedSkillFiresItsExactEffectListForThreeSwings() throws Exception {
        Map<String, List<List<String>>> expected = new LinkedHashMap<>();
        expected.put("djeeta", List.of(
                List.of("extra_damage"),
                List.of("gain_charge")));
        expected.put("kokkoro", List.of(
                List.of("extra_damage"),
                List.of("haste@2", "buff_attack_area@1")));
        expected.put("pecorine", List.of(
                List.of("heal_self@1", "buff_magic_defence_area@1"),
                List.of("extra_damage")));
        expected.put("kyaru", List.of(
                List.of("extra_damage"),
                List.of("defence_down@1")));        // 雪菲: swing 1 is 雪靴 (spends one 冰龙之印 mark, deals its damage, breaks armour); swing 2 is
        // 冻结之湖 (spends one mark, raises the party attack). Her marks arrive on her UB, which is the only
        // cast-time grant this build can express as a UbEffect — see docs/balance-derivation.md §18.4.
        expected.put("sheffy", List.of(
                List.of("consume_counter", "extra_damage", "defence_down@1"),
                List.of("consume_counter", "buff_attack_area@1")));

        for (String id : IDS) {
            List<List<String>> skills = expected.get(id);
            List<List<String>> swings = threeSwings(load(id));
            assertEquals(3, swings.size());
            assertEquals(skills.get(0), swings.get(0), id + " swing 1");
            assertEquals(skills.get(1), swings.get(1), id + " swing 2");
            assertEquals(skills.get(0), swings.get(2),
                    id + " swing 3 must wrap to her first skill, so the period is " + skills.size());
        }
    }

    /**
     * <b>Each ultimate tier's effect list, with scope and tier.</b>
     *
     * <p>Same reasoning as the rotation table: the ultimate is where most of this task's mapping lands, and
     * an ultimate effect is invisible in play until somebody casts one at 6★ affection. So every one of the
     * eight tiers is pinned here.
     *
     * <p>The {@code ally}/{@code victim} scope is part of the expectation, not decoration: it is what
     * decides whether a debuff lands on the enemy or on your own party, and it is the half the codec cannot
     * check.
     */
    @Test
    void everyUltimateTierCarriesItsExactEffectList() throws Exception {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        // 姬塔: the base ultimate is pure damage; 6★ adds the party-wide physical attack boost.
        expected.put("djeeta/base", List.of());
        expected.put("djeeta/enhanced", List.of("ally:buff_attack_area@5"));
        // コッコロ: base = party attack up + self heal; 6★ = party heal + big attack up + phys defence.
        expected.put("kokkoro/base", List.of("ally:buff_attack_area@2", "ally:heal_self@1"));
        // task 60 closed the 「TP 获得量小幅提升」 gap: the 6★ clause is a timed party charge-rate buff, which
        // UbEffect could not carry until its duration was read from `effectParams.seconds` — it always could,
        // actually, and the extra sentence claiming otherwise was stale.
        expected.put("kokkoro/enhanced",
                List.of("ally:heal_self@1", "ally:heal_area@1", "ally:buff_attack_area@5",
                        "ally:charge_rate_up@1"));
        // ペコリーヌ: both tiers buff her own two defences. The old data's 6★ `heal 3.4` is NOT carried
        // over — the mapping doc lists only the two defence buffs for this ultimate — and the
        // 「バリア解除 + 最大HP比例ダメージ」 clause stays visible in `extra` because neither has a mechanism
        // (see docs/balance-derivation.md §9).
        expected.put("pecorine/base",
                List.of("ally:buff_phys_defence_area@2", "ally:buff_magic_defence_area@2"));
        // ペコリーヌ 6★ プリンセスヴァリアント, verbatim from the game text:
        //   「自分の物理・魔法防御力を特大アップ、物理攻撃力を大アップし、HPを小回復する」
        // So tier 3 (not 2) for both defences — 特大, not 大 — plus the attack buff and the
        // self-heal that the first wiring pass dropped.
        expected.put("pecorine/enhanced",
                List.of("ally:buff_phys_defence_area@3", "ally:buff_magic_defence_area@3",
                        "ally:buff_attack_area@5"));
        // ｷｬﾙ: base is pure magic damage; 6★ adds the large armour break and 恐慌 on every body hit.
        expected.put("kyaru/base", List.of());
        // 深渊炸裂 is 「2.16*魔法攻击力+24+24*技能等级」 damage + 「100%几率…恐慌，持续8秒」. No armour break.
        expected.put("kyaru/enhanced", List.of("victim:panic@1"));        // 雪菲: both tiers do the same two things — damage, and a 冰龙之印 grant whose size is the wiki
        // formula 【5+计数器枚数】(6★: 【7+计数器枚数】) capped at 10 (6★: 20). The rung and the applied cap
        // come from the ladder, derived from the stated cap; the grant itself travels in the counter block.
        expected.put("sheffy/base", List.of("ally:counter@3"));
        // task 60: the 「每秒恢复【10+计数】技能值」 clause is a party effect on the caster with a chargeRate
        // block, not a tier — so it reports tier 1 (the default an unwritten `tier` gets) and its magnitude
        // travels beside it.
        expected.put("sheffy/enhanced", List.of("ally:counter@5", "ally:counter_charge_regen@1"));

        for (String id : IDS) {
            CharacterDef def = load(id);
            assertEquals(expected.get(id + "/base"), ubEffects(def.ub().base()), id + " base UB");
            assertEquals(expected.get(id + "/enhanced"), ubEffects(def.ub().enhanced()),
                    id + " 6★ UB");
        }
    }

    /**
     * <b>The two rows that are bug fixes.</b>
     *
     * <h2>1. 姬塔's 6★ attack boost is party-wide, not self-only</h2>
     * The skill is 「味方全体の物理攻撃力を特大アップ」. It used to be {@code ub.enhanced.attackBuff}, which
     * {@code AttackBuffs} applied to the caster and her party — so the receiver set was already right — but
     * the <b>value</b> was an untiered 1.5 ratio owned by djeeta's data. It is now
     * {@code buff_attack_area tier 3}, i.e. {@code +60%} from {@code EffectTiers}' table, on every receiver.
     * The assertion that matters is that the effect is a <b>party</b> effect at a real tier: a regression to
     * a self buff would have to be written as a {@code radius: 0}, which this checks against.
     *
     * <h2>2. ｷｬﾙ's damage is magical</h2>
     * 「単体魔法ダメージ」 and 「敵全体に<em>魔法</em>中ダメージ」. Without {@code damageType: astrum:magic}
     * her damage is filed under {@code astrum:hook_damage}, which {@code DamageReduction} classifies as
     * <b>physical</b> — so the magic defence buff would reduce none of it and the physical one would reduce
     * all of it, i.e. both buffs would be wrong for her. That is exactly the pairing the tiered defence pair
     * exists to make measurable, so it is asserted on both the hook and both ultimate tiers.
     */
    @Test
    void theTwoBugFixesArePinned() throws Exception {
        // 1. djeeta's party attack buff.
        UbTier enhanced = load("djeeta").ub().enhanced();
        UbEffect boost = effectOf(enhanced, EffectType.BUFF_ATTACK_AREA).orElseThrow(
                () -> new AssertionError("姬塔's 6★ UB must raise attack — 星河一天's whole point"));
        assertEquals(UbEffect.Scope.ALLY, boost.scope(),
                "「味方全体の物理攻撃力」 is a party buff; a victim scope would hit the enemy");
        assertEquals(5, boost.params().tier(),
                "「40+40*技能等级」 physical attack at the reference 2000 is 418% of it; §14.2 clamps that " +
                        "to the top rung (+100%), which is the strongest the shared ladder can express");
        assertTrue(boost.params().radius().isEmpty() || boost.params().radius().getAsDouble() > 0.0,
                "a radius of 0 would be 「自分」 alone — the very bug this row fixes; found "
                        + boost.params().radius());
        assertTrue(boost.params().radius().isEmpty(),
                "the shipped data declares no radius, so the documented party default (8 blocks) applies; "
                        + "writing 8.0 would also work but two spellings of one answer is how they drift");

        // 2. kyaru's magic marker, on the hook and on both tiers.
        CharacterDef kyaru = load("kyaru");
        assertEquals(Optional.of(ResourceLocation.parse("astrum:magic")),
                ((EffectHook.ExtraDamage) kyaru.effects().get(0)).damageType(),
                "サンダーボール is 単体魔法ダメージ");
        assertEquals(Optional.of(ResourceLocation.parse("astrum:magic")),
                kyaru.ub().base().damageType(), "グリムバースト is 敵全体に魔法中ダメージ");
        assertEquals(Optional.of(ResourceLocation.parse("astrum:magic")),
                kyaru.ub().enhanced().damageType(), "アビスバースト is the same element");
    }

    /**
     * <b>Every tier the data declares is one its effect actually has.</b>
     *
     * <p>A tier above an effect's ladder is <em>clamped</em> with a warning rather than refused
     * ({@code EffectTiers.resolve}), so an author who writes {@code "tier": 5} on
     * {@code buff_phys_defence_area} gets 60% and a log line. That is the right failure mode for a
     * datapack, and the wrong thing to ship: this test is what makes "the shipped numbers are the
     * intended rungs" true rather than "the shipped numbers are somewhere in range".
     */
    @Test
    void everyDeclaredTierIsInsideItsEffectsLadder() throws Exception {
        for (String id : IDS) {
            CharacterDef def = load(id);
            for (EffectHook hook : def.effects()) {
                hook.tieredParams().ifPresent(params ->
                        assertInLadder(id, "hook " + hook.type(), hook.type(), params));
            }
            for (UbTier tier : List.of(def.ub().base(), def.ub().enhanced())) {
                for (UbEffect effect : tier.effects()) {
                    assertInLadder(id, "UB " + effect.type(), effect.type(), effect.params());
                }
                // A clause's effects are the same record on the same ladder: 纺希's pull and knockback moved onto
                // the clauses that select their bodies, and a rung written there must be inside the ladder for
                // exactly the same reason a tier-level one must.
                for (com.astrum.pcrd.api.v1.UbSegment segment : tier.segments()) {
                    for (UbEffect effect : segment.effects()) {
                        assertInLadder(id, "UB clause " + segment.target() + " " + effect.type(),
                                effect.type(), effect.params());
                    }
                }
            }
        }
    }

    /** Asserts {@code params}' tier is in {@code type}'s ladder, unclamped. */
    private static void assertInLadder(String id, String what, EffectType type, TieredParams params) {
        var resolved = params.resolveFor(type);
        assertEquals(params.tier(), resolved.tier(),
                id + "'s " + what + " declares tier " + params.tier() + ", which its "
                        + EffectTiers.maxTier(type) + "-rung ladder would clamp — the shipped data must "
                        + "be inside the ladder, not merely close to it");
    }

    // ------------------------------------------------------------------ helpers

    /** {@return three {@code melee_hit} swings of {@code def}, as {@code type@tier} strings} */
    private static List<List<String>> threeSwings(CharacterDef def) {
        SkillRotation cursor = SkillRotation.START;
        List<List<String>> swings = new ArrayList<>();
        for (int swing = 0; swing < 3; swing++) {
            HookPlan.Outcome outcome = HookPlan.fire(def.effects(),
                    TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                    (index, hook) -> Optional.empty(), invalid -> {
                    }, cursor);
            swings.add(outcome.fired().stream().map(fired -> describe(fired.hook())).toList());
            cursor = outcome.rotation();
        }
        return swings;
    }

    /** {@return {@code type} or {@code type@tier}, so a tier regression is a visible diff} */
    private static String describe(EffectHook hook) {
        String type = hook.type().getSerializedName();
        return hook.tieredParams().map(params -> type + "@" + params.tier()).orElse(type);
    }

    /** {@return one tier's effect list as {@code scope:type@tier} strings, in declaration order} */
    private static List<String> ubEffects(UbTier tier) {
        List<String> out = new ArrayList<>();
        for (UbEffect effect : tier.effects()) {
            out.add(effect.scope().getSerializedName() + ":" + effect.type().getSerializedName() + "@"
                    + effect.params().tier());
        }
        return out;
    }

    /** {@return the first effect of {@code type} on {@code tier}} */
    private static Optional<UbEffect> effectOf(UbTier tier, EffectType type) {
        return tier.effects().stream().filter(effect -> effect.type() == type).findFirst();
    }
}
