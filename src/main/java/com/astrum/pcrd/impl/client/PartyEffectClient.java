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
     * Attaches one extension per slot effect. Called from {@code ClientEvents.register} on the mod bus
     * with {@code RegisterClientExtensionsEvent} — the non-deprecated replacement for overriding
     * {@code MobEffect#initializeClient}, which is {@code forRemoval} since 1.21.
     */
    static void registerExtensions(RegisterClientExtensionsEvent event) {
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            event.registerMobEffect(new PartyEffectClient(slot), PartyEffects.holderFor(slot));
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
        graphics.blit(texture, x, y, ICON_SIZE, ICON_SIZE, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE,
                ICON_SIZE, ICON_SIZE);
    }

    /**
     * {@return the slot's icon texture, or {@code null} when the client does not know it}
     *
     * <p>Reads {@link CharacterDef#face()}, the same resolution the 角色册's roster cell and party slot
     * use, so the picture on the HUD row and the picture in the book are one picture and cannot drift
     * apart. That is not cosmetic: the defect of 2026-11-14 was a character whose <em>book</em> drew a
     * generic card and whose HUD row drew her face, because the two read different fields.
     */
    private static @Nullable ResourceLocation avatar(int slot) {
        CharacterDef definition = definition(slot);
        return definition == null ? null : definition.face().orElse(null);
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