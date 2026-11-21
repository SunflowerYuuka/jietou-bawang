package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.WarnOnce;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 「嘲讽」: the mob-AI half of {@link EffectType#TAUNT}, and the one state the taunt needs.
 *
 * <h2>The human's sentence, and which half of it vanilla can answer</h2>
 * 「嘲讽就强制被攻击单位，锁定视角方向指向攻击者。」 Vanilla has <b>no</b> taunt: there is no {@code MobEffect}
 * that makes a mob prefer one player, and no "aggro table" to write into. What vanilla does have is exactly
 * the two things the sentence names:
 *
 * <ul>
 *   <li><b>the attack target</b> — {@code Mob#setTarget(LivingEntity)}, which is what every hostile mob's AI
 *       reads to decide whom to path to and swing at (and which fires NeoForge's
 *       {@code LivingChangeTargetEvent}, so other mods still see and can veto it);</li>
 *   <li><b>the view direction</b> — {@code Mob#getLookControl().setLookAt(entity, speed, speed)}, the wanted
 *       look point its own head control interpolates toward.</li>
 * </ul>
 *
 * <p>Both are re-asserted <b>every tick</b> of the effect's life, which is why this is a per-instance
 * {@code MobEffect#applyEffectTick} and not a tick listener: vanilla calls that for one instance on one
 * entity out of that entity's own effect map, so nothing is enumerated at level scope by the effect itself.
 * The sweep below <em>does</em> query a box around the taunter — that is the mechanic (a taunt reaches
 * everyone in range), it is bounded by {@link #RADIUS}, and it is the same query a single mob's own target
 * goal makes.
 *
 * <h2>Why the holder is the taunter and not the mob</h2>
 * 望's clause is 「嘲讽自身」, and two mechanical facts make the caster the only sensible holder:
 *
 * <ol>
 *   <li><b>An effect instance has no "who applied me".</b> {@code MobEffectInstance} carries a type, a
 *       duration and an amplifier. A taunt on the mob would need a side channel holding the taunter's uuid for
 *       the instance's whole life — a stale uuid waiting to point at the wrong player after a relog. On the
 *       caster there is nothing to store: the holder <em>is</em> the taunter.</li>
 *   <li><b>It is the only shape that fires for her ultimate.</b> Both of 望's UB tiers declare
 *       {@code damage: 0.0}, and {@code UbDamageSettlement} refuses every {@code VICTIM} effect unless
 *       {@code hurt()} answered true — a 0-damage tier never gets there, so a victim-scoped taunt would
 *       silently never run. A party-scoped one is settled before the damage, alongside her buffs.</li>
 * </ol>
 *
 * <h2>What a player observes, and what is honestly not forced</h2>
 * Hostile mobs within {@value #RADIUS} blocks turn toward the taunter and take her as their AI target for the
 * duration, so they walk to her and attack her instead of whoever they were fighting. When the effect ends,
 * every mob still locked on her has that target <b>cleared</b> and its own target selectors run again.
 *
 * <p>Two limits, stated rather than papered over:
 * <ul>
 *   <li>A mob's own target selector runs after this tick and <b>may</b> pick somebody else within that same
 *       tick; the taunter is re-asserted on the next one, so the mob's attention returns, but it can flicker
 *       for a tick. The look direction is the steadier half: it is re-aimed every tick and interpolated, so
 *       the mob visibly keeps facing her.</li>
 *   <li>A <b>player</b> victim is never force-looked. A server cannot own a client's camera; the only lever
 *       (a client position/rotation packet every tick) would rubber-band her movement, so this type only
 *       moves mobs' gaze. That is stated in the enum's javadoc too.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class Taunts {

    /** Registry path: {@code astrum:taunt}. */
    public static final String PATH = "taunt";

    /** {@code astrum:taunt}. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** Effect colour: a hot amber, the "look at me" reading rather than a damage colour. */
    public static final int COLOUR = 0xE0A030;

    /**
     * How far the taunt calls, in blocks.
     *
     * <p>A constant rather than the data's {@code radius}, because a {@code MobEffectInstance} carries no
     * radius: the per-tick sweep only knows the holder and the amplifier. 8 is the party default
     * ({@code AttackBuffs.PARTY_RADIUS_DEFAULT}), i.e. the reach of the same ultimate's buffs, so the taunt
     * covers the fight the ultimate was cast in. The data therefore writes no {@code radius} for this type —
     * exactly as {@code PANIC} ignores a declared {@code seconds}.
     */
    public static final double RADIUS = 8.0;

    /**
     * How fast the head turns, in degrees a tick.
     *
     * <p>{@code 30} is a deliberate snap — the human's word is 锁定, "locked on", not "glancing" — while still
     * being an interpolation rather than an instant rotation, so the mob does not appear to teleport its head.
     */
    public static final float LOOK_SPEED = 30.0F;

    /** The radii the "let go" sweep uses; see {@link #letGo}. */
    public static final double RELEASE_RADIUS = 16.0;

    /** Refusals already reported, so bad data does not log once per cast. */
    private static final WarnOnce REPORTED = new WarnOnce();

    private Taunts() {
    }

    /**
     * One body as the taunt sees it: the two questions and the two answers.
     *
     * <p>An interface so the rule — "focus the ones who are not looking at me, and let go of the ones who are"
     * — can be asserted without a server. {@code Mob} cannot be constructed in a unit test, so a test that
     * drove the real entity would not exist; the production implementation is {@link Chaser}, a five-line
     * adapter over the two vanilla calls.
     */
    interface Focusable {
        /** {@return whether this body's AI target is already the taunter} */
        boolean targetsTaunter();

        /** Turns both halves of its attention — AI target and gaze — onto the taunter. */
        void focus();

        /** Gives the taunter up again: clears the AI target it took. */
        void release();
    }

    /**
     * The production adapter: one {@code Mob}, the taunter, and vanilla's two calls.
     */
    private record Chaser(Mob mob, LivingEntity taunter) implements Focusable {

        @Override
        public boolean targetsTaunter() {
            return mob.getTarget() == taunter;
        }

        @Override
        public void focus() {
            mob.setTarget(taunter);
            // The gaze half of 「锁定视角方向指向攻击者」: the wanted look point, which the mob's own
            // LookControl.tick then rotates the head toward.
            mob.getLookControl().setLookAt(taunter, LOOK_SPEED, LOOK_SPEED);
        }

        @Override
        public void release() {
            // Only the target this taunt took: a mob that acquired her for its own reasons in the meantime is
            // not ours to unset. (An AI target is one field, so the two cases are indistinguishable — this is
            // the closest honest rule.)
            if (mob.getTarget() == taunter) {
                mob.setTarget(null);
            }
        }
    }

    /**
     * {@return how many bodies started looking at the taunter this tick}
     *
     * <p>The rule, in one loop: a body that is not already on her is focused; one that is is left alone (no
     * packet churn, and no re-triggering a {@code LivingChangeTargetEvent} every tick for a mob that is already
     * where it should be).
     */
    static int tick(List<? extends Focusable> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return 0;
        }
        int focused = 0;
        for (Focusable body : bodies) {
            if (body != null && !body.targetsTaunter()) {
                body.focus();
                focused++;
            }
        }
        return focused;
    }

    /**
     * {@return how many bodies were let go of}
     *
     * <p>The mirror rule: only a body that is <em>still</em> on her is released, so a mob that has already
     * moved on is not disturbed. Called from the expiry and removal events — the effect's own clock is the
     * only thing that should end a taunt, so the cleanup is event-shaped rather than polled.
     */
    static int release(List<? extends Focusable> bodies) {
        if (bodies == null || bodies.isEmpty()) {
            return 0;
        }
        int released = 0;
        for (Focusable body : bodies) {
            if (body != null && body.targetsTaunter()) {
                body.release();
                released++;
            }
        }
        return released;
    }

    /**
     * {@return every hostile mob the taunter's call reaches, as {@link Focusable}s}
     *
     * <p>Three filters, each a real case: {@link Enemy} keeps the taunt off passive animals (a cow has no
     * attack goals to receive it), {@code isAlliedTo} keeps it off a player's own wolf, and the squared distance
     * is the sphere rather than the bounding box's cube — the same shape {@code EffectExecutors.around} uses.
     *
     * @param taunter the body calling; a {@code null} or client-side answer is an empty list
     */
    static List<Focusable> nearby(@Nullable LivingEntity taunter, double radius) {
        if (taunter == null || taunter.level().isClientSide || !(radius > 0.0)) {
            return List.of();
        }
        double limit = radius * radius;
        List<Focusable> bodies = new ArrayList<>();
        for (Mob mob : taunter.level().getEntitiesOfClass(Mob.class, taunter.getBoundingBox().inflate(radius),
                candidate -> candidate != taunter && candidate instanceof Enemy && candidate.isAlive()
                        && !candidate.isRemoved() && !candidate.isAlliedTo(taunter)
                        && candidate.distanceToSqr(taunter) <= limit)) {
            bodies.add(new Chaser(mob, taunter));
        }
        return bodies;
    }

    /** {@return how many mobs this tick turned onto {@code taunter}} */
    public static int sweep(@Nullable LivingEntity taunter) {
        return tick(nearby(taunter, RADIUS));
    }

    /**
     * {@return whether an instance with this remaining duration is on its <b>last</b> tick}
     *
     * <p>Vanilla's {@code MobEffectInstance.tick} calls {@code applyEffectTick} <em>before</em>
     * {@code tickDownDuration}, and it only ticks while {@code duration > 0}. So reading the instance at
     * {@code duration == 1} inside the tick is exactly "this is the tick it will end after", which is the one
     * place a {@code MobEffect} can observe its own expiry without a listener.
     *
     * @param duration the instance's remaining ticks, as read during {@code applyEffectTick}
     */
    static boolean finalTick(int duration) {
        return duration <= 1;
    }

    /**
     * One tick of the taunt: aim the mobs onto her, or — on the effect's last tick — let them go.
     *
     * <h2>Why the release happens here and not in a {@code MobEffectEvent}</h2>
     * Expiry is normally event-shaped in this project ({@code PanicControl} restores a mob's {@code noAi} from
     * {@code MobEffectEvent.Expired}/{@code Remove}). That listener lives in {@code CombatEvents}, which this
     * task does not own, so the same fact is read where a {@code MobEffect} <em>can</em> see it: the last tick
     * of its own instance (see {@link #finalTick}). The honest limitation, stated rather than hidden: an
     * <b>early</b> removal ({@code /effect clear}, milk, a totem cure) never reaches this tick, so a mob that
     * was locked on her keeps that target until its own AI re-selects — the normal expiry, which is the case
     * that happens in play, does clean up.
     */
    public static int pulse(@Nullable LivingEntity taunter) {
        if (taunter == null) {
            return 0;
        }
        Holder<MobEffect> holder = PartyEffects.tauntHolder().orElse(null);
        if (holder == null) {
            return 0;
        }
        MobEffectInstance instance = taunter.getEffect(holder);
        if (instance != null && finalTick(instance.getDuration())) {
            return letGo(taunter);
        }
        return sweep(taunter);
    }

    /**
     * {@return how many mobs were let go when the taunt ended}
     *
     * <p>{@link #RELEASE_RADIUS} rather than {@link #RADIUS}: by the end of a ten-second taunt the mobs it
     * called have walked to the taunter, so they are well inside the taunt's own reach; a body that wandered
     * further away meanwhile is out of the fight anyway, and sweeping an unbounded area to find it would be
     * the level-wide walk this project forbids.
     */
    public static int letGo(@Nullable LivingEntity taunter) {
        return release(nearby(taunter, RELEASE_RADIUS));
    }

    /**
     * Applies the taunt to the caster and aims the mobs already in range at her.
     *
     * <p>The effect is a real {@code MobEffectInstance}, so it inherits the HUD marker, the expiry clock and
     * {@code /effect give} from vanilla; the amplifier is {@code tier - 1}, which for a one-rung ladder is
     * always 0. The immediate sweep means the taunt takes visible effect on the cast tick rather than on the
     * next entity tick — one tick is a detail, but a cast that looks like it did nothing for a tick reads as a
     * failure to the player.
     *
     * @param caster      the taunter
     * @param tier        the resolved rung (always 1 on this ladder)
     * @param params      the duration
     * @param characterId the character that asked, for the log line
     * @return how many mobs were aimed at her, or {@code 0} when the effect could not be applied
     */
    public static int apply(@Nullable LivingEntity caster, int tier, TieredParams params,
            ResourceLocation characterId) {
        if (caster == null) {
            return 0;
        }
        int ticks = AreaBuffs.ticksFor(params == null ? TieredParams.FIRST : params);
        if (ticks <= 0) {
            if (REPORTED.first(characterId + "|zero-ticks")) {
                Astrum.LOGGER.warn("astrum: taunt of {} declared no duration, so it was not applied; a "
                        + "taunt needs `seconds`", characterId);
            }
            return 0;
        }
        Holder<MobEffect> holder = PartyEffects.tauntHolder().orElse(null);
        if (holder == null) {
            if (REPORTED.first(characterId + "|unregistered")) {
                Astrum.LOGGER.warn("astrum: the taunt effect is not registered yet, so the taunt of {} was "
                        + "not applied", characterId);
            }
            return 0;
        }
        int amplifier = Math.max(0, tier - 1);
        boolean added = caster.addEffect(new MobEffectInstance(holder, ticks, amplifier, false, true, true));
        int focused = added ? sweep(caster) : 0;
        Astrum.LOGGER.debug("astrum: taunt tier {} for {}t on {} -> applied={}, {} mob(s) turned onto her",
                tier, ticks, caster.getName().getString(), added, focused);
        return focused;
    }

    /**
     * The taunt's clock ran out, or the instance was replaced: let go of the mobs still on her.
     *
     * <p>Kept as a plain method with no listener attached, so the cleanup can be wired to
     * {@code MobEffectEvent} later without touching the rule. In this build it is reached from
     * {@link #pulse} on the instance's last tick, an early removal is the documented gap (see that method).
     */
    public static void letGoAfterRemoval(@Nullable LivingEntity taunter) {
        if (taunter == null || taunter.level().isClientSide) {
            return;
        }
        int released = letGo(taunter);
        Astrum.LOGGER.debug("astrum: taunt ended on {} -> {} mob(s) let go",
                taunter.getName().getString(), released);
    }
}