package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.effect.PartyBuffName;
import com.astrum.pcrd.impl.effect.PartyEffects;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.EffectRenderingInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.GatherEffectScreenTooltipsEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * What a party effect looks like: the character's <b>official avatar</b> as the icon, her <b>skill
 * name</b> as the name.
 *
 * <h2>Where each half is actually visible (verified in the 21.1.265 sources)</h2>
 * <ul>
 *   <li><b>HUD</b>: {@code Gui.renderEffects} draws only the 24x24 icon slots — in 1.21 it renders
 *       <b>no text at all</b>. So the HUD shows five icons, one per equipped character, and the name
 *       is not on the HUD. {@link #renderGuiIcon} draws the avatar inside the slot.</li>
 *   <li><b>Inventory</b>: {@code EffectRenderingInventoryScreen} draws an icon and a name per row,
 *       plus a hover tooltip. {@link #renderInventoryIcon} and {@link #renderInventoryText} draw the
 *       avatar and the skill name there, and {@link #onEffectTooltip} replaces the tooltip's first
 *       line, because the default name comes from {@code MobEffect.getDisplayName()} which is
 *       <em>per type</em> and cannot know which character stands in the slot.</li>
 * </ul>
 *
 * <h2>Why the avatar can be drawn at all</h2>
 * {@code IClientMobEffectExtensions} offers a render hook for the HUD icon and the inventory icon;
 * returning {@code true} suppresses vanilla's own sprite. That is the override the effect needs, so no
 * per-slot atlas sprite is used in the normal case. When the slot's character has no face — an older
 * pack, or the client has not received the party sync yet — the hook returns {@code false} and vanilla
 * falls back to {@code astrum:textures/mob_effect/party_<n>.png} — a generic slot rune that the mod
 * ships, rather than the purple missing-texture checker.
 *
 * <p>The face is {@link CharacterDef#face()}, the same call the 角色册 makes, so the HUD icon and the
 * book portrait are one picture. That is not a convenience: the defect of 2026-11-14 was a character
 * whose book drew the generic {@code item/character_card} while her own face was already packaged and
 * drawn on the HUD, because the two sites read different fields.
 *
 * <p>The avatar is blitted with {@code uWidth == textureWidth}, which samples the <b>whole</b> texture
 * whatever its pixel size, so a pack may ship a 32x32 or a 128x128 avatar and both are drawn complete
 * instead of cropped to the top-left corner.
 *
 * <h2>Why the slot, not the character id, is the identity</h2>
 * One extension instance is registered per effect type, and the effect type is per slot
 * ({@code PartyEffectSlots}). The extension therefore never asks "which character is this?" — it asks
 * "what is in my slot right now?", reads {@code ClientPartyCache}, and draws that. Two slots holding
 * the same character draw two entries with the same avatar and the same skill name, which is exactly
 * what five distinct effect types buy (see {@code PartyEffects}' note about vanilla merging instances
 * of one type).
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class PartyEffectClient implements IClientMobEffectExtensions {

    /** Side of the icon vanilla draws inside its 24x24 HUD slot (3px of frame on each side). */
    private static final int ICON_SIZE = 18;

    /** HUD frame around the icon: 3px, matching vanilla's {@code blit(x + 3, y + 3, ...)}. */
    private static final int HUD_INSET = 3;

    /** Inventory row: vanilla blits its sprite at {@code (x, y + 7)}; the text column starts at x + 28. */
    private static final int INVENTORY_TOP = 7;
    private static final int TEXT_COLUMN = 28;
    private static final int TEXT_TOP = 6;
    private static final int DURATION_TOP = 16;

    /** Vanilla's two label colours: white name, grey duration. */
    private static final int COLOUR_NAME = 0xFFFFFF;
    private static final int COLOUR_DURATION = 0x835571;

    /** The 0-based party slot this extension serves. */
    private final int slot;

    private PartyEffectClient(int slot) {
        this.slot = slot;
    }

    /**
     * {@return the extension that serves party slot {@code slot}}
     *
     * <p>Package-private, and the only way a caller outside {@link #registerExtensions} obtains one — which
     * is what lets {@code PartyRowLayerRegistrationTest} assert the hide-from-the-bar predicate for
     * <b>every</b> registered slot without constructing its own equivalent. A test that built its own
     * instance would pass while production registered a different one.
     *
     * @param slot 0-based party slot
     */
    static IClientMobEffectExtensions forSlot(int slot) {
        return new PartyEffectClient(slot);
    }

    /**
     * Attaches one extension per slot effect. Called from {@code ClientEvents.register} on the mod bus
     * with {@code RegisterClientExtensionsEvent} — the non-deprecated replacement for overriding
     * {@code MobEffect#initializeClient}, which is {@code forRemoval} since 1.21.
     */
    static void registerExtensions(RegisterClientExtensionsEvent event) {
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            event.registerMobEffect(forSlot(slot), PartyEffects.holderFor(slot));
        }
        Astrum.LOGGER.info("astrum: {} party effect icons registered (avatar + skill name)", Party.SLOTS);
    }

    /**
     * Replaces the hovered inventory tooltip's name line with the slot's current skill name.
     *
     * <p>A listener rather than part of the extension interface: the tooltip is built by vanilla from
     * {@code getDisplayName()} before this event fires, and NeoForge fires
     * {@code GatherEffectScreenTooltipsEvent} at exactly that point with a mutable list. Line 0 is the
     * name, line 1 the duration; only line 0 is replaced.
     */
    static void onEffectTooltip(GatherEffectScreenTooltipsEvent event) {
        OptionalInt slot = PartyEffects.slotOf(event.getEffectInstance().getEffect().value());
        if (slot.isEmpty()) {
            return;
        }
        event.getTooltip().set(0, PartyBuffName.nameFor(definition(slot.getAsInt()), slot.getAsInt()));
    }

    @Override
    public boolean renderGuiIcon(MobEffectInstance instance, Gui gui, GuiGraphics graphics, int x,
                                 int y, float z, float alpha) {
        ResourceLocation avatar = avatar(slot);
        if (avatar == null) {
            return false;
        }
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        blitWholeTexture(graphics, avatar, x + HUD_INSET, y + HUD_INSET);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        return true;
    }

    /**
     * <b>The five party effects are not drawn in either of vanilla's effect lists.</b>
     *
     * <p>They are shown in our own {@link PartyStatusHud} row above the UB gauge instead. The reason is not
     * taste: vanilla's bar is a column of 24px sprites with a duration, built for potions. Five entries for
     * effects the player did not cast pushed real effects off the column, and 24px is not a size at which a
     * party member's face, name and remaining time all read. A party is 「who is with me」, which is a
     * different question from 「what is on me」 and deserves its own row.
     *
     * <p>Returning {@code false} from {@code isVisibleInGui} is the whole mechanism: vanilla's
     * {@code Gui.renderEffects} filters the player's effect list through this predicate, so the entry is not
     * drawn and the bar is honest again by subtraction. The <b>effect itself is untouched</b> — it is still
     * registered, still on the entity, still visible through {@code /effect} and still the server's record of
     * who is buffed ({@code PartyBuffs}). Only the drawing moved.
     *
     * <p><b>The inventory list hides them too</b> ({@link #isVisibleInInventory}): the human opened his
     * inventory and found 姬塔 / 可可萝 / 佩可莉姆 / 凯露 sitting at the end of the potion list
     * (「药水栏还有角色头像的药水效果。」). A party member is not a potion and no list of potions should name
     * one, so the two predicates now agree. That reverses an earlier decision — the inventory was argued to
     * be "where a player looks things up" — and the argument was wrong for the same reason the HUD one was:
     * the row is the lookup, and vanilla's list is not a second copy of it.
     *
     * <p>The five <em>temporary</em> effects this mod also registers (攻击提升, 行动速度, 魔法防御力, TP
     * 获得量提升, 攻击力提升) are a different kind of thing and stay in vanilla's list, because they are
     * buffs on the player with a real duration, not a statement about who is in the party.
     */
    @Override
    public boolean isVisibleInGui(MobEffectInstance instance) {
        logHiddenOnce(HIDDEN_SURFACE_BAR);
        return false;
    }

    /**
     * <b>The inventory's effect list hides the party entries as well.</b>
     *
     * <p>Same rule, same reason as {@link #isVisibleInGui}: a party slot is not an effect on the player, and
     * vanilla's list is a list of effects on the player. Keeping it here as well as on the HUD is the point
     * — one answer for every surface, so there is nowhere left for a party avatar to appear among the
     * potions.
     *
     * <p>The effect type remains registered, and {@link #renderInventoryIcon} / {@link #renderInventoryText}
     * remain implemented: they are unreachable through vanilla now, but a {@code MobEffectInstance} of one of
     * these types can still be produced by a command, and a surface that asks the extension how to draw one
     * should get an avatar rather than a purple missing-texture checker.
     */
    @Override
    public boolean isVisibleInInventory(MobEffectInstance instance) {
        logHiddenOnce(HIDDEN_SURFACE_INVENTORY);
        return false;
    }

    /** {@code Gui.renderEffects}: the HUD bar, the surface the party row replaced. */
    static final String HIDDEN_SURFACE_BAR = "HUD";

    /** {@code EffectRenderingInventoryScreen}: the inventory's effect page, the list he screenshotted. */
    static final String HIDDEN_SURFACE_INVENTORY = "inventory";

    /**
     * The {@code slot:surface} pairs whose filtering has already been logged.
     *
     * <p>Bounded by {@code Party.SLOTS * 2}, and only ever added to, so it cannot grow with play time: the
     * two surfaces are the only two places vanilla asks these predicates, which is what makes the key set
     * finite.
     */
    private static final java.util.Set<String> LOGGED_HIDDEN =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Logs, once per slot and surface, that vanilla asked whether to draw this party effect and got "no".
     *
     * <p>The hiding is an <em>absence</em>, and an absence is the one kind of fix a screenshot cannot show:
     * a picture of an empty corner proves nothing, and neither does a picture of a list that no longer holds
     * four names — it could equally be a list that never had them. A line naming the slot, the surface and
     * the moment vanilla asked is the only evidence that the filter ran.
     *
     * <p>Once per pair, not once per query: vanilla asks on every frame the inventory is open, and a line at
     * 300 fps would be a formatting cost for a fact that does not move.
     *
     * @param surface {@link #HIDDEN_SURFACE_BAR} or {@link #HIDDEN_SURFACE_INVENTORY}
     */
    private void logHiddenOnce(String surface) {
        if (!LOGGED_HIDDEN.add(slot + ":" + surface)) {
            return;
        }
        Astrum.LOGGER.info("astrum: party effect slot {} filtered out of vanilla's {} effect list",
                slot, surface);
    }

    @Override
    public boolean renderInventoryIcon(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                       GuiGraphics graphics, int x, int y, int blitOffset) {
        ResourceLocation avatar = avatar(slot);
        if (avatar == null) {
            return false;
        }
        blitWholeTexture(graphics, avatar, x, y + INVENTORY_TOP);
        return true;
    }

    /**
     * Draws the row's name and duration.
     *
     * <p>The duration is drawn too, not only the name: returning {@code true} suppresses the whole
     * vanilla row, and a name with no duration would look like a different bug.
     */
    @Override
    public boolean renderInventoryText(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                       GuiGraphics graphics, int x, int y, int blitOffset) {
        CharacterDef definition = definition(slot);
        if (definition == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        graphics.drawString(minecraft.font, PartyBuffName.nameFor(definition, slot),
                x + TEXT_COLUMN, y + TEXT_TOP, COLOUR_NAME);
        graphics.drawString(minecraft.font, MobEffectUtil.formatDuration(instance, 1.0F, tickRate()),
                x + TEXT_COLUMN, y + DURATION_TOP, COLOUR_DURATION);
        return true;
    }

    /**
     * Draws an avatar scaled into an {@link #ICON_SIZE}-pixel square.
     *
     * <p>{@code uWidth = textureWidth = ICON_SIZE} normalises the source coordinates to
     * {@code 0..1}, so the whole image is sampled regardless of its real resolution — passing the
     * texture's true size instead would crop every avatar larger than the icon.
     */
    private static void blitWholeTexture(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
        graphics.blit(ArtFallback.resolve(texture), x, y, ICON_SIZE, ICON_SIZE, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE,
                ICON_SIZE, ICON_SIZE);
    }

    /**
     * {@return the slot's icon texture, or {@code null} when the client does not know it}
     *
     * <p>Reads {@link TierAvatar#forPartySlot}, the same resolution the 角色册's party slot and the HUD's gauge
     * circle use, so the picture on the HUD row and the picture in the book are one picture and cannot drift
     * apart. That is not cosmetic: the defect of 2026-11-14 was a character whose <em>book</em> drew a generic
     * card and whose HUD row drew her face, because the two read different fields.
     *
     * <p>And it <b>follows the affection tier</b> (「角色头像要跟随好感度进行变化，符合前面的1* 3* 6*的要求」):
     * this is the party-effect icon, one of the four surfaces the brief names for the 1★/3★/6★ rule. It used to
     * read {@link CharacterDef#face()}, which is the one plain avatar field and would have pinned her there.
     */
    private static @Nullable ResourceLocation avatar(int slot) {
        CharacterDef definition = definition(slot);
        Optional<ResourceLocation> character = ClientPartyCache.slot(slot);
        if (definition == null || character.isEmpty()) {
            return null;
        }
        return TierAvatar.forPartySlot(definition, character.get()).orElse(null);
    }

    /**
     * {@return the character currently in {@code slot}, or {@code null} when the client cannot know}
     *
     * <p>Read from {@link ClientPartyCache}, the party push the wheel and the book already use: one
     * source of truth for "who is in slot i" on the client. The character definition is read through
     * {@link AstrumApi#characters()}, which is empty outside a world.
     */
    private static @Nullable CharacterDef definition(int slot) {
        Optional<ResourceLocation> character = ClientPartyCache.slot(slot);
        if (character.isEmpty()) {
            return null;
        }
        return AstrumApi.characters()
                .map(registry -> registry.get(character.get()))
                .orElse(null);
    }

    /** {@return the world's tick rate, or the vanilla default outside a world} */
    private static float tickRate() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 20.0F : minecraft.level.tickRateManager().tickrate();
    }
}