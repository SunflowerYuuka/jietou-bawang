package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Roster;
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
import org.jetbrains.annotations.Nullable;

/**
 * The character card: right-click to enrol the character it names into the player's book.
 *
 * <h2>One card, every character, every book</h2>
 * The character is a data component on the stack ({@code astrum:character_id}), not a different item, and
 * the roster the card writes into is the player's — one roster, read by the blue, sakura and forest books
 * alike. So there is exactly one card id, and using it puts the character in front of whichever of the
 * three books the player opens next. There is no per-theme card and no per-character card; the human's
 * rule was 「角色卡一个角色只用做一个，不需要做三种不同的，一个角色卡右键使用后应该是放进三个角色册」.
 *
 * <h2>Four outcomes, and none of them is silent</h2>
 * <ol>
 *   <li><b>Enrolled</b> — the book gains the character at level 1 / exp 0 / affection 1, exactly one
 *       card is spent, a sound plays and the player is told.</li>
 *   <li><b>Already owned</b> — nothing is written and <b>the card is not spent</b>; the player is told
 *       why. Spending it would destroy the item while the book was already correct.</li>
 *   <li><b>The book cannot be read</b> (quarantined save) — nothing is written and the card is not
 *       spent, because the write would land in a view this build cannot read and the player would lose
 *       the card for nothing.</li>
 *   <li><b>The card names nobody</b>, or names a character the pack does not define — the card is not
 *       spent and the reason is given.</li>
 * </ol>
 *
 * <p>Everything is decided on the server: the item is used from both sides but only the server branch
 * writes, spends or speaks, so a client cannot enrol a character by lying. The client branch returns a
 * swing so the press is acknowledged while the server's message arrives.
 *
 * <h2>Why the card is spent per stack rather than per click</h2>
 * {@link ItemStack#shrink(int)} removes exactly one card from the stack — a stack of sixteen enrols one
 * character and leaves fifteen, which is what "a card is a ticket" means. The same contract is why
 * {@link CardEnroll} returns the roster unchanged rather than a flag: the test for "did anything
 * happen" is here, at the only place that may spend the item.
 */
@ApiStatus.Internal
public class CharacterCardItem extends Item {

    // -----------------------------------------------------------------------------------------
    // Translation keys as constants, plus the set the parity test checks against both lang files.
    // A player-visible line whose key is missing shows as a raw key in chat — worse than no line at
    // all — so the keys are data the test can enumerate, not string literals buried in branches.
    // -----------------------------------------------------------------------------------------

    /** Item name, {@code item.astrum.character_card}. One card, so one name key, as a constant. */
    public static final String KEY_NAME = "item.astrum.character_card";

    public static final String KEY_TOOLTIP = "astrum.card.tooltip";
    public static final String KEY_ENROLLED = "astrum.card.enrolled";
    public static final String KEY_ALREADY_OWNED = "astrum.card.already_owned";
    public static final String KEY_QUARANTINED = "astrum.card.quarantined";
    public static final String KEY_NO_CHARACTER = "astrum.card.no_character";
    public static final String KEY_UNKNOWN_CHARACTER = "astrum.card.unknown_character";
    public static final String KEY_NO_REGISTRY = "astrum.card.no_registry";

    /**
     * {@return every key this item asks for}, for {@code LangParityTest}
     *
     * <p>Includes the item's own name. A LinkedHashSet, not {@code Set.of}: a duplicated key would throw
     * inside a static initialiser and take this class down at class-load time in a player's game.
     *
     * <p>It used to walk {@code BookTheme} to build three name keys, back when there were three cards.
     * That loop is gone with the variants — and so is the second tooltip line that named the card's
     * theme, because a card has no theme to name.
     */
    public static Set<String> langKeys() {
        Set<String> keys = new java.util.LinkedHashSet<>(Set.of(KEY_NAME, KEY_TOOLTIP, KEY_ENROLLED,
                KEY_ALREADY_OWNED, KEY_QUARANTINED, KEY_NO_CHARACTER, KEY_UNKNOWN_CHARACTER,
                KEY_NO_REGISTRY));
        return Set.copyOf(keys);
    }

