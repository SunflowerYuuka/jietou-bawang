package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.effect.AttackBuffRules;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The second loop's remaining lifetime, as a deadline.</b> One entry per <b>(player, character)</b> that
 * is currently in her second rotation.
 *
 * <h2>Why a deadline and not a countdown</h2>
 * 「进入第二套循环开始每秒消耗充能」 sounds like a per-second subtraction, and that is exactly what this
 * class must <b>not</b> do: a per-second drain needs something to run every second, and this project's red
 * line is 「no entity/level tick polling」 — a tick listener walking players is what the guard check fails on.
 *
 * <p>So the mode is stored as <b>the game time at which it ends</b>, and "is she in the second loop?" is asked
 * at the only moment anyone needs the answer — the hit that fires her skills — by comparing that number with
 * {@code level.getGameTime()}. The clock advances on its own; nothing has to advance it here. This is the
 * same "expire lazily on read" trick {@code ShieldState} uses for its {@code expiresAtTick} and
 * {@code UbState.isReady} uses for the ultimate's cooldown, and it is why this class has no {@code tick}
 * method at all.
 *
 * <p><b>The one consequence, stated rather than hidden:</b> a mode that expires is <em>not</em> removed from
 * the map until somebody asks about it or the player logs out, so an idle player holds one expired
 * {@code long} until then. {@link #isActive} answers {@code false} for it, so no rule reads it as running;
 * {@link #activeCount} reports the number that are actually live. The table is bounded by the players of one
 * server run times the characters they field, which is the same bound {@code SkillRotationStore} has.
 *
 * <h2>Why the key is the character, like the rotation cursor's</h2>
 * 「两套独立的循环」 — the two loops belong to one character. Two characters in one party can be in
 * different loops at the same time (only one of them cast her ultimate), so a per-player flag would put a
 * character who never cast into a loop she does not have. The player is part of the key for the reason
 * {@code SkillRotationStore} gives: a server has several players, and two of them fielding muimi must not
 * share one deadline.
 *
 * <h2>In-memory only, cleared explicitly</h2>
 * A mode that survived a relog would pay out skills the player never cast for, and the ultimate's own
 * attachment state ({@code UB_STATE}) is per-session combat state for the same reason. So there is no codec
 * and no attachment; two events clear it — {@code PlayerLoggedOutEvent} (「退出就重置」, the same event the
 * rotation is reset on) and {@code ServerStoppingEvent} (defence in depth, for a world that stops without
 * every player logging out).
 */
@ApiStatus.Internal
public final class SecondRotationStore {

    /** One player fielding one character. */
    private record Key(UUID player, ResourceLocation character) {
    }

    /** The game time each active mode ends at. An entry is live while {@code now < deadline}. */
    private static final Map<Key, Long> DEADLINES = new ConcurrentHashMap<>();

    private SecondRotationStore() {
    }

    /**
     * Starts the mode, or restarts it, from {@code now}.
     *
     * <p><b>It always writes a fresh deadline</b> rather than extending the existing one. Two ultimate casts
     * are two separate mode activations: the second one re-empties the bar (every cast does,
     * {@code UbTransaction.decide}) and so must own its own clock, exactly as {@code ShieldState.granted}
     * says a second grant "lasts as long as its own skill says". Extending would let a player who recast
     * early bank the remainder of the first mode on top of the second, which is a duration no cast paid for.
     *
     * <p>A non-positive {@code seconds} writes nothing and returns {@code false}: a pack that declares no
     * clock has not described a mode, and a deadline of {@code now} would read as active until the very next
     * comparison. The caller logs the refusal once.
     *
     * @param player      the player whose character cast
     * @param characterId the character whose loop begins
     * @param now         the current game time, from {@code level.getGameTime()}
     * @param seconds     how long the mode lasts, as the pack declared it
     * @return {@code true} when a deadline was written
     */
    public static boolean begin(UUID player, ResourceLocation characterId, long now, double seconds) {
        if (player == null || characterId == null) {
            return false;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            return false;
        }
        DEADLINES.put(new Key(player, characterId), now + ticks);
        Astrum.LOGGER.debug("astrum: {} entered her second rotation for {}t ({}s)", characterId, ticks,
                seconds);
        return true;
    }

    /**
     * Extends the running mode by {@code seconds} — 「+2 秒 / 击杀，<b>无上限</b>」.
     *
     * <h2>No cap, and why that is the shipped rule</h2>
     * 「原作游戏内就是无上限」. A cap would be a number nobody gave, and any cap this class invented would
     * silently truncate a long fight. So the deadline moves by exactly {@code +seconds} per kill, however many
     * kills that is.
     *
     * <p><b>A kill while the mode is <em>not</em> running extends nothing</b> and returns {@code false}: the
     * human's sentence is about the second loop's duration (「击杀单位增加持续时间」), not about banking time
     * for a mode she is not in. The caller therefore asks this only for a character whose mode is live —
     * {@link #extend} re-checks the deadline itself as well, so a stale entry cannot be resurrected by a kill
     * that happens after it expired.
     *
     * @param player      the player whose character killed
     * @param characterId the character whose loop may be extended
     * @param now         the current game time
     * @param seconds     the per-kill extension, from {@code SecondRotation.secondsPerKill()}
     * @return {@code true} when the deadline moved
     */
    public static boolean extend(UUID player, ResourceLocation characterId, long now, double seconds) {
        if (player == null || characterId == null) {
            return false;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            return false;
        }
        Key key = new Key(player, characterId);
        Long deadline = DEADLINES.get(key);
        if (deadline == null || now >= deadline) {
            // Not in the loop: a kill does not put her into one, and an expired entry is not extended back
            // into life. The expired entry is dropped here so the table does not keep a number no reader uses.
            if (deadline != null) {
                DEADLINES.remove(key, deadline);
            }
            return false;
        }
        DEADLINES.put(key, deadline + ticks);
        Astrum.LOGGER.debug("astrum: {} killed; second rotation extended by {}t to {}t", characterId, ticks,
                deadline + ticks);
        return true;
    }

    /**
     * {@return whether this character is in her second loop right now}
     *
     * <p>The one read the combat path makes, and the only place the deadline comparison is written. An expired
     * entry is removed here so the answer cannot drift from what the map holds for longer than one hit.
     *
     * @param player      the player whose party is fighting
     * @param characterId the character asked about
     * @param now         the current game time
     */
    public static boolean isActive(UUID player, ResourceLocation characterId, long now) {
        if (player == null || characterId == null) {
            return false;
        }
        Key key = new Key(player, characterId);
        Long deadline = DEADLINES.get(key);
        if (deadline == null) {
            return false;
        }
        if (now >= deadline) {
            DEADLINES.remove(key, deadline);
            Astrum.LOGGER.debug("astrum: {} left her second rotation (deadline {}t reached at {}t)",
                    characterId, deadline, now);
            return false;
        }
        return true;
    }

    /**
     * {@return the game time this character's mode ends at, or empty when she is not in one}
     *
     * <p>For the log lines and for a caller that wants to say "3.4 s left" — never for a decision, which is
     * {@link #isActive}'s job so that the comparison exists once.
     */
    public static java.util.OptionalLong deadlineOf(UUID player, ResourceLocation characterId) {
        if (player == null || characterId == null) {
            return java.util.OptionalLong.empty();
        }
        Long deadline = DEADLINES.get(new Key(player, characterId));
        return deadline == null ? java.util.OptionalLong.empty() : java.util.OptionalLong.of(deadline);
    }

    /**
     * Forgets one player's modes. Called on {@code PlayerLoggedOutEvent}, with the rotation reset.
     *
     * @param player the player who left
     * @return how many characters' modes were dropped, for the caller's log
     */
    public static int clear(UUID player) {
        if (player == null) {
            return 0;
        }
        int before = DEADLINES.size();
        DEADLINES.keySet().removeIf(key -> key.player().equals(player));
        return before - DEADLINES.size();
    }

    /** Forgets every mode. Called on {@code ServerStoppingEvent}. */
    public static void clearAll() {
        DEADLINES.clear();
    }

    /** {@return how many deadlines are held, expired ones included, for the server-stop log and for tests} */
    public static int size() {
        return DEADLINES.size();
    }

    /**
     * {@return how many modes are <b>actually running</b> at {@code now}
     *
     * <p>Distinct from {@link #size()} on purpose: this is the number a reader means by "how many characters
     * are in their second loop", and it is the one a test asserts after a deadline passes without anybody
     * touching the table.
     *
     * @param now the current game time
     */
    public static int activeCount(long now) {
        int live = 0;
        for (Long deadline : DEADLINES.values()) {
            if (deadline != null && now < deadline) {
                live++;
            }
        }
        return live;
    }

    /**
     * Logs a reset at debug volume, silent when nothing was held — the shape
     * {@code SkillRotationStore.logCleared} uses.
     *
     * @param dropped what {@link #clear(UUID)} answered
     * @param who     the player, for the line
     */
    static void logCleared(int dropped, String who) {
        if (dropped > 0) {
            Astrum.LOGGER.debug("astrum: second rotation reset for {} ({} character(s))", who, dropped);
        }
    }
}
