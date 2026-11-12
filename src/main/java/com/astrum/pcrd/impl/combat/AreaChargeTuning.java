package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.config.ServerConfig;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>single</b> place that decides what {@code area_charge}'s two anti-loop knobs currently are:
 * a runtime override when an operator has set one, otherwise the server config read at that moment.
 *
 * <h2>Why a seam and not direct config reads in each guard</h2>
 * The guards live in a pure kernel ({@link AreaChargeBudget}) that must not know about commands,
 * permissions or config files, and the precedence rule "override wins over config" must exist
 * exactly once. If {@code AreaChargeBudget} read {@code ServerConfig} and some other caller
 * separately applied an override, one of them would drift: the budget would enforce 20 ticks while
 * the command reported 40. Both the budget and any future caller ask this class instead.
 *
 * <h2>Why the override does not violate spec §8.4 guardrail 1</h2>
 * That guardrail forbids <em>caching</em> a config value in a static field: a copy taken before the
 * file loads freezes the default forever, and a copy taken once ignores later edits. An override is
 * not a copy of the config — it is explicit operator intent that outlives one tick by design. With
 * <b>no</b> override set, {@link #pairCooldownTicks()} and {@link #receiveCapPerSecond()} still call
 * {@code ConfigValue.get()} on <b>every</b> invocation, so a reload is honoured immediately. The
 * override is cleared when the server stops ({@code CombatEvents.onServerStopping}), so a value
 * tuned in one world cannot silently govern the next.
 *
 * <h2>What Task 14 calls</h2>
 * Task 14's {@code /astrum} command (permission level 2) is the intended caller. It must not build
 * its own precedence rule or write the TOML; it calls:
 *
 * <pre>{@code
 * AreaChargeTuning.override(40, 8.0);   // set either knob (null leaves that one unchanged)
 * AreaChargeTuning.clearOverride();     // remove both, back to the config values
 * }</pre>
 *
 * <p>{@link #override} <b>rejects</b> absurd values with {@link IllegalArgumentException} instead of
 * accepting them: a negative pair cooldown, or a per-second cap that is zero, negative, NaN or
 * infinite. A typo must never silently switch an anti-loop brake off. The command should catch the
 * exception and report the message to the sender. The persisted defaults remain the TOML entries
 * {@code [multiplayer] areaChargePairCooldownTicks = 20} and
 * {@code areaChargeReceiveCapPerSecond = 6.0}; nothing here changes what a pack author writes.
 *
 * <h2>Concurrency</h2>
 * These are two scalar settings, not per-entity state, and the server thread is the only writer.
 * They are {@code volatile} so a future off-thread reader (a command diagnostic, say) sees a
 * complete value rather than a torn one; there is no compound state to make consistent.
 */
@ApiStatus.Internal
public final class AreaChargeTuning {

    /**
     * The pair cooldown used when neither an override nor a loaded config can answer.
     *
     * <p>An alias of the config's own default, not a second number: a unit test and a pre-load call
     * must see exactly what an operator's untouched file will say.
     */
    public static final int DEFAULT_PAIR_COOLDOWN_TICKS =
            ServerConfig.MultiplayerOptions.DEFAULT_PAIR_COOLDOWN_TICKS;

    /** The per-second cap used when neither an override nor a loaded config can answer. */
    public static final double DEFAULT_RECEIVE_CAP_PER_SECOND =
            ServerConfig.MultiplayerOptions.DEFAULT_RECEIVE_CAP_PER_SECOND;

    /** The pair cooldown an operator has pinned, or {@code null} when the config decides. */
    private static volatile Integer pairCooldownOverride;

    /** The per-second cap an operator has pinned, or {@code null} when the config decides. */
    private static volatile Double receiveCapOverride;

    private AreaChargeTuning() {
    }

    /**
     * {@return the pair cooldown in ticks: the operator's override, else the configured value}
     *
     * <p>When no override is set the config is read lazily on this call (spec §8.4 guardrail 1);
     * if the config cannot be read yet (a unit test, or a call before load), the documented default
     * answers instead so a guard never disappears because a file is missing.
     */
    public static int pairCooldownTicks() {
        Integer override = pairCooldownOverride;
        if (override != null) {
            return override;
        }
        try {
            return Math.max(0, ServerConfig.MULTIPLAYER.areaChargePairCooldownTicks.get());
        } catch (RuntimeException notLoaded) {
            return DEFAULT_PAIR_COOLDOWN_TICKS;
        }
    }

    /**
     * {@return charge points one receiver may gain per second: override, else the configured value}
     *
     * <p>Same lazy rule as {@link #pairCooldownTicks()}. A configured value that is somehow not a
     * finite number is replaced by the documented default rather than allowed to poison the budget.
     */
    public static double receiveCapPerSecond() {
        Double override = receiveCapOverride;
        if (override != null) {
            return override;
        }
        try {
            double configured = ServerConfig.MULTIPLAYER.areaChargeReceiveCapPerSecond.get();
            return Double.isFinite(configured) ? configured : DEFAULT_RECEIVE_CAP_PER_SECOND;
        } catch (RuntimeException notLoaded) {
            return DEFAULT_RECEIVE_CAP_PER_SECOND;
        }
    }

    /**
     * Pins one or both knobs for this server session; {@code null} leaves that knob as it was.
     *
     * <p>Validation happens before either field is written, so a rejected call changes nothing.
     *
     * @param pairCooldownTicks new pair cooldown in ticks, or {@code null} to leave it unchanged;
     *                          must be {@code >= 0} (0 is the config's documented "no cooldown")
     * @param receiveCapPerSecond new per-second cap, or {@code null} to leave it unchanged; must be
     *                            finite and {@code > 0}
     * @throws IllegalArgumentException if a value would disable a brake by accident
     */
    public static void override(Integer pairCooldownTicks, Double receiveCapPerSecond) {
        if (pairCooldownTicks != null && pairCooldownTicks < 0) {
            throw new IllegalArgumentException(
                    "astrum: areaChargePairCooldownTicks must be >= 0, got " + pairCooldownTicks);
        }
        if (receiveCapPerSecond != null
                && (!(receiveCapPerSecond > 0.0) || !Double.isFinite(receiveCapPerSecond))) {
            throw new IllegalArgumentException(
                    "astrum: areaChargeReceiveCapPerSecond must be finite and > 0, got "
                            + receiveCapPerSecond);
        }
        if (pairCooldownTicks != null) {
            pairCooldownOverride = pairCooldownTicks;
        }
        if (receiveCapPerSecond != null) {
            receiveCapOverride = receiveCapPerSecond;
        }
    }

    /**
     * Removes both overrides, so both guards read the server config again.
     *
     * <p>Called when the server stops; a command may also call it to undo a tuning without a restart.
     */
    public static void clearOverride() {
        pairCooldownOverride = null;
        receiveCapOverride = null;
    }
}
