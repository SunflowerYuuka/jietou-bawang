package com.astrum.pcrd.impl.combat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.ApiStatus;

/**
 * Remembers which diagnostics have already been logged, so a defect in <b>loaded data</b> produces one
 * line instead of one line per hit.
 *
 * <h2>Why a set of strings and not a boolean somewhere</h2>
 * There is more than one thing that can be wrong (character A's hook #2, character B's hook #0), and the
 * whole point of the line is to tell the author <em>which</em>. A single "already warned" flag would
 * silence the second, different problem — which is how a warning turns into silence with extra steps.
 *
 * <h2>Why it is not the "static mutable state" the guide forbids</h2>
 * R8's rule is about per-entity data (a map keyed by player or world in a static field): it survives
 * world reloads and outlives its entities. What a key means is the caller's choice, and it always names
 * the <b>thing that is broken</b>, never the event that noticed it: in the combat path it is
 * {@code <character id>#<hook index>}, derived from the loaded datapack only, so the set is bounded by
 * the pack's content, holds no entity, player or world, and cannot grow per hit. The worst case is one
 * entry per malformed hook in the pack, which is exactly the information an operator wants. A caller
 * that wants "once per player" ({@code UbService}'s quarantine warning) keys on the player id instead,
 * which is bounded by the players of that server run.
 */
@ApiStatus.Internal
public final class WarnOnce {

    private final Set<String> alreadyReported = ConcurrentHashMap.newKeySet();

    /** {@return {@code true} the first time {@code key} is seen, {@code false} on every later call} */
    public boolean first(String key) {
        return alreadyReported.add(key);
    }
}
