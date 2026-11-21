package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.BattleStartState;
import com.astrum.pcrd.impl.attach.ComboState;
import com.astrum.pcrd.impl.config.ServerConfig;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * The runtime of the non-damage trigger state: the {@code combo_n} chain, the {@code battle_start} boundary,
 * and the config the first is judged against (spec §4.4's lazy-state pattern; §8.2's {@code comboWindowTicks}).
 *
 * <h2>Why the two pieces of state live in attachments and not fields</h2>
 * Both are per player, so they belong in per-player state — {@code Attachments.COMBO} and
 * {@code Attachments.BATTLE_START}, both {@code serialize = false} and therefore never written to a save file.
 * A static map here would survive world reloads, outlive the players in it, and be invisible to every test that
 * does not reset it: the guide's R8 "no static mutable state holding per-entity data" exists exactly for this
 * shape.
 *
 * <h2>Why there is still no tick</h2>
 * {@link #noteHit} is called from the damage path — a player hit something — and does three things: read the
 * chain, let {@link ComboState} decide whether the gap broke it, write it back. The chain "lapses" when the
 * next hit looks at it, not when a timer fires. An idle player costs nothing. {@link #noteBattleStart} is the
 * same shape for the fight boundary: one read, one comparison, one write, on the blow that already happened.
 *
 * <h2>The two windows are deliberately different numbers</h2>
 * {@code comboWindowTicks} (2 s by default) is "did this player keep swinging rhythmically", and it is an
 * operator knob because a combo's feel is a server-policy question. The fight window is
 * {@link #BATTLE_START_WINDOW_TICKS} (30 s) and is a <b>constant</b>: it decides whether a stalling player gets
 * another mark grant, which is a balance rule and not an operation one. One window cannot serve both — using
 * the combo's 2 s would hand out four marks on almost every swing — so the two rules stay separate rather than
 * sharing a number that would quietly mean two things.
 */
@ApiStatus.Internal
public final class TriggerRuntime {

    /**
     * How long blows have to stop for the next one to open a <b>new</b> fight: 600 ticks (30 s).
     *
     * <h2>Why 30 seconds</h2>
     * Long enough that a real exchange cannot lapse during it (a combo feels broken after 2 s; a fight does not
     * end after 2 s), and short enough that "walk away, come back" is a deliberate pause rather than the default
     * rhythm of combat. It is also the smallest round value that comfortably exceeds the longest plausible
     * in-fight gap a player produces while still hitting — switching targets, eating, repositioning — so a
     * single engagement is not read as several.
     *
     * <h2>Why a constant, and not {@code ServerConfig}</h2>
     * Two reasons. §8.2 declares exactly two tunable windows ({@code comboWindowTicks} and the stack window),
     * which is the operator's set; and this number is the gate on a <b>balance</b> quantity — how often 雪菲 can
     * refill her marks by pausing. A server admin should not be able to turn a battle-start grant into a
     * per-blow one, and a constant is the one statement of the rule that no config edit can contradict.
     * {@link TriggerFacts#LOW_HEALTH_FRACTION} is a constant for the same reason.
     */
    public static final int BATTLE_START_WINDOW_TICKS = 600;

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

    /**
     * Records that {@code attacker} landed a blow, and answers whether that blow <b>opened a fight</b>.
     *
     * <h2>What this is, in one sentence</h2>
     * "A fight begins with the first blow after blows stopped for
     * {@link #BATTLE_START_WINDOW_TICKS}" — {@link BattleStartState#beginsAt} is the arithmetic, this method is
     * the one read/write of the attachment. It is called from exactly one place, the damage path that already
     * runs the character wheel ({@code CombatEvents.onDamageLanded}), so a login, a respawn or a dimension
     * change <b>cannot</b> fire the trigger: there is no second caller to define "a fight" differently.
     *
     * <p>Every qualifying blow updates the anchor, including a blow in the middle of a fight. That is the half
     * that makes a long fight one opening rather than one per window.
     *
     * @param attacker the player who landed the blow
     * @return {@code true} only on the blow that opens a fight
     */
    public static boolean noteBattleStart(ServerPlayer attacker) {
        long now = attacker.level().getGameTime();
        BattleStartState before = attacker.getData(Attachments.BATTLE_START);
        boolean begins = before.beginsAt(now, BATTLE_START_WINDOW_TICKS);
        attacker.setData(Attachments.BATTLE_START, before.noteBlow(now));
        return begins;
    }
}
