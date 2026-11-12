package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.config.ServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The operator-tuning seam for the two anti-loop guards.
 *
 * <h2>What "wins" means, and why it is tested here</h2>
 * A runtime override is explicit operator intent and must take precedence over the TOML value; with
 * no override, the config is the answer. That precedence exists in exactly one class, so it is tested
 * in exactly one place. The fallback assertions use the documented defaults because a unit test has
 * no loaded {@code ModConfigSpec}; that is also the path a call before config load takes.
 *
 * <h2>Why absurd values are rejected</h2>
 * These knobs are the brakes that stop N djeeta players from charging each other in a loop. An
 * operator typo (`-1`, `0`, `NaN`) must fail loudly at the command boundary, not be stored and
 * silently turn a guard off. Each rejection test also checks that a previously valid override
 * survives the rejected call.
 */
class AreaChargeTuningTest {

    @BeforeEach
    @AfterEach
    void clearTuning() {
        AreaChargeTuning.clearOverride();
    }

    @Test
    void theRuntimeOverrideWinsOverTheConfigValue() {
        AreaChargeTuning.override(7, 2.5);
        assertEquals(7, AreaChargeTuning.pairCooldownTicks());
        assertEquals(2.5, AreaChargeTuning.receiveCapPerSecond(), 1e-9);
    }

    @Test
    void clearingTheOverrideFallsBackToTheConfigPath() {
        AreaChargeTuning.override(7, 2.5);
        AreaChargeTuning.clearOverride();
        assertEquals(ServerConfig.MultiplayerOptions.DEFAULT_PAIR_COOLDOWN_TICKS,
                AreaChargeTuning.pairCooldownTicks());
        assertEquals(ServerConfig.MultiplayerOptions.DEFAULT_RECEIVE_CAP_PER_SECOND,
                AreaChargeTuning.receiveCapPerSecond(), 1e-9);
    }

    @Test
    void aNullKnobLeavesTheOtherOverrideInPlace() {
        AreaChargeTuning.override(7, 2.5);
        AreaChargeTuning.override(9, null);   // a command that only changes the cooldown
        assertEquals(9, AreaChargeTuning.pairCooldownTicks());
        assertEquals(2.5, AreaChargeTuning.receiveCapPerSecond(), 1e-9);
    }

    @Test
    void aZeroCooldownIsTheConfigDocumentedOffAndIsAccepted() {
        AreaChargeTuning.override(0, null);
        assertEquals(0, AreaChargeTuning.pairCooldownTicks());
    }

    @Test
    void absurdOverridesAreRejectedAndThePreviousOneSurvives() {
        AreaChargeTuning.override(7, 2.5);

        assertThrows(IllegalArgumentException.class,
                () -> AreaChargeTuning.override(-1, null), "负的成对 CD 必须被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> AreaChargeTuning.override(null, 0.0), "0 上限等于关掉护栏");
        assertThrows(IllegalArgumentException.class,
                () -> AreaChargeTuning.override(null, -2.0), "负上限必须被拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> AreaChargeTuning.override(null, Double.NaN), "NaN 不会被 clamp 捕获，必须显式拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> AreaChargeTuning.override(null, Double.POSITIVE_INFINITY), "无限上限必须被拒绝");

        assertEquals(7, AreaChargeTuning.pairCooldownTicks(), "被拒绝的调用不得改动已有 override");
        assertEquals(2.5, AreaChargeTuning.receiveCapPerSecond(), 1e-9);
    }
}
