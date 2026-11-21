package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.UbEffect;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「每秒恢复自身【10+「冰龙之印」】技能值」 — charge over time whose instalment follows a live tally</b> (雪菲's
 * 6★ UB), the one effect whose magnitude a <em>rung</em> could not carry.
 *
 * <h2>What this file pins</h2>
 * <ol>
 *   <li>The <b>two-nibble encoding</b>. A {@code MobEffectInstance} has one byte-sized amplifier and this effect
 *       has two data numbers, so {@code base} and {@code perMark} are carried in <b>tenths of a charge point a
 *       second</b>: {@code 1.0} and {@code 0.1} become {@code 10 | (1 << 4) = 26}.</li>
 *   <li>The <b>formula</b>: {@code (base + perMark × marks) / 20} a tick, read live, so spending a mark during the
 *       regen really slows it — which is what 「+「冰龙之印」」 means and why a fixed rung would be wrong.</li>
 *   <li>The <b>shipped clause, at the source's own arithmetic</b>: the wiki's 「最大总计315」 is what
 *       {@code (1.0 + 0.1 × 20) × 10.5 s} comes to at the 20-mark cap, i.e. 31.5 charge points = 315 TP at the
 *       project's 1 point = 10 TP. The data's numbers therefore reproduce the number the source states.</li>
 * </ol>
 *
 * <p>What a unit test cannot do is watch the gauge fill on a live player for ten and a half seconds, or spend a
 * mark mid-regen and see the rate drop: that needs a server and is on the "only a human can confirm" list in
 * {@code task-60-report.md}.
 */
class CounterChargeRegenTest {

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = CounterChargeRegenTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    // ------------------------------------------------------------------ the encoding

    /** <b>Tenths of a point, two nibbles, one amplifier.</b> */
    @Test
    void theTwoRatesTravelInTheAmplifiersTwoNibbles() {
        int shipped = OverTimeEffects.counterAmplifierFor(1.0, 0.1);
        assertEquals(26, shipped, "1.0 point/s and 0.1 a mark: 10 | (1 << 4)");
        assertEquals(1.0, OverTimeEffects.counterBaseOf(shipped), 1e-12);
        assertEquals(0.1, OverTimeEffects.counterPerMarkOf(shipped), 1e-9);
        // The formula, at the two ends of 雪菲's ladder.
        assertEquals(1.0, OverTimeEffects.counterChargePointsPerSecond(shipped, 0), 1e-12,
                "no marks: 10 TP a second");
        assertEquals(1.5, OverTimeEffects.counterChargePointsPerSecond(shipped, 5), 1e-12,
                "five marks: 15 TP a second");
        assertEquals(3.0, OverTimeEffects.counterChargePointsPerSecond(shipped, 20), 1e-12,
                "the 20-mark cap: 30 TP a second");
        // Per tick, divided once and nowhere else.
        assertEquals(0.05, OverTimeEffects.counterChargePointsPerTick(shipped, 0), 1e-12);
        assertEquals(0.15, OverTimeEffects.counterChargePointsPerTick(shipped, 20), 1e-12);
        // Negative marks cannot invent a rate (the count comes from Counters, and is clamped here anyway).
        assertEquals(1.0, OverTimeEffects.counterChargePointsPerSecond(shipped, -4), 1e-12);
    }

    /**
     * <b>A clause outside the nibbles' range is refused, not quietly clamped.</b>
     *
     * <p>Each half holds 0..1.5 points a second (15 steps). A rate that would not fit is a WARN and no instance,
     * because an effect that pays a different number than the data says is the failure this project treats as the
     * worst of the three.
     */
    @Test
    void aRateOutsideTheNibblesIsRefused() {
        assertTrue(OverTimeEffects.counterAmplifierFor(2.0, 0.1) < 0, "2.0 points/s is 20 steps, more than 15");
        assertTrue(OverTimeEffects.counterAmplifierFor(1.0, 1.6) < 0, "and so is 1.6 a mark");
        assertTrue(OverTimeEffects.counterAmplifierFor(-1.0, 0.1) < 0);
        assertTrue(OverTimeEffects.counterAmplifierFor(Double.NaN, 0.1) < 0);
        assertEquals(0, OverTimeEffects.counterAmplifierFor(0.0, 0.0), "zero is a legal (silent) rate");
        // The boundary itself fits: 1.5 points a second is 15 steps.
        assertEquals(1.5, OverTimeEffects.counterBaseOf(OverTimeEffects.counterAmplifierFor(1.5, 0.0)),
                1e-12);
    }

