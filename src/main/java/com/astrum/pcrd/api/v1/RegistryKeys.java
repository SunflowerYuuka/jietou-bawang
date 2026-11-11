package com.astrum.pcrd.api.v1;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Keys of the datapack registries this mod exposes.
 *
 * <p>Kept free of any {@code impl} reference on purpose: the {@code astrum} namespace is spelled out
 * here instead of going through {@code InternalIds}, so this package never depends on the
 * implementation (spec §9.2 A1).
 */
@ApiStatus.Experimental
public final class RegistryKeys {
    /**
     * The character registry, loaded from {@code data/<namespace>/astrum/character/<id>.json}.
     */
    public static final ResourceKey<Registry<CharacterDef>> CHARACTERS =
            ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("astrum", "character"));

    private RegistryKeys() {
    }
}
