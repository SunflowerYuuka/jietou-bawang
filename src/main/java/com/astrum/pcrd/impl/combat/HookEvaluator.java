package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.impl.InternalIds;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turns one character's hook list into effects for one landed hit (spec §5.1/§5.2/§5.3).
 *
 * <h2>Why this is its own class</h2>
 * It is the part Task 10 grows eight-fold: today one {@link EffectType#EXTRA_DAMAGE} arm, then
 * {@code apply_effect}, {@code lifesteal}, {@code gain_charge}, {@code area_charge}, {@code splash},
 * {@code stack} and {@code detonate}. Keeping it out of {@link CombatEvents} means the event listener
 * stays about <em>when</em> a hit happened and this class stays about <em>what</em> a hook does —
 * the two questions that were already tangled once in this task.
 *
 * <h2>The rules that live here and nowhere else</h2>
 * <ul>
 *   <li><b>Trigger dedupe (spec §5.3).</b> Each character, each trigger kind, at most once per
 *       attack. Enforced per trigger rather than per effect type, because the rule is about the
 *       event being counted twice. A second hook for an already-fired trigger is skipped
 *       <b>loudly</b>: it is a data-authoring mistake (or a hook ordered before a not-yet-implemented
 *       one) and silence is what makes such a bug invisible.</li>
 *   <li><b>Diminishing returns scale quantities only (spec §5.2).</b> The multiplier is handed to the
 *       effect arm; it never decides whether the arm runs.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class HookEvaluator {

    /** The one damage type attachment damage uses (spec §5.1 rule 3). */
    private static final ResourceLocation HOOK_DAMAGE = InternalIds.id("hook_damage");

    /**
     * One landed hit, as an effect executor sees it.
     *
     * <p>All three fields are carried for the executors Task 10 adds — {@code lifesteal} and
     * {@code gain_charge} read {@link #attacker()}, {@code splash} and {@code area_charge} read
     * {@link #target()}, {@code stack} reads {@link #source()} attribution. The only arm implemented
     * today reads just the target.
     *
     * @param attacker the player whose wheel is being evaluated
     * @param target   the entity that was hit
     * @param source   the damage source that landed
     */
    public record Hit(ServerPlayer attacker, LivingEntity target, DamageSource source) {
    }

    private HookEvaluator() {
    }

    /**
     * Runs {@code def}'s hooks for one hit, adding whatever they produce to {@code batch}.
     *
     * @param def        the character definition owning the hooks
     * @param multiplier this slot's diminishing multiplier
     * @param hit        the landed hit
     * @param batch      the accumulator the hit will be flushed from
     */
    public static void evaluate(CharacterDef def, double multiplier, Hit hit, DamageBatch batch) {
        Set<Trigger> fired = EnumSet.noneOf(Trigger.class);
        for (EffectHook hook : def.effects()) {
            if (fired.contains(hook.trigger())) {
                Astrum.LOGGER.warn("astrum: a second {} hook of the same character was skipped on {} "
                        + "(spec §5.3 allows one per trigger per attack); check the hook order — the "
                        + "one that fired may not be the one the author meant", hook.trigger(),
                        hit.target().getName().getString());
                continue;
            }
            if (!observes(hook.trigger(), hit.source(), hit.attacker())) {
                continue;
            }
            if (!roll(hook.chance(), hit.attacker())) {
                continue;
            }
            fired.add(hook.trigger());
            dispatch(hook, multiplier, hit, batch);
        }
    }

    /**
     * The effect seam. Task 9 implements {@code extra_damage} only; the other seven effect types are
     * Task 10's, and say so at DEBUG rather than silently doing nothing.
     *
     * <p>Exhaustive on purpose (no {@code default}): a ninth {@link EffectType} stops compiling here,
     * which is the reminder to give it an executor.
     *
     * @param multiplier this slot's diminishing multiplier; it scales <b>quantities only</b>
     *                   (spec §5.2) — never whether the effect runs
     */
    private static void dispatch(EffectHook hook, double multiplier, Hit hit, DamageBatch batch) {
        switch (hook.type()) {
            case EXTRA_DAMAGE -> {
                EffectHook.ExtraDamage extra = (EffectHook.ExtraDamage) hook;
                batch.add(HOOK_DAMAGE, (float) (extra.amount() * multiplier));
            }
            case APPLY_EFFECT, LIFESTEAL, GAIN_CHARGE, AREA_CHARGE, SPLASH, STACK, DETONATE ->
                    Astrum.LOGGER.debug("astrum: effect {} is not implemented yet (Task 10); hook on {} skipped",
                            hook.type(), hit.target().getName().getString());
        }
    }

    /**
     * {@return whether this damage sequence can be the trigger a hook waits for}
     *
     * <p>Only the triggers a landed damage event can observe are implemented. The rest belong to
     * other sources — {@code kill} to the death path, {@code block} to the shield path, {@code
     * combo_n}/{@code stack_full} to counters Task 10 keeps — and answering {@code false} is the
     * honest answer here rather than pretending they fired.
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
