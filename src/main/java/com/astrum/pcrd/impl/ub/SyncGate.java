package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.attach.UbCooldown;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
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
 *   <li><b>Only on change.</b> An unchanged {@code (charge, deadlines)} pair produces no traffic, no
 *       matter how many windows pass. Re-sending an identical state is pure waste: the client already
 *       holds it.</li>
 *   <li><b>At most one push per 5-tick window.</b> Charge changes on every tick while the trickle
 *       runs, so "on change" alone would still be twenty packets a second. Pushes are therefore
 *       aligned to ticks divisible by {@link #INTERVAL_TICKS}; a change that misses a window is still
 *       pending at the next one, because {@code lastSentCharge} only advances when a push actually
 *       happens.</li>
 * </ul>
 *
 * <h2>What counts as a change, now that cooldowns are per character</h2>
 * The compared value is the whole {@code Map<ResourceLocation, UbCooldown>} — one entry per character
 * still cooling. Three consequences, all of them intended:
 *
 * <ul>
 *   <li><b>Arming a cooldown is a change.</b> The deadline map grows, the frame goes out, the client
 *       starts its shade on the same tick.</li>
 *   <li><b>Every cooldown expiring is a change.</b> The dead entries are dropped on send, so the map
 *       shrinks and the client is told that she is castable. Without this the shade would have to fade
 *       on the client's own clock — which is fine for the fade, but the client also needs to know the
 *       entry is gone so a <em>stale</em> deadline cannot outlive a reconnect.</li>
 *   <li><b>The map changing shape without the charge changing is still a change.</b> A player whose bar
 *       is full and who casts character A twice in a row (refill in between) must have A's second,
 *       later deadline pushed even though the charge reads the same {@code 100.0} at both windows.</li>
 * </ul>
 *
 * <p>These are pure inputs: this class does not remember anything. The caller owns the per-connection
 * "what has this client been told" values, and passes {@code lastSyncTick = -1},
 * {@code lastSentCharge = Float.NaN}, {@code lastSentDeadlines = null} before the first push.
 * {@code NaN} is the natural "nothing yet" charge because it compares unequal to every real value, and
 * {@code null} does the same for the map — so a fresh connection is always sent the current state
 * exactly once.
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
     * @param tick              the player's tick counter, e.g. {@code ServerPlayer.tickCount}
     * @param lastSyncTick      the tick of the last push, or {@code -1} if there has never been one
     * @param charge            the gauge's current charge
     * @param deadlines         the per-character cooldowns still running, as they will be sent
     * @param lastSentCharge    the charge carried by the last push, or {@link Float#NaN} for none
     * @param lastSentDeadlines the deadlines carried by the last push, or {@code null} for none
     */
    public static boolean shouldSync(int tick, int lastSyncTick, float charge,
            Map<ResourceLocation, UbCooldown> deadlines, float lastSentCharge,
            Map<ResourceLocation, UbCooldown> lastSentDeadlines) {
        if (tick == lastSyncTick) {
            // One push per tick at most, even if two mutations happened inside it: they are one
            // visible state, and the second call is exactly what a careless extra listener does.
            return false;
        }
        if (tick % INTERVAL_TICKS != 0) {
            return false;
        }
        // Map.equals compares entry sets, so it is the right comparison for a map whose iteration order
        // is not meaningful. A null map is the "this client has never been told" sentinel and is never
        // equal to a real one, including an empty one — a fresh connection must learn that there is
        // nothing cooling, not stay at its default.
        if (lastSentDeadlines == null) {
            return true;
        }
        return charge != lastSentCharge || !deadlines.equals(lastSentDeadlines);
    }
}
