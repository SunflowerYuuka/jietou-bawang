package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.PartyEditResultPayload;
import com.astrum.pcrd.impl.party.PartyRules;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's memory of the last refused party edit, so the 角色册 screen can say why.
 *
 * <h2>Why a cache at all</h2>
 * Spec §5.5: 禁止静默失败. The player clicked a slot and the server said no; without somewhere to put
 * the reason, the click would simply do nothing. The screen is a modal that the player is looking at,
 * so a chat line would be behind it — the refusal has to be drawn <em>in</em> the screen, which means
 * the screen needs a value to read on every frame that does not depend on the packet arriving while it
 * is open.
 *
 * <h2>Why it holds no Minecraft client types</h2>
 * Same reason as {@link ClientCastFeedback}: the payload handler that writes here is registered from
 * common code, so it must load on a dedicated server. The timestamp is a raw game time the caller
 * compares against its own clock, and the "still showing" window is
 * {@link RefusalFeedback#isActive}'s one implementation — not a second copy.
 */
@ApiStatus.Internal
public final class ClientBookFeedback {

    /**
     * How long a refused edit stays on screen. Five seconds at 20 tps: long enough to read a sentence,
     * short enough that it is gone before the player has forgotten what they clicked.
     */
    public static final int WINDOW_TICKS = 100;

    /**
     * One immutable refusal.
     *
     * @param slot     the slot the player touched
     * @param reason   why the server refused
     * @param gameTime the client game time the refusal arrived
     */
    public record Refusal(int slot, PartyRules.Reason reason, long gameTime) {
    }

    /** Never null: "no refusal yet" is {@code null}, which the screen checks once. */
    private static volatile Refusal latest;

    private ClientBookFeedback() {
    }

    /**
     * Records a refusal. Called from the payload handler on the client main thread.
     *
     * @param payload  the refusal the server sent
     * @param gameTime the client's current game time
     */
    public static void accept(PartyEditResultPayload payload, long gameTime) {
        latest = new Refusal(payload.slot(), payload.reason(), gameTime);
    }

    /**
     * {@return the refusal still inside its window, or empty}
     *
     * @param now the client's current game time
     */
    public static Optional<Refusal> active(long now) {
        Refusal refusal = latest;
        if (refusal == null) {
            return Optional.empty();
        }
        if (!RefusalFeedback.isActive(now, refusal.gameTime(), WINDOW_TICKS)) {
            return Optional.empty();
        }
        return Optional.of(refusal);
    }

    /**
     * Forgets the last refusal, called when the player asks for something new: a stale "you do not own
     * that character" under a fresh click would read as the new answer.
     */
    public static void clear() {
        latest = null;
    }
}
