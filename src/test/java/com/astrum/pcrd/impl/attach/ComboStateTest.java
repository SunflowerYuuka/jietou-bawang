package com.astrum.pcrd.impl.attach;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code combo_n} chain of one player: a hit counter that forgets itself after a gap.
 *
 * <p>Same shape as the stack table, for the same reason (spec §4.4's performance red line): the chain
 * is a number plus the tick of the last hit, and "did the chain break?" is answered by comparing two
 * ticks when the player next hits something. No timer, no tick listener, nothing to sweep.
 */
class ComboStateTest {

    private static final int WINDOW = 40;

    @Test
    void consecutiveHitsCountUp() {
        ComboState state = ComboState.EMPTY;
        state = state.noteHit(100, WINDOW);
        assertEquals(1, state.hits());
        state = state.noteHit(120, WINDOW);
        assertEquals(2, state.hits());
        state = state.noteHit(140, WINDOW);
        assertEquals(3, state.hits());
        assertEquals(140, state.lastHitTick());
    }

    /** Boundary rule shared with {@link StackKernel#isExpired}: age {@code >=} window is a new chain. */
    @Test
    void aGapOfTheWindowLengthStartsANewChain() {
        ComboState state = new ComboState(3, 100).noteHit(139, WINDOW);
        assertEquals(4, state.hits(), "39 ticks later is still the same chain");
        state = new ComboState(3, 100).noteHit(140, WINDOW);
        assertEquals(1, state.hits(), "exactly the window later is a new chain");
        state = new ComboState(3, 100).noteHit(200, WINDOW);
        assertEquals(1, state.hits());
    }

    @Test
    void aTickThatWentBackwardsStartsANewChain() {
        assertEquals(1, new ComboState(5, 500).noteHit(100, WINDOW).hits());
    }

    /** A window of zero or less means "no combo can span two hits" — never "infinite combo". */
    @Test
    void aNonPositiveWindowNeverAccumulates() {
        ComboState state = ComboState.EMPTY;
        for (int i = 0; i < 5; i++) {
            state = state.noteHit(100, 0);
            assertEquals(1, state.hits());
        }
        assertEquals(1, new ComboState(4, 100).noteHit(100, -5).hits());
    }

    @Test
    void readingAStaleChainAnswersZero() {
        ComboState state = new ComboState(4, 100);
        assertEquals(4, state.hitsAt(139, WINDOW));
        assertEquals(0, state.hitsAt(140, WINDOW));
        assertEquals(0, ComboState.EMPTY.hitsAt(0, WINDOW));
        assertEquals(0, state.hitsAt(0, WINDOW), "a state from the future is not a live chain");
    }

    /** The count is capped so a long fight cannot overflow it into a negative. */
    @Test
    void anAbsurdlyLongChainStaysPositive() {
        ComboState state = new ComboState(Integer.MAX_VALUE, 100);
        assertTrue(state.noteHit(110, WINDOW).hits() > 0);
    }
}
