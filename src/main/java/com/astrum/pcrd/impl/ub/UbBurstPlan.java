package com.astrum.pcrd.impl.ub;

import org.jetbrains.annotations.ApiStatus;

/**
 * The UB burst's geometry and count, as pure arithmetic.
 *
 * <h2>What is decided here, and why it is a class</h2>
 * A cast spawns a <b>ring of sparks</b> around the caster plus a <b>few rising motes</b>; the server
 * turns each point into one {@code ServerLevel.sendParticles} call. How many points, on what
 * radius, with what vertical jitter and in what order is the difference between "an ultimate" and
 * "a handful of dots", and it is also the only part of a particle effect that can be asserted
 * without eyes. So the plan is a pure function of {@code (enhanced, index, count)} and
 * {@code UbBurstPlanTest} pins its shape.
 *
 * <h2>Why golden-angle points instead of random ones</h2>
 * The ring has to look dense on every cast without clumping. Random angles inevitably leave a gap
 * on some casts and a cluster on others (and a dense-but-random ring looks like a bug when the gap
 * is right in front of the player). The golden angle spreads any prefix of the sequence evenly, so
 * 24 points already cover the circle, and the per-index jitter stays small enough that the ring
 * still reads as a ring. {@code 2.399963...} is {@code π(3-√5)}, the usual sunflower constant.
 *
 * <h2>Why the points are offsets, not absolute coordinates</h2>
 * The server adds the caster's position and the caster's eye/chest height (see
 * {@link UbCastParticles}); this class never touches a level, a player or a client type, which is
 * what keeps it unit-testable and keeps the client-side provider free to reuse the same constants.
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

    /** Mean spark-ring radius in blocks, measured from the caster's centre. */
    public static final double SPARK_RADIUS = 0.92;

    /** Peak-to-peak radius jitter, so the ring is a band rather than a perfect circle. */
    public static final double SPARK_RADIUS_JITTER = 0.28;

    /** Peak vertical jitter around the caster's chest, in blocks. */
    public static final double SPARK_HEIGHT_JITTER = 0.30;

    /** Radius band the motes start in: close to the caster, not on the spark ring. */
    public static final double MOTE_RADIUS_MIN = 0.16;

    /** Outer radius band for the motes. */
    public static final double MOTE_RADIUS_MAX = 0.58;

    /** Lowest mote start, above the caster's feet. */
    public static final double MOTE_Y_MIN = 0.15;

    /** Highest mote start: around the caster's head for a tall figure. */
    public static final double MOTE_Y_MAX = 1.35;

    /** The sunflower constant; see the class javadoc. */
    public static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    /** Peak-to-peak mote radius jitter. */
    public static final double MOTE_RADIUS_JITTER = 0.12;

    /** Peak-to-peak mote height jitter. */
    public static final double MOTE_HEIGHT_JITTER = 0.18;

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
     * One spark's offset from the caster's chest.
     *
     * <p>The point sits on a jittered ring and its height jitters around the chest. Sparks are the
     * fast outward half of the burst; their speed and lifetime are the client provider's business
     * because those are per-particle random values, but the ring this point lies on is fixed here.
     *
     * @param index zero-based spark index, in {@code [0, count)}
     * @param count {@link #sparkCount(boolean)}
     */
    public static Point sparkPoint(int index, int count) {
        int n = Math.max(1, count);
        int i = Math.floorMod(index, n);
        double angle = i * GOLDEN_ANGLE;
        double radius = SPARK_RADIUS + (hash(i, 0x9E37L) - 0.5) * SPARK_RADIUS_JITTER;
        double y = (hash(i, 0x85EBL) - 0.5) * 2.0 * SPARK_HEIGHT_JITTER;
        return new Point(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }

    /**
     * One mote's offset from the caster's feet.
     *
     * <p>The motes climb a spiral: their height rises with the index and their radius follows, so
     * the few of them read as "embers lifting off the caster" rather than as a second ring.
     *
     * @param index zero-based mote index, in {@code [0, count)}
     * @param count {@link #moteCount(boolean)}
     */
    public static Point motePoint(int index, int count) {
        int n = Math.max(1, count);
        int i = Math.floorMod(index, n);
        double progress = (i + 0.5) / n;
        double angle = i * GOLDEN_ANGLE + 0.7;
        double radius = MOTE_RADIUS_MIN + (MOTE_RADIUS_MAX - MOTE_RADIUS_MIN) * progress
                + (hash(i, 0xC2B2L) - 0.5) * MOTE_RADIUS_JITTER;
        double y = MOTE_Y_MIN + (MOTE_Y_MAX - MOTE_Y_MIN) * progress
                + (hash(i, 0x27D4L) - 0.5) * MOTE_HEIGHT_JITTER;
        return new Point(Math.cos(angle) * radius, Math.max(0.0, y), Math.sin(angle) * radius);
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
     * A point relative to the caster.
     *
     * @param x horizontal offset, blocks
     * @param y vertical offset, blocks
     * @param z horizontal offset, blocks
     */
    public record Point(double x, double y, double z) {
    }
}