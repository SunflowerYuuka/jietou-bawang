package com.astrum.pcrd.impl.growth;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The "one dish per game-day per player" rule of spec §6.2, as a value with a clock.
 *
 * <h2>Why the day is an argument</h2>
 * The rule is arithmetic on a day number; the day number is the world's business. Taking {@code
 * gameDay} as a {@code long} parameter means the throttle — the part that is easy to get wrong, and the
 * part a player notices immediately when it is wrong — is testable without a Minecraft level, a player
 * or a clock.
 *
 * <h2>Why in memory, and what ends its meaning</h2>
 * A player's save must not grow a row per dish per day for the lifetime of the world, and a throttle
 * that survives a restart is indistinguishable from a cooldown that bugged out. So nothing here is
 * persisted: the map holds <b>(player, dish) → the day it was last fed</b>, and it dies with the
 * session ({@link #clear()} on {@code ServerStoppingEvent}, called by {@code GrowthEvents}).
 *
 * <p>The entry does not need its own expiry: a stored day different from today's <em>is</em> the
 * expiry, so the table is bounded by (players online × dishes) no matter how long the world runs.
 *
 * <h2>Not static</h2>
 * One instance is owned by the {@code GrowthEvents} listener, next to the other per-session state, so
 * there is exactly one of these per server and none of it is reachable through a static that a second
 * world in the same JVM would inherit.
 */
@ApiStatus.Internal
public final class DishThrottle {

    /** (player, dish) to the game day it was last fed on. */
    private final Map<Key, Long> fedOn = new HashMap<>();

    /**
     * {@return whether this player may feed this dish today, remembering the answer when it is yes}
     *
     * <p>Consumes the allowance: the first call for a (player, dish, day) answers {@code true} and the
     * next one for the same day answers {@code false}. Calling it for a day that has moved on re-arms
     * the allowance, which is exactly "once per day".
     *
     * @param player  the player feeding
     * @param dish    the item id of the dish
     * @param gameDay the world's current day number
     */
    public boolean allow(UUID player, ResourceLocation dish, long gameDay) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(dish, "dish");
        Key key = new Key(player, dish);
        Long last = fedOn.get(key);
        if (last != null && last == gameDay) {
            return false;
        }
        fedOn.put(key, gameDay);
        return true;
    }

    /** {@return whether this player already fed this dish on {@code gameDay}, without consuming} */
    public boolean alreadyFed(UUID player, ResourceLocation dish, long gameDay) {
        Long last = fedOn.get(new Key(player, dish));
        return last != null && last == gameDay;
    }

    /** Forgets every allowance. */
    public void clear() {
        fedOn.clear();
    }

    /** {@return how many (player, dish) pairs are remembered; for the log and for tests} */
    public int size() {
        return fedOn.size();
    }

    /** One player and one dish. */
    private record Key(UUID player, ResourceLocation dish) {
    }
}
