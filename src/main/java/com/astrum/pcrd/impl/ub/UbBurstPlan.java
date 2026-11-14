package com.astrum.pcrd.impl.ub;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * The UB burst's geometry and count, as pure arithmetic.
 *
 * <h2>What is decided here, and why it is a class</h2>
 * A cast spawns a <b>ring of sparks</b> plus a <b>few rising motes</b> <em>on each victim it hit</em>;
 * the server turns each point into one {@code ServerLevel.sendParticles} call. How many points, on
 * what radius, with what vertical jitter, in what order, and <b>which way the spray travels</b> is the
 * difference between "a blade landed here" and "a handful of dots", and it is also the only part of a
 * particle effect that can be asserted without eyes. So the plan is a pure function of
 * {@code (enhanced, index, count)} and of the victim's spot, and {@code UbBurstPlanTest} /
 * {@code UbSlashBurstTest} pin its shape.
 *
 * <h2>Why the points are offsets, not absolute coordinates</h2>
 * The server adds a victim's position and chest/feet height (see {@link #burstsFor}); this class never
 * touches a level, an entity or a client type, which is what keeps it unit-testable and keeps the
 * client-side provider free to reuse the same constants.
 *
 * <h2>The direction: the blow comes from the caster</h2>
 * A slash has a direction — it lands on the victim <em>from somewhere</em> — so every point of a
 * burst also carries the way it should travel: outward from the ring's centre, biased along
 * caster → victim. {@link #sparkVelocity} and {@link #moteVelocity} are unit vectors, because the
 * server can only order a particle to be born with a velocity, while its speed, size and lifetime
 * stay per-particle client values.
 *
 * <h2>Why golden-angle points instead of random ones</h2>
 * The ring has to look dense on every cast without clumping. Random angles inevitably leave a gap
 * on some casts and a cluster on others (and a dense-but-random ring looks like a bug when the gap
 * is right in front of the player). The golden angle spreads any prefix of the sequence evenly, so
 * 24 points already cover the circle, and the per-index jitter stays small enough that the ring
 * still reads as a ring. {@code 2.399963...} is {@code π(3-√5)}, the usual sunflower constant.
 */
@ApiStatus.Internal
public final class UbBurstPlan {

    /** Sparks in a base cast. Dense enough to read as a ring, short enough for one tick's packets. */
    public static final int BASE_SPARKS = 24;

    /** Sparks in a tier-3 cast: the enhanced ultimate is visibly bigger, not merely recoloured. */
    public static final int ENHANCED_SPARKS = 36;

    /** Rising motes in a base cast. Deliberately far fewer than sparks: "a few", not a second burst. */
    public static final int BASE_MOTES = 7;

    /** Rising motes in a tier-3 cast. */
    public static final int ENHANCED_MOTES = 11;

    /**
     * Mean spark-ring radius in blocks, measured from the <b>victim's</b> centre.
     *
     * <p>The ring belongs where the blade lands, so this is a radius around the thing that was hit —
     * see {@link UbBurstTargets}, which is what decides that the centre is a victim and never the
     * caster.
     */
    public static final double SPARK_RADIUS = 0.92;

    /** Peak-to-peak radius jitter, so the ring is a band rather than a perfect circle. */
    public static final double SPARK_RADIUS_JITTER = 0.28;

    /** Peak vertical jitter around the victim's chest, in blocks. */
    public static final double SPARK_HEIGHT_JITTER = 0.30;

    /** Radius band the motes start in: close to the victim, not on the spark ring. */
    public static final double MOTE_RADIUS_MIN = 0.16;

    /** Outer radius band for the motes. */
    public static final double MOTE_RADIUS_MAX = 0.58;

    /** Lowest mote start, above the victim's feet. */
    public static final double MOTE_Y_MIN = 0.15;

    /** Highest mote start: around the victim's head for a tall figure. */
    public static final double MOTE_Y_MAX = 1.35;

    /** The sunflower constant; see the class javadoc. */
    public static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    /** Peak-to-peak mote radius jitter. */
    public static final double MOTE_RADIUS_JITTER = 0.12;

    /** Peak-to-peak mote height jitter. */
    public static final double MOTE_HEIGHT_JITTER = 0.18;

    /**
     * How hard the incoming blow pulls the spray away from a pure ring, as a weight on the unit
     * caster→victim vector.
     *
     * <p>{@code 1.0} would make every spark leave along the blow (a cone, not a slash); {@code 0.0}
     * would be a ring with no direction at all. At {@code 0.55} the ring still closes in every
     * direction while the far half carries visibly further — which is what "the blade went through
     * here" looks like from the side, and it is the same number the ring's own jitter is scaled by,
     * so the burst keeps one look at base and enhanced counts.
     */
    public static final double INCOMING_PULL = 0.55;

    /** Blocks per tick a spark leaves the ring at, before the client's per-particle variance. */
    public static final double SPARK_SPEED = 0.26;

    /** Upward share of a spark's velocity, so the spray arcs instead of sliding along the ground. */
    public static final double SPARK_LIFT = 0.12;

    /** Blocks per tick a mote drifts along the blow, on top of its own rise. */
    public static final double MOTE_DRIFT = 0.05;

    /** Blocks per tick a mote rises. The one component a mote keeps whatever the blow's direction. */
    public static final double MOTE_RISE = 0.030;

    private UbBurstPlan() {
    }

    /**
     * {@return how many sparks one cast spawns}
     *
     * @param enhanced whether the tier-3 ultimate fired
     */
    public static int sparkCount(boolean enhanced) {
        return enhanced ? ENHANCED_SPARKS : BASE_SPARKS;
    }

    /**
     * {@return how many motes one cast spawns}
     *
     * @param enhanced whether the tier-3 ultimate fired
     */
    public static int moteCount(boolean enhanced) {
        return enhanced ? ENHANCED_MOTES : BASE_MOTES;
    }

    /**
     * One spark's offset from the <b>victim's</b> chest.
     *
     * <p>The point sits on a jittered ring and its height jitters around the chest. Sparks are the
     * fast outward half of the burst; their speed and lifetime are the client provider's business
     * because those are per-particle random values, but the ring this point lies on is fixed here.
     *
     * <p>The offset is rotated so that the ring's {@code +x} axis points along the incoming blow:
     * two victims hit from different sides must not both get a ring aligned to world north, or the
     * burst reads as a decoration pasted on the world rather than as something that landed.
     *
     * @param index    zero-based spark index, in {@code [0, count)}
     * @param count    {@link #sparkCount(boolean)}
     * @param incoming the way the blow travelled, {@link #incomingDirection}
     */
    public static Point sparkPoint(int index, int count, Direction incoming) {
        Direction facing = incoming == null ? Direction.NONE : incoming;
        int n = Math.max(1, count);
        int i = Math.floorMod(index, n);
        double angle = i * GOLDEN_ANGLE;
        double radius = SPARK_RADIUS + (hash(i, 0x9E37L) - 0.5) * SPARK_RADIUS_JITTER;
        double y = (hash(i, 0x85EBL) - 0.5) * 2.0 * SPARK_HEIGHT_JITTER;
        double ringX = Math.cos(angle) * radius;
        double ringZ = Math.sin(angle) * radius;
        return new Point(facing.rotateX(ringX, ringZ), y, facing.rotateZ(ringX, ringZ));
    }

    /**
     * {@return one spark's offset from the victim's chest and the unit way it travels}
     *
     * <p>The velocity is the ring's own outward normal, pulled towards the incoming blow by
     * {@link #INCOMING_PULL}: a spark on the far side of the victim therefore carries onward through
     * the body, and one on the near side is slowed, which is what a ring of slash marks looks like
     * when the blow has a direction. It is a <b>unit</b> vector — the server sends it, the client
     * scales it by its own per-particle speed so a burst is never uniform.
     *
     * @param index    zero-based spark index, in {@code [0, count)}
     * @param count    {@link #sparkCount(boolean)}
     * @param incoming the way the blow travelled
     */
    public static Spark spark(int index, int count, Direction incoming) {
        Direction facing = incoming == null ? Direction.NONE : incoming;
        Point point = sparkPoint(index, count, facing);
        double flat = Math.hypot(point.x(), point.z());
        double outwardX = flat > 1.0E-6 ? point.x() / flat : facing.x();
        double outwardZ = flat > 1.0E-6 ? point.z() / flat : facing.z();
        double vx = outwardX * (1.0 - INCOMING_PULL) + facing.x() * INCOMING_PULL;
        double vz = outwardZ * (1.0 - INCOMING_PULL) + facing.z() * INCOMING_PULL;
        return new Spark(point, Vector.unit(vx, SPARK_LIFT, vz).scaled(SPARK_SPEED));
    }

    /**
     * One mote's offset from the <b>victim's</b> feet.
     *
     * <p>The motes climb a spiral: their height rises with the index and their radius follows, so
     * the few of them read as "embers lifting off the thing that was hit" rather than as a second
     * ring. The spiral is aligned to the incoming blow for the same reason the ring is.
     *
     * @param index    zero-based mote index, in {@code [0, count)}
     * @param count    {@link #moteCount(boolean)}
     * @param incoming the way the blow travelled
     */
    public static Point motePoint(int index, int count, Direction incoming) {
        Direction facing = incoming == null ? Direction.NONE : incoming;
        int n = Math.max(1, count);
        int i = Math.floorMod(index, n);
        double progress = (i + 0.5) / n;
        double angle = i * GOLDEN_ANGLE + 0.7;
        double radius = MOTE_RADIUS_MIN + (MOTE_RADIUS_MAX - MOTE_RADIUS_MIN) * progress
                + (hash(i, 0xC2B2L) - 0.5) * MOTE_RADIUS_JITTER;
        double y = MOTE_Y_MIN + (MOTE_Y_MAX - MOTE_Y_MIN) * progress
                + (hash(i, 0x27D4L) - 0.5) * MOTE_HEIGHT_JITTER;
        double ringX = Math.cos(angle) * radius;
        double ringZ = Math.sin(angle) * radius;
        return new Point(facing.rotateX(ringX, ringZ), Math.max(0.0, y),
                facing.rotateZ(ringX, ringZ));
    }

    /**
     * {@return one mote's offset from the victim's feet and the way it drifts}
     *
     * <p>A mote rises on its own; the only direction it is given is a slow drift along the blow, so
     * the embers lean away from the caster the way smoke does behind a passing blade.
     *
     * @param index    zero-based mote index, in {@code [0, count)}
     * @param count    {@link #moteCount(boolean)}
     * @param incoming the way the blow travelled
     */
    public static Spark mote(int index, int count, Direction incoming) {
        Direction facing = incoming == null ? Direction.NONE : incoming;
        return new Spark(motePoint(index, count, facing),
                new Vector(facing.x() * MOTE_DRIFT, 0.0, facing.z() * MOTE_DRIFT));
    }

    /**
     * {@return the horizontal unit vector from {@code (fromX, fromZ)} towards {@code (toX, toZ)}}
     *
     * <p>Degenerate cases answer {@link Direction#NONE}: a caster standing exactly on the victim (a
     * suicide-range splash, a same-block test) has no direction to give, and a {@code NaN}
     * coordinate must not turn into a {@code NaN} velocity that vanilla then hands to every client.
     * The ring is still drawn — it is simply not rotated — which is the honest outcome for "there was
     * no direction".
     *
     * @param fromX the blow's origin, x
     * @param fromZ the blow's origin, z
     * @param toX   where it landed, x
     * @param toZ   where it landed, z
     */
    public static Direction incomingDirection(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) {
            return Direction.NONE;
        }
        return Direction.of(dx, dz);
    }

    /**
     * {@return a deterministic value in {@code [0, 1)} for {@code (index, salt)}}
     *
     * <p>SplitMix64's finalizer: cheap, deterministic across runs and platforms, and good enough
     * that consecutive indices do not correlate (which a plain sine or multiply would).
     */
    private static double hash(int index, long salt) {
        long z = index * 0x9E3779B97F4A7C15L + salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (z >>> 11) * 0x1.0p-53;
    }

    /**
     * A point relative to the entity it belongs to.
     *
     * @param x horizontal offset, blocks
     * @param y vertical offset, blocks
     * @param z horizontal offset, blocks
     */
    public record Point(double x, double y, double z) {
    }

    /**
     * A velocity in blocks per tick.
     *
     * @param x x component
     * @param y y component
     * @param z z component
     */
    public record Vector(double x, double y, double z) {

        /** {@return this vector scaled by {@code factor}} */
        public Vector scaled(double factor) {
            return new Vector(x * factor, y * factor, z * factor);
        }

        /**
         * {@return the unit vector in this direction, or a strictly upward one when it has none}
         *
         * <p>The fallback is vertical rather than zero: vanilla treats a zero velocity as "let the
         * particle engine randomise it", which is exactly the un-steerable spray this class exists
         * to avoid.
         */
        public static Vector unit(double x, double y, double z) {
            double length = Math.sqrt(x * x + y * y + z * z);
            if (!(length > 1.0E-9) || !Double.isFinite(length)) {
                return new Vector(0.0, 1.0, 0.0);
            }
            return new Vector(x / length, y / length, z / length);
        }
    }

    /**
     * The horizontal way a blow travelled: a unit vector, or {@link #NONE}.
     *
     * <p>Its own type rather than a {@link Vector} because a blow's <em>direction</em> is horizontal
     * by construction (a player and a victim stand on the ground), and because the rotation of the
     * ring is defined only in that plane. Keeping it separate is what makes {@link #rotateX} and
     * {@link #rotateZ} total functions with no branch at every use.
     *
     * <p>The canonical constructor does not normalise — a record's cannot call another constructor
     * to do it — so {@link #of} is the only way a non-{@link #NONE} direction is built from raw
     * numbers, and it is where the "no length means no direction" rule lives.
     *
     * @param x unit x, or {@code 0.0} for {@link #NONE}
     * @param z unit z, or {@code 0.0} for {@link #NONE}
     */
    public record Direction(double x, double z) {

        /** No direction: the ring is drawn unrotated. */
        public static final Direction NONE = new Direction(0.0, 0.0);

        /**
         * {@return the unit direction of {@code (x, z)}, or {@link #NONE} when there is none}
         *
         * <p>Zero length, a {@code NaN} and an infinity all answer {@link #NONE}: a velocity of
         * {@code NaN} would be written to the packet and handed to every client's particle engine.
         *
         * @param x x component, any magnitude
         * @param z z component, any magnitude
         */
        public static Direction of(double x, double z) {
            double length = Math.hypot(x, z);
            if (!(length > 1.0E-9) || !Double.isFinite(length)) {
                return NONE;
            }
            return new Direction(x / length, z / length);
        }

        /** {@return whether this is {@link #NONE}, i.e. the ring is drawn in world axes} */
        public boolean isNone() {
            return x == 0.0 && z == 0.0;
        }

        /**
         * {@return the x of {@code (ringX, ringZ)} rotated so that {@code +x} points along this
         * direction}
         *
         * <p>A rotation, not a reflection: with {@code d = (cos θ, sin θ)} the map is
         * {@code (X cos θ - Z sin θ, X sin θ + Z cos θ)}. Getting the sign of the cross term wrong
         * turns it into a mirror, which is invisible in a test that only checks radii — so
         * {@code UbBurstPlanTest.theRingsOwnForwardAxisFollowsTheBlow} pins the handedness.
         *
         * <p>The ring is unchanged when there is no direction — it looks the same rotated or not —
         * and the two coordinates read the same flag, so they can never disagree about which case
         * they are in.
         */
        double rotateX(double ringX, double ringZ) {
            return isNone() ? ringX : ringX * x - ringZ * z;
        }

        /** {@return the z of {@code (ringX, ringZ)} rotated with {@link #rotateX}} */
        double rotateZ(double ringX, double ringZ) {
            return isNone() ? ringZ : ringX * z + ringZ * x;
        }
    }

    /**
     * One particle of a burst: where it starts, and the unit way it travels.
     *
     * @param origin   offset from the victim's chest (sparks) or feet (motes)
     * @param velocity blocks per tick, already scaled; never zero
     */
    public record Spark(Point origin, Vector velocity) {
    }
}