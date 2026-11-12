package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turns one character's hook list into effects for one landed hit (spec §5.1/§5.2/§5.3).
 *
 * <h2>Why this is its own class</h2>
 * It is the "what does a hook do" half of the damage pipeline; {@link CombatEvents} is the "when did a
 * hit happen" half. Keeping them apart means the event listener stays about event choice, ordering and
 * the recursion fuse, while this class is about the eight effects — the two questions that were already
 * tangled once in this project.
 *
 * <h2>The three rules that live in this class</h2>
 * <ul>
 *   <li><b>Trigger dedupe (spec §5.3).</b> Each character, each trigger kind, at most once per
 *       attack. Enforced per trigger rather than per effect type, because the rule is about the event
 *       being counted twice. A second hook for an already-fired trigger is skipped <b>loudly</b>: it is
 *       a data-authoring mistake and silence is what makes such a bug invisible.</li>
 *   <li><b>Trigger observation.</b> Only the triggers a landed damage event can honestly observe are
 *       answered {@code true}; see {@link #observes}.</li>
 *   <li><b>Chance.</b> Rolled before the effect runs, once per hook, from the attacker's
 *       {@link java.util.RandomSource}.</li>
 * </ul>
 *
 * <p>The effects themselves, and the diminishing-returns rule they obey, live in
 * {@link EffectExecutors}; the multiplier is passed there in the {@link HookContext} and never decides
 * whether a hook fires.
 */
@ApiStatus.Internal
public final class HookEvaluator {

    private HookEvaluator() {
    }

    /**
     * Runs {@code def}'s hooks for one hit, applying whatever they produce through {@code ctx}.
     *
     * @param def the character definition owning the hooks
     * @param ctx the hit, carrying this slot's multiplier and character attribution
     */
    public static void evaluate(CharacterDef def, HookContext ctx) {
        Set<Trigger> fired = EnumSet.noneOf(Trigger.class);
        for (EffectHook hook : def.effects()) {
            if (fired.contains(hook.trigger())) {
                Astrum.LOGGER.warn("astrum: a second {} hook of the same character was skipped on {} "
                        + "(spec §5.3 allows one per trigger per attack); check the hook order — the "
                        + "one that fired may not be the one the author meant", hook.trigger(),
                        ctx.target().getName().getString());
                continue;
            }
            if (!observes(hook.trigger(), ctx.source(), ctx.attacker())) {
                continue;
            }
            if (!roll(hook.chance(), ctx.attacker())) {
                Astrum.LOGGER.debug("astrum: {} hook of {} missed its {} chance roll",
                        hook.type(), ctx.characterId(), hook.chance());
                continue;
            }
            fired.add(hook.trigger());
            EffectExecutors.run(hook, ctx);
        }
    }

    /**
     * {@return whether this damage sequence can be the trigger a hook waits for}
     *
     * <p>Only the triggers a landed damage event can observe are implemented. The rest belong to other
     * sources — {@code kill} to the death path, {@code block} to the shield path, {@code
     * low_health} to a health query, {@code combo_n}/{@code stack_full} to hit counters that do not
     * exist yet — and answering {@code false} is the honest answer here rather than pretending they
     * fired. A hook on such a trigger is inert until its counter is built; it is not silently firing
     * with the wrong semantics.
     */
    private static boolean observes(Trigger trigger, DamageSource source, ServerPlayer attacker) {
        return switch (trigger) {
            case MELEE_HIT -> source.getDirectEntity() == attacker;
            case PROJECTILE_HIT -> source.is(DamageTypeTags.IS_PROJECTILE);
            case CRIT, KILL, LOW_HEALTH, COMBO_N, BLOCK, STACK_FULL -> false;
        };
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
