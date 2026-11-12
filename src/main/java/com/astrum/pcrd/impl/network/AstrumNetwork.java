package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.UbState;
import com.astrum.pcrd.impl.client.ClientCastFeedback;
import com.astrum.pcrd.impl.client.ClientUbCache;
import com.astrum.pcrd.impl.ub.CastGate;
import com.astrum.pcrd.impl.ub.SyncGate;
import com.astrum.pcrd.impl.ub.UbService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.ApiStatus;

/**
 * The mod's payloads, all registered here, on both sides: the charge channel (one clientbound
 * payload, pushed to a player only when the server's copy of their gauge actually changed), the UB
 * cast request ({@link UbCastPayload}, serverbound) and its typed refusal
 * ({@link UbCastFeedbackPayload}, clientbound, back to that one player).
 *
 * <h2>The cast pair: one request up, one reason down</h2>
 * The client says only <em>which slot</em>; {@link UbService#tryCast} re-decides everything on the
 * server and answers through {@link CastGate.Reason}. A <b>success sends nothing back</b>: the gauge
 * push above already carries the cleared bar and the new cooldown, so a second packet would say the
 * same thing twice. A <b>refusal is answered on the same connection, to the asking player only</b>
 * ({@code PacketDistributor.sendToPlayer}), never broadcast — spec §5.4's 禁止静默失败 without
 * letting one player's cooldown shake another's slot.
 *
 * <h2>Why the registration is one call, on the mod bus, in common code</h2>
 * {@link RegisterPayloadHandlersEvent} is an {@code IModBusEvent} fired by
 * {@code NetworkRegistry.setup()} through {@code ModLoader.postEvent}, on every physical side that
 * loads the mod. Subscribing from {@link #register(IEventBus)} — called from the {@code @Mod}
 * constructor, i.e. from common code — therefore registers the payload in the client's
 * {@code NetworkRegistry} <em>and</em> the dedicated server's. That is what spec §9.5's
 * "payload 两端都注册" requires: a payload registered only in a client-only class would be unknown to
 * the server that has to encode it, and vice versa. There is deliberately no
 * {@code FMLEnvironment.dist} check here, because skipping the call on the server would break
 * exactly that.
 *
 * <p>The handlers are references to common methods whose bodies touch only {@link ClientUbCache} and
 * {@link ClientCastFeedback} — classes with no {@code net.minecraft.client} imports — so loading this
 * class on a dedicated server cannot pull client-only types in. The two {@code playToClient} handlers
 * are only ever invoked on the client, for that reason; the {@code playToServer} one runs on the
 * server and touches only server types.
 *
 * <h2>Why the push is here and not at the mutation site</h2>
 * Charge is mutated from several places (damage dealt, damage taken, a kill, later the
 * {@code gain_charge} effect). Sending from each of them would mean N packets for one tick's worth
 * of state, and would put the "is this worth a packet" question in N places. Instead the gauge is
 * written locally and this listener observes it: at most one push per player per 5-tick window,
 * only when {@link SyncGate} says the client does not already hold the value.
 */
@ApiStatus.Internal
public final class AstrumNetwork {

    /** Negotiated payload version; bump when any payload registered here changes shape. */
    public static final String PROTOCOL_VERSION = "1";

    /**
     * What each online player's client was last told, keyed by UUID.
     *
     * <p>Transient bookkeeping, not player state: the authoritative gauge lives in the
     * {@code UB_STATE} attachment and is persisted with the player. This map only answers "would
     * re-sending be redundant", so it is intentionally <b>not</b> saved and is dropped on logout
     * (a re-joining client starts from the default and must be re-sent). Only touched from server
     * callbacks, so a plain {@link HashMap} is enough.
     */
    private static final Map<UUID, Sent> LAST_SENT = new HashMap<>();

    private AstrumNetwork() {
    }

