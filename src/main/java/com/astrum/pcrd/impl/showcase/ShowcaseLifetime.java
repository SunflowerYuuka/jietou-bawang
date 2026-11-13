package com.astrum.pcrd.impl.showcase;

import org.jetbrains.annotations.ApiStatus;

/**
 * When a 演出 entity goes away, as arithmetic with no {@code Entity} in sight.
 *
 * <h2>The two despawn paths, and why both are needed</h2>
 * Spec §5.6 lists two, and neither substitutes for the other:
 *
 * <ol>
 *   <li><b>The animation finished.</b> The normal path: the entity lives as long as the animation it
 *       was spawned for, plus a few ticks of grace so the last frame is visible rather than snapped
 *       away. This is what a player sees, and it is derived from the <em>declared</em> animation
 *       length, because the client that actually plays it cannot be asked (server authority,
 *       spec §9.5).</li>
 *   <li><b>{@code maxLifetime} ran out.</b> The hard backstop. Spec §5.6 is explicit that a chunk
 *       unload, a teleport or a lag spike can interrupt the animation, and an interrupted animation
 *       never reports anything — the entity would simply stand there for the rest of the world's
 *       life. So the lifetime is capped independently of the animation, and the cap is checked
 *       <b>every</b> tick from the entity's own {@code tick()}, which keeps running even when the
 *       model does not.</li>
 * </ol>
 *
 * <p>Both predicates are checked together in {@link #shouldDespawn}: either one is enough. The
 * timeout also covers one case the animation path cannot: a {@code animationTicks} of {@code 0} (the
 * declared animation is missing from the pack, so no model ever plays). With the animation half
 * answering "already finished", such an entity would vanish the tick it spawned, which is the wrong
 * failure — the timeout path is what keeps it visible for its full budget instead. That case is
 * therefore special-cased, not left to fall out of the comparison.
 *
 * <h2>Why a pure class</h2>
 * "It never leaks an entity" is the property most likely to be broken by a future edit (a
 * {@code return} added one line too early, a comparison flipped), and it is the one property that
 * cannot be seen in a screenshot. As a pure function it is pinned by
 * {@link com.astrum.pcrd.impl.showcase.ShowcaseLifetimeTest} — boundaries included — instead of
 * needing a server, a chunk and twelve seconds.
 */
@ApiStatus.Internal
public final class ShowcaseLifetime {

    /**
     * How long a showcase entity may live, in ticks, whatever its animation says.
     *
     * <p>120 ticks (6 s) is the plan's number. It is deliberately a <b>constant</b> and not a config
     * option: spec §8.2's tables have no key for it, and {@code ConfigDefaultsTest} pins the file's
     * exact key set, so a new option would be a spec change. It also wants to be long enough that
     * the normal path (a ~1.4 s animation) never comes near it, and short enough that a stuck entity
     * is gone before a player wonders what it is.
     */
    public static final int MAX_LIFETIME_TICKS = 120;

    /**
     * How many ticks the entity survives its own animation, so the final frame is seen.
     *
     * <p>Zero would be defensible but reads as a pop; four ticks (0.2 s) is below the threshold a
     * player notices as a delay and above the one where the last keyframe is skipped.
     */
    public static final int DESPAWN_GRACE_TICKS = 4;

    private ShowcaseLifetime() {
    }

    /**
     * {@return whether the entity should be discarded this tick}
     *
     * @param ageTicks         ticks since the entity spawned (its own {@code tickCount})
     * @param animationTicks   how long the animation it was spawned for runs, in ticks
     * @param maxLifetimeTicks the hard cap; a value {@code <= 0} means "despawn now" (see below)
     */
    public static boolean shouldDespawn(int ageTicks, int animationTicks, int maxLifetimeTicks) {
        if (timedOut(ageTicks, maxLifetimeTicks)) {
            return true;
        }
        // A declared animation of zero ticks is "this entity has no model to play", not "it already
        // finished". Treating it as finished would make it invisible for its whole budget; the
        // timeout above is the only clock in that case, which is exactly why it is checked first.
        if (animationTicks <= 0) {
            return false;
        }
        return animationFinished(ageTicks, animationTicks + DESPAWN_GRACE_TICKS);
    }

    /**
     * {@return whether {@code ageTicks} has reached the hard lifetime cap}
     *
     * <p>A cap of {@code 0} or less answers {@code true}: a caller that computed no budget must not
     * get an unbounded entity, and "despawn immediately" is the only safe reading of it.
     */
    public static boolean timedOut(int ageTicks, int maxLifetimeTicks) {
        return maxLifetimeTicks <= 0 || ageTicks >= maxLifetimeTicks;
    }

    /**
     * {@return whether the animation (plus whatever grace the caller added) has run its course}
     *
     * <p>An {@code animationTicks} of {@code 0} or less answers {@code false} — "nothing to wait
     * for" is not "finished", see {@link #shouldDespawn}.
     */
    public static boolean animationFinished(int ageTicks, int animationTicks) {
        return animationTicks > 0 && ageTicks >= animationTicks;
    }

    /**
     * {@return ticks a showcase spawned for a {@code seconds}-long animation lives}
     *
     * <p>Rounded, not truncated: a 0.9 s animation at 20 tps is 18 ticks either way, but a 1.45 s one
     * is 29 rather than 28 and the last keyframe is not clipped. The floor is one tick, so a
     * nonsensical length can never produce an entity that is dead before it is added.
     *
     * @param seconds animation length in seconds, from the animation file
     */
    public static int animationTicks(double seconds) {
        if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
            return 0;   // "unknown": the timeout path owns the lifetime
        }
        return Math.max(1, (int) Math.round(seconds * 20.0));
    }

    /**
     * {@return whether one more showcase entity may be spawned}
     *
     * <p>Spec §5.6 and the {@code ShowcaseSink} contract: the <b>entity</b> is what gets dropped at
     * the cap, never the effect. So this answers only the spawning question — the caller settles
     * damage either way (功能不断，画面不炸).
     *
     * <p>{@code maxActive <= 0} answers {@code false} on every call rather than "unlimited": 0 is a
     * legitimate way to say "never spawn a showcase" (the visual half is optional), and reading it
     * as unlimited would make the one value an operator uses to switch the entity off the one value
     * that switches it to unbounded.
     *
     * @param activeCount showcase entities already alive in the level
     * @param maxActive   the configured {@code maxActiveShowcaseEntities}
     */
    public static boolean maySpawn(int activeCount, int maxActive) {
        return maxActive > 0 && activeCount < maxActive;
    }
}
