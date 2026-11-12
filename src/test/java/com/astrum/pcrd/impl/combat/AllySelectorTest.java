package com.astrum.pcrd.impl.combat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §7.4's ally rule, as arithmetic: who is selected, with no {@code Player}, no {@code Level} and
 * no scoreboard in sight.
 *
 * <h2>The trap these tests exist for</h2>
 * {@code Entity.isAlliedTo()} answers {@code false} when either side has no scoreboard team, so a
 * selection built on it would never charge LAN co-op players who never made a team. Every test below
 * therefore uses team-less candidates in {@link AllyMode#ALL_PLAYERS}, and
 * {@link #allPlayersModeReachesTeammatelessNeighbours} would fail for an {@code isAlliedTo}-based
 * selector even though both players are four blocks apart.
 *
 * <h2>"Single-player" is not a case</h2>
 * The selector never asks how many players exist; it answers "who in this list reaches this caster".
 * {@link #aWorldWithNobodyElseDegradesToSelfCharge} pins the consequence: with only the caster in the
 * list, an {@code includeSelf} hook charges the caster and an {@code includeSelf=false} hook charges
 * nobody. That is the single-player experience, produced by the same rule as every other world.
 */
class AllySelectorTest {

    static final UUID ME = UUID.randomUUID(), OTHER = UUID.randomUUID(), FAR = UUID.randomUUID();

    static List<AllySelector.Candidate> pool() {
        return List.of(new AllySelector.Candidate(ME, true, Optional.empty(), 0.0),
                new AllySelector.Candidate(OTHER, false, Optional.empty(), 4.0),
                new AllySelector.Candidate(FAR, false, Optional.empty(), 20.0));
    }

    @Test
    void allPlayersModeReachesTeammatelessNeighbours() {
        var got = AllySelector.select(pool(), ME, AllyMode.ALL_PLAYERS, 8.0, true);
        assertEquals(2, got.size(), "自己 + 邻居，不含 20 格外");
        assertTrue(got.stream().anyMatch(c -> c.id().equals(OTHER)),
                "isAlliedTo 在无队伍时会给 false，这里必须仍选中");
    }

    @Test
    void includeSelfFalseExcludesCaster() {
        var got = AllySelector.select(pool(), ME, AllyMode.ALL_PLAYERS, 8.0, false);
        assertEquals(1, got.size());
        assertTrue(got.stream().noneMatch(AllySelector.Candidate::self));
    }

    @Test
    void sameTeamOnlyRequiresMatchingTeam() {
        var withTeams = List.of(new AllySelector.Candidate(ME, true, Optional.of("red"), 0.0),
                new AllySelector.Candidate(OTHER, false, Optional.of("blue"), 4.0),
                new AllySelector.Candidate(FAR, false, Optional.of("red"), 4.0));
        var got = AllySelector.select(withTeams, ME, AllyMode.SAME_TEAM_ONLY, 8.0, false);
        assertEquals(1, got.size());
        assertTrue(got.get(0).id().equals(FAR));
    }

    @Test
    void unknownModeStringFallsBackToAllPlayers() {
        assertEquals(AllyMode.ALL_PLAYERS, AllyMode.parse("NONSENSE"));
    }

    // ------------------------------------------------------------------ the edges the brief implies

    /**
     * {@code SAME_TEAM_ONLY} must not turn "no team anywhere" into "the whole server is one team":
     * two team-less players are strangers under that mode. The caster is still eligible for itself
     * when the hook asks for it, because the caster is trivially on its own (absent) team.
     */
    @Test
    void sameTeamOnlyTreatsTeamlessPlayersAsStrangers() {
        var got = AllySelector.select(pool(), ME, AllyMode.SAME_TEAM_ONLY, 8.0, true);
        assertEquals(1, got.size(), "只有自己；无队伍的邻居不算同队");
        assertTrue(got.get(0).self());
    }

    /**
     * The single-player consequence, stated as a test rather than as a branch: a list containing
     * only the caster yields self-charge or nothing, and never a special case.
     */
    @Test
    void aWorldWithNobodyElseDegradesToSelfCharge() {
        var alone = List.of(new AllySelector.Candidate(ME, true, Optional.empty(), 0.0));
        assertEquals(1, AllySelector.select(alone, ME, AllyMode.ALL_PLAYERS, 8.0, true).size());
        assertEquals(0, AllySelector.select(alone, ME, AllyMode.ALL_PLAYERS, 8.0, false).size());
    }

    @Test
    void theRadiusBoundaryIsInclusive() {
        var atEdge = List.of(new AllySelector.Candidate(ME, true, Optional.empty(), 0.0),
                new AllySelector.Candidate(OTHER, false, Optional.empty(), 8.0),
                new AllySelector.Candidate(FAR, false, Optional.empty(), 8.001));
        var got = AllySelector.select(atEdge, ME, AllyMode.ALL_PLAYERS, 8.0, true);
        assertEquals(List.of(ME, OTHER), got.stream().map(AllySelector.Candidate::id).toList());
    }

    /** Config files are hand-edited; whitespace and lower case are not a reason to fall back. */
    @Test
    void modeParsingToleratesCaseAndPaddingButStillFallsBackOnNonsense() {
        assertEquals(AllyMode.SAME_TEAM_ONLY, AllyMode.parse("  same_team_only "));
        assertEquals(AllyMode.ALL_PLAYERS, AllyMode.parse("all_players"));
        assertEquals(AllyMode.ALL_PLAYERS, AllyMode.parse(null));
        assertEquals(AllyMode.ALL_PLAYERS, AllyMode.parse(""));
    }
}
