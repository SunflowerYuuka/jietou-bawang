package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.gacha.CardRoll;
import com.astrum.pcrd.impl.gacha.TokenExchange;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * 记忆结晶: what a duplicate character card turns into, and what a hundred of them buy.
 *
 * <h2>The crystal itself is what you right-click</h2>
 * 「100个，又不是放在合成台，是背包里右键直接使用的东西。」 — there is <b>no crafting recipe and no
 * intermediate voucher</b>. a full stack of crystals are spent by right-clicking the stack that holds them, and
 * one character card comes back. The previous revision turned them into a 选择券 item first, which then
 * asked the player to choose; the human rejected that twice: the voucher could not be obtained at all
 * (nothing produced it), and once it opened the book there was nobody to pick, because the book shows the
 * characters you <em>own</em> and the whole point is the one you do not.
 *
 * <h2>The two halves, and where each rule lives</h2>
 * <ul>
 *   <li><b>The cost</b> is {@link TokenExchange}: how many are in the stack, and the exact take. It reads
 *       <b>one stack</b> — the one being clicked — not the whole inventory, which is why the item's own
 *       count number is the answer to "how many more do I need".</li>
 *   <li><b>The roll</b> is {@link CardRoll#roll} with {@link CardRoll.Mode#GUARANTEED_PREFER_UNOWNED}:
 *       uniform among the characters the player does not have (「优先未拥有」, a better roll than crafting
 *       an ordinary card), falling back to the ordinary lottery once everything is owned so the crystals
 *       are never dead weight.</li>
 * </ul>
 *
 * <p>Nothing is decided here: the item supplies the registry, the roster and the RNG. That is the same
 * split {@code CardRollOnCraft} keeps, and it is what makes the distribution testable without a world.
 *
 * <h2>Server side only, and nothing spent on a refusal</h2>
 * The exchange writes an inventory and gives an item, so only the server may do it. A stack of 99 is told
 * how many more are needed and loses nothing; the crystals are taken only after the roll has produced a
 * card, so a bad datapack costs the player nothing rather than a full stack of crystals.
 */
@ApiStatus.Internal
public class MemoryTokenItem extends Item {

    /** Item name, {@code item.astrum.memory_token}. */
    public static final String KEY_NAME = "item.astrum.memory_token";

    public static final String KEY_TOOLTIP = "astrum.token.tooltip";

    /** Shown when the right-click happens with fewer than a hundred. Takes the missing count. */
    public static final String KEY_NOT_ENOUGH = "astrum.token.not_enough";

    /** Shown when a hundred became a card. Takes the character's name. */
    public static final String KEY_GRANTED = "astrum.token.granted";

    /** Shown when the roll produced nothing at all — a pack that defines no characters. */
    public static final String KEY_NO_CHARACTERS = "astrum.token.no_characters";

    /** Shown when the book is quarantined: the roll's input cannot be read, so nothing is spent. */
    public static final String KEY_UNREADABLE = "astrum.token.unreadable";

    /** The second tooltip line: what a full stack of crystals actually give you. */
    public static final String KEY_PREFER_UNOWNED = "astrum.token.prefer_unowned";

    /** Colours as RGB: {@code net.minecraft.ChatFormatting} cannot be imported in this build. */
    private static final int COLOR_OK = 0x55FF55;
    private static final int COLOR_HINT = 0xAAAAAA;
    private static final int COLOR_REFUSED = 0xFFFF55;

    /** {@return every key this item asks for}, for {@code LangParityTest} */
    public static Set<String> langKeys() {
        // LinkedHashSet, not Set.of: a duplicated key would throw inside a static initialiser.
        return Set.copyOf(new LinkedHashSet<>(List.of(KEY_NAME, KEY_TOOLTIP, KEY_NOT_ENOUGH,
                KEY_GRANTED, KEY_NO_CHARACTERS, KEY_UNREADABLE, KEY_PREFER_UNOWNED,
                recycleHintKey())));
    }

    /**
     * @param properties item properties, supplied by {@link AstrumItems}
     */
    public MemoryTokenItem(Properties properties) {
        super(properties);
    }

    /**
     * Spends a full stack of crystals from this stack and hands back one character card, rolled to prefer the
     * characters the player does not own.
     *
     * <p>The client returns {@code SUCCESS} unconditionally so the arm swings whether or not the count is
     * there; the server decides and speaks.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer server)) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        int have = TokenExchange.countTokens(stack);
        if (!TokenExchange.canExchange(have)) {
            server.displayClientMessage(Component.translatable(KEY_NOT_ENOUGH,
                    TokenExchange.missing(have)).withColor(COLOR_REFUSED), false);
            return InteractionResultHolder.fail(stack);
        }

        // The inputs the roll depends on. Read before anything is spent, so a datapack that defines no
        // characters costs the player nothing rather than a full stack of crystals.
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        if (registry.isEmpty() || registry.get().keySet().isEmpty()) {
            return refuse(server, stack, Component.translatable(KEY_NO_CHARACTERS));
        }
        Roster roster = server.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            // Reading the book would throw, and a throw here would eat the crystals. The message says which
            // thing is wrong rather than reporting the roll as failed.
            return refuse(server, stack, Component.translatable(KEY_UNREADABLE));
        }

        List<ResourceLocation> pool = List.copyOf(new LinkedHashSet<>(registry.get().keySet()));
        Optional<ResourceLocation> drawn = CardRoll.roll(CardRoll.Mode.GUARANTEED_PREFER_UNOWNED,
                pool, roster.entries().keySet(), server.getRandom());
        if (drawn.isEmpty()) {
            return refuse(server, stack, Component.translatable(KEY_NO_CHARACTERS));
        }
        ResourceLocation characterId = drawn.get();

        // Take the cost only once the roll succeeded, and by the same stack that was clicked.
        int taken = TokenExchange.consumeTokens(stack);

        ItemStack card = new ItemStack(AstrumItems.CHARACTER_CARD.get(), 1);
        // CardStamp, not a bare `set`: a rolled card carries both the character id and the
        // custom_model_data that draws her face on the icon. One method, shared with the crafting lottery.
        CardStamp.stamp(card, characterId);
        if (!server.getInventory().add(card)) {
            // Full inventory: the card is dropped next to the player rather than lost. The crystals are
            // already gone at this point, so a silent failure here would delete a hundred of them.
            server.drop(card, false);
        }

        String name = Optional.ofNullable(registry.get().get(characterId))
                .map(CharacterDef::displayName)
                .orElseGet(characterId::toString);
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP,
                SoundSource.PLAYERS, 0.8f, 1.6f);
        server.displayClientMessage(Component.translatable(KEY_GRANTED, name).withColor(COLOR_OK), false);
        Astrum.LOGGER.info("astrum: {} exchanged {} memory tokens for a character card naming {} "
                        + "(unowned preferred, {} of {} characters owned)",
                server.getGameProfile().getName(), taken, characterId, roster.entries().size(), pool.size());
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /**
     * Tells the player why nothing happened and spends nothing.
     *
     * <p>One method rather than a message at each failure site, so "do not spend" cannot be forgotten at
     * one of them — the same shape {@code CharacterCardItem.refuse} uses.
     */
    private static InteractionResultHolder<ItemStack> refuse(ServerPlayer player, ItemStack stack,
            Component message) {
        player.displayClientMessage(message.copy().withColor(COLOR_REFUSED), false);
        return InteractionResultHolder.fail(stack);
    }

    /** Says what the item is for; a 16px gem cannot. */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(Component.translatable(KEY_TOOLTIP, TokenExchange.TOKENS_PER_VOUCHER)
                .withColor(COLOR_HINT));
        // Why the crystals are worth collecting at all: they buy a character the player does not have yet,
        // which is a different promise from the card lottery's and is the one that makes a duplicate welcome.
        tooltip.add(Component.translatable(KEY_PREFER_UNOWNED).withColor(COLOR_HINT));
    }

    /**
     * {@return the message key for "a duplicate card becomes a token"}, for the tooltip of the card
     *
     * <p>Lives here rather than on the card because the token is the thing the sentence is about.
     */
    public static String recycleHintKey() {
        return "astrum.token.recycle_hint";
    }
}
