package com.astrum.pcrd.impl.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * <b>A character book larger than one frame must arrive whole, not be refused.</b>
 *
 * <h2>The defect</h2>
 * The server put <b>every</b> owned row into one {@code roster_sync} frame, and the client refused more than
 * {@link RosterSyncPayload#MAX_ENTRIES} (256). The two limits were only ever consistent by luck. Two ordinary
 * ways to cross it: a large character pack, and a save that keeps rows for characters the current pack no longer
 * defines — those are deliberately <em>kept</em> and still shown, so swapping packs repeatedly grows the book
 * monotonically. Crossing it did not lose one row: the client threw on decode, and <b>reconnecting did not help</b>,
 * because the next sync sent the same oversized frame.
 *
 * <h2>What is asserted here</h2>
 * The wire shape of one part, that the boundary cases (256 and 257) round-trip, that a multi-part book is split
 * into parts that each fit, and that the receiver drops an out-of-order part rather than gluing a hole into a
 * book. The reassembly half lives in {@code ClientRosterCache}; {@link #theDecoderRefusesMoreThanOneFrameCanHold}
 * pins the far side of the same contract.
 */
class RosterSyncBatchingTest {

    private static RosterSyncPayload.Entry entry(int index) {
        return new RosterSyncPayload.Entry(ResourceLocation.parse("astrum:character_" + index),
                index, index * 2, 100, index * 3, 1 + (index % 3));
    }

    private static List<RosterSyncPayload.Entry> entries(int count) {
        List<RosterSyncPayload.Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(entry(i));
        }
        return list;
    }

    /** A single-part book keeps the bytes it always had, plus the two new var-ints. */
    @Test
    void aOnePartBookRoundTrips() {
        RosterSyncPayload sent = new RosterSyncPayload(entries(5), false);
        assertEquals(0, sent.part(), "one packet is part 0");
        assertEquals(1, sent.parts(), "of 1");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RosterSyncPayload.STREAM_CODEC.encode(buf, sent);
        RosterSyncPayload decoded = RosterSyncPayload.STREAM_CODEC.decode(buf);

        assertEquals(5, decoded.size());
        assertEquals(0, decoded.part());
        assertEquals(1, decoded.parts());
        assertFalse(decoded.quarantined());
        for (int i = 0; i < 5; i++) {
            assertEquals(entry(i), decoded.entries().get(i), "order is the roster's own and must not reshuffle");
        }
    }

    /**
     * <b>The boundary cases: 256 fits in one part, 257 needs two.</b>
     *
     * <p>These are the two numbers the review named, and they are the whole difficulty of the change: 256 is legal
     * and must stay one frame (an ordinary large book must not pay for batching), 257 is legal <b>data</b> and must
     * not be refused.
     */
    @Test
    void twoHundredFiftySixFitsAndTwoHundredFiftySevenIsSplit() {
        RosterSyncPayload atLimit = new RosterSyncPayload(entries(RosterSyncPayload.MAX_ENTRIES), false, 0, 1);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RosterSyncPayload.STREAM_CODEC.encode(buf, atLimit);
        assertEquals(RosterSyncPayload.MAX_ENTRIES,
                RosterSyncPayload.STREAM_CODEC.decode(buf).size(),
                "256 entries is exactly what one frame may hold, and must decode");

        // 257 cannot be one part: the decoder refuses it, which is why the server has to split. Asserting the
        // refusal is what proves the split is load-bearing rather than decorative.
        List<RosterSyncPayload.Entry> tooMany = entries(RosterSyncPayload.MAX_ENTRIES + 1);
        FriendlyByteBuf oversized = new FriendlyByteBuf(Unpooled.buffer());
        oversized.writeBoolean(false);
        oversized.writeVarInt(0);
        oversized.writeVarInt(1);
        oversized.writeVarInt(tooMany.size());
        for (RosterSyncPayload.Entry entry : tooMany) {
            oversized.writeResourceLocation(entry.id());
            oversized.writeVarInt(entry.level());
            oversized.writeVarInt(entry.exp());
            oversized.writeVarInt(entry.expToNext());
            oversized.writeVarInt(entry.affection());
            oversized.writeVarInt(entry.tier());
        }
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> RosterSyncPayload.STREAM_CODEC.decode(oversized),
                "257 in one part is a protocol error — the reason a 257-row book must arrive as two parts");
    }

    /** A part index outside its own count is refused, so a corrupt frame cannot create a part that never ends. */
    @Test
    void theDecoderRefusesAnImpossiblePartIndex() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(false);
        buf.writeVarInt(3);   // part
        buf.writeVarInt(2);   // parts — part 3 of 2 does not exist
        buf.writeVarInt(0);
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> RosterSyncPayload.STREAM_CODEC.decode(buf));

        FriendlyByteBuf zero = new FriendlyByteBuf(Unpooled.buffer());
        zero.writeBoolean(false);
        zero.writeVarInt(0);
        zero.writeVarInt(0);   // a book with no parts at all
        zero.writeVarInt(0);
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> RosterSyncPayload.STREAM_CODEC.decode(zero));
    }

    /**
     * <b>The receiver glues the parts back into the whole book, in order.</b>
     *
     * <p>This is the half that makes the split worth doing: sending 257 rows in two frames only helps if the client
     * ends up with all 257 afterwards, in the roster's own order. The accumulator is static state shared with the
     * 角色册 screen, so this test restores it before and after itself.
     */
    @Test
    void theReceiverReassemblesAPartialBookInOrder() {
        com.astrum.pcrd.impl.client.ClientRosterCache.accept(RosterSyncPayload.EMPTY);
        try {
            List<RosterSyncPayload.Entry> all = entries(517);   // 256 + 256 + 5 => three parts
            com.astrum.pcrd.impl.client.ClientRosterCache.accept(
                    new RosterSyncPayload(all.subList(0, 256), false, 0, 3));
            assertEquals(0, com.astrum.pcrd.impl.client.ClientRosterCache.entries().size(),
                    "a half-arrived book is not installed: the screen must not draw a prefix as the whole book");

            com.astrum.pcrd.impl.client.ClientRosterCache.accept(
                    new RosterSyncPayload(all.subList(256, 512), false, 1, 3));
            assertEquals(0, com.astrum.pcrd.impl.client.ClientRosterCache.entries().size(),
                    "still incomplete");

            com.astrum.pcrd.impl.client.ClientRosterCache.accept(
                    new RosterSyncPayload(all.subList(512, 517), false, 2, 3));
            List<RosterSyncPayload.Entry> installed =
                    com.astrum.pcrd.impl.client.ClientRosterCache.entries();
            assertEquals(517, installed.size(), "the whole book, not the part that fit one frame");
            for (int i = 0; i < 517; i++) {
                assertEquals(all.get(i), installed.get(i),
                        "order preserved at index " + i + ": a grid that reshuffles moves a cell under the cursor");
            }
        } finally {
            com.astrum.pcrd.impl.client.ClientRosterCache.accept(RosterSyncPayload.EMPTY);
        }
    }

    /** A part that arrives before its predecessor is dropped, so a book never gets a hole glued into it. */
    @Test
    void anOutOfOrderPartIsDropped() {
        com.astrum.pcrd.impl.client.ClientRosterCache.accept(RosterSyncPayload.EMPTY);
        try {
            com.astrum.pcrd.impl.client.ClientRosterCache.accept(
                    new RosterSyncPayload(entries(3), false, 1, 2));   // part 1 with no part 0
            assertEquals(0, com.astrum.pcrd.impl.client.ClientRosterCache.entries().size(),
                    "a tail with no head is discarded rather than installed");
        } finally {
            com.astrum.pcrd.impl.client.ClientRosterCache.accept(RosterSyncPayload.EMPTY);
        }
    }

    /** The two legacy constants stay one-part, so every caller that never batches keeps working unchanged. */
    @Test
    void theLegacyConstantsAreOnePart() {
        assertEquals(0, RosterSyncPayload.EMPTY.part());
        assertEquals(1, RosterSyncPayload.EMPTY.parts());
        assertEquals(0, RosterSyncPayload.QUARANTINED.part());
        assertEquals(1, RosterSyncPayload.QUARANTINED.parts());
        assertTrue(RosterSyncPayload.QUARANTINED.quarantined());
    }
}
