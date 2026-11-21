package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Task 60's resource half: the data says what the code now can do, and every closed gap's sentence is gone.</b>
 *
 * <h2>Why a resource test and not only the unit ones</h2>
 * A gap is closed twice: once in the kernel, and once in the character's JSON. The second half is the one that
 * shows to a player, because {@code extra} renders as 「尚未实现」 in the book — so a sentence left behind after the
 * mechanism ships tells a reader the skill does nothing while it is doing it. {@code e74a99b} shipped two such
 * sentences (矛依未's control immunity and 铃莓's tea-pot state) and they were removed then; this file pins the
 * same rule for task 60's five gaps and re-checks the two old ones.
 *
 * <h2>What it asserts</h2>
 * <ol>
 *   <li><b>由衣's barrier is physical-only</b> ({@code shield_phys}) and the other three are still the shared
 *       {@code shield} — the human's 「物理·魔法伤害的护盾」 is untouched.</li>
 *   <li><b>Panic's duration is the data's</b>: 绫音 2.5/3, 胡桃 5.5, 凯露 8, and no sentence says 「固定 1.5 秒」.</li>
 *   <li><b>The crit-chance buff</b> is on the three characters that name a score, at the converted percentages.</li>
 *   <li><b>可可萝's TP gain</b> is a timed {@code charge_rate_up} on the 6★ ultimate.</li>
 *   <li><b>雪菲's per-second charge</b> is the tally-scaled type with its two rates (the arithmetic is
 *       {@code CounterChargeRegenTest}'s).</li>
 *   <li><b>铃莓's two probability branches</b> are two {@code defence_down} clauses with their own {@code chance},
 *       and a zero chance is refused by {@code UbEffect.mismatch} rather than shipped as a clause that never fires.</li>
 *   <li><b>The two stale sentences</b> are still absent: 矛依未's control immunity and 铃莓's tea-pot state are
 *       implemented, so no {@code extra} may claim otherwise.</li>
 * </ol>
 */
