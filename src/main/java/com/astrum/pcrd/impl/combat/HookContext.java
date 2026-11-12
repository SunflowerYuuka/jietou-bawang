package com.astrum.pcrd.impl.combat;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * One landed hit, as the eight effect executors see it.
 *
 * <h2>Why a context object instead of widening the parameter list</h2>
 * Four of the eight effects need the attacker ({@code lifesteal}, {@code gain_charge},
 * {@code area_charge}, and every stack key), two need the landed amount, two need a way to reach other
 * entities, and all of them need the multiplier and the batch. Adding those one at a time to
 * {@code dispatch(hook, multiplier, hit, batch)} would have produced a nine-parameter switch arm by the
 * seventh effect — the kind of signature where a caller passes {@code attacker} and {@code target} in
 * the wrong order and the compiler says nothing. One record, named fields, built once per hit.
 *
 * <h2>The per-slot half</h2>
 * The multiplier and the attributed character are per <b>wheel slot</b>, while the rest is per
 * <b>hit</b>, so {@link #forSlot} derives the slot's context from the hit's. That keeps "which
 * multiplier applies" next to "which character it belongs to", which is the pair Task 9's dedupe
 * already reasons about.
 *
 * @param attacker    the player whose wheel is being evaluated
 * @param target      the entity that was hit
 * @param source      the damage source that landed
 * @param dealt       health the hit actually removed, overkill clamped (see {@code LandedHit.healthLost})
 * @param multiplier  this slot's diminishing multiplier; scales quantities only (spec §5.2)
 * @param characterId the character owning the hook being evaluated (attribution, never a selector)
 * @param batch       the damage accumulator this hit will be flushed from
 * @param otherTargets where an effect that hits a different entity files its damage
 * @param areaCharge  the {@code area_charge} seam Task 11 fills in
 */
@ApiStatus.Internal
public record HookContext(ServerPlayer attacker, LivingEntity target, DamageSource source, float dealt,
                          double multiplier, ResourceLocation characterId, DamageBatch batch,
                          DamageToTarget otherTargets, AreaChargeSink areaCharge) {

    public HookContext {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(characterId, "characterId");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(otherTargets, "otherTargets");
        Objects.requireNonNull(areaCharge, "areaCharge");
    }

    /**
     * {@return the same hit, seen from one wheel slot}
     *
     * @param slotMultiplier that slot's diminishing multiplier
     * @param slotCharacter  the character equipped in that slot
     */
    public HookContext forSlot(double slotMultiplier, ResourceLocation slotCharacter) {
        return new HookContext(attacker, target, source, dealt, slotMultiplier, slotCharacter, batch,
                otherTargets, areaCharge);
    }
}
