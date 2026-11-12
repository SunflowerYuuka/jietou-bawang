package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.attach.Party;
import java.util.OptionalInt;
import org.jetbrains.annotations.ApiStatus;

/**
 * The wheel's two slot conventions, and the single place they are converted.
 *
 * <h2>Why this class exists at all</h2>
 * The wheel speaks two numbers for the same thing:
 *
 * <ul>
 *   <li><b>Digits, 1–5</b> — what the player presses. {@code WheelInputMachine.onDigit} keeps that
 *       convention (its name and its tests say {@code 1..5}), and so does
 *       {@code impl.client.WheelDigits}, which decides which raw key codes are wheel digits.</li>
 *   <li><b>Slots, 0–4</b> — the party index, which is also the wheel position and the number on the
 *       wire ({@code UbCastPayload}), and the only thing {@code UbService} and {@code Party} accept.
 *       Hover and tap-cast are 0-based already.</li>
 * </ul>
 *
 * <p>The first version of the client forgot the conversion: the digit went straight to the cast
 * request, so {@code 1} cast the <em>second</em> segment, {@code 4} the fifth, and {@code 5} was
 * refused by the sender's own range check — a legal press that produced nothing, which spec §5.5
 * forbids (禁止静默失败). It survived because the conversion lived in an untested client layer. So it
 * lives here instead: pure, no Minecraft types, and asserted per digit in {@code WheelSlotsTest}.
 *
 * <p>Callers must send only what {@link #slotForDigit} returns. An empty result is a
 * <b>programming error</b> — the caller produced a digit that is not a wheel digit — and must not be
 * turned into a silently dropped press.
 */
@ApiStatus.Internal
public final class WheelSlots {

    /** The digit that selects the first wheel segment. */
    public static final int FIRST_DIGIT = 1;

    /** The digit that selects the last wheel segment: one digit per segment, starting at one. */
    public static final int LAST_DIGIT = Party.SLOTS;

    private WheelSlots() {
    }

    /**
     * {@return the 0-based wheel slot for a pressed hotbar digit, or empty if it is not one}
     *
     * <p>{@code 1→0}, {@code 2→1}, {@code 3→2}, {@code 4→3}, {@code 5→4}. Anything else — {@code 0}
     * (the "not a digit" value the screen uses), {@code 6}–{@code 9} (hotbar keys with no wheel
     * meaning) and negatives — has no slot.
     *
     * @param digit the digit the player pressed, {@code 1}–{@code 5} for a real pick
     */
    public static OptionalInt slotForDigit(int digit) {
        if (digit < FIRST_DIGIT || digit > LAST_DIGIT) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(digit - FIRST_DIGIT);
    }
}
