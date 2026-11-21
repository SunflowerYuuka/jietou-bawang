package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.UbCooldown;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "should a packet go out now" decision, tested as a pure function so it cannot hide inside a
 * listener body.
 *
 * <p>Two rules have to hold at once, and each has its own test:
 * <ul>
 *   <li><b>Only on change.</b> An unchanged gauge must not produce traffic, however many ticks
 *       pass.</li>
 *   <li><b>At most once per 5-tick window.</b> A gauge that changes every tick (it does, once the
 *       trickle is live) must still cost at most four packets per second, not twenty.</li>
 * </ul>
 *
 * <p>「每个角色UB的CD不共享」 turned the compared value from one {@code cdEndTick} into a map of
 * per-character deadlines, which adds three cases the old gate did not have and which are asserted
 * below: arming one character's cooldown is a change, a <em>different</em> character's arming is a
 * change even when the first character's deadline is untouched, and the map being replaced by an equal
 * one is not.
 *
 * <p>{@code lastSyncTick = -1}, {@code lastSentCharge = Float.NaN}, {@code lastSentDeadlines = null}
 * mean "this client has not been told anything yet"; the NaN charge compares unequal to every real
 * charge and the null map is never equal to a real one, so the first window always pushes.
 */
class SyncGateTest {

    private static final int NEVER = -1;
    private static final float NEVER_CHARGE = Float.NaN;
    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KYARU = InternalIds.id("kyaru");

