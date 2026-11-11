package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/**
 * Damage dimension used for same-tick multiplicative falloff.
 */
public enum Dimension implements StringRepresentable {
    OUTPUT,
    SURVIVAL,
    CHARGE,
    CONTROL,
    RESOURCE;

    public static final Codec<Dimension> CODEC = StringRepresentable.fromEnum(Dimension::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
