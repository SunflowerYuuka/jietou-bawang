package com.astrum.pcrd.impl.gacha;

import com.astrum.pcrd.impl.item.AstrumItems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Counting and spending a full stack of 记忆结晶</b>, on a real {@link ItemStack}.
 *
 * <h2>One stack, and why that is the design rather than a shortcut</h2>
 * 「一组，又不是放在合成台，是背包里右键直接使用的东西。」 — the thing the player clicks is the crystal, so the
 * count is <b>that stack's</b>. This test replaced a version that summed the whole {@link Inventory} and
 * drained several slots; the human's sentence makes that reading wrong, and the wider one also has failure
 * modes the narrow one cannot have (a half-spend across two slots, a count that changes between the check and
 * the take, and a player with 50 + 50 who can never fire the interaction from either).
 *
 * <h2>What is now possible, and must stay possible</h2>
 * Because the requirement equals the stack size ({@code AstrumItemsTest} pins that), a full stack is exactly
 * one purchase and a stack of 63 is exactly a refusal. Those are the two arms this file checks, plus the
 * off-by-one boundary between them, which is the number the whole loop hangs on.
 */
class TokenExchangeTest {

    /** A stack of {@code count} crystals, built without a world. */
    private static ItemStack tokens(int count) {
        return new ItemStack(AstrumItems.MEMORY_TOKEN.get(), count);
    }

    /** The number the human gave: one full stack of 64, so 63 is a refusal. */
    @Test
    void aCardCostsAFullStack() {
        assertEquals(64, TokenExchange.TOKENS_PER_VOUCHER, "「64个换一个自选角色卡」，一组的数量");
        assertFalse(TokenExchange.canExchange(63), "63 is not enough");
        assertTrue(TokenExchange.canExchange(64), "64 is exactly enough");
        assertTrue(TokenExchange.canExchange(65), "and more is fine");
        assertFalse(TokenExchange.canExchange(0), "an empty stack is not enough");
    }

    /**
     * <b>The requirement is the stack size, so one full stack is one purchase.</b>
     *
     * <p>This is the invariant that makes the interaction possible at all: if the item stacked to less than
     * {@link TokenExchange#TOKENS_PER_VOUCHER}, no stack could ever meet the requirement and the crystal would
     * be unspendable. It is asserted here as well as in {@code AstrumItemsTest} because the two numbers live
     * in different packages and only a test can tie them together.
     */
    @Test
    void theRequirementFitsInOneStack() {
        assertEquals(TokenExchange.TOKENS_PER_VOUCHER,
                new ItemStack(AstrumItems.MEMORY_TOKEN.get()).getMaxStackSize(),
                "the price must fit in one stack, or the right-click can never succeed");
    }

    /** The count is the clicked stack's, and is by item rather than by slot. */
    @Test
    void theCountIsThatStacksAndNothingElse() {
        assertEquals(0, TokenExchange.countTokens(null), "null is answered, not thrown");
        assertEquals(0, TokenExchange.countTokens(ItemStack.EMPTY), "an empty stack holds nothing");
        assertEquals(37, TokenExchange.countTokens(tokens(37)));
        assertEquals(0, TokenExchange.countTokens(new ItemStack(net.minecraft.world.item.Items.PAPER, 64)),
                "paper is not a memory crystal");
    }

    /**
     * <b>A hundred is spent to zero; ninety-nine is refused and nothing is spent.</b>
     *
     * <p>The two arms the human named, and the boundary is the same pair read twice: {@code consumeTokens}
     * returns the full hundred only from a hundred, and a short stack is reported as <b>zero taken</b> rather
     * than partially drained — "impossible to half-consume" was an explicit requirement, so a short stack must
     * come back untouched rather than with 63 crystals gone and no card.
     */
    @Test
    void aFullStackIsSpentToNothingAndOneShortIsRefused() {
        ItemStack full = tokens(64);
        assertEquals(64, TokenExchange.consumeTokens(full), "a full stack pays the whole price");
        assertTrue(full.isEmpty(), "and is emptied, because the price is the stack size");

        ItemStack short63 = tokens(63);
        assertEquals(0, TokenExchange.consumeTokens(short63), "a short stack pays nothing");
        assertEquals(63, short63.getCount(), "and is left exactly as it was");

        assertEquals(1, TokenExchange.missing(63), "the refusal names one missing crystal");
        assertEquals(0, TokenExchange.missing(64), "a full stack is missing nothing");
        assertEquals(64, TokenExchange.missing(0), "an empty stack is missing the whole price");
    }

    /**
     * <b>A full stack exactly is the boundary, and one more keeps its remainder.</b>
     *
     * <p>Only reachable if a caller builds a stack above the max size (a creative-given or migrated stack),
     * which is why it is asserted rather than assumed: the take must be exactly the price and never "whatever
     * is in the stack", or a player with 65 would lose the spare crystal.
     */
    @Test
    void onlyThePriceIsTakenFromAnOversizedStack() {
        ItemStack over = tokens(65);
        assertEquals(64, TokenExchange.consumeTokens(over));
        assertEquals(1, over.getCount(), "65 - 64 = 1: the spare crystal is the player's");
    }

    /**
     * The whole-inventory count still exists, but only as a diagnostic — never as the price.
     *
     * <p>Kept apart from {@link #theCountIsThatStacksAndNothingElse()} so nobody re-wires the exchange onto it:
     * a stack of 32 + 32 sums to 64 and must still <b>not</b> buy anything, because no single stack the player
     * can click holds a full stack. That is the behaviour change this task made, and this is the assertion that
     * holds it.
     */
    @Test
    void twoHalvesDoNotAddUpToOnePurchase() {
        Inventory inventory = new Inventory(null);
        inventory.setItem(0, tokens(32));
        inventory.setItem(1, tokens(32));
        assertEquals(64, TokenExchange.countAcross(inventory), "the diagnostic sees a full stack in total");
        assertFalse(TokenExchange.canExchange(TokenExchange.countTokens(inventory.getItem(0))),
                "but the stack the player clicks holds 32, so the exchange must refuse");
        assertFalse(TokenExchange.canExchange(TokenExchange.countTokens(inventory.getItem(1))));
        assertEquals(0, TokenExchange.countAcross(null), "a null inventory is answered, not thrown");
    }
}
