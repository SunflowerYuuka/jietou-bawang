package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jetbrains.annotations.ApiStatus;

/**
 * One tier of a character's UB (ultimate burst).
 *
 * <h2>Both tiers of one character usually share {@code hits}</h2>
 * The 6★ ultimate is a <em>strengthening</em> of the 5★ one, not a replacement: 星河一天 keeps 疾风剑雨's
 * four hits and adds 「特大幅度提升自己的物理攻击 18 秒」. So the shipped character declares
 * {@code hits: 4} on <b>both</b> tiers, and what the enhanced tier adds is damage and
 * {@link #attackBuff} — never a different hit count. The codec does not enforce that (a pack may
 * legitimately ship two unrelated tiers), so {@code DjeetaResourceTest} pins it for this character.
 *
 * @param animation   animation name played by the showcase
 * @param hits        number of damage ticks
 * @param damage      damage per hit
 * @param radius      area radius, empty for a single-target UB; also the reach of {@link #attackBuff}
 * @param attackBuff  the attack buff this tier grants the caster and her party, empty for none
 * @param teamChargeBonus charge granted to allies, empty for none
 * @param extra       free-form extra tag, e.g. {@code armor_break}
 */
@ApiStatus.Experimental
public record UbTier(String animation, int hits, double damage, OptionalDouble radius,
                     Optional<AttackBuffDef> attackBuff, OptionalDouble teamChargeBonus,
                     Optional<String> extra) {
    public static final Codec<UbTier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("animation").forGetter(UbTier::animation),
            Codec.INT.fieldOf("hits").forGetter(UbTier::hits),
            Codec.DOUBLE.fieldOf("damage").forGetter(UbTier::damage),
            Codec.DOUBLE.optionalFieldOf("radius").xmap(UbTier::toOptionalDouble, UbTier::toOptional)
                    .forGetter(UbTier::radius),
            AttackBuffDef.CODEC.optionalFieldOf("attackBuff").forGetter(UbTier::attackBuff),
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

    /**
     * The attack buff one UB tier grants (「特大幅度提升自己的物理攻击」).
     *
     * <p>Its own record rather than two scalars on {@link UbTier} because the two numbers only mean
     * something together — a multiplier with no duration is not a buff, and a duration with no
     * multiplier is not one either — and because {@code seconds} is what the wiki states, so the
     * datapack should say seconds rather than making every author divide by twenty.
     *
     * <p>The same two-field shape is what {@link EffectHook.AttackBuff} takes in {@code effectParams},
     * so a pack author writes the buff the same way whether it hangs off an ultimate or off a swing.
     *
     * <h2>Who receives it</h2>
     * 「自己」 notwithstanding, the game's 星河一天 raises the <b>whole party's</b> physical attack: the
     * boost is part of the ultimate itself (verified against gamewith, not a CHAIN effect), i.e. an area
     * effect. So it lands on the caster <em>and</em> every ally inside {@link UbTier#radius()}, and
     * {@code AttackBuffs} owns that rule (through {@code AllySelector}, never a second ally test).
     * A tier with no radius still buffs its party, at that class's documented default.
     *
     * @param multiplier attack multiplier while it lasts, {@code 1.5} = +50%; at or below {@code 1.0}
     *                   is not a buff and is refused when it is applied
     * @param seconds    how long it lasts, in seconds
     */
    @ApiStatus.Experimental
    public record AttackBuffDef(double multiplier, double seconds) {
        public static final Codec<AttackBuffDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("multiplier").forGetter(AttackBuffDef::multiplier),
                Codec.DOUBLE.fieldOf("seconds").forGetter(AttackBuffDef::seconds)
        ).apply(instance, AttackBuffDef::new));
    }
}
