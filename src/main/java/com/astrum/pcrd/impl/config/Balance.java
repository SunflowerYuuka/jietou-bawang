package com.astrum.pcrd.impl.config;

import org.jetbrains.annotations.ApiStatus;

/**
 * Pure arithmetic for the combat balance knobs (spec §5.2, §8.4).
 *
 * <h2>Why this class has no config and no Minecraft types</h2>
 * Every method takes plain {@code double} / {@code int} / {@code boolean}. The {@code ModConfig}
 * values are read by the caller at the point of use and handed in here, which is what makes the
 * rules unit-testable without a running game and keeps the "read lazily" rule in one place
 * (the caller) instead of two.
 *
 * <h2>Why the base is clamped</h2>
 * {@code diminishingBase} is an operator-facing number. An operator who writes {@code 0} kills
 * every effect (0^n is 0), and one who writes {@code 2.0} makes the fifth same-dimension character
 * 16× the first. Clamping the base into {@code [0.1, 1.0]} makes both mistakes merely useless
 * rather than game-breaking (spec §8.4 guardrail 2). The product is clamped as well, so the
 * documented floor of 0.1 holds no matter how many characters share a dimension.
 */
@ApiStatus.Internal
public final class Balance {

    /** Lowest multiplier any same-dimension slot can receive (spec §5.3). */
    public static final double MIN_MULTIPLIER = 0.1;

    /** Highest multiplier any slot can receive: the first slot of a dimension. */
    public static final double MAX_MULTIPLIER = 1.0;

    private Balance() {
    }

    /**
     * {@return the multiplier for one party slot} 
     *
     * <p>The first character of a dimension gets {@code 1.0}; the second gets {@code base}, the
     * third {@code base²}, and so on, never falling below {@link #MIN_MULTIPLIER} and never exceeding
     * {@link #MAX_MULTIPLIER}. With diminishing returns switched off every slot gets {@code 1.0},
     * which is the "off" an operator expects and not "everything at the floor".
     *
     * @param sameDimensionRank zero-based rank of the slot among the earlier slots that share its
     *                          dimension; {@code 0} means "first of its dimension"
     * @param base              configured decay factor, clamped by {@link #clampBase(double)}
     * @param enabled           value of the {@code enableDiminishingReturns} config option
     */
    public static double diminishingMultiplier(int sameDimensionRank, double base, boolean enabled) {
        if (!enabled) {
            return MAX_MULTIPLIER;
        }
        return Math.clamp(Math.pow(clampBase(base), sameDimensionRank), MIN_MULTIPLIER, MAX_MULTIPLIER);
    }

    /**
     * {@return {@code raw} clamped into {@link #MIN_MULTIPLIER}..{@link #MAX_MULTIPLIER}}
     *
     * <p>Applied to {@code diminishingBase} at the point of use, so a stale or hand-edited config
     * file cannot push the decay factor outside the playable range (spec §8.4 guardrail 2).
     *
     * <p><b>{@code NaN} is special-cased.</b> Every comparison with {@code NaN} is false, so
     * {@code Math.clamp(NaN, min, max)} answers {@code NaN} rather than a bound — the range promise
     * would silently fail and the {@code NaN} would spread through every multiplier derived from it.
     * Since TOML can carry {@code nan}, it is mapped to the floor: the same conservative choice as
     * an operator writing {@code 0}, and the value the spec calls for at the bottom of the range.
     *
     * @param raw the value as read from config, possibly hostile
     */
    public static double clampBase(double raw) {
        if (Double.isNaN(raw)) {
            return MIN_MULTIPLIER;
        }
        return Math.clamp(raw, MIN_MULTIPLIER, MAX_MULTIPLIER);
    }
}
