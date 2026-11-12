package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.network.PartySyncPayload;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's mirror of the ordered party, so the wheel knows what to draw where.
 *
 * <h2>Same shape and same reason as {@link ClientUbCache}</h2>
 * {@code PARTY} is a server attachment with no sync, so the wheel cannot read it. The server pushes
 * {@link PartySyncPayload} instead, and this holds the last one. Written only by
 * {@code AstrumNetwork}'s payload handler on the client main thread; read only by the wheel and the
 * HUD. One volatile reference, because the list and the tier mask are only meaningful together — a
 * wheel drawing slot 3's character with slot 2's tier would be exactly the bug that reading two
 * packets would produce.
 *
 * <p>Like {@link ClientUbCache} this class has <b>no Minecraft client types</b>: the payload handler
 * that writes it is registered from common code (spec §9.5: payload 两端都注册), so it must load on a
 * dedicated server. Loading it there is harmless — it draws nothing.
 *
 * <p>Empty until the first sync arrives: a wheel opened before the party is known shows five empty
 * slots rather than inventing an order, because order is meaning (spec §5.5).
 */
@ApiStatus.Internal
public final class ClientPartyCache {

    /** "Nothing known yet": five empty slots, no tier-3 bits. */
    private static volatile PartySyncPayload latest = PartySyncPayload.of(Party.empty(), 0);

    private ClientPartyCache() {
    }

    /**
     * Records a push from the server.
     *
     * @param payload the party the server just sent
     */
    public static void accept(PartySyncPayload payload) {
        latest = payload;
    }

    /** {@return the last party the server pushed, never {@code null}} */
    public static PartySyncPayload latest() {
        return latest;
    }

    /**
     * {@return the character in a wheel slot, empty for an unused slot}
     *
     * @param slot slot index in {@code [0, Party.SLOTS)}
     */
    public static Optional<ResourceLocation> slot(int slot) {
        return latest.slot(slot);
    }

    /**
     * {@return whether the character in a wheel slot is at affection tier 3}
     *
     * @param slot slot index in {@code [0, Party.SLOTS)}
     */
    public static boolean enhanced(int slot) {
        return latest.enhanced(slot);
    }

    /** {@return whether every slot is empty, i.e. nothing has been synced or nothing is equipped} */
    public static boolean isEmpty() {
        for (int i = 0; i < Party.SLOTS; i++) {
            if (latest.slot(i).isPresent()) {
                return false;
            }
        }
        return true;
    }
}
