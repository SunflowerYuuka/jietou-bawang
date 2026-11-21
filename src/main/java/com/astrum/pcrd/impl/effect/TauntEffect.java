package com.astrum.pcrd.impl.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「嘲讽」 as a real, self-expiring effect: while the holder has it, the hostile mobs around her take her as
 * their target and keep their eyes on her.
 *
 * <h2>Why the work is in {@code applyEffectTick}, and why that is not a tick listener</h2>
 * {@link MobEffect#applyEffectTick} is called by vanilla for <b>one instance on one entity</b>, from
 * {@code MobEffectInstance.tick}, which {@code LivingEntity.tickEffects} calls while walking that entity's own
 * effect map. The entity is handed in and nothing is enumerated at level scope by the effect machinery; the
 * only query is {@link Taunts}' bounded box around the taunter, which <em>is</em> the taunt's mechanic. That is
 * the sanctioned shape this project documents in {@code Spirits} and {@code ChargeRegenEffect}, one level below
 * the {@code PlayerTickEvent} polling the red line forbids.
 *
 * <h2>The effect carries no numbers of its own</h2>
 * The instance's amplifier is the rung (always 0 on a one-rung ladder), its duration is the clock, and the
 * <b>reach is a constant</b> ({@link Taunts#RADIUS}) because an effect instance has no field for it — so the
 * per-tick sweep needs no state and no side channel. The taunter is the holder itself, which is what makes the
 * state-free design possible at all; see {@code Taunts} for why the caster carries the taunt.
 *
 * <h2>Expiry is event-shaped</h2>
 * {@code MobEffect} in 21.1.265 has no {@code onEffectRemoved}; expiry and explicit removal are NeoForge's
 * {@code MobEffectEvent.Expired} / {@code MobEffectEvent.Remove}, and putting the mobs' targets back lives
 * there ({@code Taunts.onEffectEnded} / {@code onEffectRemoved}), not in a tick that would have to guess
 * whether the instance is still alive. Same split {@code PanicEffect} documents for a mob's {@code noAi}.
 */
@ApiStatus.Internal
public final class TauntEffect extends MobEffect {

    /**
     * Builds the effect.
     *
     * <p>{@code NEUTRAL}: a taunt is not straightforwardly a buff or a debuff — the holder gains the party's
     * safety and pays for it by being hit, which is the tank's trade. Vanilla draws the category as the
     * tooltip colour, and this row is hidden from the effect bar anyway ({@code HiddenEffectClient}), so the
     * category is a label rather than a signal.
     */
    TauntEffect() {
        super(MobEffectCategory.NEUTRAL, Taunts.COLOUR);
    }

    /**
     * Called every tick of the instance's life; re-asserts the taunt.
     *
     * @return always {@code true} — a taunt ends when its clock runs out, not when a tick "fails"
     */
    @Override
    public boolean applyEffectTick(LivingEntity holder, int amplifier) {
        if (holder.level().isClientSide) {
            return true;
        }
        Taunts.pulse(holder);
        return true;
    }

    /** Vanilla calls {@code applyEffectTick} only when this answers true; a taunt needs every tick. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }
}