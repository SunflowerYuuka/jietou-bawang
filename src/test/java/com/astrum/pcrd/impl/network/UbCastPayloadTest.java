package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.ub.CastGate;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UB cast pair's wire formats: what goes up, what comes down, and that both stay small.
 *
 * <h2>Two decisions worth pinning</h2>
 * The request carries <b>only the slot</b>, as a var-int, so the server is the one that decides
 * everything else (spec §9.5) and a slot it cannot hold is answered with a typed refusal instead of
 * a wrapped-around byte. The refusal carries the {@link CastGate.Reason} <b>by name</b>, so reordering
 * the enum cannot silently change which sound the client plays — a property that only a test on the
 * bytes can hold, because both sides compile from the same enum either way.
 *
 * <p>Both codecs are {@code StreamCodec<FriendlyByteBuf, ...>}, the shape {@code playToServer} /
 * {@code playToClient} accept ({@code ? super RegistryFriendlyByteBuf}), which is also what makes this
 * test possible off-line.
 */
class UbCastPayloadTest {

    @Test void bothIdsAreInTheAstrumNamespace() {
        assertEquals(InternalIds.id("ub_cast"), UbCastPayload.TYPE.id());
        assertEquals(InternalIds.id("ub_cast_feedback"), UbCastFeedbackPayload.TYPE.id());
    }

    @Test void payloadsReportTheirOwnType() {
        assertSame(UbCastPayload.TYPE, new UbCastPayload(1).type());
        assertSame(UbCastFeedbackPayload.TYPE, new UbCastFeedbackPayload(CastGate.Reason.COOLDOWN).type());
    }

    /** A wheel slot fits one byte; the point of the var-int is that a lying client still fits the wire. */
    @Test void theRequestIsASingleVarIntByte() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        UbCastPayload.STREAM_CODEC.encode(buf, new UbCastPayload(3));
        assertEquals(1, buf.readableBytes(), "a slot in [0,5) must cost one byte");
        assertEquals(3, UbCastPayload.STREAM_CODEC.decode(buf).slot());
        assertEquals(0, buf.readableBytes(), "the decoder must consume the whole frame");
    }

    /**
     * A slot outside the wheel is <b>not</b> truncated on the way out: it is the server's job to answer
     * {@code SLOT_OUT_OF_RANGE}, and a wrapped byte would make it answer for a slot the player never
     * named.
     */
    @Test void anOutOfRangeSlotStillArrivesIntact() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        UbCastPayload.STREAM_CODEC.encode(buf, new UbCastPayload(700));
        assertEquals(700, UbCastPayload.STREAM_CODEC.decode(buf).slot());
        assertTrue(buf.readableBytes() == 0);
    }

    /**
     * The refusal's frame must contain the reason's name as ASCII. Asserted on the bytes rather than on
     * a round-trip, because a round-trip through ordinals would pass just as happily — the bug this
     * guards is a wire format change, not a decode failure.
     */
    @Test void theRefusalCarriesTheReasonNameNotTheOrdinal() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        UbCastFeedbackPayload.STREAM_CODEC.encode(buf, new UbCastFeedbackPayload(CastGate.Reason.COOLDOWN));
        byte[] bytes = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), bytes);
        String frame = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(frame.contains("COOLDOWN"), "the name must be on the wire, frame=" + frame);
        // One var-int length byte, then the eight ASCII characters of "COOLDOWN" — an ordinal would be
        // a single byte of a different length, and reordering the enum would then change the sound.
        assertEquals(9, bytes.length, "length prefix + 8 ASCII characters, frame=" + frame);
        assertEquals(8, bytes[0]);
        assertFalse(frame.endsWith("\u0004"), "the ordinal alone must not be the wire format");
    }

    /** Every reason survives the round-trip, so the client can tell all five shakes apart. */
    @Test void everyReasonRoundTrips() {
        for (CastGate.Reason reason : CastGate.Reason.values()) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            UbCastFeedbackPayload.STREAM_CODEC.encode(buf, new UbCastFeedbackPayload(reason));
            assertEquals(reason, UbCastFeedbackPayload.STREAM_CODEC.decode(buf).reason());
            assertEquals(0, buf.readableBytes());
        }
    }
}
