package com.astrum.pcrd.impl.attach;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.ApiStatus;

/**
 * UB runtime state: how charged the bar is, which wheel slot is selected, and when the global
 * cooldown ends.
 *
 * <h2>Why it is its own attachment</h2>
 * These three values change every tick while the player fights, while {@link Roster} changes a few
 * times per session. Merging them would re-serialize the entire character book on every charge tick.
 * Spec §4.3 splits them for exactly this reason. The split also means a change here can be synced
 * with a tiny packet without touching progress data.
 *
 * <h2>Why the codec validates instead of trusting</h2>
 * {@code playerdata} is a file on the player's disk, so these numbers arrive from an untrusted
 * source even when the mod wrote them. Two of them can hurt:
 *
 * <ul>
 *   <li>A non-finite {@code charge} ({@code NaN}, infinity) makes every comparison false, so the bar
 *       would neither fill nor reset — a permanent, silent loss of the character's core mechanic.</li>
 *   <li>An out-of-range {@code selectedSlot} is an index the wheel would eventually use, i.e. an
 *       {@code IndexOutOfBoundsException} during rendering rather than a clean failure.</li>
 * </ul>
 *
 * <p>Both are <b>rejected</b> rather than clamped. Clamping would quietly invent a state the player
 * never had; a decode error makes the attachment fall back to its default, which is a known-good
 * state, and {@code AttachmentHolder.deserializeAttachments} logs the failure instead of taking the
 * world down. The upper bound of {@code charge} is deliberately <b>not</b> checked here: the maximum
 * is a server config value and this codec must not depend on config being loaded.
 *
 * @param charge       charge accumulated toward the UB, finite and non-negative
 * @param selectedSlot index of the currently selected wheel slot, in {@code [0, Party.SLOTS)}
 * @param cdEndTick    game time at which the global UB cooldown ends
 */
@ApiStatus.Internal
public record UbState(float charge, int selectedSlot, long cdEndTick) {

    /** {@return the state a player who has never cast anything starts from} */
    public static UbState fresh() {
        return new UbState(0.0f, 0, 0L);
    }

    private static final Codec<Float> CHARGE_CODEC = Codec.FLOAT.validate(charge ->
            Float.isFinite(charge) && charge >= 0.0f
                    ? DataResult.success(charge)
                    : DataResult.error(() -> "astrum: UB charge must be finite and non-negative, got " + charge));

    private static final Codec<Integer> SELECTED_SLOT_CODEC = Codec.INT.validate(slot ->
            slot >= 0 && slot < Party.SLOTS
                    ? DataResult.success(slot)
                    : DataResult.error(() -> "astrum: UB selectedSlot out of range [0, " + Party.SLOTS
                            + "), got " + slot));

    /** Disk codec for {@code playerdata}; small on purpose, it is written far more often than the roster. */
    public static final Codec<UbState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CHARGE_CODEC.fieldOf("charge").forGetter(UbState::charge),
            SELECTED_SLOT_CODEC.fieldOf("selectedSlot").forGetter(UbState::selectedSlot),
            Codec.LONG.fieldOf("cdEndTick").forGetter(UbState::cdEndTick)
    ).apply(instance, UbState::new));

    /**
     * {@return {@code true} if the cooldown has expired at {@code gameTime}}
     *
     * <p>Lazy check, no tick polling — the project's performance rule is zero polling loops, so
     * expiry is only ever asked about at the moment someone wants to cast.
     *
     * @param gameTime the current game time, e.g. {@code level.getGameTime()}
     */
    public boolean isReady(long gameTime) {
        return gameTime >= cdEndTick;
    }
}
