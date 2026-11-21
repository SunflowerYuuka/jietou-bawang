package com.astrum.pcrd.impl.effect;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>召唤精灵</b> as a real, self-expiring effect: {@code astrum:spirit} puts a ring of particle spirits around
 * its holder for as long as it lasts. No entity is created.
 *
 * <h2>Why the work lives in {@code applyEffectTick}</h2>
 * The same argument {@code PanicEffect} and {@code ShieldEffect} make, and it is the reason this feature does
 * not need a tick listener: {@code MobEffect#applyEffectTick} is called by vanilla for <b>one instance on one
 * entity</b>, from {@code MobEffectInstance.tick} → {@code LivingEntity.tickEffects}, which walks that
 * entity's own effect map. The entity is handed in; there is no entity walk, no level poll, and nothing to
 * enumerate. So the orbit is advanced by the effect's own clock, and the effect's own clock is the thing that
 * already ends it.
 *
 * <h2>Server-driven, so other players see it</h2>
 * {@code ServerLevel.sendParticles} broadcasts to every client tracking the chunk — a client-side particle
 * would be visible only to the summoner, which for a party game is the wrong half of the audience. The
 * {@code isClientSide} guard is therefore not an optimisation: it is what stops a client from also drawing its
 * own copy, and it is the same 端隔离 rule {@code PanicEffect} follows.
 *
 * <h2>The budget</h2>
 * One particle per spirit per tick, spirits = amplifier + 1 capped by {@link Spirits#countForAmplifier}. Tier 5
 * is five particles a tick and one {@code sendParticles} call — see {@link Spirits} for the arithmetic and for
 * why nothing on this path searches for entities.
 */
@ApiStatus.Internal
public final class SpiritEffect extends MobEffect {

    /**
     * Builds the effect.
     *
     * <p>{@code BENEFICIAL}: a summon is a buff on its holder, and vanilla draws the category as the HUD's
     * green column.
     */
    public SpiritEffect() {
        super(MobEffectCategory.BENEFICIAL, Spirits.COLOUR);
    }

    /**
     * Draws the ring for this tick.
     *
     * <p>One {@code sendParticles} call per tick for the <b>whole ring</b> rather than one per spirit: the
     * offsets are computed here and sent as zero-offset points, which is one packet instead of five. Every
     * position is derived from the holder's own coordinates and the level's tick counter, so the ring is
     * stateless — there is no per-entity orbit bookkeeping to leak when the effect ends.
     *
     * @return always {@code true}: a summon ends when its clock runs out, not because a tick "failed"
     */
    @Override
    public boolean applyEffectTick(LivingEntity holder, int amplifier) {
        if (holder.level().isClientSide) {
            return true;
        }
        int count = Spirits.countForAmplifier(amplifier);
        if (count <= 0 || !(holder.level() instanceof ServerLevel level)) {
            return true;
        }
        long gameTime = level.getGameTime();
        double baseY = holder.getY() + holder.getBbHeight() * Spirits.HEIGHT_FRACTION;
        for (int index = 0; index < count; index++) {
            double angle = Spirits.angleAt(gameTime, index, count);
            double x = holder.getX() + Math.cos(angle) * Spirits.RADIUS;
            double z = holder.getZ() + Math.sin(angle) * Spirits.RADIUS;
            double y = baseY + Spirits.bobAt(gameTime, index);
            level.sendParticles(ParticleTypes.ENCHANT, x, y, z, Spirits.PARTICLES_PER_SPIRIT_PER_TICK,
                    0.0, 0.0, 0.0, 0.0);
        }
        return true;
    }

    /** Vanilla calls {@code applyEffectTick} only when this answers true, and an orbit needs every tick. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }
}
