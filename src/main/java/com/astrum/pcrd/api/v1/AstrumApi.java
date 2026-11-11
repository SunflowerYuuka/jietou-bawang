package com.astrum.pcrd.api.v1;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Registry;
import org.jetbrains.annotations.ApiStatus;

/**
 * The single facade of {@code api.v1} (spec §9.2 A4).
 *
 * <p>Load order: the character registry is a NeoForge datapack registry, so it only exists once a
 * world's data has been loaded. {@code AstrumApi} therefore starts empty and serves whatever the
 * implementation last handed to {@link #bind(Registry)} — never a half-built or stale registry.
 */
@ApiStatus.Experimental
public final class AstrumApi {

    private static volatile Registry<CharacterDef> characters;

    private AstrumApi() {
    }

    /**
     * {@return every character definition currently loaded, or empty before the first world load}
     *
     * <p>The registry only ever contains definitions that decoded cleanly: entries whose JSON was
     * broken are skipped and logged, never surfaced here (spec §9.2 A6).
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
}
