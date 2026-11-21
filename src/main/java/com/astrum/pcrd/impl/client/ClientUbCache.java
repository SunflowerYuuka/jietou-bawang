package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.UbCooldown;
import com.astrum.pcrd.impl.network.ChargeSyncPayload;
import net.minecraft.resources.ResourceLocation;
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
 * <h2>What "the cooldown" means here now</h2>
 * There is no single one. 「每个角色UB的CD不共享」 — the payload carries one deadline per character
 * still cooling, so every reader must name <em>which</em> character she is asking about. There is
 * deliberately no {@code cdEndTick()} convenience any more: it would have to guess a character, and the
 * only two candidates (the preset slot, the last-cast slot) disagree exactly when it matters.
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
     * <p>One volatile reference rather than several fields: the values are only meaningful together, and
     * a reader testing "charge says 100 but the deadlines still say the previous cast" would be reading
     * two different packets.
     */
    private static volatile ChargeSyncPayload latest = new ChargeSyncPayload(0.0f, java.util.Map.of());

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

    /**
     * {@return that character's cooldown as the server last reported it, or
     *         {@link UbCooldown#NONE} when she is not cooling}
     *
     * <p>The single reader every client-side cooldown question goes through — the HUD's ring, the shade
     * over her face and the "ready" outline all ask here, so they cannot disagree. See
     * {@link ChargeSyncPayload#deadlineOf} for why a character absent from the payload is "ready"
     * rather than an error.
     *
     * @param characterId the character, e.g. {@code astrum:djeeta}; {@code null} answers
     *                    {@link UbCooldown#NONE}
     */
    public static UbCooldown cooldownOf(ResourceLocation characterId) {
        return latest.deadlineOf(characterId);
    }
}
