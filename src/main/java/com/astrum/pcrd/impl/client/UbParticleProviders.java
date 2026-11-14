package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.config.ClientConfig;
import com.astrum.pcrd.impl.ub.UbParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Binds the two registered particle types to their sprites and their {@link UbSparkParticle} /
 * {@link UbMoteParticle} implementations.
 *
 * <h2>Why this is the client-only half, and why {@code guard} cares</h2>
 * {@code RegisterParticleProvidersEvent} only exists on the logical client, and everything it
 * touches ({@code ParticleProvider}, {@code TextureSheetParticle}) is {@code net.minecraft.client}.
 * This project's {@code guard} forbids importing that package outside {@code impl/client}, so the
 * type (common) and the provider (client) deliberately live in different classes. That split is
 * also what lets the dedicated server encode a particle id it never has to render.
 *
 * <h2>The config toggle is enforced here</h2>
 * {@code [effect] enableUbCastEffect} is a <b>client</b> file: the server must not read it, and a
 * round-trip to ask "may I draw?" would make the effect depend on a packet the damage does not.
 * Instead the server sends the burst unconditionally and this provider returns {@code null} when
 * the option is off. Returning {@code null} from a {@code ParticleProvider} is a supported vanilla
 * outcome — {@code ParticleEngine.makeParticle} simply does not add a particle — so the toggle
 * still means "spawn nothing on my screen" without touching the cast, the cooldown or the damage.
 *
 * <h2>The sprite sets</h2>
 * {@code registerSpriteSet} requires a matching {@code assets/astrum/particles/<id>.json} carrying
 * one or more textures under {@code assets/astrum/textures/particle/}. The two JSONs shipped here
 * each name <b>one</b> strip, and the subclasses index a cell out of it:
 *
 * <pre>
 *   ub_spark.png  640x64  ten 64x64 cells   (official Djeeta slash/crescent/lightning sprites)
 *   ub_mote.png   384x64  six 64x64 cells   (official Djeeta orbs, sparkles, golden shapes)
 * </pre>
 *
 * One texture rather than one file per sprite is deliberate: the JSON binds the whole strip, and
 * {@code tools/gen_ub_particles.py} is the single place that decides which official sprites go into
 * it. A resource pack can still replace either PNG without a code change.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class UbParticleProviders {

    private UbParticleProviders() {
    }

    /**
     * Adds the provider-registration listener to the mod bus. Called from {@code ClientEvents}'s
     * {@code Dist.CLIENT} wiring, so no client class is ever loaded on a dedicated server.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterParticleProvidersEvent.class,
                UbParticleProviders::onRegisterParticleProviders);
    }

    /**
     * Registers both sprite-set providers.
     *
     * <p>The config check is inside the provider, not here: the registration event runs during
     * client startup, before a config file is guaranteed to be loaded, and the toggle has to follow
     * an edit made in the config screen without a restart.
     *
     * <p>The velocity the provider is handed is the <b>server's</b> — {@code UbBurstPlan} computed it
     * from the blow's direction — and it is passed straight to the particle so both halves of a
     * burst travel the way the cast did. That is also why the packet must carry it: the count-0 form
     * of {@code sendParticles} is the only one that delivers a velocity unchanged (see
     * {@code UbCastParticles#send}).
     *
     * @param event NeoForge's client particle-provider event
     */
    static void onRegisterParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(UbParticles.SPARK.get(), sprites ->
                (type, level, x, y, z, vx, vy, vz) -> enabled()
                        ? new UbSparkParticle(level, x, y, z, vx, vy, vz, sprites)
                        : null);
        event.registerSpriteSet(UbParticles.MOTE.get(), sprites ->
                (type, level, x, y, z, vx, vy, vz) -> enabled()
                        ? new UbMoteParticle(level, x, y, z, vx, vy, vz, sprites)
                        : null);
        // One INFO line with the part a probe can check: both providers are wired, and the two JSONs
        // that bind them to sprites are named. The JSONs themselves are not read here: this event fires
        // while the client is still being constructed, before the resource manager exists, so any
        // "present?" answer taken here would be a lie. The sprite is really resolved when the first
        // particle is created, and that is where a missing texture becomes visible.
        Astrum.LOGGER.info("astrum: particle providers registered for {} (UbSparkParticle) and {} "
                        + "(UbMoteParticle); sprites come from assets/astrum/particles/{}.json and "
                        + "assets/astrum/particles/{}.json",
                UbParticles.SPARK_ID, UbParticles.MOTE_ID,
                UbParticles.SPARK_ID.getPath(), UbParticles.MOTE_ID.getPath());
    }

    /** {@return whether the client may draw the UB burst right now — a lazy client-config read} */
    private static boolean enabled() {
        return ClientConfig.EFFECT.enableUbCastEffect.get();
    }
}