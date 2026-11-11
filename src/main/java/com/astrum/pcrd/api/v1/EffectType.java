package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Kind of effect an {@link EffectHook} applies. Also the dispatch key of {@link EffectHook#CODEC}.
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
    DETONATE;

    public static final Codec<EffectType> CODEC = StringRepresentable.fromEnum(EffectType::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
