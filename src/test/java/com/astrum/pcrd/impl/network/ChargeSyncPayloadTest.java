package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.UbCooldown;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The charge packet's wire format: what it contains, that it round-trips, and — per spec §9 "payload
 * 两端都注册 + 尺寸校验" — that its size is bounded rather than "whatever the state happens to be".
 *
 * <h2>What changed and why these assertions moved</h2>
 * The frame used to carry one shared {@code cdEndTick}. 「每个角色UB的CD不共享」 replaced it with a map of
 * per-character deadlines, <b>each with its own total</b> (the shade's denominator), so this test now
 * pins three new things: both characters' deadlines survive a round trip independently, the total
 * survives with the deadline, and the count is capped so a datapack with many characters cannot turn
 * this into an unbounded frame. The FPS budget assertion (four pushes a second, ~50 bytes/s) is kept,
 * restated against the new shape.
 *
 * <p>The codec is {@code StreamCodec<FriendlyByteBuf, ...>}, not
 * {@code StreamCodec<RegistryFriendlyByteBuf, ...>}: nothing here needs registry access, and a plain
 * buffer is what makes this test possible off-line. {@code PayloadRegistrar.playToClient} accepts
 * {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}, and {@code FriendlyByteBuf} is a supertype
 * of {@code RegistryFriendlyByteBuf}, so the narrower codec is the one the registrar wants — the same
 * shape vanilla's {@code ConfigFilePayload} uses.
 */
class ChargeSyncPayloadTest {

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KYARU = InternalIds.id("kyaru");

    @Test void typeIdIsInTheAstrumNamespace() {
        assertEquals(InternalIds.id("charge_sync"), ChargeSyncPayload.TYPE.id());
    }

    @Test void payloadReportsItsOwnType() {
        ChargeSyncPayload payload = new ChargeSyncPayload(12.5f, Map.of(DJEETA, new UbCooldown(345L, 100L)));
        assertSame(ChargeSyncPayload.TYPE, payload.type());
    }

    /** The headline: two characters' deadlines travel together and are still independent on arrival. */
    @Test void thePayloadCarriesPerCharacterDeadlines() {
        ChargeSyncPayload source = new ChargeSyncPayload(42.5f, Map.of(
                DJEETA, new UbCooldown(1_200L, 100L),
                KYARU, new UbCooldown(9_999L, 900L)));

        ChargeSyncPayload decoded = roundTrip(source);

        assertEquals(source, decoded);
        assertEquals(new UbCooldown(1_200L, 100L), decoded.deadlineOf(DJEETA));
        assertEquals(new UbCooldown(9_999L, 900L), decoded.deadlineOf(KYARU),
                "キャル's deadline must not be confused with ディジェータ's");
    }

    /** The denominator travels with the deadline: the shade cannot be computed without it. */
    @Test void theDeadlinesTotalSurvivesTheWire() {
        ChargeSyncPayload decoded = roundTrip(new ChargeSyncPayload(1.0f,
                Map.of(DJEETA, new UbCooldown(5_000_000_000L, 1_234L))));
        assertEquals(5_000_000_000L, decoded.deadlineOf(DJEETA).endTick(),
                "a five-year-old world's game time is a var-long, not a var-int");
        assertEquals(1_234L, decoded.deadlineOf(DJEETA).totalTicks());
    }

    /** No cooldowns at all is a legal, tiny frame — the common case after everything has expired. */
    @Test void anEmptyDeadlineMapRoundTrips() {
        ChargeSyncPayload decoded = roundTrip(new ChargeSyncPayload(100.0f, Map.of()));
        assertEquals(100.0f, decoded.charge());
        assertTrue(decoded.deadlines().isEmpty());
        assertEquals(UbCooldown.NONE, decoded.deadlineOf(DJEETA),
                "a character absent from the frame is castable, which is what NONE means");
    }

    /**
     * The five-byte common case is gone on purpose, but the frame must stay small.
     *
     * <p>Charge plus an empty map is five bytes; each running cooldown costs its id plus two var-longs.
     * A player can only arm one cooldown per cast and each cast needs a full bar, so two or three
     * entries is the realistic case, and that stays well inside the budget spec §4.3 set for a
     * four-per-second push.
     */
    @Test void aTypicalFrameStaysUnderFortyBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChargeSyncPayload.STREAM_CODEC.encode(buf, new ChargeSyncPayload(42.5f, Map.of(
                DJEETA, new UbCooldown(1_200L, 100L),
                KYARU, new UbCooldown(1_244L, 100L))));
        assertTrue(buf.readableBytes() <= 40,
                "two cooldowns must stay small, was " + buf.readableBytes() + " bytes");
    }

    /** An empty map is exactly the float plus a one-byte zero count. */
    @Test void anIdleFrameIsFiveBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChargeSyncPayload.STREAM_CODEC.encode(buf, new ChargeSyncPayload(42.5f, Map.of()));
        assertEquals(5, buf.readableBytes());
    }

    /**
     * The decoder refuses a count it would have to trust.
     *
     * <p>The count decides how many reads follow, so a hostile or corrupt frame must be rejected here
     * rather than read until the buffer runs out. Throwing from a payload codec is the right answer: the
     * connection is dropped, and nothing on the client is left half-updated.
     */
    @Test void anOversizedCountIsRejected() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeFloat(1.0f);
        buf.writeVarInt(ChargeSyncPayload.MAX_DEADLINES + 1);
        assertThrows(DecoderException.class, () -> ChargeSyncPayload.STREAM_CODEC.decode(buf));

        FriendlyByteBuf negative = new FriendlyByteBuf(Unpooled.buffer());
        negative.writeFloat(1.0f);
        negative.writeVarInt(-1);
        assertThrows(DecoderException.class, () -> ChargeSyncPayload.STREAM_CODEC.decode(negative));
    }

    /** The decoder consumes the whole frame: a leftover byte would mean the shape is not what it says. */
    @Test void theDecoderConsumesTheWholeFrame() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        Map<ResourceLocation, UbCooldown> deadlines = new LinkedHashMap<>();
        deadlines.put(DJEETA, new UbCooldown(123_456L, 100L));
        ChargeSyncPayload.STREAM_CODEC.encode(buf, new ChargeSyncPayload(7.0f, deadlines));
        ChargeSyncPayload.STREAM_CODEC.decode(buf);
        assertEquals(0, buf.readableBytes());
    }

    private static ChargeSyncPayload roundTrip(ChargeSyncPayload source) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ChargeSyncPayload.STREAM_CODEC.encode(buf, source);
        return ChargeSyncPayload.STREAM_CODEC.decode(buf);
    }
}
