package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「行動速度アップ」: <b>movement speed and attack speed</b>, which vanilla's {@code speed} only half does.
 *
 * <h2>The measurement that forced this class</h2>
 * {@code MobEffects.SPEED} declares exactly one attribute modifier, on {@code Attributes.MOVEMENT_SPEED}
 * (read from {@code MobEffects} in 21.1.265). It does not touch {@code Attributes.ATTACK_SPEED}. So a
 * character's 「味方全体の行動速度小アップ」 written as {@code minecraft:speed} moves faster and swings at
 * exactly the same rate — half of what its name says, and the missing half is the one a player feels.
 * There is no vanilla effect that does both, so this mod owns one.
 *
 * <h2>Two modifiers, one amplifier</h2>
 * Both are {@code ADD_MULTIPLIED_TOTAL} under fixed ids, for the reason {@link AttackBuffEffect} documents
 * at length: vanilla calls {@code addAttributeModifiers} on add and {@code removeAttributeModifiers} on
 * expiry, both by id, so a fixed id makes a recast <b>replace</b> rather than accumulate, and there is no
 * expiry bookkeeping and no tick listener anywhere.
 *
 * <p>The fractions come from {@link HasteRules}, which is also where the 3-tier ladder lives: amplifier
 * {@code N - 1} is tier {@code N}, and the bonus is {@code 20% × N} on both attributes. Attack speed is
 * capped by vanilla at 4.0 in the player's own attack-strength arithmetic, so a large amplifier cannot
 * make a player swing infinitely — it saturates, which is the correct degradation.
 */
@ApiStatus.Internal
public final class HasteEffect extends MobEffect {

    /** Particle tint: the pale cyan vanilla's speed uses, because it is the closer of the two mechanics. */
    public static final int COLOUR = 0x7CAFC4;

    /** The movement half's modifier id. Fixed, so a recast replaces instead of stacking. */
    public static final ResourceLocation MOVE_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", "haste_move");

    /** The attack half's modifier id — <b>different</b>, or one would overwrite the other. */
    public static final ResourceLocation ATTACK_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", "haste_attack");

    /**
     * Builds the effect: the same fraction on both attributes, from one curve.
     *
     * <p>{@code BENEFICIAL}, so vanilla draws it in the buff column where a player looks for haste.
     */
    public HasteEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Attributes.MOVEMENT_SPEED, MOVE_ID,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                HasteRules::fractionForAmplifier);
        addAttributeModifier(Attributes.ATTACK_SPEED, ATTACK_ID,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                HasteRules::fractionForAmplifier);
    }
}
