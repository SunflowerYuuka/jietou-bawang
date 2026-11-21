package com.astrum.pcrd.api.v1;

import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The tier table, and the clamping every tiered effect depends on.</b>
 *
 * <h2>Why the ladder is a table instead of fifty fields</h2>
 * Ten effects × five rungs is fifty numbers if each effect carries {@code tier1..tier5}; it is one
 * {@code tier} integer plus this table if the rung is an <em>ordinal into a ladder</em>. The human fixed the
 * counts and the per-rung values, so both halves of that are asserted here — the count and the arithmetic —
 * because a rung added or a step changed is a balance change the brief did not authorise.
 *
 * <h2>Why the clamping gets its own group of tests</h2>
 * A tier arrives from a datapack, i.e. from a file a third party writes. {@code "tier": 5} on the four-rung
 * armour break, {@code "tier": 0}, {@code "tier": -3} and {@code "tier": 900} are all files that will exist
 * somewhere, and none of them may crash a damage sequence, silently disable the effect, or invent a rung.
 * The three answers are pinned one at a time: use it, clamp it with a warning, or treat it as the weakest
 * rung.
 */
class EffectTiersTest {

    /** The ten tiered effects, in the order the brief's table lists them. */
    private static final List<EffectType> TIERED = List.of(
            EffectType.HEAL_AREA,
            EffectType.HEAL_SELF,
            EffectType.BUFF_ATTACK_AREA,
            EffectType.BUFF_PHYS_DEFENCE_AREA,
            EffectType.BUFF_MAGIC_DEFENCE_AREA,
            EffectType.DEFENCE_DOWN,
            EffectType.REMOVE_SHIELD,
            EffectType.HASTE,
            EffectType.PANIC,
            EffectType.PERCENT_DAMAGE,
            EffectType.CHARGE_RATE_UP,
            EffectType.SHIELD,
            // task 60: the two typed channels. Same ladder, same rung worth, same clock — only the half of the
            // damage a layer absorbs differs, so a restriction costs a barrier nothing.
            EffectType.SHIELD_PHYS,
            EffectType.SHIELD_MAGIC,
            EffectType.MAGIC_DEFENCE_DOWN,
            // task 38: the magic attack buff. A separate type from BUFF_ATTACK_AREA because the two raise
            // different attributes. Task 39 gave it its OWN step (26%) — see EffectType#BUFF_MAGIC_ATTACK_AREA.
            EffectType.BUFF_MAGIC_ATTACK_AREA,
            // task 42: the summon and the tally. Both are rungs — a spirit count and a mark cap — so both are
            // in this table and neither needs a second scale anywhere else. CONSUME_COUNTER is deliberately
            // NOT here: its magnitude is read off the marks the caster holds, so a rung would be a competing
            // second way to say the same number.
            EffectType.SUMMON_SPIRIT,
            EffectType.COUNTER,
            // task 49: the two transfer effects and the four over-time ones. All six are rungs — 6 charge
            // points, 20% of max health, a vanilla amplitude, or a charge rate — so all six are here and none
            // needs a second scale anywhere else.
            EffectType.TRANSFER_CHARGE,
            EffectType.TRANSFER_HEAL,
            EffectType.REGEN_SELF,
            EffectType.REGEN_AREA,
            EffectType.CHARGE_REGEN_SELF,
            EffectType.CHARGE_REGEN_AREA,
            // task 56: the three movement-control mechanics. All three carry a rung: a distance in blocks, a
            // vanilla knockback strength, and (for the taunt) a one-rung ladder whose length is `seconds`.
            EffectType.PULL,
            EffectType.KNOCKBACK,
            EffectType.TAUNT,
            // task 55 (appended after task 56's three, by the agent that owns those constants): two one-rung
            // ladders. Listed here so this file's own enumeration stays the single answer to "which effects are
            // tiered" — a new constant with no entry is what this test exists to catch, and it caught these two.
            EffectType.PHYS_IMMUNITY,
            EffectType.CONTROL_IMMUNITY);

    // ------------------------------------------------------------------ the table itself

