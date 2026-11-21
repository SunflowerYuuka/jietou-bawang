package com.astrum.pcrd.impl.effect;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「无视 50% 物理防御力」</b>: one hit behaves as though the target had a fraction less armour — no attribute
 * is written, and the target keeps every point of armour it has.
 *
 * <h2>Why this is an arithmetic on the amount, and not a modifier on the victim</h2>
 * Vanilla's armour is one non-linear curve ({@code LivingEntity.getDamageAfterArmorAbsorb} →
 * {@code CombatRules.getDamageAfterAbsorb}):
 *
 * <pre>
 *   afterArmour(D, armor, toughness) = D × (1 − clamp(armor − D / (2 + toughness / 4), armor × 0.2, 20) / 25)
 * </pre>
 *
 * <p>The reduction depends on the <b>incoming amount as well as on the armour</b>, so "50% less armour" is not
 * "50% more damage", and it is not a constant factor either: it is a different point on the same curve. Two
 * consequences, both of which decide this class's shape:
 *
 * <ul>
 *   <li><b>It cannot be a negative {@code ARMOR} modifier.</b> A modifier would be a change to the
 *       <em>victim</em> — every hit against it, from every source, for the duration — and the clause is about
 *       <b>this one hit</b>. {@code DefenceDownEffect} is the modifier shape, and it is a debuff with a clock;
 *       this is a property of a strike.</li>
 *   <li><b>It must be solved backwards through the curve.</b> What the clause means, stated exactly, is: the
 *       health lost is what the same hit would have taken off a body carrying {@code armor × (1 − fraction)}.
 *       So the amount handed to {@code hurt()} is the one vanilla's own armour step turns into that number —
 *       computed with vanilla's own formula at both armour values, and inverted by bisection.</li>
 * </ul>
 *
 * <p><b>This is exact, not an approximation, and it says why.</b> Both ends use the game's own implementation —
 * the lambda {@link #amountFor(LivingEntity, DamageSource, float, double)} supplies is
 * {@link CombatRules#getDamageAfterAbsorb} — and the amount returned here is fed back into that same method
 * moments later by {@code hurt()}, including the per-weapon enchantment factor (which depends on the weapon and
 * the source, not on the armour value, so it is the same on both sides of the comparison). What <em>is</em>
 * approximate is the clause itself: {@code fraction 0.5} is the wiki's 「50%」 of a defence <b>value</b>, and a
 * defence value is not a damage percentage — 50% less armour is worth however much a particular target's armour
 * was worth.
 *
 * <h2>Two layers: the rule, and the curve it is applied to</h2>
 * {@link #plan(ArmourCurve, float, float, float, double)} holds the whole rule and takes the curve as a argument,
 * so "the amount that lands the half-armour number" is assertable with a stand-in curve and no world — this
 * project's unit tests have no {@code LivingEntity}, and a rule that could only be checked by starting a server
 * would not be checked. {@link #amountFor(LivingEntity, DamageSource, float, double)} is the one-line game
 * binding: read the two armour numbers, pass vanilla's curve, hand back the amount.
 *
 * <h2>What it does when armour does not apply at all</h2>
 * Two answers, both honest and both stated rather than silently "working":
 *
 * <ul>
 *   <li>a damage type in {@link DamageTypeTags#BYPASSES_ARMOR} never reaches the armour step, so there is
 *       nothing to pierce and the amount is returned unchanged (no bisection, no log line). <b>This is the case
 *       for this mod's own {@code astrum:hook_damage}</b>, which ships in that tag: a hook's extra damage already
 *       ignores armour in full, so a clause asking for half of it is asking for less than the type already does.
 *       The ultimate's own hits are filed under {@code astrum:ub_damage}, which is <em>not</em> in that tag, so
 *       armour applies to them and the channel is real there.</li>
 *   <li>a victim with no {@code ARMOR} attribute at all (an armour stand, a client-side copy, a test double):
 *       the amount is returned unchanged, the same "this build cannot say" reading {@code DamageReduction}
 *       gives.</li>
 * </ul>
 *
 * <h2>What it deliberately does not do</h2>
 * It does not reach {@code ARMOR_TOUGHNESS} or vanilla's {@code resistance}, and it does not touch
 * {@code astrum:defence_down}. 「50 物理防御力」 is an armour <b>value</b>, so the armour value is what is reduced
 * here; everything else in the damage sequence sees the numbers it always saw.
 */
@ApiStatus.Internal
public final class ArmourPierce {

    /**
     * How many halvings the inversion takes.
     *
     * <p>Sixty is far more than a {@code float} needs (24 significant bits). The cost is sixty multiplications on
     * one hit, and it buys a result that does not depend on where in the curve the hit falls — which a closed-form
     * inverse would have to re-derive per piece.
     */
    static final int BISECTION_STEPS = 60;

    /** The largest share this channel honours: all of it. A value above it is read as this. */
    public static final double MAX_FRACTION = 1.0;

    /** The steepest reduction vanilla's curve applies, as a fraction: {@code 20/25}, i.e. armour keeps a fifth. */
    private static final float STEEPEST_REDUCTION = 0.2F;

    private ArmourPierce() {
    }

    /**
     * Vanilla's armour step, as a value: what {@code amount} becomes after {@code armour} has absorbed its part.
     *
     * <p>An interface rather than a static call so the arithmetic of {@link #plan} can be asserted with a curve a
     * test owns, and so this class never re-types vanilla's three constants: the real implementation is one method
     * reference in {@link #amountFor(LivingEntity, DamageSource, float, double)}.
     */
    @FunctionalInterface
    public interface ArmourCurve {

        /**
         * @param amount    the pre-armour amount
         * @param armour    the armour value to absorb with
         * @param toughness the armour toughness
         * @return what is left after armour
         */
        float afterArmour(float amount, float armour, float toughness);
    }

    /**
     * {@return the amount to hand {@code hurt()} so that the armour step lands as if the target had
     * {@code fraction} less armour}
     *
     * @param victim   the body about to be hurt; {@code null} or armour-less answers {@code amount}
     * @param source   the damage source, for the bypass check and for the enchantment factor inside the curve
     * @param amount   the pre-armour amount the clause authored
     * @param fraction how much of the target's armour to ignore, {@code 0.5} for 「50%」
     */
    public static float amountFor(@Nullable LivingEntity victim, @Nullable DamageSource source, float amount,
            double fraction) {
        if (victim == null || !(amount > 0.0F) || !Float.isFinite(amount)) {
            return amount;
        }
        if (!(sanitise(fraction) > 0.0)) {
            return amount;
        }
        // Armour is not applied at all to a type in this tag, so there is nothing to ignore. Stated rather than
        // treated as "already handled", because a reader has to be able to see that the astrum:hook_damage case
        // is a deliberate no-op rather than a broken write.
        if (source != null && source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            return amount;
        }
        float armour = victim.getArmorValue();
        if (!(armour > 0.0F)) {
            return amount;
        }
        float toughness = (float) victim.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        if (!Float.isFinite(toughness)) {
            toughness = 0.0F;
        }
        return plan((preArmour, withArmour, withToughness) -> CombatRules.getDamageAfterAbsorb(victim,
                        preArmour, source, Math.max(0.0F, withArmour), Math.max(0.0F, withToughness)),
                amount, armour, toughness, fraction);
    }

    /**
     * {@return the pre-armour amount that lands the {@code fraction}-pierced figure, given the game's own curve}
     *
     * <h2>The rule, in one paragraph</h2>
     * {@code target} is what the hit would take off a body carrying {@code armour × (1 − fraction)}; the answer is
     * the smallest amount whose post-armour value reaches {@code target}. One path serves every share, including
     * "ignore all of it" — at {@code fraction 1} the reduced armour is 0 and the same inversion is asked for the
     * amount that lands an unarmoured figure — so 50% and 100% cannot drift apart into two arithmetics.
     *
     * <p>Bisection rather than a closed form: the curve has three pieces (the clamp's two ends and its middle), and
     * which piece a hit is on depends on the hit's own size, so a formula would have to solve for the piece first —
     * one more branch to get wrong for no gain. The curve is non-decreasing in the amount, so the bracket below
     * holds the answer.
     *
     * <p>Never throws and never invents a number: a hostile share is no pierce, a curve that cannot land the target
     * at all answers the authored amount, and a zero or non-finite amount comes straight back. This is reached from
     * a damage sequence, where an exception would cost the hit.
     *
     * @param curve    the game's armour step
     * @param amount   the amount the clause authored
     * @param armour   the target's armour value
     * @param toughness the target's armour toughness
     * @param fraction how much of {@code armour} to ignore
     */
    static float plan(ArmourCurve curve, float amount, float armour, float toughness, double fraction) {
        if (curve == null || !(amount > 0.0F) || !Float.isFinite(amount) || !(armour > 0.0F)) {
            return amount;
        }
        double share = sanitise(fraction);
        if (!(share > 0.0)) {
            return amount;
        }
        float target = curve.afterArmour(amount, armour * (float) (1.0 - share), toughness);
        if (!(target > 0.0F) || !Float.isFinite(target)) {
            return amount;
        }
        // The curve keeps at least a fifth of the amount at its steepest, so five times the target is always
        // enough — unless a weapon/enchant combination clamps the armour ratio to 1 (a slope of 0), and then no
        // amount lands the target. Bracketing is doubled a bounded number of times, and the authored amount is
        // returned if even that is not enough: a smaller hit than intended is better than an absurd one.
        float high = Math.max(target, 1.0F) / STEEPEST_REDUCTION;
        for (int doublings = 0; doublings < 16 && curve.afterArmour(high, armour, toughness) < target;
                doublings++) {
            high *= 2.0F;
        }
        if (curve.afterArmour(high, armour, toughness) < target) {
            return amount;
        }
        float low = 0.0F;
        for (int step = 0; step < BISECTION_STEPS; step++) {
            float mid = (low + high) * 0.5F;
            if (!(mid > low) || !(mid < high)) {
                break;   // float resolution: no further halving changes the answer
            }
            if (curve.afterArmour(mid, armour, toughness) < target) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return Float.isFinite(high) ? high : amount;
    }

    /**
     * {@return {@code fraction} as a usable share of the target's armour}
     *
     * <p>Total and never throwing, for the reason every number on this path is: it is read off a datapack, in a
     * damage sequence, where a {@code NaN} must mean "no pierce" rather than an exception. A negative or
     * non-finite value is no pierce; a value above {@link #MAX_FRACTION} is clamped, because "ignore 150% of the
     * armour" is not a bigger hit — it is the same unarmoured hit.
     */
    public static double sanitise(double fraction) {
        if (!Double.isFinite(fraction)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(MAX_FRACTION, fraction));
    }
}
