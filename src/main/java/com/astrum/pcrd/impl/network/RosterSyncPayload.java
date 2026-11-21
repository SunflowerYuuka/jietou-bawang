package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.impl.InternalIds;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The player's whole character book, pushed to their client so the 角色册 screen can draw the roster
 * grid and the growth view (spec §6.3.1).
 *
 * <h2>Why the client cannot derive this</h2>
 * {@code ROSTER} is a server attachment with no {@code .sync(...)}: a client knows neither which
 * characters the player owns nor their level, experience or affection. All four are server facts, and
 * three of them (level, exp, affection) are exactly what the growth view shows. The only thing the
 * client could work out alone is a character's <em>name</em>, from the synced datapack registry, so
 * the payload carries ids and numbers and nothing that could be recomputed wrongly.
 *
 * <h2>Why each entry carries {@code expToNext} and {@code tier}</h2>
 * Both are rules, not data: the experience curve and the affection ladder. Sending the two answers
 * instead of the two rules keeps exactly one implementation of each — the server's
 * {@code GrowthFacts} — and means a client holding a stale pack cannot disagree with the server about
 * which tier a character is in. It costs one var-int per character per field.
 *
 * <h2>Quarantine is a state, not an empty book</h2>
 * {@code Roster.entries()} <b>throws</b> while the book is quarantined (an unreadable save is preserved
 * verbatim on disk), so "the book is empty" and "the book exists but this build cannot read it" would
 * otherwise be indistinguishable to the screen — and one of those is a fresh player while the other is
 * a player whose progress is still on disk. {@link #quarantined()} is what lets the screen say
 * 存档读取失败 instead of showing an empty grid, exactly as spec §6.3.1 demands.
 *
 * <h2>Wire shape and its bound</h2>
 * A boolean, a var-int count, then per entry: the id string, and five var-ints (level, exp, expToNext,
 * affection, tier). Decoding is capped at {@link #MAX_ENTRIES} so a hostile or corrupt peer cannot make
 * the client allocate a list of arbitrary size; an over-long list is refused rather than trimmed,
 * because a book is the player's whole progress and silently dropping rows would be worse than a
 * disconnect that leaves it intact.
 *
 * <p>Order is <b>the roster's own order</b> (insertion order, which is the order characters were
 * enrolled) and is preserved verbatim: a grid that reshuffled itself between packets would move a
 * cell out from under the player's cursor.
 *
 * @param entries     owned characters in roster order; never {@code null}
 * @param quarantined whether the server's book is an unreadable save (then {@code entries} is empty)
 */
@ApiStatus.Internal
public record RosterSyncPayload(List<Entry> entries, boolean quarantined, int part, int parts)
        implements CustomPacketPayload {

    /** {@code astrum:roster_sync}. */
    public static final Type<RosterSyncPayload> TYPE = new Type<>(InternalIds.id("roster_sync"));

    /**
     * Decode cap. A character book holds the characters a player has enrolled; two hundred and fifty
     * six is far beyond any pack this slice can ship and still small enough that a corrupt frame cannot
     * exhaust memory.
     */
    public static final int MAX_ENTRIES = 256;

    /** The empty, readable book: a player who owns nothing, or a client that has not been synced yet. */
    public static final RosterSyncPayload EMPTY = new RosterSyncPayload(List.of(), false, 0, 1);

    /** The "save could not be read" state: no rows, and the screen must say so. */
    public static final RosterSyncPayload QUARANTINED = new RosterSyncPayload(List.of(), true, 0, 1);

    /**
     * A whole book in one packet — the shape every caller used before {@code part}/{@code parts} existed.
     *
     * <p>Kept because most books <b>are</b> one packet: {@link #MAX_ENTRIES} is far beyond any pack this
     * slice ships, so the single-part case must stay the ordinary one and must not pay for the batching.
     */
    public RosterSyncPayload(List<Entry> entries, boolean quarantined) {
        this(entries, quarantined, 0, 1);
    }

    /**
     * One owned character, as the screen needs it.
     *
     * @param id        the character's id, the key everything else is looked up by
     * @param level     the character's level
     * @param exp       experience into the current level
     * @param expToNext what the current level needs, from {@code GrowthFacts.expToNext}
     * @param affection the character's affection
     * @param tier      the affection tier that affection is in: 1, 2 or 3
     */
    public record Entry(ResourceLocation id, int level, int exp, int expToNext, int affection, int tier) {
    }

    /**
     * The stream codec, hand-written rather than composed from {@code StreamCodec.composite}: the
     * entry has six fields (the composite helpers stop at six including the factory, and the chained
     * form leaves the buffer type under-determined for javac), and the list needs its own decode cap.
     */
    public static final StreamCodec<FriendlyByteBuf, RosterSyncPayload> STREAM_CODEC =
            StreamCodec.of(RosterSyncPayload::encode, RosterSyncPayload::decode);

    /** Copies the list so a caller's collection cannot change what this payload holds. */
    public RosterSyncPayload {
        entries = List.copyOf(entries);
        // A part index outside its own count is a protocol error, not something to clamp: the receiver's
        // accumulator keys on it, and a silent fix would assemble a book out of the wrong frames.
        if (parts < 1) {
            parts = 1;
        }
        if (part < 0 || part >= parts) {
            part = 0;
            parts = 1;
        }
    }

    private static void encode(FriendlyByteBuf buf, RosterSyncPayload payload) {
        buf.writeBoolean(payload.quarantined);
        // Part index and count first: a receiver must know how much is still coming before it decides what
        // to do with this frame's rows.
        buf.writeVarInt(payload.part);
        buf.writeVarInt(payload.parts);
        buf.writeVarInt(payload.entries.size());
        for (Entry entry : payload.entries) {
            writeEntry(buf, entry);
        }
    }

    private static RosterSyncPayload decode(FriendlyByteBuf buf) {
        boolean quarantined = buf.readBoolean();
        int part = buf.readVarInt();
        int parts = buf.readVarInt();
        if (parts < 1 || part < 0 || part >= parts) {
            throw new io.netty.handler.codec.DecoderException(
                    "astrum: roster_sync carried part " + part + " of " + parts);
        }
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new io.netty.handler.codec.DecoderException(
                    "astrum: roster_sync carried " + count + " entries (allowed 0-" + MAX_ENTRIES + ")");
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(readEntry(buf));
        }
        return new RosterSyncPayload(entries, quarantined, part, parts);
    }

    /**
     * Writes one entry; package-visible so {@code RosterSyncPayloadTest} can pin the frame size.
     *
     * <p>Typed {@link FriendlyByteBuf} and not {@code ByteBuf}: the var-int helpers live on Minecraft's
     * buffer, not on Netty's — a {@code ByteBuf} would only offer fixed-width writes.
     */
    static void writeEntry(FriendlyByteBuf buf, Entry entry) {
        ResourceLocation.STREAM_CODEC.encode(buf, entry.id());
        buf.writeVarInt(entry.level());
        buf.writeVarInt(entry.exp());
        buf.writeVarInt(entry.expToNext());
        buf.writeVarInt(entry.affection());
        buf.writeVarInt(entry.tier());
    }

    /** Reads one entry, mirroring {@link #writeEntry}. */
    static Entry readEntry(FriendlyByteBuf buf) {
        ResourceLocation id = ResourceLocation.STREAM_CODEC.decode(buf);
        return new Entry(id, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt());
    }

    /** {@return the entry for {@code id}, empty when this player does not own it} */
    public java.util.Optional<Entry> entry(ResourceLocation id) {
        for (Entry entry : entries) {
            if (entry.id().equals(id)) {
                return java.util.Optional.of(entry);
            }
        }
        return java.util.Optional.empty();
    }

    /** {@return whether the book holds {@code id}} */
    public boolean owns(ResourceLocation id) {
        return entry(id).isPresent();
    }

    /** {@return how many characters the player owns} */
    public int size() {
        return entries.size();
    }

    @Override
    public Type<RosterSyncPayload> type() {
        return TYPE;
    }
}

