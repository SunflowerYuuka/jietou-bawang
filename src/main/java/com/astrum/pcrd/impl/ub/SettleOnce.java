package com.astrum.pcrd.impl.ub;

import org.jetbrains.annotations.ApiStatus;

/**
 * "This ultimate settles its damage exactly once" — the one-shot latch of a single cast.
 *
 * <h2>Why a latch and not a boolean on the entity</h2>
 * A settlement is armed in <b>two</b> places, because both of them are real ways a showcase ends:
 *
 * <ol>
 *   <li><b>The strike frame.</b> The clip's own length says when it reads as having landed; that is
 *       the primary moment, and it is what makes the damage line up with the swing.</li>
 *   <li><b>The removal.</b> A clip can be cut short — the owner logs out, the server stops, the chunk
 *       unloads, or the hard deadline fires — and an interrupted clip has no strike frame. Settling
 *       only at the strike frame would <b>lose</b> the ultimate's whole output in those cases, which is
 *       the failure spec §5.6 warns about ("动画被打断就丢伤害").</li>
 * </ol>
 *
 * <p>Both moments call {@link #claim()}; exactly one wins. The thing that was between them (a bare
 * {@code boolean}) is this class, because "settles once" is a property that a log cannot distinguish
 * from a correct multi-hit: a double settlement reads as five hits instead of four, and nothing throws,
 * warns, or looks wrong. It is a pure state machine so the four transitions below are asserted rather
 * than trusted ({@code SettleOnceTest}).
 *
 * <h2>Three states, not two</h2>
 * <ul>
 *   <li><b>pending</b> — armed, nothing settled yet; the fallback is still welcome.</li>
 *   <li><b>claimed</b> — one caller is running (or has finished) the settlement.</li>
 * </ul>
 * The "running" distinction matters because the settlement applies real {@code hurt()} calls, and a
 * damage listener may run game code that reaches the same cast again. A claim made while a run is in
 * progress must be refused — otherwise the damage sequence would feed itself.
 *
 * <h2>A failed run still counts as spent</h2>
 * There is deliberately no {@code release()}. Whatever landed before an exception is on the target's
 * health already, so re-running the plan would double it; a partially applied ultimate is a bug worth
 * logging, not a bug worth completing. {@link #done()} is idempotent, and a throwing caller still calls
 * it from a {@code finally} block.
 *
 * <p>Not thread-safe and does not need to be: every call comes from the server thread's own tick or
 * from the cast path.
 */
@ApiStatus.Internal
public final class SettleOnce {

    /** Whether the single settlement has been taken. */
    private boolean claimed;

    /** {@return whether the settlement has not been taken yet} */
    public boolean isPending() {
        return !claimed;
    }

    /**
     * {@return {@code true} for the one caller that may settle, {@code false} for everyone else}
     *
     * <p>Consuming rather than reading: the first caller takes it, every later one is told no. A
     * re-entrant claim (one made while the winning run is still in progress) is also refused, which is
     * what keeps the plan from applying itself recursively.
     */
    public boolean claim() {
        if (claimed) {
            return false;
        }
        claimed = true;
        return true;
    }

    /**
     * Marks the settlement finished. Idempotent, and required after a run that threw.
     *
     * <p>It changes nothing about {@link #claim()} — the latch has already been taken by then — and
     * exists so the caller's {@code finally} has something honest to call, and so a future edit that
     * adds a "was it actually applied" diagnostic has one place to record it.
     */
    public void done() {
        claimed = true;
    }
}
