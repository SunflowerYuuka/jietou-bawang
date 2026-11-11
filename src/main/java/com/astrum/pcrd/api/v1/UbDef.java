package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.ApiStatus;

/**
 * The two UB tiers of a character: the base one and the one unlocked at affection tier 3.
 */
@ApiStatus.Experimental
public record UbDef(UbTier base, UbTier enhanced) {
    public static final Codec<UbDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UbTier.CODEC.fieldOf("base").forGetter(UbDef::base),
            UbTier.CODEC.fieldOf("enhanced").forGetter(UbDef::enhanced)
    ).apply(instance, UbDef::new));
}
