package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * The two particle types a UB cast spawns: a fast {@code spark} (a slash) and a slow {@code mote}.
 *
 * <h2>Why a custom type instead of vanilla {@code FIREWORK} / {@code END_ROD}</h2>
 * The art is the point. Minecraft's particle system supports custom sprites through
 * {@code assets/<namespace>/particles/<name>.json} plus a client {@code ParticleProvider}; the
 * previous implementation's claim that "a registered ParticleType still wouldn't blit the PNGs"
 * was simply wrong. A custom type is what lets the official-art atlas be the thing that is
 * actually drawn.
 *
 * <h2>Registered on the mod bus, on both sides</h2>
 * {@link DeferredRegister} with {@link Registries#PARTICLE_TYPE} is the registry the dedicated
 * server and the client both need: the server encodes a particle spawn as an id, and the client
 * resolves that id to a provider. Registration is called from {@code Astrum}'s constructor, i.e.
 * common wiring, exactly like the entity type.
 *
 * <h2>Why the textures are not named here</h2>
 * A {@link SimpleParticleType} owns no sprite. The connection from id to PNG lives where the client
 * resource system can see it: {@code assets/astrum/particles/ub_spark.json} names
 * {@code astrum:particle/ub_spark}, and {@code UbParticleProviders} binds that sprite set to this
 * type. Keeping the PNG name out of this class is what lets a resource pack re-skin the particle
 * without a code change.
 */
@ApiStatus.Internal
public final class UbParticles {

    /** The slash streak. */
    public static final ResourceLocation SPARK_ID = InternalIds.id("ub_spark");

    /** The slow rising ember. */
    public static final ResourceLocation MOTE_ID = InternalIds.id("ub_mote");

    /** Particle-type registrations, on the mod bus. */
    private static final DeferredRegister<ParticleType<?>> TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Astrum.MOD_ID);

    /**
     * The spark type.
     *
     * <p>{@code overrideLimiter = false}: a burst of ~36 short-lived sparks is well inside vanilla's
     * particle budget, and claiming the override would let a cast push out other players' particles
     * on a busy server for no benefit.
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARK =
            TYPES.register(SPARK_ID.getPath(), () -> new SimpleParticleType(false));

    /** The mote type. Kept separate so one type's provider cannot mix the two motions. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MOTE =
            TYPES.register(MOTE_ID.getPath(), () -> new SimpleParticleType(false));

    private UbParticles() {
    }

    /**
     * Subscribes both particle types to the mod bus.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        Astrum.LOGGER.info("astrum: particle types registered ({} -> particle/ub_spark, "
                + "{} -> particle/ub_mote)", SPARK_ID, MOTE_ID);
    }
}