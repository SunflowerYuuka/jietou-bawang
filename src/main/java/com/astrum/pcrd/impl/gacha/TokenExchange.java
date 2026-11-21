package com.astrum.pcrd.impl.gacha;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.item.AstrumItems;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>How many 记忆结晶 a stack holds, and taking exactly a hundred</b> — pure counting, in one place.
 *
 * <h2>One stack, not the whole inventory</h2>
 * The rule is <b>one full stack of crystals</b>, so {@link #TOKENS_PER_VOUCHER} is also the item's
 * stack size ({@code AstrumItems.MEMORY_TOKEN} is {@code stacksTo(64)}) and the whole exchange is one
 * slot. That is deliberately narrower than the previous revision, which summed the entire inventory: the
 * human's sentence is 「一组，又不是放在合成台，是背包里右键直接使用的东西」 — the <em>thing you click</em> is
 * the crystal, so the count is that stack's. A wider count has three failure modes this design cannot
 * have — half a stack spent from one slot and the rest from another, a count that changes between the
 * check and the take, and a player with 50+50 being told they have 100 and then watching two slots drain
 * — and it also means the answer to "how many do I need" is visible on the item's own count number.
 *
 * <h2>Why the number is a rule rather than a literal in the item</h2>
 * {@link #TOKENS_PER_VOUCHER} appears in the tooltip, in the stack size, in the refusal and in the take.
 * One constant, so a rebalance is one edit and the tooltip cannot lie about what the item does.
 */
@ApiStatus.Internal
public final class TokenExchange {

    /**
     * How many 记忆结晶 buy one character card — 「64个换一个自选角色卡」，也就是一组的数量。
     *
     * <p>Also the token's stack size: a requirement larger than the stack could never be met by clicking
     * one stack, which is the whole interaction. {@code AstrumItemsTest} asserts the two agree.
     */
    public static final int TOKENS_PER_VOUCHER = 64;

    private TokenExchange() {
    }

    /**
     * {@return how many tokens {@code stack} holds, {@code 0} for anything that is not a token}
     *
     * @param stack the stack in the player's hand
     */
    public static int countTokens(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        return stack.is(AstrumItems.MEMORY_TOKEN.get()) ? stack.getCount() : 0;
    }

    /**
     * {@return how many tokens {@code inventory} holds}, summed across every slot
     *
     * <p>Only for diagnostics and for a status line — <b>not</b> for the exchange, which reads one stack
     * (see the class javadoc). Keeping the two apart is why the exchange cannot accidentally spend from
     * several slots.
     *
     * @param inventory the player's inventory
     */
    public static int countAcross(Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            total += countTokens(inventory.getItem(slot));
        }
        return total;
    }

    /**
     * {@return whether {@code have} tokens are enough for one card}
     *
     * @param have the token count
     */
    public static boolean canExchange(int have) {
        return have >= TOKENS_PER_VOUCHER;
    }

    /**
     * {@return how many more tokens are needed, {@code 0} when there are already enough}
     *
     * @param have the token count
     */
    public static int missing(int have) {
        return Math.max(0, TOKENS_PER_VOUCHER - have);
    }

    /**
     * Takes exactly {@link #TOKENS_PER_VOUCHER} tokens out of {@code stack}.
     *
     * <p>Only call it when {@link #canExchange} said yes. Like the count, this touches <b>one stack</b> and
     * nothing else: a single {@code shrink}, so there is no state in which half the cost came from one
     * place and half from another.
     *
     * @param stack the stack in the player's hand
     * @return how many were actually taken; short only if the caller skipped {@link #canExchange}
     */
    public static int consumeTokens(@Nullable ItemStack stack) {
        int have = countTokens(stack);
        if (have < TOKENS_PER_VOUCHER) {
            // Unreachable through the item, which checks canExchange() first. Logged rather than thrown
            // because a throw here would leave the player's stack partly spent with no card to show.
            Astrum.LOGGER.warn("astrum: token exchange asked to take {} tokens from a stack of {} — "
                    + "nothing was taken", TOKENS_PER_VOUCHER, have);
            return 0;
        }
        stack.shrink(TOKENS_PER_VOUCHER);
        return TOKENS_PER_VOUCHER;
    }
}
