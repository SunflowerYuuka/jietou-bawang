package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.ApiStatus;

/**
 * Experience curve of a character: level {@code n} needs {@code expBase * expPerLevel^(n-1)}.
 */
@ApiStatus.Experimental
public record GrowthDef(int expBase, double expPerLevel, int maxLevel) {
    public static final Codec<GrowthDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("expBase").forGetter(GrowthDef::expBase),
            Codec.DOUBLE.fieldOf("expPerLevel").forGetter(GrowthDef::expPerLevel),
            Codec.INT.fieldOf("maxLevel").forGetter(GrowthDef::maxLevel)
    ).apply(instance, GrowthDef::new));
}