    /** <b>The effect class:</b> constructible headlessly, BENEFICIAL, under its own registry path. */
    @Test
    void theEffectIsItsOwnRegistryType() {
        assertEquals("charge_counter", OverTimeEffects.COUNTER_CHARGE_PATH);
        assertEquals(ResourceLocation.parse("astrum:charge_counter"), OverTimeEffects.COUNTER_CHARGE_ID);
        var effect = new CounterChargeRegenEffect();
        assertEquals(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL, effect.getCategory());
        assertEquals(OverTimeEffects.CHARGE_REGEN_COLOUR, effect.getColor(),
                "the same gauge gold as the rung-based regen: both mean 「the bar is filling」");
        // It is a DIFFERENT type from the rung-based regen, deliberately: vanilla merges two instances of one
        // type by keeping the stronger, which is right for a rung and wrong for a formula.
        assertTrue(!OverTimeEffects.COUNTER_CHARGE_ID.equals(OverTimeEffects.ID));
    }

    /** {@code apply} refuses a clause it cannot express instead of applying a zero-rate state. */
    @Test
    void anInexpressibleOrTimelessClauseAppliesNothing() {
        assertEquals(false, OverTimeEffects.applyCounterChargeRegen(null, 1.0, 0.1, 10.5,
                ResourceLocation.parse("astrum:sheffy")));
    }

    // ------------------------------------------------------------------ the shipped data

    /**
     * <b>雪菲's 6★ ultimate carries the clause at the wiki's numbers, and the gap sentence is gone.</b>
     *
     * <p>「每秒恢复自身【10+「冰龙之印」】技能值，持续10.5秒（最大总计315）」 at 1 charge point = 10 TP is
     * {@code base 1.0}, {@code perMark 0.1}, {@code seconds 10.5}. The 315 is reproduced by the formula at the
     * 20-mark cap, which is why the number is asserted here rather than trusted.
     */
    @Test
    void sheffysSixStarClauseCarriesTheChargeRateAndTheGapIsDeleted() throws Exception {
        CharacterDef sheffy = load("sheffy");
        UbEffect found = null;
        for (UbEffect effect : sheffy.ub().enhanced().effects()) {
            if (effect.type() == EffectType.COUNTER_CHARGE_REGEN) {
                found = effect;
            }
        }
        assertNotNull(found, "her 6★ ultimate must carry counter_charge_regen; effects were "
                + sheffy.ub().enhanced().effects().stream().map(UbEffect::type).toList());
        assertEquals(UbEffect.Scope.ALLY, found.scope(), "the regen is on her own side");
        UbEffect.ChargeRate rate = found.chargeRate().orElseThrow(
                () -> new AssertionError("counter_charge_regen needs its chargeRate block"));
        assertEquals(1.0, rate.base(), 1e-12, "10 TP a second = 1 charge point");
        assertEquals(0.1, rate.perMark(), 1e-12, "one 冰龙之印 = 1 TP a second");
        assertEquals(10.5, found.params().seconds().orElseThrow(), 1e-12, "the wiki's 10.5 seconds");
        // The source's 「最大总计315」, reproduced from the numbers rather than restated as a constant.
        double totalPoints = OverTimeEffects.counterChargePointsPerSecond(
                OverTimeEffects.counterAmplifierFor(rate.base(), rate.perMark()), 20) * 10.5;
        assertEquals(315.0, totalPoints * 10.0, 1e-9,
                "at the 20-mark cap the clause totals 315 TP, which is the number the source states");
        assertTrue(sheffy.ub().enhanced().extra().isEmpty(),
                "the gap sentence must be deleted, not left: it renders as 「尚未实现」. Was: "
                        + sheffy.ub().enhanced().extra().orElse("(none)"));
    }
}