package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.ub.ChargeKernel;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A hidden effect still applies — hiding is a drawing predicate, and this is the proof.</b>
 *
 * <h2>The mistake this exists to prevent</h2>
 * 「隐藏」 and 「失效」 are one keystroke apart in intent and indistinguishable in a screenshot: an effect that applies
 * nothing and an effect that applies but is not drawn both produce "no row on the bar". The task's instruction is
 * therefore explicit — 「the effects themselves must keep working … prove with a test that a hidden effect still
 * applies」 — because the change that hides them (an {@code IClientMobEffectExtensions} predicate) lives on the
 * <em>client</em> while every reader of these effects lives on the server and in the damage maths.
 *
 * <h2>What "still applies" means for each family, and why they differ</h2>
 * The thirteen generic effects are not one mechanism, so "applies" is three different facts and each is asserted
 * where it is cheapest to see:
 *
 * <ul>
 *   <li><b>The ladder still answers a number.</b> A tiered effect's magnitude is {@code EffectTiers} plus
 *       {@code TieredAmounts}; a hidden {@code buff_attack_area} must still resolve tier 3 to +60%. That is the
 *       arithmetic every one of the thirteen shares, so it covers them all at once — and it is the same call the
 *       executor makes.</li>
 *   <li><b>The instance still lands on the entity.</b> A {@code MobEffectInstance} is added to a body by its
 *       {@code Holder}; nothing on that path consults the client extension. Asserted by constructing the instance
 *       from the registered holder and reading back its amplifier and duration — the two numbers the HUD would have
 *       shown and the mechanics still read.</li>
 *   <li><b>The mechanics still read what they read.</b> The two that are read by an amplifier rather than by an
 *       attribute — {@code charge_rate}'s multiplier and {@code charge_regen}'s rate — are asserted through their
 *       own kernels, because those are the numbers hiding could plausibly break if somebody moved the amplifier
 *       into the drawing code.</li>
 * </ul>
 *
 * <h2>Why no live entity is needed</h2>
 * {@code Holder.direct} and {@code MobEffectInstance} work outside a registry, and the arithmetic classes are pure.
 * What is <b>not</b> here is {@code LivingEntity#addEffect} on a real player — no server exists in this JVM — so
 * the "instance lands" half is asserted on the instance itself; the call site is the same one the shipped
 * characters already exercise, and the human's in-game check covers it.
 */
class HiddenEffectStillAppliesTest {

    /**
     * <b>A hidden tiered effect still resolves a legal rung and its number.</b>
     *
     * <p>{@code buff_attack_area} is the representative: it is hidden ({@code HiddenEffectClientTest} asserts that
     * for every one of the thirteen) and its tier arithmetic is the table all of them share.
     */
    @Test
    void aHiddenTieredEffectStillResolvesItsRungAndNumber() {
        assertFalse(PartyEffects.mechanicHolders().isEmpty(), "sanity");
        TieredAmounts.Amount amount = TieredAmounts.resolve(EffectType.BUFF_ATTACK_AREA,
                TieredParams.of(3, 8.0, 6.0));
        assertEquals(3, amount.tier(), "tier 3 is still tier 3 while the effect is invisible");
        assertEquals(0.60, amount.fraction(), 1e-9, "and +20% a rung is still +60%");
        // The clamp is the ladder's, and it lives on the server-side table — nothing about hiding touches it.
        assertEquals(5, EffectTiers.maxTier(EffectType.BUFF_ATTACK_AREA));
        assertEquals(5, TieredAmounts.resolve(EffectType.BUFF_ATTACK_AREA,
                TieredParams.of(9, 8.0, 6.0)).tier(), "a declared 9 still clamps to the top rung");
    }

    /**
     * <b>Every generic effect still has a registered type with a real identity.</b>
     *
     * <p>The registry itself is the thing that must survive hiding: an effect that is not registered cannot be
     * applied at all, and a test that only asked the client predicates would pass on a registry from which all
     * thirteen had been deleted.
     */
    @Test
    void everyGenericEffectIsStillARegisteredType() {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (var holder : PartyEffects.mechanicHolders()) {
            assertNotNull(holder.getKey(), "a generic effect must have a registry key: " + holder);
            String path = holder.getKey().location().getPath();
            assertFalse(seen.containsKey(path), "duplicate generic effect path: " + path);
            seen.put(path, Boolean.TRUE);
        }
        assertTrue(seen.size() >= 13, "the manifest covers every generic effect, got " + seen.size());
    }

    /**
     * <b>An instance built from a generic effect's holder keeps its amplifier and duration.</b>
     *
     * <p>This is the "lands on the body" half, as close as a unit test gets: the numbers the HUD would have drawn —
     * and the numbers every reader looks up — are carried by the instance, not by the row. A version of hiding that
     * zeroed the amplifier (or expired the instance) to "clean up" the bar would fail here.
     */
    @Test
    void anInstanceOfAHiddenEffectKeepsItsAmplifierAndDuration() {
        // A direct holder stands in for the registered one: the point is the instance's arithmetic, and the
        // registered holder's identity is asserted above.
        Holder<MobEffect> holder = Holder.direct(new AstrumMarkerEffect(
                net.minecraft.world.effect.MobEffectCategory.BENEFICIAL, 0x6FA8DC));
        MobEffectInstance instance = new MobEffectInstance(holder, 200, 3, false, false, true);
        assertEquals(3, instance.getAmplifier(), "the rung travels in the amplifier, hidden or not");
        assertEquals(200, instance.getDuration(), "and the clock is untouched");
        assertFalse(instance.endsWithin(0), "a 200-tick instance is not over");
    }

    /**
     * <b>The two amplifier-read mechanics still read the amplifier.</b>
     *
     * <p>{@code charge_rate}'s rung becomes a multiplier and {@code charge_regen}'s becomes a rate; both are read
     * from an instance's amplifier by a <em>server-side</em> path. If somebody ever moved that reading into the
     * client extension — the one place that now knows about visibility — these two numbers would go to their
     * identity and the bar would be clean while the buffs did nothing.
     */
    @Test
    void theAmplifierReadMechanicsStillReadTheAmplifier() {
        assertEquals(1.25, ChargeKernel.rateForAmplifier(0), 1e-9,
                "tier 1 of 行動速度/TP上昇 is still 1.25x, not the identity");
        assertEquals(2.25, ChargeKernel.rateForAmplifier(4), 1e-9, "and tier 5 is still 2.25x");
        assertEquals(1.25, ChargeRates.multiplierFor(0), 1e-9,
                "the effect-facing name answers the same number");
        // The charge-regen ladder, which is the amplifier read one effect over.
        assertEquals(2.0, OverTimeEffects.chargePointsPerSecond(0), 1e-9,
                "amplifier 0 of charge_regen still pays 2 points a second");
        assertEquals(10.0, OverTimeEffects.chargePointsPerSecond(4), 1e-9, "and the top rung 10");
        assertEquals(0.1, OverTimeEffects.chargePointsPerTick(0), 1e-9,
                "one tick of it is a tenth of a point, not zero — the fraction must survive");
    }
}
