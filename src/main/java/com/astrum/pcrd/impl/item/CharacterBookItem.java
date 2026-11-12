package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.impl.client.CharacterBookScreen;
import com.astrum.pcrd.impl.party.BookText;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.ApiStatus;

/**
 * The 角色册: right-click to open the player's character book — the screen that owns 角色册 / 编队 /
 * 养成 (spec §6.3.1).
 *
 * <h2>Why this item exists at all</h2>
 * Spec §6.3.1's principle: <b>normal play must not need commands</b>. {@code /astrum} is an operator
 * tool — it needs permission level 2, which a LAN host with cheats off cannot grant (spec §7.4) — so
 * every player-facing capability has to be reachable with an item that a fresh player can craft within
 * minutes. This is that item: cheap, not consumed, and the only entry point the book UI has.
 *
 * <h2>What a right-click writes here: nothing</h2>
 * Opening the book is a pure client action. The item touches no attachment, spends nothing and (unlike
 * the card) has no server branch that could disagree with the client about what was used. Every value
 * the screen shows was pushed to the client by the server earlier, and every change the screen asks for
 * goes back as a payload that the server validates — so this class cannot become a second path into
 * player state.
 *
 * <h2>Why the client call is safe on a dedicated server</h2>
 * {@link CharacterBookScreen} is a client class. It is referenced only inside the
 * {@code level.isClientSide()} branch, and a JVM resolves a method reference at the point the
 * instruction executes, not when the enclosing class loads — so on a dedicated server the branch is
 * never taken and the class is never resolved. The mod's own {@code guard} check (no
 * {@code net.minecraft.client} imports outside {@code impl/client}) is what keeps that boundary
 * honest for everything this file does <em>not</em> guard.
 */
@ApiStatus.Internal
public class CharacterBookItem extends Item {

    // Feedback colours as RGB. net.minecraft.ChatFormatting cannot be imported in this build (javac
    // 21.0.8 rejects the artifact's class file); see AstrumCommand for the full finding.
    private static final int COLOR_HINT = 0xAAAAAA;

    /**
     * This book's theme, resolved from its own registry id the first time it is needed.
     *
     * <p>Lazy on purpose: an item is constructed <em>before</em> the registry that will name it is
     * populated, so the id does not exist yet inside the constructor. By the first right-click or tooltip
     * it does, and it never changes after that — so the answer is computed once and cached.
     */
    private BookTheme theme;

    /**
     * @param properties item properties, supplied by {@link AstrumItems}
     */
    public CharacterBookItem(Properties properties) {
        super(properties);
    }

    /**
     * {@return this book's theme}
     *
     * <p>The item id decides it ({@link BookTheme#ofItemId}), which is the whole rule: the three books are
     * three ids, so the icon, the name, the recipe and the panel all follow from one fact instead of two
     * that could disagree.
     */
    BookTheme theme() {
        if (theme == null) {
            theme = BookTheme.ofItemId(BuiltInRegistries.ITEM.getKey(this));
        }
        return theme;
    }

    /**
     * Opens the character book, in the theme of the item that was right-clicked.
     *
     * <p>The theme is read here and nowhere else. Three facts make this the right place:
     * <ul>
     *   <li>the <b>item id</b> decides it ({@link BookTheme#ofItemId}), so a player who owns all three
     *       books gets three different screens from one item class, and the icon, the name, the recipe and
     *       the panel all follow from the same id — they cannot disagree;</li>
     *   <li>the player's hand is only available here; the screen sees neither hand nor stack, so if it
     *       looked the theme up itself it would have to guess which of the player's books was used;</li>
     *   <li>a book whose id this build does not recognise resolves to {@link BookTheme#DEFAULT} rather
     *       than refusing to open.</li>
     * </ul>
     *
     * <p>Returns {@code SUCCESS} on both sides rather than {@code sidedSuccess}: nothing was used up, and
     * a {@code CONSUME} result on the server would tell the client the stack changed when it did not.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            CharacterBookScreen.open(theme());
        }
        return InteractionResultHolder.success(stack);
    }

    /** Tells the player what the item does, since nothing on a 16px icon can. */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        // The theme's name is a second line, not part of the tooltip sentence: the sentence tells every
        // player what the item does, and the name only says which of the three they are holding. Adding
        // it as a format argument would force every language to write a sentence that reads well with
        // three short nouns, and would put the DEFAULT theme's name on the plain book, which reads as
        // "why does my book have a variant name".
        if (theme() != BookTheme.DEFAULT) {
            tooltip.add(Component.translatable(theme().nameKey()).withColor(COLOR_HINT));
        }
        tooltip.add(Component.translatable(BookText.KEY_TOOLTIP).withColor(COLOR_HINT));
    }
}
