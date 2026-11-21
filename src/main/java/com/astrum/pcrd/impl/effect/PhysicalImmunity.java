package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.InternalIds;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「物理无敌」: the one rule that turns a physical hit into nothing.</b>
 *
 * <h2>The channel, and the one classifier it shares</h2>
 * {@code minecraft:resistance} level V is 100% of <b>every</b> damage type — full invulnerability — so it
 * cannot express 「单独免疫物理不免疫魔法」, and it cannot be split. This class is the physical-only channel:
 * while the holder carries {@code astrum:phys_immunity}, a hit is negated <b>iff</b>
 * {@link DamageReduction#isMagic(DamageSource)} says it is physical. That is the same predicate the two
 * defence buffs and the magic-defence debuff already use ({@code astrum:magic} or the {@code astrum:is_magic}
 * tag), so there is no second answer to 「is this magic」 and the documented limitation travels with it: an
 * unmarked type is physical, so another mod's unmarked magic is negated until it tags its types.
 *
 * <h2>Why "negated" is a zero at the damage event and not an immunity attribute</h2>
 * 1.21.265 has no "immune to a damage type" attribute, and the physical/magic split is not a vanilla concept
 * at all — it is this mod's marker. So the immunity is applied where the split already is: {@code CombatEvents}
 * asks {@link #blocks} on {@code LivingDamageEvent.Pre}, before the two defence buffs, and sets the damage to
 * zero. That is the same stage and the same writable API the buffs use, and it means the immunity cannot be
 * forgotten by a damage path that bypasses an attribute.
 *
 * <h2>What it honestly cannot stop</h2>
 * A hit reduced to zero still reached the damage event, so anything the engine does <em>before</em> that event
 * (knockback, the hurt animation, a mod listening at {@code LivingIncomingDamageEvent}) has already happened.
 * The immunity promises «no health lost to physical damage», which is what the source text says; it does not
 * claim the swing never occurred. Only a cancellable incoming event could promise that, and cancelling every
 * physical hit wholesale would also swallow effects that <em>should</em> still fire on a hit (charge, on-hit
 * marks), which is a different mechanic than 无敌.
 */
@ApiStatus.Internal
public final class PhysicalImmunity {

    /** Registry path: {@code astrum:phys_immunity}. */
    public static final String PATH = "phys_immunity";

    /** {@code astrum:phys_immunity}. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** Particle tint: a golden ward, deliberately unlike the defence buffs' steel blue. */
    public static final int COLOUR = 0xD8C45A;

    private PhysicalImmunity() {
    }

    /**
     * {@return whether an immunity negates a hit with this classification}
     *
     * <p><b>The whole rule, pure.</b> {@code magic == true} always answers {@code false}: a physical immunity
     * must let magic through, which is the requirement the resistance channel could not meet. Split out so
     * the property is a unit test with no entity: "blocks a physical source, lets a magical one through" is
     * exactly this function's truth table.
     *
     * @param magic  whether {@link DamageReduction#isMagic} classified the source as magical
     * @param immune whether the victim holds {@code astrum:phys_immunity}
     */
    public static boolean applies(boolean magic, boolean immune) {
        return immune && !magic;
    }

    /**
     * {@return whether {@code source} does no damage to {@code victim} because of this immunity}
     *
     * @param victim the body the hit is about to land on; {@code null} answers {@code false}
     * @param source the damage being applied; {@code null} is not magical (see {@code DamageReduction})
     */
    public static boolean blocks(@Nullable LivingEntity victim, @Nullable DamageSource source) {
        return applies(DamageReduction.isMagic(source), holds(victim));
    }

    /**
     * {@return whether {@code entity} currently holds the immunity}
     *
     * <p>Empty holder before the registry is up answers {@code false}, the fail-open answer for a
     * <em>blocking</em> rule: refusing to let a player take damage because a registry was not bound would be
     * worse than an immunity that missed one hit.
     */
    public static boolean holds(@Nullable LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        Optional<Holder<MobEffect>> holder = PartyEffects.physImmunityHolder();
        return holder.isPresent() && entity.hasEffect(holder.get());
    }
}