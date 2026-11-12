package com.astrum.pcrd.impl.combat;

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
     * @param target the entity to damage (never the entity the current hit already targets)
     * @param type   which damage type to deal
     * @param amount how much
     */
    void add(LivingEntity target, ResourceLocation type, float amount);
}