    /**
     * Wires this subsystem: payload registration on the mod bus, plus the server-side push loop on
     * the game bus.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, AstrumNetwork::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post.class, AstrumNetwork::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, AstrumNetwork::onLoggedOut);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // One registrar, both flows, no dist check: this call must run on the client and on the
        // dedicated server alike (see the class javadoc).
        event.registrar(PROTOCOL_VERSION)
                .playToClient(ChargeSyncPayload.TYPE, ChargeSyncPayload.STREAM_CODEC,
                        AstrumNetwork::handleChargeSync)
                .playToServer(UbCastPayload.TYPE, UbCastPayload.STREAM_CODEC,
                        AstrumNetwork::handleUbCast)
                .playToClient(UbCastFeedbackPayload.TYPE, UbCastFeedbackPayload.STREAM_CODEC,
                        AstrumNetwork::handleUbCastFeedback);
        Astrum.LOGGER.info("astrum: payloads registered (charge_sync, ub_cast, ub_cast_feedback), protocol {}",
                PROTOCOL_VERSION);
    }

    /**
     * Receives the server's gauge on the client.
     *
     * <p>No {@code context.enqueueWork} is needed: {@code PayloadRegistrar} wraps handlers in
     * {@code MainThreadPayloadHandler} by default ({@code HandlerThread.MAIN}), so this already runs
     * on the client's main thread.
     */
    private static void handleChargeSync(ChargeSyncPayload payload, IPayloadContext context) {
        ClientUbCache.accept(payload);
    }

    /**
     * Receives the client's cast request on the server and answers a refusal on the same connection.
     *
     * <p>Only the slot is taken from the packet: {@link UbService#tryCast} re-resolves the party, the
     * registry, the gauge and the cooldown, so nothing the client says here is trusted beyond "which
     * slot the player picked" (spec §9.5).
     *
     * <p>The refusal goes to <b>this</b> player and nobody else. It also needs no
     * {@code enqueueWork}: same {@code HandlerThread.MAIN} default as above, so the cast is decided on
     * the server thread, in the same tick the packet arrived.
     */
    private static void handleUbCast(UbCastPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            // Unreachable for a playToServer payload; if it ever happens the registration is wrong,
            // so say so rather than throwing into the packet handler.
            Astrum.LOGGER.warn("astrum: ub_cast arrived without a ServerPlayer (slot {}) — dropped",
                    payload.slot());
            return;
        }
        UbService.tryCast(player, payload.slot())
                .ifPresent(reason -> PacketDistributor.sendToPlayer(player,
                        new UbCastFeedbackPayload(reason)));
    }

    /**
     * Receives the server's refusal on the client.
     *
     * <p>Stamps the refusal with the client's own game time here rather than inside
     * {@link ClientCastFeedback}: the cache must stay a plain value holder so this class can be loaded
     * on a dedicated server without dragging a level (let alone a client level) into its types.
     */
    private static void handleUbCastFeedback(UbCastFeedbackPayload payload, IPayloadContext context) {
        ClientCastFeedback.accept(payload, context.player().level().getGameTime());
    }

    /**
     * The push loop. Runs once per player per tick on both logical sides, so the server check is the
     * {@code instanceof}: only a {@link ServerPlayer} owns an authoritative gauge.
     */
    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // getData, not getExistingData: a player who has not charged yet still owns a gauge, and its
        // value is the documented default. Reading it materialises that default, which is what makes
        // the first window after login push the real state — otherwise a client whose cache still
        // holds the previous world's charge (the same JVM switches worlds) would never be corrected.
        UbState state = player.getData(Attachments.UB_STATE);
        Sent last = LAST_SENT.getOrDefault(player.getUUID(), Sent.NEVER);
        if (!SyncGate.shouldSync(player.tickCount, last.tick(), state.charge(), state.cdEndTick(),
                last.charge(), last.cdEndTick())) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ChargeSyncPayload(state.charge(), state.cdEndTick()));
        LAST_SENT.put(player.getUUID(), new Sent(player.tickCount, state.charge(), state.cdEndTick()));
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    /**
     * What one client was last told.
     *
     * @param tick      the player's tick counter at the moment of the push
     * @param charge    the charge that was pushed
     * @param cdEndTick the cooldown end that was pushed
     */
    private record Sent(int tick, float charge, long cdEndTick) {
        /** The "never pushed" value: tick {@code -1} and a {@code NaN} charge, so the first window always sends. */
        static final Sent NEVER = new Sent(-1, Float.NaN, Long.MIN_VALUE);
    }
}
