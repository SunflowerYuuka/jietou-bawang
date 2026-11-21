package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.effect.PartyEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Every generic effect this mod registers is a mechanic, not a status a player reads — so it is hidden from
 * both of vanilla's effect lists.</b>
 *
 * <h2>The human's rule, and what it replaced</h2>
 * 「药水栏不是把每个通用效果都显示出来，是仅显示角色的技能图标，是技能图标不是效果图标。」
 *
 * <p>So the effect bar's content is <b>one row per fired skill</b> — {@link SkillEntryClient}'s family, one effect
 * type per (character, skill) since task 59 — and nothing else. The bar used to contain thirteen more rows, one per generic effect
 * ({@code 攻击力提升}, {@code 魔法防御力}, {@code 行动速度}, {@code 护盾} …), because a generic effect is a real
 * {@code MobEffect} and vanilla draws every registered effect a player carries. Each of those rows was also
 * <em>magenta</em>: the per-effect sprites were deleted when a generic effect stopped having its own picture, and
 * a row with no sprite is the missing-texture checkerboard.
 *
 * <p>The party slots were already hidden this way ({@link PartyEffectClient}) for a different reason — 「who is
 * with me」 belongs in our own row above the gauge. This class is the second half of the same decision: a generic
 * effect is a <b>quantity the damage maths reads</b>, not a sentence a player reads off a column, so no list of
 * them should exist either.
 *
 * <h2>One table, thirteen effects — not thirteen overrides</h2>
 * The families live in {@link PartyEffects}: {@code holders()} (the five party slots) and
 * {@link PartyEffects#mechanicHolders()} (every generic effect). The second list is the <b>declaration</b> —
 * the sprite generator's manifest and {@code EffectSpriteResourceTest}'s source of ids — so this class reads it
 * rather than repeating the paths. A fourteenth generic effect therefore arrives hidden and gets a sprite in the
 * same commit, without touching this file. Writing one subclass per effect, or a per-effect anonymous class, is
 * exactly the duplication this project keeps paying for.
 *
 * <h2>The effects keep working; only the drawing changed</h2>
 * Hiding is a <b>drawing predicate</b> and nothing else. {@code Gui.renderEffects} and
 * {@code EffectRenderingInventoryScreen} filter their lists through these two answers; the instance stays on the
 * entity, stays registered, stays visible to {@code /effect}, and every reader — {@code ShieldPool},
 * {@code EffectTiers}, {@code DamageReduction}, {@code ChargeRates}, {@code SkillEntries} — reads the effect
 * itself and never its row. {@code HiddenEffectClientTest} proves a hidden effect still applies, precisely
 * because "hidden" and "inert" are easy to confuse.
 *
 * <p>Nothing here calls {@code MobEffect#initializeClient}: that override is {@code forRemoval} since 1.21, and
 * {@link RegisterClientExtensionsEvent} is the supported door.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class HiddenEffectClient implements IClientMobEffectExtensions {

    /**
     * The single instance every generic effect shares.
     *
     * <p>One object rather than one per effect: the answer is the same for all of them, so the registry map
     * holding thirteen references to this instance is the honest shape — the family is one decision.
     */
    static final IClientMobEffectExtensions INSTANCE = new HiddenEffectClient();

    private HiddenEffectClient() {
    }

    /** {@return the instance production registers, so a test asserts the object the client really uses} */
    static IClientMobEffectExtensions instance() {
        return INSTANCE;
    }

    /**
     * Attaches the hider to every generic effect.
     *
     * <p>Iterates {@link PartyEffects#mechanicHolders()} — the registry's own list — so "which effects exist" has
     * one answer. An unbound holder is skipped rather than registered: {@code registerMobEffect} needs a
     * {@code MobEffect}, and a client that somehow reached this before the registry was up is better off with
     * vanilla's row than with an exception during start-up.
     *
     * @param event the mod-bus event that collects client extensions
     */
    static void registerExtensions(RegisterClientExtensionsEvent event) {
        // The exceptions first: {@link PartyEffects#shownHolders()} names the mechanics a player is meant to
        // read — today 计数 alone, whose amplifier is the stack count and therefore prints as an effect level
        // for free. Skipping them here is what keeps the two lists one decision each rather than a subtraction.
        java.util.Set<net.minecraft.world.effect.MobEffect> shown = new java.util.HashSet<>();
        for (var holder : PartyEffects.shownHolders()) {
            if (holder.isBound()) {
                shown.add(holder.value());
            }
        }
        int registered = 0;
        int kept = 0;
        for (var holder : PartyEffects.mechanicHolders()) {
            if (!holder.isBound()) {
                continue;
            }
            if (shown.contains(holder.value())) {
                kept++;
                continue;
            }
            event.registerMobEffect(INSTANCE, holder.value());
            registered++;
        }
        Astrum.LOGGER.info("astrum: {} generic effect(s) filtered out of vanilla's effect bar and inventory "
                + "list, {} kept visible — the bar reports fired skills and stackable counters", registered, kept);
    }

    /**
     * {@return {@code false}: a generic effect is never drawn in vanilla's HUD column}
     *
     * <p>{@code Gui.renderEffects} filters the player's effect list through this predicate, so returning
     * {@code false} is the whole mechanism — the entry is not drawn and the column is honest again by
     * subtraction. The <b>effect itself is untouched</b>: still registered, still on the entity, still read by
     * the damage and charge paths.
     *
     * @param instance the instance vanilla is about to draw; deliberately unread
     */
    @Override
    public boolean isVisibleInGui(MobEffectInstance instance) {
        return false;
    }

    /**
     * {@return {@code false}: nor in the inventory's effect page}
     *
     * <p>Same rule, same reason. The human opened his inventory and read a column of effect names he never
     * asked for; a mechanic with no picture of its own has no business being a row there either.
     *
     * @param instance the instance vanilla is about to draw; deliberately unread
     */
    @Override
    public boolean isVisibleInInventory(MobEffectInstance instance) {
        return false;
    }
}
