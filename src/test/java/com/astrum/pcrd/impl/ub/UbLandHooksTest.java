package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.api.v1.UbHooks;
import com.astrum.pcrd.impl.combat.HookPlan;
import com.astrum.pcrd.impl.combat.TriggerFacts;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * <b>「大招的敌方减益」: the UB-side trigger lands a debuff on the bodies the ultimate hit.</b>
 *
 * <h2>What is asserted, and where production runs the same code</h2>
 * The decision is driven through {@link UbLandHooks#decide} — the method the settlement itself calls, which
 * delegates to the production {@link HookPlan#fireTiered}. So the test walks the route production takes: the
 * ultimate's facts ({@link TriggerFacts#forUbLand()}), the same collapse, the same executor hand-off. What it
 * cannot drive is the live executor and the {@code hurt()} gate; those are the settlement's, and the report
 * says a human has to watch a cast for them.
 *
 * <h2>The shipped data, decoded, not a fixture</h2>
 * {@code misogi.json}'s 6★ rung is the character this trigger exists for — 「降低敌方全体物理攻击力 18 秒」
 * — so the second test decodes the real file through {@link CharacterDef#CODEC} and reads its
 * {@code ub.landHooks}. A fixture would pass while the pack shipped nothing.
 */
class UbLandHooksTest {

    private static final ResourceLocation WEAKNESS = ResourceLocation.parse("minecraft:weakness");

    private static EffectHook weakness() {
        return new EffectHook.ApplyEffect(Trigger.UB_LAND, TriggerParams.NONE, 1.0, WEAKNESS, 360, 4);
    }

    private static EffectHook swing() {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT, TriggerParams.NONE, 1.0, 2.6);
    }

    /** {@return every hook {@code decide} ran, in order} */
    private static List<EffectHook> fired(List<EffectHook> hooks) {
        List<EffectHook> fired = new ArrayList<>();
        HookPlan.Outcome outcome = UbLandHooks.decide(hooks, 1, chance -> true, state -> false,
                (index, hook, tier) -> {
                    fired.add(hook);
                    return Optional.empty();
                }, invalid -> {
                });
        assertEquals(outcome.fired().size(), fired.size(), "the outcome and the runner must agree");
        return fired;
    }

    /**
     * <b>The ultimate's facts observe {@code ub_land} and nothing a swing observes.</b>
     *
     * <p>This is what makes the trigger the trigger: if {@link TriggerFacts#forUbLand()} also answered a
     * {@code melee_hit}, a UB's {@code effects[]} would fire on a swing, and a UB-land hook written with the
     * wrong trigger would pretend to work.
     */
    @Test
    void theUltimateFactsObserveOnlyTheUbTrigger() {
        TriggerFacts facts = TriggerFacts.forUbLand();
        assertTrue(facts.observes(Trigger.UB_LAND, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.MELEE_HIT, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.CRIT, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.KILL, TriggerParams.NONE));
        assertFalse(facts.observes(Trigger.STACK_FULL, TriggerParams.NONE));
        // And the mirror: an ordinary hit does not observe the ultimate's trigger.
        assertFalse(TriggerFacts.forHit(true, false, false, 1, false)
                .observes(Trigger.UB_LAND, TriggerParams.NONE));
    }

    /**
     * <b>A {@code ub_land} debuff runs, and a swing hook put in the same list does not.</b>
     *
     * <p>The mixed list is the point: {@link UbHooks#problemOf} owns the "only this trigger is legal here"
     * rule, and {@link UbLandHooks#legal} applies it. A list holding both must run exactly the UB clause, or a
     * pack could believe its swing hook belongs to the ultimate.
     */
    @Test
    void aUbLandDebuffRunsAndASwingHookInTheSameListDoesNot() {
        List<EffectHook> declared = List.of(weakness(), swing());
        List<EffectHook> legal = UbLandHooks.legal(declared, ResourceLocation.parse("astrum:misogi"));
        assertEquals(List.of(weakness()), legal, "the swing hook must be dropped from a UB-land list");

        List<EffectHook> fired = fired(legal);
        assertEquals(1, fired.size());
        assertEquals(EffectHook.ApplyEffect.class, fired.get(0).getClass(),
                "the UB landed its debuff — the weakness is what this trigger exists for");
        assertEquals(WEAKNESS, ((EffectHook.ApplyEffect) fired.get(0)).effect());
        assertEquals(4, ((EffectHook.ApplyEffect) fired.get(0)).amplifier(),
                "4 is 「特大幅度」 on the adjective ladder docs/derivations-batch-b.md §1.2 fixes");
        assertEquals(360, ((EffectHook.ApplyEffect) fired.get(0)).duration(), "18 s = 360 ticks");
    }

    /** The shipped 未奏希 6★ rung declares exactly that clause, and her base rung declares none. */
    @Test
    void theShippedMisogiEnhancedRungCarriesTheWeaknessClause() throws Exception {
        CharacterDef misogi = load("misogi");
        assertFalse(misogi.ub().landHooksFor(true).isEmpty(),
                "her 6★ UB is the character this trigger was built for");
        assertEquals(List.of(), misogi.ub().landHooksFor(false),
                "the base UB has no such clause, so a base cast must not lower attack");
        UbHooks hooks = misogi.ub().landHooks().orElseThrow();
        assertEquals(Optional.empty(), hooks.mismatch(),
                "every shipped UB hook must be legal (trigger ub_land)");
        List<EffectHook> enhanced = misogi.ub().landHooksFor(true);
        assertEquals(1, enhanced.size());
        EffectHook.ApplyEffect applied = (EffectHook.ApplyEffect) enhanced.get(0);
        assertEquals(Trigger.UB_LAND, applied.trigger());
        assertEquals(WEAKNESS, applied.effect());
        assertEquals(4, applied.amplifier());
        assertEquals(360, applied.duration());
    }

    private static CharacterDef load(String id) throws Exception {
        String path = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id
                + ".json";
        try (InputStream in = UbLandHooksTest.class.getResourceAsStream(path)) {
            assertNotNull(in, id + ".json must be packaged at " + path);
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }
}