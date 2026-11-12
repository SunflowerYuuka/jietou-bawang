package com.astrum.pcrd.impl.combat;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where {@code area_charge} hands its work over: the seam between "this hook fired" and "these
 * players' gauges are credited".
 *
 * <h2>Why the seam exists at all</h2>
 * What {@code area_charge} has to do is two separable jobs. The <b>effect</b> half is: pick the
 * players this hook reaches, and credit each of them. The <b>guard</b> half (spec §7.4) is: only
 * charge players who count as allies under {@code allyMode}, apply the caster→receiver pair cooldown,
 * and cap what one receiver can gain per second. Keeping them behind an interface lets the guard
 * half live in {@link AllySelector} and {@link AreaChargeBudget}, where it is arithmetic that unit
 * tests can prove without a Minecraft server — which is the whole point, because these guards are
 * the part most likely to be wrong in a way that only shows up on a LAN world with three players.
 *
 * <p>{@link GuardedAreaChargeSink} is the only implementation. It is constructed in exactly one
 * place, {@link CombatEvents}; nothing else may deliver directly to
 * {@link com.astrum.pcrd.impl.ub.UbCharge}.
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
 *       {@link com.astrum.pcrd.impl.ub.UbCharge#apply} unchanged so the charge event names the
 *       character whose hook fired.</li>
 *   <li><b>No exception may escape.</b> This runs inside a damage sequence; an implementation that
 *       cannot complete a grant must log and continue with the other receivers.</li>
 * </ol>
 */
@ApiStatus.Internal
public interface AreaChargeSink {

    /**
     * One fired {@code area_charge} hook.
     *
     * @param caster      the player whose wheel fired it
     * @param characterId which character owns the hook (attribution for
     *                    {@link com.astrum.pcrd.impl.ub.UbCharge})
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
}
