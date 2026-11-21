package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.ub.ChargeKernel;
import com.astrum.pcrd.impl.ub.UbCharge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>「缓慢恢复充能」 as a real effect: {@code astrum:charge_regen} pays an instalment of UB charge every tick
 * it is alive.</b>
 *
 * <h2>One type, not one per character — because the human said 「取最强」</h2>
 * 「两个不同角色的缓慢回充同时挂在玩家身上时：选择合并，取最强。」
 *
 * <p>Vanilla keys a living entity's effects by <b>type</b> and merges a second instance of one type into the
 * first, keeping the higher amplifier. So a single registered type whose amplifier is the tier gives exactly the
 * ruled behaviour for nothing: two characters' regens become one entry, and 20%/s plus 40%/s is 40%/s, never 60%.
 * One type per slot would have produced two entries that both paid, i.e. the "add" the human rejected.
 *
 * <p>That is also why the area form ({@code CHARGE_REGEN_AREA}) uses <b>this same</b> type: an area cast and a
 * self cast landing on one player are one entry under the same rule.
 *
 * <h2>Why the work is in {@code applyEffectTick}, and why that is not a tick listener</h2>
 * The same argument {@code SpiritEffect} and {@code PanicEffect} make: vanilla calls
 * {@code MobEffect#applyEffectTick} for <b>one instance on one entity</b>, from
 * {@code MobEffectInstance.tick} → {@code LivingEntity.tickEffects}, which walks that entity's own effect map.
 * The entity is handed in; nothing is enumerated, no level is polled, and this class is not subscribed to any
 * event. {@code guard}'s two tick red lines are about {@code EntityTickEvent}/{@code LevelTickEvent} handlers
 * that walk entities, which this is the opposite of.
 *
 * <h2>Why the charge goes through {@code ChargeKernel} and not a direct gauge write</h2>
 * {@link UbCharge#apply} is the one place a gauge is credited on the server: it posts the cancellable
 * {@code UbChargeEvent}, applies the player's charge-rate multiplier through {@link ChargeKernel#add} and clamps.
 * Writing {@code UbState} here would (a) skip the event other mods and this mod's own listeners observe, (b) skip
 * 「TP上昇」, and (c) be a second accumulator — the exact thing the class javadoc of {@code ChargeKernel} forbids.
 * It also means the fraction is <b>not</b> lost: {@code UbCharge} takes a {@code double} delta and the kernel
 * clamps the sum, so a tier-1 instalment of 0.2 a tick accumulates correctly instead of truncating to zero.
 *
 * <h2>Only players pay</h2>
 * A gauge belongs to a {@link ServerPlayer} ({@code UbState} is a player attachment). A mob carrying the effect
 * ticks and does nothing, which is the honest answer rather than an invented per-entity gauge.
 */
@ApiStatus.Internal
public final class ChargeRegenEffect extends MobEffect {

    /**
     * Builds the effect.
     *
     * <p>{@code BENEFICIAL}: the gauge filling is a good thing, so vanilla draws it in the buff column — and on
     * this mod's bar the row is hidden entirely ({@code impl/client/HiddenEffectClient}), because
     * 「药水栏……仅显示角色的技能图标」.
     */
    public ChargeRegenEffect() {
        super(MobEffectCategory.BENEFICIAL, OverTimeEffects.CHARGE_REGEN_COLOUR);
    }

    /**
     * Pays one tick's instalment.
     *
     * <p>The amount comes from {@link OverTimeEffects#chargePointsPerTick}, the one amplifier-to-rate mapping, so
     * the ladder lives in one place. {@code characterId} for the log and for {@code UbChargeEvent} attribution is
     * the effect's own id — the regen belongs to no particular caster by the time it is ticking, which is the
     * ordinary case for a merged instance.
     *
     * @return always {@code true}: the regen ends when its clock runs out, not because a tick "failed"
     */
    @Override
    public boolean applyEffectTick(LivingEntity holder, int amplifier) {
        if (holder.level().isClientSide) {
            return true;
        }
        if (!(holder instanceof ServerPlayer player)) {
            // No gauge to pay. Deliberately silent: a mob carrying the effect is not a defect, and a debug line
            // here would be one per tick per mob for a fact that never changes.
            return true;
        }
        double points = OverTimeEffects.chargePointsPerTick(amplifier);
        if (points > 0.0) {
            UbCharge.apply(player, OverTimeEffects.ID, points);
        }
        return true;
    }

    /** Vanilla calls {@code applyEffectTick} only when this answers true, and a rate needs every tick. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }
}
