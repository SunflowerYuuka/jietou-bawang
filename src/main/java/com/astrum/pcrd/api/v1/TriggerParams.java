package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Extra parameters a {@link Trigger} needs. Everything is optional so that simple triggers stay
 * declarative.
 *
 * @param hits    hit count for {@link Trigger#COMBO_N}, {@code 0} when unused
 * @param stackId stack that {@link Trigger#STACK_FULL} watches, empty when unused
 */
@ApiStatus.Experimental
public record TriggerParams(int hits, Optional<ResourceLocation> stackId) {
    public static final TriggerParams NONE = new TriggerParams(0, Optional.empty());

    public static final Codec<TriggerParams> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("hits", 0).forGetter(TriggerParams::hits),
            ResourceLocation.CODEC.optionalFieldOf("stackId").forGetter(TriggerParams::stackId)
    ).apply(instance, TriggerParams::new));
}
