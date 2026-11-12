package com.astrum.pcrd.impl.combat;

import org.jetbrains.annotations.ApiStatus;

/**
 * The only arithmetic the eight effect executors are allowed to use on a hook's numbers (spec §5.2).
 *
 * <h2>Why this exists as its own class</h2>
 * §5.2 draws one line and draws it in bold: 递减作用于「量」（伤害、回血、充能速度、持续时间），
 * **绝不作用于「质」**（是否触发、叠层上限、引爆这个行为本身）. An executor that multiplies the wrong
 * field — a stack {@code max}, a chance roll, a "should this run" flag — breaks that line while
 * looking like four lines of ordinary arithmetic. Funnelling every quantity through here means the
 * multiplier has exactly two victims (a {@code double} and a {@code int} duration) and a reviewer can
 * check the whole of §5.2 by reading one file.
 *
 * <h2>The one place it is deliberately <em>not</em> applied</h2>
 * {@code amplifier} is an integer effect level, not a quantity: halving it would halve the debuff's
 * strength in a way an author cannot express, and rounding it would make "level I" and "level II" the
 * same thing for a second slot. It is passed through untouched; the level is data, the duration is the
 * knob.
 */
@ApiStatus.Internal
public final class EffectQuantities {

    private EffectQuantities() {
    }

    /**
     * {@return {@code quantity × multiplier}, with nonsense answered as {@code 0}}
     *
     * <p>No upper clamp: affection hook modifiers (spec §6.2) legitimately scale a hook <em>above</em>
     * its declared value, and capping at the declared number here would silently disable them. The
     * diminishing table itself is already clamped into {@code [0.1, 1.0]} by
     * {@link com.astrum.pcrd.impl.config.Balance#diminishingMultiplier}.
     *
     * <p>Non-positive and non-finite results answer {@code 0.0F} rather than a negative or a
     * {@code NaN}: a negative heal would damage, a negative damage would heal, and a {@code NaN}
     * slips through every {@code amount <= 0} filter because comparisons with it are false.
     *
     * @param quantity   the number declared by the hook
     * @param multiplier this slot's diminishing multiplier
     */
    public static float scaled(double quantity, double multiplier) {
        if (!(quantity > 0.0) || !(multiplier > 0.0)) {
            return 0.0F;
        }
        double product = quantity * multiplier;
        if (!Double.isFinite(product)) {
            return 0.0F;
        }
        return (float) product;
    }

    /**
     * {@return the duration in ticks to apply, scaled and rounded, but never below one tick}
     *
     * <p>The floor is the mechanism half of §5.2. {@code duration: 1} on the fifth same-dimension slot
     * scales to {@code 0.1} ticks, and rounding that to {@code 0} would mean the debuff is never
     * applied at all — i.e. the diminishing multiplier would have switched an effect off, which is
     * exactly what the spec forbids. Declaring {@code 0} still means {@code 0}: that is the author
     * asking for nothing, not the multiplier taking something away.
     *
     * @param declaredTicks duration as written in the datapack
     * @param multiplier    this slot's diminishing multiplier
     */
    public static int durationTicks(int declaredTicks, double multiplier) {
        if (declaredTicks <= 0) {
            return 0;
        }
        if (!(multiplier > 0.0)) {
            return 0;
        }
        double scaled = declaredTicks * multiplier;
        if (!Double.isFinite(scaled) || scaled >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.round(scaled));
    }
}
