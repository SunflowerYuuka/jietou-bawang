package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.RosterSyncPayload;
import com.astrum.pcrd.impl.network.RosterSyncPayload.Entry;
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

    private ClientRosterCache() {
    }

    /**
     * Records a push from the server.
     *
     * @param payload the book the server just sent
     */
    public static void accept(RosterSyncPayload payload) {
        latest = payload;
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
