package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.Astrum;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Who has hurt a victim so far, per attacker and per victim, so a kill can pay the characters that
 * actually did the damage (spec §6.1).
 *
 * <h2>Why a ledger and not a counter on the entity</h2>
 * Experience is decided at the <b>end</b> of a fight ("the mob died — who gets it?"), but damage is
 * observed one hit at a time. Something has to remember the hits in between, and the two obvious homes
 * are both wrong: a data attachment would make transient bookkeeping part of a player's save (and a
 * crash mid-fight would leave a damage ledger on disk), and a field on the victim would follow the
 * entity across dimension changes and resurrection. So this is a process-local table with three
 * lifetimes, all of them explicit:
 *
 * <ul>
 *   <li><b>The kill</b> — {@link #take(UUID, UUID)} removes the pair's entries and hands them to
 *       {@code GrowthEvents}, which splits the experience and is done with them. That is the event
 *       that ends their meaning.</li>
 *   <li><b>A leak guard</b> — a mob that is damaged and then despawns, falls in lava or is unloaded
 *       never fires the death hook, so {@link #note} prunes entries older than
 *       {@link #STALE_TICKS} once the table grows past {@link #PRUNE_THRESHOLD}. No tick listener:
 *       the prune happens on the next recorded hit, which is the only moment the cost is already paid.
 *   <li><b>The session</b> — {@link #clear()} on {@code ServerStoppingEvent}, so nothing survives into
 *       the next world (the same reason {@code CombatEvents} clears its pending batches there).</li>
 * </ul>
 *
 * <h2>Why this is not "the player's damage share"</h2>
 * Spec §6.1 says 按玩家造成的伤害占比分摊经验; the character is the thing that levels, so the split has
 * to name characters. The convention this class records is the one the plan asks for: <b>the attachment
 * damage each character's own hooks produced</b> against that victim, the same damage Task 9 established
 * still counts toward charge. A character that only ever charged the gauge (never dealt damage) has no
 * entry, and the caller awards nothing rather than inventing a share for it.
 *
 * <p>Not thread-safe and does not need to be: every call comes from a server-thread event handler.
 */
@ApiStatus.Internal
public final class ContributionTracker {

    /** Entries older than this many ticks can no longer belong to a live fight, in game time. */
    public static final long STALE_TICKS = 2_400L;

    /** How many (attacker, victim) pairs may accumulate before stale ones are pruned. */
    public static final int PRUNE_THRESHOLD = 256;

    /** Attacker and victim to that victim's per-character damage, plus when it was last touched. */
    private static final Map<Key, Tally> LEDGER = new HashMap<>();

    private ContributionTracker() {
    }

    /**
     * Records that {@code character} dealt {@code damage} to {@code victim}.
     *
     * @param attacker  the player whose wheel produced the damage
     * @param victim    the entity that took it
     * @param character the character the damage is attributed to
     * @param damage    health removed; non-positive amounts are ignored
     * @param nowTick   the level's game time, for the staleness prune
     */
    public static void note(UUID attacker, UUID victim, ResourceLocation character, double damage,
            long nowTick) {
        if (attacker == null || victim == null || character == null) {
            return;
        }
        if (!(damage > 0.0) || !Double.isFinite(damage)) {
            return;
        }
        if (LEDGER.size() > PRUNE_THRESHOLD) {
            prune(nowTick);
        }
        Key key = new Key(attacker, victim);
        Tally tally = LEDGER.computeIfAbsent(key, ignored -> new Tally());
        tally.add(character, damage, nowTick);
    }

    /**
     * {@return the per-character damage recorded for this pair, and forgets it}
     *
     * <p>Taking rather than reading is the whole contract: a kill settles the ledger, so a second death
     * event for the same victim (a mod reviving it, a totem) cannot pay the same damage out twice.
     *
     * @param attacker the player credited with the kill
     * @param victim   the entity that died
     */
    public static Map<ResourceLocation, Double> take(UUID attacker, UUID victim) {
        if (attacker == null || victim == null) {
            return Map.of();
        }
        Tally tally = LEDGER.remove(new Key(attacker, victim));
        return tally == null ? Map.of() : tally.damage();
    }

    /**
     * <b>Replaces</b> what this character is credited for, after the hit's real outcome is known.
     *
     * <h2>The defect this exists for</h2>
     * A credit has to be on the ledger <b>before</b> {@code hurt()} runs, because a killing blow settles the
     * ledger from inside that call — the death handler pays the experience while the damage is still being
     * applied. But the amount known at that moment is a <b>projection</b>, and {@code hurt()} can refuse it
     * outright (invulnerability, a cancelled event, an already-dead body) or reduce it (armour, absorption,
     * resistance). The projection could not be taken back, so a UB that dealt nothing still shared the exp of a
     * kill somebody else earned.
     *
     * <p>So the projected note stays where it is — that is what makes a UB's own killing blow pay — and this
     * call is made afterwards with the health the victim actually lost. When the kill already happened the pair
     * is gone ({@link #take}), and correcting it is a no-op by construction: the settled payout is not rewritten
     * after the fact.
     *
     * <p>Only ever lowers: a correction that would <b>raise</b> the credit is ignored, because the caller's
     * projection is the optimistic bound and anything above it would be inventing damage. A correction to zero
     * removes the character's entry entirely, so a hit that did nothing leaves nothing behind.
     *
     * @param attacker  the player whose wheel produced the hit
     * @param victim    the entity that took it
     * @param character the character the damage is attributed to
     * @param actual    health actually removed, as measured around {@code hurt()}
     */
    public static void correct(UUID attacker, UUID victim, ResourceLocation character, double actual) {
        if (attacker == null || victim == null || character == null) {
            return;
        }
        Tally tally = LEDGER.get(new Key(attacker, victim));
        if (tally == null) {
            return;   // the kill already settled this pair: a payout is not rewritten afterwards
        }
        double measured = actual > 0.0 && Double.isFinite(actual) ? actual : 0.0;
        tally.lowerTo(character, measured);
    }

    /** Drops everything: one world's fights are not the next world's. */
    public static void clear() {
        LEDGER.clear();
    }

    /** {@return how many (attacker, victim) pairs are being tracked; for the log and for tests} */
    public static int size() {
        return LEDGER.size();
    }

    /** Drops pairs whose last recorded hit is older than {@link #STALE_TICKS}. */
    private static void prune(long nowTick) {
        Iterator<Map.Entry<Key, Tally>> entries = LEDGER.entrySet().iterator();
        int dropped = 0;
        while (entries.hasNext()) {
            if (nowTick - entries.next().getValue().lastTick > STALE_TICKS) {
                entries.remove();
                dropped++;
            }
        }
        if (dropped > 0) {
            Astrum.LOGGER.debug("astrum: growth: dropped {} stale contribution entries ({} pairs left)",
                    dropped, LEDGER.size());
        }
    }

    /** One attacker → one victim. */
    private record Key(UUID attacker, UUID victim) {
    }

    /** The damage each character has done to one victim, in the order they first contributed. */
    private static final class Tally {

        private final Map<ResourceLocation, Double> damage = new LinkedHashMap<>();
        private long lastTick = Long.MIN_VALUE;

        private void add(ResourceLocation character, double amount, long nowTick) {
            damage.merge(character, amount, Double::sum);
            lastTick = Math.max(lastTick, nowTick);
        }

        /**
         * Lowers this character's credit to at most {@code measured}; zero removes the entry.
         *
         * <p>"At most" and not "to": {@link #correct} is called with the health the victim actually lost, but
         * two hits of one ultimate can land before either correction runs, and the second hit's measurement
         * must not erase the first hit's real damage. Lowering is therefore the only direction that cannot
         * invent a contribution — the ledger never exceeds what was measured, and never exceeds what was
         * projected either.
         */
        private void lowerTo(ResourceLocation character, double measured) {
            Double current = damage.get(character);
            if (current == null) {
                return;
            }
            if (!(measured > 0.0)) {
                damage.remove(character);
                return;
            }
            if (measured < current) {
                damage.put(character, measured);
            }
        }

        private Map<ResourceLocation, Double> damage() {
            // A copy, in contribution order: the caller splits experience from it and logs the split,
            // and two runs of the same fight should read the same way.
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(damage));
        }
    }
}
