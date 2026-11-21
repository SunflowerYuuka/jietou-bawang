package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.impl.effect.EffectCollapse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The decision step of the hook pipeline: given a character's hook list and the facts of one event,
 * decides <b>which effects run and in what order</b> (spec §5.2/§5.3, §6.4).
 *
 * <h2>Why the loop is here and not in {@link HookEvaluator}</h2>
 * The loop used to mix five unrelated things — the trigger test, the chance roll, a per-trigger
 * dedupe, the effect dispatch and the {@code stack_full} re-entry — inside a class that also owns the
 * logger and a {@link HookContext} built from live game entities. Nothing about it could be asserted
 * without a Minecraft server, so nothing was: the per-trigger dedupe that silently dropped djeeta's
 * {@code area_charge} shipped, and stayed shipped, with a passing suite.
 *
 * <p>So the loop takes exactly four inputs — the hooks, the facts, a chance roll and a way to run one
 * effect — and returns what ran. Both seams are functional interfaces, which is what makes the three
 * properties this class owes the design assertable in a plain unit test:
 * <ul>
 *   <li>every effect hanging off one trigger fires (§6.4: 本系统允许一个 trigger 挂多个 effect);</li>
 *   <li>a {@code stack_full} hook that itself fills a stack does not recurse;</li>
 *   <li>{@code stack_full} fires once per <b>fill</b>, not on every later hit at the cap.</li>
 * </ul>
 *
 * <h2>Why running the effect is injected rather than returned</h2>
 * {@code stack_full} is not a fact about the world, it is a fact about <b>what one effect just did</b>:
 * a {@code stack} hook that took a count to its cap. A decision step that only returned a list could
 * not know that, so the list would have to be walked twice and the second walk would be the untested
 * code again. {@link EffectRun} returns that one fact (the stack id filled, if any), and the same loop
 * uses it to decide whether a {@code stack_full} pass follows. In production the runner is
 * {@link EffectExecutors#run}; in a test it is a lambda.
 *
 * <h2>Why there is no per-trigger dedupe</h2>
 * Spec §5.3's "每角色每类 trigger 每次攻击最多求值一次" limits <b>how many times a trigger is
 * evaluated</b>, not how many effects may hang off it — §6.4 states that one trigger may carry several
 * effects, and djeeta's shipped shape does exactly that (one {@code combo_n: 3}, two effects). Reading
 * §5.3 as "run only the first effect of a trigger" is what shipped "djeeta works at 2/3".
 *
 * <p>The §5.3 property is structural and already holds above this class: {@code CombatEvents}
 * subscribes to exactly one damage event, so one attack produces <b>one</b> call to
 * {@link HookEvaluator#evaluate} per character. A dedupe here would therefore subtract nothing that
 * structure does not already prevent — it could only ever drop a legitimate second effect. So every
 * hook whose trigger matches runs, in list order.
 *
 * <h2>The per-character skill rotation (and why it lives here)</h2>
 * 「每次挥砍同时触发队伍五个角色各自的小技能，下一次挥砍触发下一个技能。每个角色的轮转是独立的。」
 *
 * <p>So on one {@code melee_hit} event each equipped character fires <b>exactly one</b> of her own
 * small skills, and the next swing fires the next one. "Which one" is a decision about which effects
 * run — this class's one job — so it belongs here and not in an executor: an executor sees one hook
 * and cannot know it is the second of three.
 *
 * <p><b>Which hooks rotate.</b> Hooks whose {@link Trigger} is {@link Trigger#MELEE_HIT}. Every other
 * trigger keeps firing on its own condition, every time that condition holds, exactly as before: the
 * rotation answers "which of my swings' skills is next", and a {@code crit} or {@code kill} hook is not
 * a swing skill, it is a different event that happens to be able to fire at the same time. A single
 * character may legitimately have one rotating hook and three conditional ones.
 *
 * <p><b>What one turn of the rotation is: a skill, not a hook.</b> A skill may be several effects —
 * djeeta's 技能2 is 「自分の物理攻撃力を小アップし、TPを特大回復」, attack up <em>and</em> TP — and
 * counting those as two entries would make her rotation three long instead of two, so one swing would
 * deal half of 广域斩击 and half of 武器爆发. So the unit is the <b>skill group</b>:
 * {@link SkillGroups} groups the rotating hooks by {@code triggerParams.skill}, and the cursor indexes
 * the groups. Every hook of the chosen group fires on that swing; the cursor advances once, however
 * many effects the skill has.
 *
 * <p>The chosen group is the <b>cursor-th</b> of the rotating groups (not of {@code effects[]}),
 * so a character's rotation is unchanged by where the author puts her conditional hooks. The cursor
 * advances <b>only when her skill actually fires</b>: a failed chance roll leaves it where it is, so the
 * player gets that skill on the next swing instead of silently skipping it. The consequence worth
 * knowing is that a <em>permanently</em> broken rotating hook (an invalid parameter) holds the cursor
 * forever; it is reported once by {@link #invalidReason}'s report path, so the author sees why.
 *
 * <h2>What this class deliberately does not do</h2>
 * No logging (a caller owns the diagnostics), no {@code HookContext}, no chance arithmetic
 * ({@link HookEvaluator#roll} stays the one place a random number is drawn), and no knowledge of
 * characters or slots. Everything it returns is a plain value.
 */
@ApiStatus.Internal
public final class HookPlan {

    /**
     * One effect that ran, and where it sits in the character's hook list.
     *
     * <p>{@code hookIndex} is the position in {@code effects[]}, i.e. the same index
     * {@link com.astrum.pcrd.api.v1.HookModifier#hookIndex()} points at — so a log line or a test can
     * name the exact hook an author wrote.
     */
    public record Fired(int hookIndex, EffectHook hook) {
    }

    /**
     * A hook that cannot work as written, reported instead of being silently skipped.
     *
     * <p>The alternative — treating a nonsense parameter as a sane default — is what made
     * {@code combo_n} with {@code hits = 0} pay out on every hit (fail-open). A hook this class
     * rejects does not run and is handed to the caller, which owns the "warn once" policy.
     */
    public record Invalid(int hookIndex, EffectHook hook, String problem) {
    }

    /**
     * Runs one already-decided hook and reports the one outcome the loop needs back.
     *
     * <p>{@return the stack id whose count this effect took to its cap, or empty for every other
     *         outcome — the fact that decides whether a {@code stack_full} pass follows}
     */
    @FunctionalInterface
    public interface EffectRun {
        Optional<ResourceLocation> run(int hookIndex, EffectHook hook);
    }

    /**
     * Runs one already-decided hook <b>at a resolved tier</b>.
     *
     * <h2>Why the tier arrives here rather than being read off the hook</h2>
     * 「不同技能都相同效果，取最高等级」 — the same effect coming from two skills is applied
     * <b>once</b>, at the highest rung either skill reached. That decision needs the <em>whole</em> hook list
     * of one firing, so it cannot live in an executor (which sees one hook) and it cannot be done by rewriting
     * the list either, because the winner depends on the character's level band as well as on the declared
     * tiers. So {@link #fire} runs the collapse ({@code EffectCollapse.collapse}) and hands the survivor's
     * tier to the runner alongside the hook.
     *
     * <p>{@code tier} is {@code 0} for a hook that has no tier at all ({@code extra_damage} and friends), so a
     * runner that ignores it is correct for those — and one that used it blindly would be reporting a
     * non-tiered effect as tier 0, which no ladder contains. See {@code EffectCollapse.Applied#tier()}.
     */
    @FunctionalInterface
    public interface TieredEffectRun {
        Optional<ResourceLocation> run(int hookIndex, EffectHook hook, int tier);
    }

    /**
     * The whole answer of one {@link #fire}: what ran, and where the character's rotation now stands.
     *
     * @param fired    every effect that ran, in order, including any {@code stack_full} pass
     * @param rotation the cursor after this event, to be stored by the caller for the next swing
     */
    public record Outcome(List<Fired> fired, SkillRotation rotation) {

        public Outcome {
            fired = List.copyOf(fired);
            rotation = rotation == null ? SkillRotation.START : rotation;
        }
    }

    /**
     * The gate a caller passes when it <b>cannot answer</b> "does the caster hold this state" — a test that
     * never declares one, an ultimate whose caster has no live effect map.
     *
     * <p>Answers {@code false}, i.e. <b>fail-closed</b>: a state-gated hook does not fire for a caller that
     * could not check its state. The opposite default would make an unchecked gate mean "the state is always
     * held", which is the fail-open shape this project forbids and which would silently turn 怜's
     * wind-blade branch on for everybody.
     */
    public static final Predicate<ResourceLocation> NO_STATE_HELD = state -> false;

    private HookPlan() {
    }

    /**
     * Fires {@code hooks} for one event, in list order, with the character's rotation at its start.
     *
     * <p>The convenience form for a caller that has no cursor to keep — a test, or a character who is
     * not in a party. It is the same implementation as the five-argument-plus-cursor form and not a
     * second rule: the rotation is simply discarded.
     *
     * @param hooks     the character's hook list, indexed by its position
     * @param facts     what just happened, as the nine triggers see it
     * @param chance    the chance roll; called once per hook that is about to run
     * @param effects   runs one hook and answers with the stack it filled, if any
     * @param onInvalid receives every hook that cannot work as written
     * @return every effect that ran, in the order it ran, including any {@code stack_full} pass
     */
    public static List<Fired> fire(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            EffectRun effects, Consumer<Invalid> onInvalid) {
        return fire(hooks, facts, chance, effects, onInvalid, SkillRotation.START).fired();
    }

    /**
     * Fires {@code hooks} for one event, with the <b>same effect coming from several skills collapsed</b>.
     *
     * <p>The production form. {@link EffectRun} cannot express this: the collapse has to look at the whole
     * firing list at once and then hand the survivor's <em>resolved</em> tier to the executor, so the behaviour
     * that 「不同技能都相同效果，取最高等级」 describes has nowhere to live in a signature that says "run this
     * hook". See {@link TieredEffectRun}.
     *
     * @param hooks     the character's hook list, indexed by its position
     * @param facts     what just happened, as the nine triggers see it
     * @param chance    the chance roll; called once per hook that is about to run
     * @param effects   runs one hook at one resolved tier, and answers with the stack it filled
     * @param onInvalid receives every hook that cannot work as written
     * @param level     the character's level, for the band the collapse adds; {@code 0} for "unknown"
     */
    public static Outcome fireTiered(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            TieredEffectRun effects, Consumer<Invalid> onInvalid, int level) {
        return fireTiered(hooks, facts, chance, effects, onInvalid, SkillRotation.START, level);
    }

    /**
     * Fires {@code hooks} at an explicit rotation cursor, with the same-effect collapse applied.
     *
     * @param hooks    the character's hook list, indexed by its position
     * @param facts    what just happened
     * @param chance   the chance roll
     * @param effects  runs one hook at one resolved tier
     * @param onInvalid receives every hook that cannot work as written
     * @param rotation her cursor before the event; {@code null} means {@link SkillRotation#START}
     * @param level    the character's level, for the band
     */
    public static Outcome fireTiered(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            TieredEffectRun effects, Consumer<Invalid> onInvalid, SkillRotation rotation, int level) {
        return fireTiered(hooks, facts, chance, effects, onInvalid, rotation, level, NO_STATE_HELD);
    }

    /**
     * Fires {@code hooks} at an explicit rotation cursor, with the same-effect collapse applied and a
     * <b>state gate</b>.
     *
     * <h2>The state gate</h2>
     * 「状态」 is a {@code MobEffect} the caster is holding (see {@code TriggerParams#requiresState()}). A hook
     * that names one fires only while {@code stateHeld} answers true for it, and stops the moment the effect
     * expires — the gate is asked on <b>every</b> firing, so nothing has to be cleared when the state ends.
     * The gate is a {@link Predicate} rather than an {@code EffectType} check because "does she hold it" is a
     * live question about one entity; keeping it a function is what lets the rule be asserted with no world.
     *
     * <p>A skipped gated hook is silent and does not advance the rotation (its skill's other hooks still
     * decide the turn), which is the same treatment a missed chance roll gets: the branch is unavailable, not
     * broken. {@link #NO_STATE_HELD} is the fail-closed default every older overload passes.
     *
     * @param stateHeld answers "is {@code state} on the caster right now"; called once per gated hook
     */
    public static Outcome fireTiered(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            TieredEffectRun effects, Consumer<Invalid> onInvalid, SkillRotation rotation, int level,
            Predicate<ResourceLocation> stateHeld) {
        return run(hooks, facts, chance, effects, onInvalid,
                rotation == null ? SkillRotation.START : rotation, true, level, true, stateHeld);
    }

    /**
     * Fires {@code hooks} for one event, in list order, honouring the character's own rotation.
     *
     * <p>See the class javadoc for the rule. In one sentence: of the hooks whose trigger is
     * {@link Trigger#MELEE_HIT}, exactly the cursor-th runs, and the cursor advances only if that one
     * actually fired.
     *
     * <p>This form does <b>not</b> collapse duplicate effects: it is the low-level seam, where "every hook you
     * were given runs" is what a caller is asserting. Production goes through {@link #fireTiered}, which
     * collapses. The difference is one boolean in {@link #run}, not a second loop.
     *
     * @param hooks     the character's hook list, indexed by its position
     * @param facts     what just happened, as the nine triggers see it
     * @param chance    the chance roll; called once per hook that is about to run
     * @param effects   runs one hook and answers with the stack it filled, if any
     * @param onInvalid receives every hook that cannot work as written
     * @param rotation  the cursor this character had before the event; {@code null} means {@link SkillRotation#START}
     * @return what ran and the cursor to store for the next swing
     */
    public static Outcome fire(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            EffectRun effects, Consumer<Invalid> onInvalid, SkillRotation rotation) {
        return run(hooks, facts, chance, (index, hook, tier) -> effects.run(index, hook), onInvalid,
                rotation == null ? SkillRotation.START : rotation, true, 0, false, NO_STATE_HELD);
    }

    /**
     * The one firing loop: decide, collapse, execute.
     *
     * <h2>Why decide-then-execute rather than execute-as-you-walk</h2>
     * The collapse needs the <b>whole</b> set of hooks this event will fire before it can answer which single
     * tier each effect runs at — an executor cannot see the hook after it. And the decision itself must not be
     * computed twice: the chance roll is drawn once per hook and the rotation advances once per swing, so a
     * second walk would either roll again (changing the outcome) or need the rolls cached.
     *
     * <p>So the walk has two phases over one list. <b>Decide</b> applies the six gates in their documented
     * order — well-formed, state held, trigger observed, this skill's turn, chance roll — and records the
     * survivors with their indices. <b>Execute</b> then collapses those survivors and runs them. The rotation
     * and the grouping are computed on the <b>author's</b> list, so collapsing cannot change the rotation's
     * period or which skill's turn it is.
     *
     * @param collapse whether to collapse duplicate tiered effects; {@code false} for the low-level seam
     * @param stateHeld the state gate; {@link #NO_STATE_HELD} for every caller that cannot answer it
     */
    private static Outcome run(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            TieredEffectRun effects, Consumer<Invalid> onInvalid, SkillRotation rotation,
            boolean allowStackFull, int level, boolean collapse, Predicate<ResourceLocation> stateHeld) {
        SkillGroups groups = SkillGroups.of(hooks);
        int target = rotation.peek(groups.count());
        SkillRotation current = rotation;

        // ---- phase 1: decide. Survivors, in author order, with the index each one reports.
        List<Integer> survivors = new ArrayList<>();
        List<EffectHook> survivorHooks = new ArrayList<>();
        boolean rotatingSkillFired = false;
        for (int index = 0; index < hooks.size(); index++) {
            EffectHook hook = hooks.get(index);
            Optional<String> problem = invalidReason(hook);
            if (problem.isPresent()) {
                onInvalid.accept(new Invalid(index, hook, problem.get()));
                continue;
            }
            Optional<ResourceLocation> requiredState = hook.triggerParams().requiresState();
            if (requiredState.isPresent() && !stateHeld.test(requiredState.get())) {
                // 「持有某状态才生效的分支」: the branch is unavailable, not broken — no diagnostic, and the
                // skill's other hooks still decide this turn. The gate is re-asked on the next firing, so an
                // expired state simply stops the branch with nothing to clear.
                continue;
            }
            if (!facts.observes(hook.trigger(), hook.triggerParams())) {
                continue;
            }
            int group = groups.skillOf(index);
            if (group != SkillGroups.NOT_ROTATING && group != target) {
                continue;   // her other small skills wait for their own swing
            }
            if (!chance.test(hook.chance())) {
                continue;   // a miss must not consume the swing: the cursor stays put
            }
            if (group != SkillGroups.NOT_ROTATING) {
                rotatingSkillFired = true;
            }
            survivors.add(index);
            survivorHooks.add(hook);
        }

        // ---- phase 2: collapse, then execute.
        List<Fired> fired = new ArrayList<>(survivors.size());
        List<EffectCollapse.Applied> applied = collapse
                ? EffectCollapse.collapse(survivorHooks, level)
                : uncollapsed(survivorHooks);
        for (EffectCollapse.Applied one : applied) {
            int index = survivorIndexOf(survivors, survivorHooks, one.hook());
            fired.add(new Fired(index, one.hook()));
            Optional<ResourceLocation> filled = effects.run(index, one.hook(), one.tier());
            if (filled.isPresent() && allowStackFull) {
                Outcome pass = run(hooks, TriggerFacts.forStackFull(filled.get()), chance, effects,
                        onInvalid, current, false, level, collapse, stateHeld);
                fired.addAll(pass.fired());
            }
        }
        // Advanced once, after the walk: advancing inside the loop would move the cursor under the
        // feet of the very pass that is deciding what runs. One advance per swing, not per effect —
        // a two-effect skill is still one skill.
        current = rotatingSkillFired ? current.advance(groups.count()) : current;
        return new Outcome(fired, current);
    }

    /**
     * {@return every hook as its own application, for the seam that must not collapse}
     *
     * <p>The tier is read as written ({@code 0} for a hook that has none): this path exists so a caller can
     * assert "all N hooks ran", and inventing a band here would make that assertion depend on a level the
     * caller never supplied.
     */
    private static List<EffectCollapse.Applied> uncollapsed(List<EffectHook> hooks) {
        List<EffectCollapse.Applied> all = new ArrayList<>(hooks.size());
        for (EffectHook hook : hooks) {
            Optional<com.astrum.pcrd.api.v1.TieredParams> params = hook.tieredParams();
            int tier = params.map(com.astrum.pcrd.api.v1.TieredParams::tier).orElse(0);
            all.add(new EffectCollapse.Applied(hook, tier, tier, 1));
        }
        return all;
    }

    /**
     * {@return the author's index of {@code hook} among the survivors}
     *
     * <p>Matched by <b>identity</b>, which is what the collapse preserves: the survivor of a key is the first
     * hook that carried it, not a copy. So a duplicate that was collapsed away keeps no index, and the
     * diagnostic lines and {@code Fired.hookIndex} keep pointing at the hook an author wrote.
     */
    private static int survivorIndexOf(List<Integer> survivors, List<EffectHook> survivorHooks,
            EffectHook hook) {
        for (int i = 0; i < survivorHooks.size(); i++) {
            if (survivorHooks.get(i) == hook) {
                return survivors.get(i);
            }
        }
        return -1;
    }

    /**
     * {@return why {@code hook} cannot work as written, or empty when it is well formed}
     *
     * <p>Switched on the trigger, exhaustively: a new parameterised trigger has to be given a rule
     * here, so it cannot silently inherit "whatever the arithmetic happens to answer" — which is how
     * {@code combo_n} with the codec's default {@code hits = 0} came to mean "every hit".
     *
     * <p>The only rule so far is {@code combo_n}: a period of zero or less is not a period, and
     * clamping it to 1 would be a valid period that pays out on every hit. There is no value that makes
     * the hook mean what its author presumably meant, so it is reported instead of guessed.
     */
    private static Optional<String> invalidReason(EffectHook hook) {
        return switch (hook.trigger()) {
            case COMBO_N -> hook.triggerParams().hits() >= 1
                    ? Optional.empty()
                    : Optional.of("combo_n needs triggerParams.hits >= 1 but has hits="
                            + hook.triggerParams().hits() + "; add triggerParams or the hook never fires "
                            + "(it is not treated as 1, which would pay out on every hit)");
            case MELEE_HIT, PROJECTILE_HIT, CRIT, KILL, BLOCK, LOW_HEALTH, BATTLE_START, UB_LAND,
                    STACK_FULL -> Optional.empty();
        };
    }
}
