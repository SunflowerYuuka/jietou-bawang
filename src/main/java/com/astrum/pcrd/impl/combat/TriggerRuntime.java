package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.ComboState;
import com.astrum.pcrd.impl.config.ServerConfig;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * The runtime of the non-damage trigger state: the {@code combo_n} chain, and the config it is judged
 * against (spec §4.4's lazy-state pattern; §8.2's {@code comboWindowTicks}).
 *
 * <h2>Why the chain lives in an attachment and not a field</h2>
 * A chain is per player, so it belongs in per-player state — {@code Attachments.COMBO}, which is
 * {@code serialize = false} and therefore never reaches a save file. A static map here would survive
 * world reloads, outlive the players in it, and be invisible to every test that does not reset it: the
 * guide's R8 "no static mutable state holding per-entity data" exists exactly for this shape.
 *
 * <h2>Why there is still no tick</h2>
 * {@link #noteHit} is called from the damage path — a player hit something — and does three things: read
 * the chain, let {@link ComboState} decide whether the gap broke it, write it back. The chain "lapses"
 * when the next hit looks at it, not when a timer fires. An idle player costs nothing.
 */
@ApiStatus.Internal
public final class TriggerRuntime {

    private TriggerRuntime() {
    }

    /**
     * Records one landed hit by {@code attacker} and returns the chain length including it.
     *
     * <p>An empty or lapsed chain becomes 1; a live one increments. The returned number is what a
     * {@code combo_n} hook compares against its {@code hits} parameter.
     *
     * @param attacker the player who landed the hit
     */
    public static int noteHit(ServerPlayer attacker) {
        long now = attacker.level().getGameTime();
        ComboState before = attacker.getData(Attachments.COMBO);
        ComboState after = before.noteHit(now, comboWindowTicks());
        attacker.setData(Attachments.COMBO, after);
        return after.hits();
    }

    /** {@return the chain length still alive for {@code attacker}, or {@code 0} when it has lapsed} */
    public static int comboHits(ServerPlayer attacker) {
        return attacker.getData(Attachments.COMBO)
                .hitsAt(attacker.level().getGameTime(), comboWindowTicks());
    }

    /** {@return the configured combo window, in ticks, read fresh from the server config} */
    public static int comboWindowTicks() {
        return ServerConfig.BALANCE.comboWindowTicks.get();
    }
}
