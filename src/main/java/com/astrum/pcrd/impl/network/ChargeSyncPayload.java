package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.UbCooldown;
import io.netty.handler.codec.DecoderException;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The server's copy of one player's UB gauge and <b>per-character cooldowns</b>, pushed to that
 * player's client.
 *
 * <h2>What it carries, and what it deliberately does not</h2>
 * The two facts the client cannot compute and actually draws: {@code charge} (the bar) and
 * {@code deadlines} — one entry per character whose cooldown is still running, each carrying
 * <b>the tick it ends and the length it was armed with</b>. {@code selectedSlot} stays behind because
 * the wheel's selection is a client-side input in flight (Task 12), and the roster is a different
 * attachment with a different sync frequency (spec §4.3).
 *
 * <h2>Why the total travels with the deadline</h2>
 * The shade over the gauge's face is {@code remaining / total} (「从阴影全覆盖，到没有阴影」), and that
 * denominator <b>has</b> to be the length this cooldown was actually armed with — not
 * {@code ServerConfig}'s current value, which an operator can retune mid-cooldown, and not a number the
 * client observed for itself, which is what this payload used to force it to do
 * ({@code CooldownTracker} kept a high-water mark of the remaining ticks it had seen, so a client that
 * joined mid-cooldown drew its ring against a total it could not know). Sending the total makes the
 * client's arithmetic exact and stateless, at the cost of one var-long per running cooldown.
 *
 * <h2>Wire shape</h2>
 * {@code float} charge, a var-int count, then per entry: the character id as a string, and two
 * var-longs. Only <b>still-running</b> cooldowns are sent, which is what bounds the frame: a cooldown
 * that has ended is dropped by the sender, so the count is "characters this player cast whose cooldown
 * has not run out" rather than "characters this player has ever cast". {@link #MAX_DEADLINES} is a
 * hard cap on top of that, so a datapack with hundreds of characters cannot turn this into a large
 * packet; the decoder rejects anything above it rather than trusting the length.
 *
 * <p>The codec is typed on {@link FriendlyByteBuf} rather than {@code RegistryFriendlyByteBuf}
 * because nothing here touches a registry: that is the narrower type
 * {@code PayloadRegistrar.playToClient} accepts, and the same shape vanilla's {@code ConfigFilePayload}
 * uses. It is hand-written rather than composed, like {@code RosterSyncPayload}'s, because the map
 * needs its own decode cap.
 *
 * @param charge    the player's gauge, {@code [0, 100]}
 * @param deadlines per-character cooldowns still running, keyed by character id; a character absent
 *                  from the map is castable as far as the cooldown is concerned
 */
@ApiStatus.Internal
public record ChargeSyncPayload(float charge, Map<ResourceLocation, UbCooldown> deadlines)
        implements CustomPacketPayload {

    /** {@code astrum:charge_sync}. */
    public static final Type<ChargeSyncPayload> TYPE = new Type<>(InternalIds.id("charge_sync"));

    /**
     * Most cooldowns one frame may carry.
     *
     * <p>Sixteen, not {@code Party.SLOTS}: a cooldown is keyed by character, not by slot, so a player can
     * legitimately hold more running cooldowns than the party has slots (she casts A, swaps her out,
     * casts B, and A is still cooling). Sixteen is far above the worst real case — each cast needs a
     * full gauge — and far below anything that would matter for a frame.
     */
    public static final int MAX_DEADLINES = 16;

    /**
     * The stream codec for this payload, in both directions of registration.
     *
     * <p>No validation of the values on decode: they are produced by this mod's server, which clamps
     * the gauge through {@code ChargeKernel} and the deadlines through {@link UbCooldown}'s own
     * constructor, and a codec that rejected a bad deadline would disconnect the client rather than
     * degrade it. The <b>count</b> is validated, because that one decides how many reads follow.
     */
    public static final StreamCodec<FriendlyByteBuf, ChargeSyncPayload> STREAM_CODEC =
            StreamCodec.of(ChargeSyncPayload::encode, ChargeSyncPayload::decode);

    /** Copies the map, so a caller's collection cannot change what this payload holds. */
    public ChargeSyncPayload {
        deadlines = deadlines == null ? Map.of() : Map.copyOf(deadlines);
    }

    private static void encode(FriendlyByteBuf buf, ChargeSyncPayload payload) {
        buf.writeFloat(payload.charge);
        // Sorted by id so the same state always produces the same bytes: a frame order that varied with
        // HashMap iteration would make a size assertion flaky for no gain (a map has no order on the
        // reading side either, and Map.equals compares entry sets).
        Map<ResourceLocation, UbCooldown> ordered = new TreeMap<>(
                (a, b) -> a.toString().compareTo(b.toString()));
        ordered.putAll(payload.deadlines);
        buf.writeVarInt(ordered.size());
        for (Map.Entry<ResourceLocation, UbCooldown> entry : ordered.entrySet()) {
            ResourceLocation.STREAM_CODEC.encode(buf, entry.getKey());
            buf.writeVarLong(entry.getValue().endTick());
            buf.writeVarLong(entry.getValue().totalTicks());
        }
    }

    private static ChargeSyncPayload decode(FriendlyByteBuf buf) {
        float charge = buf.readFloat();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_DEADLINES) {
            throw new DecoderException("astrum: charge_sync carried " + count + " cooldowns (allowed 0-"
                    + MAX_DEADLINES + ")");
        }
        Map<ResourceLocation, UbCooldown> deadlines = new TreeMap<>(
                (a, b) -> a.toString().compareTo(b.toString()));
        for (int i = 0; i < count; i++) {
            ResourceLocation id = ResourceLocation.STREAM_CODEC.decode(buf);
            long endTick = buf.readVarLong();
            long totalTicks = buf.readVarLong();
            deadlines.put(id, new UbCooldown(endTick, totalTicks));
        }
        return new ChargeSyncPayload(charge, deadlines);
    }

    @Override
    public Type<ChargeSyncPayload> type() {
        return TYPE;
    }

    /**
     * {@return that character's cooldown as the server last reported it, {@link UbCooldown#NONE} when
     *         she is not cooling}
     *
     * @param characterId the character, e.g. {@code astrum:djeeta}; {@code null} answers
     *                    {@link UbCooldown#NONE}
     */
    public UbCooldown deadlineOf(ResourceLocation characterId) {
        if (characterId == null) {
            return UbCooldown.NONE;
        }
        UbCooldown cooldown = deadlines.get(characterId);
        return cooldown == null ? UbCooldown.NONE : cooldown;
    }
}
