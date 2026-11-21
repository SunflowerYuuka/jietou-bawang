package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Every table row's arithmetic, and the split the two defence buffs depend on.</b>
 *
 * <h2>Why the maths is asserted per tier and not per formula</h2>
 * Each of these effects has a ladder the human fixed, so the number that matters is not "the code
 * multiplies by 0.12" but "tier 3 of the physical buff removes 36% of a 10-damage hit". Those are different
 * claims: the first passes for an implementation that uses the wrong rung. So every rung of every row is
 * checked against a concrete input here — a 20-health player for the heals, a 10-damage hit for the two
 * defence buffs, a 40-health boss for the percent damage, a 30-point hit for the charge rate.
 *
 * <h2>What cannot be asserted without a server</h2>
 * The <em>application</em>: no {@code MobEffectInstance} and no {@code ServerPlayer} exist in a unit test, so
 * "the armour attribute really moved" and "the ally really gained health" stay unasserted. What is asserted is
 * every rule that decides <em>who</em> and <em>how much</em> — which is where this project's defects have
 * actually been — through the same pure seams {@code AttackBuffsTest} uses.
 */
class TieredEffectMathsTest {

    private static final UUID CASTER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID NEAR = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID FAR = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static List<AllySelector.Candidate> party() {
        return List.of(
                new AllySelector.Candidate(CASTER, true, java.util.Optional.empty(), 0.0),
                new AllySelector.Candidate(NEAR, false, java.util.Optional.empty(), 3.0),
                new AllySelector.Candidate(FAR, false, java.util.Optional.empty(), 40.0));
    }

    private static List<UUID> ids(List<AllySelector.Candidate> candidates) {
        return candidates.stream().map(AllySelector.Candidate::id).toList();
    }

    /** Runs one tier through the same resolution path an executor uses, so the clamp is included. */
    private static TieredAmounts.Amount resolve(EffectType type, int tier) {
        return TieredAmounts.resolve(type, TieredParams.ofTier(tier));
    }

    // ------------------------------------------------------------------ the heals: 20N% of max health

    /**
     * <b>{@code HEAL_AREA} and {@code HEAL_SELF} restore 20/40/60/80/100% of the receiver's own max health.</b>
     *
     * <p>A 20-health player is used because that is the vanilla player's bar, and the point of a percentage
     * heal is that the same rung means twice as much on a 40-health receiver. Tier 5 is a full heal on both,
     * which the brief intends.
     */
    @Test
    void theHealsRestoreTwentyPercentOfMaxHealthARung() {
        assertEquals(4.0F, PercentHeals.amountFor(20.0F, resolve(EffectType.HEAL_AREA, 1).fraction()), 1e-5);
        assertEquals(8.0F, PercentHeals.amountFor(20.0F, resolve(EffectType.HEAL_AREA, 2).fraction()), 1e-5);
        assertEquals(12.0F, PercentHeals.amountFor(20.0F, resolve(EffectType.HEAL_AREA, 3).fraction()), 1e-5);
        assertEquals(16.0F, PercentHeals.amountFor(20.0F, resolve(EffectType.HEAL_AREA, 4).fraction()), 1e-5);
        assertEquals(20.0F, PercentHeals.amountFor(20.0F, resolve(EffectType.HEAL_AREA, 5).fraction()), 1e-5,
                "tier 5 is a full heal, exactly, not one point short");

        // The same ladder for the self-heal, because it is the same table row.
        for (int tier = 1; tier <= 5; tier++) {
            assertEquals(PercentHeals.amountFor(20.0F, resolve(EffectType.HEAL_AREA, tier).fraction()),
                    PercentHeals.amountFor(20.0F, resolve(EffectType.HEAL_SELF, tier).fraction()), 1e-6,
                    "the two heals share one ladder, so tier " + tier + " must pay identically");
        }

        // And it scales with the receiver, which is the whole reason it is a percentage.
        assertEquals(50.0F, PercentHeals.amountFor(100.0F, 0.50), 1e-4,
                "half of a 100-health boss is 50, which no fixed amount could be");
    }

