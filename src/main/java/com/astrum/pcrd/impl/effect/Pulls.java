package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * 「位移 / 吸引」: the arithmetic and the one position write behind {@link EffectType#PULL}.
 *
 * <h2>The human's requirement, read literally</h2>
 * 「位移参考使用末影珍珠的效果。」 A pearl does not push: it <em>computes a destination</em> and the thrower is
 * <b>placed</b> there. So this class computes a point and calls
 * {@link LivingEntity#teleportTo(double, double, double)} — the very call an ender pearl's landing makes. In
 * 21.1.265 {@code ServerPlayer} overrides that method with
 * {@code connection.teleport(x, y, z, yRot, xRot, RelativeMovement.ROTATION)}, so a pulled <b>player</b> moves
 * through the same client packet {@code /tp} uses, and a pulled mob moves through the same server-side
 * {@code moveTo} the pearl would have used. No velocity is written on this path at all — that is
 * {@link Knockbacks}' job, and the two mechanics must not be confused.
 *
 * <h2>Which reading of 「拉到身前 300」 the data supports</h2>
 * The rung is a <b>distance travelled</b> (see {@code EffectTiers} for the ladder). The competing reading —
 * "the point it ends up at, measured from the caster" — is rejected because a melee hook fires on a target
 * that is already inside melee reach: a "land 3 blocks from me" pull would move it <em>away</em>, and the
 * skill would do nothing visible while claiming to work. The travel reading is the only one under which both
 * 纺希's 6-block ultimate and her melee skill move the body, and a target five blocks away still ends up two
 * blocks in front of the caster, which is exactly what 「身前」 describes.
 *
 * <h2>Walls, and what this cannot do</h2>
 * The path from the victim to the caster is walked in {@value #STEP}-block probes and the <b>last clear
 * point</b> wins, so a body is never placed inside a block. An ender pearl <em>can</em> do that and suffocate
 * its thrower; doing it here would turn a movement-control skill into a griefing tool. The honest limitation
 * is that this is a straight line: a wall <b>between</b> the two bodies stops the pull at the wall instead of
 * routing the victim around a corner. The destination is also chosen from the victim's feet, with the head
 * height checked, so a body is only placed where it physically fits.
 */
@ApiStatus.Internal
public final class Pulls {

    /**
     * How finely the path is probed, in blocks.
     *
     * <p>Five probes a block: fine enough that a full block face cannot be stepped over, coarse enough that a
     * five-block pull is twenty-five block-state reads rather than a hundred.
     */
    public static final double STEP = 0.2;

    /**
     * The gap left between the pulled body and the caster, in blocks.
     *
     * <p>One block: two 0.6-wide boxes cannot overlap, so the victim ends up beside her rather than inside
     * her — which is both the visible reading of 「拉到身前」 and what keeps two entities from being pushed
     * apart by the game on the next tick.
     */
    public static final double MIN_SEPARATION = 1.0;

    private Pulls() {
    }

    /**
     * "Is there something solid at this point?" — the one world question this arithmetic asks.
     *
     * <p>An interface rather than a {@code Level} so the rule can be asserted without a world: the tests
     * plant a wall at a chosen coordinate and check where the body stops. The production implementation is
     * {@link #solidAt(Level, LivingEntity)}.
     */
    @FunctionalInterface
    public interface Obstruction {
        boolean blockedAt(Vec3 point);
    }

    /**
     * {@return the point a body at {@code from} is pulled to, toward {@code toward}}
     *
     * <p>Never throws and never returns {@code null}: hostile input (a {@code null} point, a negative or
     * non-finite reach, a gap smaller than the separation) answers {@code from}, i.e. "no movement", which is
     * the honest answer for a pull that cannot happen.
     *
     * @param from        where the body is now
     * @param toward      where it is being pulled (the caster's position)
     * @param reach       how far it may travel, in blocks — the hook's rung
     * @param separation  the gap to leave between the two bodies, in blocks
     * @param obstruction the world's answer to "is this point solid"; {@code null} means "nothing blocks"
     */
    public static Vec3 destination(Vec3 from, Vec3 toward, double reach, double separation,
            @Nullable Obstruction obstruction) {
        if (from == null || toward == null || !(reach > 0.0) || !Double.isFinite(reach)) {
            return from;
        }
        Vec3 delta = toward.subtract(from);
        double gap = delta.length();
        double keep = Double.isFinite(separation) && separation > 0.0 ? separation : 0.0;
        if (!(gap > keep)) {
            return from;
        }
        double travel = Math.min(reach, gap - keep);
        if (!(travel > 0.0)) {
            return from;
        }
        Vec3 heading = delta.scale(1.0 / gap);
        int probes = Math.max(1, (int) Math.ceil(travel / STEP));
        Vec3 lastClear = from;
        for (int probe = 1; probe <= probes; probe++) {
            Vec3 point = from.add(heading.scale(travel * probe / probes));
            if (obstruction != null && obstruction.blockedAt(point)) {
                break;
            }
            lastClear = point;
        }
        return lastClear;
    }

    /**
     * {@return the obstruction test for {@code body} in its own level}
     *
     * <p>Both the feet and the top of the body's own bounding box are checked, so a pull can neither put a
     * body inside a wall nor leave its head in one — a one-block-high gap is a place a mob fits and a player
     * does not, and the check has to read the body's own height to know the difference.
     *
     * <p>{@code getCollisionShape} rather than {@code isSolid}: a fence, a slab and a wall are all solid
     * obstacles to a teleport, and {@code isSolid} answers false for a fence.
     *
     * @param level the level the body is in
     * @param body  the body being moved
     */
    public static Obstruction solidAt(Level level, LivingEntity body) {
        double head = Math.max(0.0, body.getBbHeight() - 0.1);
        return point -> {
            BlockPos feet = BlockPos.containing(point);
            if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) {
                return true;
            }
            BlockPos top = BlockPos.containing(point.x, point.y + head, point.z);
            return !level.getBlockState(top).getCollisionShape(level, top).isEmpty();
        };
    }

    /**
     * Pulls {@code victim} toward {@code caster} by {@code blocks}, ender-pearl style.
     *
     * <p>Server side only ({@code teleportTo} is a no-op on a client level), and a no-op in the four cases
     * that have no meaning: no victim, no caster, the body pulling itself, and a non-positive reach. The
     * momentum is cleared after the move because a body that is <em>placed</em> somewhere should not arrive
     * carrying the speed it had while it was elsewhere — the pearl's thrower does not keep the pearl's
     * velocity either. Gravity still applies from the next tick, so a body pulled off a ledge falls.
     *
     * @param victim the body being pulled
     * @param caster the body it is pulled toward
     * @param blocks how far it travels, in blocks
     * @return whether the body's position changed
     */
    public static boolean apply(@Nullable LivingEntity victim, @Nullable LivingEntity caster, double blocks) {
        if (victim == null || caster == null || victim == caster
                || !(blocks > 0.0) || !Double.isFinite(blocks)
                || victim.level().isClientSide) {
            return false;
        }
        if (victim.isRemoved() || !victim.isAlive()) {
            return false;
        }
        Vec3 from = victim.position();
        Vec3 destination = destination(from, caster.position(), blocks, MIN_SEPARATION,
                solidAt(victim.level(), victim));
        if (destination.distanceToSqr(from) < 1e-4) {
            Astrum.LOGGER.debug("astrum: pull of {} block(s) on {} moved nothing (already beside {}, or a "
                            + "wall between them)", blocks, victim.getName().getString(),
                    caster.getName().getString());
            return false;
        }
        victim.teleportTo(destination.x, destination.y, destination.z);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.hasImpulse = true;
        Astrum.LOGGER.debug("astrum: pull {} block(s): {} ({}) -> ({}) toward {}", blocks,
                victim.getName().getString(), from, destination, caster.getName().getString());
        return true;
    }
}