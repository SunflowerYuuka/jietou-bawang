package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.Astrum;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * The growth system's <b>entry point</b>: one right-click that feeds a dish, and one session-scoped
 * table that remembers which dish was fed today (spec §6.1, §6.2).
 *
 * <h2>Where the click comes from, and why not eating</h2>
 * The listener is {@link UseItemOnBlockEvent} in the {@code ITEM_BEFORE_BLOCK} phase — what NeoForge
 * posts from {@code ItemStack.onItemUseFirst}, on both logical sides, before the block's own use and
 * before vanilla's eating path is considered. {@link DishClick} carries the full reasoning; the short
 * version is that every dish is a vanilla <em>food</em> item, so hooking {@code Item.use} would make
 * one right-click mean both "eat this" and "feed this", and the player would watch the dish vanish into
 * a bite.
 *
 * <p>Both sides cancel the vanilla paths (so nothing is eaten and no chest opens), and only the
 * <b>logical server</b> writes affection, because the roster lives on the server. That is also why
 * there is no side branch beyond that one: {@code UseItemOnBlockEvent} is only posted where a player
 * interacts, so it never runs on a headless dedicated server without a player.
 *
 * <h2>The throttle, and where it dies</h2>
 * One {@link DishThrottle} per listener instance — i.e. one per server session, not a static — keyed on
 * (player, dish) → the day it was fed. It is cleared in {@link #onServerStopping}, together with
 * nothing else, because it is the only per-session table this class owns. It needs no expiry of its
 * own: a stored day that is not today <em>is</em> the expiry.
 *
 * <h2>What this class does not do</h2>
 * It does not decide who is fed, whether today's allowance is spent, or how much affection moves — all
 * three are {@link DishFeed}/{@link DishThrottle}, which are unit-tested without a game. It also does
 * not consume the dish unless affection actually changed: a refused click keeps the item, because
 * losing a cake to "you already fed that today" would be a bug a player would report immediately.
 */
@ApiStatus.Internal
public final class GrowthEvents {

    /** The one day throttle of this server session. Not static: one server, one table. */
    private final DishThrottle fedDishes = new DishThrottle();

    private GrowthEvents() {
    }

    /** Subscribes this listener to the game bus. Called once, from the mod constructor. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new GrowthEvents());
        Astrum.LOGGER.info("astrum: growth wired — kills pay exp, a right-click feeds a dish");
    }

    /**
     * Feeds the party when a player right-clicks a block holding a dish.
     *
     * <p>The result is {@link ItemInteractionResult#CONSUME} on both sides: on the client so the eat
     * animation does not play for a click the server is about to turn into a feeding, and on the server
     * so the dish is not also eaten by {@code Item.use}. A non-dish returns without touching the event,
     * which is what keeps every other right-click in the game exactly as it was.
     */
    @SubscribeEvent
    public void onUseItemOnBlock(UseItemOnBlockEvent event) {
        ItemStack held = event.getItemStack();
        if (!Dishes.isDish(held)) {
            return;   // not a dish: vanilla keeps the click, untouched
        }
        event.cancelWithResult(ItemInteractionResult.CONSUME);
        if (!DishClick.writes(event.getSide().isClient())) {
            return;   // the client only suppresses the vanilla paths; the roster is the server's
        }
        if (!(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) {
            return;   // a fake player or a detached context: nothing to write
        }

        long gameDay = player.level().getDayTime() / 24_000L;
        // Read the name before the stack shrinks: after a successful feed the held stack is empty, and
        // a debug line saying "fed minecraft:air" is exactly the kind of wrong log that costs an hour.
        String dishName = held.getHoverName().getString();
        DishFeed.Result result = DishFeed.feed(player, held, fedDishes, gameDay);
        player.displayClientMessage(DishClick.message(result.outcome(), dishName, result.characters()),
                false);
        if (result.outcome() == DishFeed.Outcome.FED) {
            consumeOne(player, event.getHand(), held);
        }
        Astrum.LOGGER.debug("astrum: growth: feeding {} on day {} -> {} ({} characters)", dishName,
                gameDay, result.outcome(), result.characters());
    }

    /**
     * Removes the one dish that was eaten, unless the player is in creative.
     *
     * <p>Creative keeps the item: a creative player testing the feeding route should not run out of
     * cake, and losing an item there would look like a bug rather than a cost.
     */
    private static void consumeOne(net.minecraft.server.level.ServerPlayer player, InteractionHand hand,
            ItemStack held) {
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
    }

    /** Drops the day table when the world goes away: one world's dishes are not the next world's. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        fedDishes.clear();
    }

    /** {@return how many (player, dish) allowances the session remembers; for the log and for tests} */
    public int rememberedAllowances() {
        return fedDishes.size();
    }
}
