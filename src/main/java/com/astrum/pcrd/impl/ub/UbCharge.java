package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.event.UbChargeEvent;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.UbState;
import com.astrum.pcrd.impl.effect.ChargeRates;
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
 * <h2>The charge-rate multiplier is resolved here, once per credit</h2>
 * {@code CHARGE_RATE_UP} (「TP上昇」) makes every source pay more. The multiplier is
 * {@link ChargeRates#multiplierOf} — the single query for "what is this player's charge rate right now",
 * read from the effect instances actually on the entity — and it is applied <b>inside</b>
 * {@link ChargeKernel#add}, the same primitive all four shaped {@code add*} methods compose. So a
 * damage-dealt credit, a damage-taken credit, a kill credit and the passive trickle are all multiplied,
 * and none of those four callers mentions the effect.
 *
 * <p>It is read <b>per credit</b>, never cached: an effect that expires stops paying immediately, and an
 * operator changing a coefficient takes effect on the next credit — the same "config values are handles,
 * never copies" rule (spec §8.4 guardrail 1), applied to an effect level instead of a config key.
 *
 * <h2>Attribution is the caller's, the gauge is the player's</h2>
 * {@code characterId} says <em>which character's hook caused this charge</em>; it never selects a
 * gauge. Every player has exactly one gauge ({@code UbState.charge}), and it is deliberately still one
 * shared bar: the human asked 「每个角色UB的CD不共享」 — about the <b>cooldown</b> — and not about the
 * gauge, so splitting the gauge as well would be inventing a second design decision he did not make.
 * The consequence is stated where it is visible: casting A drains the one bar for everybody, while the
 * cooldown A arms belongs to A alone ({@code UbState.cooldowns}); B can therefore be castable the
 * moment the bar refills, even while A still cools. A caller that iterates the wheel passes the
 * character whose hook fired; a caller with no character has nothing to attribute and must not invent
 * one.
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
     * rewrite {@link UbChargeEvent#amount()} (a negative amount drains). The <b>charge-rate
     * multiplier</b> is applied after the event, by {@link ChargeKernel#add}, so a listener that rewrites
     * the amount gets the player's real rate applied to its number rather than the caller's — the rate is
     * a property of the player, not of the source. The result is clamped by {@link ChargeKernel}, and a
     * change that leaves the gauge bit-identical is not written, so a full bar costs no attachment churn.
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
        double rate = ChargeRates.multiplierOf(player);
        float next = (float) ChargeKernel.add(before.charge(), event.amount(), rate);
        if (next == before.charge()) {
            return false;
        }
        // withCharge, not a full constructor: the per-character cooldowns are somebody else's fact and
        // must survive a charge credit untouched. Spelling out `new UbState(next, slot, Map.of())` here
        // — which is what the three-argument record invites — would clear every cooldown on the wheel
        // every time the trickle paid out.
        player.setData(Attachments.UB_STATE, before.withCharge(next, before.selectedSlot()));
        Astrum.LOGGER.debug("astrum: UB charge {} -> {} ({}, rate x{})", before.charge(), next,
                characterId, rate);
        return true;
    }
}
