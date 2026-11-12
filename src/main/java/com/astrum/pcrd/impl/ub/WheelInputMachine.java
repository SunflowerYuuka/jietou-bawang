package com.astrum.pcrd.impl.ub;

import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/**
 * The press / hold / release machine behind <b>both</b> UB keys, as a pure state machine (spec §5.5,
 * 2026-11-12: 拆成两个按键 — a cast key and a preset wheel key).
 *
 * <h2>Why one class with a purpose, and not two machines</h2>
 * The two keys share every timing rule exactly — press arms the gesture, the decision is made on a
 * later poll or on the release, a hold never fires on its first frame, a hold released without a poll
 * closes instead of firing — and differ in exactly two places: <em>what the hold opens</em> and
 * <em>what a digit does</em>. Writing that twice would duplicate the part that is easy to get wrong
 * (the release-time decision) and leave the duplicate untested the day someone edits one copy. So the
 * timing lives once and the purpose is a constructor parameter; two <em>instances</em> are still needed
 * and are kept by the client, because the two keys have independent press and release lifecycles — one
 * state field shared by both would let the wheel key's release settle the cast key's gesture.
 *
 * <table border="1">
 *   <caption>What each purpose turns the same gesture into</caption>
 *   <tr><th>input</th><th>{@link Purpose#CAST}</th><th>{@link Purpose#PRESET}</th></tr>
 *   <tr><td>press, then release &lt; 200 ms</td><td>{@link Decision#TAP_CAST}</td>
 *       <td>{@link Decision#NONE} — this key only serves a hold</td></tr>
 *   <tr><td>poll / release at ≥ 200 ms</td><td>{@link Decision#OPEN_CAST_PAD}</td>
 *       <td>{@link Decision#OPEN_WHEEL}</td></tr>
 *   <tr><td>digit {@code 1}–{@code 5} while active</td><td>{@link Decision#CAST_SLOT}</td>
 *       <td>{@link Decision#SET_PRESET} — never casts</td></tr>
 *   <tr><td>release while active</td><td>{@link Decision#CLOSE}</td><td>{@link Decision#CLOSE}</td></tr>
 * </table>
 *
 * <h2>States</h2>
 * <pre>
 *   IDLE ──onPress──▶ PENDING ──poll(t≥t0+200)──▶ ACTIVE ──onDigit(n)──▶ IDLE  (CAST_SLOT / SET_PRESET)
 *     ▲                  │                          │
 *     └──onRelease────◀──┘                          └──onRelease──▶ IDLE     (CLOSE)
 *        (CAST: TAP_CAST if &lt;200 ms — PRESET: NONE; CLOSE if ≥200 ms)
 * </pre>
 *
 * <h2>Two rules that carry the whole contract</h2>
 * <ul>
 *   <li><b>Not on the press frame.</b> {@link #onPress(long)} only arms the machine. The decision is
 *       made on a later {@link #poll(long)} or {@link #onRelease(long)}, so pressing a key never by
 *       itself casts, opens anything or changes the preset.</li>
 *   <li><b>The two meanings never cross.</b> A hold on the cast key answers {@code OPEN_CAST_PAD} and
 *       can only ever end in {@code CAST_SLOT} or {@code TAP_CAST}; a hold on the wheel key answers
 *       {@code OPEN_WHEEL} and can only end in {@code SET_PRESET}. A caller cannot mistake one for the
 *       other: there is no decision that means both.</li>
 * </ul>
 *
 * <p>The machine holds no Minecraft types, no clock and no screen: the caller passes
 * {@code System.nanoTime()} (or any monotonic millisecond source) and acts on the returned
 * {@link Decision}. {@link #capturesHotbarDigits()} is the seam that tells the caller to swallow the
 * hotbar {@code 1}–{@code 5} keys while <em>either</em> overlay is up, rather than letting them change
 * the selected slot.
 *
 * <p>Instances are single-threaded: they are driven from the client's input handling only.
 */
@ApiStatus.Internal
public final class WheelInputMachine {

    /** Press-to-hold boundary in milliseconds: at or past this, the gesture becomes an overlay. */
    public static final long HOLD_THRESHOLD_MILLIS = 200L;

    /** Which key this machine serves, i.e. what its hold and its digits mean. */
    public enum Purpose {
        /**
         * The cast key ({@code key.astrum.ub_wheel}, default {@code V}): tap casts the preset, hold
         * raises the slot pad, a digit casts that slot at once.
         */
        CAST,
        /**
         * The preset wheel key ({@code key.astrum.ub_preset_wheel}, default {@code B}): tap does
         * nothing, hold opens the ring, a digit makes that slot the preset and never casts.
         */
        PRESET
    }

    /** Where the gesture currently is. */
    public enum State {
        /** No key is held. */
        IDLE,
        /** The key is down but the hold threshold has not been reached yet. */
        PENDING,
        /** The hold threshold passed and this purpose's overlay is up (pad or wheel). */
        ACTIVE
    }

    /** What the caller should do in response to an input call. */
    public enum Decision {
        /** Nothing to do. */
        NONE,
        /** {@link Purpose#CAST}: the threshold passed, so show the slot pad. */
        OPEN_CAST_PAD,
        /** {@link Purpose#PRESET}: the threshold passed, so show the ring wheel. */
        OPEN_WHEEL,
        /** {@link Purpose#CAST}: released before the threshold, so cast the current preset. */
        TAP_CAST,
        /** The overlay was up and is now dismissed without a pick. */
        CLOSE,
        /** {@link Purpose#CAST} digit: cast this slot (see {@link #pickedSlot()}), then close. */
        CAST_SLOT,
        /** {@link Purpose#PRESET} digit: make this slot the preset (see {@link #pickedSlot()}), then
         * close. Deliberately distinct from {@link #CAST_SLOT}: this one must never cast. */
        SET_PRESET
    }

