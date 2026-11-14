package com.astrum.pcrd.impl.ub;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The whole burst of one cast, as absolute world points</b>: one ring and one spiral on every body
 * the cast hit, and nothing at all on the caster.
 *
 * <h2>Why this is its own class</h2>
 * {@link UbBurstPlan} owns the shape of a single burst — how many points, on what radius, which way
 * they travel — but it knows only <em>offsets</em>. This class is the join between that geometry, the
 * victim list {@link UbBurstVictims} tidies up, and the numbers a server call needs: absolute
 * {@code x/y/z}, and a velocity. Splitting it out is what makes the one claim the defect is about —
 * "N victims give N bursts, at their positions, none at the caster" — a statement about a
 * {@code List} instead of about packets, so it is unit-testable ({@code UbBurstTargetsTest}) without
 * a world, a player or a particle engine. That claim could not be tested at all while the emit loop
 * lived inside {@code ServerLevel.sendParticles} calls.
 *
 * <h2>No game types</h2>
 * A {@link Spawn} is a particle id's <em>kind</em>, three positions and three velocities. The caller
 * maps a kind to a registered {@code ParticleType}, which is what keeps this class free of
 * {@code ServerLevel} and lets the tests read exact coordinates.
 */
@ApiStatus.Internal
public final class UbBurstTargets {

    /** Which of the two registered particle types a point belongs to. */
    public enum Kind {
        /** The fast slash streak, on the ring around the victim's chest. */
        SPARK,
        /** The slow rising ember, climbing from the victim's feet. */
        MOTE
    }

    /**
     * One particle to spawn, at an absolute world position.
     *
     * @param kind     which particle type
     * @param x        world x
     * @param y        world y
     * @param z        world z
     * @param velocity blocks per tick; never zero (see {@link UbBurstPlan.Vector#unit})
     * @param victim   the body this point belongs to — kept so a log line and a test can name it
     */
    public record Spawn(Kind kind, double x, double y, double z, UbBurstPlan.Vector velocity,
                        java.util.UUID victim) {
    }

    /**
     * One victim's burst, at absolute positions.
     *
     * @param victim the body
     * @param sparks its ring, chest height
     * @param motes  its spiral, from the feet
     */
    public record Burst(UbBurstVictims.Victim victim, List<Spawn> sparks, List<Spawn> motes) {

        public Burst {
            sparks = List.copyOf(sparks);
            motes = List.copyOf(motes);
        }

        /** {@return every point of this burst, sparks then motes} */
        public List<Spawn> all() {
            List<Spawn> all = new ArrayList<>(sparks.size() + motes.size());
            all.addAll(sparks);
            all.addAll(motes);
            return List.copyOf(all);
        }

        /** {@return every point as an array of {@code [x, y, z]}, for tests that ask "where"} */
        public List<double[]> positions() {
            List<double[]> positions = new ArrayList<>();
            for (Spawn spawn : all()) {
                positions.add(new double[] {spawn.x(), spawn.y(), spawn.z()});
            }
            return List.copyOf(positions);
        }
    }

    private UbBurstTargets() {
    }

    /**
     * {@return every burst of one cast, in first-hit victim order — empty when nothing was hit}
     *
     * <p>Never throws. The victim list is {@link UbBurstVictims#resolve}'s: deduped by body, the caster
     * removed, unusable coordinates dropped. So a cast that hit two enemies answers two bursts, a
     * four-hit tier against one enemy answers <b>one</b>, and a cast that hit nothing answers an empty
     * list — which the caller must log rather than silently swallow.
     *
     * @param casterX  the caster's x, the origin of every blow's direction
     * @param casterZ  the caster's z
     * @param landed   every hit the settlement recorded, repeats included
     * @param casterId the caster's UUID — never a burst centre
     * @param enhanced whether the tier-3 ultimate fired
     */
    public static List<Burst> bursts(double casterX, double casterZ,
            List<UbBurstVictims.Victim> landed, java.util.UUID casterId, boolean enhanced) {
        List<UbBurstVictims.Victim> victims = UbBurstVictims.resolve(landed, casterId);
        if (victims.isEmpty()) {
            return List.of();
        }
        int sparks = UbBurstPlan.sparkCount(enhanced);
        int motes = UbBurstPlan.moteCount(enhanced);
        List<Burst> bursts = new ArrayList<>(victims.size());
        for (UbBurstVictims.Victim victim : victims) {
            UbBurstPlan.Direction incoming =
                    UbBurstPlan.incomingDirection(casterX, casterZ, victim.x(), victim.z());
            List<Spawn> ring = new ArrayList<>(sparks);
            for (int i = 0; i < sparks; i++) {
                UbBurstPlan.Spark spark = UbBurstPlan.spark(i, sparks, incoming);
                ring.add(new Spawn(Kind.SPARK, victim.x() + spark.origin().x(),
                        victim.chestY() + spark.origin().y(), victim.z() + spark.origin().z(),
                        spark.velocity(), victim.id()));
            }
            List<Spawn> spiral = new ArrayList<>(motes);
            for (int i = 0; i < motes; i++) {
                UbBurstPlan.Spark mote = UbBurstPlan.mote(i, motes, incoming);
                spiral.add(new Spawn(Kind.MOTE, victim.x() + mote.origin().x(),
                        victim.y() + mote.origin().y(), victim.z() + mote.origin().z(),
                        mote.velocity(), victim.id()));
            }
            bursts.add(new Burst(victim, ring, spiral));
        }
        return List.copyOf(bursts);
    }
}
