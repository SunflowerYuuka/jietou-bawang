package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.item.BookTheme;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 送料理's dish list — now one dedicated 料理 instead of four vanilla ingredients.
 *
 * <h2>The rule this file encodes, and why it changed</h2>
 * The list used to be sugar, cocoa beans, a honey bottle and cake, and the test pinned those four ids.
 * That is now <b>wrong by design</b>: none of them is a 料理, and with a one-per-dish-per-day throttle the
 * four-item list was a checklist rather than a choice. The dish is {@code astrum:rice_bowl} and the list is
 * closed: adding a dish is a change to the list and to a recipe, not a convenience.
 *
 * <p>What is worth asserting is the shape, not just the string:
 *
 * <ul>
 *   <li><b>exactly one dish, in this mod's namespace</b> — the old "every dish is vanilla" assertion is
 *       inverted, and inverted deliberately: a dish is now something this mod ships;</li>
 *   <li><b>{@link Dishes#RICE_BOWL} is in the set</b>, which is the one link between the item registration
 *       and the feeding rule that a unit test can check without a registry;</li>
 *   <li><b>a lookalike id is not a dish</b> — the set holds ids, not paths, so
 *       {@code minecraft:rice_bowl} is a different item;</li>
 *   <li><b>nothing is not a dish</b> — the null and empty stack cases the handler actually guards.</li>
 * </ul>
 *
 * <p>What is <em>not</em> covered here: {@link Dishes#idOf(ItemStack)} on a real stack, because
 * {@code BuiltInRegistries.ITEM.getKey} needs the item registry up. The empty and null stacks are covered
 * because they are the two inputs an event with no item in hand can produce.
 */
class DishesTest {

    @Test
    void theDishIsTheRiceBowlAndNothingElse() {
        Set<ResourceLocation> expected = Set.of(InternalIds.id("rice_bowl"));
        assertEquals(expected, Dishes.DISHES, "one dedicated 料理, not a list of pantry ingredients");
        assertEquals(expected, Set.of(Dishes.RICE_BOWL),
                "the constant the registration-side test reads must be the entry in the set");
    }

    /**
     * The dish is <b>this mod's</b> item, i.e. the inverse of the old rule.
     *
     * <p>It is asserted rather than described because the change is exactly this: a dish used to have to
     * be {@code minecraft:*} and now it has to be {@code astrum:*}. A future edit that puts vanilla sugar
     * back would fail here with the reason attached.
     */
    @Test
    void theDishIsThisModsItemAndNoLongerAVanillaIngredient() {
        for (ResourceLocation dish : Dishes.DISHES) {
            assertEquals("astrum", dish.getNamespace(),
                    dish + " must be a dedicated 料理 of this mod, not a vanilla ingredient");
        }
        for (String gone : new String[] {"sugar", "cocoa_beans", "honey_bottle", "cake"}) {
            assertFalse(Dishes.isDishId(ResourceLocation.withDefaultNamespace(gone)),
                    "minecraft:" + gone + " is no longer a 料理 — the rice bowl replaced that list");
        }
    }

    @Test
    void everyDishIdIsRecognised() {
        for (ResourceLocation dish : Dishes.DISHES) {
            assertTrue(Dishes.isDishId(dish), dish + " is in the set, so it must be feedable");
        }
        assertTrue(Dishes.isDishId(Dishes.RICE_BOWL));
    }

    /**
     * A same-named id in another namespace is not this dish.
     *
     * <p>{@code minecraft:rice_bowl} does not exist in vanilla today, which is precisely why it is the
     * right test: it fails if the set is ever compared by path instead of by id.
     */
    @Test
    void aLookalikeIdFromAnotherNamespaceIsNotADish() {
        assertFalse(Dishes.isDishId(ResourceLocation.withDefaultNamespace("rice_bowl")),
                "the set holds ids, not paths: minecraft:rice_bowl is not astrum:rice_bowl");
        assertFalse(Dishes.isDishId(InternalIds.id("rice_bowls")));
        assertFalse(Dishes.isDishId(InternalIds.id("bowl")));
    }

    @Test
    void nothingIsNotADish() {
        assertFalse(Dishes.isDishId(null));
        assertFalse(Dishes.isDish(null));
        assertFalse(Dishes.isDish(ItemStack.EMPTY));
        assertNull(Dishes.idOf(null));
        assertNull(Dishes.idOf(ItemStack.EMPTY), "an empty stack has no item to name");
    }

    /**
     * The dish is not a book or a card: the two content families this task touches must not overlap, or a
     * right-click could both feed a character and open a screen.
     */
    @Test
    void noBookOrCardIsADish() {
        for (BookTheme theme : BookTheme.values()) {
            assertFalse(Dishes.isDishId(theme.bookItemId()),
                    theme + " book must not be feedable");
        }
    }
}
