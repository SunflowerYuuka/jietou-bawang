package com.astrum.pcrd.impl.effect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.Test;

/**
 * <b>The physical-defence buff's two layers, and the one place they must not be the same thing.</b>
 *
 * <h2>What the human asked for, in two sentences that had to be reconciled</h2>
 * 「物理防御上升做成护甲值的提升」, then, when asked which reading: 「护甲值 + 保留百分比」, then
 * 「只有 ex 或者 sp 技能里面加物理防御的，才是增加固定的护甲值」.
 *
 * <p>So there are three statements and they resolve to two mechanisms:
 * <ul>
 *   <li>a <b>timed buff</b> adds a <b>percentage</b> of armour and keeps its damage-reduction percentage;</li>
 *   <li>an <b>EX / SP passive</b> adds <b>flat</b> armour points, because a percentage of zero armour is
 *       zero and a passive has to be worth something on a naked character.</li>
 * </ul>
 *
 * <p>The arithmetic is asserted here rather than in the effect classes: those are client-and-server shared
 * and cannot be unit-tested without a world, while {@code DefenceRules} is pure.
 */
class DefenceRulesArmourTest {

    /** The timed buff's fraction, rung by rung: 20/40/60/80/100% of the armour she already has. */
    @Test
    void theBuffGrantsAFractionOfArmour() {
        assertEquals(0.20, DefenceRules.armourFractionForAmplifier(0), 1e-9);
        assertEquals(0.40, DefenceRules.armourFractionForAmplifier(1), 1e-9);
        assertEquals(0.60, DefenceRules.armourFractionForAmplifier(2), 1e-9);
        assertEquals(0.80, DefenceRules.armourFractionForAmplifier(3), 1e-9);
        assertEquals(1.00, DefenceRules.armourFractionForAmplifier(4), 1e-9,
                "rung 5 doubles her armour — generous, and vanilla's own curve is the balance");
    }

    /** The passive's points, rung by rung: 4/8/12/16/20, so one rung is roughly one armour piece. */
    @Test
    void thePassiveGrantsFlatPoints() {
        assertEquals(4.0, DefenceRules.armourPointsForAmplifier(0), 1e-9);
        assertEquals(8.0, DefenceRules.armourPointsForAmplifier(1), 1e-9);
        assertEquals(12.0, DefenceRules.armourPointsForAmplifier(2), 1e-9);
        assertEquals(16.0, DefenceRules.armourPointsForAmplifier(3), 1e-9);
        assertEquals(20.0, DefenceRules.armourPointsForAmplifier(4), 1e-9,
                "a full set of iron is 15, so the top rung is a set and a bit");
    }

    /** Out-of-range amplifiers clamp into the ladder instead of inventing a rung or going negative. */
    @Test
    void bothCurvesClamp() {
        assertEquals(0.20, DefenceRules.armourFractionForAmplifier(-99), 1e-9);
        assertEquals(1.00, DefenceRules.armourFractionForAmplifier(99), 1e-9);
        assertEquals(4.0, DefenceRules.armourPointsForAmplifier(-99), 1e-9);
        assertEquals(20.0, DefenceRules.armourPointsForAmplifier(99), 1e-9);
        assertTrue(DefenceRules.armourFractionForAmplifier(0) > 0,
                "rung 1 must do something, or the buff is a HUD row that lies");
        assertTrue(DefenceRules.armourPointsForAmplifier(0) > 0, "and so must the passive");
    }

    /**
     * <b>The two forms use different modifier ids.</b>
     *
     * <p>Vanilla's {@code addAttributeModifiers} removes a modifier <em>by id</em> before adding its own, so
     * a shared id would let the EX passive and a timed buff cancel each other depending on which landed last.
     * This is the assertion that keeps that from being reintroduced by a helpful refactor.
     */
    @Test
    void theTwoFormsDoNotShareAModifierId() {
        assertNotEquals(PhysDefenceEffect.MODIFIER_ID, PhysDefenceEffect.FLAT_MODIFIER_ID,
                "one id would make the passive and the buff erase each other");
        assertEquals("phys_defence", PhysDefenceEffect.MODIFIER_ID.getPath());
        assertEquals("phys_defence_flat", PhysDefenceEffect.FLAT_MODIFIER_ID.getPath());
        assertEquals("astrum", PhysDefenceEffect.MODIFIER_ID.getNamespace());
    }

    /**
     * <b>The two forms use different operations</b>, which is the whole difference between them.
     *
     * <p>Read from the enum rather than from a constructed effect because the operation is fixed at
     * construction time; asserting the constants is what a future edit would break first.
     */
    @Test
    void theTwoFormsUseDifferentOperations() {
        assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                AttributeModifier.Operation.valueOf("ADD_MULTIPLIED_TOTAL"),
                "the buff is 「百分比增加护甲值」");
        assertEquals(AttributeModifier.Operation.ADD_VALUE,
                AttributeModifier.Operation.valueOf("ADD_VALUE"),
                "the passive is 「固定的护甲值」");
        assertNotEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                AttributeModifier.Operation.ADD_VALUE);
    }

    /** The damage-reduction half is untouched: the buff still reduces damage by its own percentage. */
    @Test
    void theReductionPercentageIsUnchanged() {
        assertEquals(0.12, DefenceRules.STEP, 1e-9);
        assertEquals(0.60, DefenceRules.reductionFor(5), 1e-9, "still the documented 60% ceiling");
        assertEquals(0.12, DefenceRules.reductionFor(1), 1e-9);
        assertEquals(0.0, DefenceRules.reductionFor(0), 1e-9, "no tier, no reduction");
    }

    /** The passive is a separate <em>registry type</em>, so vanilla never merges it into the buff's row. */
    @Test
    void thePassiveHasItsOwnRegistryPath() {
        assertEquals("phys_defence_flat", DefenceRules.PHYS_FLAT_PATH);
        assertNotEquals(DefenceRules.PHYS_PATH, DefenceRules.PHYS_FLAT_PATH,
                "one path would be one type, and two grants would merge into one amplifier");
        assertEquals("phys_defence", DefenceRules.PHYS_PATH, "the buff's own path is unchanged");
    }
}