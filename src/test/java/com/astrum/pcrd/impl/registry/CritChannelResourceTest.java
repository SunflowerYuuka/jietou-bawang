package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.SkillUpgrade;
import com.astrum.pcrd.api.v1.UbEffect;
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
 * 镜华's and 璃乃's <b>per-skill crit multiplier</b>, as the shipped data actually declares it (task 55).
 *
 * <h2>Why this file reads the real JSON</h2>
 * The channel has two halves that must agree: the codec ({@code UbTier.critMultiplier},
 * {@code EffectHook.ExtraDamage.critMultiplier}) and the numbers this pack ships. A unit test of the
 * arithmetic cannot see a typo in a character file, and a character file that omits the field still
 * decodes — it just silently crits at the global 1.5, which is exactly the gap this task closed. So the
 * two characters are decoded here through {@link CharacterDef#CODEC} from their real resource path, and
 * their <b>values</b> are asserted, including that the {@code extra} sentence which used to record the gap
 * is gone (an {@code extra} describing a closed gap renders as 「尚未实现」 in the book, which would be a
 * lie).
 *
 * <h2>What is deliberately asserted as absent</h2>
 * The 5★ ultimates and every other extra-damage hook keep <b>no</b> value, i.e. the caster's global
 * multiplier: 鏡華's clause is on her 6★ ultimate and her 专属 only, and 璃乃's on her 6★ ultimate only.
 * A field leaked onto the base tier or onto an unrelated skill would make that skill crit for 4× in play,
 * and nobody here can see the game — so the absence is pinned.
 */
class CritChannelResourceTest {

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = CritChannelResourceTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be in resources: " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    /** {@return the {@code extra_damage} hooks of a list, in firing order} */
    private static List<EffectHook.ExtraDamage> extraDamages(List<EffectHook> hooks) {
        List<EffectHook.ExtraDamage> result = new ArrayList<>();
        for (EffectHook hook : hooks) {
            if (hook instanceof EffectHook.ExtraDamage extra) {
                result.add(extra);
            }
        }
        return result;
    }

    /** {@return the one {@code extra_damage} hook tagged with {@code skill}} */
    private static EffectHook.ExtraDamage extraDamageOf(List<EffectHook> hooks, String skill) {
        return extraDamages(hooks).stream()
                .filter(hook -> hook.triggerParams().skillTag().filter(skill::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no extra_damage hook tagged '" + skill + "' in " + hooks));
    }

    /**
     * <b>鏡華: 4× on the 6★ ultimate and on 冰枪突刺+ (专属), the global on everything else.</b>
     *
     * <p>「本技能出现暴击时，造成的伤害值从 2 倍提升至 4 倍」 is recorded twice in the source wiki: on the 6★
     * ultimate and on the unique-weapon skill. Both are {@code 4.0} here, and the {@code extra} sentence
     * that used to say the channel did not exist is gone.
     */
    @Test
    void kyokaCarriesFourTimesOnHerSixStarUltimateAndHerUniqueWeaponSkill() throws Exception {
        CharacterDef kyoka = load("kyoka");

        assertEquals(4.0, kyoka.ub().enhanced().critMultiplier().orElseThrow(), 1e-9,
                "鏡華's 6★UB 「从 2 倍提升至 4 倍」 is 4.0, a replacement for the global 1.5");
        assertTrue(kyoka.ub().base().critMultiplier().isEmpty(),
                "the 5★ ultimate has no such clause and must keep critting at the global multiplier");
        assertTrue(kyoka.ub().enhanced().extra().isEmpty(),
                "the gap is closed, so the `extra` tag must be gone — it would render as 「尚未实现」");

        SkillUpgrade unique = kyoka.affection().ladderFor("ice_lance").orElseThrow()
                .tier2().orElseThrow();
        EffectHook.ExtraDamage upgraded = extraDamageOf(unique.effects(), "ice_lance");
        assertEquals(4.0, upgraded.critMultiplier().orElseThrow(), 1e-9,
                "the 专属 of 冰枪突刺 carries the same 4×");
        assertEquals(8.1, upgraded.amount(), 1e-9, "and it is still the 专属's authored damage");

        assertTrue(extraDamageOf(kyoka.effects(), "ice_lance").critMultiplier().isEmpty(),
                "the base 冰枪突刺 (tier 1) has no such clause and keeps the global");
    }

    /**
     * <b>璃乃: 3.5× on the 6★ ultimate only, and her {@code extra} sentence is gone.</b>
     *
     * <p>「暴击时将造成 3.5 倍伤害」. Her other recorded crit gaps (45/75 物理暴击、15% 物理爆伤 on
     * 技能1/专属/二专) are <b>different things</b> — party/global crit buffs with no effect type — and stay
     * in {@code _notes}; they are not per-skill multipliers and must not borrow this field.
     */
    @Test
    void rinoCarriesThreePointFiveOnHerSixStarUltimateOnly() throws Exception {
        CharacterDef rino = load("rino");

        assertEquals(3.5, rino.ub().enhanced().critMultiplier().orElseThrow(), 1e-9,
                "璃乃's 6★UB 「3.5 倍伤害」 is 3.5, replacing the global 1.5");
        assertTrue(rino.ub().base().critMultiplier().isEmpty(),
                "the 5★ ultimate must keep critting at the global multiplier");
        assertTrue(rino.ub().enhanced().extra().isEmpty(),
                "the gap is closed, so the `extra` tag must be gone — it would render as 「尚未实现」");

        for (EffectHook.ExtraDamage hook : extraDamages(rino.effects())) {
            assertTrue(hook.critMultiplier().isEmpty(),
                    "no small skill of 璃乃 declares a per-skill multiplier: " + hook);
        }
    }

    /**
     * <b>千歌's 28% / 18 s crit-damage buff is <em>not</em> this channel — it is an effect type of its own
     * (task 58).</b>
     *
     * <p>Task 55's test of this name asserted the opposite: that the clause stayed in {@code extra}, because no
     * effect could carry a timed {@code astrum:crit_damage} modifier. Task 58 added
     * {@code EffectType.CRIT_DAMAGE_UP} (the shape {@code AttackBuffEffect} demonstrates: a {@code MobEffect}
     * declaring one {@code ADD_MULTIPLIED_TOTAL} modifier under a fixed id, expiring by vanilla's own clock), so the
     * gap is closed and the sentence must be <b>deleted</b> — {@code extra} renders as 「尚未实现」.
     *
     * <p>What still must <b>not</b> happen is the conflation this test has always guarded: the 28% must never migrate
     * to the tier's {@code critMultiplier}, which would make it reach only 千歌's own hits and leave every ally at the
     * global multiplier — the opposite of 「提升我方全体」.
     */
    @Test
    void chikasTimedGlobalCritBuffIsAnEffectTypeNowAndNotThePerSkillChannel() throws Exception {
        CharacterDef chika = load("chika");

        assertTrue(chika.ub().enhanced().critMultiplier().isEmpty(),
                "the 28% is a timed global buff, not a per-skill multiplier: putting it here would narrow it to "
                        + "her own hits");
        assertTrue(chika.ub().enhanced().extra().isEmpty(),
                "the gap is closed, so the `extra` sentence must be gone — it renders as 「尚未实现」");
        UbEffect buff = chika.ub().enhanced().effects().stream()
                .filter(effect -> effect.type() == com.astrum.pcrd.api.v1.EffectType.CRIT_DAMAGE_UP)
                .findFirst()
                .orElseThrow(() -> new AssertionError("千歌's 6★ UB must declare crit_damage_up; it declares "
                        + chika.ub().enhanced().effects().stream()
                                .map(UbEffect::type).toList()));
        assertEquals(UbEffect.Scope.ALLY, buff.scope(), "「我方全体」 is the ally scope");
        assertEquals(28.0, buff.percent().orElseThrow(), 1e-9, "the wiki's own percentage");
        assertEquals(18.0, buff.params().seconds().orElseThrow(), 1e-9, "and its 18 seconds");
        assertTrue(buff.mismatch().isEmpty(), "the clause must be legal: " + buff.mismatch());
    }
}