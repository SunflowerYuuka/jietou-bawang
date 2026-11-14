package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.impl.attach.StackKernel;
import com.astrum.pcrd.impl.attach.StackState;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The hook loop itself, without a game (spec §5.3, §6.4).
 *
 * <p>This is the file whose absence let the worst defect of the trigger task through review: the loop
 * in {@code HookEvaluator} deduped by trigger, so djeeta's second {@code combo_n: 3} effect
 * ({@code area_charge}) never ran — "djeeta works at 2/3" — while a WARN blamed the data author every
 * third hit and the whole suite stayed green.
 *
 * <p>Four properties are asserted here, each by a test that fails if the property is broken:
 *
 * <ol>
 *   <li>all effects on one trigger fire (§6.4: 本系统允许一个 trigger 挂多个 effect) — asserted twice,
 *       once through {@link HookPlan#fire} and once through the adapter's own path,
 *       {@link HookEvaluator#evaluateHooks}, so a per-trigger dedupe re-added between the character and
 *       the loop cannot pass;</li>
 *   <li>a {@code stack_full} hook that itself fills a stack cannot recurse — the {@code stack_full}
 *       pass runs with {@code allowStackFull=false}, so exactly two effects run and the run
 *       terminates;</li>
 *   <li>{@code stack_full} fires once per <b>fill</b>, not on every later hit at the cap — asserted by
 *       driving the real cap-transition rule ({@link EffectExecutors#reachesCap}) across five hits
 *       with one drain in the middle;</li>
 *   <li>the fail-closed reading of a missing {@code combo_n} parameter: {@code hits <= 0} never runs
 *       and is reported, instead of silently meaning "every hit".</li>
 * </ol>
 *
 * <p><b>Not asserted here:</b> the three lambdas {@link HookEvaluator#evaluate} builds from a live
 * {@code HookContext} — the chance roll from {@code RandomSource}, the {@code EffectExecutors.run}
 * dispatch and the warn-once diagnostic. Those need a server; this file deliberately has none, and
 * nothing here claims to cover them.
 */
class HookPlanTest {

    private static final String DJEETA_JSON =
            "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/djeeta.json";

    private static final ResourceLocation PROBE_STACK =
            ResourceLocation.fromNamespaceAndPath("astrum", "probe_stack");

    private static List<EffectType> types(List<HookPlan.Fired> fired) {
        return fired.stream().map(f -> f.hook().type()).collect(Collectors.toList());
    }

    private static List<Integer> indices(List<HookPlan.Fired> fired) {
        return fired.stream().map(HookPlan.Fired::hookIndex).toList();
    }

    private static CharacterDef djeeta() throws Exception {
        try (InputStream in = HookPlanTest.class.getResourceAsStream(DJEETA_JSON)) {
            assertNotNull(in, "shipped djeeta.json must be on the test classpath");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    /** A {@code stack} effect of {@code trigger} on {@link #PROBE_STACK}, capped at {@code max}. */
    private static EffectHook stackHook(Trigger trigger, TriggerParams params, int max) {
        return new EffectHook.Stack(trigger, params, 1.0, PROBE_STACK, max);
    }

    /**
     * The {@code stack_full} hook every stack test watches: an effect that is obviously not a stack, so
     * "did the {@code stack_full} pass run" is readable from the fired list alone.
     */
    private static EffectHook stackFullWatcher() {
        return new EffectHook.GainCharge(Trigger.STACK_FULL,
                new TriggerParams(0, Optional.of(PROBE_STACK)), 1.0, 1.0);
    }

    private static EffectHook comboHook(int hits) {
        return new EffectHook.Splash(Trigger.COMBO_N,
                new TriggerParams(hits, Optional.empty()), 1.0, 3.0, 4.0);
    }

    /**
     * The shipped character fires <b>one</b> small skill per {@code melee_hit}, and her two skills
     * alternate: swing 1 runs {@code extra_damage} (广域斩击), swing 2 runs <b>both</b> halves of
     * 武器爆发 (attack_buff + gain_charge), swing 3 is back to {@code extra_damage}.
     *
     * <p>This is the shape the human's 技能2 correction forced: ウェポンバースト is 「自分の物理攻撃力を
     * 小アップし、TPを特大回復」, so it is two effects on one skill — not two skills, which would have
     * made the rotation three long and dealt half of 广域斩击 on the swing that should have cast the
     * whole of 武器爆发.
     *
     * <p>Breaks if: the rotation stops advancing, advances on a failed chance roll, something dedupes
     * the list down to a single fixed hook, <b>or the rotation counts hooks instead of skill groups</b>
     * (swing 2 would then fire only attack_buff and swing 3 only gain_charge).
     */
    @Test
    void oneSwingFiresOneSmallSkillAndTheNextSwingFiresTheOther() throws Exception {
        CharacterDef djeeta = djeeta();

        HookPlan.Outcome first = HookPlan.fire(djeeta.effects(),
                TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                (index, hook) -> Optional.empty(), invalid -> {
                }, new SkillRotation(0));
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), types(first.fired()),
                "swing 1 must run 广域斩击 (extra_damage) and nothing else");

        HookPlan.Outcome second = HookPlan.fire(djeeta.effects(),
                TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                (index, hook) -> Optional.empty(), invalid -> {
                }, first.rotation());
        assertEquals(List.of(EffectType.ATTACK_BUFF, EffectType.GAIN_CHARGE), types(second.fired()),
                "swing 2 must run BOTH halves of 武器爆发 (attack up + TP), in one rotation step");
        assertEquals(List.of(1, 2), indices(second.fired()),
                "and both are the two hooks tagged weapon_burst");

        HookPlan.Outcome third = HookPlan.fire(djeeta.effects(),
                TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                (index, hook) -> Optional.empty(), invalid -> {
                }, second.rotation());
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), types(third.fired()),
                "swing 3 must wrap around to 广域斩击 — two skills, not three");
        assertEquals(first.rotation(), third.rotation(),
                "the period is 2: after swing 3 the cursor is back where it started");
    }

    /**
     * The same claim counted from the outside: over four swings the rotation alternates
     * 广域斩击 ↔ 武器爆发, and 武器爆发 always arrives as a pair.
     *
     * <p>Written separately from the test above because it asserts the <b>sequence</b> rather than
     * three hand-fed cursors — a rotation whose {@code advance} were applied twice on a two-hook skill
     * would still pass the cursor-by-cursor test (each call is given the right cursor) and fail here.
     */
    @Test
    void fourSwingsAlternateTheTwoSkillsAndWeaponBurstAlwaysArrivesAsAPair() throws Exception {
        CharacterDef djeeta = djeeta();
        SkillRotation cursor = SkillRotation.START;
        List<List<EffectType>> swings = new ArrayList<>();
        for (int swing = 0; swing < 4; swing++) {
            HookPlan.Outcome outcome = HookPlan.fire(djeeta.effects(),
                    TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                    (index, hook) -> Optional.empty(), invalid -> {
                    }, cursor);
            swings.add(types(outcome.fired()));
            cursor = outcome.rotation();
        }
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), swings.get(0));
        assertEquals(List.of(EffectType.ATTACK_BUFF, EffectType.GAIN_CHARGE), swings.get(1));
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), swings.get(2));
        assertEquals(List.of(EffectType.ATTACK_BUFF, EffectType.GAIN_CHARGE), swings.get(3));
        assertEquals(SkillRotation.START, cursor, "四次挥砍之后回到起点");
    }

    /**
     * The same rotation through the route production actually takes: {@link HookEvaluator} (the
     * adapter) rather than {@link HookPlan} directly.
     *
     * <p>Why separate: a dedupe or a rotation bug that lived in the adapter's loop would leave every
     * test that calls {@code HookPlan} directly green. This one calls the adapter's game-free seam.
     */
    @Test
    void theAdapterRunsOneRotatingSkillPerSwing() throws Exception {
        CharacterDef djeeta = djeeta();
        List<EffectType> first = new ArrayList<>();
        HookEvaluator.evaluateHooks(djeeta, TriggerFacts.forHit(true, false, false, 3, false),
                chance -> true,
                (index, hook) -> {
                    first.add(hook.type());
                    return Optional.empty();
                },
                invalid -> fail("no hook of the shipped character may be rejected: " + invalid.problem()),
                SkillRotation.START);

        assertEquals(List.of(EffectType.EXTRA_DAMAGE), first,
                "the adapter must run exactly one rotating skill on one swing");

        List<EffectType> second = new ArrayList<>();
        HookEvaluator.evaluateHooks(djeeta, TriggerFacts.forHit(true, false, false, 3, false),
                chance -> true,
                (index, hook) -> {
                    second.add(hook.type());
                    return Optional.empty();
                },
                invalid -> fail("no hook of the shipped character may be rejected: " + invalid.problem()),
                new SkillRotation(1));
        assertEquals(List.of(EffectType.ATTACK_BUFF, EffectType.GAIN_CHARGE), second,
                "技能2 through the adapter is both of its effects, on one swing");
    }

    /**
     * A {@code stack_full} hook that <b>itself</b> fills a stack must not send the loop round again.
     *
     * <p>The mechanism is the {@code allowStackFull=false} the recursive call carries: the outer pass
     * fills a stack ({@code probe_stack}), the {@code stack_full} pass runs hook #1, hook #1 reports a
     * fill of its own, and that second fill is ignored. So the observable consequence is a fired list
     * of exactly two entries — and a runner that is called exactly twice.
     *
     * <p>Breaks if: {@code allowStackFull} is dropped from the recursive call (the runner is then
     * entered a third, fourth, … time and this test fails with the message below instead of a
     * {@code StackOverflowError}), or if the recursive pass were run with the original facts rather
     * than {@code forStackFull} (hook #0 would run twice and the indices would read {@code [0, 0]}).
     */
    @Test
    void aStackFullHookThatFillsAStackDoesNotRecurse() {
        List<EffectHook> hooks = List.of(
                stackHook(Trigger.MELEE_HIT, TriggerParams.NONE, 1),
                stackHook(Trigger.STACK_FULL, new TriggerParams(0, Optional.of(PROBE_STACK)), 1));

        AtomicInteger runs = new AtomicInteger();
        HookPlan.EffectRun fillsEveryTime = (index, hook) -> {
            if (runs.incrementAndGet() > 8) {
                throw new AssertionError("the stack_full pass re-entered " + runs.get()
                        + " times: the recursive call must carry allowStackFull=false");
            }
            // Both hooks take their stack to its cap; only the guard decides where it stops.
            return Optional.of(PROBE_STACK);
        };

        List<HookPlan.Fired> fired = HookPlan.fire(hooks,
                TriggerFacts.forHit(true, false, false, 3, false), chance -> true, fillsEveryTime,
                invalid -> {
                });

        assertEquals(2, runs.get(), "exactly the melee stack and one stack_full effect may run");
        assertEquals(List.of(0, 1), indices(fired),
                "the second pass is the stack_full pass: hook #1, not a second melee hit");
    }

    /**
     * {@code stack_full} fires once per <b>fill</b>: the hit that reaches the cap, never a later hit
     * that leaves a full stack full, and again on a later fill after the stack is drained.
     *
     * <p>The only thing faked is where the count lives — a field instead of an entity attachment. The
     * arithmetic ({@link StackKernel#add}, which clamps to the cap) and the cap-transition rule
     * ({@link EffectExecutors#reachesCap}) are the production ones, so this test breaks if that rule
     * is ever widened to "at or above the cap" (hit 3 would then fire {@code stack_full} again) or
     * narrowed to "only the first fill ever" (the refill would then stay silent).
     *
     * <p>{@code stacks[0] = 0} stands in for {@code StackRuntime.consume}, i.e. for what a
     * {@code detonate} does between the two fills.
     */
    @Test
    void stackFullFiresOncePerFillNotOnEveryHitAtTheCap() {
        List<EffectHook> hooks = List.of(
                stackHook(Trigger.MELEE_HIT, TriggerParams.NONE, 2),
                stackFullWatcher());

        int[] stacks = {0};
        HookPlan.EffectRun fillASharedStack = (index, hook) -> {
            if (!(hook instanceof EffectHook.Stack stack)) {
                return Optional.empty();
            }
            int before = stacks[0];
            stacks[0] = StackKernel.add(new StackState(before, 0L), 0L, 1, stack.max());
            return EffectExecutors.reachesCap(before, stacks[0], stack.max())
                    ? Optional.of(stack.stackId())
                    : Optional.empty();
        };

        TriggerFacts hit = TriggerFacts.forHit(true, false, false, 3, false);
        Supplier<List<HookPlan.Fired>> oneHit = () -> HookPlan.fire(hooks, hit, chance -> true,
                fillASharedStack, invalid -> {
                });

        assertEquals(List.of(0), indices(oneHit.get()), "hit 1 of 2: a stack, no fill yet");
        assertEquals(List.of(EffectType.STACK, EffectType.GAIN_CHARGE), types(oneHit.get()),
                "the hit that takes the stack to 2/2 fires stack_full");
        assertEquals(List.of(0), indices(oneHit.get()),
                "a further hit at the cap must NOT fire stack_full again");

        stacks[0] = 0;   // drained, as StackRuntime.consume does for detonate
        assertEquals(List.of(0), indices(oneHit.get()), "after a drain the next hit only starts a stack");
        assertEquals(List.of(0, 1), indices(oneHit.get()),
                "a later fill fires stack_full again — once per fill, not once per stack");
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

        assertEquals(List.of(2), indices(fired), "only the well-formed combo_n hook may run");
        assertEquals(List.of(0, 1), reported.stream().map(HookPlan.Invalid::hookIndex).toList(),
                "both broken hooks must be reported, with their effects[] index");
        assertTrue(reported.get(0).problem().contains("hits"),
                "the report must name the offending parameter: " + reported.get(0).problem());
    }
}
