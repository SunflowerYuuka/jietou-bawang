package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One tier of a character's UB (ultimate burst).
 *
 * @param animation       animation name played by the showcase
 * @param hits            number of damage ticks
 * @param damage          damage per hit
 * @param radius          area radius, empty for a single-target UB
 * @param selfAtkBuff     multiplier applied to the caster's attack, empty for none
 * @param buffTicks       duration of {@code selfAtkBuff} in ticks
 * @param teamChargeBonus charge granted to allies, empty for none
 * @param extra           free-form extra tag, e.g. {@code armor_break}
 */
public record UbTier(String animation, int hits, double damage, OptionalDouble radius, OptionalDouble selfAtkBuff,
                     int buffTicks, OptionalDouble teamChargeBonus, Optional<String> extra) {

    public static final Codec<UbTier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("animation").forGetter(UbTier::animation),
            Codec.INT.fieldOf("hits").forGetter(UbTier::hits),
            Codec.DOUBLE.fieldOf("damage").forGetter(UbTier::damage),
            Codec.DOUBLE.optionalFieldOf("radius").xmap(UbTier::toOptionalDouble, UbTier::toOptional).forGetter(UbTier::radius),
            Codec.DOUBLE.optionalFieldOf("selfAtkBuff").xmap(UbTier::toOptionalDouble, UbTier::toOptional)
                    .forGetter(UbTier::selfAtkBuff),
            Codec.INT.optionalFieldOf("buffTicks", 0).forGetter(UbTier::buffTicks),
            Codec.DOUBLE.optionalFieldOf("teamChargeBonus").xmap(UbTier::toOptionalDouble, UbTier::toOptional)
                    .forGetter(UbTier::teamChargeBonus),
            Codec.STRING.optionalFieldOf("extra").forGetter(UbTier::extra)
    ).apply(instance, UbTier::new));

    private static OptionalDouble toOptionalDouble(Optional<Double> value) {
        return value.isPresent() ? OptionalDouble.of(value.get()) : OptionalDouble.empty();
    }

    private static Optional<Double> toOptional(OptionalDouble value) {
        return value.isPresent() ? Optional.of(value.getAsDouble()) : Optional.empty();
    }
}
