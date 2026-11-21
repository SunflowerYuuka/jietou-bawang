package com.astrum.pcrd.impl.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>召唤精灵</b> as arithmetic: the rung→count mapping, the particle budget, and the orbit.
 *
 * <h2>Why the count is asserted rather than the particles</h2>
 * A unit test cannot watch a particle packet leave a server, so what is asserted is the number that decides
 * the whole feature: <b>how many spirits a rung buys</b>. Everything downstream — the per-tick budget, the
 * angular spacing, the fact that the ring is stateless — is derived from that one number, and each is a pure
 * function so it can be pinned here.
 *
 * <h2>The one property worth stating twice</h2>
 * <b>The amplifier is the count.</b> That is the human's own instruction (「如果有计数要求就用等级来计数」) and it
 * is what makes a summon expressible as a status effect at all: there is no other per-instance field a
 * {@code MobEffect} carries. {@code theAmplifierIsTheCount} pins the round trip in both directions, because
 * an off-by-one here would draw one spirit fewer or one more than the tooltip promises.
 */
class SpiritsTest {

    /** The rung is the count: tier 1..5 is one..five spirits, and the ladder is {@code EffectTiers}'s. */
    @Test
    void theRungIsTheNumberOfSpirits() {
        assertEquals(1, Spirits.countForTier(1));
        assertEquals(2, Spirits.countForTier(2));
        assertEquals(3, Spirits.countForTier(3));
        assertEquals(4, Spirits.countForTier(4));
        assertEquals(5, Spirits.countForTier(5));
        assertEquals(1, Spirits.countForTier(0), "there is no tier 0; the nearest legal rung is used");
        assertEquals(5, Spirits.countForTier(99), "there is no sixth spirit");
    }

    /** {@code amplifier = tier - 1}, and reading it back gives the same count. */
    @Test
    void theAmplifierIsTheCount() {
        for (int tier = 1; tier <= 5; tier++) {
            int amplifier = Spirits.amplifierForTier(tier);
            assertEquals(tier - 1, amplifier, "amplifier 0 is one spirit, so tier N is amplifier N-1");
            assertEquals(tier, Spirits.countForAmplifier(amplifier),
                    "the round trip must not lose a spirit at tier " + tier);
        }
    }

    /**
     * A hand-written {@code /effect give ... 200} cannot summon a hundred spirits.
     *
     * <p>The amplifier is a byte, so a player or a datapack can put any value in it. The count is clamped to
     * the ladder's top rung, which is what keeps "how many particles does this cost" bounded by something the
     * table says rather than by whatever an operator typed.
     */
    @Test
    void anAbsurdAmplifierClampsToTheTopRung() {
        assertEquals(5, Spirits.countForAmplifier(200));
        assertEquals(5, Spirits.countForAmplifier(5));
        assertEquals(0, Spirits.countForAmplifier(-1), "no amplifier, no spirits");
    }

    /**
     * <b>The particle budget: one particle per spirit per tick.</b>
     *
     * <p>This is the number the report has to state, so it is a test rather than a comment: tier 1 costs one
     * particle a tick and tier 5 costs five, i.e. 20 and 100 a second. Four party members at tier 5 is
     * twenty particles a tick in total, which is the figure the "is this affordable to leave on" question is
     * answered against.
     */
    @Test
    void oneParticlePerSpiritPerTick() {
        assertEquals(1, Spirits.PARTICLES_PER_SPIRIT_PER_TICK);
        assertEquals(0, Spirits.budgetPerTick(0));
        assertEquals(1, Spirits.budgetPerTick(1));
        assertEquals(5, Spirits.budgetPerTick(5));
        assertEquals(5, Spirits.budgetPerTick(Spirits.countForTier(5)), "the top rung costs five a tick");
        assertTrue(Spirits.budgetPerTick(Spirits.countForTier(5)) * 20 <= 100,
                "tier 5 must stay at or under a hundred particles a second");
    }

    /**
     * The ring is evenly spaced, and a full turn takes about 4.4 s.
     *
     * <p>Spirit {@code i} of {@code n} sits at {@code 2πi/n}, so three spirits are 120° apart and no two share
     * a position. The speed is stated as a constant and checked as a period, which is the number a reader
     * actually cares about: an orbit you cannot see move is a static ring, and one that spins is a washing
     * machine.
     */
    @Test
    void theRingIsEvenlySpacedAndTurnsSlowly() {
        assertEquals(0.0, Spirits.angleAt(0L, 0, 3), 1e-9);
        assertEquals(Math.PI * 2.0 / 3.0, Spirits.angleAt(0L, 1, 3), 1e-9);
        assertEquals(Math.PI * 4.0 / 3.0, Spirits.angleAt(0L, 2, 3), 1e-9);
        // index wraps, so a group of three asked about spirit 4 draws spirit 1 and the loop cannot run off.
        assertEquals(Spirits.angleAt(0L, 1, 3), Spirits.angleAt(0L, 4, 3), 1e-9);
        assertEquals(0.0, Spirits.angleAt(17L, 0, 0), 1e-9, "no spirits, no division by zero");

        double period = (Math.PI * 2.0) / Spirits.ANGULAR_SPEED;
        assertTrue(period > 60.0 && period < 120.0,
                "one full orbit must be a slow hover, not a spin: " + period + " ticks");
    }

    /**
     * Half the spirits bob against the other half, so the ring never pulses as one.
     *
     * <p>And the offsets cancel: at any tick the even spirits are up by exactly what the odd ones are down.
     * That is what stops the summon from looking like it is bobbing on a spring.
     */
    @Test
    void theBobIsBalancedAndBounded() {
        for (long tick = 0; tick < 60; tick++) {
            assertEquals(0.0, Spirits.bobAt(tick, 0) + Spirits.bobAt(tick, 1), 1e-9,
                    "even and odd bob against each other at tick " + tick);
            assertTrue(Math.abs(Spirits.bobAt(tick, 0)) <= Spirits.BOB + 1e-9,
                    "the bob never exceeds its own amplitude");
        }
    }

    /** The registry path and the id are the ones the task names, and the orbit is a radius, not a range. */
    @Test
    void theRegistryPathIsSpirit() {
        assertEquals("spirit", Spirits.PATH);
        assertEquals("astrum:spirit", Spirits.ID.toString());
        assertTrue(Spirits.RADIUS > 0.5 && Spirits.RADIUS < 3.0,
                "an orbit of " + Spirits.RADIUS + " blocks is close enough to read and far enough not to "
                        + "clip through the holder");
        assertTrue(Spirits.HEIGHT_FRACTION > 0.0 && Spirits.HEIGHT_FRACTION < 1.0,
                "the ring sits somewhere on the body, not on the ground or above the head");
    }
}
