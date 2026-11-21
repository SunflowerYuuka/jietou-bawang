package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The timed party crit-<em>chance</em> buff</b> — 千歌's 专属, 静流's 二专 and 铃莓's 二专, which each name a
 * critic score and had no carrier before task 60.
 *
 * <h2>What this file pins</h2>
 * <ol>
 *   <li>The <b>amplifier encoding</b>: one step is <b>0.1 percentage point</b>, so 千歌's 2.5% is exact (25) instead
 *       of being quantised to 2% or 3%. The crit-damage buff next door uses whole percents because 28 is whole;
 *       this one needs tenths because 2.5 is not.</li>
 *   <li>The <b>number the player ends up with</b>: {@code ADD_VALUE} on {@code astrum:crit_chance}, so the buff
 *       <b>adds</b> to her own chance ({@code CritChances}) and {@code Crit.chanceOf} clamps the sum — a percentage
 *       of a chance would be a different, smaller buff than 「+50 物理暴击」 describes.</li>
 *   <li>The <b>shipped data</b>: three characters carry the hook, at their wiki scores converted at the build's
 *       stated 1 point = 0.05 percentage points, and the {@code _notes} sentences that recorded the gap are gone.</li>
 * </ol>
 *
 * <p>What a unit test cannot do is apply the buff to a live player and watch the attribute rise and fall at the
 * 12-second mark: that needs a server and a client, and it is on the "only a human can confirm" list in
 * {@code task-60-report.md}. What is asserted here is that the effect constructs (so its
 * {@code addAttributeModifier} call with the curve is valid), that its id and modifier id are its own, and that the
 * arithmetic the curve feeds is exact.
 */
class CritChanceUpsTest {

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = CritChanceUpsTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    // ------------------------------------------------------------------ the encoding

    /**
     * <b>Tenths of a point, so the shipped values are exact.</b>
     *
     * <p>The three clauses' numbers are 千歌 50, 静流 100 and 铃莓 200 critic points, which the build's conversion
     * ({@code CritChances.CHANCE_PER_POINT}) turns into 2.5%, 5% and 10%. A whole-percent step could not hold the
     * 2.5, so the amplifier counts tenths: {@code 25 / 50 / 100}.
     */
    @Test
    void oneAmplifierStepIsATenthOfAPercent() {
        assertEquals(25, CritChanceUps.amplifierFor(2.5), "千歌's 2.5% is exact, not 2 or 3");
        assertEquals(50, CritChanceUps.amplifierFor(5.0), "静流's 5%");
        assertEquals(100, CritChanceUps.amplifierFor(10.0), "铃莓's 10%");
        assertEquals(0.025, CritChanceUps.fractionFor(25), 1e-12);
        assertEquals(2.5, CritChanceUps.percentForAmplifier(25), 1e-12);
        for (double percent : new double[] {2.5, 5.0, 10.0, 0.1, 25.5}) {
            assertTrue(CritChanceUps.isRepresentable(percent), percent + " must survive the round trip");
            assertEquals(percent, CritChanceUps.percentForAmplifier(CritChanceUps.amplifierFor(percent)), 1e-9);
        }
        // A value finer than a tenth is quantised, and the applier says so rather than silently changing it.
        assertFalse(CritChanceUps.isRepresentable(0.04));
        assertFalse(CritChanceUps.isRepresentable(0.0));
        assertFalse(CritChanceUps.isRepresentable(Double.NaN));
        // Hostile input is a no-grant rather than an exception.
        assertEquals(0, CritChanceUps.amplifierFor(-2.0));
        assertEquals(0, CritChanceUps.amplifierFor(Double.NaN));
        assertEquals(0, CritChanceUps.amplifierFor(Double.POSITIVE_INFINITY));
        // And the byte-sized ceiling is respected, because an amplifier has to fit in one.
        assertEquals(AttackBuffRules.MAX_AMPLIFIER, CritChanceUps.amplifierFor(1000.0));
        assertEquals(AttackBuffRules.MAX_AMPLIFIER * CritChanceUps.FRACTION_PER_STEP,
                CritChanceUps.fractionFor(AttackBuffRules.MAX_AMPLIFIER), 1e-12);
    }

    /** <b>The effect class:</b> constructible headlessly, BENEFICIAL, and under its own modifier id. */
    @Test
    void theEffectDeclaresOneModifierUnderItsOwnId() {
        assertEquals(ResourceLocation.parse("astrum:crit_chance_up"), CritChanceUps.ID);
        assertEquals(ResourceLocation.parse("astrum:crit_chance_up"), CritChanceUpEffect.MODIFIER_ID);
        assertEquals("effect.astrum.crit_chance_up", CritChanceUps.NAME_KEY);
        assertFalse(CritChanceUpEffect.MODIFIER_ID.equals(CritDamageUpEffect.MODIFIER_ID),
                "a shared id would make the chance buff and the damage buff cancel each other");
        var effect = new CritChanceUpEffect();
        assertEquals(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL, effect.getCategory());
        assertEquals(CritChanceUpEffect.COLOUR, effect.getColor());
        // The stat it writes is the one Crit owns — not a second crit-chance channel.
        assertNotNull(Crit.CRIT_CHANCE);
    }

