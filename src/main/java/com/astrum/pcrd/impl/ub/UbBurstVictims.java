package com.astrum.pcrd.impl.ub;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Who a cast's burst belongs to</b>: the victims the damage actually landed on, once each, and
 * never the caster.
 *
 * <h2>The rule this class is</h2>
 * 打对面的攻击，特效就该在对面身上 — a slash is aimed at somebody, so its visual belongs where the
 * blade lands. The previous implementation anchored the whole burst at {@code caster.position()}, and
 * its javadoc argued for that; the argument was written for the <em>old screen-space</em> effect (a
 * teammate should not have their screen flashed) and does not survive the move to world particles: a
 * slash drawn on the attacker is not "my effect, seen by me", it is an effect on the wrong body.
 *
 * <h2>Why the victims come from the damage settlement</h2>
 * "Who was hit" already has one answer: {@link UbDamage#plan} proposes, {@link UbDamageSettlement}
 * lands real {@code hurt()} calls and knows which of them returned {@code true}. Re-deriving the
 * target set here (re-running the reach test, or re-reading the candidates) would be a second answer
 * to "did this cast hit that thing", and the two would disagree the first time a victim died, went
 * invulnerable or left the level between the two readings. So this class does not look for victims:
 * it <b>tidies up</b> the list the settlement recorded.
 *
 * <h2>The three rules of {@link #resolve}, all of them cheap to get wrong</h2>
 * <ol>
 *   <li><b>One burst per victim, not per hit.</b> A four-hit ultimate calls {@code hurt()} four times
 *       on the same body; four bursts would read as four separate impacts and would be four times the
 *       particles for one landing.</li>
 *   <li><b>The caster is never a burst centre.</b> An area tier can legitimately catch the caster in
 *       its own radius, and {@code UbDamage} already refuses to damage them — but the particle path is
 *       a separate call site, so the refusal is repeated here rather than assumed.</li>
 *   <li><b>A victim with no usable position is dropped.</b> {@code NaN} coordinates come from a
 *       misbehaving entity; sending them would put a packet with {@code NaN} on the wire, which
 *       vanilla's particle engine then hands to every client.</li>
 * </ol>
 *
 * <p>Holds no {@code Level} and no {@code Entity}, so all three rules are unit-testable
 * ({@code UbBurstVictimsTest}) — which is what the previous caster-anchored version could not be.
 */
@ApiStatus.Internal
public final class UbBurstVictims {

    /**
     * One body a burst belongs on, as the settlement saw it at the moment the hit landed.
     *
     * <p>Position rather than a live {@code Entity}: the burst is emitted in the same tick as the
     * damage, but a record keeps this class free of game types and keeps the emit a pure function of
     * numbers.
     *
     * @param id     the victim's UUID
     * @param x      feet x
     * @param y      feet y
     * @param z      feet z
     * @param height the victim's bounding-box height, for the chest the ring sits around
     */
    public record Victim(UUID id, double x, double y, double z, double height) {

        /** {@return the y a spark ring sits at: 55% of the body, the same fraction the old burst used} */
        public double chestY() {
            return y + height * 0.55;
        }
    }

    private UbBurstVictims() {
    }

    /**
     * The landed victims of one cast, filled by the settlement and read by the emitter.
     *
     * <p>A ledger rather than a return value because the settlement is wrapped in
     * {@link UbDamageSettlement#once}: the two sinks share one {@code Runnable}, and a value returned
     * from it would be visible only to whichever site happened to run it. Each sink creates its own
     * ledger per cast, so it is per-cast state with a lifetime of one method — nothing static, nothing
     * to clear on logout or shutdown.
     *
     * <p>Deliberately not thread-safe: every write comes from the server thread's own cast path.
     */
    public static final class Ledger {

        /** Every landed hit, in strike order and with repeats; {@link #resolve} is what dedupes. */
        private final List<Victim> landed = new ArrayList<>();

        /**
         * Records one body that a hit actually landed on.
         *
         * <p>Called once per {@code hurt()}, so a victim of a four-hit tier appears four times here —
         * on purpose, because "the same body was struck four times" is a fact the settlement knows
         * and this class is the one place that turns it into "one burst".
         *
         * @param victim the body the hit landed on; {@code null} is ignored
         */
        public void record(Victim victim) {
            if (victim != null) {
                landed.add(victim);
            }
        }

        /** {@return every recorded hit, in the order the hits landed} */
        public List<Victim> landed() {
            return List.copyOf(landed);
        }

        /** {@return whether nothing landed — i.e. this cast had no victim to draw on} */
        public boolean isEmpty() {
            return landed.isEmpty();
        }
    }

    /**
     * {@return one burst centre per victim of {@code landed}, in first-hit order, never the caster}
     *
     * <p>Never throws and never returns {@code null}; a {@code null} list answers an empty one, which
     * is the same answer as "the cast hit nothing" and is exactly what the emitter logs.
     *
     * @param landed   every landed hit, repeats included — {@link Ledger#landed()}
     * @param casterId the player who cast; a victim with this id is dropped
     */
    public static List<Victim> resolve(List<Victim> landed, UUID casterId) {
        if (landed == null || landed.isEmpty()) {
            return List.of();
        }
        List<Victim> resolved = new ArrayList<>(landed.size());
        Set<UUID> seen = new HashSet<>();
        for (Victim victim : landed) {
            if (victim == null || victim.id() == null) {
                continue;
            }
            if (victim.id().equals(casterId)) {
                continue;   // 打对面的攻击不该在施法者身上炸开
            }
            if (!usable(victim)) {
                continue;   // a NaN coordinate must never reach the wire
            }
            if (seen.add(victim.id())) {
                resolved.add(victim);
            }
        }
        return List.copyOf(resolved);
    }

    /** {@return whether every number of this victim is a real coordinate and a sane body height} */
    private static boolean usable(Victim victim) {
        return Double.isFinite(victim.x()) && Double.isFinite(victim.y())
                && Double.isFinite(victim.z()) && Double.isFinite(victim.height())
                && victim.height() > 0.0;
    }
}
