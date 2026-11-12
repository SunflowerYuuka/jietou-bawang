package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turns one character's hook list into effects for one trigger event (spec §5.1/§5.2/§5.3).
 *
 * <h2>Why this is its own class</h2>
 * It is the "what does a hook do" half of the pipeline; {@link CombatEvents} is the "when did something
 * happen" half. Keeping them apart means the event listener stays about event choice, ordering, the
 * recursion fuse and the facts of what just happened, while this class is about the eight effects.
 *
 * <h2>The three rules that live here</h2>
 * <ul>
 *   <li><b>Trigger dedupe (spec §5.3).</b> Each character, each trigger kind, at most once per event.
 *       Enforced per trigger rather than per effect type, because the rule is about the event being
 *       counted twice. A second hook for an already-fired trigger is skipped <b>loudly</b>: it is a
 *       data-authoring mistake and silence is what makes such a bug invisible.</li>
 *   <li><b>Trigger observation.</b> Whether the event is the one a trigger waits for is
 *       {@link TriggerFacts#observes}, not a table of hard-coded {@code false}s — all eight triggers are
 *       observable now, and that switch is exhaustive.</li>
 *   <li><b>Chance.</b> Rolled before the effect runs, once per hook, from the attacker's
 *       {@link net.minecraft.util.RandomSource}.</li>
 * </ul>
 *
 * <h2>The one re-entrant step: {@code stack_full}</h2>
 * A {@code stack} hook can drive a count to its cap, and "the stack is full" is a trigger of its own.
 * The stack arm reports that fact back here, and this class re-evaluates the <b>same character</b> with
 * {@link TriggerFacts#forStackFull} — same context, same slot multiplier, so a slot-5 character's
 * {@code stack_full} hook is still scaled as a slot-5 character. The re-entrant call is marked
 * {@code allowStackFull = false}, so a {@code stack_full} hook that itself adds stacks cannot loop.
 */
@ApiStatus.Internal
public final class HookEvaluator {

    private HookEvaluator() {
    }

    /**
     * Runs {@code def}'s hooks for one event, applying whatever they produce through {@code ctx}.
     *
     * @param def  the character definition owning the hooks
     * @param ctx  the event, carrying this slot's multiplier and character attribution
     * @param facts what just happened, as the eight triggers see it
     */
    public static void evaluate(CharacterDef def, HookContext ctx, TriggerFacts facts) {
        evaluate(def, ctx, facts, true);
    }

    private static void evaluate(CharacterDef def, HookContext ctx, TriggerFacts facts,
            boolean allowStackFull) {
        Set<Trigger> fired = EnumSet.noneOf(Trigger.class);
        for (EffectHook hook : def.effects()) {
            if (fired.contains(hook.trigger())) {
                Astrum.LOGGER.warn("astrum: a second {} hook of character {} was skipped on {} "
                        + "(spec §5.3 allows one per trigger per event); check the hook order — the "
                        + "one that fired may not be the one the author meant", hook.trigger(),
                        ctx.characterId(), ctx.target().getName().getString());
                continue;
            }
            if (!facts.observes(hook.trigger(), hook.triggerParams())) {
                continue;
            }
            if (!roll(hook.chance(), ctx.attacker())) {
                Astrum.LOGGER.debug("astrum: {} hook of {} missed its {} chance roll",
                        hook.type(), ctx.characterId(), hook.chance());
                continue;
            }
            fired.add(hook.trigger());
            ResourceLocation reachedMax = EffectExecutors.run(hook, ctx);
            if (reachedMax != null && allowStackFull) {
                Astrum.LOGGER.debug("astrum: stack {} reached its cap on {} — firing the {} stack_full "
                        + "hooks", reachedMax, ctx.target().getName().getString(), ctx.characterId());
                evaluate(def, ctx, TriggerFacts.forStackFull(reachedMax), false);
            }
        }
    }

    /** {@return the chance roll for one hook} */
    private static boolean roll(double chance, ServerPlayer attacker) {
        if (chance >= 1.0) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        return attacker.getRandom().nextFloat() < chance;
    }
}
