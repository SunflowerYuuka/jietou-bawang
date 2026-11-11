package com.astrum.pcrd.api.v1;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CharacterDefCodecTest {
    static final String DJEETA = """
        {"schemaVersion":1,"displayName":"姬塔","dimension":"charge",
         "effects":[
           {"trigger":"melee_hit","effect":"gain_charge","effectParams":{"amount":2.0}},
           {"trigger":"combo_n","triggerParams":{"hits":3},"effect":"splash","effectParams":{"radius":3.0,"damage":4.0}},
           {"trigger":"combo_n","triggerParams":{"hits":3},"effect":"area_charge","effectParams":{"radius":8.0,"amount":3.0,"includeSelf":true}}],
         "ub":{"base":{"animation":"tempest_blade","hits":1,"damage":20.0,"selfAtkBuff":1.3,"buffTicks":360},
               "enhanced":{"animation":"seiga_itten","hits":4,"damage":20.0,"teamChargeBonus":0.2}},
         "growth":{"expBase":100,"expPerLevel":1.15,"maxLevel":30},
         "affection":{"tier2Threshold":3,"tier3Threshold":8,"tier2HookModifiers":[{"hookIndex":1,"mult":1.5}]}}""";

    @Test
    void decodesDjeetaFromSpec() {
        var json = JsonParser.parseString(DJEETA);
        var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(1, def.schemaVersion());
        assertEquals(Dimension.CHARGE, def.dimension());
        assertEquals(3, def.effects().size());
        assertInstanceOf(EffectHook.GainCharge.class, def.effects().get(0));
        assertEquals(2.0, ((EffectHook.GainCharge) def.effects().get(0)).amount());
        assertEquals(3, def.effects().get(1).triggerParams().hits());
        assertEquals("tempest_blade", def.ub().base().animation());
        assertEquals(4, def.ub().enhanced().hits());
        assertEquals(1, def.affection().tier2HookModifiers().size());
    }

    @Test
    void roundTripsAllEightEffectTypes() {
        List<String> hooks = List.of(
            "{\"trigger\":\"melee_hit\",\"effect\":\"apply_effect\",\"effectParams\":{\"effect\":\"minecraft:poison\",\"duration\":100,\"amplifier\":0}}",
            "{\"trigger\":\"crit\",\"effect\":\"extra_damage\",\"effectParams\":{\"amount\":3.0}}",
            "{\"trigger\":\"kill\",\"effect\":\"lifesteal\",\"effectParams\":{\"fraction\":0.2}}",
            "{\"trigger\":\"melee_hit\",\"effect\":\"gain_charge\",\"effectParams\":{\"amount\":2.0}}",
            "{\"trigger\":\"combo_n\",\"triggerParams\":{\"hits\":3},\"effect\":\"area_charge\",\"effectParams\":{\"radius\":8.0,\"amount\":3.0,\"includeSelf\":true}}",
            "{\"trigger\":\"combo_n\",\"triggerParams\":{\"hits\":3},\"effect\":\"splash\",\"effectParams\":{\"radius\":3.0,\"damage\":4.0}}",
            "{\"trigger\":\"melee_hit\",\"effect\":\"stack\",\"effectParams\":{\"stackId\":\"astrum:poison\",\"max\":5}}",
            "{\"trigger\":\"stack_full\",\"triggerParams\":{\"stackId\":\"astrum:poison\"},\"effect\":\"detonate\",\"effectParams\":{\"damagePerStack\":2.0,\"radius\":3.0}}");
        for (String h : hooks) {
            var json = JsonParser.parseString(h);
            var hook = EffectHook.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            var reEncoded = EffectHook.CODEC.encodeStart(JsonOps.INSTANCE, hook).getOrThrow();
            assertEquals(Optional.of(hook), EffectHook.CODEC.parse(JsonOps.INSTANCE, reEncoded).result(), h);
        }
    }

    @Test
    void unknownEnumValueIsAnErrorNotACrash() {
        var bad = JsonParser.parseString(DJEETA.replace("\"charge\"", "\"nonexistent\""));
        assertTrue(CharacterDef.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }

    @Test
    void missingRequiredFieldIsAnError() {
        var bad = JsonParser.parseString(DJEETA.replace("\"expBase\":100,", ""));
        assertTrue(CharacterDef.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }

    @Test
    void characterProgressRoundTrips() {
        var p = new CharacterProgress(5, 42, 2);
        var enc = CharacterProgress.CODEC.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        assertEquals(Optional.of(p), CharacterProgress.CODEC.parse(JsonOps.INSTANCE, enc).result());
    }
}
