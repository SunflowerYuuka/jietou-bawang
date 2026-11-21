package com.astrum.pcrd.api.v1;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>one</b> {@code effectParams} shape every tiered effect shares: a tier, and the two knobs the
 * timed area effects need.
 *
 * <h2>Why one record and not ten</h2>
 * The ten tiered effects differ in <em>what a rung is worth</em> (that is {@link EffectTiers}' table) and
 * in <em>who they reach</em> (that is the executor's and {@code AllySelector}'s job). They do not differ
 * in what an author has to write: every one of them says "which rung", and the area/timed ones also say
 * "how long" and "how far". So the JSON is
 *
 * <pre>
 *   "effectParams": { "tier": 3, "seconds": 8.0, "radius": 8.0 }
 * </pre>
 *
 * for all ten — {@code seconds} and {@code radius} simply omitted where the effect has no clock or no
 * area. That is one codec, one set of defaults, and one place to add a knob; ten per-effect
 * {@code Params} records would have been ten copies of the same three fields and ten chances for the
 * defaults to drift apart.
 *
 * <h2>Clamping is not here</h2>
 * {@code tier} is decoded <b>as written</b> and clamped where it is used, by
 * {@link EffectTiers#resolve} — the same split the amplifier fields already follow (data is data; the
 * rule that a value is legal lives with the rule). That way {@code "tier": 9} on a four-rung effect does
 * not make the hook fail to load: it loads, the executor clamps to the top rung and logs once.
 *
 * <h2>Lenient on the wire, strict nowhere</h2>
 * {@code tier} accepts a bare integer or a numeric string, and anything unparseable becomes
 * {@link EffectTiers#FIRST_TIER}. A datapack typo therefore produces a working hook at its weakest rung
 * plus one warning, never a hook that silently disappears — the failure mode this project treats as the
 * worst of the three.
 *
 * @param tier    which rung, 1-based; see {@link EffectTiers} for each effect's ladder
 * @param seconds how long it lasts, for the effects that have a clock; empty for the instant ones
 * @param radius  how far it reaches, for the area effects; empty for the party default, {@code 0.0} for
 *                "the caster only" — exactly the reading {@code AttackBuffs.radiusFor} gives it
 */
@ApiStatus.Experimental
public record TieredParams(int tier, OptionalDouble seconds, OptionalDouble radius) {

    /** Tier 1, no clock, default reach: what an omitted {@code effectParams} would mean. */
    public static final TieredParams FIRST =
            new TieredParams(EffectTiers.FIRST_TIER, OptionalDouble.empty(), OptionalDouble.empty());

    /**
     * The tier value's codec: an int, or a string holding one, or tier 1.
     *
     * <p>A type mismatch is not a decode failure on purpose. {@code Codec.INT} would reject
     * {@code "tier": "3"} and reject {@code "tier": "x"}, and a rejected hook is a <em>missing</em>
     * effect — the one outcome the brief rules out. {@link #parseTier} answers the weakest legal rung
     * for anything it cannot read, and the executor's clamp reports the rest.
     */
    private static final Codec<Integer> TIER_VALUE = Codec.either(Codec.INT, Codec.STRING)
            .xmap(either -> either.map(value -> value, TieredParams::parseTier), Either::left);

    /**
     * The parameters as a <b>field-less</b> map codec, so a hook can inline them beside its own keys.
     *
     * <p>{@code mapCodec} rather than a nested {@code fieldOf("tiered")}: the three keys go straight into
     * whatever object the map codec is embedded in. Production hooks embed it under {@code effectParams}
     * ({@code TieredParams.CODEC.fieldOf("effectParams")}), which is this schema's rule for a hook's
     * parameters — so an author writes
     *
     * <pre>
     *   "effectParams": { "tier": 3, "seconds": 8.0 }
     * </pre>
     *
     * and the three keys sit directly in that object rather than under a further {@code "tiered"} key.
     * {@link #CODEC} is the same thing as a standalone codec, for a caller that wants one value.
     */
    public static final MapCodec<TieredParams> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            // Default 1, so `{"radius": 8.0}` alone is a legal, weakest-rung hook.
            TIER_VALUE.optionalFieldOf("tier", EffectTiers.FIRST_TIER).forGetter(TieredParams::tier),
            Codec.DOUBLE.optionalFieldOf("seconds").xmap(TieredParams::toOptionalDouble, TieredParams::toOptional)
                    .forGetter(TieredParams::seconds),
            Codec.DOUBLE.optionalFieldOf("radius").xmap(TieredParams::toOptionalDouble, TieredParams::toOptional)
                    .forGetter(TieredParams::radius)
    ).apply(instance, TieredParams::new));

    public static final Codec<TieredParams> CODEC = MAP_CODEC.codec();

    /**
     * {@return the tier as written, or the first tier when the text is not a number}
     *
     * @param text the string a pack wrote
     */
    static int parseTier(String text) {
        if (text == null) {
            return EffectTiers.FIRST_TIER;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return EffectTiers.FIRST_TIER;
        }
    }

    /**
     * {@return this tier, clamped against {@code type}'s ladder}
     *
     * <p>The one call an executor makes: it needs a legal tier and it needs to know whether to warn.
     *
     * @param type the effect whose ladder applies
     */
    public EffectTiers.Resolved resolveFor(EffectType type) {
        return EffectTiers.resolve(type, tier);
    }

    /** {@return the tier-1 shape with the given clock and reach} */
    public static TieredParams of(int tier, double seconds, double radius) {
        return new TieredParams(tier, OptionalDouble.of(seconds), OptionalDouble.of(radius));
    }

    /** {@return the tier-1 shape with no clock} */
    public static TieredParams ofTier(int tier) {
        return new TieredParams(tier, OptionalDouble.empty(), OptionalDouble.empty());
    }

    private static OptionalDouble toOptionalDouble(Optional<Double> value) {
        return value.isPresent() ? OptionalDouble.of(value.get()) : OptionalDouble.empty();
    }

    private static Optional<Double> toOptional(OptionalDouble value) {
        return value.isPresent() ? Optional.of(value.getAsDouble()) : Optional.empty();
    }
}
