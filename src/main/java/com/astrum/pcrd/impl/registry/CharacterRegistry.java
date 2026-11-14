package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.UbDef;
import com.astrum.pcrd.api.v1.UbTier;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.Lifecycle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Bootstrap and defensive loading of the {@code astrum:character} datapack registry.
 *
 * <h2>What "resilient" does and does not cover (A6 boundary)</h2>
 * The spec's A6 says a single bad JSON must be skipped rather than crash the game. That holds for
 * <b>semantic</b> errors only, and the limit is inherent rather than chosen:
 * {@code RegistryDataLoader.loadElementFromResource} has two stages — it first does
 * {@code JsonParser.parseReader(reader)} and only then {@code decoder.parse(...)}. A file that is
 * not valid JSON dies in stage one, where no codec (this one or any other) is consulted, and the
 * loader's outer {@code catch (Exception)} collects it into the same map that
 * {@code RegistryDataLoader.load} turns into a fatal
 * {@code IllegalStateException("Failed to load registries due to above errors")}. So:
 *
 * <ul>
 *   <li><b>Semantic problems</b> (unknown enum value, missing required field, wrong value type,
 *       JSON that is valid but not an object) — handled here, skipped with one WARN.</li>
 *   <li><b>Syntax problems</b> (truncated file, trailing garbage, bad escapes) — abort world load
 *       before any mod code runs; outside this mod's control.</li>
 * </ul>
 *
 * {@code CharacterRegistryTest.jsonSyntaxErrorIsOutsideCodecControl} pins that boundary.
 *
 * <h2>How semantic errors are made non-fatal</h2>
 * Because the loader has no per-entry "skip this file" path for decode failures, resilience cannot
 * live in a reload listener — it has to live in the codec. {@link #LENIENT_CODEC} decodes strictly
 * and, on failure, hands back the {@link #INVALID} placeholder instead of a
 * {@code DataResult.Error}. The registry therefore always loads; {@link #salvage} then drops the
 * placeholders, reports each dropped id once, and publishes only real definitions to
 * {@link AstrumApi}.
 *
 * <h2>Why the placeholder is compared by value</h2>
 * A synced datapack registry is re-encoded and sent to clients, so a client's placeholder is a
 * freshly decoded instance, not the same object. Identity comparison would therefore let a bogus
 * entry through on the client. {@link #isInvalid} compares by value instead; the placeholder's
 * {@code schemaVersion} of {@code 0} and non-character display name make it unreachable by any real
 * definition (version 1 is the only schema this build accepts).
 *
 * <h2>Which events, and why</h2>
 * <b>Create:</b> {@link DataPackRegistryEvent.NewRegistry} on the mod bus — the only way to create
 * the registry at all.
 *
 * <p><b>Bind:</b> {@link TagsUpdatedEvent}, fired by
 * {@code ReloadableServerResources.updateRegistryTags()} after the datapack registry layers are
 * frozen, on both the initial world load ({@code WorldLoader.load}) and every {@code /reload}
 * ({@code MinecraftServer.reloadResources}). It carries a {@code RegistryAccess}, which is the
 * handle needed to reach a datapack registry, and it fires on both sides, so no side check is
 * needed. Note that {@code /reload} re-fires this event but does <em>not</em> re-read datapack
 * registry contents — only a world re-entry picks up a changed character JSON.
 *
 * <p><b>Unbind:</b> {@link ServerStoppedEvent}, which per its javadoc fires "on the dedicated server
 * … and before returning to the main menu on the client". That is exactly the moment the registry
 * stops being valid, and it too needs no side check.
 *
 * <p>No reload listener is registered: the registry is the single loading mechanism.
 */
@ApiStatus.Internal
public final class CharacterRegistry {

    /**
     * Placeholder recorded for an entry whose JSON did not decode.
     *
     * <p>Never leaves this package: {@link #salvage} removes it before anything is published.
     */
    public static final CharacterDef INVALID = new CharacterDef(
            0, "<invalid>", Dimension.OUTPUT, Optional.empty(), Optional.empty(), List.of(),
            new UbDef(
                    new UbTier("", 0, 0.0, OptionalDouble.empty(), Optional.empty(),
                            OptionalDouble.empty(), Optional.empty()),
                    new UbTier("", 0, 0.0, OptionalDouble.empty(), Optional.empty(),
                            OptionalDouble.empty(), Optional.empty())),
            new GrowthDef(0, 0.0, 0),
            new AffectionDef(0, 0, List.of()),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(),
            // still6th: the invalid definition ships no still art at either rung.
            Optional.empty());

    /**
     * Codec the registry is built with, for both disk and network: decodes like
     * {@link CharacterDef#CODEC}, but a decode failure degrades to {@link #INVALID} instead of a
     * {@code DataResult.Error}, because the datapack loader treats any error as fatal.
     */
    public static final Codec<CharacterDef> LENIENT_CODEC =
            Codec.of(CharacterDef.CODEC, CharacterRegistry::decodeLenient);

    private CharacterRegistry() {
    }

    /**
     * Subscribes the registry to the mod bus (registry creation) and to the game bus (per-load
     * publishing, plus dropping the binding when the world goes away).
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(DataPackRegistryEvent.NewRegistry.class, CharacterRegistry::onNewRegistry);
        NeoForge.EVENT_BUS.addListener(TagsUpdatedEvent.class, CharacterRegistry::onTagsUpdated);
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, CharacterRegistry::onServerStopped);
    }

    private static void onNewRegistry(DataPackRegistryEvent.NewRegistry event) {
        // Same codec on both sides: the network codec is required so clients receive definitions,
        // and it must be lenient too, otherwise a server holding one bad definition would fail
        // every client that joins while re-encoding its registry.
        event.dataPackRegistry(RegistryKeys.CHARACTERS, LENIENT_CODEC, LENIENT_CODEC);
    }

    /**
     * Republishes the character registry after a datapack load.
     *
     * <p>Both sides fire this event: the physical server after reading the datapack, the client
     * after receiving its copy. Both bind, so single-player and multiplayer behave alike without
     * any side check; only the datapack read reports, because the client's copy is the same content
     * and logging it would double every line.
     *
     * @param event the tag update that follows every datapack load
     */
    private static void onTagsUpdated(TagsUpdatedEvent event) {
        event.getRegistryAccess().registry(RegistryKeys.CHARACTERS).ifPresent(loaded -> {
            Salvage salvage = salvage(loaded.entrySet());
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
                report(salvage);
            }
            AstrumApi.bind(salvage.skipped().isEmpty() ? loaded : copyOf(salvage.valid()));
        });
    }

    /**
     * Drops the published registry once the world it belongs to is gone.
     *
     * <p>Without this, {@link AstrumApi#characters()} would keep handing out a closed world's
     * registry, which is worse than returning nothing: a caller cannot tell stale data from live
     * data. Fires on both sides, so single-player and a dedicated server behave the same.
     *
     * @param event the server-stopped notification
     */
    private static void onServerStopped(ServerStoppedEvent event) {
        AstrumApi.unbind();
    }

    private static void report(Salvage salvage) {
        for (ResourceLocation skipped : salvage.skipped()) {
            Astrum.LOGGER.warn(skippedMessage(skipped));
        }
        for (ResourceKey<CharacterDef> key : salvage.valid().keySet()) {
            Astrum.LOGGER.info("astrum: character {} loaded", key.location());
        }
    }

    /**
     * {@return the WARN text for one skipped entry}
     *
     * <p>Pack authors only ever see this line, so it is built in one place and asserted by a test
     * instead of being formatted inline at the log call.
     *
     * @param skipped id of the entry that did not decode
     */
    public static String skippedMessage(ResourceLocation skipped) {
        return "astrum: character " + skipped + " skipped: invalid definition";
    }

    /**
     * Splits loaded entries into the ones that decoded and the ids that did not.
     *
     * <p>Separate from {@link #onTagsUpdated} so the filtering rule is testable without a game.
     *
     * @param entries every key/value pair of the loaded registry
     * @return the surviving entries in encounter order, plus the ids that were dropped
     */
    public static Salvage salvage(Collection<Map.Entry<ResourceKey<CharacterDef>, CharacterDef>> entries) {
        Map<ResourceKey<CharacterDef>, CharacterDef> valid = new LinkedHashMap<>();
        List<ResourceLocation> skipped = new ArrayList<>();
        for (Map.Entry<ResourceKey<CharacterDef>, CharacterDef> entry : entries) {
            if (isInvalid(entry.getValue())) {
                skipped.add(entry.getKey().location());
            } else {
                valid.put(entry.getKey(), entry.getValue());
            }
        }
        return new Salvage(valid, skipped);
    }

    private static boolean isInvalid(CharacterDef def) {
        return def == INVALID || INVALID.equals(def);
    }

    /**
     * {@return a frozen registry holding only {@code entries}}
     *
     * <p>Used when at least one entry was dropped, so consumers of {@link AstrumApi#characters()}
     * cannot reach a placeholder through the live registry either.
     */
    private static Registry<CharacterDef> copyOf(Map<ResourceKey<CharacterDef>, CharacterDef> entries) {
        MappedRegistry<CharacterDef> registry = new MappedRegistry<>(RegistryKeys.CHARACTERS, Lifecycle.stable());
        entries.forEach((key, def) -> registry.register(key, def, RegistrationInfo.BUILT_IN));
        return registry.freeze();
    }

    private static <T> DataResult<Pair<CharacterDef, T>> decodeLenient(DynamicOps<T> ops, T input) {
        DataResult<Pair<CharacterDef, T>> strict = CharacterDef.CODEC.decode(ops, input);
        if (strict.error().isEmpty()) {
            return strict;
        }
        // The reason is kept at DEBUG: the single WARN belongs to report(), the only place that
        // knows which entry id failed.
        Astrum.LOGGER.debug("astrum: character entry failed to decode: {}",
                strict.error().map(DataResult.Error::message).orElse("unknown error"));
        return DataResult.success(Pair.of(INVALID, input));
    }

    /**
     * Result of {@link #salvage}.
     *
     * @param valid   definitions that decoded, in encounter order
     * @param skipped ids of the entries that were dropped
     */
    public record Salvage(Map<ResourceKey<CharacterDef>, CharacterDef> valid, List<ResourceLocation> skipped) {
    }
}
