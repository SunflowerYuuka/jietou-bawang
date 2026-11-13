package com.astrum.pcrd.impl.showcase;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration of the {@code astrum:showcase} entity type.
 *
 * <h2>Why the type is built the way it is</h2>
 * Every builder call below is a spec §5.6 requirement, not a default that happened to be convenient:
 *
 * <ul>
 *   <li>{@code sized(0.6F, 1.8F)} — a player-shaped box, so the renderer's bounding box is roughly the
 *       figure's. It is used for culling only; nothing collides with it.</li>
 *   <li>{@code noSummon()} — {@code /summon astrum:showcase} is refused. A showcase outside a cast has
 *       no animation, no owner and no reason to exist, and letting an operator create one would be a
 *       second way into {@link ShowcaseManager}'s budget that does not go through it.</li>
 *   <li>{@code noSave()} — it is never written to a level file. A saved cutscene would be resurrected on
 *       the next world load with no caster and a stale animation (spec §5.6: 不参与世界 tick 逻辑).</li>
 *   <li>{@code clientTrackingRange(10)} — 160 blocks, the same as a player: a cutscene is meant to be
 *       seen by everyone who can see the caster, and no further.</li>
 *   <li>{@code setUpdateInterval(3)} — the entity never moves and never changes state after the spawn
 *       packet, so 20 packets per second per showcase would be pure waste. Three ticks is the same
 *       interval armour stands use.</li>
 *   <li>{@code fireImmune()} — it must not catch fire and burn a hole in the picture.</li>
 *   <li>{@code MobCategory.MISC} — it is not a creature: it must not count towards mob caps, must not
 *       be included in mob-spawn statistics, and must not be despawned by difficulty changes.</li>
 * </ul>
 *
 * <h2>Why this class exists at all</h2>
 * So {@code Astrum.java} keeps one call per subsystem instead of one per register (plan R1), and so the
 * entity type and the cast sink are wired together: the type is registered here, and the sink the cast
 * path uses is chosen here — {@code EffectsOnlySink} today, {@code ShowcaseManager} when the entity is
 * switched back on.
 */
@ApiStatus.Internal
public final class ShowcaseTypes {

    /** The mod-relative path, i.e. the registry key's path. */
    public static final ResourceLocation ENTITY_ID = InternalIds.id("showcase");

    /**
     * The entity's display name key.
     *
     * <p>Unused by design at the moment — the entity is not pickable, so no player ever sees its name
     * — but vanilla reads a name key for every entity type on its {@code /data} and death-message
     * surfaces, and {@code LangParityTest} source-pins this constant so a rename cannot leave one file
     * behind.
     */
    public static final String LANG_KEY = "entity.astrum.showcase";

    /** Entity-type registrations, on the mod bus. */
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Astrum.MOD_ID);

    /**
     * The showcase entity type.
     *
     * <p>Registered as {@link #ENTITY_ID}'s path, so the id a datapack, a log line or the
     * {@code guard} check sees is the same {@code astrum:showcase}.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<ShowcaseEntity>> SHOWCASE =
            ENTITY_TYPES.register(ENTITY_ID.getPath(),
                    () -> EntityType.Builder.<ShowcaseEntity>of(ShowcaseEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .setUpdateInterval(3)
                            .fireImmune()
                            .noSummon()
                            .noSave()
                            .build(ENTITY_ID.toString()));

    private ShowcaseTypes() {
    }

    /**
     * Subscribes the entity type to the mod bus and installs the <b>effects-only</b> cast sink.
     *
     * <h2>Why the entity type is still registered while nothing spawns it</h2>
     * 不需要实体，但是实体的相关代码保留，后续考虑是否增加. Registering it is what keeps the entity code
     * <em>alive</em> rather than merely present: the class is loaded through the type's factory, the
     * {@code GeoEntity}/{@code GeoModel} path is compiled against GeckoLib, and {@code /data} still
     * resolves {@code astrum:showcase}'s name key. A later task flips the cast path back by installing
     * {@code ShowcaseManager} instead — see {@link ShowcaseManager#installEntitySink()} — and nothing
     * else has to be restored.
     *
     * <p>The sink installed here is {@link com.astrum.pcrd.impl.ub.EffectsOnlySink}, which spawns
     * nothing. {@code ShowcaseManager.install()} is deliberately <b>not</b> called: it is the entity
     * sink, and it stays in the tree unused (its javadoc says so).
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        // The sink is installed here rather than from Astrum so the two halves of the subsystem are
        // wired by one call: Astrum stays wiring-only, and no future edit can register the entity
        // without deciding which sink the cast path uses.
        com.astrum.pcrd.impl.ub.EffectsOnlySink.install();
    }
}
