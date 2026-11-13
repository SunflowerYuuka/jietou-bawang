package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * "Your ultimate fired — draw it", sent to the <b>caster</b> when a cast is accepted.
 *
 * <h2>Why this is a new payload and not a reuse</h2>
 * The cast pair that exists is a request and its <em>refusal</em>: {@code UbCastPayload} goes up,
 * {@code UbCastFeedbackPayload} comes down only when the server says no, and success is silent because
 * the charge sync already carries the emptied bar. Now that the effect is drawn on the client, success
 * has something more to say than "the bar is empty", and a refusal payload cannot say it — so this is a
 * third, separate message rather than a flag smuggled into either of the other two. It is not a second
 * <em>refusal</em> channel; the refusal channel is untouched.
 *
 * <h2>What it carries, and what it does not</h2>
 * {@code characterId} and the {@code enhanced} bit. The first names whose ultimate it is (the overlay
 * picks the art from it, and a party of five has five different characters); the second is the tier that
 * fired, so the enhanced cast can read differently. Nothing about damage, timing or position travels:
 * the damage has already been settled on the server (spec §9.5: the client never decides anything), and
 * a screen-space effect needs no coordinates.
 *
 * <p>The wire is the id as a string plus one boolean — {@code ResourceLocation.STREAM_CODEC} rather than
 * a registry id, because the client must be able to draw a character the running pack no longer defines
 * (it falls back to the generic art) instead of failing the decode and disconnecting.
 *
 * @param characterId the character whose ultimate fired
 * @param enhanced    whether the enhanced (affection tier 3) tier fired
 */
@ApiStatus.Internal
public record UbCastEffectPayload(ResourceLocation characterId, boolean enhanced)
        implements CustomPacketPayload {

    /** {@code astrum:ub_cast_effect}. */
    public static final Type<UbCastEffectPayload> TYPE = new Type<>(InternalIds.id("ub_cast_effect"));

    /** The stream codec; both sides register it, so one codec serves encode and decode. */
    public static final StreamCodec<FriendlyByteBuf, UbCastEffectPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, UbCastEffectPayload::characterId,
                    ByteBufCodecs.BOOL, UbCastEffectPayload::enhanced,
                    UbCastEffectPayload::new);

    @Override
    public Type<UbCastEffectPayload> type() {
        return TYPE;
    }

    /**
     * {@return the resource path the effect's art is named after}
     *
     * <p>Pure and used by the overlay, so the id-to-art rule is one expression rather than a second
     * implementation in the renderer. It exists because {@code ResourceLocation} carries a namespace that
     * is not part of a texture path: {@code astrum:djeeta} must become {@code djeeta} for
     * {@code textures/gui/ub_cutin_djeeta.png}.
     */
    public String artName() {
        return characterId.getPath();
    }
}
