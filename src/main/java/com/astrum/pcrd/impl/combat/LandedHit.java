package com.astrum.pcrd.impl.combat;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * What the entry point does with one landed hit, as two pure rules.
 *
 * <p>Both rules exist because an earlier version of the entry point got one of them wrong, and both
 * are cheap enough that a reviewer would otherwise have to trust the order of statements inside an
 * event handler.
 */
@ApiStatus.Internal
public final class LandedHit {

    private LandedHit() {
    }

    /**
     * The decision for one hit's damage type.
     *
     * @param creditsCharge whether this hit may credit the UB gauge
     * @param runsHooks     whether this hit may evaluate attachment hooks
     */
    public record Plan(boolean creditsCharge, boolean runsHooks) {
    }

    /**
     * {@return the plan for damage of {@code damageTypeId}}
     *
     * <h2>Why charge is not gated on "is this ours"</h2>
     * Spec §5.4 lists 造成伤害 as the main charge source and carves out nothing for damage this mod
     * applied itself, so a five-slot wheel whose attachment damage is its main output must charge
     * from it. Gating charge behind the fuse would charge roughly half of what such a wheel actually
     * deals — the first version of this class did exactly that.
     *
     * <p>Nothing loops: charge grants gauge and nothing else, and any {@code astrum}-namespace
     * damage is fused out of {@link #runsHooks()} below, so "extra damage → charge → extra damage"
     * cannot close.
     *
     * <p>{@code creditsCharge} is therefore always {@code true}. It stays a field of the plan rather
     * than collapsing into "the entry point always credits" so that the exemption this rule removes
     * cannot quietly grow back as a branch somewhere else.
     *
     * @param damageTypeId the hit's damage type, or {@code null} when unbound
     */
    public static Plan plan(@Nullable ResourceLocation damageTypeId) {
        return new Plan(true, !OwnDamage.isOurs(damageTypeId));
    }

    /**
     * {@return how much health this hit really removed, given the health before it landed}
     *
     * <h2>Why this is not just {@code LivingDamageEvent.Post.getNewDamage()}</h2>
     * Measured in {@code LivingEntity.actuallyHurt}: the container's {@code newDamage} is the
     * post-absorption damage <b>and is not clamped to the health that is left</b> — {@code setHealth}
     * subtracts it and {@code setHealth} clamps at zero, so the container keeps the unclamped number.
     * A 40-damage crit on a 3-hp mob reports {@code newDamage = 40} while 3 health was removed.
     * Feeding that to the gauge would let a big weapon on a nearly-dead mob earn charge it never
     * dealt, so overkill is clamped here instead.
     *
     * @param applied      post-absorption damage from the damage container
     * @param preHitHealth the target's health before this hit (from {@code LivingDamageEvent.Pre})
     */
    public static float healthLost(float applied, float preHitHealth) {
        if (!(applied > 0.0F) || !(preHitHealth > 0.0F)) {
            return 0.0F;   // NaN and non-positive answers the empty result, never NaN
        }
        return Math.min(applied, preHitHealth);
    }
}
