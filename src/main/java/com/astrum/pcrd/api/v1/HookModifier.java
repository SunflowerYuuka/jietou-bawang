package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Multiplier applied to one hook once the character reaches affection tier 2.
 *
 * @param hookIndex index into {@link CharacterDef#effects()}
 * @param mult      multiplier, {@code 1.5} = +50%
 */
public record HookModifier(int hookIndex, double mult) {
    public static final Codec<HookModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("hookIndex").forGetter(HookModifier::hookIndex),
            Codec.DOUBLE.fieldOf("mult").forGetter(HookModifier::mult)
    ).apply(instance, HookModifier::new));
}
