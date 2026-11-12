package com.astrum.pcrd.impl.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The two guards of spec §7.4, as pure arithmetic over an injected clock.
 *
 * <h2>The failure this exists to stop</h2>
 * N players carrying the same character each fire {@code area_charge}; every hook charges every
 * player in range. Without guards the total charge rate grows with N, so a party of djeeta clones
 * fills every gauge faster than the encounter can drain them. Two independent limits close that:
 *
 * <ul>
 *   <li>a <b>pair cooldown</b> — one caster may fill one receiver at most once per
 *       {@code areaChargePairCooldownTicks};</li>
 *   <li>a <b>per-receiver per-second cap</b> — however many casters target one player, that player
 *       gains at most {@code areaChargeReceiveCapPerSecond} from {@code area_charge} each second.</li>
 * </ul>
 *
 * <p>The pair guard bounds one edge of the graph; the cap bounds a vertex. A crowd can therefore
 * neither multiply one caster's work nor pile onto one victim.
 *
 * <h2>Staleness is judged when called</h2>
 * There is no timer and no tick listener. A pair is stale when the next grant for that pair observes
 * {@code nowTick - lastTick >= cooldown}; a receiver's window is stale when the next grant observes a
 * different {@code nowSecond}. This is the same lazy-expiry rule the stack table uses (§4.4), and it
 * means an idle server does no work at all.
 *
 * <h2>Configuration is read at the point of use, through one seam</h2>
 * The two knobs are suppliers, and both are consulted on <b>every</b> {@link #grant} call (spec §8.4
 * guardrail 1). The no-argument constructor points them at {@link AreaChargeTuning}, the single class
 * that knows "a runtime operator override wins; otherwise read the config right now". Nothing is
 * copied into a field, so a reload is honoured immediately — and because an override is operator
 * intent rather than a cached config value, it is allowed to outlive one tick (see
 * {@link AreaChargeTuning} for why that is not a §8.4 violation).
 *
 * <h2>It may reduce, never throw</h2>
 * Something that looks hostile — a {@code NaN} request, a backwards game-time clock (a new world),
 * a negative window — is answered with a smaller grant rather than an exception. This is called
 * inside a damage sequence, and a guard that throws costs more than the charge it withheld. Zero is
 * a valid answer for one receiver; the caller must still process the others.
 */
@ApiStatus.Internal
public final class AreaChargeBudget {

    /** Memory bound for the pair table; the guard is per pair, so eviction can only be stale entries. */
    private static final int MAX_TRACKED_PAIRS = 4096;

    /** Last tick a grant was actually made for each caster→receiver edge. */
    private final Map<PairKey, Long> lastGrantTick = new HashMap<>();

    /** Per receiver: {@code [second, granted]} for the second currently open. */
    private final Map<UUID, double[]> receivedThisSecond = new HashMap<>();

    private final IntSupplier pairCooldownTicks;
    private final DoubleSupplier receiveCapPerSecond;

    /** Production constructor: both values come from {@link AreaChargeTuning} on every call. */
    public AreaChargeBudget() {
        this(AreaChargeTuning::pairCooldownTicks, AreaChargeTuning::receiveCapPerSecond);
    }

    /** Test seam: the same logic with the clock-independent knobs supplied directly. */
    AreaChargeBudget(IntSupplier pairCooldownTicks, DoubleSupplier receiveCapPerSecond) {
        this.pairCooldownTicks = pairCooldownTicks;
        this.receiveCapPerSecond = receiveCapPerSecond;
    }

    /**
     * Asks the guards how much of {@code requested} one receiver may actually gain.
     *
     * <p>Only a granted amount arms the pair cooldown. A grant refused by the per-second cap is
     * therefore not punished a second time by the pair rule — the receiver's ceiling was the reason,
     * and that ceiling resets with the next second.
     *
     * @param caster    the player whose hook fired
     * @param receiver  the player about to be credited
     * @param requested charge points the hook wants to give, already scaled by diminishing returns
     * @param nowTick   current game time in ticks (the pair cooldown's clock)
     * @param nowSecond current game time in seconds, i.e. {@code nowTick / 20} (the cap's window)
     * @return the amount to credit, between {@code 0.0} and {@code requested}; never negative or NaN
     */
    public double grant(UUID caster, UUID receiver, double requested, long nowTick, long nowSecond) {
        if (caster == null || receiver == null || !(requested > 0.0) || !Double.isFinite(requested)) {
            return 0.0;
        }

        int cooldown = pairCooldownTicks();
        PairKey pair = new PairKey(caster, receiver);
        Long lastTick = lastGrantTick.get(pair);
        if (cooldown > 0 && lastTick != null && nowTick >= lastTick && nowTick - lastTick < cooldown) {
            return 0.0;
        }

        double cap = receiveCapPerSecond();
        double[] window = receivedThisSecond.get(receiver);
        double grantedSoFar = window != null && (long) window[0] == nowSecond ? window[1] : 0.0;
        double granted = Math.min(requested, Math.max(0.0, cap - grantedSoFar));
        if (!(granted > 0.0)) {
            return 0.0;
        }

        lastGrantTick.put(pair, nowTick);
        receivedThisSecond.put(receiver, new double[] {nowSecond, grantedSoFar + granted});
        pruneStalePairs(nowTick, cooldown);
        return granted;
    }

    /** Drops every pair whose cooldown has elapsed; called only when the table reaches its bound. */
    private void pruneStalePairs(long nowTick, int cooldown) {
        if (lastGrantTick.size() < MAX_TRACKED_PAIRS) {
            return;
        }
        lastGrantTick.values().removeIf(last -> nowTick >= last && nowTick - last >= cooldown);
    }

    /** Forgets everything, e.g. when the server stops: the next world starts with a clean clock. */
    public void reset() {
        lastGrantTick.clear();
        receivedThisSecond.clear();
    }

    private int pairCooldownTicks() {
        int ticks;
        try {
            ticks = pairCooldownTicks.getAsInt();
        } catch (RuntimeException unavailable) {
            // Defensive: the default supplier is AreaChargeTuning, which never throws. This keeps
            // an injected supplier from breaking the sink's "must not throw" contract.
            ticks = AreaChargeTuning.DEFAULT_PAIR_COOLDOWN_TICKS;
        }
        return Math.max(0, ticks);
    }

    private double receiveCapPerSecond() {
        double cap;
        try {
            cap = receiveCapPerSecond.getAsDouble();
        } catch (RuntimeException unavailable) {
            cap = AreaChargeTuning.DEFAULT_RECEIVE_CAP_PER_SECOND;
        }
        return Double.isFinite(cap) && cap >= 0.0
                ? cap : AreaChargeTuning.DEFAULT_RECEIVE_CAP_PER_SECOND;
    }

    /** One directed edge of the charge graph: this caster filling this receiver. */
    private record PairKey(UUID caster, UUID receiver) {
    }
}
