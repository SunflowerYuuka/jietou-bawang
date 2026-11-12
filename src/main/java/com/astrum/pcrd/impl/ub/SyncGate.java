package com.astrum.pcrd.impl.ub;

import org.jetbrains.annotations.ApiStatus;

/**
 * The "should the server push the gauge to this client now" verdict, as a pure function.
 *
 * <h2>Why this is not inlined in the tick listener</h2>
 * It is the only interesting decision in the whole sync path, and a listener body cannot be tested
 * without a server, a player and a payload. As a static method it is exercised directly, including
 * the measurement that matters: twenty ticks of a changing gauge must cost four packets, not twenty
 * (spec §4.3 splits the attachments precisely because a per-tick push would re-send the state 20×
 * per second per player).
 *
 * <h2>Two rules, both required</h2>
 * <ul>
 *   <li><b>Only on change.</b> An unchanged {@code (charge, cdEndTick)} pair produces no traffic, no
 *       matter how many windows pass. Re-sending an identical state is pure waste: the client already
 *       holds it.</li>
 *   <li><b>At most one push per 5-tick window.</b> Charge changes on every tick while the trickle
 *       runs, so "on change" alone would still be twenty packets a second. Pushes are therefore
 *       aligned to ticks divisible by {@link #INTERVAL_TICKS}; a change that misses a window is still
 *       pending at the next one, because {@code lastSentCharge} only advances when a push actually
 *       happens.</li>
 * </ul>
 *
 * <p>These are pure inputs: this class does not remember anything. The caller owns the per-connection
 * "what has this client been told" values, and passes {@code lastSyncTick = -1},
 * {@code lastSentCharge = Float.NaN}, {@code lastSentCdEndTick = Long.MIN_VALUE} before the first
 * push. {@code NaN} is the natural "nothing yet" charge because it compares unequal to every real
 * value, so a fresh connection is always sent the current gauge exactly once.
 */
@ApiStatus.Internal
public final class SyncGate {

    /** One push per this many ticks: four per second, the ceiling spec §4.3 allows. */
    public static final int INTERVAL_TICKS = 5;

    private SyncGate() {
    }

    /**
     * {@return {@code true} if the caller should send a charge payload this tick}
     *
     * @param tick             the player's tick counter, e.g. {@code ServerPlayer.tickCount}
     * @param lastSyncTick     the tick of the last push, or {@code -1} if there has never been one
     * @param charge           the gauge's current charge
     * @param cdEndTick        the gauge's current cooldown end
     * @param lastSentCharge   the charge carried by the last push, or {@link Float#NaN} for none
     * @param lastSentCdEndTick the cooldown end carried by the last push, or {@link Long#MIN_VALUE}
     */
    public static boolean shouldSync(int tick, int lastSyncTick, float charge, long cdEndTick,
            float lastSentCharge, long lastSentCdEndTick) {
        if (tick == lastSyncTick) {
            // One push per tick at most, even if two mutations happened inside it: they are one
            // visible state, and the second call is exactly what a careless extra listener does.
            return false;
        }
        if (tick % INTERVAL_TICKS != 0) {
            return false;
        }
        return charge != lastSentCharge || cdEndTick != lastSentCdEndTick;
    }
}