    // Feedback colours as RGB. {@code net.minecraft.ChatFormatting} is unusable in this build: the
    // artifact's copy of that class carries a malformed parameter-annotation attribute and javac
    // rejects it outright ("bad class file ... class file is invalid"), which is why this file uses
    // {@code withColor(int)} instead. AstrumCommand documents the same finding in more detail.
    private static final int COLOR_OK = 0x55FF55;
    private static final int COLOR_REFUSED = 0xFFFF55;
    private static final int COLOR_HINT = 0xAAAAAA;

    /**
     * @param properties item properties, supplied by {@link AstrumItems}
     */
    public CharacterCardItem(Properties properties) {
        super(properties);
    }

    /**
     * Enrols the named character on right-click.
     *
     * <p>Returns {@code SUCCESS} on the client unconditionally (so the arm swings whether the answer
     * turns out to be yes or no) and the real verdict on the server: {@code SUCCESS} when the roster
     * gained the character, {@code FAIL} when it did not — in which case a chat line has already said
     * why, which is the "never fail silently" half of the rule.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer server)) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }

        ResourceLocation characterId = stack.get(AstrumDataComponents.CHARACTER_ID.get());
        if (characterId == null) {
            return refuse(player, stack, Component.translatable(KEY_NO_CHARACTER));
        }

        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        if (registry.isEmpty()) {
            return refuse(player, stack, Component.translatable(KEY_NO_REGISTRY));
        }
        CharacterDef def = registry.get().get(characterId);
        if (def == null) {
            return refuse(player, stack, Component.translatable(KEY_UNKNOWN_CHARACTER,
                    characterId.toString()));
        }

        Roster roster = server.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            return refuse(player, stack, Component.translatable(KEY_QUARANTINED));
        }
        if (CardEnroll.alreadyEnrolled(roster, characterId)) {
            return refuse(player, stack, Component.translatable(KEY_ALREADY_OWNED,
                    def.displayName()));
        }

        Roster enrolled = CardEnroll.enroll(roster, characterId);
        if (enrolled == roster) {
            // Unreachable: alreadyEnrolled() above covers it. Kept as a hard stop rather than a
            // comment, because consuming the card on this path would be a silent loss of the item.
            return refuse(player, stack, Component.translatable(KEY_ALREADY_OWNED,
                    def.displayName()));
        }
        server.setData(Attachments.ROSTER, enrolled);

        // One card, not the stack. At this point the book has already been written, so the order
        // matters: a failure to shrink would cost the player an item, a failure to write would cost
        // them the character.
        stack.shrink(1);
        level.playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP,
                SoundSource.PLAYERS, 0.7f, 1.4f);
        player.displayClientMessage(Component.translatable(KEY_ENROLLED, def.displayName())
                .withColor(COLOR_OK), false);
        Astrum.LOGGER.debug("astrum: {} enrolled {} (level 1, affection 1) with a character card",
                server.getGameProfile().getName(), characterId);
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    /**
     * Tells the player why the card was not used and spends nothing.
     *
     * <p>One method rather than a message string at each failure site so the "do not spend" part cannot
     * be forgotten at one of them.
     *
     * @param player  the player holding the card
     * @param stack   the stack, returned untouched
     * @param message the reason, already built
     */
    private static InteractionResultHolder<ItemStack> refuse(Player player, ItemStack stack,
            Component message) {
        player.displayClientMessage(message.copy().withColor(COLOR_REFUSED), false);
        return InteractionResultHolder.fail(stack);
    }

    /**
     * Shows which character the card grants.
     *
     * <p>The name comes from the datapack ({@code displayName}, which per spec needs no lang key); the
     * registry is only bound while a world is loaded, so outside one the raw id is shown rather than
     * nothing — a card whose tooltip is blank reads as a broken item.
     *
     * <p>There is no theme line any more. It used to tell a player holding two cards apart, and the
     * plain card deliberately showed nothing; with one card for every character and every book there is
     * no theme on the item to name, and a line that said "Sakura" on a card usable with the forest book
     * would have been wrong as well as pointless.
     */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        ResourceLocation characterId = stack.get(AstrumDataComponents.CHARACTER_ID.get());
        if (characterId == null) {
            tooltip.add(Component.translatable(KEY_NO_CHARACTER)
                    .withColor(COLOR_HINT));
        } else {
            CharacterDef def = AstrumApi.characters().map(registry -> registry.get(characterId))
                    .orElse(null);
            String name = def == null ? characterId.toString() : def.displayName();
            tooltip.add(Component.translatable(KEY_TOOLTIP, name)
                    .withColor(COLOR_HINT));
        }
    }
}

