package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

/**
 * The visible half of a UB cast: <b>real particles in world space</b>, spawned at the caster.
 *
 * <h2>Why the server emits them</h2>
 * {@link ServerLevel#sendParticles} is vanilla's own path: it builds a
 * {@code ClientboundLevelParticlesPacket} and sends it to the player, and the client's particle
 * engine spawns and ticks the custom {@code astrum:ub_spark} / {@code astrum:ub_mote} types. That
 * means the burst lives in the world (it is occluded by terrain, moves with the camera, and is not
 * a rectangle pasted over the screen), and it needs <b>no new payload</b> in this mod's protocol:
 * the call below is the whole client cue.
 *
 * <p>It is sent through the <b>per-player</b> overload with {@code force = true}. The effect is
 * caster-only by design (the previous screen effect was too: a teammate should not have their view
 * flashed by someone else's cast), and the caster is always well inside the ordinary 32-block
 * range — {@code force} just makes the "did it actually send" return value independent of the
 * player's block position rounding.
 *
 * <h2>One call per point, not one call per layer</h2>
 * Vanilla's packet spreads {@code count} particles randomly inside a box; that produces a cloud,
 * not a ring. The ring is therefore made by sending each {@link UbBurstPlan} point as its own
 * one-particle call with zero spread. That is ~31-47 small packets once per cast (the global
 * cooldown is 100 ticks), which is cheap next to a single explosion's particle packet, and it is
 * what gives the burst its visible shape. The per-particle randomisation — outward speed, a little
 * upward kick, size, lifetime, the animated frame — stays on the client in
 * {@code UbSparkParticle}/{@code UbMoteParticle}, because those are render-side values.
 *
 * <h2>Which side reads which config</h2>
 * This class never reads {@code ClientConfig}: a dedicated server must not know another player's
 * client file, and the client toggle has to keep working without a round-trip. The gate is the
 * client provider: when {@code [effect] enableUbCastEffect} is off it returns no particle, so the
 * packet arrives and produces nothing on that player's screen. Damage was already settled before
 * this runs, exactly as before.
 *
 * <h2>No tick listener and no entity</h2>
 * Everything happens in one call on the cast. Particles are ticked by the client's ordinary particle
 * engine; this class has no timer, no scheduled task and no entity to follow.
 */
@ApiStatus.Internal
public final class UbCastParticles {

    private UbCastParticles() {
    }

    /**
     * Spawns one cast's burst around {@code caster}.
     *
     * <p>Never throws: the damage is already settled by the time this is called, so a particle
     * failure must not turn an accepted, paid-for ultimate into a reported error. The count of
     * successful sends is logged, and a zero would be a visible failure in the log.
     *
     * @param level    the caster's server level
     * @param caster   the player whose client should see the burst
     * @param enhanced whether the tier-3 ultimate fired
     */
    public static void emit(ServerLevel level, ServerPlayer caster, boolean enhanced) {
        try {
            Vec3 origin = caster.position();
            double chestY = origin.y + caster.getBbHeight() * 0.55;
            int sparks = UbBurstPlan.sparkCount(enhanced);
            int motes = UbBurstPlan.moteCount(enhanced);
            int sentSparks = 0;
            int sentMotes = 0;

            for (int i = 0; i < sparks; i++) {
                UbBurstPlan.Point point = UbBurstPlan.sparkPoint(i, sparks);
                if (level.sendParticles(caster, UbParticles.SPARK.get(), true,
                        origin.x + point.x(), chestY + point.y(), origin.z + point.z(),
                        1, 0.0, 0.0, 0.0, 0.0)) {
                    sentSparks++;
                }
            }
            for (int i = 0; i < motes; i++) {
                UbBurstPlan.Point point = UbBurstPlan.motePoint(i, motes);
                if (level.sendParticles(caster, UbParticles.MOTE.get(), true,
                        origin.x + point.x(), origin.y + point.y(), origin.z + point.z(),
                        1, 0.0, 0.0, 0.0, 0.0)) {
                    sentMotes++;
                }
            }

            Astrum.LOGGER.info("astrum: UB cast emitted {} particles ({} sparks + {} motes) at caster "
                            + "world ({}, {}, {}) — types {}/{}, enhanced={}",
                    sentSparks + sentMotes, sentSparks, sentMotes,
                    fmt(origin.x), fmt(origin.y), fmt(origin.z),
                    UbParticles.SPARK_ID, UbParticles.MOTE_ID, enhanced);
            if (sentSparks != sparks || sentMotes != motes) {
                Astrum.LOGGER.warn("astrum: only {} of {} sparks and {} of {} motes were sent to {} "
                                + "— the cast is unaffected, but the client will see a thinner burst",
                        sentSparks, sparks, sentMotes, motes, caster.getGameProfile().getName());
            }
        } catch (RuntimeException e) {
            // Same rule as the damage: a cast already paid for is not turned into a failure by a
            // cosmetic. The log line is how the next probe sees that it happened at all.
            Astrum.LOGGER.warn("astrum: could not emit the UB particle burst for {} — the cast itself "
                    + "and its damage are unaffected", caster.getGameProfile().getName(), e);
        }
    }

    /** {@return a coordinate with three decimals, for a readable log line} */
    private static String fmt(double value) {
        return String.format("%.3f", value);
    }
}