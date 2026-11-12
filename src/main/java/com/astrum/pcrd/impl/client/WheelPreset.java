package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import org.jetbrains.annotations.ApiStatus;

/**
 * The slot the next <b>tap cast</b> fires: the wheel's "preset" (spec §5.5 — 鼠标移入某格切换预设，
 * 不释放，松手后生效).
 *
 * <h2>Why the client has to keep this</h2>
 * {@code UB_STATE.selectedSlot} exists on the server, but {@code ChargeSyncPayload} deliberately
 * carries only the bar and the cooldown, and the preset changes several times a second while the
 * player sweeps the wheel — it is an input in flight, not state worth a packet per hover. So it lives
 * here, client-side, and the server learns about it the moment it matters: from the slot number in
 * {@code UbCastPayload}.
 *
 * <h2>Not per-player state</h2>
 * One value for the one local player, like the other client caches; not persisted. A fresh install
 * starts on slot 0, which is the party's highest-priority slot — the documented default.
 */
@ApiStatus.Internal
public final class WheelPreset {

    /** Slot 0: the party's first, which is also the top of the wheel (spec §5.2). */
    public static final int DEFAULT_SLOT = 0;

    private static volatile int slot = DEFAULT_SLOT;

    private WheelPreset() {
    }

    /** {@return the slot a tap cast would fire, always in {@code [0, Party.SLOTS)}} */
    public static int slot() {
        return slot;
    }

    /**
     * Moves the preset.
     *
     * <p>Out-of-range input is ignored rather than clamped: a caller that computed a bad slot has a
     * bug, and silently firing slot 0 instead would hide it behind a cast that looks intended.
     *
     * @param slot slot index in {@code [0, Party.SLOTS)}
     */
    public static void select(int slot) {
        if (slot < 0 || slot >= Party.SLOTS) {
            return;
        }
        WheelPreset.slot = slot;
    }
}
