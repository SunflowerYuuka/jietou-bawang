package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 「击退」: one call to <b>vanilla's own knockback</b>, at a strength the data chooses — {@link EffectType#KNOCKBACK}.
 *
 * <h2>The human was right, and this class is the proof</h2>
 * 「击退原版不是有吗？原版里，怪被攻击不是会击退吗？直接调用就好了，如果你觉得击退距离不够就改数值。」
 *
 * <p>Read out of 21.1.265's sources rather than assumed:
 * <ul>
 *   <li>{@code LivingEntity#hurt} ends every damage sequence with {@code this.knockback(0.4F, dx, dz)} — so a
 *       mob that is hit <b>already</b> flies back, which is the fact he was pointing at;</li>
 *   <li>{@code Player#attack} adds a second, smaller one ({@code strength × 0.5}) on top for the swing itself,
 *       plus the Knockback enchantment;</li>
 *   <li>{@code LivingEntity#knockback(double strength, double x, double z)} normalizes {@code (x, z)},
 *       multiplies by the strength, halves the body's existing horizontal delta, adds a vertical component and
 *       fires NeoForge's {@code LivingKnockBackEvent} so other mods can veto it.</li>
 * </ul>
 *
 * <p>So the mechanic is <b>more of the same call</b>. This class writes no velocity of its own: the one
 * statement that moves the body is {@code victim.knockback(...)}, and a test asserts that the class mentions
 * no velocity-writing API at all. What the data chooses is the <b>strength</b>, which is the knob the human
 * said to turn if the distance feels short: one rung is 0.4 (vanilla's own number, see {@code EffectTiers}),
 * so tier 2 is twice a plain hit and tier 5 is a launch.
 *
 * <h2>Why the felt distance is not the strength</h2>
 * {@code knockback} halves the horizontal delta a body already has, so this call landing right after
 * {@code hurt}'s own knockback composes with it rather than replacing it, and
 * {@code KNOCKBACK_RESISTANCE} multiplies the result down. A tier-2 push therefore travels a little more
 * than twice as far as a sword hit, not exactly twice. Which rung "feels" right is a judgement for somebody
 * in the game; the ladder exists so the number can be changed in data instead of in code.
 *
 * <h2>The vertical part, and 绫音's 「击飞高度」</h2>
 * {@code knockback} gives an on-ground body {@code min(0.4, y/2 + strength)} of upward motion, so a stronger
 * push <b>does</b> pop the body up a little — but that vertical component is <b>capped at 0.4</b> whatever the
 * strength is, and it is scaled down by {@code KNOCKBACK_RESISTANCE} along with everything else. A declared
 * launch height therefore cannot come out of {@code knockback} at all: at strength 2.0 (tier 5) the body still
 * leaves the ground with 0.4 and still reaches the same ~0.75 blocks, and a resistant body reaches less.
 *
 * <p>So the height is a <b>second, explicit channel</b> ({@link #apply(LivingEntity, LivingEntity, double, double)}):
 * after vanilla has pushed the body, a declared height is turned into the initial upward velocity that reaches
 * it and written over a <em>lower</em> vertical component. The arithmetic is the game's own integrator, not a
 * guess — see {@link #apexOf} and {@link #launchVelocityFor} for the two constants and for why the height is
 * inverted rather than plugged into {@code v² = 2gh}.
 *
 * <p><b>The one thing that is still vanilla's:</b> the horizontal push. {@code setDeltaMovement} here touches
 * the <b>y</b> component only, so {@code KNOCKBACK_RESISTANCE} still scales the kick, NeoForge's
 * {@code LivingKnockBackEvent} still sees it, and the strength ladder still means what it says.
 */
@ApiStatus.Internal
public final class Knockbacks {

    /**
     * The strength vanilla's own damage sequence uses, in blocks/tick of initial horizontal speed.
     *
     * <p>Named because it is the unit of the ladder rather than because this class needs it: tier N is
     * {@code 0.4 × N} in {@code EffectTiers}, and tier 1 must be recognisable as "exactly what a hit already
     * does". Read from {@code LivingEntity#hurt}'s {@code this.knockback(0.4F, d0, d1)}.
     */
    public static final double VANILLA_STRENGTH = 0.4;

    /**
     * Downward acceleration per tick, in blocks a tick squared — {@code LivingEntity#travel}'s own constant.
     *
     * <p>Named because the launch's arithmetic is <b>the game's</b> integrator and not a textbook parabola: the
     * same 0.08 that makes a jump look right is what every launch height is measured against.
     */
    public static final double LAUNCH_GRAVITY = 0.08;

    /**
     * Vertical drag applied every tick — {@code LivingEntity#travel}'s own {@code 0.98} on the fall path.
     *
     * <p>This is why {@code v² = 2gh} is the wrong inversion: with drag, a body launched at 0.4 blocks a tick
     * peaks at <b>0.75</b> blocks, not 1.0, so a formula that ignored the 0.98 would answer a velocity that
     * overshoots the declared height by a third.
     */
    public static final double LAUNCH_DRAG = 0.98;

    /** How many ticks {@link #apexOf} integrates before it declares the body to be on its way down. */
    private static final int LAUNCH_TICKS = 400;

    /**
     * The top of {@link #launchVelocityFor}'s search bracket, in blocks a tick.
     *
     * <p>Three blocks a tick peaks at about 20 blocks ({@code apexOf(3.0)}), which is already far past any
     * height a knockback in this pack declares — 绫音's is one block, converted from 800 battlefield units. A
     * bracket rather than an unbounded search so a hostile number in a datapack answers a number instead of
     * spinning.
     */
    private static final double MAX_LAUNCH_VELOCITY = 3.0;

    private Knockbacks() {
    }

    /**
     * {@return the unit horizontal direction from {@code source} to {@code victim}}
     *
     * <p>Zero when the two positions coincide: {@code LivingEntity#knockback} treats a near-zero direction by
     * drawing a random one, which is the right behaviour for "something hit me from inside my own square" and
     * is vanilla's own answer. Normalised here so the pure rule is legible and testable; the vanilla call
     * normalises again and is unharmed by it.
     *
     * @param victim where the body is
     * @param source where the push comes from
     */
    public static Vec3 direction(Vec3 victim, Vec3 source) {
        if (victim == null || source == null) {
            return Vec3.ZERO;
        }
        Vec3 flat = new Vec3(victim.x - source.x, 0.0, victim.z - source.z);
        return flat.lengthSqr() < 1e-6 ? Vec3.ZERO : flat.normalize();
    }

    /**
     * Knocks {@code victim} away from {@code source} with vanilla's knockback.
     *
     * <p>Server side only, and a no-op for the cases that have no meaning: no victim, no source, a
     * non-positive or non-finite strength, a removed body. A non-finite strength is refused rather than passed
     * on, because {@code knockback} would write an infinite delta into the tracker and the client would be
     * sent a position it cannot represent.
     *
     * @param victim   the body being pushed
     * @param source   the body pushing it
     * @param strength vanilla knockback strength; {@code 0.4} is one plain hit
     * @return whether a push was applied
     */
    public static boolean apply(@Nullable LivingEntity victim, @Nullable LivingEntity source,
            double strength) {
        return apply(victim, source, strength, 0.0);
    }

    /**
     * Knocks {@code victim} away from {@code source}, and lifts it to a declared height.
     *
     * <h2>Why the launch is applied after, and as a floor</h2>
     * Vanilla's {@code knockback} writes the body's whole delta in one statement, so anything written before it
     * would be erased. The launch is therefore a second statement, and it takes the <b>larger</b> of the two
     * vertical components rather than adding the two: adding would make the height the sum of the declared
     * launch and vanilla's incidental 0.4, i.e. half again as high as the data asks. Taking the maximum is the
     * reading that keeps both statements true — vanilla's pop still happens for a body that declares no height,
     * a declared height that is <em>lower</em> than vanilla's never shortens the pop, and a declared height that
     * is higher is what the body gets.
     *
     * <p>Only the y component is written, so the horizontal kick is exactly vanilla's: its resistance rule, its
     * event, its strength. {@code hasImpulse} is set afterwards so the client accepts the server's delta rather
     * than smoothing it away — vanilla's own call sets it for the same reason.
     *
     * @param victim      the body being pushed
     * @param source      the body pushing it
     * @param strength    vanilla knockback strength; {@code 0.4} is one plain hit
     * @param launchHeight how high the body should rise, in blocks, already converted from the source's units;
     *                     {@code 0} or a non-finite value means "no launch of its own"
     * @return whether a push was applied
     */
    public static boolean apply(@Nullable LivingEntity victim, @Nullable LivingEntity source,
            double strength, double launchHeight) {
        if (victim == null || source == null || victim == source
                || !(strength > 0.0) || !Double.isFinite(strength)
                || victim.level().isClientSide
                || victim.isRemoved() || !victim.isAlive()) {
            return false;
        }
        Vec3 direction = direction(victim.position(), source.position());
        // The one movement statement in this class: vanilla's call, vanilla's arithmetic, vanilla's event.
        victim.knockback(strength, direction.x, direction.z);
        Astrum.LOGGER.debug("astrum: knockback strength {} on {} away from {}", strength,
                victim.getName().getString(), source.getName().getString());
        if (launchHeight > 0.0 && Double.isFinite(launchHeight)) {
            double upward = launchVelocityFor(launchHeight);
            Vec3 delta = victim.getDeltaMovement();
            if (upward > delta.y) {
                victim.setDeltaMovement(delta.x, upward, delta.z);
                victim.hasImpulse = true;
                Astrum.LOGGER.debug("astrum: launch {} blocks on {} -> upward velocity {} (vanilla gave {})",
                        launchHeight, victim.getName().getString(), upward, delta.y);
            }
        }
        return true;
    }

    /**
     * {@return how high a body launched at {@code velocity} blocks a tick rises, in blocks}
     *
     * <h2>The integrator, stated once</h2>
     * A non-flying body in the air applies {@code (v - 0.08) * 0.98} to its vertical speed every tick
     * ({@code LivingEntity#travel}); the height it reaches is the sum of the positive speeds it passes through.
     * Integrating the recurrence is both shorter and more honest than a closed form, because the constant 0.98
     * is not a decoration: it is what makes a 0.4 launch peak at 0.75 blocks rather than a full block.
     *
     * @param velocity the initial upward speed, in blocks a tick
     */
    public static double apexOf(double velocity) {
        if (!Double.isFinite(velocity) || velocity <= 0.0) {
            return 0.0;
        }
        double speed = velocity;
        double height = 0.0;
        for (int tick = 0; tick < LAUNCH_TICKS; tick++) {
            speed = (speed - LAUNCH_GRAVITY) * LAUNCH_DRAG;
            if (speed <= 0.0) {
                break;
            }
            height += speed;
        }
        return height;
    }

    /**
     * {@return the initial upward velocity that just reaches {@code blocks}}
     *
     * <h2>Why an inversion and not {@code sqrt(2gh)}</h2>
     * {@code v² = 2gh} describes a parabola with no drag; this game has drag, so it answers too little
     * (it says 0.397 for a 0.985-block launch, which actually peaks at 0.74 — a quarter short). The height is
     * monotone in the velocity, so the answer is found by bisection on {@link #apexOf} instead: fifty halvings
     * of a three-block-a-tick bracket, which is far below float precision and costs nothing on a damage path.
     *
     * <p>Answers {@code 0} for a height that is not positive: "no launch" rather than a launch of nothing.
     *
     * @param blocks the apex height wanted, in blocks
     */
    public static double launchVelocityFor(double blocks) {
        if (!Double.isFinite(blocks) || blocks <= 0.0) {
            return 0.0;
        }
        double low = 0.0;
        double high = MAX_LAUNCH_VELOCITY;
        // Nothing above the bracket can be expressed: a body launched faster than the game's own terminal
        // speeds is already out of the range a skill's payload can mean, so the top of the bracket is the
        // answer rather than a search that does not converge.
        if (apexOf(high) < blocks) {
            return high;
        }
        for (int step = 0; step < 50; step++) {
            double mid = (low + high) / 2.0;
            if (apexOf(mid) < blocks) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return (low + high) / 2.0;
    }
}