package com.astrum.pcrd.impl.ub;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The digit→slot conversion, one assertion per digit.
 *
 * <h2>Why every case is spelled out instead of looped over a formula</h2>
 * The bug this class exists for was an <b>off-by-one</b>: the wheel's {@code 1}–{@code 5} digits went
 * straight to a 0-based slot index, so {@code 1} cast the second segment and {@code 5} was dropped by
 * the range check (spec §5.5: 禁止静默失败). A test written as
 * {@code assertEquals(digit - 1, slotOf(digit))} would have re-hidden exactly that: it moves with any
 * future slip of the same kind. So the mapping is written out: {@code 1→0}, {@code 2→1}, …,
 * {@code 5→4}, and the three ways to be out of range are separate assertions.
 */
class WheelSlotsTest {

    @Test
    void digitOneIsTheFirstSegment() {
        assertEquals(0, slotOf(1));
    }

    @Test
    void digitTwoIsTheSecondSegment() {
        assertEquals(1, slotOf(2));
    }

    @Test
    void digitThreeIsTheThirdSegment() {
        assertEquals(2, slotOf(3));
    }

    @Test
    void digitFourIsTheFourthSegment() {
        assertEquals(3, slotOf(4));
    }

    @Test
    void digitFiveIsTheFifthSegment() {
        assertEquals(4, slotOf(5));
    }

    @Test
    void zeroIsNotAWheelDigit() {
        assertTrue(WheelSlots.slotForDigit(0).isEmpty(),
                "the hotbar's 'no digit' value must not silently mean slot 0");
    }

    @Test
    void sixIsPastTheLastSegment() {
        assertTrue(WheelSlots.slotForDigit(6).isEmpty(),
                "a sixth slot does not exist; the caller must not send it");
    }

    @Test
    void negativeDigitsHaveNoSlot() {
        assertTrue(WheelSlots.slotForDigit(-1).isEmpty());
        assertTrue(WheelSlots.slotForDigit(-5).isEmpty());
        assertTrue(WheelSlots.slotForDigit(Integer.MIN_VALUE).isEmpty());
    }

    private static int slotOf(int digit) {
        OptionalInt slot = WheelSlots.slotForDigit(digit);
        assertTrue(slot.isPresent(), "digit " + digit + " must map to a slot");
        return slot.getAsInt();
    }
}
