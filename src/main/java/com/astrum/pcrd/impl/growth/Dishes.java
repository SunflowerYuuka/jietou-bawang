package com.astrum.pcrd.impl.growth;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The 料理 a player can feed a character (spec §6.1: 送料理, 用 MC 原生材料 … 不新增怪物掉落).
 *
 * <h2>Why this set, and why it is small</h2>
 * Affection has to be earnable by a player who just made their first character, so every entry is a
 * vanilla item a new world can reach in the first ten minutes: two drops from a farm/mine
 * ({@code sugar}, {@code cocoa_beans}), one from bees ({@code honey_bottle}) and one that costs real
 * effort ({@code cake}). A larger set would only dilute the daily cap; a smaller one risks a player
 * with crops but no bees being unable to raise affection at all.
 *
 * <p>The set is a set of <b>ids</b>, not a set of {@code Item} instances: {@link #isDishId} is pure and
 * therefore testable without a running game, and the id list is the thing a pack author or a future
 * config would edit. {@link #idOf(ItemStack)} is the one method that needs the item registry, and it is
 * two lines.
 *
 * <h2>Not a tag</h2>
 * An item tag ({@code #astrum:dishes}) would be more pack-friendly, but §6.1 fixes the list at "vanilla
 * materials, no new drops" and a tag nobody ships is a file a reviewer has to trust. When a pack needs
 * to extend the list, this constant becomes a tag; until then the list is here, in one place, readable.
 */
@ApiStatus.Internal
public final class Dishes {

    /** The 料理 that raise affection: sugar, cocoa beans, a honey bottle, cake. */
    public static final Set<ResourceLocation> DISHES = Set.of(
            ResourceLocation.withDefaultNamespace("sugar"),
            ResourceLocation.withDefaultNamespace("cocoa_beans"),
            ResourceLocation.withDefaultNamespace("honey_bottle"),
            ResourceLocation.withDefaultNamespace("cake"));

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
     * <p>{@code BuiltInRegistries.ITEM.getKey} answers {@code null} for an item that was never
     * registered (a stack built by a hand-crafted test, a detached datapack object), which is why the
     * callers treat {@code null} as "not a dish" instead of letting it become an empty id.
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
