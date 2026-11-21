package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.attach.ShieldState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;

/**
 * 護盾 as a real, self-expiring effect: absorption that <b>adds to</b> vanilla's golden-apple absorption.
 *
 * <h2>What this effect contributes that no bookkeeping alone could</h2>
 * Two things, and both are needed for the human's requirement (「两者可以叠加」):
 *
 * <ol>
 *   <li><b>A {@code MAX_ABSORPTION} ceiling.</b> That attribute's base value is <b>0</b>, and
 *       {@code LivingEntity.setAbsorptionAmount} clamps into {@code [0, getMaxAbsorption()]}. So absorption
 *       cannot exist unless something raises the ceiling — vanilla's {@code absorption} does it with
 *       {@code ADD_VALUE +4} a level. That is also why <b>vanilla's expiry deletes a shield that lives only
 *       in the float</b>: removing its modifier drops the ceiling, and
 *       {@code LivingEntity.onAttributeUpdated} clamps the float down to meet it. Ours therefore carries
 *       its own {@code ADD_VALUE} modifier, so the ceiling survives vanilla's removal.</li>
 *   <li><b>A per-tick re-mirror.</b> Vanilla's {@code absorption} writes the float with
 *       {@code max(current, 4 * (1 + amplifier))} in {@code onEffectStarted}, so a golden apple drunk
 *       <em>after</em> our shield would set the total to its own 4 points. {@link #applyEffectTick} puts the
 *       sum back on the very next tick, which is why both icons appear and the hearts really do add up.
 *       The sum it puts back is {@code ours + everyone else's}, so this repair is not specific to vanilla's
 *       effect either: a mod whose absorption was written over by the previous mirror gets its part back on
 *       the same tick, because the other side is read from the float rather than from a list of known
 *       sources ({@code ShieldPool.foreignShare}).</li>
 * </ol>
 *
 * <h2>Why the ceiling is a constant and not the pool</h2>
 * A {@code MobEffect} is constructed once for the whole registry, and the only per-instance value its
 * attribute curve receives is the integer <b>amplifier</b>. A shield's size is a float measured against the
 * receiver's max health (a fifth of a 3377-health boss is 675), so it cannot be carried there without
 * either overflowing 255 or losing the precision that makes tier 5 mean "a full bar". A curve could read
 * one entity's attachment through a static, but that is per-entity state in a static container — the thing
 * this project forbids — and it would be wrong for the second of two simultaneous grants.
 *
 * <p>So the ceiling is {@link #CEILING}, the attribute's own maximum. That is the honest choice rather than
 * a lazy one: <b>the ceiling only has to be no lower than the pool</b>, the pool is what says how much
 * shield exists, and once our modifier is gone vanilla's clamp removes exactly our part and nothing else.
 * The visible cost is that a client which asks {@code MAX_ABSORPTION} while a shield is up is told 2048; the
 * absorption actually drawn is the float, which we keep at {@code ours + vanilla's}.
 *
 * <h2>Why the tick is legitimate here</h2>
 * {@code applyEffectTick} is called by vanilla for <b>one instance on one entity</b>, from
 * {@code MobEffectInstance.tick} → {@code LivingEntity.tickEffects}, which walks that entity's own effect
 * map. The entity is handed in: there is no entity walk and no level poll, so this is not the tick polling
 * the project forbids. It is the same argument {@code PanicEffect} makes, one class over, and it needs
 * {@code shouldApplyEffectTickThisTick == true} for the same reason.
 */
@ApiStatus.Internal
public final class ShieldEffect extends MobEffect {

    /** Particle tint: the pale gold of absorption hearts, so the HUD column reads as a shield. */
    public static final int COLOUR = 0xF2D26B;

    /**
     * The ceiling our effect grants on {@code MAX_ABSORPTION}: the attribute's own maximum.
     *
     * <p>{@code RangedAttribute("generic.max_absorption", 0.0, 0.0, 2048.0)} in 21.1.265, so 2048 is the
     * largest legal value and cannot itself be clamped. See the class javadoc for why a constant rather
     * than the pool.
     */
    public static final double CEILING = 2048.0;

    /** The {@code MAX_ABSORPTION} modifier's id. Fixed, so a re-application replaces rather than adds. */
    public static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", ShieldPool.MODIFIER_PATH);

    /**
     * Builds the effect.
     *
     * <p>{@code BENEFICIAL}, because a shield is something done <em>for</em> its holder and vanilla draws
     * the category as the HUD's green column.
     */
    public ShieldEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        // A curve rather than a flat amount so the constant is stated once and every amplifier answers the
        // same: `amplifier` is not "how big", it is the tier's ordinal, and the size lives in the pool.
        addAttributeModifier(Attributes.MAX_ABSORPTION, MODIFIER_ID,
                AttributeModifier.Operation.ADD_VALUE, amplifier -> CEILING);
    }

    /**
     * Re-mirrors the float every tick, which is what repairs a vanilla overwrite.
     *
     * @return always {@code true}: a shield ends when its clock runs out
     */
    @Override
    public boolean applyEffectTick(LivingEntity holder, int amplifier) {
        if (holder.level().isClientSide) {
            return true;
        }
        ShieldState state = Shields.stateOf(holder);
        if (!state.isPresent()) {
            return true;
        }
        Shields.reflect(holder, state);
        return true;
    }

    /** Vanilla calls {@code applyEffectTick} only when this answers true, and a shield needs every tick. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }
}
