package com.astrum.pcrd.impl.gacha;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.item.AstrumItems;
import com.astrum.pcrd.impl.item.CardIndex;
import com.astrum.pcrd.impl.item.CardStamp;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Rolls the character a crafted card grants, at craft time, per card.</b>
 *
 * <h2>Why the result cannot be a recipe</h2>
 * A recipe's {@code result} is a static template shared by every player on the server, and the roll depends on
 * <em>who is crafting</em> — specifically on which characters that player already owns
 * ({@link CardRoll#OWNED_WEIGHT}). So the card comes out of the recipe naming nobody, and this handler fills the
 * name in. The human's requirement is explicit: 「抽卡在合成时决定」.
 *
 * <h2>Which event, and why this one</h2>
 * {@link PlayerEvent.ItemCraftedEvent} is fired by {@code EventHooks.firePlayerCraftingEvent} from
 * {@code ResultSlot.checkTakeAchievements} — i.e. at the moment the player <b>takes</b> the result out of the
 * crafting output and a card actually enters their hands. That is the only point that has all three things the
 * roll needs: the {@link ServerPlayer} (for the roster), the {@link ItemStack} that is about to be given (which
 * this handler may replace), and a count (the stack's size, which is what a shift-craft multiplies).
 *
 * <p>NeoForge 21.1 does not offer a {@code ModifyCraftingItemEvent} — that was the 1.20 name and it is gone.
 * The alternative, a custom {@code CraftingRecipe} subclass, was rejected: it would put the probability rule
 * inside a recipe serializer (untestable without a world) and it would still not know the crafter at the time
 * the result is built.
 *
 * <h2>Server side only, and why that is not a detail</h2>
 * The roster lives on the server. A client-side roll would have to guess which characters the player owns,
 * which is exactly the input the probability depends on — so the client would produce a different card from
 * the one the server recorded, and the tooltip would disagree with the item. The guard is
 * {@code instanceof ServerPlayer}: a client player is not one, so the whole body is skipped rather than
 * branch-protected.
 *
 * <h2>Shift-crafting: one roll per card, re-reading ownership between them</h2>
 * A shift-click can take a whole stack of cards in one event ({@code onQuickCraft} accumulates
 * {@code removeCount} and the stack handed here has that size). The human's requirement is that each card in
 * that stack rolls <b>independently</b> and that each roll accounts for what the player owns <em>at that
 * moment</em> — otherwise a stack of five could come back as five copies of one girl, not because the odds
 * said so but because one snapshot was reused five times.
 *
 * <p>So the count is read from the stack, {@link CardRoll#rollBatch} is asked for that many ids (it feeds each
 * result back into the owned set before the next draw), and the <b>first</b> id goes on the crafted stack. The
 * remaining cards are handed over as their own stacks, because {@code astrum:character_id} is a component of a
 * stack and not of an item: five cards naming three girls is not expressible as one stack of five. That is a
 * consequence of the existing data shape rather than a new design, and it is the only shape that keeps every
 * rolled card the card it was rolled as.
 *
 * <h2>What happens when nothing can be rolled</h2>
 * An empty character registry (a broken or absent datapack) means the pool is empty. The card is then left
 * <b>without</b> a {@code character_id}, which is a state {@code CharacterCardItem} already handles: it
 * refuses the card with a message instead of guessing. The player keeps the card and can use it after the
 * datapack is fixed, rather than losing the ingredients to a silent no-op.
 */
@ApiStatus.Internal
public final class CardRollOnCraft {

    private CardRollOnCraft() {
    }

    /**
     * Fills in the rolled character on every card a craft produced.
     *
     * <p>Registered on the <b>game</b> bus by {@code CombatEvents.register()} next to the other gameplay
     * listeners; {@code Astrum} stays wiring-only.
     *
     * @param event the craft event, carrying the player and the stack about to be taken
     */
    @SubscribeEvent
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;   // client side: the roster is not here, and the server's answer is authoritative
        }
        ItemStack crafted = event.getCrafting();
        if (!crafted.is(AstrumItems.CHARACTER_CARD.get())) {
            return;
        }
        // Already named — a datapack recipe or a creative-given card. Leave it exactly as it is.
        if (CardStamp.characterOf(crafted) != null) {
            return;
        }

        int count = Math.max(1, crafted.getCount());
        List<ResourceLocation> pool = characterPool();
        if (pool.isEmpty()) {
            Astrum.LOGGER.warn("astrum: {} crafted a character card but the pack defines no characters, so "
                    + "the card names nobody", player.getGameProfile().getName());
            return;
        }

        Roster roster = player.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            // Reading it would throw and a throw here would eat the ingredients. A card that names nobody is
            // the safer failure: CharacterCardItem refuses it with a message, and the card is still usable
            // once the save is readable again.
            Astrum.LOGGER.warn("astrum: {} crafted a character card while their book is quarantined, so the "
                    + "card names nobody", player.getGameProfile().getName());
            return;
        }

        Set<ResourceLocation> owned = roster.entries().keySet();
        List<ResourceLocation> drawn = CardRoll.rollBatch(pool, owned, count, player.getRandom());
        if (drawn.isEmpty()) {
            return;
        }

        // The crafted stack becomes the first card. The component is per *stack*, so several different girls
        // cannot share one — the rest are handed over as their own stacks. Anything else would silently make
        // four of five cards the wrong character.
        //
        // CardStamp, not two `set` calls: a rolled card carries BOTH the character it names and the
        // custom_model_data that draws her face, and one method is what keeps this site and MemoryTokenItem's
        // from drifting (see CardStamp's javadoc).
        crafted.setCount(1);
        CardStamp.stamp(crafted, drawn.get(0));
        for (int i = 1; i < drawn.size(); i++) {
            ItemStack extra = new ItemStack(AstrumItems.CHARACTER_CARD.get(), 1);
            CardStamp.stamp(extra, drawn.get(i));
            if (!player.getInventory().add(extra)) {
                // Full inventory: dropped rather than voided. A roll the player earned must not evaporate.
                player.drop(extra, false);
            }
        }
        Astrum.LOGGER.info("astrum: {} crafted {} character card(s), rolled {} — card index {} on the held "
                        + "card, {} (the card index that draws her face, or 0 for the generic art)",
                player.getGameProfile().getName(), drawn.size(), drawn,
                CardStamp.indexOf(crafted), CardIndex.indexOf(drawn.get(0)));
    }

    /**
     * {@return every character the pack defines, in registry order}
     *
     * <p>Registry order rather than sorted: it is the order the datapack was loaded in, which is stable for a
     * given pack, and {@link CardRoll} documents that the pool's order is what lets a test pin the mapping.
     *
     * <p>Empty when the registry is not bound (outside a world) — on the server during a craft it always is,
     * but a total answer is cheaper than a null check at the call site.
     */
    private static List<ResourceLocation> characterPool() {
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        if (registry.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(registry.get().keySet()));
    }
}
