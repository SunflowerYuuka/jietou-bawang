package com.astrum.pcrd.impl.combat;

import org.jetbrains.annotations.ApiStatus;

/**
 * The stack arithmetic of spec §4.4/§5.2 as three pure functions.
 *
 * <h2>The line this class is on the right side of</h2>
 * Spec §5.2 is explicit: diminishing returns apply to the <em>quantity</em> (damage, healing, charge,
 * duration) and <b>never</b> to the <em>mechanism</em> (whether a hook fires, the stack cap, whether
 * a detonation happens). So {@link #add} takes no multiplier at all — there is no parameter through
 * which a rank-5 slot could be given a smaller stack cap — and {@link #detonateDamage} multiplies the
 * damage while the detonation itself is decided by the executor.
 *
 * <h2>Why expiry is a comparison and not a timer</h2>
 * {@link #isExpired} answers from two ticks and a ttl. Nothing schedules, nothing sweeps, nothing
 * walks a world: the stack table is only ever consulted when the target is hit again (spec §4.4's
 * lazy expiry), so the cost of an idle stack is zero rather than one check per entity per tick.
 *
 * <h2>Hostile input</h2>
 * Every value here can arrive from a datapack or a hand-edited config, so non-positive caps, negative
 * deltas, {@code NaN} and infinities are all answered with a defined number instead of an exception
 * thrown in the middle of a damage sequence.
 */
@ApiStatus.Internal
public final class StackKernel {

    private StackKernel() {
    }

    /**
     * {@return the stack count after adding {@code delta}, clamped into {@code [0, max]}}
     *
     * <p>{@code max} is a cap, not a suggestion: a datapack may declare {@code max: 5} and a hit may
     * arrive with a delta that would overshoot it, and the stored count must never exceed what the
     * data author allowed. A negative {@code max} is treated as {@code 0} rather than as an error.
     *
     * @param state    the stack as last stored
     * @param nowTick  the tick of the hit being recorded; see {@link #hit}
     * @param delta    stacks to add; negative removes
     * @param max      the cap from the hook definition, never scaled (§5.2)
     */
    public static int add(StackState state, long nowTick, int delta, int max) {
        int cap = Math.max(0, max);
        long next = (long) state.stacks() + delta;   // long first: two ints cannot overflow into a cap check
        return (int) Math.clamp(next, 0L, cap);
    }

    /**
     * {@return the state after one hit: new count, and {@code nowTick} recorded}
     *
     * <p>The pair {@code hit} + {@link #add} exists so the count and the tick cannot drift apart: a
     * caller that computed the count itself could forget to re-anchor {@code lastHitTick}, and the
     * symptom would be a stack that expires early or never.
     *
     * @param state   the stack as last stored
     * @param nowTick the tick of this hit
     * @param delta   stacks to add; negative removes
     * @param max     the cap from the hook definition, never scaled (§5.2)
     */
    public static StackState hit(StackState state, long nowTick, int delta, int max) {
        return new StackState(add(state, nowTick, delta, max), nowTick);
    }

    /**
     * {@return {@code true} when the stack has aged out at {@code nowTick}}
     *
     * <p>Expired at exactly {@code ttlTicks} of age: {@code ttl=80} means "still alive at +79, gone at
     * +80", which is what the plan's test pins and what an author reads "80 ticks" to mean.
     *
     * <p>Two defensive answers: a state whose tick is <b>after</b> {@code nowTick} (a tick counter
     * that restarted, a state carried from another timeline) counts as expired instead of living
     * until the clock catches up, and a non-positive ttl counts as expired instead of meaning
     * "forever" — the second is a hand-edited {@code config/astrum-server.toml} writing {@code 0},
     * which the same guardrail as {@code Balance.clampBase} refuses to make game-breaking.
     *
     * @param state    the stack as last stored
     * @param nowTick  the current {@code Level.getGameTime()}
     * @param ttlTicks how long a stack lives without a new hit
     */
    public static boolean isExpired(StackState state, long nowTick, int ttlTicks) {
        if (ttlTicks <= 0) {
            return true;
        }
        long age;
        try {
            age = Math.subtractExact(nowTick, state.lastHitTick());
        } catch (ArithmeticException overflow) {
            // Only reachable for the sentinel tick ({@link StackState#EMPTY}) or a state from another
            // timeline; either way there is no meaningful age, so the stack is not alive.
            return true;
        }
        return age < 0 || age >= ttlTicks;
    }

    /**
     * {@return the damage one detonation deals: {@code stacks × damagePerStack × multiplier}}
     *
     * <p>The multiplier is the <b>only</b> place diminishing returns touch a detonation (spec §5.2:
     * a rank-5 character's detonation still detonates, just for less). This method therefore cannot
     * answer "no detonation" for a low multiplier — {@code 0.0F} here means "no damage", and the
     * empty detonation is still performed by the executor, which is a separate, un-scaled decision.
     *
     * <p>Non-positive and non-finite inputs answer {@code 0.0F}. That is not pedantry: a negative
     * result reaching {@code LivingEntity.hurt} would <em>heal</em> the target, and a {@code NaN}
     * would slip past a {@code amount <= 0} filter (every comparison with NaN is false) and reach the
     * damage pipeline.
     *
     * @param stacks         stacks consumed by the detonation
     * @param damagePerStack damage declared per consumed stack
     * @param multiplier     this slot's diminishing multiplier
     */
    public static float detonateDamage(int stacks, double damagePerStack, double multiplier) {
        if (stacks <= 0 || !(damagePerStack > 0.0) || !(multiplier > 0.0)) {
            return 0.0F;
        }
        double amount = stacks * damagePerStack * multiplier;
        if (!Double.isFinite(amount)) {
            return 0.0F;
        }
        return (float) amount;
    }
}
