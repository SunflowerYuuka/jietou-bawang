package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * How far through the shared cooldown the client believes it is, for the HUD's cooldown ring.
 *
 * <h2>The problem this solves</h2>
 * {@code ChargeSyncPayload} carries an absolute {@code cdEndTick} and nothing else: the client is
 * told <em>when</em> the cooldown ends, never how long it was. A ring sweep needs both — without a
 * total, the only honest drawing is "on cooldown / off cooldown", which is a lamp, not a ring. The
 * total is therefore <b>observed</b>: the remaining ticks first seen for a given end tick are the
 * longest this client has seen that cooldown be, and that high-water mark is the denominator.
 *
 * <h2>What it is not allowed to do</h2>
 * It never feeds a decision: {@code CastGate} on the server is the only thing that decides whether a
 * cast may happen (spec §5.5). A ring drawn slightly long after a client joins mid-cooldown is a
 * cosmetic approximation, not a rule. The tracking itself is pure — {@link #observe} and
 * {@link #fraction} take the previous value and the clock and return a new one — so it is tested
 * without a client, and the one mutable field in {@link UbHud} only holds the last answer.
 */
@ApiStatus.Internal
public final class CooldownTracker {

    /**
     * The cooldown the client is currently drawing.
     *
     * @param endTick   the game time the cooldown ends; {@code 0} when there is nothing to draw
     * @param spanTicks the longest remaining duration seen for this {@code endTick}
     */
    public record Span(long endTick, long spanTicks) {

        /** No cooldown observed. */
        public static final Span NONE = new Span(0L, 0L);
    }

    private CooldownTracker() {
    }

    /**
     * Folds one observation of the server's cooldown into the tracking span.
     *
     * <ul>
     *   <li>Cooldown over, or none at all — {@link Span#NONE}.</li>
     *   <li>A different {@code cdEndTick} than before — a new cooldown: its span is what is left of
     *       it, since that is the most this client can prove.</li>
     *   <li>Same {@code cdEndTick} — keep the high-water mark, so a frame that arrives late does not
     *       make the ring jump backwards.</li>
     * </ul>
     *
     * @param previous  the last span, never {@code null}
     * @param cdEndTick the end tick the server last reported
     * @param nowTick   the client's game time
     */
    public static Span observe(Span previous, long cdEndTick, long nowTick) {
        if (cdEndTick <= nowTick) {
            return Span.NONE;
        }
        long remaining = cdEndTick - nowTick;
        if (previous.endTick() != cdEndTick || remaining > previous.spanTicks()) {
            return new Span(cdEndTick, remaining);
        }
        return previous;
    }

    /**
     * {@return the fraction of the cooldown still to run, {@code 1.0} at its start and {@code 0.0}
     *         once it is over}
     *
     * @param span    the tracked cooldown
     * @param nowTick the client's game time
     */
    public static double fraction(Span span, long nowTick) {
        if (span.endTick() <= nowTick || span.spanTicks() <= 0L) {
            return 0.0;
        }
        long remaining = span.endTick() - nowTick;
        return Math.max(0.0, Math.min(1.0, (double) remaining / (double) span.spanTicks()));
    }
}
