package com.astrum.pcrd.impl.ub;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The defect the human found by playing</b>: 打对面的攻击，特效却显示在自己身上.
 *
 * <h2>What is asserted, and why it is the whole point</h2>
 * The claim is spatial: <b>N victims give N bursts, at the victims' own positions, and none at the
 * caster</b>. Everything else about the effect (which sprite, how bright, how long it lives) is a
 * matter of taste; "whose body is it on" is not, and it is exactly the thing the previous
 * implementation got wrong while every test stayed green — because the emit loop lived inside
 * {@code ServerLevel.sendParticles} calls, where no assertion could reach it.
 *
 * <p>So the assertions here are on {@link UbBurstTargets}, the pure half: a list of absolute points.
 * The three questions a reviewer would ask are one test each —
 * {@link #everyVictimGetsItsOwnBurstAtItsOwnPosition},
 * {@link #theCasterNeverGetsABurst} and {@link #aMultiHitTierDrawsOneBurstPerBodyNotPerHit} — and
 * {@link #aCastThatHitsNothingDrawsNothing} pins the silent-no-op case the brief calls out.
 *
 * <h2>What only a real client can confirm</h2>
 * That the burst <em>looks</em> like a slash landing on the victim, and that a second player actually
 * sees it. Those are in the task report as human checks; this file proves the coordinates, which is
 * the part that was wrong.
 */
class UbBurstTargetsTest {

    private static final UUID CASTER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000000ca");

    /** A player-sized body (1.8 blocks tall) standing at {@code (x, z)}. */
    private static UbBurstVictims.Victim at(UUID id, double x, double z) {
        return new UbBurstVictims.Victim(id, x, 64.0, z, 1.8);
    }

    /** The caster stands at the origin, so every expected position below is also "x blocks away". */
    private static final double CASTER_X = 0.0;
    private static final double CASTER_Z = 0.0;

    private static List<UbBurstTargets.Burst> bursts(List<UbBurstVictims.Victim> landed,
            boolean enhanced) {
        return UbBurstTargets.bursts(CASTER_X, CASTER_Z, landed, CASTER, enhanced);
    }

    /**
     * Two enemies, two bursts, each centred on its own body.
     *
     * <p>"Centred on" is asserted as a distance, not as an equality: the points sit on a ring of
     * radius {@link UbBurstPlan#SPARK_RADIUS}, so the right statement is "every point is within the
     * ring band of that victim, and the ring's mean centre is that victim". A test that demanded
     * exact coordinates would just restate the arithmetic; this one fails if the centre moves.
     */
    @Test
    void everyVictimGetsItsOwnBurstAtItsOwnPosition() {
        List<UbBurstTargets.Burst> result = bursts(List.of(at(BOB, 4.0, 0.0), at(CAROL, 0.0, 5.0)),
                false);

        assertEquals(2, result.size(), "两个受害者就该有两团特效，一人一团");
        assertEquals(BOB, result.get(0).victim().id(), "按命中顺序，第一团是第一个被打的");
        assertEquals(CAROL, result.get(1).victim().id());

        assertCentredOn(result.get(0), 4.0, 0.0);
        assertCentredOn(result.get(1), 0.0, 5.0);
        assertEquals(UbBurstPlan.sparkCount(false), result.get(0).sparks().size());
        assertEquals(UbBurstPlan.moteCount(false), result.get(0).motes().size());
    }

    /**
     * The headline: <b>nothing at the caster</b>.
     *
     * <p>Not "the caster gets a smaller burst" and not "the caster's burst is off to the side" — no
     * point of the whole cast may be within a body's width of where the caster is standing. That is
     * the literal thing the human objected to, so it is asserted literally.
     */
    @Test
    void theCasterNeverGetsABurst() {
        List<UbBurstTargets.Burst> result = bursts(List.of(at(BOB, 4.0, 0.0)), true);

        assertEquals(1, result.size());
        assertCentredOn(result.get(0), 4.0, 0.0);
        for (UbBurstTargets.Spawn spawn : result.get(0).all()) {
            double fromCaster = Math.hypot(spawn.x() - CASTER_X, spawn.z() - CASTER_Z);
            assertTrue(fromCaster > 2.0,
                    "施法者身上不许有任何一点特效，实际有一点离他只有 " + fromCaster + " 格：" + spawn);
        }
    }

    /**
     * A four-hit tier against one body is <b>one</b> burst.
     *
     * <p>This is the rule that a naive "one burst per hit" reading gets wrong, and it would be
     * invisible in play (four rings at the same place look like one bright ring) while multiplying the
     * packet count by four.
     */
    @Test
    void aMultiHitTierDrawsOneBurstPerBodyNotPerHit() {
        List<UbBurstVictims.Victim> fourHits = new ArrayList<>();
        for (int hit = 0; hit < 4; hit++) {
            fourHits.add(at(BOB, 4.0, 0.0));
        }

        List<UbBurstTargets.Burst> result = bursts(fourHits, false);

        assertEquals(1, result.size(), "四段打同一个身体 = 一团特效，不是四团");
        assertEquals(UbBurstPlan.sparkCount(false), result.get(0).sparks().size(),
                "四段也不该把点位数量翻四倍");
    }

    /** A cast that hit nothing draws nothing — the caller is expected to log it, not invent a burst. */
    @Test
    void aCastThatHitsNothingDrawsNothing() {
        assertEquals(List.of(), bursts(List.of(), false));
        assertEquals(List.of(), bursts(null, false));
    }

    /**
     * The caster cannot be a burst centre even when the settlement reports them.
     *
     * <p>An area tier can catch the caster in its own radius; {@code UbDamage.plan} refuses to damage
     * them, but this call site repeats the refusal rather than assuming it, because a burst on the
     * attacker is precisely the reported bug.
     */
    @Test
    void aLandedHitOnTheCasterIsStillRefused() {
        assertEquals(List.of(), bursts(List.of(at(CASTER, CASTER_X, CASTER_Z)), false));
        assertEquals(1, bursts(List.of(at(CASTER, 0.0, 0.0), at(BOB, 4.0, 0.0)), false).size(),
                "施法者被剔除，受害者仍然有自己的一团");
    }

    /**
     * Two victims hit from different sides are rotated differently.
     *
     * <p>The ring's own points are the same numbers in both bursts; only the rotation differs. So the
     * assertion is that the two sets of offsets are <b>not</b> equal — i.e. the burst is oriented by
     * the blow, which is what "oriented along the incoming direction" asks for.
     */
    @Test
    void eachBurstIsOrientedAlongItsOwnIncomingDirection() {
        List<UbBurstTargets.Burst> result = bursts(List.of(at(BOB, 4.0, 0.0), at(CAROL, 0.0, 5.0)),
                false);

        List<double[]> fromWest = result.get(0).positions();
        List<double[]> fromNorth = result.get(1).positions();
        boolean anyDifferent = false;
        for (int i = 0; i < fromWest.size(); i++) {
            double offsetWest = fromWest.get(i)[0] - 4.0;
            double offsetNorth = fromNorth.get(i)[0] - 0.0;
            if (Math.abs(offsetWest - offsetNorth) > 1e-6) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "两团特效的朝向必须不同：一团是从西边打的，一团是从南边打的");
    }

    /** Every velocity is a real, non-zero vector: a zero one would let vanilla re-randomise the spray. */
    @Test
    void everyPointCarriesAUsableVelocity() {
        for (UbBurstTargets.Burst burst : bursts(List.of(at(BOB, 4.0, 0.0)), true)) {
            for (UbBurstTargets.Spawn spawn : burst.all()) {
                UbBurstPlan.Vector velocity = spawn.velocity();
                assertTrue(Double.isFinite(velocity.x()) && Double.isFinite(velocity.y())
                        && Double.isFinite(velocity.z()), "速度必须是有限数：" + velocity);
                double length = Math.sqrt(velocity.x() * velocity.x() + velocity.y() * velocity.y()
                        + velocity.z() * velocity.z());
                assertTrue(length > 0.01, "速度不能是零向量（vanilla 会把它当『随机』）：" + velocity);
            }
        }
    }

    /** The enhanced tier is denser on every victim, not only on the first. */
    @Test
    void theEnhancedTierIsDenserOnEveryVictim() {
        List<UbBurstVictims.Victim> two = List.of(at(BOB, 4.0, 0.0), at(CAROL, 0.0, 5.0));
        for (UbBurstTargets.Burst burst : bursts(two, true)) {
            assertEquals(UbBurstPlan.ENHANCED_SPARKS, burst.sparks().size());
            assertEquals(UbBurstPlan.ENHANCED_MOTES, burst.motes().size());
        }
    }

    /**
     * The shape of the defect, expressed as a test that would have failed before the fix.
     *
     * <p>The old implementation's whole output sat at one point. This asserts the opposite: the set of
     * burst centres is exactly the set of victim ids, one each.
     */
    @Test
    void theBurstCentresAreTheVictimsAndNobodyElse() {
        List<UbBurstTargets.Burst> result = bursts(List.of(at(BOB, 3.0, 0.0), at(CAROL, 0.0, 3.0)),
                false);
        List<UUID> centres = result.stream().map(burst -> burst.victim().id()).toList();

        assertEquals(List.of(BOB, CAROL), centres);
        assertNotEquals(List.of(CASTER), centres, "这正是被修掉的那个 bug：以前唯一的中心是施法者");
        assertFalse(centres.contains(CASTER));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Asserts the ring and the spiral both belong to the body at {@code (x, z)}.
     *
     * <p>Mean horizontal offset, not each point, because the points are deliberately spread over a
     * band — the centre is what must be the victim. Bounds are the plan's own constants, so a
     * regeneration of the geometry moves the test with it rather than invalidating it.
     */
    private static void assertCentredOn(UbBurstTargets.Burst burst, double x, double z) {
        double sumX = 0.0;
        double sumZ = 0.0;
        int count = 0;
        for (UbBurstTargets.Spawn spawn : burst.all()) {
            double dx = spawn.x() - x;
            double dz = spawn.z() - z;
            assertTrue(Math.hypot(dx, dz) <= UbBurstPlan.SPARK_RADIUS
                            + UbBurstPlan.SPARK_RADIUS_JITTER + UbBurstPlan.MOTE_RADIUS_JITTER,
                    "点离受害者太远了（这团是不是画在别人身上？）：(" + dx + ", " + dz + ")");
            sumX += dx;
            sumZ += dz;
            count++;
        }
        assertTrue(count > 0);
        assertTrue(Math.hypot(sumX / count, sumZ / count) < 0.10,
                "整团的平均水平中心必须就是受害者本人，实际偏移 (" + (sumX / count) + ", "
                        + (sumZ / count) + ")");
    }
}