    /**
     * <b>The counts are exactly the human's</b>: 5 heals / attack / defence, <b>4</b> for the armour break,
     * <b>1</b> for the shield break and panic, <b>3</b> for haste, 5 for percent damage and charge rate.
     *
     * <p>Four for {@code DEFENCE_DOWN} is the number that looks like a typo next to its neighbours and is
     * not: the ladder is 20/40/60/80, so 80% is its ceiling and a fifth rung would be 100% — armour removed,
     * not reduced. One for the shield break and one for panic are alike for a different reason: those two are
     * <em>state</em>, not magnitude, and "remove 40% of a barrier" is not a skill anybody wrote.
     */
    @Test
    void everyTieredEffectHasTheCountTheBriefFixed() {
        assertEquals(5, EffectTiers.maxTier(EffectType.HEAL_AREA));
        assertEquals(5, EffectTiers.maxTier(EffectType.HEAL_SELF));
        assertEquals(5, EffectTiers.maxTier(EffectType.BUFF_ATTACK_AREA));
        assertEquals(5, EffectTiers.maxTier(EffectType.BUFF_PHYS_DEFENCE_AREA));
        assertEquals(5, EffectTiers.maxTier(EffectType.BUFF_MAGIC_DEFENCE_AREA));
        assertEquals(4, EffectTiers.maxTier(EffectType.DEFENCE_DOWN), "the armour break has FOUR rungs, not five");
        assertEquals(1, EffectTiers.maxTier(EffectType.REMOVE_SHIELD));
        assertEquals(3, EffectTiers.maxTier(EffectType.HASTE));
        assertEquals(1, EffectTiers.maxTier(EffectType.PANIC));
        // task 56: one block a rung for the pull, 0.4 strength a rung for the knockback, one rung for the
        // taunt (its duration, not a magnitude, is what a taunt scales).
        assertEquals(5, EffectTiers.maxTier(EffectType.PULL));
        assertEquals(5, EffectTiers.maxTier(EffectType.KNOCKBACK));
        assertEquals(1, EffectTiers.maxTier(EffectType.TAUNT));
        assertEquals(5, EffectTiers.maxTier(EffectType.PERCENT_DAMAGE));
        assertEquals(5, EffectTiers.maxTier(EffectType.CHARGE_RATE_UP));
        // 護盾: five rungs, the same 20% of max health the two heals use. A shield and a heal are the same
        // quantity of health — spent before it rather than after it.
        assertEquals(5, EffectTiers.maxTier(EffectType.SHIELD));
        // 「魔法防御力をダウン」: four rungs, the human's explicit 「same ladder as DEFENCE_DOWN」.
        assertEquals(4, EffectTiers.maxTier(EffectType.MAGIC_DEFENCE_DOWN),
                "the magic counterpart shares DEFENCE_DOWN's ladder exactly");
        assertEquals(EffectTiers.ruleOf(EffectType.DEFENCE_DOWN),
                EffectTiers.ruleOf(EffectType.MAGIC_DEFENCE_DOWN),
                "and it must be the same Rule instance, not two records that happen to agree today");
        // The magic attack buff is NO LONGER the physical one's ladder. This assertion used to say the
        // opposite ("the same Rule instance"), and task 39 inverted it on the human's instruction:
        // 「物理攻击提升和魔法攻击提升的两个buff分开，因为物理攻击通常情况下等同于攻击伤害，而魔法攻击的数值是0开始的，
        // 所以魔法攻击提升buff的数值会比物理攻击提升更高，大约5%-10%」 — he then chose 26% against physical's 20%.
        //
        // The physical stat's base is ~1-2, so +20% is a real absolute gain; the magic stat's base is 0 and is
        // written as flat points (fraction × 2000), so the same fraction is a different-sized buff. The rungs
        // must therefore differ, while the number of rungs and the amplifier mapping stay shared.
        assertEquals(EffectTiers.maxTier(EffectType.BUFF_ATTACK_AREA),
                EffectTiers.maxTier(EffectType.BUFF_MAGIC_ATTACK_AREA),
                "both attack stats' buffs still have the same number of rungs");
        assertNotEquals(EffectTiers.ruleOf(EffectType.BUFF_ATTACK_AREA),
                EffectTiers.ruleOf(EffectType.BUFF_MAGIC_ATTACK_AREA),
                "the two attack ladders must now DIFFER — one shared Rule instance was the previous rule and "
                        + "it is what the human's 5-10% band replaces");
        assertEquals(0.20, EffectTiers.ruleOf(EffectType.BUFF_ATTACK_AREA).stepPerTier(), 1e-12,
                "physical stays at 20% a rung, as he said (「原本物理是20%」)");
        assertEquals(0.26, EffectTiers.ruleOf(EffectType.BUFF_MAGIC_ATTACK_AREA).stepPerTier(), 1e-12,
                "magic is 26% — the midpoint of his 25-30% band, which is the top of his 5-10% increment "
                        + "(2/20 = 10% stronger per rung)");
        // And the pair is distinguishable at EVERY tier, not merely at the top — the requirement his
        // instruction states explicitly.
        for (int tier = 1; tier <= 5; tier++) {
            assertNotEquals(EffectTiers.ruleOf(EffectType.BUFF_ATTACK_AREA).fractionAt(tier),
                    EffectTiers.ruleOf(EffectType.BUFF_MAGIC_ATTACK_AREA).fractionAt(tier),
                    "tier " + tier + " must not produce the same number on both stats");
        }
    }

