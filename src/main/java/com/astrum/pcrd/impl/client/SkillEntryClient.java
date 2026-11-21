package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.effect.PartyEffects;
import com.astrum.pcrd.impl.effect.SkillEffectTypes;
import com.astrum.pcrd.impl.party.SkillRows;
import java.util.List;
import java.util.Optional;
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
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>What one fired skill looks like in vanilla's effect bar: that skill's own picture, that skill's own name,
 * and its combined effect as the tooltip.</b>
 *
 * <h2>The three surfaces, and what each one shows</h2>
 * <ul>
 *   <li><b>HUD</b> ({@link #renderGuiIcon}): {@code Gui.renderEffects} draws 24×24 icon slots and <b>no text</b>
 *       in 1.21, so the HUD shows the skill's picture alone. That is exactly the human's first sentence —
 *       「在药水栏上显示的图标也只有一个技能图标」.</li>
 *   <li><b>Inventory list</b> ({@link #renderInventoryIcon} and {@link #renderInventoryText}): the row's picture
 *       and its name, plus the clock underneath. The effect list under the name was removed at the human's
 *       request (「效果的数值描述消失」) and is not re-added.</li>
 *   <li><b>Hover tooltip</b>: <b>vanilla's own</b> — the name and the clock. A listener that replaced it with
 *       one line per state existed (unwired from task 49, wired in task 59) and the human removed it again
 *       (「撤掉」), because listing 「全体物攻 持续 12 秒」 on hover is the same text he asked to be deleted from
 *       the row.</li>
 * </ul>
 *
 * <h2>Where the picture and the name come from — one identity, one join</h2>
 * The effect <b>type is the (character, skill) identity</b> ({@link SkillEffectTypes}), so this extension reads
 * it off the instance instead of asking a party slot who is in it and reading a row index out of the amplifier.
 * The join then runs forward, not backward: the identity's skill tag selects the row from {@link SkillRows}, and
 * that row is the same one the character book draws, so the picture ({@link SkillArt#pictureOf} of the row's own
 * {@code icon} with her face at her tier as the fallback) and the name ({@code SkillLadder.nameAt(tier)}) are the
 * skill's <em>now</em>, including any 「+」 an upgrade gave it. Nothing here re-derives a name from
 * {@code CharacterDef.displayName()} or a picture from an effect type.
 *
 * <p><b>One extension object serves every type.</b> The old per-slot class needed one instance per slot because
 * "which slot am I?" was the only question the registry could answer; with the identity in the type the answer
 * is on the instance, so 91 registrations pointing at one stateless object is the honest shape.
 *
 * <h2>Why this family is hidden from neither of vanilla's lists</h2>
 * {@link PartyEffectClient} hides its five effects because a party member is 「who is with me」 and belongs in our
 * own row. This family is <b>shown</b>: 「会在药水栏出现持续时间的，是技能提供的状态」 says the bar is where a
 * skill's state is reported. {@code isVisibleInGui}/{@code isVisibleInInventory} therefore answer {@code true},
 * and the two families are opposites on exactly this predicate.
 *
 * <h2>Fallbacks, and the jar/pack split for the sprite</h2>
 * The chain is {@link SkillArt}'s: the skill's own picture, else her face at her tier, else
 * {@link ArtFallback#PLACEHOLDER} (「请安装资源包」). Vanilla is <b>never</b> allowed to draw instead — both icon
 * hooks and the text hook answer {@code true} unconditionally — and that is a deliberate difference from the
 * party family, which hands back {@code false} so a committed slot rune appears.
 *
 * <p>What the jar <em>does</em> ship is one self-drawn 64×64 <b>placeholder per type</b>, at
 * {@code textures/mob_effect/skill/<character>/<skill>.png} (91 copies of {@code gui/effect_icon.png}, written by
 * {@code tools/gen_skill_effect_types.py}). Those files are for the atlas, not for this class: JEI and any other
 * consumer ask the {@code mob_effects} atlas for the sprite named by the effect's registry id, and a missing one
 * is the magenta checkerboard. So the split is:
 *
 * <pre>
 *   jar   : 91 copies of one placeholder  -> the atlas always has something to draw (JEI, recipe viewers)
 *   pack  : the real skill icons          -> this extension draws the skill's own art
 *   no pack: ArtFallback's placeholder    -> 「请安装资源包」, never the checkerboard
 * </pre>
 *
 * <p>The official art is derived from Cygames' assets, so it lives only in the pack (``.gitignore`` lists it and
 * ``build.gradle`` excludes it from the jar); the placeholder is the mod's own drawing and ships.
 *
 * <h2>The one defect that came from declining</h2>
 * {@code renderInventoryText} used to answer {@code false} when the character could not be resolved, which handed
 * the row back to vanilla — whose name is {@code getDisplayName()} <b>plus its own effect-level suffix</b>. That
 * produced 「技能效果 1 二级」: a slot number and a fake strength. The hooks now always answer {@code true} and the
 * fallback is text from {@link SkillEntryText#title}; and the amplifier is no longer a row index at all, so
 * vanilla's suffix (if it ever ran) would describe nothing.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class SkillEntryClient implements IClientMobEffectExtensions {

    /** Side of the icon vanilla draws inside its 24×24 HUD slot (3px of frame on each side). */
    private static final int ICON_SIZE = 18;

    /** HUD frame around the icon: 3px, matching vanilla's own {@code blit(x + 3, y + 3, ...)}. */
    private static final int HUD_INSET = 3;

    /** Inventory row: vanilla blits its sprite at {@code (x, y + 7)}; the text column starts at x + 28. */
    private static final int INVENTORY_TOP = 7;
    private static final int TEXT_COLUMN = 28;
    private static final int TEXT_TOP = 6;
    private static final int EFFECT_TOP = 16;

    /** Vanilla's two label colours: white name, grey detail. */
    private static final int COLOUR_NAME = 0xFFFFFF;
    private static final int COLOUR_DIM = 0x835571;

    /**
     * The single instance every skill-entry type shares.
     *
     * <p>One object rather than one per type — the answer is the same code for all 91, and the identity it needs
     * arrives on the instance. The old per-slot design needed 5 instances because the object was asked "which
     * slot am I?"; nothing asks that any more.
     */
    static final SkillEntryClient INSTANCE = new SkillEntryClient();

    private SkillEntryClient() {
    }

    /** {@return the instance production registers, so a test asserts the object the client really uses} */
    static IClientMobEffectExtensions instance() {
        return INSTANCE;
    }

    /**
     * Attaches the one extension to <b>every</b> skill-entry type.
     *
     * <p>Iterates {@link PartyEffects#skillHolders()} — the registry's own list, one entry per (character, skill) —
     * so "which types exist" has one answer and a character the human adds cannot be forgotten here. An unbound
     * holder is skipped rather than registered: {@code registerMobEffect} needs a {@code Holder}, and a client
     * that somehow reached this before the registry was up is better off with vanilla's rune than with an
     * exception at start-up.
     */
    static void registerExtensions(RegisterClientExtensionsEvent event) {
        int registered = 0;
        for (var holder : PartyEffects.skillHolders()) {
            if (!holder.isBound()) {
                continue;
            }
            event.registerMobEffect(INSTANCE, holder);
            registered++;
        }
        Astrum.LOGGER.info("astrum: {} skill effect icons registered (skill picture + skill name)", registered);
    }

    @Override
    public boolean renderGuiIcon(MobEffectInstance instance, Gui gui, GuiGraphics graphics, int x, int y,
                                 float z, float alpha) {
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        blitWholeTexture(graphics, picture(instance), x + HUD_INSET, y + HUD_INSET);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        return true;
    }

    @Override
    public boolean renderInventoryIcon(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                       GuiGraphics graphics, int x, int y, int blitOffset) {
        blitWholeTexture(graphics, picture(instance), x, y + INVENTORY_TOP);
        return true;
    }

    /**
     * Draws the row's name and its clock.
     *
     * <p><b>This method never returns {@code false}.</b> It used to when the client could not resolve the
     * character, which handed the row back to vanilla — and vanilla's answer is {@code getDisplayName()} plus its
     * own effect-level suffix, i.e. 「技能效果 1 二级」. Declining is what produced that defect, so the fallback now
     * happens here, in text, through {@link SkillEntryText#title}.
     */
    @Override
    public boolean renderInventoryText(MobEffectInstance instance, EffectRenderingInventoryScreen<?> screen,
                                       GuiGraphics graphics, int x, int y, int blitOffset) {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<SkillEffectTypes.Identity> identity =
                SkillEffectTypes.identityOf(instance.getEffect().value());
        Component name = identity
                .map(id -> SkillEntryText.title(definition(id), tierOf(id), id.skill()))
                .orElseGet(() -> Component.translatable(SkillEffectTypes.FALLBACK_NAME_KEY));
        graphics.drawString(minecraft.font, name, x + TEXT_COLUMN, y + TEXT_TOP, COLOUR_NAME);
        // The clock only. No {@code SkillEntryText#summary} call: the human deleted the effect list from this
        // surface (「效果的数值描述消失」), so drawing it would be re-adding what he removed.
        graphics.drawString(minecraft.font, MobEffectUtil.formatDuration(instance, 1.0F, tickRate()),
                x + TEXT_COLUMN, y + EFFECT_TOP, COLOUR_DIM);
        return true;
    }

    /**
     * {@return the picture to draw for {@code instance} — the skill's own, else her face, never {@code null}}
     *
     * <p>Every link of {@link SkillArt}'s chain is resolved here, in one place, so the HUD and the inventory row
     * cannot pick different pictures for the same instance. The identity comes from the type, so the row is found
     * by skill tag — the coupling to a row index travelled in the amplifier and is gone.
     */
    private ResourceLocation picture(MobEffectInstance instance) {
        Optional<SkillEffectTypes.Identity> identity =
                SkillEffectTypes.identityOf(instance.getEffect().value());
        if (identity.isEmpty()) {
            return ArtFallback.PLACEHOLDER;
        }
        CharacterDef definition = definition(identity.get());
        if (definition == null) {
            return ArtFallback.PLACEHOLDER;
        }
        int tier = tierOf(identity.get());
        SkillRows.Row row = SkillEntryText.rowOf(definition, tier, identity.get().skill());
        if (row == null) {
            // A skill tag this pack no longer defines for her — a type from a jar a little older than the data.
            // Her face at her tier is the honest answer, through the same join the party rune and the book's
            // party slot use, so the three cannot disagree about which picture is hers.
            return SkillArt.drawable(TierAvatar.forPartySlot(definition, identity.get().character()));
        }
        return SkillArt.drawable(SkillArt.pictureOf(row.icon(), definition.face(tier)));
    }

    /**
     * {@return the tier to resolve this skill's row at}
     *
     * <p>{@link TierAvatar#tierOf} — the same roster row the party slot and the book read — is the one copy of
     * "which of her three rungs is live", and it answers the first tier for a character the client has no
     * progress row for. A tier resolved anywhere else would let the bar and the book disagree about which 「+」
     * version of a skill is live.
     */
    private static int tierOf(SkillEffectTypes.Identity identity) {
        return TierAvatar.tierOf(identity.character());
    }

    /** {@return the character definition the type names, or {@code null} when the pack does not define her} */
    private static @Nullable CharacterDef definition(SkillEffectTypes.Identity identity) {
        return AstrumApi.characters()
                .map(registry -> registry.get(identity.character()))
                .orElse(null);
    }

    /**
     * Draws an image scaled into {@link #ICON_SIZE} pixels.
     *
     * <p>{@code uWidth = textureWidth = ICON_SIZE} normalises the source coordinates to {@code 0..1}, so the
     * whole picture is sampled whatever its real resolution — {@code SkillArt}'s pictures are 64×64 and the
     * character faces are larger, and passing a texture's true size instead would crop both.
     */
    private static void blitWholeTexture(GuiGraphics graphics, ResourceLocation texture, int x, int y) {
        graphics.blit(texture, x, y, ICON_SIZE, ICON_SIZE, 0.0F, 0.0F, ICON_SIZE, ICON_SIZE,
                ICON_SIZE, ICON_SIZE);
    }

    /** {@return the world's tick rate, or the vanilla default outside a world} */
    private static float tickRate() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 20.0F : minecraft.level.tickRateManager().tickrate();
    }
}