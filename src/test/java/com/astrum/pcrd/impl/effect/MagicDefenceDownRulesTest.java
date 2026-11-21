package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「魔法防御力をダウン」 — the magic counterpart of {@code DEFENCE_DOWN}, tier by tier.
 *
 * <h2>The requirement, and what it forbids</h2>
 * 「MAGIC_DEFENCE_DOWN — the magic counterpart of the existing DEFENCE_DOWN. Reads/writes the armour attribute
 * equivalent for magic. Since Minecraft has one Attributes.ARMOR, use the existing astrum:magic damage marker:
 * our magic-defence-down must reduce magic damage taken (mirror of BUFF_MAGIC_DEFENCE_AREA), not touch armour.
 * Tiered 4 @ 20%, same ladder as DEFENCE_DOWN.」
 *
 * <p>So there are three things to assert, and the third is the one a reader would otherwise have to trust:
 * <ol>
 *   <li>the ladder is <b>four rungs of 20%</b>, and it is the <em>same</em> ladder object as
 *       {@code DEFENCE_DOWN}'s;</li>
 *   <li>the arithmetic produces <b>+20%..+80% magic damage</b> and never a reduction;</li>
 *   <li>it <b>does not touch armour</b> — asserted by construction: {@code MagicDefenceDownEffect} applies no
 *       attribute modifier at all, and the penalty is read on the damage path.</li>
 * </ol>
 *
 * <h2>What cannot be asserted here</h2>
 * The damage path itself ({@code DamageReduction.magicPenaltyFor} reading an instance) needs an entity, so
 * the <em>classification</em> half is asserted where it already lives: {@code DamageReduction.isMagic},
 * pinned by {@code TieredEffectMathsTest}. This file owns the numbers.
 */
class MagicDefenceDownRulesTest {

    /** The human's numbers: four rungs, twenty percent each. */
    @Test
    void theLadderIsFourRungsOfTwentyPercent() {
        assertEquals(4, MagicDefenceDownRules.MAX_TIER);
        assertEquals(0.20, MagicDefenceDownRules.STEP, 1e-12);
        assertEquals(0.80, MagicDefenceDownRules.MAX_PENALTY, 1e-12);
        for (int tier = 1; tier <= 4; tier++) {
            assertEquals(0.20 * tier, MagicDefenceDownRules.penaltyForTier(tier), 1e-12, "tier " + tier);
        }
    }

    /**
     * <b>The same ladder as {@code DEFENCE_DOWN}, asserted against the shared table.</b>
     *
     * <p>The human's words are 「same ladder as DEFENCE_DOWN」, and {@code EffectTiers} is the one table. So
     * this compares the two {@code Rule}s rather than re-typing 4 and 0.20 — a re-typed pair is a second copy
     * of a rule, and it is exactly the copy that drifts.
     */
    @Test
    void theSharedTierTableGivesBothDebuffsTheSameLadder() {
        assertEquals(EffectTiers.ruleOf(EffectType.DEFENCE_DOWN),
                EffectTiers.ruleOf(EffectType.MAGIC_DEFENCE_DOWN),
                "the two debuffs must share one Rule, not two records that happen to agree today");
        assertEquals(4, EffectTiers.maxTier(EffectType.MAGIC_DEFENCE_DOWN));
        assertEquals(0.20, EffectTiers.ruleOf(EffectType.MAGIC_DEFENCE_DOWN).stepPerTier(), 1e-12);
    }

    /**
     * <b>The magic side is not an armour side.</b>
     *
     * <p>This is the assertion that the value's javadoc argues for, made executable: the class that registers
     * this effect must not add a single attribute modifier. A regression to "lower {@code ARMOR} and call it
     * magic defence" would compile, pass every numeric test here, and silently implement the physical debuff
     * twice while 「魔法防御力ダウン」 did nothing to magic — so it fails here instead.
     */
    @Test
    void theEffectCarriesNoAttributeModifierAtAll() throws Exception {
        var effect = new MagicDefenceDownEffect();
        var collected = new java.util.ArrayList<String>();
        // MobEffect#createModifiers is the only way a modifier reaches the attribute map, so asking it for
        // every amplifier is the exact question "does this effect touch an attribute?".
        for (int amplifier = 0; amplifier <= 3; amplifier++) {
            effect.createModifiers(amplifier, (attribute, modifier) -> collected.add(
                    attribute.unwrapKey().map(key -> key.location().toString()).orElse("?")
                            + " " + modifier.amount()));
        }
        assertEquals(java.util.List.of(), collected,
                "magic_defence_down must carry no attribute modifier: armour is one attribute and it does not "
                        + "express magic defence. Found: " + collected);
    }

