package com.astrum.pcrd.impl.attach;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>One character's</b> ultimate cooldown: the tick it ends, and the length it was given.
 *
 * <h2>Why the length is stored and not looked up</h2>
 * 「每个角色UB的CD不共享」 — the cooldown is per character now, and the HUD draws it as shade that
 * covers her face completely at the start and clears exactly when she is castable again
 * (「从阴影全覆盖，到没有阴影」). That drawing needs a <em>denominator</em>: "how much of the shade is
 * left" is {@code remaining / total}, and a denominator has to be the length this cooldown was
 * actually armed with — {@code ServerConfig}'s current value is not that. An operator who lowers
 * {@code ubGlobalCooldownTicks} while a cooldown runs would otherwise make the shade jump: the old
 * deadline with a new divisor.
 *
 * <p>Storing it also makes the client's arithmetic <b>stateless</b> and exact. Before this record the
 * client had to <em>observe</em> a total ({@code CooldownTracker} kept a high-water mark of the
 * remaining ticks it had seen for a given end tick), because the packet carried a deadline and nothing
 * else — so a client that joined mid-cooldown, or first saw a second character's cooldown late, drew
 * its ring against a total it could not know. A stale divisor is tolerable for a ring sweep; it is not
 * tolerable for a shade that is meant to say "she is castable again in exactly this long".
 *
 * <h2>Why an invalid value is clamped rather than rejected</h2>
 * {@link UbState}'s codec <b>rejects</b> a bad {@code charge} or {@code selectedSlot}, because a
 * non-finite charge silently destroys the character's core mechanic and a bad slot index throws during
 * rendering. A nonsense deadline has neither property: it cannot crash anything (nothing indexes with
 * it) and it cannot disable a mechanic — the worst it can do is make one character castable one tick
 * early. So negatives are <b>normalised to {@code 0}</b> here instead of failing the decode, because a
 * decode failure drops the whole {@code UB_STATE} attachment: the player would lose the charge bar to
 * punish a corrupt timestamp. That is the asymmetry, stated rather than left implicit.
 *
 * @param endTick    game time at which this character becomes castable again; {@code 0} means "never
 *                   cast, so ready"; clamped to non-negative
 * @param totalTicks the cooldown length this deadline was armed with, used as the shade's denominator;
 *                   clamped to non-negative
 */
@ApiStatus.Internal
public record UbCooldown(long endTick, long totalTicks) {

    /** A cooldown that has already run out, i.e. "nothing to draw". */
    public static final UbCooldown NONE = new UbCooldown(0L, 0L);

    public UbCooldown {
        endTick = Math.max(0L, endTick);
        totalTicks = Math.max(0L, totalTicks);
    }

    /** Disk and payload codec: two longs, named, so a save is readable by a human. */
    public static final Codec<UbCooldown> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.LONG.optionalFieldOf("endTick", 0L).forGetter(UbCooldown::endTick),
            Codec.LONG.optionalFieldOf("totalTicks", 0L).forGetter(UbCooldown::totalTicks)
    ).apply(instance, UbCooldown::new));

    /**
     * {@return {@code true} if this cooldown has run out at {@code nowTick}}
     *
     * <p>Lazy: nothing polls it. Expiry is asked about at the moment somebody wants to cast or draw,
     * which is the project's "no tick listener" rule applied to a deadline instead of a countdown.
     *
     * @param nowTick the current game time
     */
    public boolean isOver(long nowTick) {
        return nowTick >= endTick;
    }

    /**
     * {@return how much of the cooldown is still to run, {@code 1.0} at its start and {@code 0.0} once
     *         it is over}
     *
     * <p>This is the shade's own quantity (「阴影逐渐覆盖」), so it is defined once, here, rather than
     * in the drawing code: {@code remaining / total}, clamped to {@code [0, 1]}. It reaches exactly
     * {@code 0.0} on {@code endTick} — the same tick {@link #isOver} turns true — so the shade is gone
     * on the tick she is castable again, which is the whole request.
     *
     * <p>A zero or negative total answers {@code 0.0} rather than dividing by zero: the only way to get
     * one is a configured cooldown of {@code 0} ("no cooldown"), and then there is nothing to shade.
     *
     * @param nowTick the current game time
     */
    public double remainingFraction(long nowTick) {
        if (totalTicks <= 0L || isOver(nowTick)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) (endTick - nowTick) / (double) totalTicks));
    }
}
