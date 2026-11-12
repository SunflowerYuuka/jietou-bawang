package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.ub.UbCharge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where {@code area_charge} hands its work over — the seam Task 11 fills in.
 *
 * <h2>Why a seam and not the implementation</h2>
 * What {@code area_charge} has to do is two separable jobs. The <b>effect</b> half is: pick the
 * players this hook reaches, and credit each of them. The <b>guard</b> half (spec §7.4) is: only
 * charge players who count as allies under {@code allyMode}, apply the caster→receiver pair cooldown,
 * and cap what one receiver can gain per second. The guard half is Task 11's subject and, if it were
 * written here without measurement, would pre-empt the ally selection Task 11 must own (a
 * {@code SAME_TEAM_ONLY} rule that reads {@code isAlliedTo()} would behave differently from one that
 * reads the scoreboard team directly).
 *
 * <p>So this interface is declared here with {@link #DIRECT}, which does the effect half honestly and
 * implements <b>no</b> guard at all.
 *
 * <h2>The contract, in full</h2>
 * <ol>
 *   <li><b>One call = one charging event.</b> The caller invokes {@link #deliver} once per fired
 *       {@code area_charge} hook. An implementation must not fan out on its own schedule, and it must
 *       not be called speculatively.</li>
 *   <li><b>The amount is already final.</b> {@link Request#amount()} is the hook's declared amount
 *       already multiplied by the caster slot's diminishing multiplier. An implementation must not
 *       apply diminishing again (doubling the falloff is the §5.2 bug this seam is designed to make
 *       impossible), and must not scale it by anything else except the guards it is responsible
 *       for.</li>
 *   <li><b>{@code includeSelf} is the hook's</b>: the implementation must honour
 *       {@link Request#includeSelf()} rather than always including or always excluding the caster.</li>
 *   <li><b>Guards may reduce, never refuse to exist.</b> A guard returns less charge (or none for
 *       one receiver); it does not suppress the effect for the whole call. Spec §5.2 forbids the
 *       multiplier from switching a mechanism off, and the same reasoning applies to a cooldown: a
 *       gated receiver simply gets nothing this time.</li>
 *   <li><b>{@code characterId} is attribution, not a gauge selector</b>: pass it to
 *       {@link UbCharge#apply} unchanged so the charge event names the character whose hook fired.</li>
 *   <li><b>No exception may escape.</b> This runs inside a damage sequence; an implementation that
 *       cannot complete a grant must log and continue with the other receivers.</li>
 * </ol>
 *
 * <p>Task 11 replaces {@link #DIRECT} at the single construction point in
 * {@link CombatEvents}, and adds {@code AreaChargeBudget} + {@code AllySelector} behind it. Nothing
 * else in this package needs to change for that.
 */
@ApiStatus.Internal
public interface AreaChargeSink {

    /**
     * One fired {@code area_charge} hook.
     *
     * @param caster      the player whose wheel fired it
     * @param characterId which character owns the hook (attribution for {@link UbCharge})
     * @param radius      search radius in blocks, measured from the caster
     * @param amount      charge points per receiver, <b>already</b> scaled by diminishing returns
     * @param includeSelf whether the caster is one of the receivers
     */
    record Request(ServerPlayer caster, ResourceLocation characterId, double radius, double amount,
                   boolean includeSelf) {
    }

    /**
     * Delivers {@code request} to the receivers it selects. See the contract on this interface.
     *
     * @param request what one hook asks for
     */
    void deliver(Request request);

    /**
     * The unguarded implementation: every player within the radius, self included only when asked.
     *
     * <p>This is the <b>honest effect half</b> and deliberately nothing more. It has no pair cooldown
     * and no per-second cap, so N players carrying the same character charge each other in a linear
     * loop — the exact failure spec §7.4 describes, left open on purpose so Task 11 can close it with
     * the two proven guards rather than with a placeholder here.
     *
     * <p>"Within the radius" is checked per player against the <b>caster</b>, in the same dimension,
     * using squared distance. Single-player worlds are not special-cased (spec §7.4): the criterion is
     * "is there actually an ally in range right now", which {@code includeSelf} answers on its own when
     * there is nobody else.
     */
    AreaChargeSink DIRECT = request -> {
        ServerPlayer caster = request.caster();
        double radiusSquared = request.radius() * request.radius();
        for (ServerPlayer player : caster.serverLevel().players()) {
            if (player == caster) {
                if (!request.includeSelf()) {
                    continue;
                }
            } else if (player.distanceToSqr(caster) > radiusSquared) {
                continue;
            }
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            UbCharge.apply(player, request.characterId(), request.amount());
            Astrum.LOGGER.debug("astrum: area_charge {} -> {} ({} points, radius {})",
                    caster.getName().getString(), player.getName().getString(), request.amount(),
                    request.radius());
        }
    };
}
