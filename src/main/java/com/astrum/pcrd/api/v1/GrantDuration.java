package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>How long a grant lasts</b>, in one type: forever, or a number of seconds.
 *
 * <h2>Why this exists instead of {@code optionalFieldOf("seconds")}</h2>
 * The two flat EX effects have an <b>infinite</b> form and a <b>timed</b> form, and the difference is not
 * cosmetic: an EX passive is 「战斗开始时提升自身属性」 and lasts exactly as long as the character holds her
 * party slot ({@code ExPassives} adds it on enrolment and removes it on departure), while a skill's buff is
 * a number of seconds. Those are two different mechanisms downstream — the first is a permanent attribute
 * modifier, the second a {@code MobEffectInstance} with a clock — so "absent seconds" cannot be left to mean
 * "zero" or "eight" by accident.
 *
 * <p>So absence is spelled <b>{@link #permanent()}</b> and the two cannot be confused: a pack that writes no
 * {@code seconds} gets a modifier that lives until it is removed, which is what EX needs, and a pack that
 * writes {@code "seconds": 12} gets a timed buff.
 *
 * <h2>Why it is shared by both flat effects</h2>
 * {@code FLAT_ATTACK_UP} and {@code FLAT_MAGIC_ATTACK_UP} differ only in <em>which</em> attribute they
 * write. Their shape — an amount and a lifetime — is the same, so it is one record with one codec rather
 * than two that would drift the moment one of them gained a knob.
 *
 * @param seconds how long the grant lasts, or empty for "until something removes it"
 */
@ApiStatus.Experimental
public record GrantDuration(OptionalDouble seconds) {

    /** The permanent form: an EX passive, added on enrolment and removed on departure. */
    public static final GrantDuration PERMANENT = new GrantDuration(OptionalDouble.empty());

    /**
     * The codec: a bare number of seconds, or nothing at all.
     *
     * <p>Encoded as the number rather than as an object so an author writes the obvious thing:
     * {@code "effectParams": { "base": 15, "perLevel": 15, "seconds": 12 }}. A non-finite, zero or negative
     * value decodes as permanent, which is the safe direction: the grant is then owned by an explicit
     * remove rather than by a clock nobody can find.
     */
    private static final MapCodec<GrantDuration> SECONDS = Codec.DOUBLE
            .optionalFieldOf("seconds")
            .xmap(value -> new GrantDuration(value
                            .filter(seconds -> Double.isFinite(seconds) && seconds > 0.0)
                            .map(OptionalDouble::of).orElse(OptionalDouble.empty())),
                    duration -> duration.seconds().isPresent()
                            ? Optional.of(duration.seconds().getAsDouble())
                            : Optional.empty());

    public static final Codec<GrantDuration> CODEC = SECONDS.codec();

    /**
     * The same as a <b>field-less</b> map codec, so {@code seconds} can be inlined beside other keys in one
     * {@code effectParams} object.
     *
     * <p>{@link #CODEC} already carries its own {@code seconds} field, and using it with {@code forGetter}
     * inside another {@code RecordCodecBuilder.group} would nest the key one level deeper than an author
     * expects. This form puts the bare field where it belongs.
     */
    public static final MapCodec<GrantDuration> MAP_CODEC = SECONDS;

    public GrantDuration {
        seconds = seconds == null ? OptionalDouble.empty() : seconds;
    }

    /** {@return the permanent form} */
    public static GrantDuration permanent() {
        return PERMANENT;
    }

    /** {@return a grant lasting {@code seconds}, or the permanent form for a non-positive number} */
    public static GrantDuration ofSeconds(double seconds) {
        return Double.isFinite(seconds) && seconds > 0.0
                ? new GrantDuration(OptionalDouble.of(seconds))
                : PERMANENT;
    }

    /** {@return whether this grant lasts until it is removed} */
    public boolean isPermanent() {
        return seconds.isEmpty();
    }

    /**
     * {@return how many ticks this grant lasts, or {@code 0} for the permanent form}
     *
     * <p>Zero rather than {@code INFINITE_DURATION} deliberately: {@code 0} is the "no clock" answer here,
     * and a caller that wants vanilla's endless instance asks {@link #isPermanent()} first. Returning
     * {@code -1} would make {@code ticks <= 0} mean two different things.
     */
    public int ticks() {
        return isPermanent() ? 0 : Math.max(1, (int) Math.round(seconds.getAsDouble() * 20.0));
    }
}
