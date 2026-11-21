package com.astrum.pcrd.impl.combat;

import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where an effect that hits a <b>different</b> entity puts its damage — {@code splash} and
 * {@code detonate}'s radius.
 *
 * <h2>Why the executors cannot just call {@code hurt()}</h2>
 * They run inside {@code LivingDamageEvent.Post}. Task 9 measured what a {@code hurt()} nested there
 * does to a killing blow: {@code LivingDeathEvent} fired <b>twice</b> for one death, because
 * {@code die()} posts the event before its own {@code !isRemoved()} guard and the outer sequence then
 * resumed on a dead entity (report §4, variant A). Spec §5.1 rule 3 also wants "same tick" to mean one
 * {@code hurt()} per damage type per target.
 *
 * <p>So the executor states <em>what</em> should happen to whom, and {@link CombatEvents} merges it
 * into the end-of-tick flush exactly like its own attachment damage. Same mechanism, same fuse, same
 * "one hurt per type per target per tick" — and no nested damage sequence.
 *
 * <h2>The multiplier, and the defect it fixes</h2>
 * A splash's amount is <b>the same skill's damage</b> spread onto other bodies, so it must crit exactly as the
 * skill's primary hit does: 「本技能出现暴击时，造成的伤害值从 2 倍提升至 4 倍」 is a statement about the skill, not
 * about the one body it happens to land on first. Before this parameter existed, a secondary amount fell through
 * the sink untagged and crit at the caster's <b>global</b> {@code astrum:crit_damage}, so a 4× skill's splash was
 * a 1.5× crit — the finding {@code agent-30} reported.
 *
 * <p>The fourth argument is the hook's own value, and the {@link #tagged(OptionalDouble)} view is what fills it
 * in: {@code HookContext.forHook} hands the executor a sink already carrying the skill's multiplier, exactly the
 * way it hands it a {@code DamageBatch} tagged the same way. So the executor keeps calling
 * {@code ctx.otherTargets().add(other, type, damage)} with no extra parameter and no knowledge of the channel.
 */
@ApiStatus.Internal
@FunctionalInterface
public interface DamageToTarget {

    /**
     * Queues {@code amount} of {@code type} against {@code target}.
     *
     * <p>Non-positive amounts are the caller's to filter: an accumulator that silently drops numbers
     * would make the log disagree with the arithmetic.
     *
     * @param target         the entity to damage (never the entity the current hit already targets)
     * @param type           which damage type to deal
     * @param amount         how much
     * @param critMultiplier the skill's own crit multiplier for this amount, empty for the caster's global
     *                       {@code astrum:crit_damage}
     */
    void add(LivingEntity target, ResourceLocation type, float amount, OptionalDouble critMultiplier);

    /**
     * Queues an amount that crits at the caster's global multiplier — the shape every caller that has no
     * per-skill channel uses, and the one this interface had before the channel existed.
     *
     * @param target the entity to damage
     * @param type   which damage type to deal
     * @param amount how much
     */
    default void add(LivingEntity target, ResourceLocation type, float amount) {
        add(target, type, amount, OptionalDouble.empty());
    }

    /**
     * {@return a view of this sink that files every amount under {@code critMultiplier}}
     *
     * <h2>Why a view rather than a parameter on {@link #add(LivingEntity, ResourceLocation, float, OptionalDouble)}</h2>
     * The same reasoning {@code DamageBatch#tagged} documents: the effect executors author a splash and do not
     * know, and should not have to know, which crit multiplier the hook being executed carries. The hook is the
     * one thing the caller <em>does</em> know, so {@code HookContext.forHook} hands the executor a sink already
     * tagged. One executor signature unchanged, one place that knows the rule.
     *
     * <p>An empty tag answers {@code this}, so the common case — every hook that declares no per-skill crit —
     * allocates nothing. An amount that already carries its own value keeps it: the tag fills in an absence, it
     * does not overwrite a stated number.
     *
     * @param critMultiplier the skill's own multiplier; empty (or {@code null}) for the global
     */
    default DamageToTarget tagged(OptionalDouble critMultiplier) {
        OptionalDouble tag = critMultiplier == null ? OptionalDouble.empty() : critMultiplier;
        if (tag.isEmpty()) {
            return this;
        }
        return (target, type, amount, own) ->
                add(target, type, amount, own != null && own.isPresent() ? own : tag);
    }
}