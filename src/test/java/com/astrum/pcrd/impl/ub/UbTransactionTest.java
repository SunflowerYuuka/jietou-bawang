package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.attach.UbState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cast transaction: what an accepted cast pays, what a refused one changes, and the order the
 * three side effects run in.
 *
 * <h2>Why these assertions exist</h2>
 * This is the one path where a player's property is spent, and it used to be verified only by reading
 * the code. Two plausible slips silently break the contract: writing the new gauge as a
 * <em>subtraction</em> instead of "an empty bar", and moving {@code UbActivatedEvent.post} above the
 * state write so a listener observes the old gauge. Both are caught here — the first because the paid
 * charge is asserted to be exactly {@code 0.0}, the second because the order of the three effects is a
 * value, not a comment.
 *
 * <p>Refusals are the other half: nothing is written, nothing is announced and nothing settles, so a
 * refused cast can never cost the player anything.
 */
class UbTransactionTest {

    private static final long NOW = 4_567L;
    private static final int COOLDOWN_TICKS = 123;

    @Test
    void anAcceptedCastEmptiesTheBarExactly() {
        assertEquals(0.0f, paid(acceptedFacts(2)).charge(),
                "the whole bar is spent — never a partial spend and never a subtraction (spec §5.4)");
    }

    @Test
    void anAcceptedCastArmsTheCooldownFromNow() {
        assertEquals(NOW + COOLDOWN_TICKS, paid(acceptedFacts(2)).cdEndTick(),
                "cdEndTick must be now + ubGlobalCooldownTicks");
    }

    @Test
    void anAcceptedCastRemembersTheSlotThatFired() {
        assertEquals(3, paid(acceptedFacts(3)).selectedSlot());
        assertEquals(0, paid(acceptedFacts(0)).selectedSlot(), "slot 0 must survive as slot 0");
    }

    @Test
    void thePaidStateIsExactlyTheEmptiedBarTheSlotAndTheCooldown() {
        assertEquals(new UbState(0.0f, 3, 4_690L), paid(acceptedFacts(3)),
                "4_567 + 123: the whole record, so no field can drift on its own");
    }

    @Test
    void anAcceptedCastIsNotARefusal() {
        UbTransaction.Outcome outcome = UbTransaction.decide(acceptedFacts(1), COOLDOWN_TICKS);
        assertTrue(outcome.accepted());
        assertTrue(outcome.refusal().isEmpty());
        assertTrue(outcome.paid().isPresent());
    }

    @Test
    void everyRefusalWritesNothing() {
        refuse(new CastGate.Facts(-1, false, false, 100.0, NOW, 0L), CastGate.Reason.SLOT_OUT_OF_RANGE);
        refuse(new CastGate.Facts(5, false, false, 100.0, NOW, 0L), CastGate.Reason.SLOT_OUT_OF_RANGE);
        refuse(new CastGate.Facts(0, false, false, 100.0, NOW, 0L), CastGate.Reason.SLOT_EMPTY);
        refuse(new CastGate.Facts(0, true, false, 100.0, NOW, 0L), CastGate.Reason.CHARACTER_UNKNOWN);
        refuse(new CastGate.Facts(0, true, true, 40.0, NOW, 0L), CastGate.Reason.NOT_CHARGED);
        refuse(new CastGate.Facts(0, true, true, 100.0, NOW, NOW + 5), CastGate.Reason.COOLDOWN);
    }

    @Test
    void theThreeSideEffectsHappenOnceEachAndInOrder() {
        List<String> calls = new ArrayList<>();
        List<UbState> writes = new ArrayList<>();

        UbTransaction.commit(paid(acceptedFacts(1)),
                state -> {
                    calls.add("write");
                    writes.add(state);
                },
                () -> calls.add("announce"),
                () -> calls.add("settle"));

        assertEquals(List.of("write", "announce", "settle"), calls,
                "payment is written first, then announced, then settled — a listener must never see the old gauge");
        assertEquals(List.of(new UbState(0.0f, 1, 4_690L)), writes,
                "exactly one write, and it carries the paid state");
    }

    @Test
    void anOutcomeCarryingBothOrNeitherIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new UbTransaction.Outcome(Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new UbTransaction.Outcome(
                Optional.of(CastGate.Reason.COOLDOWN), Optional.of(UbState.fresh())));
    }

    private static CastGate.Facts acceptedFacts(int slot) {
        return new CastGate.Facts(slot, true, true, 100.0, NOW, 0L);
    }

    private static UbState paid(CastGate.Facts facts) {
        UbTransaction.Outcome outcome = UbTransaction.decide(facts, COOLDOWN_TICKS);
        assertTrue(outcome.paid().isPresent(), "an accepted cast must produce a state to write");
        return outcome.paid().get();
    }

    private static void refuse(CastGate.Facts facts, CastGate.Reason expected) {
        UbTransaction.Outcome outcome = UbTransaction.decide(facts, COOLDOWN_TICKS);
        assertEquals(Optional.of(expected), outcome.refusal());
        assertFalse(outcome.accepted());
        assertTrue(outcome.paid().isEmpty(),
                "a refused cast must give the caller nothing to write: " + expected);
    }
}
