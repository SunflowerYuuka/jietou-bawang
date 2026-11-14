package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「特大幅度提升自己的物理攻击」: a real, self-expiring attack buff.
 *
 * <h2>Why a vanilla {@link MobEffect} and not a listener</h2>
 * The buff has to <b>end by itself</b>. There are two ways to arrange that: remember the expiry
 * somewhere and undo the change when it passes — which needs something that runs on a clock, i.e. a tick
 * listener, the exact thing this project forbids — or express the buff as a modifier that vanilla
 * already adds and removes over the instance's own lifetime. This is the second: vanilla calls
 * {@link MobEffect#addAttributeModifiers} when an instance is added and
 * {@link MobEffect#removeAttributeModifiers} when it expires or is removed, both using the modifier id
 * declared here. So this mod contains no expiry code at all, nothing to sweep, and no state to leak.
 *
 * <h2>Why {@code ADD_MULTIPLIED_TOTAL}</h2>
 * The wiki's phrasing is a <em>multiplier on her attack</em>, and
 * {@link AttributeModifier.Operation#ADD_MULTIPLIED_TOTAL} is the operation that means exactly that: the
 * modifier value is a fraction of the attribute's fully-computed total, so {@code 0.5} is "+50% of
 * whatever her attack currently is". {@code ADD_VALUE} would be a flat bonus that means something
 * different at every weapon, and {@code ADD_MULTIPLIED_BASE} would ignore gear, which is not what
 * 「特大幅度」 describes.
 *
 * <h2>Why the modifier id is a constant, and why the amount is a curve</h2>
 * Vanilla's {@code addAttributeModifiers} does {@code removeModifier(id)} and then
 * {@code addPermanentModifier(...)} with a modifier built from that id, so a <b>fixed</b> id is what
 * makes re-application replace rather than accumulate — a per-cast id would leave one modifier behind per
 * cast and the buff would compound without bound.
 *
 * <p>The amount is supplied as NeoForge's {@code Int2DoubleFunction} curve over the amplifier rather than
 * as a fixed number, because the amplifier is the only per-instance knob vanilla offers and the hook's
 * multiplier has to travel in it. The curve makes that mapping linear and exact — see
 * {@link AttackBuffRules} for the encoding and for the full stacking rule.
 *
 * <h2>How it is kept distinct from the five party effects</h2>
 * Those are five <em>different</em> {@code MobEffect} types ({@code astrum:party_1..5}) with an infinite
 * duration and no attribute modifier; this is a sixth type with a finite duration and one modifier.
 * Vanilla keys a player's effects by type, so all six appear as six separate entries in the HUD — and
 * because this is the only one that declares an attribute modifier, it is the only one whose removal
 * changes a number. Its duration is real and counts down, where the party effects never expire.
 */
@ApiStatus.Internal
public final class AttackBuffEffect extends MobEffect {

    /** Particle tint: the warm gold the official 自buff skill art uses. */
    public static final int COLOUR = 0xF0C24B;

    /**
     * The one attribute modifier this effect declares.
     *
     * <p>A fixed id, for the replacement-not-accumulation reason in the class javadoc; it is also what
     * {@code removeAttributeModifiers} looks up, so changing it would orphan every live instance.
     */
    public static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath("astrum",
            "attack_buff");

    /**
     * Builds the effect: one {@code ADD_MULTIPLIED_TOTAL} modifier on attack damage, whose fraction is
     * {@code amplifier / 100}.
     *
     * <p>{@code BENEFICIAL} so vanilla draws it in the blue buff column beside the party effects rather
     * than among the debuffs, which is where a player looks for "my attack went up".
     */
    public AttackBuffEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Attributes.ATTACK_DAMAGE, MODIFIER_ID,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                amplifier -> AttackBuffRules.multiplierFor(amplifier) - AttackBuffRules.MIN_MULTIPLIER);
    }
}
