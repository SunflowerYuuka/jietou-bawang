package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.UbCastEffectPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's memory of the last ultimate that fired, so {@link UbEffectOverlay} can draw and fade it.
 *
 * <h2>Why this holds no Minecraft client types</h2>
 * The payload handler that writes here is registered from common code and must load on a dedicated
 * server too (spec §9.5: 自定义 payload 必须在两端都注册). Touching anything under {@code net.minecraft.client}
 * from this class would drag those types into the server's class loader, so it is a plain value holder:
 * the timestamp is a raw game time the renderer compares against its own clock, exactly like
 * {@link ClientCastFeedback}'s refusal.
 *
 * <h2>One effect at a time, deliberately</h2>
 * A new cast <b>replaces</b> the running effect rather than queueing behind it. The server's global
 * cooldown is 100 ticks and the effect lasts 20, so two casts cannot overlap in a real fight; if they ever
 * did (a command, a config of zero cooldown), showing the newer one is the honest answer — a queue would
 * replay a cast whose damage landed a second ago and read as lag.
 *
 * <p>Nothing here decides anything: the server has already settled the damage by the time the payload
 * arrives. This class exists so the renderer has a single place to read "what, and since when" from.
 */
@ApiStatus.Internal
public final class ClientCastEffect {

    /**
     * One cast, as the overlay needs it.
     *
     * @param characterId the character whose ultimate fired, as the wire carried it
     * @param enhanced    whether the enhanced (affection tier 3) tier fired
     * @param gameTime    the client game time the payload arrived — the fade's zero
     */
    public record Cast(String characterId, boolean enhanced, long gameTime) {
    }

    /** {@code null} means "nothing has fired yet in this session". */
    private static volatile Cast latest;

    /** How many effects this client has been told about, for the probe's log line. */
    private static volatile int received;

    private ClientCastEffect() {
    }

    /**
     * Records a cast effect. Called from the payload handler on the client main thread.
     *
     * @param payload  what the server sent
     * @param gameTime the client's current game time
     */
    public static void accept(UbCastEffectPayload payload, long gameTime) {
        latest = new Cast(payload.artName(), payload.enhanced(), gameTime);
        received++;
    }

    /**
     * Forgets the running effect, so a world change cannot replay one.
     *
     * <p>Called when the client leaves a world: the game time of the old world means nothing in the new
     * one, and a stale timestamp would either be far in the future (the effect never starts) or far in the
     * past (it starts over). Both are bugs that look like a rendering fault.
     */
    public static void clear() {
        latest = null;
    }

    /** {@return the last cast that fired, or {@code null} when nothing has fired yet} */
    public static Cast latest() {
        return latest;
    }

    /** {@return how many effect cues this client has received, for the probe and for the log} */
    public static int received() {
        return received;
    }
}
