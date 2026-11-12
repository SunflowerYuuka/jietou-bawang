package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.impl.InternalIds;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The 料理 a player can feed a character (spec §6.1: 送料理).
 *
 * <h2>Why one dedicated dish instead of a list of vanilla ingredients</h2>
 * This used to hold four vanilla items — {@code sugar}, {@code cocoa_beans}, {@code honey_bottle},
 * {@code cake}. Three things were wrong with that list, and only the third is cosmetic:
 *
 * <ol>
 *   <li><b>None of them is a 料理.</b> Sugar, cocoa beans and a honey bottle are <em>ingredients</em>; cake
 *       is a block. Feeding a character a spoonful of sugar reads as a placeholder, because it is
 *       one.</li>
 *   <li><b>The throttle made the list pointless.</b> The rule is one of each dish per game-day
 *       ({@link DishThrottle}), so four dishes bought four affection a day from four unrelated pantry
 *       items. The list was not a choice the player made; it was a checklist.</li>
 *   <li><b>A dedicated dish is one thing to draw, name and balance.</b> One item, one icon, one recipe,
 *       one line in the lang file per language — and the affection rule now reads "cook a meal, feed it",
 *       which is what 送料理 says.</li>
 * </ol>
 *
 * <p>So this is a set of <b>one</b>: {@code astrum:rice_bowl}. It stays a <em>set</em> rather than a
 * constant because {@link #isDishId} is the pure predicate the click handler and the tests are written
 * against, and a future dish (a second recipe, a festival food) is a one-line addition here rather than a
 * second branch in {@link DishClick} and {@link GrowthEvents}.
 *
 * <h2>Why an id set and not a tag</h2>
 * An item tag ({@code #astrum:dishes}) would let a pack add a dish without touching code, but a tag nobody
 * ships is a file a reviewer has to trust, and this list is one entry. When a pack needs to extend it, this
 * constant becomes a tag — the predicate is already the right shape for that swap.
 *
 * <p>The set is a set of <b>ids</b>, not of {@code Item} instances: {@link #isDishId} is pure and therefore
 * testable without a running game, and the id list is the thing a config or a pack would edit.
 * {@link #idOf(ItemStack)} is the one method that needs the item registry, and it is two lines.
 */
@ApiStatus.Internal
public final class Dishes {

    /** The 料理 that raises affection: the rice bowl, and nothing else today. */
    public static final Set<ResourceLocation> DISHES = Set.of(InternalIds.id("rice_bowl"));

    /**
     * The item id of the rice bowl, as a constant the item registration can also use.
     *
     * <p>Spelled here so the registration and the dish list cannot disagree: {@code AstrumItems} registers
     * {@code rice_bowl} and this set names {@code astrum:rice_bowl}, and {@code DishesTest} asserts the two
     * are the same string, which is the one link a unit test can check without a registry.
     */
    public static final ResourceLocation RICE_BOWL = InternalIds.id("rice_bowl");

    /**
     * The rice bowl's item name, {@code item.astrum.rice_bowl}.
     *
     * <p>Held here rather than in {@code GrowthText} (which is about sentences) because it is the
     * <em>item's</em> name, and the item lives in another package that must not be imported by the growth
     * kernels. {@code LangParityTest} reads this constant, so a rename that forgets the lang files fails a
     * test instead of showing a raw key in the inventory.
     */
    public static final String DISH_NAME_KEY = "item.astrum.rice_bowl";

    private Dishes() {
    }

    /**
     * {@return whether {@code id} names a dish}
     *
     * @param id the item id to test; {@code null} is not a dish
     */
    public static boolean isDishId(@Nullable ResourceLocation id) {
        return id != null && DISHES.contains(id);
    }

    /**
     * {@return the item id of {@code stack}, or {@code null} when it has none}
     *
     * <p>{@code BuiltInRegistries.ITEM.getKey} answers {@code null} for an item that was never registered
     * (a stack built by a hand-crafted test, a detached datapack object), which is why the callers treat
     * {@code null} as "not a dish" instead of letting it become an empty id.
     *
     * @param stack the held stack; {@code null} or empty is not a dish
     */
    public static @Nullable ResourceLocation idOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    /**
     * {@return whether {@code stack} is a dish this build can feed}
     *
     * @param stack the held stack
     */
    public static boolean isDish(@Nullable ItemStack stack) {
        return isDishId(idOf(stack));
    }
}
