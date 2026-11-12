package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * The wheel's mouse state while it is up: a running sum of the camera's turn, and the segment the last
 * flick selected (spec §5.5 — 鼠标移入某格切换预设, via direction because there is no cursor to hover).
 *
 * <h2>Why a separate object and not two fields in the listener</h2>
 * Two rules live here and both are the kind that break silently: a flick must <b>consume</b> the
 * accumulated movement (otherwise a sweep left and then a small move up still points left, and the
 * player cannot correct a wrong pick), and the selection must <b>survive</b> stillness (otherwise the
 * highlight — and the preset the release-tap will fire — flickers off the moment the mouse stops).
 * Those two are in tension, so they are written down once and tested instead of being re-derived at
 * the call site.
 *
 * <p>Not thread-safe on purpose: driven from the client tick only, one value for the one local player,
 * never persisted, and never consulted for a decision the server has to agree with.
 */
@ApiStatus.Internal
public final class WheelSession {

    /** Accumulated camera turn since the last flick, degrees: right-positive, down-positive. */
    private double accumulatedX;
    private double accumulatedY;

    /** The segment the last flick selected, {@link WheelGeometry#NO_SEGMENT} if none yet. */
    private int hovered = WheelGeometry.NO_SEGMENT;

    /**
     * Feeds one tick of camera movement.
     *
     * @param dxDegrees rightward turn, degrees
     * @param dyDegrees downward turn, degrees
     * @return the segment this movement flicked towards, or {@link WheelGeometry#NO_SEGMENT} when the
     *         movement was inside the dead zone (in which case nothing changes at all)
     */
    public int accept(double dxDegrees, double dyDegrees) {
        accumulatedX += dxDegrees;
        accumulatedY += dyDegrees;
        int segment = WheelFlick.segmentForDelta(accumulatedX, accumulatedY);
        if (segment == WheelGeometry.NO_SEGMENT) {
            return WheelGeometry.NO_SEGMENT;
        }
        // The flick is spent: the next selection has to come from the player's next movement, so a
        // wrong pick can be corrected by flicking somewhere else instead of by sweeping around.
        accumulatedX = 0.0;
        accumulatedY = 0.0;
        hovered = segment;
        return segment;
    }

    /** {@return the segment the last flick selected, {@link WheelGeometry#NO_SEGMENT} before the first} */
    public int hovered() {
        return hovered;
    }

    /** Forgets both the accumulated movement and the selection: the wheel is opening or closing. */
    public void reset() {
        accumulatedX = 0.0;
        accumulatedY = 0.0;
        hovered = WheelGeometry.NO_SEGMENT;
    }
}
