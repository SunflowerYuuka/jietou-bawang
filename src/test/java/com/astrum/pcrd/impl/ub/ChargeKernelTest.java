package com.astrum.pcrd.impl.ub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The charge arithmetic from spec §5.4, pinned to the numbers the brief names.
 *
 * <p>Every assertion here is about a plain {@code double}; nothing in this test boots a level, a
 * player or a config file, because {@link ChargeKernel} must not need any of them.
 */
class ChargeKernelTest {

    @Test void damageGrantsCharge() {
        assertEquals(2.5, ChargeKernel.addDealt(0.5, 2.0, 1.0, ChargeKernel.NEUTRAL_RATE), 1e-9);
    }

    @Test void damageTakenUsesLesserCoefficient() {
        assertEquals(0.99, ChargeKernel.addTaken(0.33, 2.0, 0.33, ChargeKernel.NEUTRAL_RATE), 1e-9);
    }

    @Test void killGrantsFlat() {
        assertEquals(20.0, ChargeKernel.addKill(0.0, 20.0, ChargeKernel.NEUTRAL_RATE), 1e-9);
    }

    @Test void naturalRegenIsPerTick() {
        assertEquals(0.01, ChargeKernel.naturalRegen(0.2), 1e-9);
    }

    @Test void chargeNeverExceedsHundred() {
        assertEquals(100.0, ChargeKernel.clamp(140.0), 1e-9);
        assertEquals(0.0, ChargeKernel.clamp(-5.0), 1e-9);
    }

    @Test void fullOnlyAtHundred() {
        assertTrue(ChargeKernel.isFull(100.0));
        assertFalse(ChargeKernel.isFull(99.99));
    }

    /**
     * Clamping is the kernel's job on <em>every</em> path, not just when a caller remembers to ask.
     * A gauge that can hold 103.7 would make {@code isFull} false forever and the ultimate
     * unreachable — the same silent, permanent loss of the mechanic that {@code UbState}'s codec
     * rejects on disk.
     */
    @Test void accumulationClampsAtTheCeiling() {
        assertEquals(100.0, ChargeKernel.addDealt(99.0, 5.0, 1.0, ChargeKernel.NEUTRAL_RATE), 1e-9);
        assertEquals(100.0, ChargeKernel.addKill(95.0, 20.0, ChargeKernel.NEUTRAL_RATE), 1e-9);
    }

    /** A drain must stop at the floor instead of going negative. */
    @Test void drainStopsAtZero() {
        assertEquals(0.0, ChargeKernel.addTaken(0.1, -5.0, 1.0, ChargeKernel.NEUTRAL_RATE), 1e-9);
        assertEquals(0.0, ChargeKernel.add(0.1, -0.5, ChargeKernel.NEUTRAL_RATE), 1e-9);
    }

    /**
     * {@code NaN} is the one value that makes every comparison false, so a gauge holding it would
     * neither fill nor reset. The kernel answers the floor instead of propagating it.
     */
    @Test void aNonFiniteGaugeCollapsesToTheFloor() {
        assertEquals(0.0, ChargeKernel.clamp(Double.NaN), 1e-9);
        assertEquals(100.0, ChargeKernel.clamp(Double.POSITIVE_INFINITY), 1e-9);
        assertEquals(0.0, ChargeKernel.clamp(Double.NEGATIVE_INFINITY), 1e-9);
    }

    /** `full` is an exact value, not a threshold — 99.9999 is not full and 100.0001 is not either. */
    @Test void fullIsAnExactValueNotAThreshold() {
        assertTrue(ChargeKernel.isFull(ChargeKernel.MAX_CHARGE));
        assertFalse(ChargeKernel.isFull(100.000001));
        assertFalse(ChargeKernel.isFull(99.999999));
    }

    /** One second's worth of trickle is exactly twenty ticks' worth: the division happens here once. */
    @Test void regenDividesByTwentyExactlyOnce() {
        assertEquals(1.0, ChargeKernel.naturalRegen(20.0), 1e-9);
        assertEquals(0.0, ChargeKernel.naturalRegen(0.0), 1e-9);
        assertEquals(ChargeKernel.naturalRegen(0.2) * 20.0, 0.2, 1e-9);
    }

    // ------------------------------------------------------------------ CHARGE_RATE_UP, all four sources

    /**
     * <b>「TP上昇」 multiplies all four charge sources</b> — damage dealt, damage taken, a kill, and the
     * passive trickle.
     *
     * <h2>Why this is the test the brief asked for</h2>
     * The four sources live in four different places ({@code CombatEvents.onDamageLanded} twice,
     * {@code CombatEvents.onDeath}, {@code PassiveCharge.onPlayerTick}) and only share one thing: they all
     * end in this kernel. So the property "every source honours the buff" is a property of the kernel, and it
     * is provable here without a server — which is exactly why the multiplier was put in the kernel rather
     * than in each source. A per-source implementation could not be asserted this way, and would be four
     * chances to forget one (the defect this test exists to catch).
     *
     * <h2>What each assertion pins</h2>
     * The tier-1 rate is {@code 1.25x} ({@link ChargeKernel#RATE_STEP}). Each shaped method is checked to pay
     * exactly {@code neutral × rate}, so a method that forgot to forward {@code rate} — the realistic bug —
     * fails here rather than in a live fight.
     */
    @Test void chargeRateUpReachesAllFourSources() {
        double rate = ChargeKernel.rateForAmplifier(0);      // tier 1
        assertEquals(1.25, rate, 1e-9, "tier 1 is +25%");

        // 1. damage dealt
        assertEquals(ChargeKernel.addDealt(0.0, 4.0, 1.0, ChargeKernel.NEUTRAL_RATE) * rate,
                ChargeKernel.addDealt(0.0, 4.0, 1.0, rate), 1e-9,
                "damage dealt must be multiplied by the rate");
        // 2. damage taken
        assertEquals(ChargeKernel.addTaken(0.0, 4.0, 1.0, ChargeKernel.NEUTRAL_RATE) * rate,
                ChargeKernel.addTaken(0.0, 4.0, 1.0, rate), 1e-9,
                "damage taken must be multiplied by the rate");
        // 3. a kill
        assertEquals(25.0, ChargeKernel.addKill(0.0, 20.0, rate), 1e-9,
                "a 20-point kill at 1.25x pays 25");
        // 4. the passive trickle, through the same primitive
        assertEquals(1.25, ChargeKernel.add(0.0, 1.0, rate), 1e-9,
                "the trickle's whole point, at 1.25x, is 1.25");
    }