    private static Map<ResourceLocation, UbCooldown> deadlines(Object... pairs) {
        Map<ResourceLocation, UbCooldown> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((ResourceLocation) pairs[i], (UbCooldown) pairs[i + 1]);
        }
        return map;
    }

    @Test void sendsWhenTheGaugeChanged() {
        assertTrue(SyncGate.shouldSync(10, 5, 12.5f, Map.of(), 10.0f, Map.of()));
    }

    /** Arming a character's cooldown must reach the client on the next window: it starts her shade. */
    @Test void sendsWhenACooldownWasArmed() {
        Map<ResourceLocation, UbCooldown> armed =
                deadlines(DJEETA, new UbCooldown(140L, 100L));
        assertTrue(SyncGate.shouldSync(10, 5, 10.0f, armed, 10.0f, Map.of()));
    }

    /** A cooldown walking forward (the server re-arming on a second cast) is a change too. */
    @Test void sendsWhenACooldownDeadlineMovedForward() {
        assertTrue(SyncGate.shouldSync(10, 5, 10.0f,
                deadlines(DJEETA, new UbCooldown(240L, 100L)),
                10.0f, deadlines(DJEETA, new UbCooldown(140L, 100L))));
    }

    /** The total is part of the compared value: a re-arm with a new length must be pushed. */
    @Test void sendsWhenOnlyTheCooldownTotalChanged() {
        assertTrue(SyncGate.shouldSync(10, 5, 10.0f,
                deadlines(DJEETA, new UbCooldown(140L, 200L)),
                10.0f, deadlines(DJEETA, new UbCooldown(140L, 100L))));
    }

    /**
     * An entry expiring is a change: the server drops it from the sent map, so the client is told she is
     * castable instead of holding a deadline it would have to expire for itself.
     */
    @Test void sendsWhenACooldownExpiredAndWasDropped() {
        assertTrue(SyncGate.shouldSync(10, 5, 10.0f, Map.of(),
                10.0f, deadlines(DJEETA, new UbCooldown(140L, 100L))));
    }

    /**
     * The case the old single-deadline gate could not express: the charge is identical and character A's
     * deadline is identical, but a <b>second</b> character went on cooldown.
     */
    @Test void sendsWhenOnlyASecondCharactersCooldownChanged() {
        Map<ResourceLocation, UbCooldown> before = deadlines(DJEETA, new UbCooldown(900L, 100L));
        Map<ResourceLocation, UbCooldown> after = deadlines(
                DJEETA, new UbCooldown(900L, 100L),
                KYARU, new UbCooldown(950L, 100L));
        assertTrue(SyncGate.shouldSync(10, 5, 100.0f, after, 100.0f, before));
    }

    @Test void doesNotSendWhenNothingChanged() {
        assertFalse(SyncGate.shouldSync(10, 5, 10.0f, Map.of(), 10.0f, Map.of()));
        assertFalse(SyncGate.shouldSync(10, 5, 10.0f,
                deadlines(DJEETA, new UbCooldown(140L, 100L)),
                10.0f, deadlines(DJEETA, new UbCooldown(140L, 100L))),
                "an equal map is not a change, whatever order it was built in");
    }

    /**
     * Even a changed gauge must not be pushed twice in one tick: two mutations inside the same tick
     * are one visible state, and the second call is what a careless second listener would do.
     */
    @Test void doesNotSendTwiceInTheSameTick() {
        assertFalse(SyncGate.shouldSync(10, 10, 12.5f, Map.of(), 10.0f, Map.of()));
    }

    /** Between windows the gate stays shut, so the next push lands on the next multiple of five. */
    @Test void doesNotSendBetweenWindows() {
        assertFalse(SyncGate.shouldSync(11, 5, 12.5f, Map.of(), 10.0f, Map.of()));
        assertTrue(SyncGate.shouldSync(15, 5, 12.5f, Map.of(), 10.0f, Map.of()));
    }

    /**
     * A fresh connection is owed the current state even when it is the default zero — including the
     * fact that <b>nothing is cooling</b>, which is why {@code null} rather than an empty map is the
     * "never told" sentinel.
     */
    @Test void firstPushAlwaysSends() {
        assertTrue(SyncGate.shouldSync(0, NEVER, 0.0f, Map.of(), NEVER_CHARGE, null));
        assertTrue(SyncGate.shouldSync(5, NEVER, 0.0f, Map.of(), NEVER_CHARGE, null));
        assertTrue(SyncGate.shouldSync(5, NEVER, 0.0f, Map.of(), 0.0f, null));
    }

    /**
     * The measurement that would fail if the gate sent every tick: 20 ticks of a gauge that changes
     * on every single tick. Four pushes, not twenty. A gate that ignored the interval — or a caller
     * that pushed from the mutation site instead of the window — reports 20 here.
     */
    @Test void twentyChangingTicksCostFourPackets() {
        int pushes = 0;
        int lastSyncTick = NEVER;
        float lastCharge = NEVER_CHARGE;
        Map<ResourceLocation, UbCooldown> lastDeadlines = null;
        for (int tick = 0; tick < 20; tick++) {
            float charge = tick; // changes every tick, like the live trickle does
            if (SyncGate.shouldSync(tick, lastSyncTick, charge, Map.of(), lastCharge, lastDeadlines)) {
                pushes++;
                lastSyncTick = tick;
                lastCharge = charge;
                lastDeadlines = Map.of();
            }
        }
        assertEquals(4, pushes, "one push per 5-tick window; 20 would mean a packet every tick");
    }

    /** And the mirror image: a still gauge costs nothing at all over the same twenty ticks. */
    @Test void twentyUnchangedTicksCostNothing() {
        int pushes = 0;
        int lastSyncTick = 5;
        float lastCharge = 42.0f;
        Map<ResourceLocation, UbCooldown> lastDeadlines =
                deadlines(DJEETA, new UbCooldown(999L, 100L));
        for (int tick = 5; tick < 25; tick++) {
            if (SyncGate.shouldSync(tick, lastSyncTick, 42.0f,
                    deadlines(DJEETA, new UbCooldown(999L, 100L)), lastCharge, lastDeadlines)) {
                pushes++;
                lastSyncTick = tick;
                lastCharge = 42.0f;
                lastDeadlines = deadlines(DJEETA, new UbCooldown(999L, 100L));
            }
        }
        assertEquals(0, pushes);
    }
}