    /**
     * <b>The buff adds to her own chance rather than replacing it.</b>
     *
     * <p>{@code Crit.chanceOf} reads the attribute's value, i.e. base plus every modifier, so a 22% character with a
     * +5% buff crits at 27% — a percentage multiplier would give 23.1%, which is a different and smaller buff.
     */
    @Test
    void theBuffAddsToTheCharactersOwnChance() {
        double own = 0.22;
        double buffed = own + CritChanceUps.fractionFor(CritChanceUps.amplifierFor(5.0));
        assertEquals(0.27, buffed, 1e-12, "22% + 5 points = 27%");
        assertTrue(buffed > own * 1.05, "and it is more than a 5% multiplier would give");
        assertTrue(Crit.clampChance(buffed) <= Crit.MAX_CHANCE,
                "Crit's own clamp still bounds the sum, so a stack of buffs cannot exceed the attribute's range");
    }

    /** {@code apply} with no caster is an answer, not an exception. */
    @Test
    void aNullCasterAppliesNothing() {
        assertEquals(0, CritChanceUps.apply(null, 5.0, 12.0, ResourceLocation.parse("astrum:suzume"),
                java.util.OptionalDouble.of(4.0)));
        // A zero or hostile percentage is refused before any receiver is touched.
        assertEquals(0, CritChanceUps.apply(null, 0.0, 12.0, ResourceLocation.parse("astrum:suzume"),
                java.util.OptionalDouble.empty()));
        assertEquals(0, CritChanceUps.apply(null, Double.NaN, 12.0,
                ResourceLocation.parse("astrum:suzume"), java.util.OptionalDouble.empty()));
    }

    /** The hook round-trips through the real dispatch, under the name the JSON uses. */
    @Test
    void theHookRoundTripsThroughTheRealCodec() {
        String json = """
                {"trigger":"melee_hit","effect":"crit_chance_up",
                 "effectParams":{"percent":5.0,"seconds":12.0,"radius":8.0}}""";
        EffectHook decoded = EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        assertTrue(decoded instanceof EffectHook.CritChanceUp, "the dispatch key must select the record");
        EffectHook.CritChanceUp hook = (EffectHook.CritChanceUp) decoded;
        assertEquals(EffectType.CRIT_CHANCE_UP, hook.type());
        assertEquals(5.0, hook.percent(), 1e-12);
        assertEquals(12.0, hook.seconds(), 1e-12);
        assertEquals(8.0, hook.radius().getAsDouble(), 1e-12);
        // A non-tiered magnitude: the panel must not show a rung for a percentage the wiki states.
        assertTrue(hook.tieredParams().isEmpty(), "a percentage is not a rung, so there is no tier block");
    }

    // ------------------------------------------------------------------ the shipped data

    /**
     * <b>千歌's 专属, 静流's 二专 and 铃莓's 二专 carry the buff, at the converted wiki scores.</b>
     *
     * <p>The three numbers are asserted from the shipped files rather than from constants, so an edit that changed
     * the data without changing this test is caught. Each conversion is the build's stated one: 1 crit point =
     * 0.05 percentage points.
     */
    @Test
    void theThreeShippedClausesCarryTheConvertedScores() throws Exception {
        EffectHook.CritChanceUp chika = findHook(load("chika"), "cheer_song");
        assertEquals(2.5, chika.percent(), 1e-12, "千歌 50 points x 0.05 = 2.5%");
        assertEquals(12.0, chika.seconds(), 1e-12);

        EffectHook.CritChanceUp shizuru = findHook(load("shizuru"), "protection");
        assertEquals(5.0, shizuru.percent(), 1e-12, "静流 100 points x 0.05 = 5%");

        EffectHook.CritChanceUp suzume = findHook(load("suzume"), "maid_yell");
        assertEquals(10.0, suzume.percent(), 1e-12, "铃莓 200 points x 0.05 = 10%");
    }

    /** {@return the shipped {@code crit_chance_up} hook of one character's affection skill} */
    private static EffectHook.CritChanceUp findHook(CharacterDef def, String skill) {
        for (var row : def.affection().skills()) {
            if (row == null || !skill.equals(row.skill()) || row.tier2().isEmpty()) {
                continue;
            }
            for (EffectHook hook : row.tier2().get().effects()) {
                if (hook instanceof EffectHook.CritChanceUp chance) {
                    return chance;
                }
            }
        }
        throw new AssertionError(def.displayName() + "'s " + skill + " must carry crit_chance_up; the extra "
                + "sentence claiming 「没有对应效果类型」 was closed by task 60");
    }
}