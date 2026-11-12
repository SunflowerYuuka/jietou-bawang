package com.astrum.pcrd.impl.growth;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Where</b> a dish is fed from, and what the player is told, as decisions with no game in them
 * (spec §6.2: 送料理).
 *
 * <h2>Why right-click-on-a-block, and not {@code Item.use} (eating)</h2>
 * Every dish this build ships is a <b>vanilla food item</b>: sugar, cocoa beans, a honey bottle, cake.
 * Hooking {@code Item.use} — the eating path — was the obvious first choice and is the wrong one for
 * three reasons, all of them visible to a player:
 *
 * <ol>
 *   <li><b>It fights vanilla eating.</b> The edible ones are consumed by {@code Item.use} with a bite
 *       animation and a hunger payoff. A handler that also fired there would make "right-click with
 *       sugar" mean two things at once, and the player would watch the dish vanish into a bite rather
 *       than into a character.</li>
 *   <li><b>It needs the item class changed.</b> {@code Item.use} is an instance method, so feeding
 *       would mean a custom item per dish (four new items replacing four vanilla ones) or a mixin.
 *       §6.1 says 用 MC 原生材料 — the items stay vanilla.</li>
 *   <li><b>It needs a rule for "aimed at nothing".</b> Eating works while looking at air; feeding a
 *       character is an act aimed at something, and aiming at a block gives the click a target.</li>
 * </ol>
 *
 * <p>So the entry point is {@code UseItemOnBlockEvent} in its {@code ITEM_BEFORE_BLOCK} phase — what
 * NeoForge posts from {@code ItemStack.onItemUseFirst}, on both logical sides, <em>before</em> the
 * block's own use and before vanilla eating is considered. One right-click on any block with a dish in
 * hand feeds the party. A sneak-click is deliberately <b>not</b> required: the sane default is that the
 * dish is fed by the click, and a player who wants the block's own use can use an empty hand. The dish
 * is never eaten on this path — the handler consumes exactly one item, on the server, and only when
 * affection actually changed ({@link DishFeed} answers that).
 *
 * <h2>What is left in this class</h2>
 * The two facts a feeding click is decided by (the side, and whether the stack is a dish) and the words
 * for each outcome. Both are asserted in a unit test, so "which click feeds" and "every refusal has a
 * sentence" are not claims about an event handler nobody can run offline.
 */
@ApiStatus.Internal
public final class DishClick {

    /** Vanilla green: "that worked". */
    private static final int COLOR_OK = 0x55FF55;

    /** Vanilla yellow: "that was refused, here is why". */
    private static final int COLOR_REFUSED = 0xFFFF55;

    private DishClick() {
    }

    /**
     * {@return whether a click on a block with {@code stack} is a feeding click at all}
     *
     * <p>One fact, and it is the same on both sides: the held stack must be a {@link Dishes dish}.
     * Anything else answers {@code false} so vanilla keeps the click — that is what lets a player
     * place a block or open a chest while holding bread, and what keeps this mod out of every other
     * right-click in the game.
     *
     * <p>Deliberately side-free. The side only decides <em>who writes</em> (see {@link #writes}),
     * never <em>whether this is a feeding</em>: a client that disagreed with the server about that
     * would either eat the dish the server is about to feed, or open a chest the server is about to
     * skip.
     *
     * @param stack the stack in the hand that clicked
     */
    public static boolean isFeedingClick(@Nullable ItemStack stack) {
        return Dishes.isDish(stack);
    }

    /**
     * {@return whether the side this click happened on may write affection and spend the dish}
     *
     * <p>Server-only, and stated as its own method rather than as an early {@code return} in the
     * handler: the client still has to <b>suppress</b> vanilla's paths for the same click, so "if
     * client, return" at the top of the handler would leave the client happily biting the cake.
     *
     * @param clientSide whether the current side is the client
     */
    public static boolean writes(boolean clientSide) {
        return !clientSide;
    }

    /**
     * {@return the one sentence a {@link DishFeed.Outcome} deserves, in the player's language}
     *
     * <p>Every outcome but {@code NOT_A_DISH} is answered: a click the game refuses with no sentence is
     * exactly the silent failure §5.5 forbids. {@code NOT_A_DISH} is the one that cannot happen here —
     * {@link #feeds} filtered it — so it is answered with nothing rather than inventing a message
     * nobody sends; the arm stays explicit so a ninth outcome stops compiling instead of reaching a
     * player as a raw {@code astrum.growth.*} key.
     *
     * @param outcome    what {@link DishFeed#feed} did
     * @param dishName   the dish's display name, already resolved by the caller
     * @param characters how many characters gained affection; read by {@code FED} only
     */
    public static Component message(DishFeed.Outcome outcome, String dishName, int characters) {
        return switch (outcome) {
            case FED -> Component.translatable(GrowthText.KEY_FED, dishName, characters)
                    .withColor(COLOR_OK);
            case ALREADY_TODAY -> Component.translatable(GrowthText.KEY_ALREADY_TODAY, dishName)
                    .withColor(COLOR_REFUSED);
            case ALREADY_FULL -> Component.translatable(GrowthText.KEY_ALREADY_FULL, dishName)
                    .withColor(COLOR_REFUSED);
            case NOBODY_TO_FEED -> Component.translatable(GrowthText.KEY_NOBODY, dishName)
                    .withColor(COLOR_REFUSED);
            case BOOK_UNREADABLE -> Component.translatable(GrowthText.KEY_BOOK_UNREADABLE)
                    .withColor(COLOR_REFUSED);
            case NOT_A_DISH -> Component.empty();
        };
    }
}
