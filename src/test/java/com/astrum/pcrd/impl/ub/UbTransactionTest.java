package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.UbCooldown;
import com.astrum.pcrd.impl.attach.UbState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
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
 *
 * <h2>The per-character half (「每个角色UB的CD不共享」)</h2>
 * A paid cast arms <b>one</b> character's cooldown and must not touch another's, which is asserted two
 * ways: the exact paid record (so no field can drift on its own) and the independence test that casts
 * A, then B, and finds A's deadline still standing. The second is the one that would fail if the map
 * were replaced rather than merged.
 */
class UbTransactionTest {

    private static final long NOW = 4_567L;
    private static final int COOLDOWN_TICKS = 123;
    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KYARU = InternalIds.id("kyaru");

    @Test
    void anAcceptedCastEmptiesTheBarExactly() {
        assertEquals(0.0f, paid(acceptedFacts(2)).charge(),
                "the whole bar is spent — never a partial spend and never a subtraction (spec §5.4)");
    }

    @Test
    void anAcceptedCastArmsThatCharactersCooldownFromNow() {
        assertEquals(NOW + COOLDOWN_TICKS, paid(acceptedFacts(2)).cooldownOf(DJEETA).endTick(),
                "the armed deadline must be now + ubGlobalCooldownTicks");
    }

    /**
     * The total travels with the deadline, and it is the length this cast was armed with.
     *
     * <p>This is what makes the HUD's shade honest: it is the denominator of
     * {@code remaining / total}. An operator who retunes the config mid-cooldown changes the next
     * arming, not this one.
     */
    @Test
    void theArmedTotalIsTheLengthThisCastWasGiven() {
        assertEquals(COOLDOWN_TICKS, paid(acceptedFacts(2)).cooldownOf(DJEETA).totalTicks());
    }

    @Test
    void anAcceptedCastRemembersTheSlotThatFired() {
        assertEquals(3, paid(acceptedFacts(3)).selectedSlot());
        assertEquals(0, paid(acceptedFacts(0)).selectedSlot(), "slot 0 must survive as slot 0");
    }

    @Test
    void thePaidStateIsExactlyTheEmptiedBarTheSlotAndThatCooldown() {
        assertEquals(new UbState(0.0f, 3, Map.of(DJEETA, new UbCooldown(4_690L, COOLDOWN_TICKS))),
                paid(acceptedFacts(3)),
                "4_567 + 123: the whole record, so no field can drift on its own");
    }

    @Test
    void anAcceptedCastIsNotARefusal() {
        UbTransaction.Outcome outcome =
                UbTransaction.decide(acceptedFacts(1), COOLDOWN_TICKS, DJEETA, UbState.fresh());
        assertTrue(outcome.accepted());
        assertTrue(outcome.refusal().isEmpty());
        assertTrue(outcome.paid().isPresent());
    }

    /**
     * <b>The headline request.</b> Casting A must leave B castable.
     *
     * <p>「每个角色UB的CD不共享」: with the paid state in hand, A is refused with {@code COOLDOWN} and B —
     * a different character, same full gauge, same tick — is accepted. Before this change both were
     * refused, because one shared {@code cdEndTick} covered the whole wheel.
     */
    @Test
    void castingOneCharacterLeavesEveryOtherCastable() {
        UbState afterCastingDjeeta = paid(acceptedFacts(0));

        CastGate.Facts djeetaAgain = new CastGate.Facts(0, true, true, 100.0, NOW + 1,
                afterCastingDjeeta.cooldownOf(DJEETA).endTick());
        assertEquals(Optional.of(CastGate.Reason.COOLDOWN), CastGate.refusal(djeetaAgain),
                "she is on her own cooldown, so she is refused");

        CastGate.Facts kyaru = new CastGate.Facts(1, true, true, 100.0, NOW + 1,
                afterCastingDjeeta.cooldownOf(KYARU).endTick());
        assertEquals(Optional.empty(), CastGate.refusal(kyaru),
                "another character has no cooldown of her own yet, so she may cast on the same tick");
    }

    /** Casting B must not shorten, extend or overwrite A's deadline. */
    @Test
    void armingOneCooldownLeavesTheOthersAlone() {
        UbState afterA = paid(acceptedFacts(0), DJEETA, UbState.fresh());
        UbState later = new UbState(100.0f, 1, afterA.cooldowns());

        UbTransaction.Outcome second =
                UbTransaction.decide(acceptedFacts(1), COOLDOWN_TICKS, KYARU, later);
        UbState afterB = second.paid().orElseThrow();

        assertEquals(new UbCooldown(NOW + COOLDOWN_TICKS, COOLDOWN_TICKS), afterB.cooldownOf(DJEETA),
                "A's deadline is untouched by B's cast");
        assertEquals(new UbCooldown(NOW + COOLDOWN_TICKS, COOLDOWN_TICKS), afterB.cooldownOf(KYARU));
    }

    /** A character who has never cast has no entry at all, and every reader must tolerate that. */
    @Test
    void neverCastMeansReadyAndAbsent() {
        UbState fresh = UbState.fresh();
        assertEquals(UbCooldown.NONE, fresh.cooldownOf(DJEETA));
        assertTrue(fresh.isReady(DJEETA, 0L));
        assertTrue(fresh.cooldowns().isEmpty(), "the map is empty, not a map of zeroed entries");
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
        assertEquals(List.of(new UbState(0.0f, 1,
                        Map.of(DJEETA, new UbCooldown(4_690L, COOLDOWN_TICKS)))), writes,
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
        return paid(facts, DJEETA, UbState.fresh());
    }

    private static UbState paid(CastGate.Facts facts, ResourceLocation character,
            UbState before) {
        UbTransaction.Outcome outcome =
                UbTransaction.decide(facts, COOLDOWN_TICKS, character, before);
        assertTrue(outcome.paid().isPresent(), "an accepted cast must produce a state to write");
        return outcome.paid().get();
    }

    private static void refuse(CastGate.Facts facts, CastGate.Reason expected) {
        UbTransaction.Outcome outcome =
                UbTransaction.decide(facts, COOLDOWN_TICKS, DJEETA, UbState.fresh());
        assertEquals(Optional.of(expected), outcome.refusal());
        assertFalse(outcome.accepted());
        assertTrue(outcome.paid().isEmpty(),
                "a refused cast must give the caller nothing to write: " + expected);
    }
}