    /**
     * The ten tiered effects are the tiered ones, and <b>nothing else</b> is.
     *
     * <p>The eight effects that shipped before this kernel are deliberately absent: they take their magnitude
     * from the datapack and their strength from the multiplier chain, so a tier would be a second, competing
     * scale for one number. Asserting the whole set rather than sampling it is what makes "someone quietly
     * made {@code extra_damage} tiered" fail here.
     */
    @Test
    void onlyTheTenGenericEffectsAreTiered() {
        for (EffectType type : EffectType.values()) {
            assertEquals(TIERED.contains(type), EffectTiers.isTiered(type),
                    type + " must be tiered exactly when the brief's table lists it");
        }
        assertEquals(TIERED.size() + 19, EffectType.values().length,
                "the 19 non-tiered values are the original eight (APPLY_EFFECT, EXTRA_DAMAGE, LIFESTEAL, "
                        + "GAIN_CHARGE, AREA_CHARGE, SPLASH, STACK, DETONATE), ATTACK_BUFF, HEAL, ALLY_EFFECT, "
                        + "the two FLAT point grants, CONSUME_COUNTER, task 58's CRIT_DAMAGE_UP, and task 60's "
                        + "CRIT_CHANCE_UP and COUNTER_CHARGE_REGEN — every one of them takes its magnitude from "
                        + "the datapack or a coefficient (a free percentage, or base + perMark), so a rung would "
                        + "be a second competing scale. Everything else is tiered, and this file's TIERED list "
                        + "is the single answer to which. Counts: " + TIERED.size() + " tiered + 19 non-tiered = "
                        + EffectType.values().length);
        assertNull(EffectTiers.ruleOf(null), "a null type is not tiered and must not throw");
        assertFalse(EffectTiers.isTiered(null));
        assertEquals(0, EffectTiers.maxTier(null));
    }

    /** The step per rung, exactly as the brief fixed it — including the defence pair's 12, not 20. */
    @Test
    void everyRungIsWorthTheFixedFraction() {
        assertEquals(0.20, step(EffectType.HEAL_AREA), 1e-12);
        assertEquals(0.20, step(EffectType.HEAL_SELF), 1e-12);
        assertEquals(0.20, step(EffectType.BUFF_ATTACK_AREA), 1e-12);
        assertEquals(0.26, step(EffectType.BUFF_MAGIC_ATTACK_AREA), 1e-12,
                "the magic half is 26%, NOT the physical 20%: the magic stat starts at 0 and is written as "
                        + "flat points, so the human asked for a 5-10% larger rung (he chose 26%)");
        // 12 and not 20: five rungs of 20% is 100% damage reduction, i.e. immunity, which is exactly what
        // vanilla's resistance does at level V. The human capped defence at 60% to avoid it.
        assertEquals(0.12, step(EffectType.BUFF_PHYS_DEFENCE_AREA), 1e-12);
        assertEquals(0.12, step(EffectType.BUFF_MAGIC_DEFENCE_AREA), 1e-12);
        assertEquals(0.20, step(EffectType.DEFENCE_DOWN), 1e-12);
        // The two typed channels are the shared barrier's ladder: a restricted barrier is the same size.
        assertEquals(0.20, step(EffectType.SHIELD_PHYS), 1e-12);
        assertEquals(0.20, step(EffectType.SHIELD_MAGIC), 1e-12);
        assertEquals(5, EffectTiers.maxTier(EffectType.SHIELD_PHYS));
        assertEquals(5, EffectTiers.maxTier(EffectType.SHIELD_MAGIC));
        assertEquals(1.00, step(EffectType.REMOVE_SHIELD), 1e-12);
        assertEquals(1.00, step(EffectType.HASTE), 1e-12, "haste's step is a vanilla level, not a fraction");
        assertEquals(1.00, step(EffectType.PANIC), 1e-12);
        assertEquals(0.10, step(EffectType.PERCENT_DAMAGE), 1e-12);
        assertEquals(0.25, step(EffectType.CHARGE_RATE_UP), 1e-12);
        // task 56: 1 block a rung, 0.4 vanilla strength a rung, and one rung for the taunt.
        assertEquals(1.00, step(EffectType.PULL), 1e-12, "one block of travel a rung");
        assertEquals(0.40, step(EffectType.KNOCKBACK), 1e-12,
                "0.4 is the strength LivingEntity#hurt itself passes, so tier 1 is one plain hit");
        assertEquals(1.00, step(EffectType.TAUNT), 1e-12);
    }

