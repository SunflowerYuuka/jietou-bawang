package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.ub.UbCharge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.ApiStatus;

/**
 * The real {@code area_charge} sink: {@link AreaChargeSink}'s effect half plus spec §7.4's guards.
 *
 * <h2>How the contract survives</h2>
 * <ol>
 *   <li><b>One call = one fired hook.</b> {@link #deliver} reads the clock once and walks the
 *       selected receivers once; it schedules nothing and fans out nowhere.</li>
 *   <li><b>The amount is already final.</b> {@link Request#amount()} goes to
 *       {@link AreaChargeBudget#grant} untouched and the granted result goes to {@link UbCharge}.
 *       Nothing here multiplies by a diminishing factor, so the §5.2 double-falloff bug cannot be
 *       written in this class by accident.</li>
 *   <li><b>{@code includeSelf} is the hook's.</b> It is handed to
 *       {@link AllySelector#select} rather than being decided here.</li>
 *   <li><b>Guards reduce, they never suppress the call.</b> The budget answers per receiver and the
 *       loop continues; a withheld receiver gets a debug line, not an early return.</li>
 *   <li><b>{@code characterId} is attribution.</b> It is passed unchanged to {@link UbCharge#apply}.</li>
 *   <li><b>Nothing escapes.</b> Selection and each receiver's credit are separately wrapped: a
 *       broken third-party charge listener costs its own receiver, not the rest of the call, and no
 *       exception reaches the damage sequence.</li>
 * </ol>
 *
 * <h2>Why the candidate list is built here</h2>
 * {@link AllySelector} must stay free of {@code Player}/{@code Level}, so this class is the adapter
 * that measures distance, reads the scoreboard team name (not {@code isAlliedTo}, which answers
 * {@code false} for team-less LAN players) and drops spectators or dead players. Non-player entities
 * never enter the list: {@link ServerLevel#players()} is players only, which is also why a mob can
 * never be charged — it has no UB gauge to charge.
 *
 * <h2>Not a special case for single-player</h2>
 * There is no "is this a single-player world" branch (spec §7.4, and no
 * {@code isDedicatedServer()} either). On a one-player world the candidate list simply holds one
 * player and {@code includeSelf} decides between self-charge and nothing.
 *
 * <h2>State and lifecycle</h2>
 * The only state is the {@link AreaChargeBudget} (pair timestamps and per-receiver windows). It is
 * owned by one sink instance, which {@code CombatEvents} constructs once and clears on server stop;
 * the class holds no static mutable container. The two guard values come from
 * {@link AreaChargeTuning}, so an operator can retune them at runtime (Task 14's command) without a
 * restart, while the TOML remains the persisted default.
 */
@ApiStatus.Internal
public final class GuardedAreaChargeSink implements AreaChargeSink {

    /** Game ticks in one second, the window of the per-receiver cap. */
    private static final long TICKS_PER_SECOND = 20L;

    private final AreaChargeBudget budget;

    public GuardedAreaChargeSink() {
        this(new AreaChargeBudget());
    }

    GuardedAreaChargeSink(AreaChargeBudget budget) {
        this.budget = budget;
    }

    @Override
    public void deliver(Request request) {
        if (request == null) {
            return;
        }
        try {
            deliverGuarded(request);
        } catch (RuntimeException e) {
            // Deliberately does not dereference the request while reporting: the failure may be the
            // request itself, and a throw from the catch block would escape into the damage sequence.
            Astrum.LOGGER.error("astrum: area_charge delivery threw; the hit is unaffected", e);
        }
    }

    private void deliverGuarded(Request request) {
        ServerPlayer caster = request.caster();
        if (caster == null || !caster.isAlive() || caster.isSpectator()) {
            return;   // no wheel fires for a removed, dead or spectator caster; nothing to attribute
        }
        ServerLevel level = caster.serverLevel();
        long nowTick = level.getGameTime();
        long nowSecond = Math.floorDiv(nowTick, TICKS_PER_SECOND);
        AllyMode mode = allyMode();

        List<AllySelector.Candidate> candidates = new ArrayList<>();
        Map<UUID, ServerPlayer> receivers = new HashMap<>();
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
            receivers.put(id, player);
        }

