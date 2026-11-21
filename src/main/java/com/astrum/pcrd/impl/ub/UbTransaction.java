package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.attach.UbCooldown;
import com.astrum.pcrd.impl.attach.UbState;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The cast transaction: what an accepted cast pays, what a refused one changes, and the order the
 * three side effects run in.
 *
 * <h2>Why the transaction is its own class</h2>
 * This is the one path in the mod where a player's property is spent, and it used to be verifiable only
 * by reading {@code UbService.tryCast}: "accepted ⇒ bar exactly {@code 0.0}, <b>that character's</b>
 * cooldown ending at {@code now + ubGlobalCooldownTicks} while every other character's is untouched,
 * event posted once, sink called once; refused ⇒ nothing changes" was a claim about statements in a
 * method that needs a {@link net.minecraft.server.level.ServerPlayer} to run.
 * Two plausible slips break that contract without failing anything: writing the new gauge as a
 * subtraction, and posting the event before the write. Both are pinned now, because both halves live
 * here as plain functions:
 *
 * <ul>
 *   <li>{@link #decide} answers <em>what</em> an accepted cast writes (and that a refusal writes
 *       nothing), from plain facts and the configured cooldown — no player, no level, no registry.</li>
 *   <li>{@link #commit} answers <em>in which order</em> the effects happen, through three callbacks the
 *       caller supplies: write the state, announce it, settle the showcase.</li>
 * </ul>
 *
 * <p>It holds no Minecraft types and no state of its own, so {@code UbTransactionTest} drives every
 * branch of it. What it does not cover: that {@code UbService} passes the real player's data and the
 * real bus into it — that wiring is still one call each, and the report says so.
 *
 * @see CastGate for the refusal reasons themselves
 */
@ApiStatus.Internal
public final class UbTransaction {

    /**
     * The decision about one cast attempt.
     *
     * <p>Exactly one half is present: a refusal with its reason, or the state an accepted cast must
     * write. The invariant is enforced here rather than documented, because an outcome that is both or
     * neither would let a caller pay for a refused cast.
     *
     * @param refusal the typed reason the cast may not happen, or empty when it may
     * @param paid    the exact state to write on acceptance, or empty when the cast was refused
     */
    public record Outcome(Optional<CastGate.Reason> refusal, Optional<UbState> paid) {

        public Outcome {
            if (refusal.isPresent() == paid.isPresent()) {
                throw new IllegalArgumentException(
                        "an outcome is either a refusal or a payment, never both and never neither");
            }
        }

        /** {@return whether the cast may proceed} */
        public boolean accepted() {
            return refusal.isEmpty();
        }
    }

    private UbTransaction() {
    }

    /**
     * {@return the refusal, or the exact state an accepted cast writes}
     *
     * <p>The paid state is, precisely: the bar emptied to {@code 0.0} (a full bar, no partial spend —
     * spec §5.4), the slot that fired remembered as the selection, and <b>that slot's character's</b>
     * cooldown armed at {@code nowTick + cooldownTicks} while every other character's cooldown is
     * carried over untouched (the end tick is already usable, see {@link CastGate.Facts}).
     *
     * <p>The character key comes from {@code characterId}, not from the slot: the slot is where she was
     * standing when the cast happened, and the party can be edited afterwards. Keying on the character
     * is exactly what makes A's cooldown survive her being moved to another wheel slot — and what makes
     * two different characters in two slots independent, which is the request
     * (「每个角色UB的CD不共享」).
     *
     * @param facts         the resolved cast inputs; the cooldown check inside them is the one for
     *                      {@code characterId}
     * @param cooldownTicks the configured cooldown length, {@code ubGlobalCooldownTicks}; it is now the
     *                      length each character's own cooldown is given, not one shared deadline
     * @param characterId   the character in {@code facts.slot()}, as the caller resolved her
     * @param before        the player's state before the cast, whose other cooldowns are carried over
     */
    public static Outcome decide(CastGate.Facts facts, int cooldownTicks,
            ResourceLocation characterId, UbState before) {
        Optional<CastGate.Reason> refusal = CastGate.refusal(facts);
        if (refusal.isPresent()) {
            return new Outcome(refusal, Optional.empty());
        }
        UbCooldown armed = new UbCooldown(facts.nowTick() + cooldownTicks, cooldownTicks);
        return new Outcome(Optional.empty(),
                Optional.of(before.withCharge(0.0f, facts.slot()).withCooldown(characterId, armed)));
    }

    /**
     * Performs the three effects of one accepted cast, once each, in the only safe order:
     * <b>write, then announce, then settle</b>.
     *
     * <p>Why that order and not another:
     *
     * <ul>
     *   <li>The write comes first because the event says "it fired" — a listener that reads the gauge
     *       must see it emptied, not the bar the player has already paid.</li>
     *   <li>The announcement comes before the showcase because it is the cheap, cancellable-by-nobody
     *       notification; the showcase is the expensive part and a listener must be able to see the
     *       cast before its visuals exist.</li>
     *   <li>Every effect happens exactly once per accepted cast: this method is the only place they are
     *       sequenced, and it is called once per accepted cast.</li>
     * </ul>
     *
     * @param paid     the state from {@link #decide}, written verbatim
     * @param write    writes the paid state to the player's {@code UB_STATE}
     * @param announce posts the {@code UbActivatedEvent}
     * @param settle   hands the cast to the showcase sink
     */
    public static void commit(UbState paid, Consumer<UbState> write, Runnable announce,
            Runnable settle) {
        write.accept(paid);
        announce.run();
        settle.run();
    }
}
