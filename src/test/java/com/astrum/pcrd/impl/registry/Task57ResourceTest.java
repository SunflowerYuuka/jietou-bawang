package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.effect.States;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.OptionalDouble;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The five touched characters, decoded through the real codec, with every closed gap pinned.</b>
 *
 * <h2>Why the extras are asserted as text, not just the mechanics</h2>
 * {@code extra} renders as 「尚未实现」 on the skill-detail page, so a sentence that stays there after the
 * mechanic lands is a <b>lie in the UI</b> — the exact failure the human complained about. Conversely, a
 * sentence deleted while the mechanic is still missing is a silently dropped clause. So this test asserts both
 * halves per character: the new effect is in the decoded data, and the extra no longer names it.
 *
 * <h2>Why the data and not a fixture</h2>
 * A resource test decodes {@code src/main/resources/.../<id>.json} through {@link CharacterDef#CODEC}, the
 * loader's own path, so a pack edit that forgets one of these fields fails here.
 */
class Task57ResourceTest {

    private static final List<String> TOUCHED =
            List.of("misogi", "rei", "suzume", "christina", "muimi");

    private static CharacterDef load(String id) throws Exception {
        String path = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id
                + ".json";
        try (InputStream in = Task57ResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, id + ".json must be packaged at " + path);
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    /** Every touched file still decodes, and no ultimate declares a land hook with a foreign trigger. */
    @Test
    void everyTouchedFileDecodesAndItsUbHooksAreLegal() throws Exception {
        for (String id : TOUCHED) {
            CharacterDef def = load(id);
            assertEquals(Optional.empty(), def.ub().landHooks().map(hooks -> hooks.mismatch())
                            .orElse(Optional.empty()),
                    id + "'s ub.landHooks must only hold ub_land hooks");
        }
    }

    /**
     * <b>克莉丝提娜: physical immunity is implemented on both rungs, and the extras name only what is left.</b>
     *
     * <p>The source is 「使自身免疫物理伤害，持续【3+0.01×技能等级】秒」 = 5.08 s at Lv208, so both tiers carry a
     * {@code phys_immunity} ally effect at 5.08 s and radius 0. What remains missing is 必中/必暴 and
     * 无视 50 物理防御力 — the extras say exactly those and no longer claim the immunity is unimplemented.
     */
    @Test
    void christinaGainedPhysicalImmunityAndHerExtrasNameOnlyTheRemainingGaps() throws Exception {
        CharacterDef def = load("christina");
        for (UbTier tier : List.of(def.ub().base(), def.ub().enhanced())) {
            UbEffect immunity = effectOf(tier, EffectType.PHYS_IMMUNITY).orElseThrow(
                    () -> new AssertionError("her 「免疫物理伤害」 must be on the ultimate: " + tier.name()));
            assertEquals(UbEffect.Scope.ALLY, immunity.scope(),
                    "「自身」 is ally scope with radius 0, the shape every self buff uses");
            assertEquals(1, immunity.params().tier(),
                    "one rung: the channel is immunity, and 「5档」 is resistance V's provenance");
            assertEquals(5.08, immunity.params().seconds().getAsDouble(), 1e-9,
                    "3 + 0.01 × 208 from the bwiki formula, not the 1 s an earlier extra claimed");
            assertEquals(0.0, immunity.params().radius().getAsDouble(), 1e-9,
                    "radius 0 = the caster alone; a party sweep would be a different skill");
        }
        // <b>task 62 closed the rest of her list, so both `extra`s are gone entirely.</b> This block used to
        // require 暴击 and 无视 to still be visible; they are now real fields, and an `extra` naming a closed
        // gap renders as 「尚未实现」 — a lie. So the assertion flips: no extra at all, and the two channels are
        // asserted exactly where the sentence used to be.
        assertTrue(def.ub().base().extra().isEmpty(),
                "every clause on her base UB is expressed, so it must carry no gap note: "
                        + def.ub().base().extra().orElse(""));
        assertTrue(def.ub().enhanced().extra().isEmpty(),
                "and the same for her 6★: " + def.ub().enhanced().extra().orElse(""));
        assertTrue(def.ub().base().forcedCrit(),
                "「第一次 Hit 必定暴击」 is `forcedCrit` on the tier — no roll, the multiplier is a crit's");
        assertTrue(def.ub().enhanced().forcedCrit());
        assertEquals(OptionalDouble.of(0.5), def.ub().enhanced().armourPierce(),
                "「无视 50 物理防御力」 is a half of the target's armour for this hit (ArmourPierce inverts "
                        + "vanilla's curve rather than writing a modifier on the victim)");
        assertTrue(def.ub().base().armourPierce().isEmpty(),
                "her base UB has no such clause, so it must not pierce");
    }

    /** <b>矛依未: control immunity is a real granted state, and the extra no longer calls it unimplemented.</b> */
    @Test
    void muimiGainedControlImmunityAndItsExtraDropsThatSentence() throws Exception {
        CharacterDef def = load("muimi");
        UbEffect immunity = effectOf(def.ub().enhanced(), EffectType.CONTROL_IMMUNITY).orElseThrow(
                () -> new AssertionError("her 6★ 「免疫控制180秒」 must be granted by the ultimate"));
        assertEquals(UbEffect.Scope.ALLY, immunity.scope());
        assertEquals(1, immunity.params().tier());
        assertEquals(180.0, immunity.params().seconds().getAsDouble(), 1e-9);
        assertEquals(0.0, immunity.params().radius().getAsDouble(), 1e-9);
        // Task 63 closed the other two: 「追加 1 个 ID 125」 is the granted state `astrum:marker_125` (so the
        // 「层数 ≥ 1」 branches can read it), and the loop itself was already `secondRotation`'s carrier. Nothing
        // of her kit is left to record, so the sentence is gone rather than rewritten.
        assertTrue(def.ub().enhanced().extra().isEmpty(),
                "every clause of her 6★ is expressed, so no gap note may remain: "
                        + def.ub().enhanced().extra().orElse(""));
        assertEquals(Optional.of(net.minecraft.resources.ResourceLocation.parse("astrum:marker_125")),
                effectOf(def.ub().enhanced(), EffectType.ALLY_EFFECT).flatMap(UbEffect::vanillaEffect),
                "「追加 1 个标记」 is a named state grant now, on the state her second loop gates on");
    }

    /** <b>未奏希: her 6★ attack-down lands through the UB trigger; the crit clauses stay recorded.</b> */
    @Test
    void misogiEnhancedUltsLandsItsPhysicalAttackDebuff() throws Exception {
        CharacterDef def = load("misogi");
        List<EffectHook> hooks = def.ub().landHooksFor(true);
        assertEquals(1, hooks.size());
        EffectHook.ApplyEffect applied = (EffectHook.ApplyEffect) hooks.get(0);
        assertEquals(ResourceLocation.parse("minecraft:weakness"), applied.effect(),
                "「物理攻击力下降」 is vanilla weakness in this build's vocabulary (docs/derivations-batch-b §1.2)");
        assertEquals(4, applied.amplifier(), "「特大幅度」 = 4 levels on the adjective ladder");
        assertEquals(360, applied.duration(), "18 s");
        // <b>task 62 gave the victim side its own two channels</b>, so the note that said "no victim-side
        // channel" is closed and deleted (「降低敌方全体物理暴击」 → `crit_chance_down`,
        // 「暴击时造成的伤害降低 25%」 → `crit_damage_down`).
        assertTrue(def.ub().enhanced().extra().isEmpty(),
                "every clause of her 6★ is expressed, so no gap note may remain: "
                        + def.ub().enhanced().extra().orElse(""));
        assertTrue(effectOf(def.ub().enhanced(), EffectType.CRIT_CHANCE_DOWN).isPresent(),
                "「降低敌方全体物理暴击 18 秒」 is a real crit-chance debuff on the victims");
        assertTrue(effectOf(def.ub().enhanced(), EffectType.CRIT_DAMAGE_DOWN).isPresent(),
                "and 「暴击时造成的伤害降低 25%」 is the crit-damage half");
    }

    /** <b>怜: 风之刃 is a real state, granted by the 6★UB and read by 反击斩's gated increment.</b> */
    @Test
    void reiGrantsWindBladeAndGatesHerCounterSlashBranchOnIt() throws Exception {
        CharacterDef def = load("rei");
        UbEffect grant = effectOf(def.ub().enhanced(), EffectType.ALLY_EFFECT).orElseThrow(
                () -> new AssertionError("her 6★ 「赋予自身风之刃状态」 must be granted by the ultimate"));
        assertEquals(Optional.of(States.WIND_BLADE), grant.vanillaEffect(),
                "the state is a real registered marker, named by id — not a percentage buff pretending");
        assertEquals(18.0, grant.params().seconds().getAsDouble(), 1e-9);
        assertEquals(0.0, grant.params().radius().getAsDouble(), 1e-9, "only herself");
        assertEquals(Optional.empty(), def.ub().enhanced().extra(),
                "the wind-blade gap is closed, so the whole extra is gone (it renders as 「尚未实现」)");

        for (int tier : new int[] {2, 3}) {
            List<EffectHook> hooks = AffectionKernel.hooksAt(def, tier);
            List<EffectHook> gated = new ArrayList<>();
            for (EffectHook hook : hooks) {
                if (hook.triggerParams().requiresState().equals(Optional.of(States.WIND_BLADE))) {
                    gated.add(hook);
                }
            }
            assertFalse(gated.isEmpty(), "tier " + tier + " must carry the wind-blade branch");
            assertEquals(2, gated.size(),
                    "the branch is the damage increment (6.6) and the longer armour break (24 s): " + gated);
            assertEquals(6.6, damageOf(gated), 1e-9,
                    "13.3 branch − 6.7 base = 6.6, because the gate adds a branch rather than replacing one");
        }
    }

    /** <b>铃莓: the teapot state and its two branches, including the 6★UB's per-victim increment.</b> */
    @Test
    void suzumeGrantsTheTeapotAndGatesBothTeapotBranchesOnIt() throws Exception {
        CharacterDef def = load("suzume");
        List<EffectHook> maidYell = AffectionKernel.hooksAt(def, 2);
        EffectHook.AllyEffect grant = null;
        for (EffectHook hook : maidYell) {
            if (hook instanceof EffectHook.AllyEffect ally
                    && States.TEA_POT.equals(ally.effect())) {
                grant = ally;
            }
        }
        assertNotNull(grant, "the 二专 grants the teapot state (「使自身获得一层【西式小茶壶】」)");
        assertEquals(360, grant.duration(), "18 s");

        double increment = 0.0;
        for (EffectHook hook : AffectionKernel.hooksAt(def, 2)) {
            if (hook.triggerParams().requiresState().equals(Optional.of(States.TEA_POT))) {
                increment = ((EffectHook.ExtraDamage) hook).amount();
            }
        }
        assertEquals(1.7, increment, 1e-9, "专属 6.7 branch − 5.0 base = 1.7");

        List<EffectHook> land = def.ub().landHooksFor(true);
        assertEquals(1, land.size());
        EffectHook.ExtraDamage extra = (EffectHook.ExtraDamage) land.get(0);
        assertEquals(Optional.of(States.TEA_POT), extra.triggerParams().requiresState());
        assertEquals(4.0, extra.amount(), 1e-9, "6★UB 13.3 branch − 9.3 base = 4.0");
        assertEquals(Optional.of(ResourceLocation.parse("astrum:magic")), extra.damageType(),
                "her damage is magical, so the increment is filed under the same marker");
        assertEquals(Optional.empty(), def.ub().enhanced().extra(),
                "the teapot gap is closed, so the whole extra is gone");
    }

    // ------------------------------------------------------------------ helpers

    /** {@return the first effect of {@code type} on {@code tier}} */
    private static Optional<UbEffect> effectOf(UbTier tier, EffectType type) {
        return tier.effects().stream().filter(effect -> effect.type() == type).findFirst();
    }

    /** {@return the {@code extra_damage} amount among {@code hooks}, or {@code NaN}} */
    private static double damageOf(List<EffectHook> hooks) {
        for (EffectHook hook : hooks) {
            if (hook instanceof EffectHook.ExtraDamage extra) {
                return extra.amount();
            }
        }
        return Double.NaN;
    }
}