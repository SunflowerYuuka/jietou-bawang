package com.astrum.pcrd.impl.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.core.component.DataComponents;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>What makes a character card a <em>rolled</em> card</b>: the character it names, and the model index that
 * draws her face. One method, so the two components are always written together.
 *
 * <h2>Why this is one call and not two {@code set}s at each roll site</h2>
 * There are exactly two places a card is rolled — {@code impl.gacha.CardRollOnCraft} (the crafting lottery)
 * and {@code MemoryTokenItem.use} (a hundred 记忆结晶) — and both used to write
 * {@code astrum:character_id} by hand. Adding the model index at each of them would have been the same rule
 * written twice, with the classic outcome: one site updated, the other shipping cards that draw the generic
 * art while their tooltip names a character. So "a rolled card has both components" lives here, and the two
 * roll sites only decide <em>which</em> character.
 *
 * <h2>Why a card with no index still gets no component, rather than index 0</h2>
 * {@link CardIndex#indexOf} answers {@code 0} for a character the index file does not list, and
 * {@code ItemProperties} reads an <b>absent</b> component as {@code 0.0f} — the same number. So writing
 * {@code CustomModelData(0)} would be identical to writing nothing, and doing nothing is the honest
 * version: a blank card (creative-mode, or a roll that found nobody) and a card of an unlisted character
 * both keep the generic card art, and neither carries a component that claims otherwise.
 *
 * <h2>Non-zero, and why that is load-bearing</h2>
 * An override authored at {@code 0} would match every stack whose component is absent, repainting
 * <em>all</em> blank cards with one character's face. {@link CardIndex.Table} refuses a 0 index while
 * loading, and this method never writes one.
 */
@ApiStatus.Internal
public final class CardStamp {

    private CardStamp() {
    }

    /**
     * Stamps {@code stack} as a rolled card of {@code characterId}.
     *
     * <p>Idempotent and total: a {@code null} character writes nothing at all, so a caller cannot turn a
     * blank card into a half-rolled one.
     *
     * @param stack       the card stack to write on; {@code null} is a no-op
     * @param characterId the character the roll produced, or {@code null} for "the roll found nobody"
     * @return whether a component was written
     */
    public static boolean stamp(@Nullable ItemStack stack, @Nullable ResourceLocation characterId) {
        if (stack == null || stack.isEmpty() || characterId == null) {
            return false;
        }
        stack.set(AstrumDataComponents.CHARACTER_ID.get(), characterId);
        int index = CardIndex.indexOf(characterId);
        if (index > 0) {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(index));
        }
        return true;
    }

    /**
     * {@return the model index {@code stack} carries, or {@code 0} when it carries none}
     *
     * <p>The read side, next to the write side so the two spell {@code CUSTOM_MODEL_DATA} once. What the
     * card actually <em>renders</em> is decided by vanilla from the component this reads; this is for tests
     * and diagnostics, not for drawing.
     *
     * @param stack the card stack, possibly {@code null}
     */
    public static int indexOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        return data == null ? 0 : data.value();
    }

    /**
     * {@return the character {@code stack} names, or {@code null} when it names nobody}
     *
     * @param stack the card stack, possibly {@code null}
     */
    public static @Nullable ResourceLocation characterOf(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : stack.get(AstrumDataComponents.CHARACTER_ID.get());
    }
}
