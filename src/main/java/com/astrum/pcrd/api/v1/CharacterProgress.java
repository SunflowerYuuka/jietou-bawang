package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Per-character player progress, stored in the roster attachment.
 */
public record CharacterProgress(int level, int exp, int affection) {
    public static final Codec<CharacterProgress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("level").forGetter(CharacterProgress::level),
            Codec.INT.fieldOf("exp").forGetter(CharacterProgress::exp),
            Codec.INT.fieldOf("affection").forGetter(CharacterProgress::affection)
    ).apply(instance, CharacterProgress::new));
}
