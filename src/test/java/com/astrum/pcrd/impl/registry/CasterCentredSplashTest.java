package com.astrum.pcrd.impl.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.SecondRotation;
import com.astrum.pcrd.impl.client.SkillDetailModel;
import com.astrum.pcrd.impl.party.BookText;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>「以自身为中心」 — a splash measured from the caster rather than from the body it struck.</b>
 *
 * <h2>The gap this closes</h2>
 * 矛依未's SP2 clause was recorded as unimplementable: 「本 mod 唯一的范围伤害是 splash，它以<b>被击中的目标</b>为圆心，
 * 所以这里取半径 6 表达『同一半径内的其他敌人也吃这一发』；圆心与被击中的敌人重合而非与施法者重合」.
 *
 * <p>That was true: {@code EffectExecutors.around} hard-coded {@code ctx.target()} as the centre, so the two
 * shapes were not distinguishable in data at all. {@code EffectHook.Splash#casterCentred} is the distinguisher,
 * and it defaults to {@code false} — every splash that shipped before the field existed still centres on the
 * body it hit, which is asserted here so the default cannot drift.
 *
 * <h2>What is asserted, and what cannot be</h2>
 * The <b>data</b> half is assertable: her two hooks declare the flag, the {@code extra} that called the clause
 * unimplemented is gone, and the page draws the centre. The <b>geometry</b> half — that a body standing behind
 * her gets hit while a body standing behind her victim does not — needs a live level with real positions, so it
 * is a human check; the implementation routes through one {@code around(ctx, radius, centre)} overload whose
 * centre is the only difference.
 */
class CasterCentredSplashTest {

    private static CharacterDef load(String id) {
        String path = "/data/astrum/astrum/character/" + id + ".json";
        try (InputStream in = CasterCentredSplashTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "the character data must be on the test classpath: " + path);
            JsonElement json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }

    /** {@return the hook a JSON snippet decodes to, through the real dispatch codec} */
    private static EffectHook hook(String json) {
        return EffectHook.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString(json)).getOrThrow();
    }

    /** Her SP2 splash names the caster as its centre, in both the base and the upgraded hook list. */
    @Test
    void muimisSp2SplashesAroundHerself() throws Exception {
        CharacterDef def = load("muimi");
        SecondRotation rotation = def.ub().secondRotation().orElseThrow(
                () -> new AssertionError("矛依未 is the second-rotation character; the field must be present"));

        List<List<EffectHook>> lists = List.of(rotation.hooks(), rotation.upgradedHooks());
        int seen = 0;
        for (List<EffectHook> hooks : lists) {
            for (EffectHook hook : hooks) {
                if (hook instanceof EffectHook.Splash splash) {
                    assertTrue(splash.casterCentred(),
                            "「以自身为中心」 is why 矛依未's SP2 declares casterCentred — without it the blast "
                                    + "is measured from the body she struck");
                    assertEquals(6.0, splash.radius(), 1e-9,
                            "半径575 → clamp(round(575/100),4,10) = 6, unchanged by the centre");
                    assertEquals(14.1, splash.damage(), 1e-9, "SP2's appended segment, unchanged");
                    seen++;
                }
            }
        }
        assertEquals(2, seen, "one splash per hook list (base and upgraded)");

        assertFalse(rotation.extra().isPresent(),
                "the clause is implemented, so the note that called it unimplemented must be gone: "
                        + rotation.extra().orElse(""));
    }

    /**
     * <b>The default is the body it struck.</b>
     *
     * <p>Every splash that shipped before this field existed relies on this, and a default of {@code true} would
     * silently re-centre 日和莉's and every other area clause on the caster.
     */
    @Test
    void aSplashWithoutTheFlagStillCentresOnTheVictim() throws Exception {
        EffectHook.Splash plain = new EffectHook.Splash(
                com.astrum.pcrd.api.v1.Trigger.MELEE_HIT, com.astrum.pcrd.api.v1.TriggerParams.NONE,
                1.0, 5.0, 3.0);
        assertFalse(plain.casterCentred(), "the 5-argument shape is the pre-flag one and must not recentre");

        // And through the real codec, with the field absent from the JSON: absence means false.
        EffectHook decoded = hook("""
                {"trigger":"melee_hit","effect":"splash","effectParams":{"radius":5.0,"damage":3.0}}
                """);
        assertTrue(decoded instanceof EffectHook.Splash, "the codec must route splash: " + decoded);
        assertFalse(((EffectHook.Splash) decoded).casterCentred(),
                "a pack written before the field existed keeps its old centre");

        // ... and when it IS named, it is honoured — the flag is not silently dropped either.
        EffectHook named = hook("""
                {"trigger":"melee_hit","effect":"splash",
                 "effectParams":{"radius":5.0,"damage":3.0,"casterCentred":true}}
                """);
        assertTrue(((EffectHook.Splash) named).casterCentred(), "an explicit true must survive the codec");
    }

    /** Her page says where the blast is measured from — a flag a player cannot read is not implemented. */
    @Test
    void herPageDrawsTheCentre() throws Exception {
        CharacterDef def = load("muimi");
        boolean sawCentre = false;
        // Every entry of every growth tier: her SP2 lives in the second rotation, and the panel is the only
        // place a player learns the blast is measured from her rather than from what she hit.
        for (int tier = 1; tier <= 5; tier++) {
            for (int level = 1; level <= 30; level++) {
                var page = SkillDetailModel.of(def, tier, level);
                for (var entry : page.entries()) {
                    for (var row : entry.rows()) {
                        for (var number : row.numbers()) {
                            sawCentre |= number.labelKey().equals(BookText.LABEL_CENTRE);
                        }
                    }
                }
            }
        }
        assertTrue(sawCentre,
                "her SP2 splash is caster-centred, so its panel must state the centre — otherwise the flag is "
                        + "invisible to the player whose blast shape it changes");
    }
}
