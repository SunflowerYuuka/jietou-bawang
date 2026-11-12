package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.party.PartyRules;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * Why a party edit was refused, sent back to the one player who asked (spec §6.3.1, §5.5).
 *
 * <h2>Only refusals travel</h2>
 * An accepted edit needs no answer of its own: the server writes the attachment, and the party push in
 * {@code AstrumNetwork} (which diffs the party every tick) and the roster push (which diffs the book)
 * carry the new state to the client within the same tick. Sending a second "it worked" packet would
 * describe the same fact twice and could arrive out of order with the sync. Exactly the arrangement the
 * cast pair already uses.
 *
 * <h2>Why the reason is sent by name</h2>
 * The wire carries {@link PartyRules.Reason}'s <b>name</b>, not its ordinal, so reordering the enum can
 * never silently change which sentence a player reads. A name this build does not know fails the
 * decode instead of being guessed — and the screen would then have no reason to show, which the decode
 * failure at least makes visible.
 *
 * <h2>Why the slot rides along</h2>
 * The screen highlights the slot the player just touched. With the slot in the packet, a refusal that
 * arrives a tick later (after the player moved on) still marks the right box, instead of marking
 * whatever is selected when it lands.
 *
 * @param slot   the 0-based slot the refused request named
 * @param reason why it was refused
 */
@ApiStatus.Internal
public record PartyEditResultPayload(int slot, PartyRules.Reason reason)
        implements CustomPacketPayload {

    /** {@code astrum:party_edit_result}. */
    public static final Type<PartyEditResultPayload> TYPE =
            new Type<>(InternalIds.id("party_edit_result"));

    /** The stream codec; encodes the reason's name so enum order is not part of the wire format. */
    public static final StreamCodec<FriendlyByteBuf, PartyEditResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PartyEditResultPayload::slot,
                    ByteBufCodecs.STRING_UTF8.map(PartyRules.Reason::valueOf, PartyRules.Reason::name),
                    PartyEditResultPayload::reason,
                    PartyEditResultPayload::new);

    @Override
    public Type<PartyEditResultPayload> type() {
        return TYPE;
    }
}
