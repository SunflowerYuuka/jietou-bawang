package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.attach.Party;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/**
 * "May this player cast slot N right now?", as a pure decision (spec §5.4).
 *
 * <h2>Why this is a pure function and not a method on a service</h2>
 * The answer must be non-negotiable in both directions: the ultimate fires only on a full gauge, off
 * cooldown, from a slot that actually holds a character the pack still defines — and every other
 * answer must be <b>typed</b>, because 禁止静默失败 means the client needs a reason to shake the slot
 * and play a sound for. Testing that without a server, a player or a config file is only possible if
 * the decision takes plain values, which is what {@link Facts} is.
 *
 * <p>The record deliberately carries booleans ("is the slot occupied", "does the pack define the
 * character") rather than the party list and the character registry: the caller resolves both, so this
 * class has no Minecraft types and no registry access, and cannot accidentally index the party with a
 * slot it has not range-checked.
 *
 * <p><b>The cooldown in {@link Facts} is the requested slot's character's own.</b> The caller resolves
 * it ({@code UbState.cooldownOf(characterId)}), because this class does not know how cooldowns are
 * keyed. That is what keeps the class free of the keying rule: it is handed one deadline and compares
 * it with the clock, and the "whose deadline" question is answered once, on the reader's side.
 *
 * @see Reason
 */
@ApiStatus.Internal
public final class CastGate {

    private CastGate() {
    }

    /**
     * Everything the cast decision needs, already resolved by the caller.
     *
     * @param slot      the requested wheel slot, expected in {@code [0, 5)}
     * @param occupied  whether the party holds a character in that slot
     * @param known     whether the pack still defines that character
     * @param charge    the gauge, {@code 0..100}; only exactly {@code 100.0} is full
     * @param nowTick   the current game time
     * @param cdEndTick the tick <b>that slot's own character's</b> cooldown ends, from
     *                  {@code UbState.cooldownOf(character).endTick()}; the end tick is already usable
     */
    public record Facts(int slot, boolean occupied, boolean known, double charge, long nowTick,
            long cdEndTick) {
    }

    /** Why a cast was refused. Each value maps to a distinct client-side shake/sound. */
    public enum Reason {
        /** The slot is outside the wheel. */
        SLOT_OUT_OF_RANGE,
        /** The slot holds no character. */
        SLOT_EMPTY,
        /** The slot names a character the datapack no longer defines. */
        CHARACTER_UNKNOWN,
        /** The gauge is not exactly full. */
        NOT_CHARGED,
        /**
         * This slot's character is still on her own cooldown.
         *
         * <p>「每个角色UB的CD不共享」 — the deadline is read for the character in the requested slot, so
         * this refusal says "she is still cooling", never "the wheel is cooling". A cast from another
         * slot whose character is ready passes while this one is refused, and that is the intended
         * consequence of the human's request rather than a bug: see {@code UbState}'s javadoc.
         */
        COOLDOWN
    }

    /**
     * {@return an empty optional when the cast may proceed, or the first problem found}
     *
     * <p>Checks run in a fixed order — range, occupancy, definition, gauge, cooldown — so that when
     * several fail at once the answer is stable and the UI agrees with the log. Any other order shows
     * a cooldown shake to a player whose real problem is an empty slot.
     *
     * @param facts the resolved inputs
     */
    public static Optional<Reason> refusal(Facts facts) {
        if (facts.slot() < 0 || facts.slot() >= Party.SLOTS) {
            return Optional.of(Reason.SLOT_OUT_OF_RANGE);
        }
        if (!facts.occupied()) {
            return Optional.of(Reason.SLOT_EMPTY);
        }
        if (!facts.known()) {
            return Optional.of(Reason.CHARACTER_UNKNOWN);
        }
        if (!ChargeKernel.isFull(facts.charge())) {
            return Optional.of(Reason.NOT_CHARGED);
        }
        if (facts.nowTick() < facts.cdEndTick()) {
            return Optional.of(Reason.COOLDOWN);
        }
        return Optional.empty();
    }
}
