package com.astrum.pcrd.impl.effect;

import org.jetbrains.annotations.ApiStatus;

/**
 * The numbers behind {@link AttackBuffEffect}: how a datapack multiplier becomes a vanilla amplifier,
 * how long the buff lasts, and the rule that makes re-applying it refresh instead of compound.
 *
 * <h2>Why the multiplier travels in the amplifier</h2>
 * A {@link net.minecraft.world.effect.MobEffect} declares its attribute modifier <b>once, on the type</b>
 * — not per instance. The only per-instance knob vanilla offers is the amplifier. So the multiplier has
 * to be encoded in an {@code int}, and this class is that encoding, written down once:
 *
 * <pre>
 *   amplifier = round((multiplier - 1.0) / 0.01) * 100   // conceptually: 1.5 -> 50
 *   fraction  = amplifier / 100.0                        // 50 -> 0.50, i.e. +50%
 * </pre>
 *
 * <p>The effect is registered with NeoForge's <b>level curve</b> overload
 * ({@code addAttributeModifier(attribute, id, operation, Int2DoubleFunction)}), so the fraction is
 * exactly {@code amplifier / 100} — <em>not</em> the {@code amount × (amplifier + 1)} that a plain
 * template would give. That is what makes the encoding exact: a linear step instead of a geometric one.
 *
 * <h2>The resolution, and what it costs</h2>
 * Vanilla caps an amplifier at {@link #MAX_AMPLIFIER} (255), so the representable range is
 * {@code [1.00, 3.55]} in steps of {@code 0.01}. In range the mapping is exact for a multiplier written
 * with two decimals; outside it the value is clamped, and {@link #isRepresentable} says so, which is what
 * lets the executor warn instead of silently handing the player a different buff than the pack asked
 * for. The shipped buff (1.5 → 1.50×) is exact.
 *
 * <h2>The stacking rule: refresh, never compound</h2>
 * Two independent vanilla mechanisms make re-application safe, and both are required:
 *
 * <ol>
 *   <li><b>Duration.</b> A player's effects are a map keyed by effect <em>type</em>, and
 *       {@code MobEffectInstance.update} merges a re-application of the same type into the existing
 *       entry: with an equal amplifier it takes the longer duration, so casting again <b>extends</b> the
 *       buff rather than starting a second one.</li>
 *   <li><b>Strength.</b> The modifier is declared under one fixed id
 *       ({@link AttackBuffEffect#MODIFIER_ID}), and vanilla's {@code addAttributeModifiers} removes a
 *       modifier with that id before adding its replacement. So even if a second application did rebuild
 *       the attribute, it would <b>replace</b> the previous fraction — never add to it. Ten casts leave
 *       {@code +50%}, not {@code +500%}.</li>
 * </ol>
 *
 * <p>A <b>stronger</b> buff applied while a weaker one runs is handled by vanilla too: it takes over and
 * hides the weaker instance, which returns if it still has time left when the stronger one ends. That is
 * vanilla's rule for every effect in the game, and a different one here would make this buff behave
 * unlike everything else in a player's HUD.
 */
@ApiStatus.Internal
public final class AttackBuffRules {

    /** One amplifier step in multiplier units: {@code 0.01}. */
    public static final double RESOLUTION = 0.01;

    /** Vanilla's own ceiling for an effect amplifier. */
    public static final int MAX_AMPLIFIER = 255;

    /** The weakest multiplier that is a buff at all: {@code 1.0} means "no change". */
    public static final double MIN_MULTIPLIER = 1.0;

    /** Ticks in a second, for the datapack's {@code seconds}. */
    public static final int TICKS_PER_SECOND = 20;

    private AttackBuffRules() {
    }

    /**
     * {@return the amplifier that expresses {@code multiplier}, clamped into
     * {@code [0, MAX_AMPLIFIER]}}
     *
     * <p>Never throws: this runs on the effect path, where a hostile number in a pack must become a
     * logged no-op rather than an exception. A {@code NaN} or a multiplier at or below
     * {@link #MIN_MULTIPLIER} answers {@code 0} ("+0%"), and the executor refuses such a hook before it
     * reaches here.
     *
     * @param multiplier the factor the pack asked for, {@code 1.5} = +50%
     */
    public static int amplifierFor(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier <= MIN_MULTIPLIER) {
            return 0;
        }
        double steps = Math.round((multiplier - MIN_MULTIPLIER) / RESOLUTION);
        return (int) Math.max(0.0, Math.min(MAX_AMPLIFIER, steps));
    }

    /**
     * {@return the multiplier an amplifier expresses — the inverse of {@link #amplifierFor}}
     *
     * @param amplifier the instance's amplifier, {@code 0} = +0%
     */
    public static double multiplierFor(int amplifier) {
        int clamped = Math.max(0, Math.min(MAX_AMPLIFIER, amplifier));
        return MIN_MULTIPLIER + clamped * RESOLUTION;
    }

    /** {@return whether {@code multiplier} survives the round trip through the amplifier unchanged} */
    public static boolean isRepresentable(double multiplier) {
        if (!Double.isFinite(multiplier) || multiplier < MIN_MULTIPLIER) {
            return false;
        }
        return Math.abs(multiplierFor(amplifierFor(multiplier)) - multiplier) < RESOLUTION / 2.0;
    }

    /**
     * {@return the duration in ticks for a {@code seconds} value, or {@code 0} when there is none}
     *
     * <p>Zero is the author asking for nothing, and is <b>not</b> floored to one tick: unlike a debuff
     * that the diminishing multiplier might shrink to nothing (spec §5.2 forbids the multiplier
     * switching an effect <em>off</em>), a declared {@code 0} seconds is a deliberate value and the
     * executor skips it with a line. Negative, infinite and {@code NaN} seconds are likewise {@code 0}.
     *
     * @param seconds how long the buff lasts, as the datapack writes it
     */
    public static int ticksFor(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return 0;
        }
        double ticks = seconds * TICKS_PER_SECOND;
        if (ticks >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.round(ticks));
    }
}
