package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.ChargeSyncPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's mirror of its own {@code UB_STATE}, as far as the server has told it.
 *
 * <h2>Why a cache and not the attachment</h2>
 * {@code Attachments.UB_STATE} is a server-side attachment with no {@code .sync(...)}: the client
 * entity does not hold one, and giving it one would create a second, unsynchronised source of truth
 * for the same bar. The gauge travels as a purpose-built payload instead (spec §4.3), so the client
 * needs somewhere to put what arrives.
 *
 * <h2>Who writes, who reads</h2>
 * Written only by {@code AstrumNetwork}'s payload handler, on the client main thread — the registrar
 * wraps handlers in {@code MainThreadPayloadHandler}, so no locking is needed beyond the single
 * volatile reference that keeps a reader from seeing a half-updated pair. Read by the HUD and the
 * wheel (Task 12).
 *
 * <h2>What it is not</h2>
 * Not authoritative: the server owns the gauge, and a client that disagreed would simply be wrong.
 * Nothing in this class is permitted to feed a decision back to the server (a cast request is
 * re-validated server-side, spec §5.5).
 */
@ApiStatus.Internal
public final class ClientUbCache {

    /**
     * The last state the server pushed, or the fresh default before the first payload arrives.
     *
     * <p>One volatile reference rather than two fields: the pair is only meaningful together, and a
     * reader testing "charge says 100 but the cooldown still says the previous cast" would be reading
     * two different packets.
     */
    private static volatile ChargeSyncPayload latest = new ChargeSyncPayload(0.0f, 0L);

    private ClientUbCache() {
    }

    /**
     * Records a push from the server.
     *
     * @param payload the state the server just sent
     */
    public static void accept(ChargeSyncPayload payload) {
        latest = payload;
    }

    /** {@return the last state the server pushed, never {@code null}} */
    public static ChargeSyncPayload latest() {
        return latest;
    }

    /** {@return the charge the server last reported} */
    public static float charge() {
        return latest.charge();
    }

    /** {@return the tick the server last reported as the end of the shared cooldown} */
    public static long cdEndTick() {
        return latest.cdEndTick();
    }
}
