package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>event-shaped</b> half of 護盾: putting the absorption float back to vanilla's part when our effect
 * goes away.
 *
 * <h2>Why an expiry hook is needed at all, if the read path already checks the clock</h2>
 * {@code Shields.stateAt} answers "no shield" the moment the granting instance is gone, which is what stops
 * a stale pool from eating a later golden apple. That is correctness of the <em>bookkeeping</em>. It is not
 * the same thing as correctness of the <em>float</em>: our {@code MAX_ABSORPTION} modifier disappears with
 * the effect, and if the float still holds our share, vanilla's attribute clamp will decide what happens
 * next — usually a silent drop to whatever the remaining ceiling allows. Doing the arithmetic ourselves, at
 * the moment the effect ends, is what makes "when the shield expires you have exactly what you had before"
 * true rather than incidental.
 *
 * <h2>Which two events, and why both</h2>
 * {@code MobEffect} in 21.1.265 has <b>no</b> {@code onEffectRemoved} hook; the two removal paths are
 * NeoForge's events:
 * <ul>
 *   <li>{@link MobEffectEvent.Expired} — the clock ran out;</li>
 *   <li>{@link MobEffectEvent.Remove} — an explicit removal ({@code /effect clear}, milk, a totem cure).</li>
 * </ul>
 * Between them they cover every way the effect can end, which is the same pair {@code PanicControl} uses and
 * for the same reason. Neither listener cancels anything: {@code Expired} is cancellable, and vetoing an
 * expiry is not this class's business.
 *
 * <h2>Both sides of the check</h2>
 * The comparison is by identity ({@link PartyEffects#isShield}), not by id string, so vanilla's own
 * {@code absorption} — the other type that writes the same float — is never mistaken for ours. A shield
 * ending must not clear the hearts a golden apple put there, and this is the guard that makes that true.
 */
@ApiStatus.Internal
public final class ShieldControl {

    private ShieldControl() {
    }

    /**
     * Our shield's clock ran out: hand the float back to vanilla's part.
     *
     * @param event the expiry, carrying the entity and the instance that ended
     */
    public static void onEffectEnded(MobEffectEvent.Expired event) {
        release(event.getEntity(), event.getEffectInstance().getEffect().value(), "expired");
    }

    /**
     * Our shield was removed before its clock ran out.
     *
     * @param event the removal, carrying the entity and the effect type
     */
    public static void onEffectRemoved(MobEffectEvent.Remove event) {
        release(event.getEntity(), event.getEffect().value(), "removed");
    }

    /**
     * Clears our pool for one entity, keeping whatever absorption is not ours.
     *
     * @param entity the entity losing the effect
     * @param effect the effect being removed
     * @param reason the event that asked, for the log line
     */
    private static void release(LivingEntity entity, MobEffect effect, String reason) {
        if (entity == null || effect == null || entity.level().isClientSide) {
            return;
        }
        if (!PartyEffects.isShield(effect)) {
            return;   // some other effect ended; not ours to touch
        }
        Astrum.LOGGER.debug("astrum: shield {} on {} -> releasing our pool", reason,
                entity.getName().getString());
        Shields.clear(entity);
    }
}