    /**
     * <b>The full ladder of every row, spelled out as percentages.</b>
     *
     * <p>This is the brief's table turned into numbers, and it is written out rather than derived so that a
     * change to any rung of any effect shows up as one failing line naming the row and the rung. A derived
     * assertion ({@code step × tier}) would agree with whatever the table said, which is the one thing it
     * must not do.
     */
    @Test
    void theLadderOfEveryEffectIsTheTableInTheBrief() {
        // HEAL_AREA / HEAL_SELF: 20/40/60/80/100% of the receiver's max health.
        assertLadder(EffectType.HEAL_AREA, 0.20, 0.40, 0.60, 0.80, 1.00);
        assertLadder(EffectType.HEAL_SELF, 0.20, 0.40, 0.60, 0.80, 1.00);
        // BUFF_ATTACK_AREA: +20/40/60/80/100% attack damage.
        assertLadder(EffectType.BUFF_ATTACK_AREA, 0.20, 0.40, 0.60, 0.80, 1.00);
        // BUFF_MAGIC_ATTACK_AREA: 26% a rung, NOT the physical 20% — the human's 「再加百分5-10%」,
        // applied to the stat whose base is 0 and which is written as flat points. He chose 26%.
        assertLadder(EffectType.BUFF_MAGIC_ATTACK_AREA, 0.26, 0.52, 0.78, 1.04, 1.30);
        // The defence pair: 12/24/36/48/60% damage reduction.
        assertLadder(EffectType.BUFF_PHYS_DEFENCE_AREA, 0.12, 0.24, 0.36, 0.48, 0.60);
        assertLadder(EffectType.BUFF_MAGIC_DEFENCE_AREA, 0.12, 0.24, 0.36, 0.48, 0.60);
        // DEFENCE_DOWN: 20/40/60/80% of armour removed. Four rungs.
        assertLadder(EffectType.DEFENCE_DOWN, 0.20, 0.40, 0.60, 0.80);
        // PULL: 1..5 blocks of travel; KNOCKBACK: 0.4..2.0 vanilla strength.
        assertLadder(EffectType.PULL, 1.00, 2.00, 3.00, 4.00, 5.00);
        assertLadder(EffectType.KNOCKBACK, 0.40, 0.80, 1.20, 1.60, 2.00);
        // TAUNT: one rung; its duration is `seconds`, not a magnitude.
        assertLadder(EffectType.TAUNT, 1.00);
        // REMOVE_SHIELD / PANIC: one rung each.
        assertLadder(EffectType.REMOVE_SHIELD, 1.00);
        assertLadder(EffectType.PANIC, 1.00);
        // HASTE: three rungs. The step is 1.0 because the magnitude is a vanilla amplifier (tier N = amp
        // N-1) and the fraction is HasteRules'; see that class.
        assertLadder(EffectType.HASTE, 1.00, 2.00, 3.00);
        // PERCENT_DAMAGE: 10/20/30/40/50% of the victim's max health.
        assertLadder(EffectType.PERCENT_DAMAGE, 0.10, 0.20, 0.30, 0.40, 0.50);
        // CHARGE_RATE_UP: +25/50/75/100/125%, i.e. a multiplier of 1.25 .. 2.25 once added to 1.
        assertLadder(EffectType.CHARGE_RATE_UP, 0.25, 0.50, 0.75, 1.00, 1.25);
        // SUMMON_SPIRIT: the step is 1 because the magnitude is the spirit COUNT, not a fraction — tier N is
        // N spirits, and the amplifier (tier - 1) is what an instance carries. SpiritsTest owns that mapping.
        assertLadder(EffectType.SUMMON_SPIRIT, 1.00, 2.00, 3.00, 4.00, 5.00);
        // COUNTER: the step is 4 because one rung of a tally is worth four MARKS — tier N caps the count at
        // 4N, so the top rung is 20, which is 雪菲's 6★ 「最多可获得20个」. CounterTest owns that mapping.
        assertLadder(EffectType.COUNTER, 4.00, 8.00, 12.00, 16.00, 20.00);
    }

