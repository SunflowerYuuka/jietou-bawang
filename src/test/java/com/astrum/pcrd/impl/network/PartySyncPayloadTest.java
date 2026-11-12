package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The party packet: what it carries, that it round-trips, that it never reorders, and that it cannot
 * be used to make the client hold a party the server never sent.
 *
 * <h2>Why ordering gets its own assertions</h2>
 * Slot index is both the wheel position and the diminishing-return rank (spec §4.3/§5.5), so a packet
 * that sorted, compacted or trimmed its slots would silently move every character on the wheel and
 * change who wins a tie. The list is therefore positional in, positional out — including through the
 * padding path a hand-written or short payload takes.
 */
class PartySyncPayloadTest {

    private static final ResourceLocation DJEETA = ResourceLocation.fromNamespaceAndPath("astrum", "djeeta");
    private static final ResourceLocation OTHER = ResourceLocation.fromNamespaceAndPath("astrum", "other");

    @Test
    void typeIdIsInTheAstrumNamespace() {
        assertEquals(InternalIds.id("party_sync"), PartySyncPayload.TYPE.id());
    }

    @Test
    void payloadReportsItsOwnType() {
        assertSame(PartySyncPayload.TYPE, PartySyncPayload.of(Party.empty(), 0).type());
    }

    @Test
    void codecRoundTripsSlotsOrderAndTierBits() {
        Party party = Party.ofSlots(List.of(Optional.of(DJEETA), Optional.empty(),
                Optional.of(OTHER), Optional.empty(), Optional.empty()));
        PartySyncPayload source = PartySyncPayload.of(party, 0b00101);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PartySyncPayload.STREAM_CODEC.encode(buf, source);
        PartySyncPayload decoded = PartySyncPayload.STREAM_CODEC.decode(buf);

        assertEquals(source, decoded);
        assertEquals(Optional.of(DJEETA), decoded.slot(0));
        assertEquals(Optional.empty(), decoded.slot(1));
        assertEquals(Optional.of(OTHER), decoded.slot(2));
        assertEquals(0, buf.readableBytes(), "the decoder must consume the whole frame");
    }

    @Test
    void tierBitsAreReadablePerSlot() {
        PartySyncPayload payload = PartySyncPayload.of(Party.of(List.of(DJEETA, OTHER)), 0b00010);
        assertFalse(payload.enhanced(0));
        assertTrue(payload.enhanced(1));
        assertFalse(payload.enhanced(4));
        assertFalse(payload.enhanced(-1));
    }

    @Test
    void maskOfAsksAboutEveryOccupiedSlotOnly() {
        Party party = Party.of(List.of(DJEETA, OTHER));
        int mask = PartySyncPayload.maskOf(party, id -> id.equals(OTHER));
        assertEquals(0b00010, mask);
        assertEquals(0, PartySyncPayload.maskOf(Party.empty(), id -> true),
                "an empty party has no tier-3 slots, whatever the predicate says");
    }

    /**
     * A short list arrives at the same slots it was sent at: padding happens at the end, never at the
     * front, because slot 0 is the top of the wheel (spec §5.2).
     */
    @Test
    void aShortListIsPaddedAtTheEndNotRepacked() {
        PartySyncPayload payload = new PartySyncPayload(List.of(Optional.empty(), Optional.of(DJEETA)),
                0);
        assertEquals(Party.SLOTS, payload.slots().size());
        assertEquals(Optional.empty(), payload.slot(0));
        assertEquals(Optional.of(DJEETA), payload.slot(1));
        assertEquals(Optional.empty(), payload.slot(2));
    }

    @Test
    void anOverlongListIsTrimmedToTheWheel() {
        PartySyncPayload payload = new PartySyncPayload(List.of(Optional.of(DJEETA), Optional.of(OTHER),
                Optional.of(DJEETA), Optional.of(OTHER), Optional.of(DJEETA), Optional.of(OTHER)), 0);
        assertEquals(Party.SLOTS, payload.slots().size());
    }

    /**
     * A full wheel of real ids stays small: five optional {@code namespace:path} strings plus a
     * var-int mask. The bound is the "尺寸校验" half of spec §9 — measured, not claimed.
     */
    @Test
    void aFullWheelStaysUnderOneHundredBytes() {
        Party party = Party.of(List.of(
                ResourceLocation.fromNamespaceAndPath("astrum", "djeeta"),
                ResourceLocation.fromNamespaceAndPath("astrum", "kokkoro"),
                ResourceLocation.fromNamespaceAndPath("astrum", "yui"),
                ResourceLocation.fromNamespaceAndPath("astrum", "himesaki"),
                ResourceLocation.fromNamespaceAndPath("astrum", "pecorine")));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        PartySyncPayload.STREAM_CODEC.encode(buf, PartySyncPayload.of(party, 0b11111));
        assertTrue(buf.readableBytes() < 100, "party sync must stay small, was "
                + buf.readableBytes() + " bytes");
    }
}
