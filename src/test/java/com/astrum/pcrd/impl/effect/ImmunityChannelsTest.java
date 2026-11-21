package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The two immunity channels, as truth tables.</b>
 *
 * <h2>Why these are pure and why that is enough for the classification</h2>
 * The question "does physical immunity block this hit" has two inputs — is the source magical
 * ({@link DamageReduction#isMagic}, the one classifier the defence buffs already use) and does the victim
 * hold the effect. {@link PhysicalImmunity#applies} is that rule with no entity, so the requirement
 * 「单独免疫物理不免疫魔法」 is one assertion: physical + immune is blocked, magic + immune is not. Live
 * behaviour (an entity holding the marker, a real {@code hurt()}) needs a server; the report says a human has
 * to watch it.
 *
 * <p>The control half is the same shape, one table: {@code PANIC} is control, damage is not, so an immune
 * holder is protected from the panic and takes the damage normally.
 */
class ImmunityChannelsTest {

    /** 「单独免疫物理不免疫魔法」 — the whole of the physical channel's contract, in four answers. */
    @Test
    void physicalImmunityBlocksThePhysicalHalfAndLetsMagicThrough() {
        assertTrue(PhysicalImmunity.applies(false, true),
                "a physical source against an immune body does nothing");
        assertFalse(PhysicalImmunity.applies(true, true),
                "a magical source against the SAME body passes — the immunity is physical-only");
        assertFalse(PhysicalImmunity.applies(false, false),
                "a body without the effect takes physical damage normally");
        assertFalse(PhysicalImmunity.applies(true, false));
    }

    /**
     * <b>Control immunity blocks panic and nothing else.</b>
     *
     * <p>「does not block damage」 is asserted as a classification fact: no damage type is in the control set,
     * so an immune holder takes {@code percent_damage} (and every other damage number) exactly as before.
     * Stat debuffs are deliberately outside too: slowness and weakness change a stat, the holder still acts,
     * so they are not control.
     */
    @Test
    void controlImmunityBlocksPanicAndNotDamageOrStats() {
        assertEquals(Set.of(EffectType.PANIC), ControlImmunity.controllableTypes(),
                "one table, and panic is the mod's only hard control");
        assertTrue(ControlImmunity.blocks(EffectType.PANIC, true));
        assertFalse(ControlImmunity.blocks(EffectType.PANIC, false),
                "without the immunity the panic lands as always");
        assertFalse(ControlImmunity.blocks(EffectType.PERCENT_DAMAGE, true),
                "control immunity is not damage immunity — a percentage-damage hit still lands");
        assertFalse(ControlImmunity.blocks(EffectType.EXTRA_DAMAGE, true));
        assertFalse(ControlImmunity.blocks(EffectType.DEFENCE_DOWN, true),
                "an armour debuff changes a stat; it is not control");
    }

    /** Both channels are one-rung states, and their registry ids are the ones the data names. */
    @Test
    void bothChannelsAreOneRungAndNamedAsTheDataNamesThem() {
        assertEquals(1, EffectTiers.maxTier(EffectType.PHYS_IMMUNITY));
        assertEquals(1, EffectTiers.maxTier(EffectType.CONTROL_IMMUNITY));
        assertEquals(1, EffectTiers.resolve(EffectType.PHYS_IMMUNITY, 5).tier(),
                "克莉丝提娜's 「5档」 is resistance V's provenance; this channel has one rung and clamps to it");
        assertEquals("astrum:phys_immunity", PhysicalImmunity.ID.toString());
        assertEquals("astrum:control_immunity", ControlImmunity.ID.toString());
        assertTrue(EffectType.PHYS_IMMUNITY.reachesParty(),
                "the immunity is a state on the caster's own side, so an ultimate grants it in ally scope");
        assertTrue(EffectType.CONTROL_IMMUNITY.reachesParty());
        assertFalse(EffectType.PHYS_IMMUNITY.reachesVictim(),
                "it is not something done to the enemy — the victim set would put it on the wrong body");
        assertTrue(EffectType.PHYS_IMMUNITY.persistsOnCaster(),
                "it is a timed state, so the skill entry lasts as long as it does");
        assertTrue(EffectType.CONTROL_IMMUNITY.persistsOnCaster());
    }
}