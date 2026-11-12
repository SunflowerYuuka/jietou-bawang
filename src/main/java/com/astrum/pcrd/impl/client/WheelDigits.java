package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/**
 * The one key rule the non-modal overlay intercepts: which raw key code is a wheel digit (spec §5.5).
 *
 * <h2>Why this is a type and not a {@code switch} in the listener</h2>
 * The wheel is no longer a {@code Screen}, so vanilla's hotbar keys are live while it is up: pressing
 * {@code 3} would both pick segment 3 <em>and</em> switch the hotbar to slot 3 (spec §5.5's 必须处理).
 * The rule "exactly {@code 1}–{@code 5}, and nothing else" is therefore the whole of the only key
 * interception the design allows, and it has to be checkable without a client. It is one pure function
 * with one assertion per key in {@code WheelDigitsTest}, including the neighbours that must
 * <em>not</em> match ({@code 0}, {@code 6}–{@code 9}, {@code W}, {@code Esc}).
 *
 * <p>GLFW key codes are plain constants, so this class has no Minecraft types and no GLFW call: the
 * values are inlined at compile time and nothing here touches the native library.
 *
 * <p>The digit convention itself ({@code 1}–{@code 5}) is the one {@code WheelInputMachine.onDigit}
 * speaks; the digit → slot conversion is {@code WheelSlots.slotForDigit}. This class only answers
 * "is this key one of the five?".
 */
@ApiStatus.Internal
public final class WheelDigits {

    /** Returned for every key that is not a wheel digit. */
    public static final int NO_DIGIT = 0;

    private WheelDigits() {
    }

    /**
     * {@return the wheel digit a raw key code presses, {@code 1}–{@code 5}, or {@link #NO_DIGIT}}
     *
     * <p>{@code 1}–{@code 5} are the five wheel slots. {@code 0} and {@code 6}–{@code 9} are hotbar
     * keys with no wheel meaning, and every other key — movement, {@code Esc}, the release key — passes
     * through untouched; that narrowness is the spec's requirement, not an accident.
     *
     * @param glfwKeyCode a GLFW key code from an input event
     */
    public static int digitForKey(int glfwKeyCode) {
        return switch (glfwKeyCode) {
            case GLFW.GLFW_KEY_1 -> 1;
            case GLFW.GLFW_KEY_2 -> 2;
            case GLFW.GLFW_KEY_3 -> 3;
            case GLFW.GLFW_KEY_4 -> 4;
            case GLFW.GLFW_KEY_5 -> 5;
            default -> NO_DIGIT;
        };
    }

    /**
     * {@return whether a raw key code is one of the five wheel digits}
     *
     * @param glfwKeyCode a GLFW key code from an input event
     */
    public static boolean isWheelDigit(int glfwKeyCode) {
        return digitForKey(glfwKeyCode) != NO_DIGIT;
    }
}
