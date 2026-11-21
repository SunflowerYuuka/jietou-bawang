package com.astrum.pcrd.impl.attach;

import org.jetbrains.annotations.ApiStatus;

/**
 * <b>One channel's worth of our shield, with its own clock</b> — 由衣's physical barrier and 望's shared one
 * are two of these, each granted by its own cast, each expiring on its own schedule.
 *
 * <h2>Why the deadline is per layer</h2>
 * Two kinds on one player are two <em>different</em> skills with two different durations (由衣's 8 s design
 * value and 望's 19.5 s, from their bwikis). A single state-wide deadline would either cut a 19.5-second
 * barrier to 8 or extend an 8-second one to 19.5, depending on which cast came last. Carrying the deadline
 * in the layer means a layer stops being ours at exactly its own moment with no event, no listener and no
 * sweep — the same "expire lazily on read" trick {@link ShieldState#reconciledAgainst} already used for the
 * single pool.
 *
 * <h2>Why the amount is a float</h2>
 * Absorption is measured in health points against the receiver's max health (a fifth of a 3377-health boss is
 * 675), so it cannot travel in an integer. The tier lives in the instance's amplifier and the size lives
 * here; {@link com.astrum.pcrd.impl.effect.ShieldPool#granted} clamps it at vanilla's {@code MAX_ABSORPTION}
 * ceiling so the mirror can always show what we hold.
 *
 * @param kind          which half of the damage this layer absorbs — see {@link ShieldKind}
 * @param pool          the absorption this layer still holds, in health points
 * @param expiresAtTick the game time at which this layer stops being ours, in level ticks
 */
@ApiStatus.Internal
public record ShieldLayer(ShieldKind kind, float pool, long expiresAtTick) {

    public ShieldLayer {
        kind = kind == null ? ShieldKind.BOTH : kind;
        if (!Float.isFinite(pool) || pool < 0.0F) {
            pool = 0.0F;
        }
    }

    /** {@return this layer with its amount replaced, keeping its kind and clock} */
    public ShieldLayer withPool(float amount) {
        return new ShieldLayer(kind, amount, expiresAtTick);
    }

    /** {@return whether a hit classified as magic / physical is absorbed by this layer} */
    public boolean covers(boolean magic) {
        return kind.covers(magic);
    }

    /** {@return whether this layer is still ours at {@code now}} */
    public boolean isLive(long now) {
        return now < expiresAtTick && pool > 0.0F;
    }
}