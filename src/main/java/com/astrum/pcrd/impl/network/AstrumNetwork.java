package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.UbState;
import com.astrum.pcrd.impl.client.ClientUbCache;
import com.astrum.pcrd.impl.ub.SyncGate;
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
 * The charge channel: one clientbound payload, registered on both sides, pushed to a player only
 * when the server's copy of their gauge actually changed.
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
 * <p>The handler is a reference to a common method whose body touches only
 * {@link ClientUbCache}, a class with no {@code net.minecraft.client} imports, so loading this class
 * on a dedicated server cannot pull client-only types in. The handler itself is only ever invoked on
 * the client, because the payload is registered {@code playToClient}.
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

    /** Negotiated payload version; bump when {@link ChargeSyncPayload}'s shape changes. */
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
        event.registrar(PROTOCOL_VERSION).playToClient(
                ChargeSyncPayload.TYPE, ChargeSyncPayload.STREAM_CODEC, AstrumNetwork::handleChargeSync);
        Astrum.LOGGER.info("astrum: charge sync payload registered, protocol {}", PROTOCOL_VERSION);
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
