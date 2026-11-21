package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * The visible half of a UB cast: <b>real particles in world space, on the bodies the cast hit</b>.
 *
 * <h2>Where the burst goes, and why that changed</h2>
 * 既然是刀光这种攻击类型的，你肯定是用来打对面的 — a slash is aimed at somebody, so it has to appear
 * where the blade lands. This class used to anchor every point at {@code caster.position()}, and the
 * javadoc argued for it; that argument belonged to the <em>old screen-space</em> effect (a teammate
 * should not have their view flashed) and is simply wrong for world particles: an effect drawn on the
 * attacker is an effect on the wrong body. So the centres are the victims
 * {@link UbBurstVictims#resolve} hands over, and a damaging ultimate draws <b>nothing on the
 * caster</b>.
 *
 * <p>The split by effect kind is deliberate and is the rule the human stated:
 *
 * <table border="1">
 *   <caption>where each kind of effect belongs</caption>
 *   <tr><th>effect</th><th>centre</th></tr>
 *   <tr><td>damage / slash (UB hits, {@code extra_damage}, {@code splash}, {@code detonate})</td>
 *       <td>each victim actually hit</td></tr>
 *   <tr><td>debuff ({@code apply_effect}, harmful)</td><td>the victim it was applied to</td></tr>
 *   <tr><td>positive buff ({@code attack_buff}, {@code gain_charge}, {@code area_charge},
 *       {@code stack} on self)</td><td>the beneficiary — the caster or the ally who received it</td></tr>
 * </table>
 *
 * <p>This class is the first row's emitter. Rows two and three are <b>not</b> routed through it: a
 * debuff already shows on the affected body (vanilla's own effect swirls) and a party buff is
 * announced on the beneficiary's HUD by {@code PartyEffectClient}, so drawing a burst there would be
 * a second, worse copy of a visual that exists. What the rule forbids — and what this class stops
 * doing — is a <em>damaging</em> cast bursting on the attacker.
 *
 * <h2>Why the server emits them</h2>
 * {@link ServerLevel#sendParticles} is vanilla's own path: it builds a
 * {@code ClientboundLevelParticlesPacket} and sends it, and the client's particle engine spawns and
 * ticks the custom {@code astrum:ub_spark} / {@code astrum:ub_mote} types. That means the burst lives
 * in the world (it is occluded by terrain, moves with the camera, and is not a rectangle pasted over
 * the screen), and it needs <b>no new payload</b> in this mod's protocol: the call below is the whole
 * client cue.
 *
 * <p>It is sent through the <b>area</b> overload now, not the per-player one. That is not a detail:
 * the per-player overload could only ever reach one client, and a slash on somebody else's body has
 * to be visible to that somebody. Vanilla's area form sends to every player within 32 blocks
 * ({@code force = true} widens that to 512), so the party sees each other's hits — which is the point
 * of a world-space effect.
 *
 * <h2>One call per point, not one call per layer</h2>
 * Vanilla's packet spreads {@code count} particles randomly inside a box; that produces a cloud, not
 * a ring. The ring is therefore made by sending each {@link UbBurstPlan} point as its own
 * one-particle call with zero spread and an explicit velocity. That is 21-31 small packets per
 * victim per cast (a character's cooldown is 100 ticks by default, per character), which is cheap
 * next to a single explosion's
 * particle packet, and it is what gives the burst its visible shape <em>and its direction</em>.
 *
 * <h2>Which side reads which config</h2>
 * This class never reads {@code ClientConfig}: a dedicated server must not know another player's
 * client file, and the client toggle has to keep working without a round-trip. The gate is the
 * client provider: when {@code [effect] enableUbCastEffect} is off it returns no particle, so the
 * packet arrives and produces nothing on that player's screen. Damage was already settled before
 * this runs, exactly as before.
 *
 * <h2>No tick listener and no entity</h2>
 * Everything happens in one call per victim on the cast. Particles are ticked by the client's
 * ordinary particle engine; this class has no timer, no scheduled task and no entity to follow.
 */
@ApiStatus.Internal
public final class UbCastParticles {

    private UbCastParticles() {
    }

    /**
     * Spawns one burst on each victim of one cast, and <b>nothing</b> on the caster.
     *
     * <p>Never throws: the damage is already settled by the time this is called, so a particle
     * failure must not turn an accepted, paid-for ultimate into a reported error. Each victim is
     * emitted in its own try/catch — one broken body must not cost the others their burst — and the
     * totals are logged, because a silent no-op is the failure mode this whole change exists to make
     * visible.
     *
     * <p>A cast that hit nothing is <b>logged at INFO and draws nothing</b>. That is a real outcome
     * (the target walked out of reach, the tier's radius reached nobody) and the log line is how it
     * is told apart from "the effect is broken".
     *
     * @param level    the caster's server level
     * @param caster   the player who cast; the origin of each blow's direction, never a centre
     * @param landed   every hit the settlement recorded, repeats included
     * @param enhanced whether the tier-3 ultimate fired
     */
    public static void emit(ServerLevel level, ServerPlayer caster, List<UbBurstVictims.Victim> landed,
            boolean enhanced) {
        if (level == null || caster == null) {
            return;
        }
        List<UbBurstTargets.Burst> bursts = UbBurstTargets.bursts(caster.getX(), caster.getZ(), landed,
                caster.getUUID(), enhanced);
        if (bursts.isEmpty()) {
            Astrum.LOGGER.info("astrum: UB cast of {} hit nothing, so no burst was drawn anywhere "
                            + "({} landed hit(s) reported, all refused or repeated)", name(caster),
                    landed == null ? 0 : landed.size());
            return;
        }
        int sent = 0;
        int seenBy = 0;
        for (UbBurstTargets.Burst burst : bursts) {
            // The centre is a fact of the plan, so it is logged <b>before</b> any packet is attempted:
            // a transport failure must not erase the one line a probe reads to answer "whose body was
            // the effect drawn on". The send result is reported separately, below.
            Astrum.LOGGER.info("astrum: UB burst centred on victim {} at ({}, {}, {}) — {} sparks + {} "
                            + "motes, blow comes from {} at ({}, {})",
                    burst.victim().id(), fmt(burst.victim().x()), fmt(burst.victim().y()),
                    fmt(burst.victim().z()), burst.sparks().size(), burst.motes().size(),
                    name(caster), fmt(caster.getX()), fmt(caster.getZ()));
            try {
                int[] result = emitOne(level, burst);
                sent += result[0];
                seenBy = Math.max(seenBy, result[1]);
            } catch (RuntimeException e) {
                Astrum.LOGGER.warn("astrum: could not emit the UB burst on victim {} — the rest of "
                        + "the cast is unaffected", burst.victim().id(), e);
            }
        }
        Astrum.LOGGER.info("astrum: UB cast emitted {} particle call(s) on {} victim(s), reached {} "
                        + "client(s) — types {}/{}, enhanced={}, nothing at the caster",
                sent, bursts.size(), seenBy, UbParticles.SPARK_ID, UbParticles.MOTE_ID, enhanced);
        if (sent == 0) {
            Astrum.LOGGER.warn("astrum: the UB burst for {} was built for {} victim(s) but reached no "
                            + "client at all — the cast itself is unaffected", name(caster),
                    bursts.size());
        }
    }

    /**
     * {@return {@code [particle calls sent, clients reached]} for one victim's burst}
     *
     * <p>Each point is one call of {@code ServerLevel.sendParticles}'s <b>area</b> form with
     * {@code count = 0}. That count is not a typo: vanilla reads {@code count == 0} as "one particle,
     * at exactly this point, with exactly this velocity" ({@code handleParticleEvent} multiplies the
     * three spread arguments by {@code maxSpeed} and passes them straight through as the velocity),
     * while {@code count > 0} means "N particles scattered randomly inside the box, each with a
     * <em>random</em> velocity". A ring needs the first reading — and it needs the area form rather
     * than the per-player one because the victim's own client has to see the blade land on them.
     */
    private static int[] emitOne(ServerLevel level, UbBurstTargets.Burst burst) {
        int sent = 0;
        int clients = 0;
        for (UbBurstTargets.Spawn spawn : burst.all()) {
            int reached = send(level, typeOf(spawn.kind()), spawn.x(), spawn.y(), spawn.z(),
                    spawn.velocity());
            sent += reached > 0 ? 1 : 0;
            clients = Math.max(clients, reached);
        }
        return new int[] {sent, clients};
    }

    /** {@return the registered particle type for {@code kind}} */
    private static ParticleOptions typeOf(UbBurstTargets.Kind kind) {
        return kind == UbBurstTargets.Kind.SPARK
                ? UbParticles.SPARK.get()
                : UbParticles.MOTE.get();
    }

    /**
     * {@return how many clients received exactly one particle at this point with this velocity}
     *
     * <p>The one place the {@code sendParticles} call shape is written, so the {@code count = 0}
     * reading above is not repeated at two loops and cannot drift between them.
     */
    private static int send(ServerLevel level, ParticleOptions type,
            double x, double y, double z, UbBurstPlan.Vector velocity) {
        return level.sendParticles(type, x, y, z, 0,
                velocity.x(), velocity.y(), velocity.z(), 1.0);
    }

    /** {@return the name used in log lines; never {@code null} for a live player} */
    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    /** {@return a coordinate with three decimals, for a readable log line} */
    private static String fmt(double value) {
        return String.format("%.3f", value);
    }
}
