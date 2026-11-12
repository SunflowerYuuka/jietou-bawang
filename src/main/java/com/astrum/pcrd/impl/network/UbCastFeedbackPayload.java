package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.ub.CastGate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jetbrains.annotations.ApiStatus;

/**
 * Why a cast was refused, sent back so the client can shake the slot and play a sound.
 *
 * <h2>Why a refusal needs a packet at all</h2>
 * Spec §5.4: CD 期间或槽未满时按释放键必须给明确反馈（音效 + 槽位抖动），<b>禁止静默失败</b>.
 * The client cannot know which check failed — the gauge it holds may be one tick stale, and only the
 * server can see the party, the cooldown and the registry — so the server has to say.
 *
 * <p>Success needs no payload: the gauge sync already pushes the cleared bar and the new cooldown, so
 * the client learns a cast happened from the state change itself.
 *
 * <p>The wire carries the enum's <b>name</b>, not its ordinal, so a reordered {@code Reason} cannot
 * silently change which sound plays. A name this build does not know fails the decode instead of
 * being guessed at (there is no "nothing" value to fall back to, and inventing one would hide a
 * version mismatch behind a wrong shake). In practice it is unreachable: the payload itself is
 * negotiated by mod version on both ends, so a peer that does not know these names never gets this
 * far.
 *
 * @param reason why the cast was refused
 */
@ApiStatus.Internal
public record UbCastFeedbackPayload(CastGate.Reason reason) implements CustomPacketPayload {

    /** {@code astrum:ub_cast_feedback}. */
    public static final Type<UbCastFeedbackPayload> TYPE =
            new Type<>(InternalIds.id("ub_cast_feedback"));

    /** The stream codec; encodes the reason's name so enum order is not part of the wire format. */
    public static final StreamCodec<FriendlyByteBuf, UbCastFeedbackPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8.map(
                            name -> CastGate.Reason.valueOf(name),
                            CastGate.Reason::name),
                    UbCastFeedbackPayload::reason,
                    UbCastFeedbackPayload::new);

    @Override
    public Type<UbCastFeedbackPayload> type() {
        return TYPE;
    }
}