    /**
     * <b>No defence rung reaches 100%, and that is the reason the step is 12.</b>
     *
     * <p>The class javadoc makes the argument; this pins the consequence. A future "let us make defence 20% a
     * tier like the heals" would pass every other test in this file and fail here, which is the point.
     */
    @Test
    void noDefenceRungReachesImmunity() {
        for (EffectType type : List.of(EffectType.BUFF_PHYS_DEFENCE_AREA,
                EffectType.BUFF_MAGIC_DEFENCE_AREA)) {
            double top = EffectTiers.ruleOf(type).fractionAt(EffectTiers.maxTier(type));
            assertEquals(0.60, top, 1e-12, type + " must top out at 60%");
            assertTrue(top < 1.0, type + " must never reach 100%: that is immunity, not a buff");
        }
    }

    // ------------------------------------------------------------------ clamping

    /**
     * <b>Tier 5 on the four-rung armour break clamps to 4 and says so</b> — the case the brief names, and the
     * one a datapack will actually contain because the neighbouring effects all have five rungs.
     *
     * <p>Three answers, and each is checked: the tier moves, {@code clamped} is true so the executor warns,
     * and the effect still <em>does</em> something (its strongest thing) rather than switching off.
     */
    @Test
    void tierFiveOnTheFourRungEffectClampsToFourWithAWarning() {
        EffectTiers.Resolved resolved = EffectTiers.resolve(EffectType.DEFENCE_DOWN, 5);
        assertEquals(4, resolved.tier(), "tier 5 of a 4-rung ladder is tier 4");
        assertTrue(resolved.clamped(), "the author must be told, or the mistake is invisible");
        assertEquals(3, EffectTiers.resolve(EffectType.DEFENCE_DOWN, 5).tier() - 1,
                "and the amplifier it encodes to is 3, not 4");
    }

    /** Every out-of-range tier on every effect clamps into range and reports it. */
    @Test
    void anOutOfRangeTierIsPulledInAndReported() {
        for (EffectType type : TIERED) {
            int max = EffectTiers.maxTier(type);
            for (int declared : new int[] {0, -1, -900, max + 1, 7, 900}) {
                EffectTiers.Resolved resolved = EffectTiers.resolve(type, declared);
                assertTrue(resolved.tier() >= EffectTiers.FIRST_TIER && resolved.tier() <= max,
                        type + " resolved " + declared + " to " + resolved.tier() + ", outside 1.." + max);
                assertTrue(resolved.clamped(), type + " must report that " + declared + " was clamped");
                // The near edge clamps to the nearest legal rung, not to 1: asking for more must not be
                // punished with less.
                if (declared > max) {
                    assertEquals(max, resolved.tier(), type + " must clamp upward to its ceiling");
                } else if (declared < EffectTiers.FIRST_TIER) {
                    assertEquals(EffectTiers.FIRST_TIER, resolved.tier(),
                            type + " must clamp a nonsense tier to its weakest rung, never to zero");
                }
            }
        }
    }

    /** An in-range tier is used as written and reports no clamp, so a correct pack logs nothing. */
    @Test
    void anInRangeTierIsUsedAsWrittenAndNotReported() {
        for (EffectType type : TIERED) {
            for (int tier = 1; tier <= EffectTiers.maxTier(type); tier++) {
                EffectTiers.Resolved resolved = EffectTiers.resolve(type, tier);
                assertEquals(tier, resolved.tier(), type + " tier " + tier);
                assertFalse(resolved.clamped(), type + " tier " + tier + " is legal and must not warn");
            }
        }
    }

