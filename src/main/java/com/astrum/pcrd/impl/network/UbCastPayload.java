package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's request to fire the ultimate in one wheel slot.
 *
 * <h2>What the client is allowed to say</h2>
 * One byte: which slot. <b>Nothing else.</b> The client never states its gauge, its cooldown or which
 * character it thinks is in the slot — the server resolves all of that itself (spec §9.5: 服务端权威，
 * 客户端只读). A client that lies about the slot is answered by {@code CastGate} with a typed refusal,
 * not obeyed.
 *
 * <p>Sent on tap (the selected slot) and on a digit inside the wheel, which is why it carries the slot
 * rather than a "cast" flag: the server must not have to guess which one the client meant.
 *
 * @param slot the requested wheel slot, expected in {@code [0, 5)}
 */
@ApiStatus.Internal
public record UbCastPayload(int slot) implements CustomPacketPayload {

    /** {@code astrum:ub_cast}. */
    public static final Type<UbCastPayload> TYPE = new Type<>(InternalIds.id("ub_cast"));

    /**
     * The stream codec. A var-int rather than a byte because the codec should not silently truncate a
     * slot the server has to range-check anyway: {@code CastGate} answers {@code SLOT_OUT_OF_RANGE}
     * for anything the wheel cannot hold, which is a better outcome than a wrapped-around byte.
     */
    public static final StreamCodec<FriendlyByteBuf, UbCastPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UbCastPayload::slot,
            UbCastPayload::new);

    @Override
    public Type<UbCastPayload> type() {
        return TYPE;
    }
}
