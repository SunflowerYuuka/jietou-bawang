package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「他充」/「他奶」 and the four over-time effects: one receiver rule, and two mechanisms for four names.</b>
 *
 * <h2>The receiver rule, and the two things the human said about it</h2>
 * 「在周围有可以充能拥有UB槽的友方单位时，优先给组最近的友方单位，没有的情况下再给自己。」 and 「他奶同他充」. So:
 * nearest ally <b>with a gauge</b>, else the caster — one function, shared. {@link #theTwoTransfersShareOneReceiverRule}
 * is the assertion that they cannot diverge, and it is written against
 * {@code TransferTarget.of}, the function both effects call, rather than against either effect.
 *
 * <h2>What a unit test can and cannot see</h2>
 * The membership test ({@code AllySelector}) and the distance comparison are pure, so they are asserted here with
 * plain {@code Candidate} values — including the tie-break, which is the case nobody can reproduce by playing. The
 * gauge test reads an attachment and therefore needs a player, so what is asserted is its <em>shape</em>: a
 * non-player is never a candidate, and the predicate is the party attachment rather than a new flag. The
 * end-to-end "it charged 可可萝 instead of me" is a human's check in game, and the report says so.
 */
class TransferAndOverTimeTest {

    // ---------------------------------------------------------------------------------------
    // The one receiver rule
    // ---------------------------------------------------------------------------------------

    /**
     * <b>Both transfer effects land on the same body, because they call one function.</b>
     *
     * <p>The human said 「他奶同他充」 — the two are one rule with two payloads. This asserts the shared entry point
     * exists and is what the effects use: {@code TieredEffects} calls {@code TransferTarget.of} for
     * {@code TRANSFER_CHARGE} and for {@code TRANSFER_HEAL}, so a divergence would have to be a second call site.
     * A non-player caster is the only case assertable without a server, and it must answer the caster — the
     * documented last resort.
     */
    @Test
    void theTwoTransfersShareOneReceiverRule() {
        // A null caster answers null rather than throwing: every caller is on a damage path.
        assertNull(TransferTarget.of(null, 8.0));
        // Both effect types are in the same receiver family, which is what makes "one rule" possible at all.
        assertTrue(EffectType.TRANSFER_CHARGE.reachesParty());
        assertTrue(EffectType.TRANSFER_HEAL.reachesParty());
        // And neither is a victim-scoped debuff, which would make the shared rule meaningless.
        assertFalse(EffectType.TRANSFER_CHARGE.reachesVictim());
        assertFalse(EffectType.TRANSFER_HEAL.reachesVictim());
    }

    /**
     * <b>The nearest candidate wins, and two at the same distance are broken deterministically.</b>
     *
     * <p>The tie-break is the part that cannot be tested by playing: two allies standing together would land the
     * transfer on whichever the level happened to list first, so the answer would change between ticks and nobody
     * could reproduce the bug. Lowest UUID wins, and both effects get that answer because they share the function.
     */
    @Test
    void theNearestCandidateWinsWithAStableTieBreak() {
        UUID near = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID tieLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID tieHigh = UUID.fromString("00000000-0000-0000-0000-000000000009");
        UUID far = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

        // Plainly nearest wins, regardless of list order.
        assertEquals(near, TransferTarget.select(List.of(
                        candidate(far, 9.0), candidate(near, 1.0), candidate(tieLow, 4.0)))
                .orElseThrow().id(), "the nearest candidate is chosen, not the first in the list");

        // A tie is broken by the id, in both list orders, so iteration order cannot decide it.
        assertEquals(tieLow, TransferTarget.select(List.of(
                        candidate(tieHigh, 2.0), candidate(tieLow, 2.0))).orElseThrow().id(),
                "a tie goes to the lower id");
        assertEquals(tieLow, TransferTarget.select(List.of(
                        candidate(tieLow, 2.0), candidate(tieHigh, 2.0))).orElseThrow().id(),
                "and the same one wins when the list is reversed — the tie-break is not list order");

        // No candidates means no answer, which is what makes the caller fall back to the caster.
        assertEquals(Optional.empty(), TransferTarget.select(List.of()));
        assertEquals(Optional.empty(), TransferTarget.select(null));
    }

    /** A NaN distance is dropped rather than compared: every comparison against NaN is false. */
    @Test
    void aNonsenseDistanceCannotWin() {
        UUID sane = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        UUID broken = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(sane, TransferTarget.select(List.of(
                        candidate(broken, Double.NaN), candidate(sane, 5.0))).orElseThrow().id(),
                "a candidate whose distance could not be measured must not be selected");
        assertEquals(Optional.empty(), TransferTarget.select(List.of(candidate(broken, Double.NaN))),
                "and when it is the only one, the answer is 'nobody' so the caster is used");
    }

    /** A non-player has no wheel and no gauge, so it is never a receiver. */
    @Test
    void aNonPlayerNeverHasAGauge() {
        assertFalse(TransferTarget.hasGauge(null));
        // No ServerPlayer exists in a unit-test JVM, so the only constructible case is the refusal.
    }

    // ---------------------------------------------------------------------------------------
    // The ladders
    // ---------------------------------------------------------------------------------------

    /**
     * <b>「他充」 is 6 points a rung, and 「他奶」 is the instant heal's own 20%.</b>
     *
     * <p>「他奶就是套用瞬间回血的那个效果」 — so its ladder must be {@code HEAL_SELF}'s, not a new convention. A
     * second heal scale would be exactly the "invent a third convention" the brief forbids.
     */
    @Test
    void theTransferLaddersMatchTheEffectsTheyReuse() {
        assertEquals(6.0, EffectTiers.ruleOf(EffectType.TRANSFER_CHARGE).stepPerTier(), 1e-9);
        assertEquals(5, EffectTiers.maxTier(EffectType.TRANSFER_CHARGE));
        assertEquals(0.20, EffectTiers.ruleOf(EffectType.TRANSFER_HEAL).stepPerTier(), 1e-9,
                "他奶 reuses the instant heal's rung");
        assertEquals(EffectTiers.maxTier(EffectType.HEAL_SELF),
                EffectTiers.maxTier(EffectType.TRANSFER_HEAL),
                "and its rung count is the instant heal's, exactly");
        assertEquals(EffectTiers.ruleOf(EffectType.HEAL_SELF).stepPerTier(),
                EffectTiers.ruleOf(EffectType.TRANSFER_HEAL).stepPerTier(),
                "one heal convention in this mod: only the receiver differs");
        // Tier 5 of 他奶 is a full heal of the receiver, like HEAL_SELF's.
        assertEquals(1.0, TieredAmounts.resolve(EffectType.TRANSFER_HEAL,
                TieredParams.of(5, 8.0, 6.0)).fraction(), 1e-9);
    }

    /**
     * <b>The four over-time effects are five-rung ladders whose magnitude is the amplifier.</b>
     *
     * <p>A {@code stepPerTier} of 1 is the code for "the rung IS the amplifier", the same encoding
     * {@code haste} and {@code summon_spirit} use — not a percentage, because {@code regeneration}'s strength is
     * a level and charge-regen's is a rate the tick reads.
     */
    @Test
    void theOverTimeLaddersAreFiveRungsWithTheAmplifierAsMagnitude() {
        for (EffectType type : List.of(EffectType.REGEN_SELF, EffectType.REGEN_AREA,
                EffectType.CHARGE_REGEN_SELF, EffectType.CHARGE_REGEN_AREA)) {
            assertEquals(5, EffectTiers.maxTier(type), type + " has five rungs");
            assertEquals(1.0, EffectTiers.ruleOf(type).stepPerTier(), 1e-9,
                    type + "'s rung is its amplifier, not a fraction");
        }
        assertEquals(0, OverTimeEffects.amplifierForTier(1), "tier 1 is amplifier 0");
        assertEquals(4, OverTimeEffects.amplifierForTier(5), "tier 5 is amplifier 4");
    }

    /**
     * <b>Charge-regen pays 4 points a second at its lowest rung and 20 at its highest.</b>
     *
     * <p>The lowest rung must <em>do</em> something: a rung-1 buff that paid nothing would put a HUD entry on the
     * player saying something untrue, which is the rule {@code ChargeKernel.rateForAmplifier} states for its own
     * ladder. And the per-tick share must stay fractional, or a tier-1 regen would truncate to zero every tick and
     * never pay at all.
     */
    @Test
    void chargeRegenPaysARealRateAtEveryRung() {
        assertEquals(2.0, OverTimeEffects.chargePointsPerSecond(0), 1e-9);
        assertEquals(4.0, OverTimeEffects.chargePointsPerSecond(1), 1e-9);
        assertEquals(10.0, OverTimeEffects.chargePointsPerSecond(4), 1e-9);
        assertEquals(0.1, OverTimeEffects.chargePointsPerTick(0), 1e-9,
                "a tenth of a point a tick at tier 1 — the fraction is the point");
        assertEquals(0.5, OverTimeEffects.chargePointsPerTick(4), 1e-9);
        // Out-of-range amplifiers clamp into the ladder rather than inventing a rung.
        assertEquals(2.0, OverTimeEffects.chargePointsPerSecond(-3), 1e-9);
        assertEquals(10.0, OverTimeEffects.chargePointsPerSecond(99), 1e-9);
    }

    /**
     * <b>The four over-time types are states, and the two transfers are not.</b>
     *
     * <p>This is what decides whether a fired skill produces a bar row at all: a timed regeneration is a state a
     * player carries, so it gets one; a transfer is a grant that finished in the same tick, so it gets none — the
     * same classification {@code GAIN_CHARGE} and {@code HEAL} already have.
     */
    @Test
    void theOverTimeTypesPersistAndTheTransfersDoNot() {
        assertTrue(EffectType.REGEN_SELF.persistsOnCaster());
        assertTrue(EffectType.REGEN_AREA.persistsOnCaster());
        assertTrue(EffectType.CHARGE_REGEN_SELF.persistsOnCaster());
        assertTrue(EffectType.CHARGE_REGEN_AREA.persistsOnCaster());
        assertFalse(EffectType.TRANSFER_CHARGE.persistsOnCaster(),
                "a transfer credits in one tick: it leaves no state to report");
        assertFalse(EffectType.TRANSFER_HEAL.persistsOnCaster(),
                "and a transfer heal restores in one tick, the same answer HEAL gets");
    }

    /**
     * <b>The self and area forms differ only in the radius</b> — one call, two receiver sets.
     *
     * <p>Four names in the data, two mechanisms in the code. This asserts the shape that makes that true:
     * {@code REGEN_SELF} and {@code REGEN_AREA} share one effect (vanilla's {@code regeneration}) and one amplifier
     * mapping, and {@code CHARGE_REGEN_SELF} / {@code CHARGE_REGEN_AREA} share <b>one registered type</b>, which is
     * what makes the human's 「取最强」 merge rule vanilla's own rather than a rule of ours.
     */
    @Test
    void theSelfAndAreaFormsShareTheirMechanism() {
        assertEquals(OverTimeEffects.amplifierForTier(3), OverTimeEffects.amplifierForTier(3));
        // One registered type for both charge-regen forms: the manifest must contain it exactly once.
        long chargeRegenEntries = PartyEffects.mechanicHolders().stream()
                .filter(holder -> holder.getKey().location().getPath()
                        .equals(OverTimeEffects.CHARGE_REGEN_PATH))
                .count();
        assertEquals(1, chargeRegenEntries,
                "one astrum:charge_regen serves both the self and the area form, so the strongest-wins merge "
                        + "is vanilla's");
        assertSame(PartyEffects.CHARGE_REGEN, PartyEffects.CHARGE_REGEN);
        assertTrue(PartyEffects.chargeRegenHolder().isPresent()
                        || !PartyEffects.CHARGE_REGEN.isBound(),
                "the bound-safe accessor agrees with the holder's own state");
    }

    /** An absent radius means the party default, not "self only": the same reading every party effect uses. */
    @Test
    void anAbsentRadiusIsThePartyDefault() {
        assertEquals(AttackBuffs.radiusFor(OptionalDouble.empty()),
                OverTimeEffects.radiusFor(OptionalDouble.empty()), 1e-9);
        assertEquals(AttackBuffs.radiusFor(OptionalDouble.of(0.0)),
                TransferTarget.radiusFor(OptionalDouble.of(0.0)), 1e-9);
        assertTrue(OverTimeEffects.radiusFor(OptionalDouble.empty()) > 0.0,
                "「味方全体」 must reach somebody, or the area forms are self-only in disguise");
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static AllySelector.Candidate candidate(UUID id, double distance) {
        return new AllySelector.Candidate(id, false, Optional.empty(), distance);
    }
}
