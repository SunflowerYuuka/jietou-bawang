package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
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
     * @param facts     what just happened, as the eight triggers see it
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
     * Fires {@code hooks} for one event, in list order, honouring the character's own rotation.
     *
     * <p>See the class javadoc for the rule. In one sentence: of the hooks whose trigger is
     * {@link Trigger#MELEE_HIT}, exactly the cursor-th runs, and the cursor advances only if that one
     * actually fired.
     *
     * @param hooks     the character's hook list, indexed by its position
     * @param facts     what just happened, as the eight triggers see it
     * @param chance    the chance roll; called once per hook that is about to run
     * @param effects   runs one hook and answers with the stack it filled, if any
     * @param onInvalid receives every hook that cannot work as written
     * @param rotation  the cursor this character had before the event; {@code null} means {@link SkillRotation#START}
     * @return what ran and the cursor to store for the next swing
     */
    public static Outcome fire(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            EffectRun effects, Consumer<Invalid> onInvalid, SkillRotation rotation) {
        return fire(hooks, facts, chance, effects, onInvalid,
                rotation == null ? SkillRotation.START : rotation, true);
    }

    private static Outcome fire(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            EffectRun effects, Consumer<Invalid> onInvalid, SkillRotation rotation,
            boolean allowStackFull) {
        List<Fired> fired = new ArrayList<>();
        SkillGroups groups = SkillGroups.of(hooks);
        int target = rotation.peek(groups.count());
        boolean rotatingSkillFired = false;
        SkillRotation current = rotation;
        for (int index = 0; index < hooks.size(); index++) {
            EffectHook hook = hooks.get(index);
            Optional<String> problem = invalidReason(hook);
            if (problem.isPresent()) {
                onInvalid.accept(new Invalid(index, hook, problem.get()));
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
            fired.add(new Fired(index, hook));
            Optional<ResourceLocation> filled = effects.run(index, hook);
            if (filled.isPresent() && allowStackFull) {
                Outcome pass = fire(hooks, TriggerFacts.forStackFull(filled.get()), chance, effects,
                        onInvalid, current, false);
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
            case MELEE_HIT, PROJECTILE_HIT, CRIT, KILL, BLOCK, LOW_HEALTH, STACK_FULL -> Optional.empty();
        };
    }
}
