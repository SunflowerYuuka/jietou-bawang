package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.Registries;

/**
 * The growth numbers the mod actually ships, read from {@code djeeta.json} instead of retyped.
 *
 * <h2>Why the tests read the datapack file</h2>
 * Spec §6.4 pins 姬塔 at {@code expBase 100}, {@code expPerLevel 1.15}, {@code maxLevel 30},
 * {@code tier2Threshold 3}, {@code tier3Threshold 8} and "tier 2 multiplies hook index 1 by 1.5". If
 * the tests restated those numbers, a pack edit could move the curve and every test would still pass
 * — the tests would then be checking a copy of the design, not the design. Loading the shipped json
 * through the same {@link CharacterDef#CODEC} the registry loader uses means a change to the pack
 * either shows up in these assertions or fails to parse at all.
 *
 * <p>The assertions still name literal expectations (115 for level 2, tier 2 at affection 3): those
 * are the <b>worked examples</b> of the rule, and a test that computed its own expectation with the
 * code under test would assert nothing.
 *
 * <p>Where a curve is deliberately <em>not</em> the shipped one — hostile datapack data, a pack with
 * no tier 3, a duplicate modifier — the test builds that {@link GrowthDef}/{@link AffectionDef} inline
 * and says why in its own comment.
 */
final class ShippedGrowth {

    /** 姬塔, parsed from the packaged datapack file (the same path the registry loader reads). */
    static final CharacterDef DJEETA = load();

    private ShippedGrowth() {
    }

    /** {@return 姬塔's shipped experience curve: 100 at a growth of 1.15, capped at 30} */
    static GrowthDef growth() {
        return DJEETA.growth();
    }

    /** {@return 姬塔's shipped affection definition: tiers at 3 and 8, hook 1 × 1.5 at tier 2} */
    static AffectionDef affection() {
        return DJEETA.affection();
    }

    private static CharacterDef load() {
        String path = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS)
                + "/djeeta.json";
        try (InputStream in = ShippedGrowth.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("djeeta.json is not on the test classpath: " + path);
            }
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
                    .getOrThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
