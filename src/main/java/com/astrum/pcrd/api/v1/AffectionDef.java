package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Affection tiers of a character. Tier 2 only scales existing hooks, it never adds one.
 */
@ApiStatus.Experimental
public record AffectionDef(int tier2Threshold, int tier3Threshold, List<HookModifier> tier2HookModifiers) {
    public static final Codec<AffectionDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("tier2Threshold").forGetter(AffectionDef::tier2Threshold),
            Codec.INT.fieldOf("tier3Threshold").forGetter(AffectionDef::tier3Threshold),
            HookModifier.CODEC.listOf().fieldOf("tier2HookModifiers").forGetter(AffectionDef::tier2HookModifiers)
    ).apply(instance, AffectionDef::new));
}
