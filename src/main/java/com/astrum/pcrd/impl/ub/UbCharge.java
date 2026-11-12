package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.event.UbChargeEvent;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.UbState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;

/**
 * The one place a player's UB gauge is credited on the server: posts {@link UbChargeEvent}, drops the
 * change if a listener cancelled it, otherwise applies it through {@link ChargeKernel} and writes
 * {@code UB_STATE}.
 *
 * <h2>Why the entry point is here and not at each source</h2>
 * Damage dealt, damage taken, a kill and the {@code gain_charge} effect all end in the same three
 * steps (post, honour cancellation, clamp). Repeating them per source would put the event contract
 * in four places and make "did that source post?" a question with four answers. Task 10's
 * {@code gain_charge} executor and Task 11's {@code area_charge} route through
 * {@link #apply(ServerPlayer, ResourceLocation, double)} for exactly that reason.
 *
 * <h2>Attribution is the caller's, the gauge is the player's</h2>
 * {@code characterId} says <em>which character's hook caused this charge</em>; it never selects a
 * gauge. Every player has exactly one gauge ({@code UbState.charge}) and one shared cooldown
 * (controller ruling on spec §4.3/§5.4), so this method has no per-character storage to choose
 * between. A caller that iterates the wheel passes the character whose hook fired; a caller with no
 * character has nothing to attribute and must not invent one.
 *
 * <p>This class deliberately touches no {@code Roster}: charge is per-player state driven by the
 * event that just happened, and reading a quarantined character book here would turn a readable
 * charge path into a throwing one.
 */
@ApiStatus.Internal
public final class UbCharge {

    private UbCharge() {
    }

    /**
     * Credits {@code delta} charge points to {@code player}'s gauge, through the API event.
     *
     * <p>The delta is the <em>proposed</em> change, before the event: listeners may cancel it or
     * rewrite {@link UbChargeEvent#amount()} (a negative amount drains). The result is clamped by
     * {@link ChargeKernel}, and a change that leaves the gauge bit-identical is not written, so a
     * full bar costs no attachment churn.
     *
     * @param player      the player whose gauge changes; must be server-side
     * @param characterId which character's hook caused the change (attribution only)
     * @param delta       charge points to add; negative values drain
     * @return {@code true} if the gauge actually changed
     */
    public static boolean apply(ServerPlayer player, ResourceLocation characterId, double delta) {
        UbChargeEvent event = NeoForge.EVENT_BUS.post(new UbChargeEvent(characterId, delta));
        if (event.isCanceled()) {
            return false;
        }

        UbState before = player.getData(Attachments.UB_STATE);
        float next = (float) ChargeKernel.add(before.charge(), event.amount());
        if (next == before.charge()) {
            return false;
        }
        player.setData(Attachments.UB_STATE, new UbState(next, before.selectedSlot(), before.cdEndTick()));
        Astrum.LOGGER.debug("astrum: UB charge {} -> {} ({})", before.charge(), next, characterId);
        return true;
    }
}