        List<AllySelector.Candidate> selected = AllySelector.select(candidates, caster.getUUID(),
                mode, request.radius(), request.includeSelf());
        Astrum.LOGGER.debug("astrum: area_charge from {} mode={} includeSelf={} radius={}: "
                        + "{} of {} eligible players selected",
                caster.getName().getString(), mode, request.includeSelf(), request.radius(),
                selected.size(), candidates.size());

        List<UUID> selectedIds = new ArrayList<>(selected.size());
        for (AllySelector.Candidate candidate : selected) {
            if (receivers.containsKey(candidate.id())) {
                selectedIds.add(candidate.id());
            }
        }

        Map<UUID, Double> credits = grantEach(selectedIds, caster.getUUID(), request.amount(),
                nowTick, nowSecond, budget);

        for (UUID id : selectedIds) {
            ServerPlayer receiver = receivers.get(id);
            double granted = credits.getOrDefault(id, 0.0);
            if (!(granted > 0.0)) {
                Astrum.LOGGER.debug("astrum: area_charge {} -> {} withheld ({} requested, "
                                + "pair cooldown or per-second cap)",
                        caster.getName().getString(), receiver.getName().getString(),
                        request.amount());
                continue;
            }
            try {
                // characterId is attribution only and amount already carries diminishing: both
                // are passed through exactly as the hook produced them.
                UbCharge.apply(receiver, request.characterId(), granted);
                Astrum.LOGGER.debug("astrum: area_charge {} -> {} {} points (of {} requested)",
                        caster.getName().getString(), receiver.getName().getString(), granted,
                        request.amount());
            } catch (RuntimeException e) {
                // One receiver's failure must not cost the others: same rule as the damage flush.
                Astrum.LOGGER.error("astrum: area_charge to {} threw; the other receivers still run",
                        id, e);
            }
        }
    }

    /**
     * The per-receiver half of one delivery, free of Minecraft types so a unit test can pin the two
     * call-level promises: a receiver withheld by a guard does not stop the others, and the amount
     * handed to the credit step is exactly what {@link AreaChargeBudget#grant} returned.
     *
     * <p>Only credited receivers appear in the result; an empty map means every receiver was
     * withheld, not that the call failed. The order of {@code receivers} is preserved. A grant is
     * still made per receiver, so one pair being blocked never short-circuits another receiver's
     * window. {@code AreaChargeBudget} is documented never to throw, and its suppliers are wrapped
     * internally, so this loop has no per-receiver catch of its own; the caller wraps each credit.
     *
     * @return receiver id → amount actually credited, in delivery order
     */
    static Map<UUID, Double> grantEach(List<UUID> receivers, UUID caster, double amount,
                                       long nowTick, long nowSecond, AreaChargeBudget budget) {
        Map<UUID, Double> credits = new LinkedHashMap<>();
        for (UUID receiver : receivers) {
            double granted = budget.grant(caster, receiver, amount, nowTick, nowSecond);
            if (granted > 0.0) {
                credits.put(receiver, granted);
            }
        }
        return credits;
    }

    /** Forgets the budget when the world goes away; the next server starts from a clean clock. */
    public void reset() {
        budget.reset();
    }

    /**
     * {@return the configured ally policy, falling back to {@link AllyMode#ALL_PLAYERS} if the file
     * cannot be read}
     *
     * <p>The fallback is the permissive one for the same reason {@link AllyMode#parse} has one: an
     * unreadable value must not switch the mechanic off for a table with no teams. In a running game
     * the config is loaded before any hit can happen, so this is a belt, not the mechanism.
     */
    private static AllyMode allyMode() {
        try {
            return AllyMode.parse(ServerConfig.MULTIPLAYER.allyMode.get());
        } catch (RuntimeException notLoaded) {
            return AllyMode.ALL_PLAYERS;
        }
    }

    /**
     * {@return the player's scoreboard team name, or empty when they have none}
     *
     * <p>The name, not {@code isAlliedTo}: the latter reports {@code false} whenever either side has
     * no team, which is the §7.4 trap. {@code SAME_TEAM_ONLY} compares these names in the kernel.
     */
    private static Optional<String> teamName(ServerPlayer player) {
        PlayerTeam team = player.getTeam();
        return team == null ? Optional.empty() : Optional.of(team.getName());
    }
}
