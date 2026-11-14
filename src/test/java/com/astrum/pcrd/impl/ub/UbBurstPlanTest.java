package com.astrum.pcrd.impl.ub;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UB burst's geometrical plan: how many, how far out, how high, evenly spread, and which way it
 * travels.
 *
 * <h2>Why this is the testable half of a visual</h2>
 * Nobody writing this can see the burst. The two things that can still be proven are its
 * <b>structure</b> — a ring of a stated density, not a random cloud, with a few motes rising from the
 * feet — and its <b>non-degeneracy</b>: no NaNs, no duplicate points, no zero-radius ring, no zero
 * velocity, and the enhanced tier actually bigger. Those are the claims a screenshot cannot state and
 * a code review keeps missing, so they are pinned here.
 *
 * <p>The per-particle random values (size, lifetime, a small share of the speed) deliberately live in
 * the client provider; this test covers only what {@link UbBurstPlan} decides. <b>Where</b> the burst
 * is centred is not decided here at all — see {@code UbBurstTargetsTest}.
 */
class UbBurstPlanTest {

    /** A blow coming from the west (the caster is at -x of the victim). */
    private static final UbBurstPlan.Direction FROM_WEST = UbBurstPlan.Direction.of(1.0, 0.0);

    /** A blow coming from the north (the caster is at -z of the victim). */
    private static final UbBurstPlan.Direction FROM_NORTH = UbBurstPlan.Direction.of(0.0, 1.0);

    // ------------------------------------------------------------------ counts

    @Test
    void theEnhancedTierIsBiggerInBothLayers() {
        assertEquals(24, UbBurstPlan.sparkCount(false));
        assertEquals(36, UbBurstPlan.sparkCount(true));
        assertEquals(7, UbBurstPlan.moteCount(false));
        assertEquals(11, UbBurstPlan.moteCount(true));
        assertTrue(UbBurstPlan.sparkCount(true) > UbBurstPlan.sparkCount(false),
                "a tier-3 ultimate must be visibly denser, not only recoloured");
        assertTrue(UbBurstPlan.moteCount(true) > UbBurstPlan.moteCount(false));
        assertTrue(UbBurstPlan.moteCount(false) < UbBurstPlan.sparkCount(false) / 2,
                "motes are 'a few rising embers', not a second burst");
    }

    // ------------------------------------------------------------------ the spark ring

