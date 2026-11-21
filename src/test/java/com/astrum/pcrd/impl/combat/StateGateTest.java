package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「持有某状态才生效的分支」: a state-gated hook fires while the state is held, and stops when it expires.</b>
 *
 * <h2>What a "state" is, asserted through the shape the data uses</h2>
 * A state is a real {@code MobEffect} on the caster, named by registry id in
 * {@link TriggerParams#requiresState()}. The rule is deliberately <em>not</em> "does the type have a flag":
 * it is a predicate over the firing body, so this test drives it exactly as production does — through
 * {@link HookPlan#fireTiered}'s gate — and simulates expiry by flipping the answer between two firings. No
 * state has to be cleared anywhere, which is the property the predicate shape buys.
 *
 * <h2>Why it is a gate and not a trigger</h2>
 * The two hooks below carry the same {@code triggerParams.skill} tag. That is 怜's 反击斩 shape: one rotating
 * skill whose extra damage depends on whether she holds 风之刃. A trigger of its own would put the branch on
 * its own turn of the rotation, which is a different skill.
 */
class StateGateTest {

    private static final ResourceLocation STATE = ResourceLocation.parse("astrum:wind_blade");

    private static EffectHook gated(double amount) {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT,
                new TriggerParams(0, Optional.empty(), Optional.of("counter_slash"), Optional.of(STATE)),
                1.0, amount);
    }

    private static EffectHook base(double amount) {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT,
                new TriggerParams(0, Optional.empty(), Optional.of("counter_slash")), 1.0, amount);
    }

    /** {@return every hook that ran when the caster holds {@code held}} */
    private static List<EffectHook> fire(Set<ResourceLocation> held) {
        List<EffectHook> fired = new ArrayList<>();
        HookPlan.fireTiered(List.of(base(6.7), gated(6.6)), TriggerFacts.forHit(true, false, false, 1, false),
                chance -> true,
                (index, hook, tier) -> {
                    fired.add(hook);
                    return Optional.empty();
                }, invalid -> {
                }, SkillRotation.START, 1, held::contains);
        return fired;
    }

    /**
     * <b>While the state is held both hooks run; when it expires only the base one does.</b>
     *
     * <p>The second firing is the same hook list and a different answer, which is exactly what happens when
     * 风之刃's 18-second instance runs out: no cleanup, no mutation of the hook, no cursor change.
     */
    @Test
    void aGatedHookFiresOnlyWhileTheStateIsHeld() {
        List<EffectHook> whileHeld = fire(Set.of(STATE));
        assertEquals(2, whileHeld.size(), "both the 6.7 base and the 6.6 wind-blade increment fire");
        assertTrue(whileHeld.contains(gated(6.6)), "the gated branch is the one the state unlocks");

        List<EffectHook> afterExpiry = fire(Set.of());
        assertEquals(1, afterExpiry.size(), "the gated branch stops the moment the state is gone");
        assertEquals(base(6.7), afterExpiry.get(0),
                "and the ungated base branch still fires — the state changes what the skill does, not when");
    }

    /** A gated hook in a list whose state is held keeps firing on every swing; there is no one-shot gate. */
    @Test
    void theGateIsReAskedOnEveryFiring() {
        assertEquals(2, fire(Set.of(STATE)).size());
        assertEquals(2, fire(Set.of(STATE)).size());
    }

    /**
     * <b>The data shape round-trips through the real codec.</b>
     *
     * <p>{@code requiresState} is an additive optional field: the parse below is the same
     * {@code TriggerParams.CODEC} every hook embeds, so a shipped character writing it is decoded by this
     * code path and not by a fixture.
     */
    @Test
    void requiresStateRoundTripsThroughTheRealCodec() {
        String json = """
                {"trigger":"melee_hit","effect":"extra_damage",
                 "triggerParams":{"skill":"counter_slash","requiresState":"astrum:wind_blade"},
                 "effectParams":{"amount":6.6}}""";
        EffectHook decoded = EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                .getOrThrow();
        assertEquals(Optional.of(STATE), decoded.triggerParams().requiresState());
        assertEquals(Optional.of("counter_slash"), decoded.triggerParams().skillTag());

        String ungated = """
                {"trigger":"melee_hit","effect":"extra_damage",
                 "triggerParams":{"skill":"counter_slash"},"effectParams":{"amount":6.7}}""";
        EffectHook plain = EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(ungated))
                .getOrThrow();
        assertEquals(Optional.empty(), plain.triggerParams().requiresState());
        assertFalse(TriggerParams.NONE.requiresState().isPresent());
        assertFalse(new TriggerParams(0, Optional.empty()).requiresState().isPresent(),
                "the two-field form every pre-existing caller uses is unconditional");
    }
}