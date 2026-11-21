package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>The one receiver rule the two transfer effects share: "which single body does a transfer land on?"</b>
 *
 * <h2>The rule, in the human's words</h2>
 * 「在周围有可以充能拥有UB槽的友方单位时，优先给组最近的友方单位，没有的情况下再给自己。」 — and, for the heal,
 * 「他奶同他充……也是在周围有拥有UB槽的友方单位时，优先给组最近的友方单位，没有的情况下再给自己。」
 *
 * <p>So three facts, in this order:
 * <ol>
 *   <li>a candidate must be an <b>ally</b> — {@link AllySelector}'s membership test, unchanged;</li>
 *   <li>a candidate must <b>have a UB gauge</b> — {@link #hasGauge};</li>
 *   <li>the <b>nearest</b> of those wins; when there is none, the <b>caster</b>.</li>
 * </ol>
 *
 * <h2>Why this is a class and not two copies of an if-statement</h2>
 * 「他充」 and 「他奶」 are one receiver rule with two payloads, and the human said so twice. Writing "among allies
 * with a gauge, nearest, else caster" into both would be exactly the duplication this project keeps paying for —
 * and the two would drift the first time somebody decided one of them should let the caster join the distance
 * race. So there is one function here and two thin effects.
 *
 * <h2>Why not {@code AREA_CHARGE} with a tiny radius</h2>
 * {@code AREA_CHARGE} charges <b>everyone</b> in the radius and takes an {@code includeSelf} flag. Reusing it
 * would (a) make "nearest" an emergent property of a radius, which is not the same rule; (b) silently charge two
 * allies standing at the same distance when only one was meant; and (c) leave the caster out whenever an ally
 * happened to be in range, which the flag cannot express. So this is its own function over the shared kernels:
 * {@link AllySelector} for membership, {@link AllyCandidates} for distance, {@code UbCharge} for the grant,
 * {@code PercentHeals} for the heal.
 *
 * <h2>Ties, and why the answer is not accidental</h2>
 * Two allies at exactly the same distance are broken by <b>lowest UUID string</b>. That is deterministic and does
 * not depend on the level's player-list order, which is not promised to be stable across ticks or reloads — so
 * without an explicit tie-break a transfer would land on a different teammate depending on iteration order, a bug
 * nobody could reproduce. Both transfer effects get the same answer because they call this one function.
 *
 * <h2>What "has a UB gauge" means, and why it is not invented here</h2>
 * {@link Party} is the wheel: five slots, each optionally holding a character. A player with nothing on their
 * wheel has no ultimate to charge and no bar to fill — {@code UbService.tryCast} refuses them with
 * {@code CastGate.Reason.SLOT_EMPTY} for exactly that reason. So {@link #hasGauge} asks the party attachment, the
 * existing answer to "is this player a party character with a gauge", rather than a new predicate. A non-player
 * has no party attachment at all and is therefore never a candidate.
 *
 * <h2>Two layers, because one of them must be testable without a game</h2>
 * {@link #select} is the whole policy as a pure function over {@link AllySelector.Candidate} values — no player,
 * no level, no attachment — so "nearest wins", "the tie-break is the id" and "no candidate means no answer" are
 * assertions. {@link #of} is the production join that prepares the candidates and owns the caster fallback. That
 * is the same split {@code AllySelector} itself makes, for the same reason.
 */
@ApiStatus.Internal
public final class TransferTarget {

    private TransferTarget() {
    }

    /**
     * {@return the single body a transfer effect lands on: the nearest ally with a UB gauge, else the caster}
     *
     * <p>Never {@code null} for a non-{@code null} caster: the caster is the documented last resort, so a
     * transfer never does nothing. That matters because both callers run on a damage path, where "the effect
     * silently did nothing" is the failure this project treats as worse than a wrong receiver.
     *
     * @param caster the player whose hook fired; {@code null} answers {@code null}
     * @param radius how far the search reaches, in blocks; the boundary itself is included
     */
    public static @Nullable LivingEntity of(@Nullable LivingEntity caster, double radius) {
        if (caster == null) {
            return null;
        }
        if (!(caster instanceof ServerPlayer player)) {
            // A non-player caster has no wheel and no allies: the transfer lands on the body that asked, the
            // same degradation Heals documents for its own non-player branch.
            return caster;
        }
        return nearestAllyWithGauge(player, radius).<LivingEntity>map(value -> value).orElse(player);
    }

    /**
     * {@return the nearest ally of {@code caster} who has a UB gauge, or empty when there is none}
     *
     * <p>The half that {@link #of} falls back from, exposed because "an ally was the answer" and "the caster was"
     * are different facts — a caller that wants to log which one happened asks this instead of comparing.
     *
     * <p>Both rules are delegated: <b>who is an ally</b> is {@link AllySelector#select} over
     * {@link AllyCandidates}' prepared pool (distance measured, team name read, the dead and spectators already
     * dropped), and <b>who has a gauge</b> is {@link #hasGauge}. This method adds only "the nearest" and the
     * tie-break, and the entity it answers comes from the pool that measured the distance — never re-resolved
     * against a level that may have moved on.
     *
     * @param caster the player whose hook fired
     * @param radius how far the search reaches, in blocks, inclusive
     */
    public static Optional<ServerPlayer> nearestAllyWithGauge(ServerPlayer caster, double radius) {
        if (caster == null) {
            return Optional.empty();
        }
        AllyCandidates.Pool pool = AllyCandidates.around(caster);
        // includeSelf = false: the caster is the documented FALLBACK, not a candidate in the race. Letting it
        // compete at distance 0.0 would make "nearest" always the caster and the transfer dead code.
        List<AllySelector.Candidate> selected = AllySelector.select(pool.candidates(),
                caster.getUUID(), AllyCandidates.configuredMode(), radius, false);
        List<AllySelector.Candidate> withGauge = new ArrayList<>(selected.size());
        for (AllySelector.Candidate candidate : selected) {
            ServerPlayer player = pool.players().get(candidate.id());
            if (player != null && hasGauge(player)) {
                withGauge.add(candidate);
            }
        }
        return select(withGauge).map(winner -> pool.players().get(winner.id()));
    }

    /**
     * {@return the nearest candidate, ties broken by the lowest UUID — the whole policy, without a game}
     *
     * <h2>Why the tie-break is here rather than left to list order</h2>
     * Two candidates at exactly the same distance need an explicit, repeatable winner. The comparison is
     * <b>strict</b> on distance (a later candidate must be strictly nearer to displace the incumbent), and the id
     * comparison settles the remaining cases. {@code NaN} distances are dropped rather than compared, because
     * every comparison against {@code NaN} is false and one bad measurement would otherwise win or block the
     * selection unpredictably.
     *
     * @param candidates the eligible candidates; {@code null} answers empty
     */
    public static Optional<AllySelector.Candidate> select(@Nullable List<AllySelector.Candidate> candidates) {
        if (candidates == null) {
            return Optional.empty();
        }
        AllySelector.Candidate winner = null;
        for (AllySelector.Candidate candidate : candidates) {
            if (candidate == null || !Double.isFinite(candidate.distance())) {
                continue;
            }
            if (winner == null || isNearer(candidate, winner)) {
                winner = candidate;
            }
        }
        return Optional.ofNullable(winner);
    }

    /**
     * {@return whether {@code candidate} beats {@code incumbent}: strictly nearer, or the same distance and a
     * lower id}
     *
     * <p>Written once: two transfer effects and the pure selection all ask it, and the id comparison is the part
     * that is easy to leave out. A silent omission would still pass a happy-path test — two allies at different
     * distances — and fail only on the day two stood together.
     */
    static boolean isNearer(AllySelector.Candidate candidate, AllySelector.Candidate incumbent) {
        if (candidate.distance() != incumbent.distance()) {
            return candidate.distance() < incumbent.distance();
        }
        return candidate.id().toString().compareTo(incumbent.id().toString()) < 0;
    }

    /**
     * {@return whether {@code player} has a UB gauge at all — i.e. holds at least one party character}
     *
     * <h2>Why the party, and not "is the player alive"</h2>
     * The gauge is per <b>player</b> ({@code UbState.charge} is one value, not one per character), so the question
     * a transfer asks is "would charging this person mean anything?" — and the answer is no for a player who has
     * equipped nothing, because their ultimate cannot be cast from any slot. So the predicate is the wheel
     * attachment, the existing source of truth for "who is a party character", not a new flag anywhere.
     *
     * <p>Never throws: a player whose attachment cannot be read answers {@code false}, which withholds a transfer
     * rather than costing the hit that triggered it.
     *
     * @param player the candidate receiver; {@code null} or a non-player answers {@code false}
     */
    public static boolean hasGauge(@Nullable LivingEntity player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        try {
            Party party = serverPlayer.getData(Attachments.PARTY);
            return party != null && !party.equipped().isEmpty();
        } catch (RuntimeException unattached) {
            return false;
        }
    }

    /**
     * {@return {@code radius}, sanitised into something {@link AllySelector} can compare}
     *
     * <p>An absent radius is the party default (a transfer written for 「周围」 must not silently become
     * self-only), {@code 0} is impossible for this rule — a transfer with no reach could never find the teammate
     * it exists for — and a nonsense radius falls back to the default rather than finding nobody. Delegated to
     * {@code AttackBuffs.radiusFor} like every other radius in this kernel, so there is one reading of "how far".
     *
     * @param declared the hook's radius, if it declared one
     */
    public static double radiusFor(java.util.OptionalDouble declared) {
        return AttackBuffs.radiusFor(declared);
    }
}
