package com.astrum.pcrd.impl.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import org.jetbrains.annotations.ApiStatus;

/**
 * A registered effect whose whole substance is <b>being on the entity</b>: it shows on the HUD, it carries
 * a level, and the code that acts on it reads that level.
 *
 * <h2>Which effects are markers, and why that is honest</h2>
 * Three of the generic effects cannot be an attribute modifier, because the thing they change is not an
 * attribute Minecraft has:
 *
 * <ul>
 *   <li>the two <b>defence buffs</b> — there is no "damage taken" attribute in 1.21.265. The reduction is
 *       applied in {@code DamageReduction}, on {@code LivingDamageEvent.Pre}, reading the instance's
 *       amplifier through {@link DefenceRules}. Vanilla's {@code resistance} works the same way: the
 *       effect is a marker and the reduction happens in {@code LivingEntity.getDamageAfterMagicAbsorb}.</li>
 *   <li><b>charge rate up</b> — the gauge is this mod's own state, so the multiplier is read by
 *       {@code ChargeRates.multiplierOf} on the charge path.</li>
 * </ul>
 *
 * <p>Being a marker is not a stub: the effect's <em>level</em> is the tier, the HUD entry is what a player
 * needs to see, and the arithmetic happens at the one place that owns the quantity. What a marker
 * deliberately does <b>not</b> have is {@code applyEffectTick} — a per-tick no-op would be a tick
 * listener in disguise, and this project forbids those.
 *
 * <h2>Why the name is per instance of this class, not one class per effect</h2>
 * These three differ only in colour and in which translation key names them; their behaviour is identical
 * (none). A subclass per effect would be three files of nothing. What must stay distinct is the
 * <b>registry entry</b> — vanilla keys a player's effects by type, so three markers need three types, or
 * two of them would merge into one HUD entry — and that is what {@code PartyEffects} registers.
 */
@ApiStatus.Internal
public final class AstrumMarkerEffect extends MobEffect {

    /**
     * @param category whether vanilla draws it as a buff or a debuff
     * @param colour   the particle tint
     */
    public AstrumMarkerEffect(MobEffectCategory category, int colour) {
        super(category, colour);
    }
}