    private static final int NO_SLOT = -1;

    private final Purpose purpose;

    private State state = State.IDLE;
    private long pressMillis;
    private Decision lastDecision = Decision.NONE;
    private int pickedSlot = NO_SLOT;

    /**
     * @param purpose which key this instance serves; fixed for its lifetime, because a gesture that
     *                began as a cast must not become a preset change at release
     */
    public WheelInputMachine(Purpose purpose) {
        this.purpose = purpose;
    }

    /**
     * Arm the gesture. Neither casts nor opens anything by itself: the decision waits for
     * {@link #poll(long)} or {@link #onRelease(long)}.
     *
     * <p>Ignored while the overlay is up, and also while the gesture is still pending: a key-repeat
     * event must not restart the hold clock, or a key whose repeat begins before the threshold would
     * never become a hold. Key repeats only start after {@code Minecraft}'s own repeat delay, so this
     * is a guard rather than a behaviour the player can feel.
     */
    public void onPress(long nowMillis) {
        if (state != State.IDLE) {
            return;
        }
        state = State.PENDING;
        pressMillis = nowMillis;
        pickedSlot = NO_SLOT;
    }

    /**
     * Advance the clock while the key is held.
     *
     * @return this purpose's "the hold happened" decision exactly once, on the first poll at or past
     *         the threshold — {@link Decision#OPEN_CAST_PAD} for {@link Purpose#CAST},
     *         {@link Decision#OPEN_WHEEL} for {@link Purpose#PRESET} — and {@link Decision#NONE}
     *         otherwise
     */
    public Decision poll(long nowMillis) {
        if (state != State.PENDING) {
            return Decision.NONE;
        }
        if (nowMillis - pressMillis < HOLD_THRESHOLD_MILLIS) {
            return Decision.NONE;
        }
        state = State.ACTIVE;
        return record(purpose == Purpose.CAST ? Decision.OPEN_CAST_PAD : Decision.OPEN_WHEEL);
    }

    /**
     * Release the key and settle the gesture.
     *
     * @return {@link Decision#TAP_CAST} if released before the threshold on the cast key,
     *         {@link Decision#NONE} if released before the threshold on the wheel key (its tap has no
     *         meaning), {@link Decision#CLOSE} if the gesture had become an overlay (or had been held
     *         past the threshold but was never polled open), {@link Decision#NONE} for a stray release
     *         while nothing is held
     */
    public Decision onRelease(long nowMillis) {
        switch (state) {
            case PENDING:
                boolean heldLongEnough = nowMillis - pressMillis >= HOLD_THRESHOLD_MILLIS;
                state = State.IDLE;
                pickedSlot = NO_SLOT;
                if (heldLongEnough) {
                    return record(Decision.CLOSE);
                }
                return record(purpose == Purpose.CAST ? Decision.TAP_CAST : Decision.NONE);
            case ACTIVE:
                state = State.IDLE;
                pickedSlot = NO_SLOT;
                return record(Decision.CLOSE);
            case IDLE:
            default:
                return Decision.NONE;
        }
    }

    /**
     * A hotbar digit, valid only while this purpose's overlay is up.
     *
     * @param slot1to5 the slot the player picked, {@code 1}–{@code 5}
     * @return {@link Decision#CAST_SLOT} on a cast-key pick, {@link Decision#SET_PRESET} on a
     *         wheel-key pick (both with the slot in {@link #pickedSlot()}), {@link Decision#NONE} when
     *         no overlay of this purpose is up
     */
    public Decision onDigit(int slot1to5) {
        if (state != State.ACTIVE) {
            return Decision.NONE;
        }
        state = State.IDLE;
        pickedSlot = slot1to5;
        return record(purpose == Purpose.CAST ? Decision.CAST_SLOT : Decision.SET_PRESET);
    }

    /** @return which key this machine serves */
    public Purpose purpose() {
        return purpose;
    }

    /** @return the current state */
    public State state() {
        return state;
    }

    /** @return the last decision actually taken, {@link Decision#NONE} before the first one */
    public Decision lastDecision() {
        return lastDecision;
    }

    /**
     * {@return the slot of the last {@link Decision#CAST_SLOT} / {@link Decision#SET_PRESET}, empty
     * otherwise}
     *
     * <p>It is called "picked" rather than "cast" because the same field carries the slot of a preset
     * change, and calling that a cast is how the two keys would drift back into meaning the same thing.
     */
    public Optional<Integer> pickedSlot() {
        return pickedSlot == NO_SLOT ? Optional.empty() : Optional.of(pickedSlot);
    }

    /**
     * {@return {@code true} only while this purpose's overlay is up}
     *
     * <p>The caller uses this to block the hotbar {@code 1}–{@code 5} keys, which would otherwise
     * change the selected slot behind the overlay. Both purposes answer {@code true} in
     * {@link State#ACTIVE}: the pad and the wheel each swallow the digits, for different reasons.
     */
    public boolean capturesHotbarDigits() {
        return state == State.ACTIVE;
    }

    private Decision record(Decision decision) {
        lastDecision = decision;
        return decision;
    }
}
