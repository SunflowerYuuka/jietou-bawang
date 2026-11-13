package com.astrum.pcrd.impl.ub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recursion guard for one ultimate's damage: <b>exactly one settlement per cast</b>.
 *
 * <h2>The two ways a cast could settle twice, both real</h2>
 * <ol>
 *   <li><b>Two moments want it.</b> The strike frame is the moment the animation reads as landed, but
 *       a clip can be cut short — the entity is discarded when the owner logs out, when the server
 *       stops, when its chunk unloads, or when the hard deadline fires. So the settlement is armed in
 *       <b>two</b> places (the entity's own tick at the strike frame, and its removal) and exactly one
 *       of them must win. Without the guard, an interrupted clip would deal its damage twice.</li>
 *   <li><b>Re-entry.</b> The plan is applied with real {@code hurt()} calls, and a listener of the
 *       resulting damage events may itself run game code (a datapack's {@code damage_taken} lobby, a
 *       future effect). A settlement that could claim again <em>while it is running</em> would
 *       re-enter itself — the classic infinite recursion, and the reason the damage is fused out of
 *       hook evaluation as well ({@code UbDamage.DAMAGE_TYPE} is in the mod namespace).</li>
 * </ol>
 *
 * <p>This is a pure state machine on purpose: "settles once" is the property that cannot be seen in a
 * log (a double settlement looks like a normal multi-hit) and the one a future edit is most likely to
 * break by adding a third call site.
 */
class SettleOnceTest {

    /** The ordinary path: the strike frame claims it, the removal-time fallback gets nothing. */
    @Test
    void theFirstClaimWinsAndTheSecondGetsNothing() {
        SettleOnce once = new SettleOnce();

        assertTrue(once.claim(), "the first moment to reach the settlement runs it");
        once.done();
        assertFalse(once.claim(), "the fallback must not run it a second time");
    }

    /**
     * Re-entry: a claim made while the settlement is still running must be refused, or the damage
     * sequence would feed itself.
     */
    @Test
    void aClaimDuringTheRunIsRefused() {
        SettleOnce once = new SettleOnce();

        assertTrue(once.claim());
        assertFalse(once.claim(), "a re-entrant claim must be refused while the run is in progress");
        once.done();
        assertFalse(once.claim(), "and refused afterwards too");
    }

    /**
     * A settlement that <b>threw</b> still counts as spent.
     *
     * <p>This is the deliberate asymmetry with a rollback: the damage has already been applied (or
     * partially applied) when a later application throws, so re-running the whole plan would double
     * whatever landed before the failure. A cast's damage is settled once, whatever happened to it —
     * {@code done()} is idempotent and a failed run must still call it.
     */
    @Test
    void aFailedRunStillCountsAsSpent() {
        SettleOnce once = new SettleOnce();
        assertTrue(once.claim());
        // the caller's finally block:
        once.done();
        once.done();
        assertFalse(once.claim(), "a partially applied plan must not be applied again");
    }

    /** {@code isPending()} is what the arming code asks; it must flip at the claim, not at done. */
    @Test
    void pendingIsTrueUntilTheMomentOfClaim() {
        SettleOnce once = new SettleOnce();
        assertTrue(once.isPending(), "armed but not yet settled is the state the fallback waits in");
        assertTrue(once.claim());
        assertFalse(once.isPending(), "a running or finished settlement is no longer pending");
    }
}
