package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.UbState;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.effect.ChargeRates;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * The trickle charge of spec §5.4 (每秒微量自然回复，兜底): {@code ubChargePerSecond} points per second,
 * for every online player, whether or not they are fighting.
 *
 * <h2>Why a player tick event, and why it is not the forbidden tick polling</h2>
 * This project forbids tick <b>polling</b> — an {@code EntityTickEvent}/{@code LevelTickEvent} handler
 * that walks entities, or {@code Stack}/{@code Combo} state touched from a tick; both are {@code guard}
 * FAILs. {@link PlayerTickEvent.Post} is not that: it is an event the engine posts for one player it
 * already had in hand, so this class never enumerates a level, never walks a list, and touches no state
 * except the one player the event names. {@code AstrumNetwork} already listens on the same event for the
 * gauge push, for the same reason, and {@code guard} reports this family as a WARN to be confirmed
 * rather than as a FAIL.
 *
 * <h2>Where the arithmetic lives</h2>
 * {@link #step} — plain {@code double} in, a {@link Step} out, no player, no level, no config handle —
 * holds both rules that matter:
 *
 * <ul>
 *   <li><b>Whole points only.</b> A trickle smaller than one point per tick must not be rounded away
 *       every tick: {@code 0.2}/s is {@code 0.01}/tick, and a caller that added that and truncated would
 *       credit nothing, forever. The remainder therefore survives in {@link #carry}, one entry per
 *       player, and a tick that produces no whole point still stores the new remainder.</li>
 *   <li><b>No clamp of its own.</b> The credit is paid through {@link ChargeKernel#add}, and the
 *       per-tick share comes from {@link ChargeKernel#naturalRegen}: this class re-implements neither.
 *       A permanently full bar is the one case decided before that call, and the reason is not clamping
 *       — it is that charge which cannot be stored must not be paid for, and a full bar must not hoard
 *       an unbounded carry.</li>
 * </ul>
 *
 * <h2>What {@code 0} means</h2>
 * A {@code ubChargePerSecond} of {@code 0} is a supported setting, not a disabled key: the listener then
 * credits nothing and drops any remainder, so a world that turns the trickle off behaves exactly like a
 * build without this class — which is what {@code PassiveChargeTest} pins.
 */
@ApiStatus.Internal
public final class PassiveCharge {

    /** Attribution for charge no character caused: the trickle belongs to nobody's hook. */
    private static final ResourceLocation NO_CHARACTER = InternalIds.id("passive");

    /**
     * Slack in the floor, in charge points.
     *
     * <p>Not a fudge factor: {@code 0.01} added a hundred times is {@code 0.9999999999999999}, not
     * {@code 1.0}, so a strict {@code Math.floor} would delay the first point of a default-rate trickle
     * by a tick each time the error happens to land just below the integer. The tolerance is fourteen
     * orders of magnitude below one charge point and far above the rounding error of any run this
     * accumulator can hold (its value is always below 1), so it changes no decision except the exact
     * boundary case it exists for.
     */
    private static final double FLOOR_SLACK = 1e-9;

    /**
     * Fractional charge earned but not yet credited, per player.
     *
     * <p>An instance field, not a static: one listener per server session owns it, exactly like
     * {@code GrowthEvents}'s dish throttle, and {@link #onServerStopping} clears it so one world's
     * remainders cannot pay the next world's first tick. Bounded by the players who have ticked, and
     * pruned on logout as well.
     */
    private final Map<UUID, Double> carry = new HashMap<>();

    private PassiveCharge() {
    }

    /** Subscribes this listener to the game bus. Called once, from the mod constructor. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new PassiveCharge());
        Astrum.LOGGER.info("astrum: passive UB charge wired — ubChargePerSecond is paid on PlayerTickEvent.Post");
    }

    /**
     * {@return one tick of the trickle: the gauge it produces, the raw points it pays, and what carries on}
     *
     * <p>The single pure rule of this class, so it is testable without a player or a config file. Three
     * states, and only three:
     *
     * <ul>
     *   <li>the option is {@code 0} (or not a positive number) → the gauge does not move and nothing
     *       carries, because a disabled trickle must not bank charge it would pay out later;</li>
     *   <li>the gauge is already at the cap → ditto, because nothing can be stored;</li>
     *   <li>otherwise → the accumulated fraction plus this tick's share is paid in whole points, and
     *       the rest carries.</li>
     * </ul>
     *
     * <h2>Why the rate is a parameter, and why the points are reported raw</h2>
     * 「TP上昇」 multiplies <b>every</b> charge source, and the passive trickle is the fourth. The rule is
     * applied in exactly one place — {@link ChargeKernel#add} — so this method must not apply it a second
     * time on the way to the gauge. What it does is compute what the gauge <em>will</em> hold once the rate
     * is applied ({@code charge}), and separately report the <b>raw</b> pre-rate points
     * ({@link Step#points()}) that the listener hands to {@code UbCharge}.
     *
     * <p>That split is what makes the two halves provably one rule: the listener credits
     * {@code step.points()} through {@code UbCharge}, which calls
     * {@code ChargeKernel.add(before, points, rate)} — the identical expression this method evaluated. A
     * test can therefore assert the rate reaches the trickle by reading {@code step.charge()} (22.5 for ten
     * points at 2.25x), while the write path stays single-application.
     *
     * @param charge    the gauge before this tick
     * @param carry     fractional charge left over from the previous tick
     * @param perSecond the configured {@code ubChargePerSecond}
     * @param rate      the player's charge-rate multiplier, {@code 1.0} for none
     */
    public static Step step(double charge, double carry, double perSecond, double rate) {
        if (!(perSecond > 0.0) || !Double.isFinite(perSecond)) {
            return new Step(ChargeKernel.clamp(charge), 0.0, 0.0);
        }
        if (ChargeKernel.isFull(charge)) {
            return new Step(ChargeKernel.MAX_CHARGE, 0.0, 0.0);
        }
        double remainder = Double.isFinite(carry) && carry > 0.0 ? carry : 0.0;
        double total = remainder + ChargeKernel.naturalRegen(perSecond);
        double points = Math.floor(total + FLOOR_SLACK);
        if (!(points > 0.0)) {
            return new Step(ChargeKernel.clamp(charge), total, 0.0);
        }
        // Math.max, not a bare subtraction: the slack can push `points` a hair past `total`, which would
        // leave a negative carry — a value that would then silently cancel the next tick's share.
        return new Step(ChargeKernel.add(charge, points, rate), Math.max(0.0, total - points), points);
    }

    /**
     * {@return one tick of the trickle at no charge-rate bonus}
     *
     * <p>The identity-rate form, for the callers and tests that have no effect to resolve — and the reason
     * every pre-existing number in {@code PassiveChargeTest} is unchanged by the rate being added.
     *
     * @param charge    the gauge before this tick
     * @param carry     fractional charge left over from the previous tick
     * @param perSecond the configured {@code ubChargePerSecond}
     */
    public static Step step(double charge, double carry, double perSecond) {
        return step(charge, carry, perSecond, ChargeKernel.NEUTRAL_RATE);
    }

    /**
     * One tick of the trickle.
     *
     * @param charge the gauge after it, with this player's charge rate applied
     * @param carry  the fraction carried into the next tick
     * @param points the <b>raw</b>, pre-rate whole points this tick pays — what the listener credits
     */
    public record Step(double charge, double carry, double points) {
    }

    /**
     * Credits the trickle to one online player.
     *
     * <p>Server-side only, by the {@code instanceof}: the event fires on both sides, and only the server
     * owns an authoritative gauge. The config is read <b>here</b>, every tick, never cached (spec §8.4
     * guardrail 1) — so an operator raising the value in the config screen takes effect on the next
     * tick, the same rule every other charge source follows.
     */
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        double perSecond = ServerConfig.UB.ubChargePerSecond.get();
        UUID id = player.getUUID();
        if (!(perSecond > 0.0)) {
            carry.remove(id);   // 0 is a real setting: nothing accrues, and no stale remainder survives
            return;
        }
        UbState before = player.getData(Attachments.UB_STATE);
        // The rate is read here, per tick, for the same "handles, never copies" reason the config is:
        // an effect that expires stops multiplying on the very tick it ends.
        double rate = ChargeRates.multiplierOf(player);
        Step step = step(before.charge(), carry.getOrDefault(id, 0.0), perSecond, rate);
        if (step.carry() == 0.0) {
            carry.remove(id);
        } else {
            carry.put(id, step.carry());
        }
        if (step.charge() <= before.charge()) {
            return;
        }
        // Through UbCharge, so the API event still sees (and may veto or rescale) the gain, and the
        // write, the clamp and the "unchanged state costs no attachment churn" rule stay in one place.
        // The RAW points are credited, not the rate-scaled ones: UbCharge resolves the player's rate and
        // applies it inside ChargeKernel.add, which is the single place the multiplier is applied.
        UbCharge.apply(player, NO_CHARACTER, step.points());
        Astrum.LOGGER.debug("astrum: passive UB charge {} — {} -> {} ({} pts x{} = {}/s)", 
                player.getGameProfile().getName(), before.charge(), step.charge(), step.points(), rate,
                perSecond);
    }

    /** One player's remainder dies with their connection. */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        carry.remove(event.getEntity().getUUID());
    }

    /** One world's remainders are not the next world's; they die with the session. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        carry.clear();
    }
}
