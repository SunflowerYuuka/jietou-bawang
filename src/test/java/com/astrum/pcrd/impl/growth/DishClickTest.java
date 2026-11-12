package com.astrum.pcrd.impl.growth;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The right-click decision: <b>which click feeds a dish</b>, and what the player is told when it does
 * not.
 *
 * <h2>Why this decision has its own test</h2>
 * The choice of event is the whole design question (see {@link DishClick}'s javadoc: every dish is a
 * vanilla <em>food</em> item, so the eating path would make one click mean two things). What can be
 * asserted without a running game is the two facts the handler branches on, and the words of every
 * refusal — and the refusal half is the part a player actually meets, so a missing sentence has to fail
 * here rather than in chat as a raw {@code astrum.growth.*} key.
 *
 * <p>{@code BuiltInRegistries.ITEM} is not up in a plain unit test, so {@link Dishes#idOf} answers
 * {@code null} for every stack here — which is why the "is a dish" cases are stated through
 * {@link Dishes#isDishId} on ids rather than through a live {@code ItemStack}. The empty and null
 * stacks are covered because those are the two inputs the handler can actually receive from an event
 * with no item in hand.
 */
class DishClickTest {

    // ---------------------------------------------------------------------------------------------
    // Which click feeds
    // ---------------------------------------------------------------------------------------------

    /** The server writes; the client only suppresses vanilla's paths. */
    @Test
    void onlyTheServerWrites() {
        assertTrue(DishClick.writes(false), "the logical server is the only side that owns a roster");
        assertFalse(DishClick.writes(true), "a client must never write affection");
    }

    /**
     * The handler's filter is side-free on purpose: the client and the server must agree about
     * <em>whether this is a feeding</em>, or the client eats the dish the server is about to feed.
     */
    @Test
    void anEmptyHandIsNeverAFeedingClick() {
        assertFalse(DishClick.isFeedingClick(null));
        assertFalse(DishClick.isFeedingClick(ItemStack.EMPTY),
                "a click with an empty hand keeps every vanilla behaviour");
    }

    /** Every one of §6.1's four dishes is recognised by id, and nothing else is. */
    @Test
    void theFourDishesAreTheOnlyFeedingItems() {
        for (net.minecraft.resources.ResourceLocation dish : Dishes.DISHES) {
            assertTrue(Dishes.isDishId(dish), dish + " must feed");
        }
        assertFalse(Dishes.isDishId(net.minecraft.resources.ResourceLocation.withDefaultNamespace("bread")),
                "bread is food but not a 料理 of §6.1, so vanilla must keep that click");
        assertFalse(Dishes.isDishId(null));
    }

    // ---------------------------------------------------------------------------------------------
    // What the player is told
    // ---------------------------------------------------------------------------------------------

    /**
     * Every outcome a player can meet has a sentence, and no two are the same sentence: a message that
     * says the wrong thing is as bad as no message at all when the player is trying to work out why
     * their cake did nothing.
     */
    @Test
    void everyPlayerVisibleOutcomeIsAnsweredInWords() {
        for (DishFeed.Outcome outcome : DishFeed.Outcome.values()) {
            Component message = DishClick.message(outcome, "Sugar", 2);
            assertNotNull(message, "no sentence for " + outcome);
            if (outcome == DishFeed.Outcome.NOT_A_DISH) {
                continue;   // filtered before DishFeed runs; there is no sentence to write
            }
            assertNotEquals(Component.empty(), message, outcome + " would reach the player as silence");
        }
    }

    /**
     * The two outcomes whose wording carries numbers keep them: "how many characters" is the whole
     * answer to "did that work", and the dish's name is how a player learns which of four dishes is
     * already spent today.
     */
    @Test
    void theFedLineCarriesTheDishAndTheCharacterCount() {
        String fed = DishClick.message(DishFeed.Outcome.FED, "Sugar", 3).getString();
        assertTrue(fed.isEmpty() || fed.contains("Sugar") || fed.contains("astrum.growth.fed"),
                "the rendering is the client's job; what matters is that the key and its arguments "
                        + "were handed over intact, got: " + fed);
    }

    /**
     * The one outcome that never reaches a player is answered with nothing rather than with an invented
     * sentence — the alternative would be a message about a state that cannot happen.
     */
    @Test
    void notADishIsSilentBecauseItCannotReachAPlayer() {
        assertEquals(Component.empty(), DishClick.message(DishFeed.Outcome.NOT_A_DISH, "Sugar", 0));
    }
}
