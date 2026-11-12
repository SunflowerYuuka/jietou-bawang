package com.astrum.pcrd.impl.ub;

import java.util.Optional;

import org.jetbrains.annotations.ApiStatus;

/**
 * The press / hold / release machine behind the UB cast key, as a pure state machine.
 *
 * <h2>Why this is not inlined in the key listener</h2>
 * The only interesting part of the input path is <em>when</em> a gesture becomes a tap cast, an open
 * wheel, or a slot pick — and that decision is untestable inside a listener that needs a client, a
 * screen and a window. As a plain object driven by a caller-supplied clock it is exercised directly,
 * including the rule that is easy to get wrong: a long hold must never cast.
 *
 * <h2>States</h2>
 * <pre>
 *   IDLE ──onPress──▶ PENDING ──poll(t≥t0+200)──▶ WHEEL ──onDigit(n)──▶ IDLE  (CAST_SLOT)
 *     ▲                  │                          │
 *     └──onRelease·───◀──┘                          └──onRelease──▶ IDLE     (CLOSE)
 *        (TAP_CAST if &lt;200 ms, CLOSE if ≥200 ms)
 * </pre>
 *
 * <h2>Two rules that carry the whole contract</h2>
 * <ul>
 *   <li><b>Not on the press frame.</b> {@link #onPress(long)} only arms the machine. The decision is
 *       made on a later {@link #poll(long)} or {@link #onRelease(long)}, so pressing the key never by
 *       itself casts or opens anything.</li>
 *   <li><b>A hold never casts.</b> Release before {@link #HOLD_THRESHOLD_MILLIS} is a
 *       {@link Decision#TAP_CAST}; release at or after it is a {@link Decision#CLOSE}, whether or not
 *       a {@code poll} got the chance to announce the wheel in between. There is no timing window in
 *       which holding the key fires a cast.</li>
 * </ul>
 *
 * <p>The machine holds no Minecraft types, no clock and no screen: the caller passes
 * {@code System.currentTimeMillis()} (or any monotonic millisecond source) and acts on the returned
 * {@link Decision}. {@link #capturesHotbarDigits()} is the seam that tells the caller to swallow the
 * hotbar {@code 1}–{@code 5} keys while the wheel is up, rather than letting them change the
 * selected slot.
 *
 * <p>Instances are single-threaded: they are driven from the client's input handling only.
 */
@ApiStatus.Internal
public final class WheelInputMachine {

    /** Press-to-hold boundary in milliseconds: at or past this, the gesture becomes a wheel. */
    public static final long HOLD_THRESHOLD_MILLIS = 200L;

    /** Where the gesture currently is. */
    public enum State {
        /** No key is held. */
        IDLE,
        /** The key is down but the hold threshold has not been reached yet. */
        PENDING,
        /** The hold threshold passed and the wheel is up. */
        WHEEL
    }

    /** What the caller should do in response to an input call. */
    public enum Decision {
        /** Nothing to do. */
        NONE,
        /** The hold threshold passed: show the wheel. */
        OPEN_WHEEL,
        /** Released before the threshold: cast the default slot. */
        TAP_CAST,
        /** The wheel was up and is now dismissed without a pick. */
        CLOSE,
        /** A digit was pressed inside the wheel: cast that slot, see {@link #castSlot()}. */
        CAST_SLOT
    }

    private static final int NO_SLOT = -1;

    private State state = State.IDLE;
    private long pressMillis;
    private Decision lastDecision = Decision.NONE;
    private int castSlot = NO_SLOT;

    /**
     * Arm the gesture. Neither casts nor opens anything by itself: the decision waits for
     * {@link #poll(long)} or {@link #onRelease(long)}.
     *
     * <p>Ignored while the wheel is already up, so a repeated press event cannot dismiss a wheel the
     * player is currently picking from.
     */
    public void onPress(long nowMillis) {
        if (state == State.WHEEL) {
            return;
        }
        state = State.PENDING;
        pressMillis = nowMillis;
        castSlot = NO_SLOT;
    }

    /**
     * Advance the clock while the key is held.
     *
     * @return {@link Decision#OPEN_WHEEL} exactly once, on the first poll at or past the threshold;
     *         {@link Decision#NONE} otherwise
     */
    public Decision poll(long nowMillis) {
        if (state != State.PENDING) {
            return Decision.NONE;
        }
        if (nowMillis - pressMillis < HOLD_THRESHOLD_MILLIS) {
            return Decision.NONE;
        }
        state = State.WHEEL;
        return record(Decision.OPEN_WHEEL);
    }

    /**
     * Release the key and settle the gesture.
     *
     * @return {@link Decision#TAP_CAST} if released before the threshold, {@link Decision#CLOSE} if
     *         the gesture had become a wheel (or had been held past the threshold but was never
     *         polled open), {@link Decision#NONE} for a stray release while nothing is held
     */
    public Decision onRelease(long nowMillis) {
        switch (state) {
            case PENDING:
                boolean heldLongEnough = nowMillis - pressMillis >= HOLD_THRESHOLD_MILLIS;
                state = State.IDLE;
                castSlot = NO_SLOT;
                return record(heldLongEnough ? Decision.CLOSE : Decision.TAP_CAST);
            case WHEEL:
                state = State.IDLE;
                castSlot = NO_SLOT;
                return record(Decision.CLOSE);
            case IDLE:
            default:
                return Decision.NONE;
        }
    }

    /**
     * A hotbar digit, valid only while the wheel is up.
     *
     * @param slot1to5 the slot the player picked, {@code 1}–{@code 5}
     * @return {@link Decision#CAST_SLOT} on a pick (the picked slot is then in {@link #castSlot()}),
     *         {@link Decision#NONE} when no wheel is up
     */
    public Decision onDigit(int slot1to5) {
        if (state != State.WHEEL) {
            return Decision.NONE;
        }
        state = State.IDLE;
        castSlot = slot1to5;
        return record(Decision.CAST_SLOT);
    }

    /** @return the current state */
    public State state() {
        return state;
    }

    /** @return the last decision actually taken, {@link Decision#NONE} before the first one */
    public Decision lastDecision() {
        return lastDecision;
    }

    /** @return the slot of the last {@link Decision#CAST_SLOT}, empty otherwise */
    public Optional<Integer> castSlot() {
        return castSlot == NO_SLOT ? Optional.empty() : Optional.of(castSlot);
    }

    /**
     * {@return {@code true} only while the wheel is up}
     *
     * <p>The caller uses this to block the hotbar {@code 1}–{@code 5} keys, which would otherwise
     * change the selected slot behind the wheel.
     */
    public boolean capturesHotbarDigits() {
        return state == State.WHEEL;
    }

    private Decision record(Decision decision) {
        lastDecision = decision;
        return decision;
    }
}
