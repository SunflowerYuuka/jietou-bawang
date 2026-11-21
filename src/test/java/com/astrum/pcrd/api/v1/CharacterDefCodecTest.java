package com.astrum.pcrd.api.v1;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
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
         "ub":{"base":{"animation":"tempest_blade","hits":1,"damage":20.0,"effects":[]},
               "enhanced":{"animation":"seiga_itten","hits":4,"damage":20.0,"teamChargeBonus":0.2}},
         "growth":{"expBase":100,"expPerLevel":1.15,"maxLevel":30},
         "affection":{"tier2Threshold":3,"tier3Threshold":8,
           "skills":[{"skill":"wide_slash","baseName":"广域斩击",
             "tier2":{"name":"广域斩击+","effects":[
               {"trigger":"melee_hit","effect":"extra_damage","effectParams":{"amount":9.4}}]}}]}}""";

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
        // The tier ladder is DATA now: one SkillLadder, with its tier-2 rung carrying both a name and the
        // hooks that rung runs. It used to be a (hookIndex, mult) pair — see AffectionKernelTest for why
        // that model could not express the real game's 専武 / 6★ versions.
        assertEquals(1, def.affection().skills().size());
        SkillLadder ladder = def.affection().skills().get(0);
        assertEquals("wide_slash", ladder.skill());
        assertEquals("广域斩击+", ladder.nameAt(2));
        assertEquals(1, ladder.tier2().orElseThrow().effects().size());
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

    /**
     * The ultimate tier's optional tail, <b>including the generic effect list</b>, round-trips.
     *
     * <h2>What this replaced, and why</h2>
     * This test used to pin {@code UbTier.AttackBuffDef} / {@code AllyEffectDef} / {@code VictimEffectDef} /
     * {@code HealDef} — four bespoke shapes, one per clause of an ultimate's text. They are now one
     * {@link UbEffect} list: a scope, a generic {@link EffectType} and the same {@link TieredParams} a hook
     * carries. So the assertion is that a tier's effects survive encode-then-decode with their
     * <b>tiers</b> intact, which is the property the old four fields each had separately.
     *
     * <p>It also pins {@code damageType}, because that is what makes ｷｬﾙ's ultimate magical and therefore
     * what the magic defence buff sees — a field whose loss would be invisible until somebody measured
     * damage against a buffed target.
     */
    /**
     * <b>A tier's damage clause list round-trips through the codec, every clause field intact.</b>
     *
     * <p>Three clauses of the three shapes the six recorded gaps needed: a plain damage clause on the nearest body,
     * a blast with its own reach, and an <b>effect clause</b> with no amount at all (紬希's 「将最远的敌人拉近 300」
     * states a distance, so a damage clause would have to invent a number the wiki never gave). The last one is
     * also what pins the optional {@code damage}: a codec that required an amount would refuse the shape the
     * mechanic actually has.
     */
    @Test
    void ubTierRoundTripsItsDamageClauses() {
        var pull = new UbEffect(UbEffect.Scope.VICTIM, EffectType.PULL, TieredParams.ofTier(3));
        var clauses = List.of(
                new UbSegment(10.0, 1, UbTargetRule.NEAREST, OptionalDouble.empty()),
                new UbSegment(OptionalDouble.of(4.5), 2, UbTargetRule.ALL_IN_RADIUS, OptionalDouble.of(4.0),
                        List.of(), Optional.of(ResourceLocation.parse("astrum:magic")),
                        OptionalDouble.of(4.0), 0.5, false, OptionalDouble.empty()),
                new UbSegment(UbTargetRule.FARTHEST, OptionalDouble.empty(), List.of(pull)));
        var tier = new UbTier("ub_cast", clauses, List.of());

        var encoded = UbTier.CODEC.encodeStart(JsonOps.INSTANCE, tier).getOrThrow();
        var decoded = UbTier.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(clauses, decoded.segments(), "clause order, rule, amount, reach, effects and scaling survive");
        assertEquals(UbTargetRule.NEAREST, decoded.segments().get(0).target());
        assertEquals(2, decoded.segments().get(1).hits());
        assertEquals(0.5, decoded.segments().get(1).perExtraTarget(), 1e-9);
        assertEquals(ResourceLocation.parse("astrum:magic"), decoded.segments().get(1).damageType().orElseThrow());
        assertTrue(decoded.segments().get(2).damage().isEmpty(), "an effect clause carries no amount");
        assertEquals(1, decoded.segments().get(2).effects().size());
    }

    /**
     * <b>A tier that lists clauses needs no top-level {@code hits} or {@code damage}.</b>
     *
     * <p>They became optional in the same change, because their numbers live in the clauses now: requiring a
     * top-level copy would make a pack state one of the amounts twice, and a reader guess which one the cast uses
     * (the segments do — {@code UbTier.damageSegments()} never reads the shorthand when a list is present).
     */
    @Test
    void aClauseListedTierDecodesWithoutTopLevelDamage() {
        var json = JsonParser.parseString("""
                {"animation":"ub_cast","segments":[
                  {"target":"nearest","damage":10.0},
                  {"target":"all_in_radius","radius":4.0,"damage":4.5}]}""");
        var tier = UbTier.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertEquals(2, tier.segments().size());
        assertEquals(10.0, tier.segments().get(0).damage().orElseThrow(), 1e-9);
        assertEquals(1, tier.segments().get(0).hits(), "hits defaults to one application");
        assertEquals(4.0, tier.segments().get(1).radius().orElseThrow(), 1e-9);
        // And the shorthand is untouched for a pack that writes no clauses: it still decodes, exactly as before.
        var shorthand = UbTier.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"animation\":\"ub_cast\",\"hits\":4,\"damage\":20.0}"))
                .getOrThrow();
        assertEquals(4, shorthand.hits());
        assertEquals(20.0, shorthand.damage(), 1e-9);
        assertTrue(shorthand.segments().isEmpty());
    }

    /**
     * <b>A clause's rules are its own: an unknown target is a decode error, not a silent default.</b>
     *
     * <p>The enum's names are the wire format, so a pack that writes {@code "secondNearest"} (or a rule this build
     * does not have) must fail loudly. Reading it as {@code NEAREST} would apply a 15.0 clause to the wrong body
     * and look like a balance bug.
     */
    @Test
    void anUnknownTargetRuleIsAnError() {
        var bad = JsonParser.parseString("""
                {"animation":"ub_cast","segments":[{"target":"almost_nearest","damage":1.0}]}""");
        assertTrue(UbTier.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }

    @Test
    void aClauseWithoutATargetIsAnError() {
        var bad = JsonParser.parseString("""
                {"animation":"ub_cast","segments":[{"damage":1.0}]}""");
        assertTrue(UbTier.CODEC.parse(JsonOps.INSTANCE, bad).error().isPresent());
    }

    /**
     * <b>{@code UbTier} grew by one component and is still under the codec's ceiling of sixteen.</b>
     *
     * <p>Same constraint {@code ExPassiveTest} pins for {@code CharacterDef} (which is <b>at</b> sixteen and may not
     * grow), asserted here because this is the record that grew: a seventeenth component would not compile, and the
     * next author deserves to know how much room is left before designing around it.
     */
    @Test
    void ubTierStaysUnderTheCodecComponentCeiling() {
        assertTrue(UbTier.class.getRecordComponents().length <= 16,
                "RecordCodecBuilder.group takes at most sixteen components; UbTier has "
                        + UbTier.class.getRecordComponents().length);
    }

    @Test
    void ubTierRoundTripsItsEffectsAndDamageType() {
        var effects = List.of(
                new UbEffect(UbEffect.Scope.ALLY, EffectType.BUFF_ATTACK_AREA,
                        TieredParams.of(3, 18.0, 8.0)),
                new UbEffect(UbEffect.Scope.VICTIM, EffectType.DEFENCE_DOWN,
                        TieredParams.ofTier(2)));
        var full = new UbTier("grim_burst", 1, 8.4, OptionalDouble.of(6.0),
                Optional.of(ResourceLocation.parse("astrum:magic")), effects, OptionalDouble.of(0.2),
                Optional.of("barrier_break"), Optional.of("深渊炸裂"));
        var bare = new UbTier("seiga_itten", 4, 20.0, OptionalDouble.empty(), List.of());

        var enc = UbDef.CODEC.encodeStart(JsonOps.INSTANCE, new UbDef(full, bare)).getOrThrow();
        var decoded = UbDef.CODEC.parse(JsonOps.INSTANCE, enc).getOrThrow();

        assertEquals(6.0, decoded.base().radius().getAsDouble());
        assertEquals(Optional.of(ResourceLocation.parse("astrum:magic")), decoded.base().damageType(),
                "a magical ultimate must keep its damage type through the codec, or the magic defence "
                        + "buff cannot see it");
        assertEquals(2, decoded.base().effects().size());
        assertEquals(UbEffect.Scope.ALLY, decoded.base().effects().get(0).scope());
        assertEquals(EffectType.BUFF_ATTACK_AREA, decoded.base().effects().get(0).type());
        assertEquals(3, decoded.base().effects().get(0).params().tier(),
                "the tier is the whole strength of the effect; losing it silently means tier 1 forever");
        assertEquals(18.0, decoded.base().effects().get(0).params().seconds().getAsDouble());
        assertEquals(8.0, decoded.base().effects().get(0).params().radius().getAsDouble());
        assertEquals(EffectType.DEFENCE_DOWN, decoded.base().effects().get(1).type());
        assertEquals(2, decoded.base().effects().get(1).params().tier());
        assertEquals(0.2, decoded.base().teamChargeBonus().getAsDouble());
        assertEquals("barrier_break", decoded.base().extra().orElseThrow());
        assertEquals(Optional.of("深渊炸裂"), decoded.base().name(),
                "the ultimate's own name survives the codec: it is what the roster shows at that tier");
        assertEquals("深渊炸裂", decoded.base().nameOr("(unnamed)"));

        assertTrue(decoded.enhanced().radius().isEmpty());
        assertTrue(decoded.enhanced().effects().isEmpty(),
                "a tier that declares no effects decodes to an empty list, not to null");
        assertTrue(decoded.enhanced().damageType().isEmpty(),
                "no damageType means the ultimate's own type; the field never invents one");
        assertTrue(decoded.enhanced().teamChargeBonus().isEmpty());
        assertTrue(decoded.enhanced().extra().isEmpty());
        assertTrue(decoded.enhanced().name().isEmpty(), "a tier with no name stays nameless");
    }

    /**
     * A scope and an effect that contradict each other are a <b>named</b> error, not a wrong-sided buff.
     *
     * <p>The codec cannot catch this — both halves are individually valid — so the rule is
     * {@link UbEffect#mismatch()}, and the two failures worth pinning are the ones a pack author would
     * actually write: a party buff declared on the victims, and a debuff declared on the party. The third
     * case is a type that is neither, which the message answers with the eligible list.
     */
    @Test
    void aScopeThatContradictsItsEffectIsReportedWithTheFix() {
        var partyBuffOnVictims = new UbEffect(UbEffect.Scope.VICTIM, EffectType.BUFF_ATTACK_AREA,
                TieredParams.ofTier(1));
        String first = partyBuffOnVictims.mismatch().orElseThrow();
        assertTrue(first.contains("\"ally\""), "the message must name the scope to write: " + first);
        assertFalse(first.isBlank());

        var debuffOnParty = new UbEffect(UbEffect.Scope.ALLY, EffectType.PANIC,
                TieredParams.ofTier(1));
        String second = debuffOnParty.mismatch().orElseThrow();
        assertTrue(second.contains("\"victim\""), second);

        // Damage is not an effect: the fix is to put it on the tier's hits, so the message has to say so.
        var damageAsEffect = new UbEffect(UbEffect.Scope.VICTIM, EffectType.EXTRA_DAMAGE,
                TieredParams.ofTier(1));
        assertTrue(damageAsEffect.mismatch().orElseThrow().contains("hits"));

        // And the two legal pairs say nothing.
        assertTrue(new UbEffect(UbEffect.Scope.ALLY, EffectType.BUFF_ATTACK_AREA,
                TieredParams.ofTier(1)).mismatch().isEmpty());
        assertTrue(new UbEffect(UbEffect.Scope.VICTIM, EffectType.PANIC,
                TieredParams.ofTier(1)).mismatch().isEmpty());
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

    /**
     * The attack buff's optional {@code radius}, and the fact that <b>0 is a value, not "absent"</b>.
     *
     * <p>Absent means "the documented party default" and {@code 0} means "this player only" — two
     * different behaviours, so they must not collapse into one on the way through the codec. {@code -0.5}
     * and a huge value are also preserved verbatim: refusing nonsense is
     * {@code AttackBuffs.radiusFor}'s job, at the point where it would be used, not the codec's.
     */
    @Test
    void attackBuffRadiusIsOptionalAndZeroIsNotAbsent() {
        var absent = EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"trigger\":\"melee_hit\",\"effect\":\"attack_buff\","
                        + "\"effectParams\":{\"multiplier\":1.1,\"seconds\":10.0}}")).getOrThrow();
        assertTrue(((EffectHook.AttackBuff) absent).radius().isEmpty(),
                "没写 radius = 用默认队伍半径，而不是 0");

        for (double declared : new double[] {0.0, 8.0, -0.5, 1e9}) {
            var hook = (EffectHook.AttackBuff) EffectHook.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseString("{\"trigger\":\"melee_hit\",\"effect\":\"attack_buff\","
                            + "\"effectParams\":{\"multiplier\":1.1,\"seconds\":10.0,\"radius\":"
                            + declared + "}}")).getOrThrow();
            assertEquals(declared, hook.radius().getAsDouble(), 1e-9,
                    "radius " + declared + " 必须原样保留");
            var encoded = EffectHook.CODEC.encodeStart(JsonOps.INSTANCE, hook).getOrThrow();
            assertEquals(Optional.of(hook), EffectHook.CODEC.parse(JsonOps.INSTANCE, encoded).result(),
                    "radius " + declared + " 必须能往返");
        }
    }

    /**
     * {@code triggerParams.skill}: the tag that makes two hooks <b>one skill</b>.
     *
     * <p>Absent and blank both mean "a skill of its own" — {@code skillTag()} is the one place that
     * reading is written — while a real tag survives a round trip, because a pack that is edited by
     * tooling must not lose the rotation's grouping.
     */
    @Test
    void theSkillTagIsOptionalAndBlankMeansAbsent() {
        var untagged = EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"trigger\":\"melee_hit\",\"effect\":\"gain_charge\","
                        + "\"effectParams\":{\"amount\":8.0}}")).getOrThrow();
        assertTrue(untagged.triggerParams().skillTag().isEmpty());

        var blank = EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"trigger\":\"melee_hit\",\"triggerParams\":{\"skill\":\"  \"},"
                        + "\"effect\":\"gain_charge\",\"effectParams\":{\"amount\":8.0}}")).getOrThrow();
        assertTrue(blank.triggerParams().skillTag().isEmpty(),
                "空白标签不是技能名；否则不同技能会被静默合并");

        var tagged = EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"trigger\":\"melee_hit\",\"triggerParams\":{\"skill\":\"weapon_burst\"},"
                        + "\"effect\":\"gain_charge\",\"effectParams\":{\"amount\":8.0}}")).getOrThrow();
        assertEquals(Optional.of("weapon_burst"), tagged.triggerParams().skillTag());
        var encoded = EffectHook.CODEC.encodeStart(JsonOps.INSTANCE, tagged).getOrThrow();
        assertEquals(Optional.of(tagged), EffectHook.CODEC.parse(JsonOps.INSTANCE, encoded).result());
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
     * The three face fields, and the two roles they express.
     *
     * <p>{@code avatar} is her one official picture; {@code portrait} is the book's own override. Both
     * are optional (an older pack keeps loading, and its buff is named after the character instead of
     * failing to render), and {@link CharacterDef#face()} is the one place the order is written.
     */
    @Test
    void skillNameAndAvatarAreOptionalAndDefaultToEmpty() {
        var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(DJEETA)).getOrThrow();
        assertTrue(def.skillName().isEmpty());
        assertTrue(def.avatar().isEmpty());
        assertTrue(def.face().isEmpty(), "no avatar and no portrait means no face at all");
        assertEquals("姬塔", def.effectiveSkillName(), "no skill name means the display name");

        String withBoth = DJEETA.replace("\"dimension\":\"charge\",",
                "\"dimension\":\"charge\",\"skillName\":\"广域斩击\","
                        + "\"avatar\":\"astrum:textures/gui/portrait/djeeta.png\",");
        var named = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(withBoth)).getOrThrow();
        assertEquals("广域斩击", named.skillName().orElseThrow());
        assertEquals("astrum:textures/gui/portrait/djeeta.png", named.avatar().orElseThrow().toString());
        assertEquals("广域斩击", named.effectiveSkillName());

        // A blank skill name is not a name: the fallback rule (effectiveSkillName) is the single copy.
        var blank = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                withBoth.replace("\"skillName\":\"广域斩击\"", "\"skillName\":\"  \""))).getOrThrow();
        assertEquals("姬塔", blank.effectiveSkillName());
    }

    /**
     * {@code portrait} is the book's override: it wins for the book and leaves the avatar alone.
     *
     * <p>This is the arrangement the 2026-11-14 defect needed written down as code. A pack may ship one
     * picture (fill {@code avatar}) or two (add {@code portrait} for the 角色册's crop); {@code face()}
     * answers "what the book draws" and never reaches for the item texture, because the item texture is
     * a generic ticket and not a face.
     */
    @Test
    void thePortraitOverridesTheAvatarForTheBookOnly() {
        String avatar = "\"avatar\":\"astrum:textures/gui/portrait/djeeta.png\",";
        String both = DJEETA.replace("\"dimension\":\"charge\",",
                "\"dimension\":\"charge\"," + avatar
                        + "\"portrait\":\"astrum:textures/gui/portrait/djeeta_alt.png\",");

        var withOverride = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(both))
                .getOrThrow();
        assertEquals("astrum:textures/gui/portrait/djeeta_alt.png",
                withOverride.face().orElseThrow().toString(), "declared portrait wins for the book");
        assertEquals("astrum:textures/gui/portrait/djeeta.png",
                withOverride.avatar().orElseThrow().toString(),
                "the override must not rewrite the field it overrides");

        var avatarOnly = CharacterDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                DJEETA.replace("\"dimension\":\"charge\",", "\"dimension\":\"charge\"," + avatar)))
                .getOrThrow();
        assertEquals(avatarOnly.avatar(), avatarOnly.face(),
                "with no portrait the book draws the avatar — the normal case");
    }
}
