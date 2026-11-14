package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where each character's rotation cursor lives while a player is in a world: one
 * {@link SkillRotation} per <b>(player, character)</b>.
 *
 * <h2>Why the key is the character and not the party slot</h2>
 * The human's rule is that each character's rotation is independent, so the cursor belongs to the
 * character. Keying by slot would still produce the shipped behaviour today — {@code Party} forbids the
 * same character occupying two slots, so a slot key and a character key are in bijection for any party
 * this mod can build — but it would encode the wrong thing: moving a character from slot 1 to slot 3
 * would restart her rotation for no reason a player could observe as sensible.
 *
 * <p>The player is part of the key because a server has several players, and two players who both
 * field djeeta must not share one cursor.
 *
 * <h2>In-memory only, and cleared explicitly</h2>
 * 「退出就重置。」 The rotation is a property of the fight in progress, like a combo chain, so it is
 * <b>not</b> an attachment and has <b>no codec</b>: a rotation that survived a relog would pay out a
 * skill the player did not swing for. It is not persisted anywhere and there is no plan to persist it.
 *
 * <p>Two events clear it, and both are needed:
 * <ul>
 *   <li><b>Logout</b> — {@code PlayerLoggedOutEvent}. This is the reset the human asked for. Clearing it
 *       here rather than on login also means a player who logs out and back in cannot inherit the cursor
 *       from the previous session.</li>
 *   <li><b>Server stop</b> — {@code ServerStoppingEvent}. Defence in depth: a logout that never fired
 *       (a crash, a world unload) must not leave an entry behind for the next world, which is the same
 *       reason {@code CombatEvents} clears its pending batches there.</li>
 * </ul>
 *
 * <h2>Why this static map is not the "no static mutable state" red line</h2>
 * The rule forbids per-entity state that outlives its entity. This table is per-player, keyed by live
 * player UUIDs, and both of its lifetimes are explicit and wired (above) — the same shape
 * {@code ContributionTracker} already has, and it is bounded by the players of one server run times the
 * characters they actually field.
 */
@ApiStatus.Internal
public final class SkillRotationStore {

    /** One player fielding one character. */
    private record Key(UUID player, ResourceLocation character) {
    }

    /** Every cursor of the current session. */
    private static final Map<Key, SkillRotation> CURSORS = new ConcurrentHashMap<>();

    private SkillRotationStore() {
    }

    /**
     * {@return the cursor of one character, {@link SkillRotation#START} when she has not fired yet}
     *
     * @param player      the player whose party is fighting
     * @param characterId the character whose rotation is asked about
     */
    public static SkillRotation get(UUID player, ResourceLocation characterId) {
        if (player == null || characterId == null) {
            return SkillRotation.START;
        }
        return CURSORS.getOrDefault(new Key(player, characterId), SkillRotation.START);
    }

    /**
     * Stores the cursor a character's rotation has advanced to.
     *
     * <p>Called with the value {@link HookPlan.Outcome#rotation()} answered, so the arithmetic happens
     * once, in {@link SkillRotation}, and this class only remembers it.
     *
     * @param player      the player whose party is fighting
     * @param characterId the character the cursor belongs to
     * @param rotation    the cursor after this event; {@code null} is ignored
     */
    public static void put(UUID player, ResourceLocation characterId, SkillRotation rotation) {
        if (player == null || characterId == null || rotation == null) {
            return;
        }
        CURSORS.put(new Key(player, characterId), rotation);
    }

    /**
     * Forgets one player's cursors. Called on {@code PlayerLoggedOutEvent} — 「退出就重置」.
     *
     * @param player the player who left
     * @return how many characters' cursors were dropped, for the caller's log
     */
    public static int clear(UUID player) {
        if (player == null) {
            return 0;
        }
        int before = CURSORS.size();
        CURSORS.keySet().removeIf(key -> key.player().equals(player));
        return before - CURSORS.size();
    }

    /** Forgets every cursor. Called on {@code ServerStoppingEvent}. */
    public static void clearAll() {
        CURSORS.clear();
    }

    /** {@return how many cursors are held, for the server-stop log line and for tests} */
    public static int size() {
        return CURSORS.size();
    }

    /**
     * Logs a reset at debug volume.
     *
     * <p>Silent when nothing was held: a server with nobody playing should not print a line per
     * shutdown.
     *
     * @param dropped what {@link #clear(UUID)} answered
     * @param who     the player, for the line
     */
    static void logCleared(int dropped, String who) {
        if (dropped > 0) {
            Astrum.LOGGER.debug("astrum: skill rotation reset for {} ({} character cursor(s))", who,
                    dropped);
        }
    }
}
