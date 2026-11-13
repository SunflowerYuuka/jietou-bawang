package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.attach.Party;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The slot -> effect mapping, as data.
 *
 * <h2>What this test is really protecting</h2>
 * The party effects are five <em>distinct</em> {@code MobEffect} types because vanilla merges two
 * instances of the <b>same</b> type into one entry. If a later edit made {@link PartyEffectSlots#ids()}
 * return five copies of one id (or made two slots collide), the mod would still compile and still
 * "work" — with four of the five party members silently missing from the HUD. That failure is
 * invisible without a game, so it is asserted here as a property: five ids, five distinct ids, and the
 * slot round-trip.
 */
class PartyEffectSlotsTest {

    @Test
    void everyPartySlotHasItsOwnEffectId() {
        List<ResourceLocation> ids = PartyEffectSlots.ids();
        assertEquals(Party.SLOTS, ids.size(), "one effect id per party slot");
        assertEquals(Party.SLOTS, new HashSet<>(ids).size(),
                "the ids must be distinct, or vanilla merges the entries: " + ids);
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            assertEquals(PartyEffectSlots.idFor(slot), ids.get(slot), "slot " + slot + " sits at its index");
        }
    }

    @Test
    void theIdsAreFiveNumberedAstrumEffects() {
        assertEquals("astrum:party_1", PartyEffectSlots.idFor(0).toString());
        assertEquals("astrum:party_5", PartyEffectSlots.idFor(Party.SLOTS - 1).toString());
        for (ResourceLocation id : PartyEffectSlots.ids()) {
            assertEquals("astrum", id.getNamespace());
            assertTrue(id.getPath().startsWith(PartyEffectSlots.PATH_PREFIX));
        }
    }

    @Test
    void slotOfInvertsIdForAndRejectsEverythingElse() {
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            OptionalInt found = PartyEffectSlots.slotOf(PartyEffectSlots.idFor(slot));
            assertEquals(OptionalInt.of(slot), found, "slot " + slot + " round-trips");
        }
        assertTrue(PartyEffectSlots.slotOf(ResourceLocation.parse("astrum:djeeta")).isEmpty(),
                "a character id is not a party effect");
        assertTrue(PartyEffectSlots.slotOf(ResourceLocation.parse("other:party_1")).isEmpty(),
                "the same path in another namespace is another effect");
        assertTrue(PartyEffectSlots.slotOf(ResourceLocation.parse("astrum:party_6")).isEmpty(),
                "there is no sixth party slot");
        assertTrue(PartyEffectSlots.slotOf(ResourceLocation.parse("astrum:party_0")).isEmpty());
        assertTrue(PartyEffectSlots.slotOf(ResourceLocation.parse("astrum:party_x")).isEmpty());
        assertTrue(PartyEffectSlots.slotOf(null).isEmpty());
    }

    @Test
    void slotsOutsideThePartyAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PartyEffectSlots.idFor(-1));
        assertThrows(IllegalArgumentException.class, () -> PartyEffectSlots.idFor(Party.SLOTS));
        assertThrows(IllegalArgumentException.class, () -> PartyEffectSlots.nameKey(Party.SLOTS));
    }

    @Test
    void everyEffectHasAFallbackNameKey() {
        Set<String> keys = PartyEffectSlots.langKeys();
        assertEquals(Party.SLOTS, keys.size());
        assertTrue(keys.contains("effect.astrum.party_1"));
        assertTrue(keys.contains("effect.astrum.party_5"));
        assertFalse(keys.contains("effect.astrum.party_6"));
    }

    @Test
    void thereIsOneColourPerSlot() {
        assertEquals(Party.SLOTS, PartyEffectSlots.SLOT_COLORS.length);
        assertEquals(Party.SLOTS, new HashSet<>(List.of(
                PartyEffectSlots.SLOT_COLORS[0], PartyEffectSlots.SLOT_COLORS[1],
                PartyEffectSlots.SLOT_COLORS[2], PartyEffectSlots.SLOT_COLORS[3],
                PartyEffectSlots.SLOT_COLORS[4])).size(),
                "the five fallback colours must differ, or the slots look identical");
    }
}