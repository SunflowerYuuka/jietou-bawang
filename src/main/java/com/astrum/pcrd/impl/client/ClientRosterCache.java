package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.RosterSyncPayload;
import com.astrum.pcrd.impl.network.RosterSyncPayload.Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's mirror of the player's character book, so the 角色册 screen can draw it.
 *
 * <h2>Same shape and same reasons as {@link ClientPartyCache}</h2>
 * {@code ROSTER} is a server attachment with no sync, so the client is told what it holds through
 * {@link RosterSyncPayload}; this holds the last packet. Written only by {@code AstrumNetwork}'s payload
 * handler on the client main thread, read only by the screen. One volatile reference, because the
 * quarantine flag and the rows are only meaningful together — a screen drawing rows from one packet
 * and "unreadable save" from another would be showing two different players' books.
 *
 * <p>Like {@code ClientPartyCache} this class has <b>no Minecraft client types</b>: the handler that
 * writes it is registered from common code (spec §9.5: payload 两端都注册), so it must load on a
 * dedicated server. Loading it there is harmless — it draws nothing.
 *
 * <p>Empty and readable until the first sync arrives: a screen opened before it lands shows "you own
 * nothing yet" for at most one tick, which is the same guarantee the party cache gives the wheel.
 * <b>Not</b> "quarantined", because that state must never be invented — it is a claim about the
 * player's save.
 */
@ApiStatus.Internal
public final class ClientRosterCache {

    /** "Nothing known yet": a readable, empty book. */
    private static volatile RosterSyncPayload latest = RosterSyncPayload.EMPTY;

    /** Rows of the parts already received for the book currently being reassembled, in arrival order. */
    private static final List<Entry> assembled = new ArrayList<>();

    /** Which part index {@link #assembled} is expecting next. */
    private static int nextPart;

    private ClientRosterCache() {
    }

    /**
     * Records a push from the server, reassembling a book that arrived in several parts.
     *
     * <h2>Why the receiver accumulates instead of the server sending one bigger frame</h2>
     * A book can hold more rows than one frame may carry — a large character pack, plus the rows this build
     * deliberately keeps for characters the current pack no longer defines, which are still owned and still shown.
     * The server therefore cuts the book into {@code parts} frames and this method glues them back together, so
     * the screen keeps showing the <b>whole</b> book rather than the prefix that fit in a buffer.
     *
     * <p><b>Part 0 starts a new book</b>, which is what makes a dropped or reordered run harmless: the next sync
     * begins from scratch instead of appending to a half-book. A part that arrives out of order is discarded
     * rather than stored, because a book with a hole in it is worse than a book that updates one tick later —
     * the server re-syncs on change, and a reconnect sends the whole thing again.
     *
     * @param payload one part of the book the server just sent
     */
    public static synchronized void accept(RosterSyncPayload payload) {
        if (payload.part() == 0) {
            assembled.clear();
            nextPart = 0;
        } else if (payload.part() != nextPart) {
            // Out of order, or a part from a run this client never saw begin: drop it and wait for part 0.
            return;
        }
        assembled.addAll(payload.entries());
        nextPart++;
        if (nextPart >= payload.parts()) {
            latest = new RosterSyncPayload(List.copyOf(assembled), payload.quarantined());
            assembled.clear();
            nextPart = 0;
        }
    }

    /** {@return the last book the server pushed, never {@code null}} */
    public static RosterSyncPayload latest() {
        return latest;
    }

    /** {@return whether the player's save could not be read (the screen must say so and offer no edits)} */
    public static boolean quarantined() {
        return latest.quarantined();
    }

    /** {@return the owned characters in roster order} */
    public static List<Entry> entries() {
        return latest.entries();
    }

    /**
     * {@return the progress row for {@code id}, empty when the player does not own that character}
     *
     * @param id the character to look up
     */
    public static Optional<Entry> entry(ResourceLocation id) {
        return latest.entry(id);
    }

    /** {@return whether the player owns {@code id}} */
    public static boolean owns(ResourceLocation id) {
        return latest.owns(id);
    }

    /** {@return how many characters the player owns according to the last sync} */
    public static int size() {
        return latest.size();
    }
}