    /**
     * The multiplier scales the <b>gain</b> and never the ceiling: a full bar stays full, and a rate that
     * would overshoot is clamped like any other credit.
     *
     * <p>This is the "加成作用于量，绝不作用于质" rule in the one place it could be broken: a kernel that
     * multiplied the <em>result</em> rather than the <em>delta</em> would move {@code MAX_CHARGE} and let the
     * gauge report 125.
     */
    @Test void theRateNeverRaisesTheCeiling() {
        assertEquals(ChargeKernel.MAX_CHARGE, ChargeKernel.add(99.0, 5.0, 2.25), 1e-9);
        assertEquals(ChargeKernel.MAX_CHARGE, ChargeKernel.addKill(95.0, 20.0, 2.25), 1e-9);
        assertTrue(ChargeKernel.add(50.0, 1.0, 2.25) <= ChargeKernel.MAX_CHARGE);
    }

    /**
     * A nonsense rate is the identity, never zero and never {@code NaN}.
     *
     * <p>A {@code 0} rate would silently switch charging <em>off</em> — the same class of silent loss the
     * clamp exists to prevent — and a {@code NaN} rate would make the gauge {@code NaN}, which compares false
     * against everything and so neither fills nor resets.
     */
    @Test void aNonsenseRateIsTheIdentity() {
        assertEquals(ChargeKernel.NEUTRAL_RATE, ChargeKernel.sanitizeRate(0.0), 1e-9);
        assertEquals(ChargeKernel.NEUTRAL_RATE, ChargeKernel.sanitizeRate(-2.0), 1e-9);
        assertEquals(ChargeKernel.NEUTRAL_RATE, ChargeKernel.sanitizeRate(Double.NaN), 1e-9);
        assertEquals(ChargeKernel.NEUTRAL_RATE, ChargeKernel.sanitizeRate(Double.POSITIVE_INFINITY), 1e-9);
        assertEquals(3.0, ChargeKernel.add(0.0, 3.0, Double.NaN), 1e-9, "a NaN rate pays the plain amount");
    }

    /**
     * The amplifier↔rate mapping is one function with one inverse, and it agrees with the tier table.
     *
     * <p>The agreement matters because there are two spellings of the same ladder: {@code EffectTiers}
     * declares {@code +25%} a tier (what an author picks) and this class owns the arithmetic (what the engine
     * does). If they ever disagree, a tier-3 hook would apply a bonus that is not what its text says.
     */
    @Test void theRateLadderAgreesWithTheTierTable() {
        assertEquals(com.astrum.pcrd.api.v1.EffectTiers.ruleOf(
                        com.astrum.pcrd.api.v1.EffectType.CHARGE_RATE_UP).stepPerTier(),
                ChargeKernel.RATE_STEP, 1e-12,
                "the tier table and the kernel must declare the same step");
        for (int tier = 1; tier <= 5; tier++) {
            assertEquals(1.0 + ChargeKernel.RATE_STEP * tier,
                    ChargeKernel.rateForAmplifier(tier - 1), 1e-9,
                    "tier " + tier + " must be 1 + 0.25 x " + tier);
            assertEquals(tier - 1, ChargeKernel.amplifierForRate(1.0 + ChargeKernel.RATE_STEP * tier),
                    "amplifierForRate must invert rateForAmplifier at tier " + tier);
        }
        // A negative amplifier is the weakest *rung*, not "no bonus": the same rule AttackAreaRules and
        // HasteRules state, so an effect instance that a pack gave a nonsense amplifier still behaves like
        // the buff it is. "No bonus" is what ChargeRates answers when the effect is ABSENT — a different
        // question, answered by NEUTRAL_RATE rather than by this function.
        assertEquals(1.25, ChargeKernel.rateForAmplifier(-5), 1e-9,
                "a negative amplifier must not make charge slower — that would be a debuff wearing the "
                        + "buff's name — and it must not be a no-op either");
        assertEquals(1.25, ChargeKernel.rateForAmplifier(0), 1e-9, "amplifier 0 is tier 1");
        assertEquals(2.25, ChargeKernel.rateForAmplifier(ChargeKernel.MAX_AMPLIFIER_LEVEL), 1e-9,
                "the top of the 5-rung ladder");
        assertEquals(2.25, ChargeKernel.rateForAmplifier(255), 1e-9,
                "a hostile amplifier clamps to the ladder's end, not to a 64x rate");
    }
}
