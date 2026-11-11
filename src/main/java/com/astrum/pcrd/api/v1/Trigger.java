package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

/**
 * Event that can attach an {@link EffectHook} to a character.
 */
public enum Trigger implements StringRepresentable {
    MELEE_HIT,
    CRIT,
    PROJECTILE_HIT,
    KILL,
    LOW_HEALTH,
    COMBO_N,
    BLOCK,
    STACK_FULL;

    public static final Codec<Trigger> CODEC = StringRepresentable.fromEnum(Trigger::values);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
