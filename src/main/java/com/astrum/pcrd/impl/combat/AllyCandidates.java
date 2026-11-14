package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.config.ServerConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.ApiStatus;

/**
 * The game-bound half of the ally rule: turns the players standing around a caster into
 * {@link AllySelector} candidates, and answers "who receives this".
 *
 * <h2>Why this class exists</h2>
 * {@link AllySelector} is deliberately free of {@code Player}, {@code Level} and the scoreboard, so
 * <b>somebody</b> has to measure the distance, read the team name and drop the spectators. That
 * somebody used to be {@code GuardedAreaChargeSink} alone; a second party effect (the 6★ ultimate's
 * attack buff) and a third (the ultimate's damage plan) each needed the same six lines, and writing
 * them a second and third time is exactly how "who is an ally" grows two answers. So the adapter lives
 * here, once, and every caller joins it to the one kernel that owns the policy.
 *
 * <h2>The three traps it keeps out of the callers</h2>
 * <ul>
 *   <li><b>{@code isAlliedTo()} is not consulted.</b> It answers {@code false} when either side has no
 *       scoreboard team, which would make every LAN co-op partner a stranger. The candidate carries the
 *       team <em>name</em>, and only {@link AllyMode#SAME_TEAM_ONLY} looks at it.</li>
 *   <li><b>The dead and the spectators are not candidates.</b> A corpse cannot be buffed and a spectator
 *       has no gameplay, so neither is worth a distance measurement or a line in the log.</li>
 *   <li><b>Non-players never enter the list.</b> {@link ServerLevel#players()} is players only; a mob
 *       has no UB gauge, no scoreboard team and no HUD entry.</li>
 * </ul>
 *
 * <h2>No radius here</h2>
 * {@link Pool#allies} passes the radius straight to {@link AllySelector}, which is the one place the
 * inclusive-boundary comparison is written. This class only measures; it never decides.
 *
 * <p>Holds no state: every method is a pure function of the level it is handed, so there is nothing to
 * clear on logout or shutdown.
 */
@ApiStatus.Internal
public final class AllyCandidates {

    /**
     * The players of one level, as selection sees them.
     *
     * @param candidates every eligible player, in the level's own order
     * @param players    the live {@code ServerPlayer} for each candidate id
     */
    public record Pool(List<AllySelector.Candidate> candidates, Map<UUID, ServerPlayer> players) {

        public Pool {
            candidates = List.copyOf(candidates);
            players = Map.copyOf(players);
        }

        /**
         * {@return the players {@link AllySelector} selects, in pool order}
         *
         * <p>The only join between the adapter and the kernel: no rule is re-applied here, and a
         * candidate with no live player behind it — impossible today, but cheap to be safe about —
         * is skipped rather than reported, because it would be a bug in {@link #around} and not
         * something a player could act on.
         *
         * @param caster      the player whose effect fired
         * @param mode        the configured policy; {@code null} means {@link AllyMode#ALL_PLAYERS}
         * @param radius      maximum distance in blocks, inclusive
         * @param includeSelf whether the caster is a receiver too
         */
        public List<ServerPlayer> allies(UUID caster, AllyMode mode, double radius, boolean includeSelf) {
            List<ServerPlayer> selected = new ArrayList<>();
            for (AllySelector.Candidate candidate
                    : AllySelector.select(candidates, caster, mode, radius, includeSelf)) {
                ServerPlayer player = players.get(candidate.id());
                if (player != null) {
                    selected.add(player);
                }
            }
            return List.copyOf(selected);
        }

        /** {@return how many players were considered, for log lines and tests} */
        public int size() {
            return candidates.size();
        }
    }

    /** Nobody: what a missing level or a dead caster produces. */
    public static final Pool EMPTY = new Pool(List.of(), Map.of());

    private AllyCandidates() {
    }

    /**
     * {@return every player in {@code caster}'s level that could be a receiver, with its distance}
     *
     * <p>The caster is included (distance {@code 0.0}), because {@link AllySelector} reads the caster's
     * own team name off their candidate to compare the others against — and because
     * {@code includeSelf} is the kernel's decision, not this class's.
     *
     * <p>Never throws and never returns {@code null}: a caster with no level (removed, or not yet
     * added) answers {@link #EMPTY}. This runs inside damage and cast paths, where a throw would cost
     * more than the buff it withheld.
     *
     * @param caster the player whose ultimate or hook fired; {@code null} answers {@link #EMPTY}
     */
    public static Pool around(ServerPlayer caster) {
        if (caster == null) {
            return EMPTY;
        }
        ServerLevel level;
        try {
            level = caster.serverLevel();
        } catch (RuntimeException notInALevel) {
            return EMPTY;
        }
        if (level == null) {
            return EMPTY;
        }
        List<AllySelector.Candidate> candidates = new ArrayList<>();
        Map<UUID, ServerPlayer> players = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            if (player == null || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            UUID id = player.getUUID();
            boolean self = id.equals(caster.getUUID());
            double distance = self ? 0.0 : Math.sqrt(player.distanceToSqr(caster));
            if (!Double.isFinite(distance)) {
                continue;
            }
            candidates.add(new AllySelector.Candidate(id, self, teamName(player), distance));
            players.put(id, player);
        }
        return new Pool(candidates, players);
    }

    /**
     * {@return the configured ally policy, falling back to {@link AllyMode#ALL_PLAYERS} if the file
     * cannot be read}
     *
     * <p>The permissive fallback is {@link AllyMode#parse}'s own choice, for the same reason: an
     * unreadable value must not switch the mechanic off for a table with no teams. Reading it here —
     * lazily, per call — is also what keeps a {@code ConfigValue} out of any constructor (spec §8.4:
     * a config read at class-init time crashes the mod).
     */
    public static AllyMode configuredMode() {
        try {
            return AllyMode.parse(ServerConfig.MULTIPLAYER.allyMode.get());
        } catch (RuntimeException notLoaded) {
            return AllyMode.ALL_PLAYERS;
        }
    }

    /** {@return the player's scoreboard team name, or empty — the name, never {@code isAlliedTo}} */
    private static Optional<String> teamName(ServerPlayer player) {
        PlayerTeam team = player.getTeam();
        return team == null ? Optional.empty() : Optional.of(team.getName());
    }
}
