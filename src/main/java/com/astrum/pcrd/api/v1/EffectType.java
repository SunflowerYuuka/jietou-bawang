package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Kind of effect an {@link EffectHook} applies. Also the dispatch key of {@link EffectHook#CODEC}.
 *
 * <h2>Adding a value is additive</h2>
 * {@link #CODEC} is {@link StringRepresentable#fromEnum}, which reads an unknown name as
 * {@code DataResult.error}. A pack that names a value a given build does not know therefore fails to
 * decode that one hook rather than being misread as some other effect, and the other values keep their
 * meaning: the enum's order and names are the wire format, so a new value goes at the end and no
 * existing pack changes behaviour.
 */
@ApiStatus.Experimental
public enum EffectType implements StringRepresentable {
    APPLY_EFFECT,
    EXTRA_DAMAGE,
    LIFESTEAL,
    GAIN_CHARGE,
    AREA_CHARGE,
    SPLASH,
    STACK,
    DETONATE,
    /**
     * Raises the caster's own attack for a while (「特大幅度提升自己的物理攻击」).
     *
     * <p>Last, so the eight values shipped before it keep their ordinal positions.
     */
    ATTACK_BUFF;

    public static final Codec<EffectType> CODEC = StringRepresentable.fromEnum(EffectType::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
