package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * バリアブレイク: removes <b>all</b> of an entity's absorption hearts.
 *
 * <h2>What absorption actually is, and why it is one call</h2>
 * Absorption is not a set of per-source bubbles — it is one {@code float} on the entity
 * ({@code LivingEntity.absorptionAmount}, read through {@code getAbsorptionAmount}) with a ceiling from the
 * {@code MAX_ABSORPTION} attribute. Damage eats it before health does. So "remove all the absorption" is one
 * {@link LivingEntity#setAbsorptionAmount} call, and there is nothing to enumerate, no per-effect ordering,
 * and no way for a second shield source to survive it.
 *
 * <h2>Why the vanilla {@code absorption} effect goes too</h2>
 * {@code MobEffects.ABSORPTION} carries an {@code ADD_VALUE} modifier on the {@code MAX_ABSORPTION}
 * attribute of <b>+4 per level</b>, and its own tick refills the pool up to that ceiling. Zeroing the float
 * while the modifier is still there would leave the ceiling raised and the next golden apple — or the next
 * tick of the same effect — would refill what the skill just removed, i.e. the skill would visibly do
 * nothing. So the effect is removed as well, and the removal is what makes the strip stick.
 *
 * <h2>What it honestly does not do</h2>
 * It does <b>not</b> touch a shield that is not absorption: an entity in a boat, a boss's own phase shield
 * from another mod, or a damage-immunity window. Minecraft has one absorbable shield pool and this removes
 * it; anything else a particular mod calls a "barrier" is that mod's to remove. The report says so rather
 * than claiming a general barrier break.
 *
 * <h2>One tier</h2>
 * {@code EffectTiers} gives this effect {@code maxTier = 1}, so the tier is always 1 after clamping. The
 * brief replaced the 20/40/60/80% ladder with "remove all", and a percentage of an all-or-nothing pool would
 * be four numbers nobody designed.
 */
@ApiStatus.Internal
public final class ShieldBreak {

    private ShieldBreak() {
    }

    /**
     * {@return how much absorption {@code fraction} of {@code current} removes}
     *
     * <p>Pure, and deliberately still a fraction even though the only tier is 1.0: the brief's first shape
     * was a ladder, the effect kept the arithmetic, and a future second tier would then be a data change
     * rather than a code change. {@code fraction = 1.0} answers exactly {@code current} — "all of it" — so
     * the one shipped rung and the general form agree.
     *
     * @param current  the entity's absorption now
     * @param fraction the share to remove, {@code 1.0} = all
     */
    public static float amountToRemove(float current, double fraction) {
        if (!(current > 0.0F) || !Float.isFinite(current)) {
            return 0.0F;
        }
        if (!(fraction > 0.0) || !Double.isFinite(fraction)) {
            return 0.0F;
        }
        double removed = current * Math.min(fraction, 1.0);
        return (float) Math.min(removed, current);
    }

    /**
     * {@return the absorption {@code entity} has after the strip}
     *
     * <p>Never throws: it runs inside a damage sequence, where an exception would cost the rest of the wheel.
     * A dead or removed entity answers {@code 0} without being touched.
     *
     * @param entity   the entity whose shield is broken
     * @param fraction the share to remove, {@code 1.0} = all
     */
    public static float strip(LivingEntity entity, double fraction) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return 0.0F;
        }
        try {
            float before = entity.getAbsorptionAmount();
            float removed = amountToRemove(before, fraction);
            if (!(removed > 0.0F)) {
                Astrum.LOGGER.debug("astrum: shield break on {} found no absorption to remove (had {})",
                        entity.getName().getString(), before);
                return before;
            }
            // Removing the effect BEFORE zeroing the pool: the effect's modifier is what raises the
            // MAX_ABSORPTION ceiling, and its own tick refills the pool. Zeroing first would let one tick
            // put the bubble back before the removal landed.
            MobEffectInstance absorption = entity.getEffect(MobEffects.ABSORPTION);
            boolean hadEffect = entity.removeEffect(MobEffects.ABSORPTION);
            entity.setAbsorptionAmount(before - removed);
            Astrum.LOGGER.debug("astrum: shield break on {} removed {} of {} absorption ({}%), effect "
                            + "present={} removed={}, left {}",
                    entity.getName().getString(), removed, before,
                    Math.round(Math.min(fraction, 1.0) * 100.0), absorption != null, hadEffect,
                    entity.getAbsorptionAmount());
            return entity.getAbsorptionAmount();
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: shield break threw on {}", entity.getName().getString(), e);
            return entity.getAbsorptionAmount();
        }
    }
}