    @Test
    void everySparkSitsOnTheJitteredRingAtChestHeight() {
        for (UbBurstPlan.Direction facing : new UbBurstPlan.Direction[] {
                UbBurstPlan.Direction.NONE, FROM_WEST, FROM_NORTH}) {
            for (int count : new int[] {UbBurstPlan.BASE_SPARKS, UbBurstPlan.ENHANCED_SPARKS}) {
                for (int i = 0; i < count; i++) {
                    UbBurstPlan.Point p = UbBurstPlan.sparkPoint(i, count, facing);
                    assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y())
                            && Double.isFinite(p.z()), "a point must be a real coordinate: " + p);
                    double radius = Math.hypot(p.x(), p.z());
                    assertTrue(radius >= UbBurstPlan.SPARK_RADIUS
                                    - UbBurstPlan.SPARK_RADIUS_JITTER / 2.0 - 1e-6,
                            "spark " + i + " is inside the ring band: radius " + radius);
                    assertTrue(radius <= UbBurstPlan.SPARK_RADIUS
                                    + UbBurstPlan.SPARK_RADIUS_JITTER / 2.0 + 1e-6,
                            "spark " + i + " is outside the ring band: radius " + radius);
                    assertTrue(Math.abs(p.y()) <= UbBurstPlan.SPARK_HEIGHT_JITTER + 1e-6,
                            "spark " + i + " is outside the chest-height band: y " + p.y());
                }
            }
        }
    }

    /**
     * No gap anywhere on the circle: the points are spread by the golden angle, not random.
     *
     * <p>The assertion is on the largest angular gap. A random ring of this count routinely leaves a
     * 60-90 degree hole, which reads as "the effect is broken on one side"; the golden-angle prefix
     * keeps every gap under 45 degrees even at the base count.
     */
    @Test
    void theSparksCoverTheWholeCircleWithoutAGap() {
        for (int count : new int[] {UbBurstPlan.BASE_SPARKS, UbBurstPlan.ENHANCED_SPARKS}) {
            List<Double> angles = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UbBurstPlan.Point p = UbBurstPlan.sparkPoint(i, count, FROM_WEST);
                double angle = Math.atan2(p.z(), p.x());
                angles.add(angle < 0 ? angle + Math.PI * 2.0 : angle);
            }
            angles.sort(Double::compare);
            double largestGap = angles.get(0) + Math.PI * 2.0 - angles.get(angles.size() - 1);
            for (int i = 1; i < angles.size(); i++) {
                largestGap = Math.max(largestGap, angles.get(i) - angles.get(i - 1));
            }
            assertTrue(largestGap < Math.PI / 4.0,
                    "a hole of " + Math.toDegrees(largestGap) + " degrees at count " + count);
        }
    }

    @Test
    void noTwoSparksStartOnTopOfEachOther() {
        for (int count : new int[] {UbBurstPlan.BASE_SPARKS, UbBurstPlan.ENHANCED_SPARKS}) {
            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    UbBurstPlan.Point a = UbBurstPlan.sparkPoint(i, count, FROM_WEST);
                    UbBurstPlan.Point b = UbBurstPlan.sparkPoint(j, count, FROM_WEST);
                    double distance = Math.sqrt(Math.pow(a.x() - b.x(), 2)
                            + Math.pow(a.y() - b.y(), 2) + Math.pow(a.z() - b.z(), 2));
                    // 0.05 blocks: they are allowed to be a dense ring (the enhanced cast is 36
                    // points on a ~0.9-block ring), but not to be the same spark twice.
                    assertTrue(distance > 0.05,
                            "sparks " + i + " and " + j + " are only " + distance + " apart");
                }
            }
        }
    }

    // ------------------------------------------------------------------ the direction

    /**
     * The ring is <b>rotated</b> by the blow, and rotating it does not change its shape.
     *
     * <p>Both halves matter: a burst that ignored the direction would be the same list twice (the
     * defect this replaced), and a rotation that stretched the ring would make an east-west hit look
     * different from a north-south one.
     */
    @Test
    void theRingIsRotatedByTheIncomingDirectionWithoutChangingShape() {
        List<UbBurstPlan.Point> west = points(FROM_WEST);
        List<UbBurstPlan.Point> north = points(FROM_NORTH);

        boolean anyDifferent = false;
        for (int i = 0; i < west.size(); i++) {
            if (Math.abs(west.get(i).x() - north.get(i).x()) > 1e-9) {
                anyDifferent = true;
            }
            assertEquals(Math.hypot(west.get(i).x(), west.get(i).z()),
                    Math.hypot(north.get(i).x(), north.get(i).z()), 1e-9,
                    "旋转只改朝向，不改半径");
            assertEquals(west.get(i).y(), north.get(i).y(), 1e-9, "旋转不改高度");
        }
        assertTrue(anyDifferent, "不同来向的两团必须朝向不同");
    }

    /**
     * A blow along {@code +x} puts the first spark on the {@code +x} axis, and a blow along {@code +z}
     * puts it on the {@code +z} axis. That is the concrete meaning of "oriented along the incoming
     * direction", and the cheapest way to catch a rotation that silently does nothing.
     */
    @Test
    void theRingsOwnForwardAxisFollowsTheBlow() {
        UbBurstPlan.Point alongX = UbBurstPlan.sparkPoint(0, 24, FROM_WEST);
        assertTrue(alongX.x() > 0.5, "从西边打来，环的第一点应该在 +x 一侧：" + alongX);
        assertEquals(0.0, alongX.z(), 1e-9);

        UbBurstPlan.Point alongZ = UbBurstPlan.sparkPoint(0, 24, FROM_NORTH);
        assertEquals(0.0, alongZ.x(), 1e-9);
        assertTrue(alongZ.z() > 0.5, "从南边打来，环的第一点应该在 +z 一侧：" + alongZ);
    }

    /** No direction is a legitimate state, and it must not produce NaN or a zero velocity. */
    @Test
    void noDirectionStillDrawsAUsableRing() {
        assertTrue(UbBurstPlan.Direction.NONE.isNone());
        UbBurstPlan.Point point = UbBurstPlan.sparkPoint(3, 24, UbBurstPlan.Direction.NONE);
        assertTrue(Double.isFinite(point.x()) && Double.isFinite(point.z()));

        UbBurstPlan.Spark spark = UbBurstPlan.spark(3, 24, UbBurstPlan.Direction.NONE);
        double length = Math.hypot(spark.velocity().x(), spark.velocity().z());
        assertTrue(length > 1e-6, "没有来向也要有速度，否则 vanilla 会把方向重新随机");
        // The unrotated ring is the original one: the no-direction branch is not a second geometry.
        assertEquals(UbBurstPlan.sparkPoint(3, 24, UbBurstPlan.Direction.NONE),
                UbBurstPlan.sparkPoint(3, 24, null), "null 与 NONE 必须是同一件事");
    }

    /**
     * A direction is a <b>unit</b> vector whatever it was built from, and a degenerate one is
     * {@link UbBurstPlan.Direction#NONE}.
     */
    @Test
    void aDirectionIsNormalisedAndDegenerateInputsAreNone() {
        UbBurstPlan.Direction long2 = UbBurstPlan.Direction.of(8.0, 0.0);
        assertEquals(1.0, long2.x(), 1e-12);
        assertEquals(0.0, long2.z(), 1e-12);

        UbBurstPlan.Direction diagonal = UbBurstPlan.Direction.of(3.0, 3.0);
        assertEquals(1.0, Math.hypot(diagonal.x(), diagonal.z()), 1e-12);

        assertEquals(UbBurstPlan.Direction.NONE, UbBurstPlan.Direction.of(0.0, 0.0));
        assertEquals(UbBurstPlan.Direction.NONE, UbBurstPlan.Direction.of(Double.NaN, 1.0));
        assertEquals(UbBurstPlan.Direction.NONE,
                UbBurstPlan.Direction.of(Double.POSITIVE_INFINITY, 1.0));
    }

    /** {@code incomingDirection} is caster → victim, and it never returns a NaN direction. */
    @Test
    void theIncomingDirectionPointsFromTheCasterToTheVictim() {
        UbBurstPlan.Direction east = UbBurstPlan.incomingDirection(0.0, 0.0, 4.0, 0.0);
        assertEquals(1.0, east.x(), 1e-12);
        assertEquals(0.0, east.z(), 1e-12);

        // Victim at +z of the caster.
        UbBurstPlan.Direction south = UbBurstPlan.incomingDirection(0.0, 0.0, 0.0, 5.0);
        assertEquals(1.0, south.z(), 1e-12);

        assertEquals(UbBurstPlan.Direction.NONE, UbBurstPlan.incomingDirection(2.0, 2.0, 2.0, 2.0),
                "施法者与受害者重合时没有来向可言");
        assertEquals(UbBurstPlan.Direction.NONE,
                UbBurstPlan.incomingDirection(0.0, 0.0, Double.NaN, 1.0));
    }

    // ------------------------------------------------------------------ the velocities

    /** Sparks leave the ring: outward, biased along the blow, and never zero. */
    @Test
    void everySparkLeavesTheRingOutwardAndAlongTheBlow() {
        List<UbBurstPlan.Spark> sparks = new ArrayList<>();
        for (int i = 0; i < UbBurstPlan.BASE_SPARKS; i++) {
            sparks.add(UbBurstPlan.spark(i, UbBurstPlan.BASE_SPARKS, FROM_WEST));
        }
        for (UbBurstPlan.Spark spark : sparks) {
            UbBurstPlan.Vector v = spark.velocity();
            double length = Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
            assertTrue(length > 0.01, "零速度会让 vanilla 重新随机方向：" + v);
            assertTrue(v.y() > 0.0, "火花要有上浮分量，否则像贴地滑动：" + v);
        }
        // The blow arrives along +x, so the *mean* horizontal velocity must have a +x component: the
        // ring is on average carried onward through the victim, which is what "a slash went through
        // here" looks like.
        double meanX = sparks.stream().mapToDouble(s -> s.velocity().x()).average().orElse(0.0);
        assertTrue(meanX > 0.05, "整团净速度应顺着来向，实际 " + meanX);
    }

    /** Motes rise and drift along the blow, and are slower than sparks by design. */
    @Test
    void motesRiseAndDriftMoreSlowlyThanSparks() {
        UbBurstPlan.Spark spark = UbBurstPlan.spark(0, UbBurstPlan.BASE_SPARKS, FROM_WEST);
        for (int i = 0; i < UbBurstPlan.BASE_MOTES; i++) {
            UbBurstPlan.Spark mote = UbBurstPlan.mote(i, UbBurstPlan.BASE_MOTES, FROM_WEST);
            UbBurstPlan.Vector v = mote.velocity();
            assertTrue(v.x() > 0.0, "余烬要顺来向漂一点：" + v);
            assertEquals(0.0, v.y(), 1e-12, "上浮是客户端的事，服务器只给漂移");
            double sparkSpeed = Math.hypot(spark.velocity().x(), spark.velocity().z());
            double moteSpeed = Math.hypot(v.x(), v.z());
            assertTrue(moteSpeed < sparkSpeed, "余烬必须比刀光慢");
        }
    }

    // ------------------------------------------------------------------ the motes

    @Test
    void theMotesRiseFromTheFeetInASmallSpiral() {
        for (int count : new int[] {UbBurstPlan.BASE_MOTES, UbBurstPlan.ENHANCED_MOTES}) {
            for (int i = 0; i < count; i++) {
                UbBurstPlan.Point p = UbBurstPlan.motePoint(i, count, FROM_WEST);
                double radius = Math.hypot(p.x(), p.z());
                assertTrue(radius >= UbBurstPlan.MOTE_RADIUS_MIN
                                - UbBurstPlan.MOTE_RADIUS_JITTER / 2.0 - 1e-6,
                        "a mote starts inside the victim: radius " + radius);
                assertTrue(radius <= UbBurstPlan.MOTE_RADIUS_MAX
                                + UbBurstPlan.MOTE_RADIUS_JITTER / 2.0 + 1e-6,
                        "a mote starts too far out: radius " + radius);
                assertTrue(p.y() >= UbBurstPlan.MOTE_Y_MIN
                                - UbBurstPlan.MOTE_HEIGHT_JITTER / 2.0 - 1e-6,
                        "a mote starts below the victim's feet: y " + p.y());
                assertTrue(p.y() <= UbBurstPlan.MOTE_Y_MAX
                                + UbBurstPlan.MOTE_HEIGHT_JITTER / 2.0 + 1e-6,
                        "a mote starts above the victim's head: y " + p.y());
            }
            assertTrue(UbBurstPlan.motePoint(count - 1, count, FROM_WEST).y()
                            > UbBurstPlan.motePoint(0, count, FROM_WEST).y() + 0.5,
                    "the motes must climb across the cast, not form a flat disc");
        }
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void thePlanIsDeterministicAndDoesNotOverflowOnBadIndices() {
        for (int i = 0; i < 100; i++) {
            assertEquals(UbBurstPlan.sparkPoint(i, 24, FROM_WEST),
                    UbBurstPlan.sparkPoint(i, 24, FROM_WEST));
            assertEquals(UbBurstPlan.motePoint(i, 7, FROM_WEST), UbBurstPlan.motePoint(i, 7, FROM_WEST));
        }
        // Defensive: a caller with a zero count or a wrapped index must not divide by zero or throw.
        UbBurstPlan.Point zero = UbBurstPlan.sparkPoint(3, 0, FROM_WEST);
        assertTrue(Double.isFinite(zero.x()) && Double.isFinite(zero.y()) && Double.isFinite(zero.z()));
        assertEquals(UbBurstPlan.sparkPoint(0, 24, FROM_WEST),
                UbBurstPlan.sparkPoint(24, 24, FROM_WEST),
                "indices wrap rather than walk off the ring");
        assertFalse(Double.isNaN(UbBurstPlan.motePoint(-1, 7, FROM_WEST).y()));
    }

    // ------------------------------------------------------------------ helpers

    /** {@return every spark point of a base ring, in index order} */
    private static List<UbBurstPlan.Point> points(UbBurstPlan.Direction facing) {
        List<UbBurstPlan.Point> points = new ArrayList<>(UbBurstPlan.BASE_SPARKS);
        for (int i = 0; i < UbBurstPlan.BASE_SPARKS; i++) {
            points.add(UbBurstPlan.sparkPoint(i, UbBurstPlan.BASE_SPARKS, facing));
        }
        return points;
    }
}