class Task60ResourceTest {

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = Task60ResourceTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    /** {@return the shipped file's <b>text</b>, for the sentences that live outside the decoded record} */
    private static String raw(String id) throws Exception {
        try (InputStream in = Task60ResourceTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ 1. the two channels

    /**
     * <b>由衣's 6★ barrier is physical-only; the other three are unchanged.</b>
     *
     * <p>Her bwiki clause is 「无效【45+45*技能等级】<b>物理</b>伤害的护盾」. 望's and 佩可莉姆's are 「物理·魔法」 and
     * 静流's names no type, so those three stay on the shared {@code shield} — a {@code shield_phys} on them would
     * be a silent <em>nerf</em>, letting magic through a barrier the source says covers it.
     */
    @Test
    void yuisBarrierIsPhysicalOnlyAndTheOtherThreeAreUnchanged() throws Exception {
        assertTrue(hasUbEffect(load("yui").ub().enhanced(), EffectType.SHIELD_PHYS),
                "由衣's 6★ ultimate must grant shield_phys");
        assertTrue(!hasUbEffect(load("yui").ub().enhanced(), EffectType.SHIELD),
                "and not the shared barrier as well: one grant, one channel");
        for (String id : List.of("nozomi", "pecorine", "shizuru")) {
            assertTrue(hasAnyLine(load(id), EffectType.SHIELD), id + " must still have its shared barrier");
            assertTrue(!hasUbEffect(load(id).ub().enhanced(), EffectType.SHIELD_PHYS)
                            && !hasAnyLine(load(id), EffectType.SHIELD_PHYS)
                            && !hasUbEffect(load(id).ub().enhanced(), EffectType.SHIELD_MAGIC),
                    id + "'s barrier covers both halves in the source, so it must not be restricted");
        }
        // And the sentence that recorded the restriction as unwritable is gone from her extra.
        assertTrue(load("yui").ub().enhanced().extra().filter(text -> text.contains("不区分伤害类型")).isEmpty(),
                "the 「限定写不进去」 sentence is closed: it would render as 「尚未实现」");
    }

    // ------------------------------------------------------------------ 2. panic's duration

    /**
     * <b>The four panic durations are the data's, not a constant.</b>
     *
     * <p>This is the half the constant could not express: 绫音's 束缚 2.5 s (3 s on her 二专), 胡桃's 5.5 s and
     * 凯露's 8 s. The rest of each sentence — the gap claim 「被 PANIC 的固定 1.5 秒替代」 — must be gone.
     */
    @Test
    void thePanicDurationsAreTheSourcesAndTheGapSentencesAreGone() throws Exception {
        CharacterDef ayane = load("ayane");
        assertEquals(2.5, uberPanicSeconds(ayane.ub().base()), 1e-12, "绫音's 束缚 2.5 s");
        assertEquals(3.0, uberPanicSeconds(ayane.ub().enhanced()), 1e-12, "and 3 s on her 二专");
        assertEquals(5.5, uberPanicSeconds(load("kurumi").ub().base()), 1e-12, "胡桃's 击晕 5.5 s");
        assertEquals(5.5, uberPanicSeconds(load("kurumi").ub().enhanced()), 1e-12,
                "both of her tiers stun; the enhanced one used to say so only in extra");
        assertEquals(8.0, uberPanicSeconds(load("kyaru").ub().enhanced()), 1e-12, "凯露's 击晕 8 s");

        for (String id : List.of("ayane", "kurumi", "kyaru")) {
            CharacterDef def = load(id);
            for (UbTier tier : List.of(def.ub().base(), def.ub().enhanced())) {
                String text = tier.extra().orElse("");
                assertTrue(!text.contains("固定 1.5 秒") && !text.contains("panic 固定")
                                && !text.contains("PANIC 的固定"),
                        id + " still claims the panic is a fixed 1.5 s: " + text);
            }
        }
    }

    // ------------------------------------------------------------------ 3. the crit-chance buff

    /**
     * <b>The three crit-chance clauses are hooks, at the converted wiki scores.</b>
     *
     * <p>千歌's 专属 「50 物理暴击」, 静流's 二专 「100 物理暴击」, 铃莓's 二专 「200 物理暴击」 — 2.5%, 5% and 10% at
     * the build's 1 point = 0.05 percentage points ({@code CritChanceUpsTest} pins the encoding).
     */
    @Test
    void theThreeCritChanceClausesAreShippedAsHooks() throws Exception {
        assertEquals(2.5, hookPercent(load("chika"), "cheer_song"), 1e-12);
        assertEquals(5.0, hookPercent(load("shizuru"), "protection"), 1e-12);
        assertEquals(10.0, hookPercent(load("suzume"), "maid_yell"), 1e-12);
        for (String id : List.of("chika", "shizuru", "suzume")) {
            String raw = raw(id);
            assertTrue(!raw.contains("没有『队伍暴击率』这个效果类型")
                            && !raw.contains("仍然没有）") && !raw.contains("没有对应效果类型（暴击")
                            && !raw.contains("没有『队伍暴击率』"),
                    id + "'s data still records the crit-chance gap in its notes; the mechanism exists now");
        }
    }

    // ------------------------------------------------------------------ 4. the timed party TP gain

    /**
     * <b>可可萝's 「TP 获得量小幅提升」 is a timed party {@code charge_rate_up} on her 6★ ultimate.</b>
     *
     * <p>The old sentence claimed {@code UbEffect} could not carry the duration, which was never true —
     * {@code TieredParams.seconds} is exactly where a party effect's clock lives, and the executor has had a
     * {@code CHARGE_RATE_UP} arm since the rate kernel shipped. So the clause is data now: tier 1 (+25%), 18
     * seconds, the party default radius.
     */
    @Test
    void kokkorosSixStarTpGainIsATimedPartyBuff() throws Exception {
        UbEffect found = findUb(load("kokkoro").ub().enhanced(), EffectType.CHARGE_RATE_UP);
        assertNotNull(found, "她的 6★ ultimate must grant charge_rate_up");
        assertEquals(UbEffect.Scope.ALLY, found.scope(), "「己方全体」 — on the caster's own side");
        assertEquals(1, found.params().tier(), "「小幅」 is rung 1 of the five (25%)");
        assertEquals(18.0, found.params().seconds().orElseThrow(), 1e-12, "with a real clock");
        assertEquals(8.0, found.params().radius().orElseThrow(), 1e-12, "and the party default reach");
        assertTrue(load("kokkoro").ub().enhanced().extra().isEmpty(),
                "the 「装不下 charge_rate_up 的时长与档位」 sentence must be gone; it renders as 「尚未实现」");
    }

    // ------------------------------------------------------------------ 5. the probability field

    /**
     * <b>铃莓's two-decimal probability split is two clauses with their own {@code chance}.</b>
     *
     * <p>bwiki splits her defence down into 33% and 67% branches with different magnitudes. Both magnitudes fall on
     * rung 1 of {@code defence_down}'s ladder (178.7 and 89.3 against a 400-point rung), which is why the data wrote
     * one clause and put the other in {@code extra}. With {@code UbEffect.chance} the two branches are expressible,
     * mutually exclusive, and the roll is per clause per victim at settlement.
     */
    @Test
    void suzumesDefenceDownIsTwoProbabilityBranches() throws Exception {
        List<UbEffect> downs = new ArrayList<>();
        for (UbEffect effect : load("suzume").ub().base().effects()) {
            if (effect.type() == EffectType.DEFENCE_DOWN) {
                downs.add(effect);
            }
        }
        assertEquals(2, downs.size(), "33% and 67% are two clauses, not one");
        assertEquals(0.33, downs.get(0).chance(), 1e-12);
        assertEquals(0.67, downs.get(1).chance(), 1e-12);
        assertTrue(downs.get(0).chance() < downs.get(1).chance(),
                "the two branches are 33% and 67%, i.e. complementary rather than equal");
        for (UbEffect down : downs) {
            assertEquals(1, down.params().tier(), "both magnitudes land on rung 1; the split is probability, "
                    + "not rung");
            assertTrue(down.mismatch().isEmpty(), "each branch is a legal (victim, chance) pair");
        }
        assertTrue(load("suzume").ub().base().extra().isEmpty(),
                "the 「UbEffect 没有 chance 字段」 sentence must be gone; it renders as 「尚未实现」");
    }

    /**
     * <b>A probability is validated, and the default is "always".</b>
     *
     * <p>A pack written before the field existed decodes at {@code 1.0}; a zero or out-of-range one is a refusal with
     * a message rather than a clause that silently never fires.
     */
    @Test
    void theChanceFieldDecodesValidatesAndDefaultsToOne() {
        String noChance = """
                {"scope":"victim","effect":"defence_down","effectParams":{"tier":1,"seconds":18.0}}""";
        UbEffect plain = UbEffect.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(noChance)).getOrThrow();
        assertEquals(1.0, plain.chance(), 1e-12, "an absent chance is 'every cast', the pre-field behaviour");

        String withChance = """
                {"scope":"victim","effect":"defence_down","chance":0.33,
                 "effectParams":{"tier":1,"seconds":18.0}}""";
        UbEffect rolled = UbEffect.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(withChance)).getOrThrow();
        assertEquals(0.33, rolled.chance(), 1e-12, "and a declared one survives the codec");
        assertTrue(rolled.mismatch().isEmpty());

        String never = """
                {"scope":"victim","effect":"defence_down","chance":0.0,
                 "effectParams":{"tier":1,"seconds":18.0}}""";
        UbEffect dead = UbEffect.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(never)).getOrThrow();
        assertTrue(dead.mismatch().isPresent(), "a clause that can never fire is refused, not shipped");
    }

    // ------------------------------------------------------------------ 6. the two old stale sentences

    /**
     * <b>矛盾: 矛依未's control immunity and 铃莓's tea-pot state are implemented, and no sentence claims otherwise.</b>
     *
     * <p>Both were closed in {@code e74a99b} (task 57's follow-up) and their {@code extra} sentences removed then.
     * This test keeps them removed: a character whose {@code extra} still describes an implemented state would show
     * 「尚未实现」 beside a skill that is doing it.
     */
    @Test
    void theTwoOldStaleSentencesAreStillAbsent() throws Exception {
        CharacterDef muimi = load("muimi");
        for (UbTier tier : List.of(muimi.ub().base(), muimi.ub().enhanced())) {
            String text = tier.extra().orElse("");
            assertTrue(!text.contains("控制") && !text.contains("免疫"),
                    "矛依未's extra must not claim the control immunity is unimplemented: " + text);
        }
        assertTrue(hasAnyLine(muimi, EffectType.CONTROL_IMMUNITY),
                "and it is implemented — an effect on her kit is the evidence");

        CharacterDef suzume = load("suzume");
        for (UbTier tier : List.of(suzume.ub().base(), suzume.ub().enhanced())) {
            String text = tier.extra().orElse("");
            assertTrue(!text.contains("茶壶") && !text.contains("tea_pot"),
                    "铃莓's extras must not claim the tea-pot state is unimplemented: " + text);
        }
        assertTrue(!suzume.effects().isEmpty(), "her swing chain is where the tea-pot state is granted");
    }

    // ------------------------------------------------------------------ helpers

    /** {@return whether {@code tier}'s effect list names {@code type}} */
    private static boolean hasUbEffect(UbTier tier, EffectType type) {
        return tier != null && tier.effects().stream().anyMatch(effect -> effect.type() == type);
    }

    /** {@return the first effect of {@code type} on {@code tier}, or {@code null}} */
    private static UbEffect findUb(UbTier tier, EffectType type) {
        return tier.effects().stream().filter(effect -> effect.type() == type).findFirst().orElse(null);
    }

    /** {@return whether any hook of any skill or ultimate names {@code type}} */
    private static boolean hasAnyLine(CharacterDef def, EffectType type) {
        for (EffectHook hook : def.effects()) {
            if (hook.type() == type) {
                return true;
            }
        }
        for (var ladder : def.affection().skills()) {
            if (ladder == null || ladder.tier2().isEmpty()) {
                continue;
            }
            for (EffectHook hook : ladder.tier2().get().effects()) {
                if (hook.type() == type) {
                    return true;
                }
            }
        }
        return hasUbEffect(def.ub().base(), type) || hasUbEffect(def.ub().enhanced(), type);
    }

    /** {@return the {@code seconds} of the tier's panic effect, or {@code NaN}} */
    private static double uberPanicSeconds(UbTier tier) {
        UbEffect panic = findUb(tier, EffectType.PANIC);
        if (panic == null || panic.params().seconds().isEmpty()) {
            return Double.NaN;
        }
        return panic.params().seconds().getAsDouble();
    }

    /** {@return the {@code percent} of one affection skill's {@code crit_chance_up} hook, or {@code NaN}} */
    private static double hookPercent(CharacterDef def, String skill) {
        for (var ladder : def.affection().skills()) {
            if (ladder == null || !skill.equals(ladder.skill()) || ladder.tier2().isEmpty()) {
                continue;
            }
            for (EffectHook hook : ladder.tier2().get().effects()) {
                if (hook instanceof EffectHook.CritChanceUp chance) {
                    return chance.percent();
                }
            }
        }
        return Double.NaN;
    }
}