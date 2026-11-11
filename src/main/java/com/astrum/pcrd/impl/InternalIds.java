package com.astrum.pcrd.impl;

import com.astrum.pcrd.Astrum;
import net.minecraft.resources.ResourceLocation;

/**
 * Factory for resource locations inside the Astrum namespace.
 */
public final class InternalIds {
    private InternalIds() {
    }

    /**
     * Creates a {@link ResourceLocation} in the {@code astrum} namespace.
     *
     * @param path the resource path, e.g. {@code "djeeta"}
     * @return the resource location {@code astrum:<path>}
     */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Astrum.MOD_ID, path);
    }
}
