package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The charge packet's wire format: what it contains, that it round-trips, and — per spec §9 "payload
 * 两端都注册 + 尺寸校验" — that its size is a handful of bytes rather than "whatever the state
 * happens to be".
 *
 * <p>The codec is {@code StreamCodec<FriendlyByteBuf, ...>}, not
 * {@code StreamCodec<RegistryFriendlyByteBuf, ...>}: nothing here needs registry access, and a plain
 * buffer is what makes this test possible off-line. {@code PayloadRegistrar.playToClient} accepts
 * {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}, and {@code FriendlyByteBuf} is a supertype
 * of {@code RegistryFriendlyByteBuf}, so the narrower codec is the one the registrar wants — the same
 * shape vanilla's {@code ConfigFilePayload} uses.
 */
class ChargeSyncPayloadTest {

    @Test void typeIdIsInTheAstrumNamespace() {
        assertEquals(InternalIds.id("charge_sync"), ChargeSyncPayload.TYPE.id());
    }

    @Test void payloadReportsItsOwnType() {
        ChargeSyncPayload payload = new ChargeSyncPayload(12.5f, 345L);
        assertSame(ChargeSyncPayload.TYPE, payload.type());
    }

    @Test void codecRoundTripsBothFields() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChargeSyncPayload source = new ChargeSyncPayload(42.5f, 123_456L);

        ChargeSyncPayload.STREAM_CODEC.encode(buf, source);
        ChargeSyncPayload decoded = ChargeSyncPayload.STREAM_CODEC.decode(buf);

        assertEquals(source, decoded);
        assertEquals(0, buf.readableBytes(), "the decoder must consume the whole frame");
    }

    /**
     * A sample frame: four bytes of raw float plus the var-int for a small game time. Asserting the
     * exact length is the point — it is the measurement that turns "small" into a fact, and it fails
     * loudly if someone adds a field or switches to a fixed-width long.
     */
    @Test void aSampleFrameIsFiveBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChargeSyncPayload.STREAM_CODEC.encode(buf, new ChargeSyncPayload(42.5f, 100L));
        assertEquals(5, buf.readableBytes());
    }

    /**
     * Worst case over any legal payload: {@code float} is fixed at four bytes and a {@code var-long}
     * of {@link Long#MAX_VALUE} takes nine, so the frame can never exceed 13 bytes. Four packets per
     * second is therefore ~50 bytes/s, which is why this attachment is allowed to be its own packet
     * (spec §4.3).
     */
    @Test void worstCaseFrameStaysUnderFourteenBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChargeSyncPayload.STREAM_CODEC.encode(buf, new ChargeSyncPayload(100.0f, Long.MAX_VALUE));
        assertTrue(buf.readableBytes() <= 13, "charge sync must stay tiny, was " + buf.readableBytes() + " bytes");
        assertEquals(13, buf.readableBytes());
    }

    /**
     * The cooldown tick survives values a 32-bit int cannot hold. This is why the field is encoded as
     * a var-long: a {@code var-int} codec would truncate a five-year-old world's game time and the
     * client's cooldown ring would wrap to "ready" while the server still refuses the cast.
     */
    @Test void preservesTicksBeyondThirtyTwoBits() {
        long large = 5_000_000_000L; // ~7.9 years of 20 tps ticks
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChargeSyncPayload.STREAM_CODEC.encode(buf, new ChargeSyncPayload(1.0f, large));
        assertEquals(large, ChargeSyncPayload.STREAM_CODEC.decode(buf).cdEndTick());
    }
}
