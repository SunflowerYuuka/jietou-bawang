package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.UbCastFeedbackPayload;
import com.astrum.pcrd.impl.ub.CastGate;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's memory of the last refused cast, so the HUD can shake the slot and play a sound.
 *
 * <h2>Why this holds no Minecraft client types</h2>
 * The payload handler that writes here is registered from common code, and registration must run on
 * both sides (spec §9.5: 自定义 payload 必须在两端都注册). If the handler touched anything under
 * {@code net.minecraft.client}, loading the handler class on a dedicated server would drag those types
 * in and fail. So this cache is a plain value holder — it lives in {@code impl/client} by intent, but
 * compiles and loads on either side. The renderer reads it; nothing here renders.
 *
 * <h2>What a refusal means for the player</h2>
 * Spec §5.4: 禁止静默失败. The server has already told us <em>why</em> ({@link CastGate.Reason}), and the
 * shake duration is a client concern, so the timestamp is stored as a raw game time the caller
 * compares against its own clock. No timer, no state machine, no tick.
 */
@ApiStatus.Internal
public final class ClientCastFeedback {

    /**
     * One immutable refusal: the reason and when it happened.
     *
     * @param reason  why the server refused the cast
     * @param gameTime the client game time the refusal arrived
     */
    public record Refusal(CastGate.Reason reason, long gameTime) {
    }

    /** Never null: "no refusal yet" is {@code null}, which every reader checks once. */
    private static volatile Refusal latest;

    private ClientCastFeedback() {
    }

    /**
     * Records a refusal. Called from the payload handler on the client main thread.
     *
     * @param payload the refusal the server sent
     * @param gameTime the client's current game time
     */
    public static void accept(UbCastFeedbackPayload payload, long gameTime) {
        latest = new Refusal(payload.reason(), gameTime);
    }

    /**
     * {@return the last refusal if it happened within {@code windowTicks}, otherwise empty}
     *
     * <p>Expiry is judged when asked, never on a tick — the same lazy rule the stack table obeys
     * (spec §4.4).
     *
     * @param now         the client's current game time
     * @param windowTicks how long the shake should last
     */
    public static java.util.Optional<CastGate.Reason> activeRefusal(long now, int windowTicks) {
        Refusal refusal = latest;
        if (refusal == null) {
            return java.util.Optional.empty();
        }
        long age = now - refusal.gameTime();
        if (age < 0 || age > windowTicks) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(refusal.reason());
    }
}
