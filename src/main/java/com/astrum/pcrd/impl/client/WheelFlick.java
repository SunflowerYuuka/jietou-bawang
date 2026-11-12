package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * Turns a mouse movement <b>direction</b> into a wheel segment: the non-modal overlay's replacement
 * for hovering with an absolute cursor (spec §5.5).
 *
 * <h2>Why direction, and not a drawn pointer</h2>
 * The wheel is no longer a {@code Screen}, so in game the cursor is {@code GLFW_CURSOR_DISABLED} and
 * there is no absolute pointer to hover with. Two ways to get the choice back: draw a virtual cursor
 * that the accumulated deltas push around, or read the direction of the movement and pick the segment
 * it points at. This class implements the second, because in combat the failure modes of a pointer are
 * all about being <em>aimed</em>: it starts at the centre, it has to be pushed out to the band, it
 * sticks to a screen edge if the player over-shoots, and it needs a sensitivity setting to feel right
 * for different mice. A flick cannot get stuck (a new flick is a new direction, whatever the previous
 * one was), needs no sensitivity, and points where a player naturally flicks a wrist.
 *
 * <h2>The dead zone</h2>
 * Below {@link #DEAD_ZONE_DEGREES} of accumulated movement nothing is picked and the caller keeps the
 * current preset, so hand jitter while the key is held does not shuffle the selection. It is a
 * magnitude test only: it never changes <em>which</em> segment a direction maps to.
 *
 * <h2>Magnitude never matters</h2>
 * Once the dead zone is passed, the answer depends on the direction alone: a 3° flick and a 300°
 * sweep in the same direction pick the same segment. That is deliberate — it is what stops a fast
 * (large-delta) flick from landing somewhere other than a slow one, and it is why there is nothing
 * here that can overflow or saturate.
 *
 * <p>Units: {@code dx} is right-positive and {@code dy} is down-positive, in <b>degrees of camera
 * rotation</b> — the game's own record of the mouse movement while the cursor is grabbed (Minecraft
 * turns the player by exactly the accumulated mouse delta, scaled by the player's sensitivity, and
 * {@code MouseHandler}'s raw accumulators are private). The convention matches
 * {@link WheelGeometry#clockwiseDegreesFromUp(double, double)} and therefore the ring's own angles.
 *
 * <p>Pure: no Minecraft types, no state. Asserted per segment in {@code WheelFlickTest}.
 */
@ApiStatus.Internal
public final class WheelFlick {

    /**
     * Accumulated movement below this many degrees selects nothing.
     *
     * <p>Chosen against the gesture, not against a mouse: at the default sensitivity 1° of camera turn
     * is roughly 7 px of mouse travel, so 2.5° is a deliberate short movement rather than a twitch.
     */
    public static final double DEAD_ZONE_DEGREES = 2.5;

    private WheelFlick() {
    }

    /**
     * {@return the segment a movement direction selects, or {@link WheelGeometry#NO_SEGMENT} inside
     * the dead zone}
     *
     * @param dxDegrees rightward movement, in degrees
     * @param dyDegrees downward movement, in degrees
     */
    public static int segmentForDelta(double dxDegrees, double dyDegrees) {
        if (isInsideDeadZone(dxDegrees, dyDegrees)) {
            return WheelGeometry.NO_SEGMENT;
        }
        return WheelGeometry.segmentAtAngle(WheelGeometry.clockwiseDegreesFromUp(dxDegrees, dyDegrees));
    }

    /**
     * {@return whether a movement is too small to count as a flick}
     *
     * @param dxDegrees rightward movement, in degrees
     * @param dyDegrees downward movement, in degrees
     */
    public static boolean isInsideDeadZone(double dxDegrees, double dyDegrees) {
        if (Double.isNaN(dxDegrees) || Double.isNaN(dyDegrees)) {
            return true;
        }
        return dxDegrees * dxDegrees + dyDegrees * dyDegrees < DEAD_ZONE_DEGREES * DEAD_ZONE_DEGREES;
    }

    /**
     * {@return how far the yaw turned from {@code fromYaw} to {@code toYaw}, wrapped into
     * {@code (-180, 180]}}
     *
     * <p>Minecraft reports yaw in {@code (-180, 180]}, so turning right past 180 flips the sign. Taken
     * literally that reads as a ~360° flick the other way — the wheel would pick the opposite segment
     * on the very tick the player crossed the seam. Wrapping keeps the answer the small turn it
     * actually was.
     *
     * @param fromYaw the yaw sampled on the previous tick
     * @param toYaw   the yaw sampled now
     */
    public static double yawDelta(float fromYaw, float toYaw) {
        double raw = (double) toYaw - (double) fromYaw;
        return wrapDegrees(raw);
    }

    /**
     * {@return {@code degrees} wrapped into {@code (-180, 180]}}
     *
     * @param degrees any angle
     */
    public static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped <= -180.0) {
            wrapped += 360.0;
        } else if (wrapped > 180.0) {
            wrapped -= 360.0;
        }
        return wrapped;
    }
}
