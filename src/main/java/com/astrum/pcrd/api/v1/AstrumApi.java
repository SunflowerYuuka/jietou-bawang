package com.astrum.pcrd.api.v1;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Registry;
import org.jetbrains.annotations.ApiStatus;

/**
 * The single facade of {@code api.v1} (spec §9.2 A4).
 *
 * <p>The character registry is a NeoForge datapack registry, so it only exists while a world is
 * loaded. What {@link #characters()} returns therefore tracks the world lifecycle exactly:
 *
 * <ul>
 *   <li><b>Before any world has loaded</b> — {@link Optional#empty()}, never {@code null} and never
 *       a half-built registry.</li>
 *   <li><b>While a world is loaded</b> — the registry the implementation bound, containing only
 *       definitions that decoded cleanly (spec §9.2 A6).</li>
 *   <li><b>After the world is left or the server stops</b> — {@link Optional#empty()} again. The
 *       binding is dropped rather than left pointing at a closed world's data, so a caller that
 *       read {@code empty()} knows to wait for the next load instead of acting on stale entries.</li>
 * </ul>
 *
 * <p>Callers should treat {@link Optional#empty()} as "no character data right now", not as an
 * error, and re-read the facade rather than caching it across worlds.
 */
@ApiStatus.Experimental
public final class AstrumApi {

    private static volatile Registry<CharacterDef> characters;

    private AstrumApi() {
    }

    /**
     * {@return every character definition loaded in the current world, or empty outside one}
     *
     * <p>The registry only ever contains definitions that decoded cleanly: entries whose JSON was
     * broken are skipped and logged, never surfaced here (spec §9.2 A6). Note that this covers
     * <em>semantic</em> problems only — see {@code CharacterRegistry.LENIENT_CODEC} for the exact
     * boundary.
     */
    public static Optional<Registry<CharacterDef>> characters() {
        return Optional.ofNullable(characters);
    }

    /**
     * Publishes the loaded character registry to {@link #characters()}.
     *
     * <p>Implementation detail, called by {@code impl.registry} after each datapack load.
     *
     * @param registry the freshly loaded registry, never {@code null}
     */
    @ApiStatus.Internal
    public static void bind(Registry<CharacterDef> registry) {
        characters = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Drops the published registry, so {@link #characters()} is empty again.
     *
     * <p>Implementation detail, called by {@code impl.registry} when the world goes away. Idempotent:
     * the shutdown path may run more than once.
     */
    @ApiStatus.Internal
    public static void unbind() {
        characters = null;
    }
}
