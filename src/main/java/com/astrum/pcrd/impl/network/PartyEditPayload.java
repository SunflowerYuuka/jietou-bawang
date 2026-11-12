package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's request to change one party slot, sent when the player clicks or drags in the 編隊
 * view (spec §6.3.1).
 *
 * <h2>What the client is allowed to say, and what it is not</h2>
 * Only an <em>intent</em>: "put this character in this slot" (or "empty this slot" when the character
 * is absent). Nothing about the resulting party is sent, and nothing here is trusted. The server
 * resolves who is asking, whether the book is readable, whether the pack defines the character,
 * whether the player owns it, and whether the slot exists — all through the same
 * {@code PartyRules}/{@code PartyService} pair {@code /astrum party set} uses, so the two doors cannot
 * drift apart (spec §9.5 server-authoritative, §6.3.1 共用同一套校验).
 *
 * <h2>Why the character is optional</h2>
 * "Clear a slot" is a party change like any other and must travel the same validated path; a separate
 * {@code party_clear} payload would be a second place to get the slot range check wrong. An absent id
 * is the clear, and the server decides whether that is allowed (it is, unless the book is unreadable).
 *
 * <h2>Why the slot is 0-based here</h2>
 * The wheel, the party, the cast payload and this one all speak {@code 0-4}; only the command line
 * uses {@code 1-5}, and its single conversion point is {@code WheelSlots.slotForDigit}. A payload that
 * numbered slots differently would be a second convention on the wire.
 *
 * @param slot        0-based party slot, expected in {@code [0, Party.SLOTS)}
 * @param characterId the character to place, or empty to clear the slot
 */
@ApiStatus.Internal
public record PartyEditPayload(int slot, Optional<ResourceLocation> characterId)
        implements CustomPacketPayload {

    /** {@code astrum:party_edit}. */
    public static final Type<PartyEditPayload> TYPE = new Type<>(InternalIds.id("party_edit"));

    /** {@return the slot number this payload is confident about, for the decode-time range log} */
    public boolean slotIsLegal() {
        return slot >= 0 && slot < Party.SLOTS;
    }

    /** Absent characters are normalised to {@link Optional#empty()}: a null optional would crash list codecs. */
    public PartyEditPayload {
        characterId = characterId == null ? Optional.empty() : characterId;
    }

    /** One optional id. Named rather than chained, for the same javac-inference reason as {@code PartySyncPayload}. */
    public static final StreamCodec<ByteBuf, Optional<ResourceLocation>> OPTIONAL_ID_CODEC =
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC);

    /** The stream codec: a var-int slot and an optional id, i.e. at most a few dozen bytes. */
    public static final StreamCodec<FriendlyByteBuf, PartyEditPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PartyEditPayload::slot,
                    OPTIONAL_ID_CODEC, PartyEditPayload::characterId,
                    PartyEditPayload::new);

    /** {@return the payload that asks for {@code slot} to be emptied} */
    public static PartyEditPayload clear(int slot) {
        return new PartyEditPayload(slot, Optional.empty());
    }

    /** {@return the payload that asks for {@code characterId} to be placed in {@code slot}} */
    public static PartyEditPayload place(int slot, ResourceLocation characterId) {
        return new PartyEditPayload(slot, Optional.of(characterId));
    }

    @Override
    public Type<PartyEditPayload> type() {
        return TYPE;
    }
}
