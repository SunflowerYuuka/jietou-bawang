package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.impl.client.CharacterBookScreen;
import com.astrum.pcrd.impl.party.BookText;
import java.util.List;
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
 * <h2>One book, no theme</h2>
 * This item used to exist three times ({@code _sakura}, {@code _forest}) and looked its own theme up from
 * its registry id through {@code BookTheme}. The human deleted that feature — 「将2个皮肤版本的角色册道具删除，
 * 仅保留最开始的那个」 — so there is one book, one id ({@code astrum:character_book}) and nothing to resolve.
 * The item therefore does not read the registry at all any more, which is one fewer way for the item and the
 * screen to disagree.
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
     * @param properties item properties, supplied by {@link AstrumItems}
     */
    public CharacterBookItem(Properties properties) {
        super(properties);
    }

    /**
     * Opens the character book.
     *
     * <p>No arguments and no theme: there is one book, and the screen it opens is the same one whatever
     * stack was used.
     *
     * <p>Returns {@code SUCCESS} on both sides rather than {@code sidedSuccess}: nothing was used up, and
     * a {@code CONSUME} result on the server would tell the client the stack changed when it did not.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            CharacterBookScreen.open();
        }
        return InteractionResultHolder.success(stack);
    }

    /** Tells the player what the item does, since nothing on a 16px icon can. */
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(Component.translatable(BookText.KEY_TOOLTIP).withColor(COLOR_HINT));
    }
}
