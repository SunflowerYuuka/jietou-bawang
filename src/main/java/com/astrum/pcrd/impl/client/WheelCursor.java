package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/**
 * Hides the mouse cursor while the wheel is open, and gives it back afterwards (spec §5.5:
 * 仅捕获鼠标并隐藏光标).
 *
 * <h2>Why GLFW and not a {@code Screen} flag</h2>
 * Vanilla has no "hide the cursor while this screen is up" hook. A screen receives the mouse through
 * {@code MouseHandler.releaseMouse()}, which sets GLFW's cursor mode to {@code GLFW_CURSOR_NORMAL} —
 * that is the visible arrow everyone sees over an inventory. Cursor mode is a property of the window,
 * so this class sets it on the same handle {@code MouseHandler} uses, and reads the current mode first
 * instead of assuming it.
 *
 * <p>{@code InputConstants.grabOrReleaseMouse} was the other candidate and is deliberately not used:
 * it also re-centres the cursor ({@code glfwSetCursorPos}), which would teleport the player's pointer
 * out from under the segment they were about to pick.
 *
 * <h2>Why hiding does not break absolute-position hovering</h2>
 * {@code GLFW_CURSOR_HIDDEN} only makes the cursor invisible; GLFW keeps delivering cursor-position
 * events for the hidden cursor, so the GUI keeps receiving ordinary {@code mouseX}/{@code mouseY} and
 * {@link WheelGeometry#segmentAt} answers about exactly the same point as before. The alternative,
 * {@code GLFW_CURSOR_DISABLED}, would be wrong for the opposite reason: it locks the cursor to the
 * window centre and reports deltas, and the wheel's hover is absolute.
 *
 * <h2>Restore</h2>
 * {@link #restore()} is called from {@link WheelScreen#removed()}, which vanilla calls for every way a
 * screen can go away: {@code Minecraft.setScreen} invokes {@code removed()} on the outgoing screen
 * before it hands the mouse to the next one (so Escape, a digit cast, a disconnect, another mod
 * replacing the screen and client shutdown all go through it). It only ever puts the mode back to
 * {@code GLFW_CURSOR_NORMAL}, and only when the mode really is hidden — the caller that follows
 * ({@code MouseHandler.grabMouse} for the resumed world, {@code releaseMouse} for a GUI) then decides
 * the final state, which is why closing the wheel into the world still leaves the game's own grab
 * intact.
 */
@ApiStatus.Internal
final class WheelCursor {

    private WheelCursor() {
    }

    /**
     * Hides the cursor until {@link #restore()}.
     *
     * <p>Idempotent on purpose: {@code init()} runs again whenever the window is resized, and hiding an
     * already hidden cursor must not be an error or a log line.
     */
    static void hide() {
        long window = windowHandle();
        if (window == 0L) {
            return;
        }
        int mode = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
        if (mode == GLFW.GLFW_CURSOR_HIDDEN) {
            return;
        }
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        Astrum.LOGGER.debug("astrum: wheel cursor hidden (cursor mode {} -> {})",
                mode, GLFW.GLFW_CURSOR_HIDDEN);
    }

    /**
     * Shows the cursor again, if this class left it hidden.
     *
     * <p>Safe to call twice and safe to call when nothing was ever hidden: it asks GLFW what the mode
     * is, and leaves any other mode alone (a grabbed game window owns {@code GLFW_CURSOR_DISABLED}, and
     * that is not ours to undo).
     */
    static void restore() {
        long window = windowHandle();
        if (window == 0L) {
            return;
        }
        if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_HIDDEN) {
            return;
        }
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        Astrum.LOGGER.debug("astrum: wheel cursor restored (cursor mode {} -> {})",
                GLFW.GLFW_CURSOR_HIDDEN, GLFW.GLFW_CURSOR_NORMAL);
    }

    /** {@return the GLFW window handle, or {@code 0} when there is no window yet} */
    private static long windowHandle() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        return window == null ? 0L : window.getWindow();
    }
}
