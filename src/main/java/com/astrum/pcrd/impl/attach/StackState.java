package com.astrum.pcrd.impl.attach;

import org.jetbrains.annotations.ApiStatus;

/**
 * One target's stack count of one stack kind, as kept for one attacker (spec §4.4).
 *
 * <h2>What is in it, and what is deliberately not</h2>
 * A count and the tick of the last hit that changed it. There is no expiry timestamp, no timer, no
 * {@code shouldRemove} flag: expiry is <b>derived</b> from {@link #lastHitTick()} and the configured
 * ttl every time the entity is looked at ({@link StackKernel#isExpired}), which is what makes "zero
 * tick polling" (spec §4.4, performance red line) a property of the shape rather than a promise
 * about the code.
 *
 * <p>No {@code LivingEntity}, no {@code Level}, no attachment handle: this is a value a kernel can
 * be unit-tested on, the same purity rule {@code DamageBatch} and {@code PartyMultipliers} follow.
 *
 * @param stacks      how many stacks are held; {@code 0} is a legal, live state
 * @param lastHitTick the {@code Level.getGameTime()} of the last hit that touched this stack
 */
@ApiStatus.Internal
public record StackState(int stacks, long lastHitTick) {

    /**
     * "Nothing stacked", anchored so far in the past that every expiry test calls it expired.
     *
     * <p>{@link Long#MIN_VALUE} rather than {@code 0}: a state at tick 0 would look alive to a
     * {@link StackKernel#isExpired} call made early in a world's life.
     */
    public static final StackState EMPTY = new StackState(0, Long.MIN_VALUE);

    /** {@return {@code true} when no stack is held} */
    public boolean isEmpty() {
        return stacks <= 0;
    }
}
