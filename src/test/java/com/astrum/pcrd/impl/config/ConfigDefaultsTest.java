package com.astrum.pcrd.impl.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.io.StringWriter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the <b>first write</b> of the two config files: what NeoForge produces when no TOML exists
 * yet, and whether every key from spec §8.2 / §8.3 is there with the documented default.
 *
 * <h2>Why this is a real test and not a formality</h2>
 * {@code ConfigTracker.createDefaultConfig} calls {@code IConfigSpec.correct(config)} on an
 * <em>empty</em> config, and {@code correct} probes each option with {@code test(null)} before
 * deciding it is missing. Anything in a validator that cannot survive {@code null} therefore throws
 * during world start-up, before the file is written:
 *
 * <pre>
 * java.lang.NullPointerException: Exception in server tick loop
 *   at java.util.ImmutableCollections$ListN.indexOf
 *   at net.neoforged.neoforge.common.ModConfigSpec$ValueSpec.test
 *   at net.neoforged.neoforge.common.ModConfigSpec.correct
 *   at net.neoforged.fml.config.ConfigTracker.createDefaultConfig
 * </pre>
 *
 * That is exactly what {@code Builder#defineInList(path, default, List.of(...))} does, because
 * {@code List.of(...).contains(null)} throws instead of answering {@code false}. It is invisible to
 * any test that only reads defaults, since {@code get()} never consults a validator — hence this
 * test drives {@code correct} the same way the loader does.
 *
 * <p>The defaults are asserted through a TOML round-trip rather than through {@code .get()}: the
 * round-trip proves the value that actually lands in the file an operator opens, which is the thing
 * spec §8.2 describes.
 */
class ConfigDefaultsTest {

    /** The config as NeoForge builds it for a save that has no {@code astrum-server.toml} yet. */
    private static CommentedConfig writtenDefaults(net.neoforged.neoforge.common.ModConfigSpec spec) {
        // Same container type and the same call as ConfigTracker.createDefaultConfig.
        CommentedConfig config = TomlFormat.instance().createConfig();
        spec.correct(config);
        return config;
    }

    private static String toToml(CommentedConfig config) {
        StringWriter out = new StringWriter();
        new TomlWriter().write(config, out);
        return out.toString();
    }

    /** Re-reads the generated text so assertions see what an operator's file really says. */
    private static CommentedConfig reload(String text) {
        CommentedConfig config = TomlFormat.instance().createConfig();
        TomlFormat.instance().createParser().parse(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), config, ParsingMode.REPLACE);
        return config;
    }

    @Test
    void freshServerConfigIsWrittenWithoutThrowing() {
        assertDoesNotThrow(() -> writtenDefaults(ServerConfig.SPEC),
                "写默认配置时不得抛异常：ConfigTracker 用空配置调 correct()，校验器必须容忍 null");
    }

    @Test
    void freshClientConfigIsWrittenWithoutThrowing() {
        assertDoesNotThrow(() -> writtenDefaults(ClientConfig.SPEC));
    }

    @Test
    void serverTomlHasEverySpecSection() {
        String text = toToml(writtenDefaults(ServerConfig.SPEC));
        for (String section : new String[] { "[balance]", "[ub]", "[wheel]", "[multiplayer]" }) {
            assertTrue(text.contains(section), "生成的 toml 缺少小节 " + section + "\n" + text);
        }
    }

    @Test
    void clientTomlHasEverySpecSection() {
        String text = toToml(writtenDefaults(ClientConfig.SPEC));
        for (String section : new String[] { "[wheel]", "[camera]" }) {
            assertTrue(text.contains(section), "生成的 toml 缺少小节 " + section + "\n" + text);
        }
    }

    @Test
    void serverDefaultsMatchSpec() {
        CommentedConfig toml = reload(toToml(writtenDefaults(ServerConfig.SPEC)));

        assertEquals(true, toml.get("balance.enableDiminishingReturns"));
        assertEquals(0.7, toml.<Number>get("balance.diminishingBase").doubleValue(), 1e-9);

        assertEquals(100, toml.<Number>get("ub.ubGlobalCooldownTicks").intValue());
        assertEquals(1.0, toml.<Number>get("ub.ubChargePerDamage").doubleValue(), 1e-9);
        assertEquals(0.33, toml.<Number>get("ub.ubChargePerDamageTaken").doubleValue(), 1e-9);
        assertEquals(20.0, toml.<Number>get("ub.ubChargePerKill").doubleValue(), 1e-9);
        assertEquals(0.2, toml.<Number>get("ub.ubChargePerSecond").doubleValue(), 1e-9);

        assertEquals("ALLOW", toml.get("wheel.scrollSwitchPolicy"));

        assertEquals("ALL_PLAYERS", toml.get("multiplayer.allyMode"));
        assertEquals(20, toml.<Number>get("multiplayer.areaChargePairCooldownTicks").intValue());
        assertEquals(6.0, toml.<Number>get("multiplayer.areaChargeReceiveCapPerSecond").doubleValue(), 1e-9);
        assertEquals(8, toml.<Number>get("multiplayer.maxActiveShowcaseEntities").intValue());

        assertEquals(100, toml.<Number>get("balance.stackTtlTicks").intValue());
        assertEquals(40, toml.<Number>get("balance.comboWindowTicks").intValue());
    }

    @Test
    void clientDefaultsMatchSpec() {
        CommentedConfig toml = reload(toToml(writtenDefaults(ClientConfig.SPEC)));

        assertEquals(false, toml.get("wheel.enableScrollSwitching"));
        assertEquals(0.40, toml.<Number>get("wheel.wheelScale").doubleValue(), 1e-9);
        assertEquals(0.30, toml.<Number>get("wheel.wheelOpacity").doubleValue(), 1e-9);
        assertEquals(false, toml.get("camera.ubCameraPush"));
    }

    /**
     * No key beyond the spec's two tables: an accidental extra option is as much a spec violation as
     * a missing one.
     */
    /**
     * Every leaf key the file contains, exactly — not just the sections.
     *
     * <p>This is the strict half of the spec check: an accidental extra option anywhere in the file is
     * as much a spec violation as a missing one, and a section-only comparison would happily accept a
     * new key inside {@code [balance]}. Adding {@code stackTtlTicks} and {@code comboWindowTicks} to
     * spec §8.2 therefore required this list to grow too, in that order: spec first, then the test.
     */
    @Test
    void theServerConfigHasExactlyTheSpecKeys() {
        assertEquals(Set.of(
                "balance.enableDiminishingReturns",
                "balance.diminishingBase",
                "balance.stackTtlTicks",
                "balance.comboWindowTicks",
                "ub.ubGlobalCooldownTicks",
                "ub.ubChargePerDamage",
                "ub.ubChargePerDamageTaken",
                "ub.ubChargePerKill",
                "ub.ubChargePerSecond",
                "wheel.scrollSwitchPolicy",
                "multiplayer.allyMode",
                "multiplayer.areaChargePairCooldownTicks",
                "multiplayer.areaChargeReceiveCapPerSecond",
                "multiplayer.maxActiveShowcaseEntities"),
                leafKeys(writtenDefaults(ServerConfig.SPEC)));
    }

    @Test
    void noKeysBeyondSpec() {
        assertEquals(Set.of("balance", "ub", "wheel", "multiplayer"),
                topLevelKeys(writtenDefaults(ServerConfig.SPEC)));
        assertEquals(Set.of("wheel", "camera"), topLevelKeys(writtenDefaults(ClientConfig.SPEC)));
    }

    /**
     * {@return the dotted paths of every leaf (non-table) key of a config}
     *
     * <p>Walks {@code entrySet()} recursively rather than {@code valueMap()}, which is deprecated in
     * nightconfig 3.8.3 — using it would put a warning in the build output.
     */
    private static Set<String> leafKeys(CommentedConfig config) {
        Set<String> keys = new TreeSet<>();
        collectLeaves("", config, keys);
        return keys;
    }

    private static void collectLeaves(String prefix, CommentedConfig config, Set<String> into) {
        for (var entry : config.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof CommentedConfig child) {
                collectLeaves(path, child, into);
            } else {
                into.add(path);
            }
        }
    }

    /**
     * {@return the top-level key names of a config}
     *
     * <p>Walks {@code entrySet()} rather than {@code valueMap()}, which is deprecated in
     * nightconfig 3.8.3 — using it would put a warning in the build output.
     */
    private static Set<String> topLevelKeys(CommentedConfig config) {
        Set<String> keys = new TreeSet<>();
        for (var entry : config.entrySet()) {
            keys.add(entry.getKey());
        }
        return keys;
    }
}
