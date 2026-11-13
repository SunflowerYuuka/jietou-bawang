package com.astrum.pcrd.impl.ub;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UB particle burst's geometrical plan: how many, how far out, how high, and evenly spread.
 *
 * <h2>Why this is the testable half of a visual</h2>
 * Nobody writing this can see the burst. The two things that can still be proven are its
 * <b>structure</b> — a ring of a stated density, not a random cloud, with a few motes rising from the
 * feet — and its <b>non-degeneracy</b>: no NaNs, no duplicate points, no zero-radius ring, and the
 * enhanced tier actually bigger. Those are the claims a screenshot cannot state and a code review
 * keeps missing, so they are pinned here.
 *
 * <p>The per-particle random values (size, lifetime, the exact outward speed) deliberately live in
 * the client provider; this test covers only what {@link UbBurstPlan} decides.
 */
class UbBurstPlanTest {

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
        for (int count : new int[] {UbBurstPlan.BASE_SPARKS, UbBurstPlan.ENHANCED_SPARKS}) {
            for (int i = 0; i < count; i++) {
                UbBurstPlan.Point p = UbBurstPlan.sparkPoint(i, count);
                assertTrue(Double.isFinite(p.x()) && Double.isFinite(p.y())
                        && Double.isFinite(p.z()), "a point must be a real coordinate: " + p);
                double radius = Math.hypot(p.x(), p.z());
                assertTrue(radius >= UbBurstPlan.SPARK_RADIUS - UbBurstPlan.SPARK_RADIUS_JITTER / 2.0 - 1e-6,
                        "spark " + i + " is inside the ring band: radius " + radius);
                assertTrue(radius <= UbBurstPlan.SPARK_RADIUS + UbBurstPlan.SPARK_RADIUS_JITTER / 2.0 + 1e-6,
                        "spark " + i + " is outside the ring band: radius " + radius);
                assertTrue(Math.abs(p.y()) <= UbBurstPlan.SPARK_HEIGHT_JITTER + 1e-6,
                        "spark " + i + " is outside the chest-height band: y " + p.y());
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
                UbBurstPlan.Point p = UbBurstPlan.sparkPoint(i, count);
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
                    UbBurstPlan.Point a = UbBurstPlan.sparkPoint(i, count);
                    UbBurstPlan.Point b = UbBurstPlan.sparkPoint(j, count);
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

    // ------------------------------------------------------------------ the motes

    @Test
    void theMotesRiseFromTheFeetInASmallSpiral() {
        for (int count : new int[] {UbBurstPlan.BASE_MOTES, UbBurstPlan.ENHANCED_MOTES}) {
            for (int i = 0; i < count; i++) {
                UbBurstPlan.Point p = UbBurstPlan.motePoint(i, count);
                double radius = Math.hypot(p.x(), p.z());
                assertTrue(radius >= UbBurstPlan.MOTE_RADIUS_MIN - UbBurstPlan.MOTE_RADIUS_JITTER / 2.0 - 1e-6,
                        "a mote starts inside the caster: radius " + radius);
                assertTrue(radius <= UbBurstPlan.MOTE_RADIUS_MAX + UbBurstPlan.MOTE_RADIUS_JITTER / 2.0 + 1e-6,
                        "a mote starts too far out: radius " + radius);
                assertTrue(p.y() >= UbBurstPlan.MOTE_Y_MIN - UbBurstPlan.MOTE_HEIGHT_JITTER / 2.0 - 1e-6,
                        "a mote starts below the caster's feet: y " + p.y());
                assertTrue(p.y() <= UbBurstPlan.MOTE_Y_MAX + UbBurstPlan.MOTE_HEIGHT_JITTER / 2.0 + 1e-6,
                        "a mote starts above the caster's head: y " + p.y());
            }
            assertTrue(UbBurstPlan.motePoint(count - 1, count).y()
                            > UbBurstPlan.motePoint(0, count).y() + 0.5,
                    "the motes must climb across the cast, not form a flat disc");
        }
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void thePlanIsDeterministicAndDoesNotOverflowOnBadIndices() {
        for (int i = 0; i < 100; i++) {
            assertEquals(UbBurstPlan.sparkPoint(i, 24), UbBurstPlan.sparkPoint(i, 24));
            assertEquals(UbBurstPlan.motePoint(i, 7), UbBurstPlan.motePoint(i, 7));
        }
        // Defensive: a caller with a zero count or a wrapped index must not divide by zero or throw.
        UbBurstPlan.Point zero = UbBurstPlan.sparkPoint(3, 0);
        assertTrue(Double.isFinite(zero.x()) && Double.isFinite(zero.y()) && Double.isFinite(zero.z()));
        assertEquals(UbBurstPlan.sparkPoint(0, 24), UbBurstPlan.sparkPoint(24, 24),
                "indices wrap rather than walk off the ring");
        assertFalse(Double.isNaN(UbBurstPlan.motePoint(-1, 7).y()));
    }
}