package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hook loop itself, without a game (spec §5.3, §6.4).
 *
 * <p>This is the file whose absence let the worst defect of the trigger task through review: the loop
 * in {@code HookEvaluator} deduped by trigger, so djeeta's second {@code combo_n: 3} effect
 * ({@code area_charge}) never ran — "djeeta works at 2/3" — while a WARN blamed the data author every
 * third hit and the whole suite stayed green. The three properties asserted here are the ones the
 * brief for the fix round names:
 *
 * <ol>
 *   <li>all effects on one trigger fire (§6.4: 本系统允许一个 trigger 挂多个 effect);</li>
 *   <li>a {@code stack_full} hook that itself fills a stack cannot recurse;</li>
 *   <li>{@code stack_full} fires once per <b>fill</b>, not on every later hit at the cap.</li>
 * </ol>
 *
 * <p>Plus the fail-closed reading of a missing {@code combo_n} parameter: {@code hits <= 0} never runs
 * and is reported, instead of silently meaning "every hit".
 */
class HookPlanTest {

    private static final String DJEETA_JSON =
            "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/djeeta.json";

    private static final ResourceLocation PROBE_STACK = ResourceLocation.fromNamespaceAndPath("astrum", "probe_stack");

    /** Every roll passes; effects report "nothing filled". */
    private static HookPlan.EffectRun recordInto(List<HookPlan.Fired> sink) {
        return (index, hook) -> {
            return Optional.empty();
        };
    }

    private static List<EffectType> types(List<HookPlan.Fired> fired) {
        return fired.stream().map(f -> f.hook().type()).collect(Collectors.toList());
    }

    private static CharacterDef djeeta() throws Exception {
        try (InputStream in = HookPlanTest.class.getResourceAsStream(DJEETA_JSON)) {
            assertNotNull(in, "shipped djeeta.json must be on the test classpath");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static EffectHook stackHook(int max) {
        return new EffectHook.Stack(com.astrum.pcrd.api.v1.Trigger.MELEE_HIT,
                com.astrum.pcrd.api.v1.TriggerParams.NONE, 1.0, PROBE_STACK, max);
    }

    private static EffectHook comboHook(int hits) {
        return new EffectHook.Splash(com.astrum.pcrd.api.v1.Trigger.COMBO_N,
                new com.astrum.pcrd.api.v1.TriggerParams(hits, Optional.empty()), 1.0, 3.0, 4.0);
    }

    /**
     * The shipped character, at the shipped {@code combo_n: 3}, must fire <b>all three</b> of her
     * effects: her {@code melee_hit} charge, and — from the same trigger — {@code splash} <b>and</b>
     * {@code area_charge}. Two effects on one trigger is the documented shape (§6.4).
     *
     * <p>Breaks if: anything dedupes by trigger, or stops after the first match of a trigger.
     */
    @Test
    void everyEffectOfOneTriggerFiresFromTheShippedJson() throws Exception {
        List<HookPlan.Fired> fired = new ArrayList<>();
        List<HookPlan.Fired> result = HookPlan.fire(djeeta().effects(),
                TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                recordInto(fired), invalid -> {
                });
        assertEquals(List.of(EffectType.GAIN_CHARGE, EffectType.SPLASH, EffectType.AREA_CHARGE), types(result),
                "djeeta's 3rd combo hit must run melee charge, splash AND area_charge");
        assertEquals(List.of(0, 1, 2), result.stream().map(HookPlan.Fired::hookIndex).toList(),
                "hook indices must be the effects[] positions, in order");
    }

    /**
     * {@code combo_n} with {@code hits <= 0} must not fire <b>and</b> must be reported, naming the hook
     * index.
     *
     * <p>{@code TriggerParams.CODEC} defaults {@code hits} to {@code 0}, so a data author who writes
     * {@code {"trigger":"combo_n"}} and no {@code triggerParams} lands here. The old behaviour clamped
     * the period to 1 and fired on every hit (fail-open, permanent payout); silently clamping is still
     * wrong even if it did not pay out, because the author never learns their hook is inert. So: no
     * effect, and a report the caller turns into a WARN.
     */
    @Test
    void comboNWithNonPositiveHitsIsSkippedAndReported() {
        List<HookPlan.Invalid> reported = new ArrayList<>();
        List<HookPlan.Fired> fired = HookPlan.fire(
                List.of(comboHook(0), comboHook(-2), comboHook(3)),
                TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                (index, hook) -> Optional.empty(), reported::add);

        assertEquals(List.of(2), fired.stream().map(HookPlan.Fired::hookIndex).toList(),
                "only the well-formed combo_n hook may run");
        assertEquals(List.of(0, 1), reported.stream().map(HookPlan.Invalid::hookIndex).toList(),
                "both broken hooks must be reported, with their effects[] index");
        assertTrue(reported.get(0).problem().contains("hits"),
                "the report must name the offending parameter: " + reported.get(0).problem());
    }
}
