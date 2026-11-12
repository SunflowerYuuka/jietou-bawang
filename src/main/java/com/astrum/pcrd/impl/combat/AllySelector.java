package com.astrum.pcrd.impl.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;

/**
 * The ally-selection half of spec §7.4, as a pure function over a prepared candidate list.
 *
 * <h2>Why there is no {@code Player} and no {@code Level} here</h2>
 * Selection is the risky rule — "who may this hook charge?" — and the answer must be provable without
 * a game. The caller (the sink) turns players into {@link Candidate}s: that is where distance is
 * measured, teams are read and spectators are dropped. This class then applies the policy to plain
 * data, so every branch that could leave a co-op player uncharged is one unit test away.
 *
 * <h2>Why {@code isAlliedTo()} does not appear</h2>
 * It answers {@code false} when either side has no scoreboard team, which would make
 * {@link AllyMode#ALL_PLAYERS} behave like {@code SAME_TEAM_ONLY} on a LAN world where nobody made a
 * team. The candidate carries the <b>team name itself</b>, and only
 * {@link AllyMode#SAME_TEAM_ONLY} looks at it. In {@code ALL_PLAYERS} a team-less neighbour is an
 * ally like any other.
 *
 * <h2>Single-player is a consequence, not a branch</h2>
 * Nothing here asks how many players exist. A world with only the caster produces a list holding only
 * the caster, so {@code includeSelf} decides between self-charge and nobody — the same rule that
 * governs a filled server, applied to a smaller list.
 */
@ApiStatus.Internal
public final class AllySelector {

    private AllySelector() {
    }

    /**
     * One player, as selection sees it.
     *
     * @param id       the player's UUID
     * @param self     whether this candidate is the caster (the sink derives it from the UUID, and
     *                 the id comparison is kept as a second guard)
     * @param teamName the player's scoreboard team name, or empty when they have no team
     * @param distance blocks from the caster; {@code 0.0} for the caster itself
     */
    public record Candidate(UUID id, boolean self, Optional<String> teamName, double distance) {
    }

    /**
     * {@return the candidates this hook may charge, in the order given}
     *
     * <p>The rules, in the order they apply:
     * <ol>
     *   <li>a candidate farther than {@code radius} is out (the boundary itself is included);</li>
     *   <li>the caster is out when {@code includeSelf} is {@code false};</li>
     *   <li>under {@link AllyMode#SAME_TEAM_ONLY}, a non-caster needs a team name equal to the
     *       caster's; a team-less caster therefore reaches nobody but itself.</li>
     * </ol>
     *
     * <p>Never throws: a {@code null} list, a {@code null} element, a {@code null} caster or a NaN
     * radius all answer an empty or reduced list. This runs inside a damage sequence, so a guard that
     * throws would cost more than the charge it withheld.
     *
     * @param candidates   players the caller has already filtered to eligible receivers
     * @param caster       the UUID of the player whose hook fired
     * @param mode         the configured ally policy
     * @param radius       maximum distance in blocks, inclusive
     * @param includeSelf  whether the caster may charge itself
     */
    public static List<Candidate> select(List<Candidate> candidates, UUID caster, AllyMode mode,
            double radius, boolean includeSelf) {
        if (candidates == null || caster == null) {
            return List.of();
        }
        AllyMode policy = mode == null ? AllyMode.ALL_PLAYERS : mode;
        Optional<String> casterTeam = candidates.stream()
                .filter(candidate -> candidate != null && isCaster(candidate, caster))
                .findFirst()
                .flatMap(Candidate::teamName);

        List<Candidate> selected = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || !(candidate.distance() <= radius)) {
                continue;   // also drops NaN distances and a NaN radius
            }
            boolean self = isCaster(candidate, caster);
            if (self && !includeSelf) {
                continue;
            }
            if (policy == AllyMode.SAME_TEAM_ONLY && !self && !sameTeam(candidate, casterTeam)) {
                continue;
            }
            selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    private static boolean isCaster(Candidate candidate, UUID caster) {
        return candidate.self() || candidate.id().equals(caster);
    }

    private static boolean sameTeam(Candidate candidate, Optional<String> casterTeam) {
        return casterTeam.isPresent() && candidate.teamName().isPresent()
                && casterTeam.get().equals(candidate.teamName().get());
    }
}
