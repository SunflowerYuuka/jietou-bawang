package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.config.ServerConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.neoforged.fml.config.IConfigSpec;
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
 *
 * <p>The file path is deliberately different: a TOML {@code 0} for the pair cooldown is a
 * persistent, auditable "off" and is still honoured when no override is set. One test loads
 * exactly that file into the real spec, so the asymmetry is pinned in both directions.
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
    void aZeroCooldownOverrideIsRejectedAndChangesNothing() {
        AreaChargeTuning.override(7, 2.5);

        assertThrows(IllegalArgumentException.class,
                () -> AreaChargeTuning.override(0, null),
                "运行时覆盖填 0 等于用一条命令关掉成对刹车，必须拒绝");

        assertEquals(7, AreaChargeTuning.pairCooldownTicks(), "被拒绝的调用不得改动已有 override");
        assertEquals(2.5, AreaChargeTuning.receiveCapPerSecond(), 1e-9,
                "同一次被拒绝的调用也不得改动上限");
    }

    /**
     * The other half of the ruling: a {@code 0} written in the TOML file is a deliberate "off" and
     * must still work when no override is set. The spec is loaded with that value and unloaded in
     * {@code finally}, so the test leaves no loaded config behind.
     */
    @Test
    void aZeroCooldownInTheTomlFileIsHonouredWhenNoOverrideIsSet() throws Exception {
        CommentedConfig config = TomlFormat.instance().createConfig();
        ServerConfig.SPEC.correct(config);
        config.set("multiplayer.areaChargePairCooldownTicks", 0);

        // IConfigSpec.ILoadedConfig is sealed and its only permitted implementation
        // (LoadedConfig) is package-private, so the test constructs it reflectively. The spec is
        // unloaded in finally, leaving no loaded config behind for other tests.
        Class<?> loadedConfigClass = Class.forName("net.neoforged.fml.config.LoadedConfig");
        var constructor = loadedConfigClass.getDeclaredConstructor(
                CommentedConfig.class, java.nio.file.Path.class,
                net.neoforged.fml.config.ModConfig.class);
        constructor.setAccessible(true);
        IConfigSpec.ILoadedConfig loaded =
                (IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null);
        try {
            ServerConfig.SPEC.acceptConfig(loaded);
            assertEquals(0, AreaChargeTuning.pairCooldownTicks(),
                    "TOML 的 0 是明确、可审计的关闸意图，无覆盖时必须生效");
        } finally {
            ServerConfig.SPEC.acceptConfig(null);
        }
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
