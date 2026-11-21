package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.ub.UbCharge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>「每秒恢复自身【10+「冰龙之印」】技能值」 as a real effect: {@code astrum:charge_counter} pays an
 * instalment every tick whose amount follows the holder's own tally.</b>
 *
 * <h2>Why this is a second charge-over-time type and not {@code astrum:charge_regen}</h2>
 * {@code astrum:charge_regen}'s magnitude <b>is</b> its amplifier: the tier is the points a second, and vanilla
 * merges two instances of one type by keeping the stronger, which is the human's 「取最强」 rule. That rule is
 * right for a rung, and wrong here: 雪菲's clause is a <b>formula</b>, {@code base + perMark × 「冰龙之印」},
 * and her tally <b>moves while the regen runs</b> (技能1 spends a mark every swing). A rung cannot express a
 * number that changes under it, so this effect reads {@code Counters.countOf(holder)} in its own tick and pays
 * the formula's value for the tally the player holds <em>at that tick</em>. The source says 「+「冰龙之印」」 —
 * per mark present, not per mark at cast time.
 *
 * <h2>The two numbers, in one integer knob</h2>
 * Vanilla gives an instance exactly one per-instance number, the byte-sized amplifier, and there are two data
 * numbers here. Both are small and both are expressed in <b>tenths of a charge point a second</b> — 雪菲's 1.0
 * points (10 TP) and 0.1 a mark (1 TP) — so the amplifier's two nibbles carry them:
 *
 * <pre>
 *   amplifier = baseSteps | (perMarkSteps &lt;&lt; 4)      // baseSteps = round(base × 10), likewise perMark
 *   雪菲: base 1.0, perMark 0.1  ->  10 | (1 &lt;&lt; 4) = 26
 * </pre>
 *
 * <p>The limits are therefore 0..1.5 points a second for each half (15 steps), which every shipped clause fits;
 * {@code OverTimeEffects.applyCounterChargeRegen} refuses a value outside the range with a WARN rather than
 * silently quantising it. This is the same trick {@code CritDamageUpEffect} uses to carry a percentage in the
 * amplifier — a documented encoding, not a second field on an instance that has none.
 *
 * <h2>Why the work is in {@code applyEffectTick}, and why that is not a tick listener</h2>
 * The same argument {@code ChargeRegenEffect}, {@code PanicEffect} and {@code SpiritEffect} make: vanilla calls
 * {@code MobEffect#applyEffectTick} for <b>one instance on one entity</b>, from {@code MobEffectInstance.tick} →
 * {@code LivingEntity.tickEffects}, which walks that entity's own effect map. The entity is handed in; nothing is
 * enumerated, no level is polled, and this class is not subscribed to any event.
 *
 * <h2>Why the charge goes through {@code ChargeKernel}</h2>
 * {@link UbCharge#apply} is the one place a gauge is credited on the server: it posts the cancellable
 * {@code UbChargeEvent}, applies 「TP上昇」 through {@link com.astrum.pcrd.impl.ub.ChargeKernel#add} and clamps. A
 * direct {@code UbState} write would skip the event, skip the multiplier and be a second accumulator.
 *
 * <h2>Only players pay</h2>
 * A gauge belongs to a {@link ServerPlayer}; a mob carrying the effect ticks and does nothing, which is the
 * honest answer rather than an invented per-entity gauge.
 */
@ApiStatus.Internal
public final class CounterChargeRegenEffect extends MobEffect {

    /**
     * Builds the effect.
     *
     * <p>{@code BENEFICIAL}: the gauge filling is a good thing, so vanilla groups it with the buffs — and on this
     * mod's bar the row is hidden entirely ({@code impl/client/HiddenEffectClient}), because
     * 「药水栏……仅显示角色的技能图标」.
     */
    public CounterChargeRegenEffect() {
        super(MobEffectCategory.BENEFICIAL, OverTimeEffects.CHARGE_REGEN_COLOUR);
    }

    /**
     * Pays one tick's instalment: {@code (base + perMark × marks) / 20}.
     *
     * @return always {@code true}: the regen ends when its clock runs out, not because a tick "failed"
     */
    @Override
    public boolean applyEffectTick(LivingEntity holder, int amplifier) {
        if (holder.level().isClientSide) {
            return true;
        }
        if (!(holder instanceof ServerPlayer player)) {
            // No gauge to pay. Deliberately silent: a mob carrying the effect is not a defect.
            return true;
        }
        double points = OverTimeEffects.counterChargePointsPerTick(amplifier, Counters.countOf(player));
        if (points > 0.0) {
            // Attributed to the effect's own id: by the time it ticks, the regen belongs to the state on the
            // player rather than to a particular caster's skill (the same answer ChargeRegenEffect gives).
            UbCharge.apply(player, OverTimeEffects.COUNTER_CHARGE_ID, points);
        }
        return true;
    }

    /** Vanilla calls {@code applyEffectTick} only when this answers true, and a rate needs every tick. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }
}