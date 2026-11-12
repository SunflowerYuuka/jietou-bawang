package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only key rule the overlay intercepts, one assertion per physical key (spec §5.5).
 *
 * <p>While the wheel is up, {@code 1}–{@code 5} pick a segment and must not also switch the hotbar;
 * every other key passes through untouched. Both halves are asserted: the five that are wheel digits,
 * and the neighbours that must <b>not</b> be ({@code 0}, {@code 6}–{@code 9} — hotbar keys with no wheel
 * meaning — plus movement, the release key and {@code Esc}, which the overlay handles through its own
 * path rather than as a digit).
 */
class WheelDigitsTest {

    @Test
    void digitOneIsAWheelDigit() {
        assertEquals(1, WheelDigits.digitForKey(GLFW.GLFW_KEY_1));
    }

    @Test
    void digitTwoIsAWheelDigit() {
        assertEquals(2, WheelDigits.digitForKey(GLFW.GLFW_KEY_2));
    }

    @Test
    void digitThreeIsAWheelDigit() {
        assertEquals(3, WheelDigits.digitForKey(GLFW.GLFW_KEY_3));
    }

    @Test
    void digitFourIsAWheelDigit() {
        assertEquals(4, WheelDigits.digitForKey(GLFW.GLFW_KEY_4));
    }

    @Test
    void digitFiveIsAWheelDigit() {
        assertEquals(5, WheelDigits.digitForKey(GLFW.GLFW_KEY_5));
    }

    @Test
    void zeroIsNotAWheelDigit() {
        assertEquals(WheelDigits.NO_DIGIT, WheelDigits.digitForKey(GLFW.GLFW_KEY_0));
        assertFalse(WheelDigits.isWheelDigit(GLFW.GLFW_KEY_0));
    }

    @Test
    void sixToNineAreHotbarKeysWithNoWheelMeaning() {
        assertEquals(WheelDigits.NO_DIGIT, WheelDigits.digitForKey(GLFW.GLFW_KEY_6));
        assertEquals(WheelDigits.NO_DIGIT, WheelDigits.digitForKey(GLFW.GLFW_KEY_7));
        assertEquals(WheelDigits.NO_DIGIT, WheelDigits.digitForKey(GLFW.GLFW_KEY_8));
        assertEquals(WheelDigits.NO_DIGIT, WheelDigits.digitForKey(GLFW.GLFW_KEY_9));
    }

    @Test
    void movementAndEscapeAndTheReleaseKeyPassThrough() {
        for (int key : new int[] {GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
                GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_SPACE,
                GLFW.GLFW_KEY_LEFT_SHIFT, -1}) {
            assertEquals(WheelDigits.NO_DIGIT, WheelDigits.digitForKey(key),
                    "key " + key + " must not be swallowed as a wheel digit");
            assertFalse(WheelDigits.isWheelDigit(key));
        }
    }

    /** Exactly five keys are wheel digits: the window is closed on both sides, not just on one. */
    @Test
    void exactlyFiveKeyCodesAreWheelDigits() {
        int digits = 0;
        for (int key = 0; key < 512; key++) {
            if (WheelDigits.isWheelDigit(key)) {
                digits++;
                assertTrue(key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_5,
                        "unexpected wheel digit at key code " + key);
            }
        }
        assertEquals(5, digits);
    }
}
