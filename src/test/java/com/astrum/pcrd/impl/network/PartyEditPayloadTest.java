package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.party.PartyRules;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two party-edit packets: the intent that goes up and the refusal that comes back.
 *
 * <h2>What is pinned here, and why</h2>
 * <ul>
 *   <li>The intent carries a <b>slot and a character</b>, not a party: the client must not be able to
 *       send a party at all, because the server is the only side that may decide what the party is
 *       (spec §6.3.1, §9.5).</li>
 *   <li>"Clear this slot" is the same packet with an absent character, so the slot range rule has one
 *       input shape instead of two.</li>
 *   <li>The refusal carries the reason's <b>name</b>, so reordering the enum cannot change the sentence
 *       a player reads, and a slot, so the screen marks the box the player touched.</li>
 *   <li>Both frames are a handful of bytes — the size assertion is the "尺寸校验" half of spec §9.</li>
 * </ul>
 */
class PartyEditPayloadTest {

    private static final ResourceLocation DJEETA = ResourceLocation.fromNamespaceAndPath("astrum", "djeeta");

    @Test
    void theIntentTypeIdsAreInTheAstrumNamespace() {
        assertEquals(InternalIds.id("party_edit"), PartyEditPayload.TYPE.id());
        assertEquals(InternalIds.id("party_edit_result"), PartyEditResultPayload.TYPE.id());
    }

    @Test
    void theIntentRoundTripsAPlacement() {
        PartyEditPayload source = PartyEditPayload.place(3, DJEETA);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PartyEditPayload.STREAM_CODEC.encode(buf, source);
        PartyEditPayload decoded = PartyEditPayload.STREAM_CODEC.decode(buf);

        assertEquals(source, decoded);
        assertEquals(3, decoded.slot());
        assertEquals(Optional.of(DJEETA), decoded.characterId());
        assertEquals(0, buf.readableBytes(), "the decoder must consume the whole frame");
    }

    @Test
    void theIntentRoundTripsAClear() {
        PartyEditPayload source = PartyEditPayload.clear(0);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PartyEditPayload.STREAM_CODEC.encode(buf, source);
        PartyEditPayload decoded = PartyEditPayload.STREAM_CODEC.decode(buf);

        assertEquals(source, decoded);
        assertTrue(decoded.characterId().isEmpty(), "a clear names no character");
    }

    @Test
    void aPlacementFitsInTwentyBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PartyEditPayload.STREAM_CODEC.encode(buf, PartyEditPayload.place(4, DJEETA));
        assertTrue(buf.readableBytes() < 20, "was " + buf.readableBytes() + " bytes");
        FriendlyByteBuf clear = new FriendlyByteBuf(Unpooled.buffer());
        PartyEditPayload.STREAM_CODEC.encode(clear, PartyEditPayload.clear(0));
        assertTrue(clear.readableBytes() <= 2, "a clear is a slot plus an absent-optional flag");
    }

    @Test
    void aNullCharacterIsReadAsEmptyRatherThanCrashingTheCodec() {
        assertEquals(Optional.empty(), new PartyEditPayload(2, null).characterId());
    }

    @Test
    void theSlotRangeCheckAgreesWithTheParty() {
        assertTrue(PartyEditPayload.place(0, DJEETA).slotIsLegal());
        assertTrue(PartyEditPayload.place(4, DJEETA).slotIsLegal());
        assertFalse(PartyEditPayload.place(-1, DJEETA).slotIsLegal());
        assertFalse(PartyEditPayload.place(5, DJEETA).slotIsLegal());
    }

    @Test
    void everyRefusalReasonSurvivesTheWireByName() {
        for (PartyRules.Reason reason : PartyRules.Reason.values()) {
            PartyEditResultPayload source = new PartyEditResultPayload(2, reason);

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            PartyEditResultPayload.STREAM_CODEC.encode(buf, source);
            PartyEditResultPayload decoded = PartyEditResultPayload.STREAM_CODEC.decode(buf);

            assertEquals(source, decoded);
            assertEquals(2, decoded.slot());
            assertEquals(0, buf.readableBytes());
        }
    }

    @Test
    void theRefusalNamesTheReasonRatherThanItsOrdinal() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PartyEditResultPayload.STREAM_CODEC.encode(buf,
                new PartyEditResultPayload(1, PartyRules.Reason.NOT_OWNED));
        // The reason travels as a UTF-8 string: reordering the enum cannot silently change which sentence
        // a player reads, which is exactly the failure the cast feedback payload documents.
        assertTrue(new String(buf.array(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("NOT_OWNED"));
    }

    @Test
    void aRefusalFitsInFiftyBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PartyEditResultPayload.STREAM_CODEC.encode(buf,
                new PartyEditResultPayload(4, PartyRules.Reason.BOOK_UNREADABLE));
        assertTrue(buf.readableBytes() < 50, "was " + buf.readableBytes() + " bytes");
    }

    @Test
    void theRefusalReportsItsOwnType() {
        assertSame(PartyEditResultPayload.TYPE,
                new PartyEditResultPayload(0, PartyRules.Reason.NOT_OWNED).type());
        assertSame(PartyEditPayload.TYPE, PartyEditPayload.clear(0).type());
    }
}
