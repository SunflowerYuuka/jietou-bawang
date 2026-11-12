package com.astrum.pcrd.impl.attach;

import org.jetbrains.annotations.ApiStatus;

/**
 * One player's {@code combo_n} chain: how many hits in a row, and when the last one landed (spec §4.4's
 * lazy-state pattern applied to the combo counter).
 *
 * <h2>Why this is a record with its own arithmetic, like {@link Roster} and {@link Party}</h2>
 * The chain has to survive across hits without any per-tick work, so it is a count plus the tick of the
 * last hit, and every question about it is answered by comparing two ticks at the moment it is read. No
 * timer is scheduled and nothing sweeps: an idle player costs exactly nothing, which is the same red
 * line the stack table obeys.
 *
 * <h2>The convention this encodes</h2>
 * Hits on <b>any</b> target extend the chain (a combo is about the player's rhythm, not one mob), the
 * chain lives while the gap to the previous hit is strictly under the window, and taking damage does
 * <b>not</b> break it. Both of those are choices — the brief allowed either — and they are recorded on
 * {@code TriggerFacts} where the payout rule lives.
 *
 * @param hits        consecutive hits in the current chain; {@code 0} means "no live chain"
 * @param lastHitTick the {@code Level.getGameTime()} of the last hit
 */
@ApiStatus.Internal
public record ComboState(int hits, long lastHitTick) {

    /** No chain at all, anchored before any real tick so it reads as stale. */
    public static final ComboState EMPTY = new ComboState(0, Long.MIN_VALUE);

    /**
     * {@return the chain after one more hit at {@code nowTick}}
     *
     * <p>A gap of {@code windowTicks} or more (or a tick that went backwards) starts a new chain at 1;
     * anything shorter continues it. A non-positive window therefore never accumulates, which is the
     * safe reading of a hand-edited config: "no window" cannot mean "infinite combo".
     *
     * @param nowTick     the tick of this hit
     * @param windowTicks how long a gap the chain survives
     */
    public ComboState noteHit(long nowTick, int windowTicks) {
        int live = hitsAt(nowTick, windowTicks);
        int next = live <= 0 ? 1 : (live == Integer.MAX_VALUE ? live : live + 1);
        return new ComboState(next, nowTick);
    }

    /**
     * {@return the chain length that is still alive at {@code nowTick}, or {@code 0} when it has lapsed}
     *
     * <p>This is the read that makes the state lazy: the chain "breaks" when someone looks and the gap
     * is too large, not when a timer fires.
     *
     * @param nowTick     the current {@code Level.getGameTime()}
     * @param windowTicks how long a gap the chain survives
     */
    public int hitsAt(long nowTick, int windowTicks) {
        if (hits <= 0 || windowTicks <= 0) {
            return 0;
        }
        long age;
        try {
            age = Math.subtractExact(nowTick, lastHitTick);
        } catch (ArithmeticException overflow) {
            return 0;   // the sentinel tick, or a state from another timeline: not a live chain
        }
        return age < 0 || age >= windowTicks ? 0 : hits;
    }
}