    /** A degenerate max health or fraction heals nothing rather than dividing by zero or poisoning health. */
    @Test
    void aDegenerateHealComputesNothing() {
        assertEquals(0.0F, PercentHeals.amountFor(0.0F, 0.5), 1e-6, "a broken attribute must not divide");
        assertEquals(0.0F, PercentHeals.amountFor(-5.0F, 0.5), 1e-6);
        assertEquals(0.0F, PercentHeals.amountFor(Float.NaN, 0.5), 1e-6);
        assertEquals(0.0F, PercentHeals.amountFor(20.0F, Double.NaN), 1e-6,
                "healing for NaN would set health to NaN and every later comparison would be false");
        assertEquals(0.0F, PercentHeals.amountFor(20.0F, -1.0), 1e-6, "a negative heal must not damage");
        assertEquals(20.0F, PercentHeals.amountFor(20.0F, 5.0), 1e-5, "an over-100% fraction caps at max");
    }

    /**
     * <b>The party heal reaches the caster and every ally inside the radius — and nobody outside it.</b>
     *
     * <p>This is the assertion the brief asks to be proved on a server, moved to where it can be proved
     * without one: the receiver set is the whole rule. The ally at 40 blocks is outside an 8-block radius and
     * must be absent, not merely sorted last.
     */
    @Test
    void theAreaHealReachesExactlyTheAlliesInsideTheRadius() {
        assertEquals(List.of(CASTER, NEAR),
                ids(PercentHeals.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, 8.0)),
                "the caster and the ally at 3 blocks, never the one at 40");
        assertEquals(List.of(CASTER), ids(PercentHeals.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, 0.0)),
                "radius 0 is 「自分」: the caster alone");
        assertEquals(List.of(CASTER, NEAR, FAR),
                ids(PercentHeals.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, 100.0)),
                "a radius that covers everyone reaches everyone");
        assertEquals(List.of(CASTER, NEAR),
                ids(PercentHeals.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, 3.0)),
                "the boundary is inclusive: an ally exactly at the radius is inside it");
    }

    /** A null or nonsense input answers an empty set rather than throwing, because this runs on a hit. */
    @Test
    void theAreaHealDegradesSafely() {
        assertEquals(List.of(), PercentHeals.receivers(null, CASTER, AllyMode.ALL_PLAYERS, 8.0));
        assertEquals(List.of(), PercentHeals.receivers(party(), null, AllyMode.ALL_PLAYERS, 8.0));
        assertEquals(List.of(), PercentHeals.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, Double.NaN));
        assertEquals(0, PercentHeals.applyArea(null, 0.5, null, OptionalDouble.empty()));
        assertEquals(0, PercentHeals.applySelf(null, 0.5, null));
    }

    // ------------------------------------------------------------------ the two defence buffs

    /**
     * <b>{@code BUFF_PHYS_DEFENCE_AREA} and {@code BUFF_MAGIC_DEFENCE_AREA} reduce damage by 12/24/36/48/60%.</b>
     *
     * <p>Worked on a 10-damage hit, because the human's ceiling has to be visible as a number: tier 5 turns
     * 10.0 into 4.0, not into 0.0. A 100% reduction would be immunity — what vanilla's {@code resistance}
     * does at level V — and it is the reason this pair is 12% a rung instead of 20%.
     */
    @Test
    void theDefenceBuffsReduceTwelvePercentARung() {
        for (EffectType type : List.of(EffectType.BUFF_PHYS_DEFENCE_AREA,
                EffectType.BUFF_MAGIC_DEFENCE_AREA)) {
            assertEquals(8.8F, DamageReduction.apply(10.0F, resolve(type, 1).fraction()), 1e-4, type + " tier 1");
            assertEquals(7.6F, DamageReduction.apply(10.0F, resolve(type, 2).fraction()), 1e-4, type + " tier 2");
            assertEquals(6.4F, DamageReduction.apply(10.0F, resolve(type, 3).fraction()), 1e-4, type + " tier 3");
            assertEquals(5.2F, DamageReduction.apply(10.0F, resolve(type, 4).fraction()), 1e-4, type + " tier 4");
            assertEquals(4.0F, DamageReduction.apply(10.0F, resolve(type, 5).fraction()), 1e-4,
                    type + " tier 5 must be 60% off, i.e. 4.0 of 10.0 and not 0.0");
        }
    }

    /**
     * The reduction is <b>capped at the documented ceiling</b> even if a caller hands it more.
     *
     * <p>{@link DamageReduction#apply} is public and takes a raw fraction, so a future caller could pass 1.5.
     * Answering 0.0 for that would be a free hit; the cap makes the buff's own ceiling the last word, which is
     * the same "the quantity is clamped where the rule lives" shape the rest of the project uses.
     */
    @Test
    void aReductionAboveTheCeilingIsCapped() {
        assertEquals(4.0F, DamageReduction.apply(10.0F, 1.5), 1e-4, "150% must still be the 60% ceiling");
        assertEquals(10.0F, DamageReduction.apply(10.0F, 0.0), 1e-6);
        assertEquals(10.0F, DamageReduction.apply(10.0F, -0.5), 1e-6,
                "a negative reduction must not become a vulnerability");
        assertEquals(10.0F, DamageReduction.apply(10.0F, Double.NaN), 1e-6);
        assertEquals(0.0F, DamageReduction.apply(0.0F, 0.5), 1e-6);
    }

    /**
     * <b>The physical and magic halves are separate ladders over the same numbers, and the split is
     * {@code DamageReduction.isMagic}'s.</b>
     *
     * <p>Minecraft ships <b>no</b> {@code is_magic} damage-type tag — verified against {@code DamageTypeTags}
     * in 21.1.265, which has {@code is_fire}, {@code is_projectile}, {@code is_explosion}, {@code is_freezing},
     * {@code is_lightning}, {@code is_fall} and {@code is_drowning} and nothing for magic. So the marker is
     * this mod's own: the damage type {@code astrum:magic}, or membership of the tag {@code astrum:is_magic}.
     *
     * <p>A null source is <b>physical</b>: "unknown" must not exempt a hit from the physical buff by being
     * treated as magical, which would be a silent hole in the buff that covers everything else.
     */
    @Test
    void anUnattributableSourceIsPhysicalNotMagic() {
        assertFalse(DamageReduction.isMagic(null),
                "an unknown damage type is physical, or it would silently bypass the physical buff");
        assertEquals(0.0, DamageReduction.reductionFor(null, null), 1e-9, "no victim, no reduction");
    }

    /** The two registry paths the markers live at, spelled the way the data files spell them. */
    @Test
    void theMagicMarkerPathsAreTheOnesTheDataShips() {
        assertEquals("magic", DefenceRules.MAGIC_DAMAGE_PATH);
        assertEquals("is_magic", DefenceRules.MAGIC_TAG_PATH);
        assertEquals("astrum:magic", DamageReduction.MAGIC_TYPE.toString());
        assertEquals("astrum:is_magic", DamageReduction.MAGIC_TAG.location().toString());
        // The buffs' own registry paths, which are also their HUD sprite names.
        assertEquals("phys_defence", DefenceRules.PHYS_PATH);
        assertEquals("magic_defence", DefenceRules.MAGIC_PATH);
        assertEquals(DefenceRules.Kind.PHYSICAL, DefenceRules.kindOf("phys_defence"));
        assertEquals(DefenceRules.Kind.MAGIC, DefenceRules.kindOf("magic_defence"));
        assertEquals(DefenceRules.Kind.PHYSICAL, DefenceRules.kindOf("anything_else"),
                "an unknown path is physical, the same default isMagic takes");
    }

    /** The ladder encodings agree with the tier table's counts, so a rung cannot exist in one and not the other. */
    @Test
    void thePerEffectLaddersAgreeWithTheTierTable() {
        assertEquals(EffectTiers.maxTier(EffectType.BUFF_PHYS_DEFENCE_AREA), DefenceRules.MAX_TIER);
        assertEquals(EffectTiers.maxTier(EffectType.BUFF_MAGIC_DEFENCE_AREA), DefenceRules.MAX_TIER);
        assertEquals(EffectTiers.ruleOf(EffectType.BUFF_PHYS_DEFENCE_AREA).stepPerTier(),
                DefenceRules.STEP, 1e-12);
        assertEquals(EffectTiers.maxTier(EffectType.BUFF_ATTACK_AREA), AttackAreaRules.MAX_TIER);
        assertEquals(EffectTiers.ruleOf(EffectType.BUFF_ATTACK_AREA).stepPerTier(),
                AttackAreaRules.STEP, 1e-12);
        assertEquals(EffectTiers.maxTier(EffectType.HASTE), HasteRules.MAX_TIER);
        assertEquals(EffectTiers.maxTier(EffectType.DEFENCE_DOWN), DefenceDownRules.MAX_TIER);
        assertEquals(EffectTiers.maxTier(EffectType.PANIC), PanicRules.MAX_TIER);
    }

    // ------------------------------------------------------------------ the attack buff

    /**
     * <b>{@code BUFF_ATTACK_AREA} adds 20/40/60/80/100% attack damage as an {@code ADD_MULTIPLIED_TOTAL}.</b>
     *
     * <p>Worked on a 7-damage sword: tier 5 doubles it to 14, which the brief intends, and tier 1 makes it 8.4.
     * The modifier's operation is asserted separately, in {@code AttackAreaEffect}'s registration — what is
     * checked here is the fraction the amplifier stands for.
     */
    @Test
    void theAttackBuffAddsTwentyPercentARung() {
        for (int tier = 1; tier <= 5; tier++) {
            double fraction = resolve(EffectType.BUFF_ATTACK_AREA, tier).fraction();
            assertEquals(0.20 * tier, fraction, 1e-12, "tier " + tier);
            // ADD_MULTIPLIED_TOTAL means the final damage is base x (1 + fraction).
            assertEquals(7.0 * (1.0 + fraction), 7.0 * (1.0 + 0.20 * tier), 1e-9,
                    "tier " + tier + " on a 7-damage sword");
        }
        assertEquals(14.0, 7.0 * (1.0 + resolve(EffectType.BUFF_ATTACK_AREA, 5).fraction()), 1e-9,
                "tier 5 doubles the damage, exactly");
        // The amplifier encoding is 0-based and exact, with no rounding to blur two rungs together.
        for (int tier = 1; tier <= 5; tier++) {
            assertEquals(tier - 1, AttackAreaRules.amplifierForTier(tier));
            assertEquals(0.20 * tier, AttackAreaRules.fractionForAmplifier(tier - 1), 1e-12);
        }
        assertEquals(0.20, AttackAreaRules.fractionForAmplifier(-5), 1e-12,
                "a negative amplifier must not become an attack debuff on a beneficial effect");
        assertEquals(1.00, AttackAreaRules.fractionForAmplifier(255), 1e-12, "the ceiling clamps");
    }

    // ------------------------------------------------------------------ percent damage

    /**
     * <b>{@code PERCENT_DAMAGE} deals 10/20/30/40/50% of the victim's max health</b>, reusing the heal's
     * percentage arithmetic because it is the same sum on the same number.
     *
     * <p>A 150-health boss is the case the effect exists for: 15 damage at tier 1, where a flat
     * {@code extra_damage} would be a scratch.
     */
    @Test
    void percentDamageIsTenPercentOfMaxHealthARung() {
        for (int tier = 1; tier <= 5; tier++) {
            assertEquals(15.0F * tier, PercentHeals.amountFor(150.0F, resolve(EffectType.PERCENT_DAMAGE, tier).fraction()),
                    1e-4, "tier " + tier + " of a 150-health boss is " + (15 * tier));
        }
        assertEquals(5.0F, PercentHeals.amountFor(10.0F, resolve(EffectType.PERCENT_DAMAGE, 5).fraction()), 1e-4,
                "tier 5 removes 50% of a 10-health target, i.e. 5.0");
        assertEquals(0.5F, PercentHeals.amountFor(5.0F, resolve(EffectType.PERCENT_DAMAGE, 1).fraction()), 1e-4);
    }

    // ------------------------------------------------------------------ haste

    /**
     * <b>{@code HASTE} is 3 rungs of +20% on <em>both</em> movement and attack speed.</b>
     *
     * <p>The step is 20% because it is the same step vanilla's {@code speed} uses, so a tier-1 haste moves the
     * party like {@code speed I}. What vanilla's effect cannot do is the second half — {@code MobEffects.SPEED}
     * carries one modifier on {@code MOVEMENT_SPEED} and none on {@code ATTACK_SPEED} — so this mod's own two
     * modifiers carry the tier together.
     */
    @Test
    void hasteAddsTwentyPercentToBothAttributesARung() {
        assertEquals(0.20, HasteRules.fractionForTier(1), 1e-12);
        assertEquals(0.40, HasteRules.fractionForTier(2), 1e-12);
        assertEquals(0.60, HasteRules.fractionForTier(3), 1e-12);
        assertEquals(0.60, HasteRules.fractionForTier(4), 1e-12, "a fourth tier clamps: haste has three");
        assertEquals(0.20, HasteRules.fractionForTier(0), 1e-12, "a zero tier is the weakest rung, not nothing");
        for (int tier = 1; tier <= HasteRules.MAX_TIER; tier++) {
            assertEquals(tier - 1, HasteRules.amplifierForTier(tier));
            assertEquals(HasteRules.fractionForTier(tier), HasteRules.fractionForAmplifier(tier - 1), 1e-12,
                    "the two entry points must be one function");
        }
    }

    // ------------------------------------------------------------------ the shield break

    /**
     * <b>{@code REMOVE_SHIELD} removes 100% of the target's absorption</b>, and its one rung is why the
     * arithmetic still takes a fraction: a future second rung would then be a data change.
     */
    @Test
    void theShieldBreakRemovesAllOfTheAbsorption() {
        assertEquals(8.0F, ShieldBreak.amountToRemove(8.0F, resolve(EffectType.REMOVE_SHIELD, 1).fraction()),
                1e-6, "tier 1 removes the whole pool");
        assertEquals(0.0F, ShieldBreak.amountToRemove(0.0F, 1.0), 1e-6, "no shield, nothing to remove");
        assertEquals(0.0F, ShieldBreak.amountToRemove(8.0F, 0.0), 1e-6);
        assertEquals(0.0F, ShieldBreak.amountToRemove(8.0F, Double.NaN), 1e-6);
        assertEquals(8.0F, ShieldBreak.amountToRemove(8.0F, 2.0), 1e-6, "an over-100% fraction caps at the pool");
        // A declared tier 3 clamps to the single rung rather than inventing two more.
        assertEquals(1, resolve(EffectType.REMOVE_SHIELD, 3).tier());
        assertEquals(1.0, resolve(EffectType.REMOVE_SHIELD, 3).fraction(), 1e-12);
    }

    // ------------------------------------------------------------------ the charge rate

    /**
     * <b>{@code CHARGE_RATE_UP} is +25/50/75/100/125%, i.e. a multiplier of 1.25× to 2.25×.</b>
     *
     * <p>Worked on a 20-point damage credit, so the top rung is visibly 45 points rather than "some bonus".
     * The multiplication itself happens inside {@code ChargeKernel}, which is what makes all four charge
     * sources honour it; {@code ChargeKernelTest} proves that half.
     */
    @Test
    void theChargeRateRaisesTheMultiplierAQuarterARung() {
        for (int tier = 1; tier <= 5; tier++) {
            double bonus = resolve(EffectType.CHARGE_RATE_UP, tier).fraction();
            assertEquals(0.25 * tier, bonus, 1e-12, "tier " + tier);
            double multiplier = 1.0 + bonus;
            assertEquals(1.0 + 0.25 * tier, multiplier, 1e-12);
            assertEquals(20.0 * multiplier, 20.0 * (1.0 + 0.25 * tier), 1e-9,
                    "a 20-point credit at tier " + tier);
        }
        assertEquals(2.25, 1.0 + resolve(EffectType.CHARGE_RATE_UP, 5).fraction(), 1e-12, "the top rung is 2.25x");
        // The amplifier the executor applies is the kernel's inverse, and it is 0-based.
        for (int tier = 1; tier <= 5; tier++) {
            int amplifier = com.astrum.pcrd.impl.ub.ChargeKernel.amplifierForRate(
                    1.0 + resolve(EffectType.CHARGE_RATE_UP, tier).fraction());
            assertEquals(tier - 1, amplifier, "tier " + tier + " must encode as amplifier " + (tier - 1));
        }
    }

    // ------------------------------------------------------------------ the resolution seam

    /**
     * The tier that reaches an executor is the clamped one, and a clamp is reported once per effect type.
     *
     * <p>Asserted through {@link TieredAmounts} rather than {@link EffectTiers} because that is the function
     * the executors call: it is what turns "the pack said 9" into a legal rung and a number, and a test on the
     * table alone would not notice it resolving the wrong type's ladder.
     */
    @Test
    void theExecutorSeamClampsAndStillProducesANumber() {
        assertEquals(4, resolve(EffectType.DEFENCE_DOWN, 9).tier(), "the armour break's ceiling is 4");
        assertEquals(0.80, resolve(EffectType.DEFENCE_DOWN, 9).fraction(), 1e-12,
                "and it is worth 80%, not 180%");
        assertEquals(1, resolve(EffectType.PANIC, 5).tier());
        assertEquals(1, TieredAmounts.tierOf(EffectType.PANIC, TieredParams.ofTier(0)));
        assertTrue(resolve(EffectType.HEAL_AREA, 1).fraction() > 0.0,
                "the weakest rung must still do something, or the skill silently does nothing");
    }
}
