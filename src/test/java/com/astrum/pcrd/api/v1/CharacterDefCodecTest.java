package com.astrum.pcrd.api.v1;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
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
    void roundTripsAllNineEffectTypes() {
        List<String> hooks = List.of(
            "{\"trigger\":\"melee_hit\",\"effect\":\"apply_effect\",\"effectParams\":{\"effect\":\"minecraft:poison\",\"duration\":100,\"amplifier\":0}}",
            "{\"trigger\":\"crit\",\"effect\":\"extra_damage\",\"effectParams\":{\"amount\":3.0}}",
            "{\"trigger\":\"kill\",\"effect\":\"lifesteal\",\"effectParams\":{\"fraction\":0.2}}",
            "{\"trigger\":\"melee_hit\",\"effect\":\"gain_charge\",\"effectParams\":{\"amount\":2.0}}",
            "{\"trigger\":\"combo_n\",\"triggerParams\":{\"hits\":3},\"effect\":\"area_charge\",\"effectParams\":{\"radius\":8.0,\"amount\":3.0,\"includeSelf\":true}}",
            "{\"trigger\":\"combo_n\",\"triggerParams\":{\"hits\":3},\"effect\":\"splash\",\"chance\":0.5,\"effectParams\":{\"radius\":3.0,\"damage\":4.0}}",
            "{\"trigger\":\"melee_hit\",\"effect\":\"stack\",\"effectParams\":{\"stackId\":\"astrum:poison\",\"max\":5}}",
            "{\"trigger\":\"stack_full\",\"triggerParams\":{\"stackId\":\"astrum:poison\"},\"effect\":\"detonate\",\"effectParams\":{\"damagePerStack\":2.0,\"radius\":3.0}}",
            "{\"trigger\":\"melee_hit\",\"effect\":\"attack_buff\",\"effectParams\":{\"multiplier\":1.5,\"seconds\":18.0}}");
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

    @Test
    void nonDefaultChanceSurvivesRoundTrip() {
        var json = JsonParser.parseString(
                "{\"trigger\":\"combo_n\",\"triggerParams\":{\"hits\":3},\"effect\":\"splash\",\"chance\":0.5,\"effectParams\":{\"radius\":3.0,\"damage\":4.0}}");
        var hook = EffectHook.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(0.5, hook.chance());
        var reEncoded = EffectHook.CODEC.encodeStart(JsonOps.INSTANCE, hook).getOrThrow();
        assertEquals(0.5, EffectHook.CODEC.parse(JsonOps.INSTANCE, reEncoded).getOrThrow().chance());
    }

    @Test
    void ubDefRoundTripsOptionalBridges() {
        var full = new UbTier("tempest_blade", 4, 20.0, OptionalDouble.of(4.0),
                Optional.of(new UbTier.AttackBuffDef(1.5, 18.0)), OptionalDouble.of(0.2),
                Optional.of("armor_break"));
        var bare = new UbTier("seiga_itten", 4, 20.0, OptionalDouble.empty(), Optional.empty(),
                OptionalDouble.empty(), Optional.empty());
        var def = new UbDef(full, bare);

        var enc = UbDef.CODEC.encodeStart(JsonOps.INSTANCE, def).getOrThrow();
        assertEquals(Optional.of(def), UbDef.CODEC.parse(JsonOps.INSTANCE, enc).result(), enc.toString());

        var decoded = UbDef.CODEC.parse(JsonOps.INSTANCE, enc).getOrThrow();
        assertEquals(4.0, decoded.base().radius().getAsDouble());
        assertEquals(1.5, decoded.base().attackBuff().orElseThrow().multiplier());
        assertEquals(18.0, decoded.base().attackBuff().orElseThrow().seconds());
        assertEquals(0.2, decoded.base().teamChargeBonus().getAsDouble());
        assertEquals("armor_break", decoded.base().extra().orElseThrow());
        assertTrue(decoded.enhanced().radius().isEmpty());
        assertTrue(decoded.enhanced().attackBuff().isEmpty());
        assertTrue(decoded.enhanced().teamChargeBonus().isEmpty());
        assertTrue(decoded.enhanced().extra().isEmpty());
    }

    /**
     * The attack buff is a real hook shape, and it round-trips.
     *
     * <p>Added with {@link EffectType#ATTACK_BUFF}: a value that decodes but cannot be re-encoded would
     * break a pack round-trip silently, so the pair is asserted rather than only the decode.
     */
    @Test
    void attackBuffHookRoundTrips() {
        var json = JsonParser.parseString("{\"trigger\":\"melee_hit\",\"effect\":\"attack_buff\","
                + "\"effectParams\":{\"multiplier\":1.5,\"seconds\":18.0}}");
        var hook = EffectHook.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertInstanceOf(EffectHook.AttackBuff.class, hook);
        assertEquals(EffectType.ATTACK_BUFF, hook.type());
        assertEquals(1.5, ((EffectHook.AttackBuff) hook).multiplier());
        assertEquals(18.0, ((EffectHook.AttackBuff) hook).seconds());
        var reEncoded = EffectHook.CODEC.encodeStart(JsonOps.INSTANCE, hook).getOrThrow();
        assertEquals(Optional.of(hook), EffectHook.CODEC.parse(JsonOps.INSTANCE, reEncoded).result());
    }

    @Test
    void unknownEffectTypeIsAnErrorNotACrash() {
        var bad = JsonParser.parseString(DJEETA.replace("\"effect\":\"gain_charge\"", "\"effect\":\"nonexistent\""));
        var result = CharacterDef.CODEC.parse(JsonOps.INSTANCE, bad);
        assertTrue(result.error().isPresent());
        assertTrue(result.error().orElseThrow().message().contains("nonexistent"));
    }

    @Test
    void unknownTriggerValueIsAnErrorNotACrash() {
        var bad = JsonParser.parseString(DJEETA.replace("\"trigger\":\"melee_hit\"", "\"trigger\":\"nonexistent\""));
        assertTrue(CharacterDef.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }

    /**
     * The two fields the party effect reads are optional: an older pack keeps loading, and its buff is
     * named after the character instead of failing to render.
     */
    @Test
    void skillNameAndAvatarAreOptionalAndDefaultToEmpty() {
        var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(DJEETA)).getOrThrow();
        assertTrue(def.skillName().isEmpty());
        assertTrue(def.avatar().isEmpty());
        assertEquals("姬塔", def.effectiveSkillName(), "no skill name means the display name");

        String withBoth = DJEETA.replace("\"dimension\":\"charge\",",
                "\"dimension\":\"charge\",\"skillName\":\"广域斩击\","
                        + "\"avatar\":\"astrum:textures/item/character_card.png\",");
        var named = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(withBoth)).getOrThrow();
        assertEquals("广域斩击", named.skillName().orElseThrow());
        assertEquals("astrum:textures/item/character_card.png", named.avatar().orElseThrow().toString());
        assertEquals("广域斩击", named.effectiveSkillName());

        // A blank skill name is not a name: the fallback rule (effectiveSkillName) is the single copy.
        var blank = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                withBoth.replace("\"skillName\":\"广域斩击\"", "\"skillName\":\"  \""))).getOrThrow();
        assertEquals("姬塔", blank.effectiveSkillName());
    }
}