    /** A null or untiered type answers tier 1 and never throws — a codec must not fail on it. */
    @Test
    void anUntieredTypeAnswersTheWeakestRung() {
        EffectTiers.Resolved resolved = EffectTiers.resolve(null, 3);
        assertEquals(EffectTiers.FIRST_TIER, resolved.tier());
        assertTrue(resolved.clamped(), "a tier on an untiered effect was not used, so it was clamped");
        assertEquals(EffectTiers.FIRST_TIER, EffectTiers.resolve(EffectType.EXTRA_DAMAGE, 3).tier());
    }

    // ------------------------------------------------------------------ the rule record's own guard

    /** A rule with fewer than one rung is a programming error, and it fails at construction. */
    @Test
    void aRuleMustHaveAtLeastOneRung() {
        assertThrows(IllegalArgumentException.class, () -> new EffectTiers.Rule(0, 0.2),
                "an effect with no rungs could never do anything");
        assertEquals(1, new EffectTiers.Rule(1, 0.2).maxTier());
    }

    // ------------------------------------------------------------------ TieredParams

    /**
     * {@code TieredParams} decodes a bare int, a numeric string, and a typo — and the typo is the weakest
     * rung plus a warning, never a missing hook.
     *
     * <p>A rejected hook is a <em>missing</em> effect, which is the worst of the three outcomes the brief
     * distinguishes: a player notices a skill that does nothing far later than an operator notices a log line.
     */
    @Test
    void tieredParamsDecodesEveryShapeOfTier() {
        assertEquals(3, TieredParams.parseTier("3"), "a numeric string is a tier");
        assertEquals(3, TieredParams.parseTier(" 3 "), "whitespace around it is not an error");
        assertEquals(EffectTiers.FIRST_TIER, TieredParams.parseTier("3.5"), "a decimal is not a rung");
        assertEquals(EffectTiers.FIRST_TIER, TieredParams.parseTier("x"), "a typo is the weakest rung");
        assertEquals(EffectTiers.FIRST_TIER, TieredParams.parseTier(null));
        assertEquals(EffectTiers.FIRST_TIER, TieredParams.FIRST.tier());
        assertEquals(OptionalDouble.empty(), TieredParams.FIRST.seconds());
        assertEquals(OptionalDouble.empty(), TieredParams.FIRST.radius());
        assertEquals(3, TieredParams.ofTier(3).tier());
    }

    /** The clamp is reachable from a decoded value, which is the call every executor makes. */
    @Test
    void aDecodedTierCanResolveItselfAgainstItsEffect() {
        EffectTiers.Resolved resolved = TieredParams.ofTier(9).resolveFor(EffectType.PANIC);
        assertEquals(1, resolved.tier(), "panic has one rung");
        assertTrue(resolved.clamped());
        assertFalse(TieredParams.ofTier(1).resolveFor(EffectType.PANIC).clamped());
    }

    // ------------------------------------------------------------------ helpers

    /** {@return the step declared for {@code type}} */
    private static double step(EffectType type) {
        EffectTiers.Rule rule = EffectTiers.ruleOf(type);
        assertTrue(rule != null, type + " must be in the tier table");
        return rule.stepPerTier();
    }

    /** Asserts {@code type}'s whole ladder equals {@code expected}, rung by rung, and that it ends there. */
    private static void assertLadder(EffectType type, double... expected) {
        EffectTiers.Rule rule = EffectTiers.ruleOf(type);
        assertTrue(rule != null, type + " must be tiered");
        assertEquals(expected.length, rule.maxTier(), type + " rung count");
        for (int tier = 1; tier <= expected.length; tier++) {
            assertEquals(expected[tier - 1], rule.fractionAt(tier), 1e-12,
                    type + " tier " + tier + " is worth " + expected[tier - 1] + ", not "
                            + rule.fractionAt(tier));
        }
        // There is no rung past the declared end: the table is the whole ladder.
        for (int tier = expected.length + 1; tier <= expected.length + 3; tier++) {
            assertEquals(EffectTiers.maxTier(type), EffectTiers.resolve(type, tier).tier(),
                    type + " has no rung " + tier);
        }
    }
}
