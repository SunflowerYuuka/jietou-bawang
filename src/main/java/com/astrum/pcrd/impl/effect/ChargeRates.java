package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.ub.ChargeKernel;
import java.util.List;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The single query for "what is this player's charge rate right now"</b> — 「TP上昇」.
 *
 * <h2>One question, one answer</h2>
 * Charge gain is multiplied by {@link com.astrum.pcrd.api.v1.EffectType#CHARGE_RATE_UP}, whose rungs are
 * {@code +25%} each ({@code 1.25× … 2.25×}). The multiplier is a property of the <b>player</b>, not of the
 * source that is paying them, so there is exactly one place that answers it and one place that applies it
 * ({@link ChargeKernel#add}, which every charge source composes). This class is the first half; nothing
 * else in the mod reads the effect's amplifier.
 *
 * <h2>Why it reads the entity instead of keeping a table</h2>
 * The obvious alternative — a static {@code Map<UUID, Double>} written when the effect lands and cleared
 * when it expires — would be a second source of truth for a fact the entity already holds, and this
 * project has shipped that defect (two answers to one question) three times. Reading
 * {@code LivingEntity.getEffect} costs one map lookup on the entity's own effect map, needs no expiry
 * bookkeeping, and cannot go stale: an effect that expires stops being seen on the very tick it ends.
 *
 * <h2>Stacking: the strongest instance wins, never the sum</h2>
 * Vanilla keys a player's effects by type, so at most one {@code CHARGE_RATE_UP} instance exists per
 * entity anyway — two casts of the same effect merge (taking the longer duration), they do not add. So
 * "the strongest" and "the only" coincide; the {@code max} below is what keeps that true even if a future
 * change ever allowed two, and it is the same rule {@code AttackBuffRules} states for its own buff.
 */
@ApiStatus.Internal
public final class ChargeRates {

    private ChargeRates() {
    }

    /**
     * {@return the charge-rate multiplier currently on {@code entity}, {@code 1.0} for none}
     *
     * <p>Never throws and never answers {@code 0}: a null entity, no effect, or an effect whose holder is
     * not registered yet all answer the identity, because the alternative is a charge path that silently
     * stops paying — the one failure this project treats as worse than a wrong number.
     *
     * @param entity the player (or any living entity) being paid charge
     */
    public static double multiplierOf(LivingEntity entity) {
        if (entity == null) {
            return ChargeKernel.NEUTRAL_RATE;
        }
        java.util.Optional<net.minecraft.core.Holder<MobEffect>> holder =
                PartyEffects.chargeRateUpHolder();
        if (holder.isEmpty()) {
            return ChargeKernel.NEUTRAL_RATE;
        }
        MobEffectInstance instance = entity.getEffect(holder.get());
        if (instance == null) {
            return ChargeKernel.NEUTRAL_RATE;
        }
        return ChargeKernel.rateForAmplifier(instance.getAmplifier());
    }

    /**
     * {@return the multiplier {@code amplifier} stands for}, for the level curve and for tests
     *
     * <p>Delegated rather than reimplemented: the encoding is {@link ChargeKernel}'s, and a second copy
     * here would drift the first time the step changed.
     *
     * @param amplifier the effect instance's amplifier, {@code 0} = no bonus
     */
    public static double multiplierFor(int amplifier) {
        return ChargeKernel.rateForAmplifier(amplifier);
    }

    /**
     * {@return whether any of {@code effects} is a charge-rate effect}
     *
     * <p>Used by the client row so an entity's HUD entry can be attributed to the character that applied
     * it, and by no decision path.
     *
     * @param effects the effects to classify
     */
    public static boolean isChargeRate(MobEffect effect) {
        return PartyEffects.isChargeRateUp(effect);
    }

    /** {@return every charge-rate effect this mod registers; one today, a list for the row's benefit} */
    public static List<MobEffect> registered() {
        return PartyEffects.chargeRateUps();
    }
}
