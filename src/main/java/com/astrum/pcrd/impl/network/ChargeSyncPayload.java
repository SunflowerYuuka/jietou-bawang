package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * The server's copy of one player's UB gauge, pushed to that player's client.
 *
 * <h2>What it carries, and what it deliberately does not</h2>
 * Only the two fields the client cannot compute and actually draws: {@code charge} (the bar) and
 * {@code cdEndTick} (the cooldown ring). {@code selectedSlot} stays behind because the wheel's
 * selection is a client-side input in flight (Task 12), and the roster is a different attachment
 * with a different sync frequency (spec §4.3). Keeping it to two fields is what makes the frame
 * five bytes.
 *
 * <h2>Wire shape</h2>
 * {@code float} (fixed, four bytes) plus a var-long tick. A tick is written var-int style rather
 * than as a fixed eight bytes because the common value is small, and the worst case — a game time
 * above the 32-bit range, i.e. a world older than three years of uptime — still costs only nine
 * bytes. The codec is typed on {@link FriendlyByteBuf} rather than {@code RegistryFriendlyByteBuf}
 * because nothing here touches a registry: that is the narrower type
 * {@code PayloadRegistrar.playToClient} accepts, and the same shape vanilla's {@code ConfigFilePayload}
 * uses.
 *
 * <p>The id is not written by this codec: the registrar writes it, which is why one
 * {@link StreamCodec} serves both sides.
 *
 * @param charge    the player's gauge, {@code [0, 100]}
 * @param cdEndTick game time at which the shared cooldown ends
 */
@ApiStatus.Internal
public record ChargeSyncPayload(float charge, long cdEndTick) implements CustomPacketPayload {

    /** {@code astrum:charge_sync}. */
    public static final Type<ChargeSyncPayload> TYPE = new Type<>(InternalIds.id("charge_sync"));

    /**
     * The stream codec for this payload, in both directions of registration.
     *
     * <p>No validation on decode: the value is produced by this mod's server, which clamps the gauge
     * through {@code ChargeKernel} before it is ever sent, and a codec that rejected a bad value would
     * disconnect the client rather than degrade it.
     */
    public static final StreamCodec<FriendlyByteBuf, ChargeSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, ChargeSyncPayload::charge,
            ByteBufCodecs.VAR_LONG, ChargeSyncPayload::cdEndTick,
            ChargeSyncPayload::new);

    @Override
    public Type<ChargeSyncPayload> type() {
        return TYPE;
    }
}
