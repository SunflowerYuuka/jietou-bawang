package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.network.RosterSyncPayload.Entry;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roster packet: what it carries, that it round-trips in order, that the quarantine state survives
 * the wire distinctly from an empty book, and that its size is bounded (spec §9: payload 两端都注册 +
 * 尺寸校验).
 *
 * <h2>Why "empty" and "unreadable" are separate assertions</h2>
 * The screen's whole behaviour in the 存档读取失败 case hangs on those two being distinguishable. If the
 * flag were lost in encoding, a player whose save this build cannot parse would be shown an empty
 * roster — an invented answer about progress that is still on disk, which is the failure spec §6.3.1
 * spells out.
 */
class RosterSyncPayloadTest {

    private static final ResourceLocation DJEETA = ResourceLocation.fromNamespaceAndPath("astrum", "djeeta");
    private static final ResourceLocation KOKKORO = ResourceLocation.fromNamespaceAndPath("astrum", "kokkoro");

    private static Entry entry(ResourceLocation id, int level, int exp, int expToNext, int affection,
            int tier) {
        return new Entry(id, level, exp, expToNext, affection, tier);
    }

    @Test
    void typeIdIsInTheAstrumNamespace() {
        assertEquals(InternalIds.id("roster_sync"), RosterSyncPayload.TYPE.id());
    }

    @Test
    void payloadReportsItsOwnType() {
        assertSame(RosterSyncPayload.TYPE, RosterSyncPayload.EMPTY.type());
    }

    @Test
    void codecRoundTripsEveryFieldInOrder() {
        RosterSyncPayload source = new RosterSyncPayload(List.of(
                entry(DJEETA, 3, 35, 132, 8, 3),
                entry(KOKKORO, 1, 0, 100, 1, 1)), false);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RosterSyncPayload.STREAM_CODEC.encode(buf, source);
        RosterSyncPayload decoded = RosterSyncPayload.STREAM_CODEC.decode(buf);

        assertEquals(source, decoded);
        assertEquals(DJEETA, decoded.entries().get(0).id(), "roster order is preserved, not sorted");
        assertEquals(3, decoded.entries().get(0).level());
        assertEquals(35, decoded.entries().get(0).exp());
        assertEquals(132, decoded.entries().get(0).expToNext());
        assertEquals(8, decoded.entries().get(0).affection());
        assertEquals(3, decoded.entries().get(0).tier());
        assertEquals(0, buf.readableBytes(), "the decoder must consume the whole frame");
    }

    @Test
    void aQuarantinedBookSurvivesTheWireAsQuarantined() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RosterSyncPayload.STREAM_CODEC.encode(buf, RosterSyncPayload.QUARANTINED);
        RosterSyncPayload decoded = RosterSyncPayload.STREAM_CODEC.decode(buf);

        assertTrue(decoded.quarantined(), "an unreadable save must not arrive looking like an empty book");
        assertTrue(decoded.entries().isEmpty());
    }

    @Test
    void anEmptyReadableBookIsNotQuarantined() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RosterSyncPayload.STREAM_CODEC.encode(buf, RosterSyncPayload.EMPTY);
        RosterSyncPayload decoded = RosterSyncPayload.STREAM_CODEC.decode(buf);

        assertFalse(decoded.quarantined());
        assertEquals(0, decoded.size());
    }

    @Test
    void anEmptyBookIsTwoBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RosterSyncPayload.STREAM_CODEC.encode(buf, RosterSyncPayload.EMPTY);
        assertEquals(2, buf.readableBytes(), "one boolean plus a zero var-int");
    }

    /**
     * A realistic book stays small: five characters, one {@code namespace:path} string and five var-ints
     * each. The bound is the measurement that turns "small" into a fact.
     */
    @Test
    void aFullStarterBookStaysUnderTwoHundredBytes() {
        RosterSyncPayload payload = new RosterSyncPayload(List.of(
                entry(DJEETA, 3, 35, 132, 8, 3),
                entry(KOKKORO, 7, 10, 200, 4, 2),
                entry(ResourceLocation.fromNamespaceAndPath("astrum", "yui"), 1, 0, 100, 1, 1),
                entry(ResourceLocation.fromNamespaceAndPath("astrum", "himesaki"), 30, 0, 0, 8, 3),
                entry(ResourceLocation.fromNamespaceAndPath("astrum", "pecorine"), 12, 99, 400, 3, 2)),
                false);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RosterSyncPayload.STREAM_CODEC.encode(buf, payload);
        assertTrue(buf.readableBytes() < 200,
                "roster sync must stay small, was " + buf.readableBytes() + " bytes");
    }

    /**
     * A frame claiming more rows than {@link RosterSyncPayload#MAX_ENTRIES} is refused rather than
     * allocated for: a book is the player's whole progress, so a corrupt count must fail loudly instead
     * of being trimmed into a half-book.
     */
    @Test
    void anOverlongFrameIsRefusedAtDecode() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(false);
        buf.writeVarInt(RosterSyncPayload.MAX_ENTRIES + 1);
        assertThrows(RuntimeException.class, () -> RosterSyncPayload.STREAM_CODEC.decode(buf));
    }

    @Test
    void lookupsAnswerOwnership() {
        RosterSyncPayload payload = new RosterSyncPayload(List.of(entry(DJEETA, 1, 0, 100, 1, 1)), false);

        assertTrue(payload.owns(DJEETA));
        assertFalse(payload.owns(KOKKORO));
        assertEquals(1, payload.entry(DJEETA).orElseThrow().level());
        assertTrue(payload.entry(KOKKORO).isEmpty());
        assertEquals(1, payload.size());
    }

    @Test
    void theEntryListIsCopiedSoACallerCannotMutateASentPayload() {
        java.util.List<Entry> mutable = new java.util.ArrayList<>();
        mutable.add(entry(DJEETA, 1, 0, 100, 1, 1));
        RosterSyncPayload payload = new RosterSyncPayload(mutable, false);
        mutable.clear();
        assertEquals(1, payload.size(), "the payload must not change when the caller's list does");
    }
}
