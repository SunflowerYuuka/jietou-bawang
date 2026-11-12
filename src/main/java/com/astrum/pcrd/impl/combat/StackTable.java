package com.astrum.pcrd.impl.combat;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * The stack state attached to a <b>target entity</b>: one {@link StackState} per (attacker, stack
 * kind), expired lazily (spec §4.4).
 *
 * <h2>Why a table and not a single {@link StackState}</h2>
 * §4.4 asks for an attachment holding stack state whose key carries the attacker's uuid. A single
 * {@code StackState} value cannot honour that: one target can be hit by several players and can carry
 * several stack kinds at once, and collapsing that into one record would either lose a player's stacks
 * or silently merge two stack kinds. The record stays the pure value the kernel works on; this table is
 * the runtime container that keys it.
 *
 * <h2>The performance red line (§4.4): no tick, ever</h2>
 * There is no timer and nothing iterates entities. An entry is only examined when the same
 * (attacker, stack kind) is looked up again, i.e. when the target is next hit by that attacker, and the
 * lookup itself drops the entry if it has aged out. An idle target therefore costs <b>zero</b> work per
 * tick; the only thing that runs is whatever the server already does.
 *
 * <h2>Why the size is bounded even without a sweep</h2>
 * Lazy expiry only cleans a key somebody looks at, so a target tagged once each by a hundred players
 * would otherwise keep a hundred dead entries until the entity unloads. {@link #hit} therefore expires
 * and, past {@link #MAX_ENTRIES}, evicts the least recently hit key at the moment the overflow would
 * happen — still no sweep, still no tick, but bounded. Sixteen is far above any real fight (five wheel
 * slots × two stack kinds would already be a data-authoring stretch) and small enough that eviction
 * can never be what a player notices first.
 *
 * <h2>Mutability</h2>
 * The table is mutated in place because it is a runtime container the attachment holds by reference;
 * copying the whole map on every stack would allocate on every hit for no reader (`serialize = false`,
 * no sync handler, so neither saving nor the network can observe the difference).
 */
@ApiStatus.Internal
public final class StackTable {

    /**
     * Upper bound on live keys on one entity.
     *
     * <p>Not a config option on purpose: it is a memory bound, not a balance number, and an operator
     * has no way to judge it. Exceeding it drops the least recently hit key, never the stack the
     * current hit is about to write.
     */
    public static final int MAX_ENTRIES = 16;

    /** Insertion order is kept so eviction can walk it; the order is not otherwise meaningful. */
    private final Map<StackKey, StackState> entries = new LinkedHashMap<>();

    /**
     * {@return the live stack for {@code key}, or {@link StackState#EMPTY} if there is none or it aged out}
     *
     * <p>Drops the entry when it is expired, which is the only cleanup this design needs.
     */
    public StackState read(StackKey key, long nowTick, int ttlTicks) {
        StackState state = entries.get(key);
        if (state == null) {
            return StackState.EMPTY;
        }
        if (StackKernel.isExpired(state, nowTick, ttlTicks)) {
            entries.remove(key);
            return StackState.EMPTY;
        }
        return state;
    }

    /**
     * {@return the state after adding {@code delta} stacks of {@code key} at {@code nowTick}}
     *
     * <p>Expiry is judged here rather than by the caller: an expired stack is replaced, not added to,
     * so a hit 10 seconds after the last one starts a new stack at 1 instead of continuing an old one.
     *
     * @param key      which attacker's stack of which kind
     * @param nowTick  the tick of this hit
     * @param delta    stacks to add
     * @param max      the hook's cap, never scaled by the diminishing multiplier (spec §5.2)
     * @param ttlTicks how long a stack lives without a new hit
     */
    public StackState hit(StackKey key, long nowTick, int delta, int max, int ttlTicks) {
        StackState current = read(key, nowTick, ttlTicks);
        if (!entries.containsKey(key)) {
            makeRoom(key);
        }
        StackState next = StackKernel.hit(current, nowTick, delta, max);
        entries.put(key, next);
        return next;
    }

    /**
     * {@return the stacks that were held, having removed them}
     *
     * <p>"Remove" rather than "mark consumed": a detonation spends the whole stack, so a second
     * detonation in the same attack must find nothing. Expired stacks are worth nothing and are
     * dropped here too.
     */
    public int drain(StackKey key, long nowTick, int ttlTicks) {
        StackState state = read(key, nowTick, ttlTicks);
        entries.remove(key);
        return Math.max(0, state.stacks());
    }

    /** {@return how many keys are currently held, expired-but-unread ones included} */
    public int size() {
        return entries.size();
    }

    /** {@return {@code true} when no key is held} */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Drops every key. Only used when the holder is being thrown away. */
    public void clear() {
        entries.clear();
    }

    /**
     * Frees one slot for {@code incoming} if the table is full.
     *
     * <p>First tries the honest cleanup — an expired entry is dead weight — and only then evicts the
     * least recently hit key. The incoming key is never the victim: it is not in the map yet.
     */
    private void makeRoom(StackKey incoming) {
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<StackKey, StackState>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<StackKey, StackState> entry = iterator.next();
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        Map.Entry<StackKey, StackState> oldest = null;
        for (Map.Entry<StackKey, StackState> entry : entries.entrySet()) {
            if (oldest == null || entry.getValue().lastHitTick() < oldest.getValue().lastHitTick()) {
                oldest = entry;
            }
        }
        if (oldest != null && !oldest.getKey().equals(incoming)) {
            entries.remove(oldest.getKey());
        }
    }
}
