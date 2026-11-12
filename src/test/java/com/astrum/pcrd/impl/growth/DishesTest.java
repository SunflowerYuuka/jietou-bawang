package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.impl.InternalIds;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 送料理's list of dishes (spec §6.1: 用 MC 原生材料，不新增怪物掉落).
 *
 * <h2>The rule this file encodes, and why it is worth a test</h2>
 * Affection must be earnable by a player who has just made their first character, so every dish is a
 * <b>vanilla</b> item reachable in the first ten minutes — and the list is <b>closed</b>: adding a new
 * drop or a modded item would be a change to §6.1, not a convenience. A test that named the same four
 * ids twice would prove nothing; what these pin is the shape the rule has: four vanilla materials, an
 * id set (not a set of {@code Item} instances), and "not in the set" answering {@code false} rather
 * than throwing.
 *
 * <p>What is <em>not</em> covered here: {@link Dishes#idOf(ItemStack)} on a real stack, because
 * {@code BuiltInRegistries.ITEM.getKey} needs the item registry up. The empty stack and the null stack
 * are covered because they are the two cases the callers actually guard.
 */
class DishesTest {

    @Test
    void theDishesAreFourVanillaMaterials() {
        Set<ResourceLocation> expected = Set.of(
                ResourceLocation.withDefaultNamespace("sugar"),
                ResourceLocation.withDefaultNamespace("cocoa_beans"),
                ResourceLocation.withDefaultNamespace("honey_bottle"),
                ResourceLocation.withDefaultNamespace("cake"));
        assertEquals(expected, Dishes.DISHES,
                "§6.1's list: two farm/mine drops, one from bees, one that costs real effort");
        assertTrue(Dishes.DISHES.stream().allMatch(id -> id.getNamespace().equals("minecraft")),
                "no dish may come from a mod or a new drop");
    }

    @Test
    void everyDishIdIsRecognised() {
        for (ResourceLocation dish : Dishes.DISHES) {
            assertTrue(Dishes.isDishId(dish), dish + " is in the set, so it must be feedable");
        }
    }

    /** A same-named id in another namespace is not the vanilla item, so it is not a dish. */
    @Test
    void aLookalikeIdFromAnotherNamespaceIsNotADish() {
        assertFalse(Dishes.isDishId(InternalIds.id("sugar")),
                "the set holds ids, not paths: astrum:sugar is not minecraft:sugar");
        assertFalse(Dishes.isDishId(ResourceLocation.withDefaultNamespace("bread")));
    }

    @Test
    void nothingIsNotADish() {
        assertFalse(Dishes.isDishId(null));
        assertFalse(Dishes.isDish(null));
        assertFalse(Dishes.isDish(ItemStack.EMPTY));
        assertNull(Dishes.idOf(null));
        assertNull(Dishes.idOf(ItemStack.EMPTY), "an empty stack has no item to name");
    }
}
