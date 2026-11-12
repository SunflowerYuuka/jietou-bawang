package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.ub.CastGate.Facts;
import com.astrum.pcrd.impl.ub.CastGate.Reason;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "May this player cast slot N right now?", tested as a pure function so every rejection reason can
 * be pinned without a server, a player or a config file.
 *
 * <p>Spec §5.4 makes the answer non-negotiable in both directions: the ultimate fires only on a full
 * gauge, off cooldown, from a slot that actually holds a character — and every other answer must be
 * <b>typed</b>, because "禁止静默失败" means the client needs a reason to shake the slot and play a
 * sound for.
 *
 * <p>The shared fixture is a cast that may proceed: slot 2, occupied, a live character definition,
 * a full gauge, a cooldown that ended at tick 100. Each test breaks exactly one thing, so a reason
 * that is reported for the wrong cause fails loudly instead of looking plausible.
 */
class CastGateTest {

    /** Now. Well past the cooldown of {@link #facts()}. */
    private static final long NOW = 1_000L;

    /** The cooldown end of the passing fixture: already elapsed. */
    private static final long READY = 900L;

    /** A full gauge, exactly: {@code ChargeKernel.isFull} is equality, not a threshold. */
    private static final double FULL = 100.0;

    /** The passing fixture: every check succeeds. */
    private static Facts facts() {
        return new Facts(2, true, true, FULL, NOW, READY);
    }

    private static Facts with(int slot, boolean occupied, boolean known, double charge, long now,
            long cdEnd) {
        return new Facts(slot, occupied, known, charge, now, cdEnd);
    }

    private static Reason refusal(Facts facts) {
        Optional<Reason> reason = CastGate.refusal(facts);
        assertTrue(reason.isPresent(), "expected a typed refusal, got none");
        return reason.get();
    }

    @Test
    void aFullGaugeOnALiveSlotOffCooldownMayCast() {
        assertEquals(Optional.empty(), CastGate.refusal(facts()));
    }

    /** The gate must never index the party with a slot outside {@code [0, Party.SLOTS)}. */
    @Test
    void refusesASlotBelowTheWheel() {
        assertEquals(Reason.SLOT_OUT_OF_RANGE, refusal(with(-1, true, true, FULL, NOW, READY)));
    }

    @Test
    void refusesASlotPastTheWheel() {
        assertEquals(Reason.SLOT_OUT_OF_RANGE, refusal(with(5, true, true, FULL, NOW, READY)));
    }

    @Test
    void refusesAnEmptySlot() {
        assertEquals(Reason.SLOT_EMPTY, refusal(with(2, false, false, FULL, NOW, READY)));
    }

    /** A party slot can name a character the pack no longer defines; that is not a silent no-op. */
    @Test
    void refusesACharacterThePackNoLongerDefines() {
        assertEquals(Reason.CHARACTER_UNKNOWN, refusal(with(2, true, false, FULL, NOW, READY)));
    }

    @Test
    void refusesAnEmptyGauge() {
        assertEquals(Reason.NOT_CHARGED, refusal(with(2, true, true, 0.0, NOW, READY)));
    }

    /** Almost full is not full: the HUD would draw one pixel short of the ultimate. */
    @Test
    void refusesAGaugeOneFractionShortOfFull() {
        assertEquals(Reason.NOT_CHARGED, refusal(with(2, true, true, 99.999, NOW, READY)));
    }

    @Test
    void refusesWhileTheCooldownRuns() {
        assertEquals(Reason.COOLDOWN, refusal(with(2, true, true, FULL, NOW, NOW + 1)));
    }

    /** The boundary is inclusive, matching {@code UbState.isReady}: the end tick is already usable. */
    @Test
    void castsOnTheTickTheCooldownEnds() {
        assertEquals(Optional.empty(), CastGate.refusal(with(2, true, true, FULL, NOW, NOW)));
    }

    /**
     * When several checks fail at once the answer must be stable and documented, not whichever
     * branch happened to be written first: range, then occupancy, then the definition, then the
     * gauge, then the cooldown. Otherwise the player sees a cooldown shake while the real problem is
     * an empty slot, and the log disagrees with the UI.
     */
    @Test
    void reportsTheFirstProblemInTheDocumentedOrder() {
        assertEquals(Reason.SLOT_OUT_OF_RANGE, refusal(with(9, false, false, 0.0, NOW, NOW + 50)));
        assertEquals(Reason.SLOT_EMPTY, refusal(with(2, false, true, 0.0, NOW, NOW + 50)));
        assertEquals(Reason.CHARACTER_UNKNOWN, refusal(with(2, true, false, 0.0, NOW, NOW + 50)));
        assertEquals(Reason.NOT_CHARGED, refusal(with(2, true, true, 0.0, NOW, NOW + 50)));
    }
}