    /** The penalty is a positive damage increase, never a reduction — a sign error would be a buff. */
    @Test
    void everyRungIncreasesMagicDamageTaken() {
        for (int tier = 1; tier <= 4; tier++) {
            double multiplier = MagicDefenceDownRules.takenMultiplierForTier(tier);
            assertTrue(multiplier > 1.0, "tier " + tier + " must raise the damage taken, got x" + multiplier);
            assertEquals(1.0 + 0.20 * tier, multiplier, 1e-12, "tier " + tier);
        }
        assertEquals(1.80, MagicDefenceDownRules.MAX_TAKEN_MULTIPLIER, 1e-12,
                "tier 4 is +80%, so 1.8x");
    }

    /** The tier travels in the amplifier, and the mapping is exactly {@code tier - 1} both ways. */
    @Test
    void theTierTravelsInTheAmplifier() {
        for (int tier = 1; tier <= 4; tier++) {
            assertEquals(tier - 1, MagicDefenceDownRules.amplifierForTier(tier), "tier " + tier);
            assertEquals(tier, MagicDefenceDownRules.tierForAmplifier(tier - 1));
        }
        assertEquals(0.20, MagicDefenceDownRules.penaltyForAmplifier(0), 1e-12,
                "amplifier 0 is the weakest rung and is still a real debuff, never +0%");
    }

    /** A hostile amplifier becomes the nearest legal rung, never a negative penalty. */
    @Test
    void aHostileAmplifierIsClamped() {
        assertEquals(0.80, MagicDefenceDownRules.penaltyForAmplifier(255), 1e-12, "capped at tier 4");
        assertEquals(0.20, MagicDefenceDownRules.penaltyForAmplifier(-5), 1e-12, "never negative");
        assertEquals(0.20, MagicDefenceDownRules.penaltyForTier(0), 1e-12);
        assertEquals(0.80, MagicDefenceDownRules.penaltyForTier(99), 1e-12);
    }

    /** Which declared tiers are expressible unchanged — anything else is a reported clamp. */
    @Test
    void onlyOneThroughFourAreRepresentable() {
        for (int tier = 1; tier <= 4; tier++) {
            assertTrue(MagicDefenceDownRules.isRepresentable(tier), "tier " + tier);
        }
        assertFalse(MagicDefenceDownRules.isRepresentable(0));
        assertFalse(MagicDefenceDownRules.isRepresentable(5));
        assertFalse(MagicDefenceDownRules.isRepresentable(-1));
    }

    /**
     * <b>The debuff multiplies after the buff, not instead of it.</b>
     *
     * <p>A victim can carry both the magic <em>buff</em> and this debuff (say, hit by キャル while ペコリーヌ's
     * ultimate is up). {@code DamageReduction.applyBoth} applies the reduction and then the increase, and the
     * results must not cancel to "no change" in a way that hides one of them: at tier 5 buff and tier 4
     * debuff, 10 damage becomes {@code 10 × 0.4 × 1.8 = 7.2}, which is neither 4 nor 18.
     */
    @Test
    void theBuffAndTheDebuffComposeOnOneHit() {
        double buffTier5 = DefenceRules.reductionFor(5);        // 0.60
        double debuffTier4 = MagicDefenceDownRules.penaltyForTier(4);   // 0.80
        assertEquals(7.2F, DamageReduction.applyBoth(10.0F, buffTier5, debuffTier4), 1e-4);
        // And with no buff, the debuff alone.
        assertEquals(18.0F, DamageReduction.applyBoth(10.0F, 0.0, debuffTier4), 1e-4);
        // And with no debuff, the buff alone — the existing behaviour is untouched.
        assertEquals(4.0F, DamageReduction.applyBoth(10.0F, buffTier5, 0.0), 1e-4);
    }

    /** A tiered params block resolves through the shared clamp, so a pack cannot invent a fifth rung. */
    @Test
    void theTierBlockClampsAgainstThisLadder() {
        TieredParams nine = TieredParams.ofTier(9);
        assertEquals(4, nine.resolveFor(EffectType.MAGIC_DEFENCE_DOWN).tier(), "9 clamps to the top rung");
        assertTrue(nine.resolveFor(EffectType.MAGIC_DEFENCE_DOWN).clamped());
        assertEquals(1, TieredParams.ofTier(0).resolveFor(EffectType.MAGIC_DEFENCE_DOWN).tier());
    }
}
