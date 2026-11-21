package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「物理・魔法防御力を小ダウン」 / 「大ダウン」: a real, self-expiring armour debuff — the debuff counterpart of
 * {@link AttackBuffEffect}.
 *
 * <h2>Why this mod has to own the effect</h2>
 * Vanilla has <b>no</b> effect that makes its holder take more damage. The candidates and why each is
 * wrong:
 *
 * <ul>
 *   <li>{@code minecraft:weakness} — lowers the victim's <b>attack</b>. That is the opposite half of the
 *       stat sheet from "takes more damage": a weakened mob hits for less and still absorbs the same
 *       damage, so the skill's whole function would be lost while the HUD claimed otherwise.</li>
 *   <li>{@code minecraft:slowness} / {@code mining_fatigue} — nothing to do with damage.</li>
 *   <li>{@code minecraft:instant_damage} — deals damage once; it is not a debuff and does not persist,
 *       so a 「小ダウン」 that lasts 12 seconds cannot be expressed.</li>
 * </ul>
 *
 * <p>So this is this mod's own effect, built exactly the way {@code AttackBuffEffect} is: a fixed modifier
 * id and NeoForge's level curve over the amplifier. Vanilla calls {@code addAttributeModifiers} when the
 * instance is added and {@code removeAttributeModifiers} when it expires, so there is no expiry
 * bookkeeping here and <b>no tick listener</b> — the same reason that class gives in full.
 *
 * <h2>Two modifiers, because the skill names two defences</h2>
 * 「物理・魔法防御力」 is, in Minecraft terms, both halves of what reduces incoming damage: {@code ARMOR}
 * (which most physical damage is checked against) and {@code ARMOR_TOUGHNESS} (which softens armour
 * penetration). A single modifier on one of them would leave the other untouched and the skill would
 * visibly do half of what its text says. Both are {@code ADD_MULTIPLIED_TOTAL} with the same negative
 * fraction, so one amplifier scales both by the same proportion — which is what one skill doing
 * 「小ダウン」 to one target means.
 *
 * <h2>Why the fraction is negative and how it travels</h2>
 * {@code ADD_MULTIPLIED_TOTAL} means the modifier value is a fraction of the attribute's computed total,
 * so {@code -0.15} is "15% less armour". The amplifier is the only per-instance knob vanilla offers, and
 * {@link DefenceDownRules} owns the encoding — the same shape {@code AttackBuffRules} uses, in the
 * opposite direction.
 */
@ApiStatus.Internal
public final class DefenceDownEffect extends MobEffect {

    /** Particle tint: the cold violet a defence debuff reads as, distinct from the buff's gold. */
    public static final int COLOUR = 0x8A6BD1;

    /** The {@code ARMOR} modifier's id. Fixed, so a re-application replaces rather than accumulates. */
    public static final ResourceLocation ARMOUR_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", "defence_down_armour");

    /** The {@code ARMOR_TOUGHNESS} modifier's id, for the second half of the same penalty. */
    public static final ResourceLocation TOUGHNESS_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", "defence_down_toughness");

    /**
     * Builds the effect: two negative {@code ADD_MULTIPLIED_TOTAL} modifiers, each {@code tier × 20%} deep,
     * on armour and on armour toughness.
     *
     * <p>{@code HARMFUL}, so vanilla draws it in the red debuff column where a player looks for "that mob
     * is weakened".
     */
    public DefenceDownEffect() {
        super(MobEffectCategory.HARMFUL, COLOUR);
        addAttributeModifier(Attributes.ARMOR, ARMOUR_MODIFIER_ID,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> -DefenceDownRules.penaltyForAmplifier(amplifier));
        addAttributeModifier(Attributes.ARMOR_TOUGHNESS, TOUGHNESS_MODIFIER_ID,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> -DefenceDownRules.penaltyForAmplifier(amplifier));
    }
}
