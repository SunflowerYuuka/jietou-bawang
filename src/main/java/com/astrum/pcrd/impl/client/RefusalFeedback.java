package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.ub.CastGate;
import org.jetbrains.annotations.ApiStatus;

/**
 * How long a refusal is shown for, how hard the slot shakes, and what it sounds like — as arithmetic.
 *
 * <h2>Why this is its own class and not three constants inside the HUD</h2>
 * Spec §5.4 forbids silent failure, and "not silent" has three parts: something moves, something is
 * heard, and it is over before it becomes noise. Which pitch belongs to which reason, and whether a
 * refusal is still inside its window, are both pure questions; keeping them here means the audio/visual
 * answer is pinned by a test instead of by whoever last opened the client.
 *
 * <p>The pitches are deliberately ordered by "how close was the player to casting": an empty bar is
 * the lowest note, an active cooldown the next — the two refusals a player will actually hit — and
 * the bookkeeping failures (empty slot, unknown character, bad index) are the most muffled, because
 * they are the player's UI being out of date rather than a decision the game made against them.
 */
@ApiStatus.Internal
public final class RefusalFeedback {

    /** Ticks a refusal shakes and sounds for. Half a second at 20 tps: noticed, then gone. */
    public static final int WINDOW_TICKS = 10;

    /** Peak horizontal shake in GUI pixels. Small enough to read, large enough not to be missed. */
    public static final int SHAKE_PIXELS = 2;

    private RefusalFeedback() {
    }

    /**
     * {@return the sound pitch for a refusal}
     *
     * <p>Distinct per reason, so a player who knows the mod can tell "not charged" from "still on
     * cooldown" without looking, and so a test can prove the five cases are five cases.
     *
     * @param reason why the cast was refused
     */
    public static float pitchFor(CastGate.Reason reason) {
        return switch (reason) {
            case NOT_CHARGED -> 0.60f;
            case COOLDOWN -> 0.75f;
            case SLOT_EMPTY -> 0.45f;
            case CHARACTER_UNKNOWN -> 0.35f;
            case SLOT_OUT_OF_RANGE -> 0.30f;
        };
    }

    /**
     * {@return whether a refusal that happened at {@code refusalTick} is still inside its window}
     *
     * @param now         the client's current game time
     * @param refusalTick the game time the refusal arrived
     * @param windowTicks how long it is shown for, normally {@link #WINDOW_TICKS}
     */
    public static boolean isActive(long now, long refusalTick, int windowTicks) {
        long age = now - refusalTick;
        return age >= 0 && age <= windowTicks;
    }

    /**
     * {@return the horizontal offset to draw the slot at, for a shake of a given age}
     *
     * <p>A damped oscillation rather than a random jitter: the slot must move every frame and end
     * exactly where it started, or the HUD appears to drift.
     *
     * @param ageTicks ticks since the refusal arrived, {@code 0} at arrival
     * @param windowTicks the shake's full duration
     */
    public static int shakeOffset(long ageTicks, int windowTicks) {
        if (ageTicks < 0 || windowTicks <= 0 || ageTicks > windowTicks) {
            return 0;
        }
        double progress = (double) ageTicks / (double) windowTicks;
        double damping = 1.0 - progress;
        // A quarter turn per tick: left, centre, right, centre … and exactly zero on both edges.
        double phase = Math.sin(ageTicks * Math.PI / 2.0);
        return (int) Math.round(SHAKE_PIXELS * damping * phase);
    }
}
