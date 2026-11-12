package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Which character sits in which wheel slot, plus which of them are at affection tier 3, sent to the
 * owning player so the wheel can be drawn at all.
 *
 * <h2>Why the client cannot work this out for itself</h2>
 * {@code PARTY} is an unsynced server attachment, so a client knows neither the order nor the
 * membership. Order is meaning here (slot index = wheel position = diminishing rank, spec §4.3/§5.5),
 * so the client cannot substitute "the characters I know about": it must be told, in order, and it
 * must not re-sort what it is told.
 *
 * <h2>Why the tier-3 bitmask rides along</h2>
 * Spec §6.2 requires the wheel to show the <em>enhanced</em> icon for a tier-3 character, and tier is
 * a fact only the server has ({@code ROSTER} affection, quarantine-aware). Sending it in the same
 * packet as the ids keeps one frame authoritative for the whole wheel: a wheel that showed last
 * week's tier would be worse than one that showed none, because it would look deliberate. One bit per
 * slot, low bits first, in the same var-int.
 *
 * <h2>Shape</h2>
 * Five optional ids (a var-int count plus per-entry string) and a var-int mask, so a full wheel is a
 * few dozen bytes and an empty one is two. The list length is capped at {@link Party#SLOTS} on
 * decode: a peer that sends more is refused rather than allocated for.
 *
 * <p>Nothing here is trusted as authority — this is display data only. A cast request names a slot
 * and the server re-resolves everything (spec §5.5/§9.5).
 *
 * @param slots        exactly {@link Party#SLOTS} entries in slot order; empty optional = unused slot
 * @param enhancedMask one bit per slot, bit {@code i} set when slot {@code i} is tier 3
 */
@ApiStatus.Internal
public record PartySyncPayload(List<Optional<ResourceLocation>> slots, int enhancedMask)
        implements CustomPacketPayload {

    /** {@code astrum:party_sync}. */
    public static final Type<PartySyncPayload> TYPE = new Type<>(InternalIds.id("party_sync"));

    /**
     * The stream codec.
     *
     * <p>The list codec is built from {@code ResourceLocation.STREAM_CODEC} rather than from a
     * registry-aware codec: ids are strings on the wire here, and nothing in this payload needs
     * registry access, which keeps the codec {@code FriendlyByteBuf}-typed and testable off-line
     * (the same reason {@link ChargeSyncPayload} is).
     *
     * <p>Two named intermediate codecs rather than one chained expression: the chain
     * ({@code STREAM_CODEC.apply(optional).apply(list)}) leaves the buffer type under-determined for
     * javac, and the resulting error ("cannot infer type variables") says nothing about the real
     * problem.
     */
    public static final StreamCodec<ByteBuf, Optional<ResourceLocation>> OPTIONAL_ID_CODEC =
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC);

    /** Five-or-fewer optional ids; the count is a var-int written by {@code list()}. */
    public static final StreamCodec<ByteBuf, List<Optional<ResourceLocation>>> SLOTS_CODEC =
            OPTIONAL_ID_CODEC.apply(ByteBufCodecs.list(Party.SLOTS));

    public static final StreamCodec<FriendlyByteBuf, PartySyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    SLOTS_CODEC,
                    PartySyncPayload::slots,
                    ByteBufCodecs.VAR_INT,
                    PartySyncPayload::enhancedMask,
                    PartySyncPayload::new);

    /**
     * Pads or trims to exactly {@link Party#SLOTS} entries.
     *
     * <p>Never reorders: index is wheel position, so a short list keeps its slots and gains empty
     * ones at the end, exactly like {@link Party#ofSlots}. This is the same rule the party itself
     * uses, applied once more where the data enters the client, so no renderer has to defend
     * against a short list.
     */
    public PartySyncPayload {
        List<Optional<ResourceLocation>> normalised = new ArrayList<>(Party.SLOTS);
        for (int i = 0; i < Party.SLOTS; i++) {
            normalised.add(i < slots.size() && slots.get(i) != null ? slots.get(i) : Optional.empty());
        }
        slots = List.copyOf(normalised);
    }

    /**
     * Snapshots a server-side party for sending.
     *
     * @param party        the player's party
     * @param enhancedMask tier-3 bits, see {@link #enhancedMask()}
     */
    public static PartySyncPayload of(Party party, int enhancedMask) {
        return new PartySyncPayload(party.slots(), enhancedMask);
    }

    /**
     * Builds the tier-3 bitmask by asking {@code enhanced} about each equipped character.
     *
     * @param party    the player's party
     * @param enhanced answers "is this character at tier 3 for this player"; called once per slot
     * @return the mask, bit {@code i} set when slot {@code i} holds such a character
     */
    public static int maskOf(Party party, Predicate<ResourceLocation> enhanced) {
        int mask = 0;
        for (int i = 0; i < Party.SLOTS; i++) {
            Optional<ResourceLocation> id = party.slots().get(i);
            if (id.isPresent() && enhanced.test(id.get())) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /**
     * {@return the character in a slot, empty for an unused or out-of-range slot}
     *
     * @param slot slot index, expected in {@code [0, Party.SLOTS)}
     */
    public Optional<ResourceLocation> slot(int slot) {
        return slot < 0 || slot >= slots.size() ? Optional.empty() : slots.get(slot);
    }

    /**
     * {@return whether the character in a slot is at tier 3}
     *
     * @param slot slot index, expected in {@code [0, Party.SLOTS)}
     */
    public boolean enhanced(int slot) {
        return slot >= 0 && slot < Party.SLOTS && (enhancedMask & (1 << slot)) != 0;
    }

    @Override
    public Type<PartySyncPayload> type() {
        return TYPE;
    }
}
