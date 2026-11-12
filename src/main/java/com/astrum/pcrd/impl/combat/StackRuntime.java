package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.config.ServerConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * The attachment read/write layer of the stack system (spec §4.4).
 *
 * <h2>Everything runtime lives here, and nothing else</h2>
 * The {@link StackTable} attached to the target is the state; {@link StackKernel} is the arithmetic;
 * this class is the only place the two meet a {@link LivingEntity}. Keeping it that narrow is what
 * lets the expiry rule be unit-tested (§4.4's lazy expiry) while the attachment plumbing stays four
 * lines long.
 *
 * <h2>No tick, no sweep, no static state</h2>
 * The state lives in an attachment on the entity, so it dies with the entity and is never reachable
 * from a static field (the guide's R8). Expiry is evaluated only inside these methods, i.e. only when
 * an attacker's hit reaches this class — there is no {@code EntityTickEvent} handler, no
 * {@code ServerTickEvent} handler that walks entities, and no {@code Level.getDataStorage()}
 * (spec §4.4, §7).
 *
 * <h2>The ttl is read at the point of use</h2>
 * {@link #ttlTicks()} reads {@code ServerConfig} on every call rather than caching it in a constant
 * (spec §8.4 guardrail 1). A cached copy read before the config loads would freeze the default for the
 * lifetime of the process, and a cached copy read once would ignore an operator's edit.
 */
@ApiStatus.Internal
public final class StackRuntime {

    private StackRuntime() {
    }

    /**
     * {@return the stack table of {@code target}, created on first use}
     *
     * <p>The attachment is {@code astrum:stack}, registered with {@code serialize = false}: this state
     * is runtime-only by design (§4.3) and must never reach a save file, so an entity that unloads
     * simply forgets its stacks. That is at least as strict as expiry and needs no cleanup path.
     */
    public static StackTable tableOf(LivingEntity target) {
        return target.getData(Attachments.STACK);
    }

    /** {@return the live stacks {@code attacker} holds on {@code target}, expired ones reading as 0} */
    public static StackState read(LivingEntity target, ServerPlayer attacker, ResourceLocation stackId) {
        return tableOf(target).read(key(attacker, stackId), nowTick(target), ttlTicks());
    }

    /**
     * Adds {@code delta} stacks and returns the new state.
     *
     * <p>{@code max} comes from the hook and is passed through untouched: spec §5.2 forbids the
     * diminishing multiplier from changing the cap, so this method has no multiplier parameter at all.
     */
    public static StackState add(LivingEntity target, ServerPlayer attacker, ResourceLocation stackId,
            int delta, int max) {
        return tableOf(target).hit(key(attacker, stackId), nowTick(target), delta, max, ttlTicks());
    }

    /**
     * {@return the stacks that were held, having consumed them}
     *
     * <p>Used by {@code detonate}. Consumption is not scaled: a rank-5 slot detonates the same stack
     * for less damage (§5.2), it does not detonate a smaller stack.
     */
    public static int consume(LivingEntity target, ServerPlayer attacker, ResourceLocation stackId) {
        return tableOf(target).drain(key(attacker, stackId), nowTick(target), ttlTicks());
    }

    /** {@return the configured stack lifetime, in ticks, read fresh from the server config} */
    public static int ttlTicks() {
        return ServerConfig.BALANCE.stackTtlTicks.get();
    }

    private static StackKey key(ServerPlayer attacker, ResourceLocation stackId) {
        return new StackKey(attacker.getUUID(), stackId);
    }

    /** {@return the holder's game time, the only clock stack expiry uses} */
    private static long nowTick(LivingEntity target) {
        return target.level().getGameTime();
    }
}
