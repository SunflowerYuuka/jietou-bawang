package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「魔法攻击力アップ」: +20% <b>magic</b> attack a tier, to 100% — {@link AttackAreaEffect}'s magic half.
 *
 * <h2>Why the pair is two effect types</h2>
 * See {@link AttackAreaEffect}'s javadoc: once {@link MagicAttack} split the game's one attack stat, "「攻击
 * 力アップ」" became two questions, and a type that raised <em>both</em> could not express a magic-only buff
 * — which is the buff 凯露 actually has. So this is the mirror, and 黑暗之蚀 is its shipped carrier.
 *
 * <h2>Why the operation is {@code ADD_VALUE} here and {@code ADD_MULTIPLIED_TOTAL} there</h2>
 * This is the one place the two halves <b>cannot</b> be built the same way, and the reason is arithmetic
 * rather than taste:
 *
 * <ul>
 *   <li>{@link AttackAreaEffect} puts {@code ADD_MULTIPLIED_TOTAL} on {@code ATTACK_DAMAGE}, whose base is
 *       {@code 2.0} (a fist) up to a sword's — a real number, so "×1.6" means something.</li>
 *   <li>{@code astrum:magic_damage} defaults to <b>0</b>, by design ({@link MagicAttack#DEFAULT_VALUE}): a
 *       non-caster must have no magic attack. And {@code 0 × 1.6 = 0} — a
 *       {@code ADD_MULTIPLIED_TOTAL} buff there would be a registered effect that changes no number, i.e.
 *       exactly the silent no-op this project treats as a defect.</li>
 *   <li>So the rung is applied as an <b>additive contribution</b> of {@code fraction × 2000}, 2000 being the
 *       reference attack every amount in {@code docs/balance-derivation.md} was derived against. Tier 5
 *       ({@code +100%}) is {@code +2000}, which makes {@link MagicAttack#magicScale} answer
 *       {@code 1 + 2000/2000 = 2.0} — double magic damage, the same ceiling the physical buff has.</li>
 * </ul>
 *
 * <p>Both halves therefore mean the same thing to a player ("tier 5 doubles my attack") while using the
 * operation that is arithmetically correct for each attribute's base. The <b>rung</b> is still
 * {@link AttackAreaRules#fractionForAmplifier} — one ladder, one table row in {@code EffectTiers} — and only
 * the conversion from fraction to attribute units differs, in one expression, here.
 *
 * <h2>Bound-safety</h2>
 * The attribute is looked up through {@link MagicAttack#holder()}, which answers empty before the registry
 * is up. An effect constructed in that state carries no modifier rather than throwing — the same
 * {@code isBound()} discipline {@code PartyEffects}' lookups follow. In a real game the attribute register
 * runs before the effect register, so the modifier is always present.
 */
@ApiStatus.Internal
public final class MagicAttackAreaEffect extends MobEffect {

    /**
     * Particle tint: the magic violet the defence buff wears, warmed toward the attack gold.
     *
     * <p>A colour of its own rather than {@link AttackAreaEffect#COLOUR}: the two buffs are told apart on the
     * effect bar by hue, exactly as the four 3010 self-buffs are told apart by their borders. The magic side
     * takes the violet family (magical defence is {@code 0x9A6AE0}) so that "magic" is one hue across the
     * whole stat sheet, brightened here because this raises damage rather than reducing it.
     */
    public static final int COLOUR = 0xB07AE6;

    /** Registry path: {@code astrum:magic_attack_area}. */
    public static final String PATH = "magic_attack_area";

    /**
     * This effect's modifier id, on {@link MagicAttack#MAGIC_DAMAGE}.
     *
     * <p>Delegated to {@link MagicAttack#BUFF_MODIFIER_ID} rather than re-declared: that class owns "the one
     * flat contribution to the magic stat", and {@code MagicAttack.setBonus} writes under the same id, so the
     * operator path and the buff path replace each other instead of compounding — which is what a fixed id
     * is for.
     */
    public static final ResourceLocation MODIFIER_ID = MagicAttack.BUFF_MODIFIER_ID;

    /** Builds the effect: one {@code ADD_VALUE} contribution of the rung's worth of magic attack. */
    public MagicAttackAreaEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        MagicAttack.holder().ifPresent(this::addMagicModifier);
    }

    /**
     * Adds the additive modifier, split out so the constructor has one guarded call site.
     *
     * @param attribute the registered {@code astrum:magic_damage} attribute holder
     */
    private void addMagicModifier(net.minecraft.core.Holder<Attribute> attribute) {
        addAttributeModifier(attribute, MODIFIER_ID, AttributeModifier.Operation.ADD_VALUE,
                MagicAttackAreaEffect::pointsForAmplifier);
    }

    /**
     * {@return the magic-attack points amplifier {@code amplifier} contributes}
     *
     * <p>{@code fraction × REFERENCE_ATTACK}, where the fraction is
     * {@link AttackAreaRules#magicFractionForAmplifier} — the <b>magic</b> rung, <b>26%</b> a tier, not the
     * physical 20%. So a rung is {@code 0.26 × 2000 = 520} points, tier 5 is {@code 2600} points, and
     * {@link MagicAttack#scaleOf} turns that into a damage scale of {@code 1 + 2600/2000 = 2.30} — against
     * the physical buff's {@code 2.00} at the same tier. See {@link AttackAreaRules#MAGIC_STEP} for why the
     * two steps differ and for the corrected arithmetic: an earlier note said 2.60, which would need a 32%
     * rung, not 26%.
     *
     * <p>Total and never throwing: vanilla evaluates this on every attribute recomputation, so a hostile
     * amplifier must clamp (it does, inside {@code magicFractionForAmplifier}) rather than throw from a
     * value function.
     *
     * @param amplifier the instance's amplifier, {@code 0} = tier 1 = +26%
     */
    public static double pointsForAmplifier(int amplifier) {
        return AttackAreaRules.magicFractionForAmplifier(amplifier) * MagicAttack.REFERENCE_ATTACK;
    }
}
