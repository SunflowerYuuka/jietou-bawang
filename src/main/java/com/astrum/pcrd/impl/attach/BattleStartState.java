package com.astrum.pcrd.impl.attach;

import org.jetbrains.annotations.ApiStatus;

/**
 * One player's <b>fight boundary</b>: the tick of the last blow she landed, and the one question the
 * {@code BATTLE_START} trigger asks of it — "is this blow the first of a new fight?".
 *
 * <h2>Why this is a record with its own arithmetic, like {@link ComboState}</h2>
 * 「战斗开始」 has to be recognised without a clock, a scheduled timer or a tick listener: the state is the
 * tick of the previous blow, and the answer is one subtraction performed when the next blow lands. A fight
 * therefore "ends" when somebody looks and the gap is too large — the same lazy-state shape the combo chain
 * and the stack table use, and the reason an idle player costs nothing.
 *
 * <h2>Why the anchor is the previous <b>blow</b>, and not the previous <b>fight start</b></h2>
 * If the stored tick were the last fight's <em>start</em>, a fight that lasted longer than the window would
 * begin a "new fight" every window while the player was still hitting the same mob — a buff that ticks, not an
 * opening. Anchoring on the last blow makes the rule "a fight begins when blows stop for a while and then
 * resume", so a continuous exchange begins exactly once however long it runs.
 *
 * <h2>The state is per player, not per target</h2>
 * A fight is the player's engagement, not one mob's: hitting a second enemy two seconds after the first is the
 * same fight. That is also why this lives on the player, beside {@code COMBO}, rather than on a target.
 *
 * <h2>Identity, and what it deliberately is not</h2>
 * {@code EMPTY} (a new player entity) answers "yes, a fight begins" to the very first blow, which is what makes
 * a respawn a new fight — and death has already cleared the effect instances the marks lived in, so that is a
 * reset rather than a gain. The state is runtime-only ({@code Attachments.BATTLE_START} is
 * {@code serialize = false}), so a relog also starts fresh; see that field for the abuse this leaves and what
 * bounds it.
 *
 * @param lastBlowTick the {@code Level.getGameTime()} of the last blow this player landed, or
 *                     {@link Long#MIN_VALUE} for "none yet"
 */
@ApiStatus.Internal
public record BattleStartState(long lastBlowTick) {

    /**
     * No blow landed yet, anchored before any real tick.
     *
     * <p>The sentinel is {@link Long#MIN_VALUE} so that the subtraction overflows rather than producing an
     * enormous "age": both readings answer "a new fight", but the overflow path is tested and the huge-number
     * path would need a second rule. {@link ComboState#EMPTY} uses the same anchor for the same reason.
     */
    public static final BattleStartState EMPTY = new BattleStartState(Long.MIN_VALUE);

    /**
     * {@return whether a blow landed at {@code nowTick} opens a <b>new</b> fight}
     *
     * <p>True when no blow is on record, when the gap is at least {@code windowTicks}, and when the tick went
     * backwards (a state from another timeline is not a fight in progress). Strictly-under the window is the
     * same fight, which is what makes a stream of blows one opening.
     *
     * <p>A non-positive window answers {@code true}: the gap between two blows is always at least zero, so no
     * fight can span two of them — the same reading {@link ComboState} gives a non-positive window ("no combo
     * can span two hits"). The shipped window is a positive constant
     * ({@code TriggerRuntime.BATTLE_START_WINDOW_TICKS}), so this branch is only reachable by editing it.
     *
     * @param nowTick     the tick of this blow
     * @param windowTicks how long blows have to stop for the next one to open a new fight
     */
    public boolean beginsAt(long nowTick, int windowTicks) {
        if (windowTicks <= 0) {
            return true;
        }
        long age;
        try {
            age = Math.subtractExact(nowTick, lastBlowTick);
        } catch (ArithmeticException overflow) {
            return true;   // the sentinel, or a tick from another timeline: no fight is in progress
        }
        return age < 0 || age >= windowTicks;
    }

    /**
     * {@return the state after a blow landed at {@code nowTick}}
     *
     * <p>Every qualifying blow moves the anchor, including one that did not open a fight — that is what keeps
     * a long fight from re-opening at each window.
     *
     * @param nowTick the tick of this blow
     */
    public BattleStartState noteBlow(long nowTick) {
        return new BattleStartState(nowTick);
    }
}