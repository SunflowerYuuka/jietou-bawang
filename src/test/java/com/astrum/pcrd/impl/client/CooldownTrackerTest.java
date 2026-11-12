package com.astrum.pcrd.impl.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cooldown ring's two pure steps: what a new server value does to the tracked span, and how much
 * of it is left.
 *
 * <h2>Why the denominator is observed rather than configured</h2>
 * {@code ChargeSyncPayload} says when the cooldown ends, never how long it was; {@code ServerConfig}
 * is not on the client. So the span is the longest remaining time this client has seen for that end
 * tick — which makes "does a late frame make the ring jump?" a question with an answer, and this test
 * that answer.
 */
class CooldownTrackerTest {

    @Test
    void noCooldownNowMeansNothingToDraw() {
        assertEquals(CooldownTracker.Span.NONE,
                CooldownTracker.observe(CooldownTracker.Span.NONE, 0L, 100L));
        assertEquals(CooldownTracker.Span.NONE,
                CooldownTracker.observe(new CooldownTracker.Span(50L, 40L), 50L, 100L));
    }

    @Test
    void aNewCooldownTakesItsRemainingTimeAsTheSpan() {
        CooldownTracker.Span span = CooldownTracker.observe(CooldownTracker.Span.NONE, 200L, 100L);
        assertEquals(200L, span.endTick());
        assertEquals(100L, span.spanTicks());
    }

    @Test
    void laterFramesOfTheSameCooldownKeepTheHighWaterMark() {
        CooldownTracker.Span first = CooldownTracker.observe(CooldownTracker.Span.NONE, 200L, 100L);
        CooldownTracker.Span later = CooldownTracker.observe(first, 200L, 150L);
        assertEquals(first, later,
                "the ring must not restart when the next sync arrives mid-cooldown");
    }

    @Test
    void aReArmedCooldownOnTheSameEndTickWidensTheSpanAgain() {
        CooldownTracker.Span first = CooldownTracker.observe(CooldownTracker.Span.NONE, 200L, 100L);
        CooldownTracker.Span wider = CooldownTracker.observe(first, 200L, 60L);
        assertEquals(140L, wider.spanTicks(), "an earlier observation means more time was left");
    }

    @Test
    void aDifferentEndTickIsANewCooldown() {
        CooldownTracker.Span first = CooldownTracker.observe(CooldownTracker.Span.NONE, 200L, 100L);
        CooldownTracker.Span next = CooldownTracker.observe(first, 900L, 300L);
        assertEquals(900L, next.endTick());
        assertEquals(600L, next.spanTicks());
    }

    @Test
    void fractionRunsFromOneAtTheStartToZeroAtTheEnd() {
        CooldownTracker.Span span = new CooldownTracker.Span(200L, 100L);
        assertEquals(1.0, CooldownTracker.fraction(span, 100L), 1e-9);
        assertEquals(0.5, CooldownTracker.fraction(span, 150L), 1e-9);
        assertEquals(0.0, CooldownTracker.fraction(span, 200L), 1e-9);
        assertEquals(0.0, CooldownTracker.fraction(span, 260L), 1e-9);
        assertEquals(0.0, CooldownTracker.fraction(CooldownTracker.Span.NONE, 260L), 1e-9);
    }
}
