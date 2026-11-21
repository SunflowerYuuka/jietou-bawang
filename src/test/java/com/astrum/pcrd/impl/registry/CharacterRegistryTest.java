package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A broken datapack entry must never escape as an exception: the datapack registry loader turns any
 * decode failure into a fatal {@code IllegalStateException} that aborts world loading, so the codec
 * this registry is built with has to swallow failures and hand back {@link CharacterRegistry#INVALID}
 * for the bootstrap step to drop.
 */
class CharacterRegistryTest {

    private static final String VALID = """
            {"schemaVersion":1,"displayName":"姬塔","dimension":"charge",
             "effects":[{"trigger":"melee_hit","effect":"gain_charge","effectParams":{"amount":2.0}}],
             "ub":{"base":{"animation":"tempest_blade","hits":1,"damage":20.0},
                   "enhanced":{"animation":"seiga_itten","hits":4,"damage":20.0}},
             "growth":{"expBase":100,"expPerLevel":1.15,"maxLevel":30},
             "affection":{"tier2Threshold":3,"tier3Threshold":8,"skills":[]}}""";

    @Test
    void lenientCodecDecodesAValidEntry() {
        var def = CharacterRegistry.LENIENT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(VALID)).getOrThrow();
        assertNotSame(CharacterRegistry.INVALID, def);
        assertEquals(Dimension.CHARGE, def.dimension());
    }

    @Test
    void lenientCodecYieldsMarkerInsteadOfThrowingOnUnknownEnum() {
        var broken = VALID.replace("\"dimension\":\"charge\"", "\"dimension\":\"nonexistent\"");
        var result = CharacterRegistry.LENIENT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(broken));
        assertTrue(result.result().isPresent(), "坏 JSON 必须返回成功值，否则注册表加载会整体失败");
        assertSame(CharacterRegistry.INVALID, result.result().orElseThrow());
    }

    @Test
    void lenientCodecYieldsMarkerInsteadOfThrowingOnMissingRequiredField() {
        var broken = VALID.replace("\"expBase\":100,", "");
        assertSame(CharacterRegistry.INVALID,
                CharacterRegistry.LENIENT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(broken)).result().orElseThrow());
    }

    @Test
    void lenientCodecYieldsMarkerForJsonThatIsNotAnObject() {
        assertSame(CharacterRegistry.INVALID,
                CharacterRegistry.LENIENT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[1,2,3]")).result().orElseThrow());
    }

    @Test
    void salvageKeepsGoodEntriesAndNamesEverySkippedOne() {
        ResourceKey<CharacterDef> good = key("djeeta");
        ResourceKey<CharacterDef> bad = key("broken");
        Map<ResourceKey<CharacterDef>, CharacterDef> loaded = new LinkedHashMap<>();
        loaded.put(good, CharacterRegistryTest.validDef());
        loaded.put(bad, CharacterRegistry.INVALID);

        var salvage = CharacterRegistry.salvage(loaded.entrySet());

        assertEquals(List.of(good), List.copyOf(salvage.valid().keySet()));
        assertEquals(List.of(bad.location()), salvage.skipped(), "被跳过的条目必须能按 id 报出来，且只报一次");
    }

    @Test
    void placeholderSurvivingANetworkRoundTripIsStillSkipped() {
        // A synced datapack registry is re-encoded and decoded on the client, so the client's
        // placeholder is a different instance with equal contents. Filtering by identity alone
        // would let it through on the client while dropping it on the server.
        var encoded = CharacterRegistry.LENIENT_CODEC
                .encodeStart(JsonOps.INSTANCE, CharacterRegistry.INVALID).getOrThrow();
        var roundTripped = CharacterRegistry.LENIENT_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertNotSame(CharacterRegistry.INVALID, roundTripped, "对照组前提：往返后必须是新实例");

        Map<ResourceKey<CharacterDef>, CharacterDef> loaded = new LinkedHashMap<>();
        loaded.put(key("broken"), roundTripped);

        var salvage = CharacterRegistry.salvage(loaded.entrySet());

        assertEquals(List.of(key("broken").location()), salvage.skipped());
        assertTrue(salvage.valid().isEmpty());
    }

    /**
     * Pins the A6 boundary, which this mod cannot move.
     *
     * <p>{@code RegistryDataLoader.loadElementFromResource} has two stages and only the second is a
     * codec: it first does {@code JsonParser.parseReader(reader)} and only then
     * {@code decoder.parse(...)}. A file that is not valid JSON throws {@code JsonSyntaxException}
     * in stage 1, before {@link CharacterRegistry#LENIENT_CODEC} is ever consulted, and the loader's
     * outer {@code catch (Exception)} turns that into {@code IllegalStateException("Failed to load
     * registries")} — a fatal world-load abort. So: semantic errors are skipped with a WARN, syntax
     * errors abort the world load and are outside this mod's control.
     */
    @Test
    void jsonSyntaxErrorIsOutsideCodecControl() {
        String truncated = "{\"schemaVersion\":1,";

        // Stage 1 — the loader's parse. This is where a malformed file dies, and no codec is involved.
        assertThrows(com.google.gson.JsonSyntaxException.class,
                () -> JsonParser.parseReader(new java.io.StringReader(truncated)),
                "解析阶段（codec 之前）必须抛 JsonSyntaxException");

        // Stage 2 is never reached for that input, so the lenient codec cannot rescue it. Shown here
        // for contrast: the same codec does succeed once a well-formed JsonElement exists.
        assertDoesNotThrow(() -> CharacterRegistry.LENIENT_CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(VALID)).getOrThrow(),
                "语义层面的问题才归 codec 管，且已被 lenient 化");
    }

    @Test
    void skippedEntryWarnMessageNamesTheEntryId() {
        // The WARN text is the only diagnostic a pack author gets, so it is asserted directly
        // rather than trusting that salvage() reported the right id somewhere.
        var id = com.astrum.pcrd.impl.InternalIds.id("broken");

        String message = CharacterRegistry.skippedMessage(id);

        assertTrue(message.contains("astrum:broken"), "WARN 必须点名出问题的条目 id，实际: " + message);
        assertTrue(message.contains("skipped"), "WARN 必须说明是被跳过，实际: " + message);
    }

    @Test
    void salvageOfOnlyGoodEntriesSkipsNothing() {
        Map<ResourceKey<CharacterDef>, CharacterDef> loaded = new LinkedHashMap<>();
        loaded.put(key("djeeta"), validDef());

        var salvage = CharacterRegistry.salvage(loaded.entrySet());

        assertEquals(1, salvage.valid().size());
        assertTrue(salvage.skipped().isEmpty());
    }

    private static CharacterDef validDef() {
        return CharacterRegistry.LENIENT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(VALID)).getOrThrow();
    }

    private static ResourceKey<CharacterDef> key(String path) {
        return ResourceKey.create(RegistryKeys.CHARACTERS, com.astrum.pcrd.impl.InternalIds.id(path));
    }
}
