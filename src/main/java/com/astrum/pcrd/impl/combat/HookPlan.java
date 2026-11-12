package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.EffectHook;
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

    private HookPlan() {
    }

    /**
     * Fires {@code hooks} for one event, in list order.
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
        return fire(hooks, facts, chance, effects, onInvalid, true);
    }

    private static List<Fired> fire(List<EffectHook> hooks, TriggerFacts facts, DoublePredicate chance,
            EffectRun effects, Consumer<Invalid> onInvalid, boolean allowStackFull) {
        List<Fired> fired = new ArrayList<>();
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
            if (!chance.test(hook.chance())) {
                continue;
            }
            fired.add(new Fired(index, hook));
            Optional<ResourceLocation> filled = effects.run(index, hook);
            if (filled.isPresent() && allowStackFull) {
                fired.addAll(fire(hooks, TriggerFacts.forStackFull(filled.get()), chance, effects, onInvalid,
                        false));
            }
        }
        return fired;
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
