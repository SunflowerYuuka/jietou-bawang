package com.astrum.pcrd.impl.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「恐慌」 as a real, self-expiring effect: for 1.5 s the holder <b>cannot act</b>.
 *
 * <h2>Why this is the right home for the per-tick work</h2>
 * {@link MobEffect#applyEffectTick} is called by vanilla for <b>one instance on one entity</b> — it is
 * invoked from {@code MobEffectInstance.tick}, which {@code LivingEntity.tickEffects} calls while walking
 * that entity's own effect map. So it is not an entity walk, not a level poll, and not the tick polling
 * this project's red line forbids; the entity is handed in, and there is nothing to enumerate. The same
 * argument {@code PassiveCharge} makes for {@code PlayerTickEvent.Post}, one level down.
 *
 * <h2>What each holder gets</h2>
 * <ul>
 *   <li><b>A {@code Mob}</b> — {@code noAi} for the duration, restored when the effect ends. That is the
 *       whole of "a mob cannot act": its goal selector never ticks, so it neither paths, nor attacks, nor
 *       uses anything. The prior flag is remembered so a mob that was already {@code noAi} (a shopkeeper
 *       NBT-locked by a pack) stays that way instead of being woken up by a panic ending.</li>
 *   <li><b>A {@code ServerPlayer} (or any other living entity)</b> — its movement input is zeroed every
 *       tick. That is the only part that needs a clock: the attack and interaction blocks are event
 *       cancellations ({@code PanicControl}), which cost nothing when no panic is on.</li>
 * </ul>
 *
 * <h2>The two APIs that are <em>not</em> reachable, and what is used instead</h2>
 * {@code LivingEntity.jumping} is {@code protected} — a {@code MobEffect} in another package cannot write
 * it. That is not worked around with reflection or an access widener; the public
 * {@link LivingEntity#setJumping(boolean)} writes the very same field and is the supported door to it, so
 * this class uses that. {@code xxa}/{@code zza} are {@code public} floats and are written directly.
 *
 * <p>{@code MobEffect#onEffectRemoved} does <b>not</b> exist in 21.1.265 — the base class has
 * {@code onMobRemoved(LivingEntity, int, RemovalReason)} (death/removal only) and {@code onEffectStarted}
 * / {@code onEffectAdded}, but no expiry hook. Expiry and explicit removal are <b>NeoForge events</b>
 * ({@code MobEffectEvent.Expired} and {@code MobEffectEvent.Remove}), so restoring a mob's AI lives in
 * {@code PanicControl.onEffectEnded} / {@code onEffectRemoved} where those events are handled. Restoring it
 * here would have needed an override of a method that does not exist.
 *
 * <h2>Why the work is server-only, checked here</h2>
 * {@code LivingEntity.tickEffects} has <b>no</b> side guard (read in the 21.1.265 sources: it calls
 * {@code instance.tick(...)} and only the {@code MobEffectEvent.Expired} post inside it is guarded), so
 * this method runs on the client too. Everything below is therefore behind
 * {@code !level().isClientSide}: the client's job is to draw the HUD entry, which vanilla does from the
 * registered type, and a client that also zeroed its own input would be a client-side gameplay rule —
 * exactly what spec §7's 端隔离 forbids.
 */
@ApiStatus.Internal
public final class PanicEffect extends MobEffect {

    /**
     * Where the previous {@code noAi} value is remembered between the panic's ticks.
     *
     * <p>An entity tag rather than a static map: a static {@code Map<UUID, Boolean>} would be per-entity
     * state in a static container — the thing this project forbids — and it would survive the entity it
     * describes. A tag dies with the entity, needs no cleanup, and is what {@code Entity.getPersistentData}
     * is for. Its key is a constant so a second reader cannot typo it.
     */
    public static final String NO_AI_TAG = "astrum:panic_prev_no_ai";

    /**
     * Builds the effect. {@code HARMFUL}, not {@code HARMLESS}: a panic is something done <em>to</em> an
     * enemy, and vanilla draws the category as the tooltip's colour and the HUD's red/green column. There
     * is no {@code MobEffectCategory.HARMLESS} — the three values are {@code BENEFICIAL}, {@code HARMFUL}
     * and {@code NEUTRAL} (read from the enum in 21.1.265).
     */
    public PanicEffect() {
        super(MobEffectCategory.HARMFUL, PanicRules.COLOUR);
    }

    /**
     * Ticks once per tick <b>while the holder has it</b>, which is what makes the lock last exactly as long
     * as the instance does.
     *
     * @return always {@code true}: a panic ends when its clock runs out, never because a tick "failed"
     */
    @Override
    public boolean applyEffectTick(LivingEntity holder, int amplifier) {
        if (holder.level().isClientSide) {
            return true;
        }
        if (holder instanceof Mob mob) {
            suppressAi(mob);
        } else {
            freezeInput(holder);
        }
        return true;
    }

    /** Vanilla calls {@code applyEffectTick} only when this answers true; a panic needs every tick. */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    /**
     * Stops a mob's AI, remembering what it was.
     *
     * <p>Called every tick of the instance, and {@code setNoAi} is idempotent, so the remembering happens
     * on the first tick and the flag stays set until the panic ends and
     * {@code PanicControl.restoreAi} puts it back.
     */
    static void suppressAi(Mob mob) {
        if (!mob.getPersistentData().contains(NO_AI_TAG)) {
            mob.getPersistentData().putBoolean(NO_AI_TAG, mob.isNoAi());
        }
        mob.setNoAi(true);
        mob.getNavigation().stop();
        mob.setDeltaMovement(0.0, mob.getDeltaMovement().y(), 0.0);
    }

    /**
     * Zeroes a living entity's movement input.
     *
     * <p>{@code xxa}/{@code zza} are the strafe/forward axes (public fields) and {@code jumping} is the jump
     * flag, reached through its public setter — the field itself is {@code protected} and not ours to write.
     * Zeroing them every tick means the next {@code aiStep} sees no input, so the entity decelerates instead
     * of walking under its own power.
     *
     * <h2>What this honestly cannot do to a player</h2>
     * A {@code ServerPlayer}'s position on the server comes from the client's own move packets
     * ({@code ServerboundMovePlayerPacket} → {@code ServerGamePacketListenerImpl.handleMovePlayer}), not from
     * these two floats: {@code ServerPlayer.setPlayerInput} only copies them into {@code xxa}/{@code zza}
     * <em>while riding</em>. So zeroing the axes stops a player who is being <b>moved</b> (a vehicle, a
     * piston, a server-driven push) but not one who is walking, because a client can send any position it
     * likes and NeoForge posts no cancellable "player moved" event. The horizontal delta is clamped as well
     * ({@link #clampHorizontal}) so server-side motion does not accumulate either.
     *
     * <p>What <b>does</b> hold a walking player still is that nothing she does is accepted: the attack and
     * interaction paths are cancelled in {@code PanicControl}, which is where the real enforcement is. That
     * is the difference between "cannot act" and "cannot move", and {@code PanicRules} states it plainly
     * rather than claiming the keys are locked.
     */
    static void freezeInput(LivingEntity holder) {
        holder.xxa = 0.0F;
        holder.zza = 0.0F;
        holder.setJumping(false);
        clampHorizontal(holder);
        if (holder instanceof Player player) {
            player.setSprinting(false);
        }
    }

    /**
     * {@return {@code entity} with its horizontal velocity removed, vertical kept}
     *
     * <p>Vertical is deliberately preserved: a panicking entity already in the air must still fall and land,
     * and zeroing {@code y} would leave it hanging — a visible defect of its own. This is the
     * {@code setDeltaMovement} clamp the brief names as the reachable alternative to writing the client's
     * input.
     *
     * @param entity the entity being held still
     */
    private static LivingEntity clampHorizontal(LivingEntity entity) {
        var motion = entity.getDeltaMovement();
        entity.setDeltaMovement(0.0, motion.y, 0.0);
        entity.hasImpulse = true;   // tell the tracker the motion really changed, or the client keeps its own
        return entity;
    }
}
