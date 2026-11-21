package com.astrum.pcrd.impl.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The three files a rolled card's face needs, checked against each other.</b>
 *
 * <h2>Why this test exists at all: no single file can be wrong on its own</h2>
 * A card's icon is a chain of three assets, and every link fails <em>silently</em> and differently:
 *
 * <ol>
 *   <li>{@code assets/astrum/card_index.json} — the character id to {@code custom_model_data} number. A typo
 *       in a key means the roll writes no index and the card keeps the generic art, with nothing logged.</li>
 *   <li>{@code assets/astrum/models/item/card_<id>.json} — what index <em>n</em> draws. A missing file means
 *       vanilla's missing model.</li>
 *   <li>{@code assets/astrum/models/item/character_card.json}'s {@code overrides} — the array that connects
 *       the two. An override naming a model that does not exist is a purple cube.</li>
 * </ol>
 *
 * <p>None of the three throws, none of them is visible to compilation, and the person who would notice is
 * the human looking at the icon. So the check is here: <b>every character in the index has a model, every
 * model has an override, and every override names a character in the index.</b> That is the "a typo cannot
 * ship" requirement, and it is one assertion per link rather than one per file.
 *
 * <h2>What only a human can confirm</h2>
 * That the rendered 16×16 icon actually looks like her. This test asserts the plumbing points at
 * {@code astrum:gui/portrait/<id>} — the same tree {@code ThreeCharactersResourceTest} proves holds four
 * different faces — but the picture on the item slot is somebody's eyes, not an assertion.
 */
class CardIndexResourceTest {

    /** The card's own model, the file that carries the overrides. */
    private static final String CARD_MODEL = "/assets/astrum/models/item/character_card.json";

    /** Where a per-character model lives, by convention: {@code card_<path>.json}. */
    private static String modelPath(String id) {
        return "/assets/astrum/models/item/card_" + id + ".json";
    }

    private static JsonObject json(String path) {
        try (InputStream in = CardIndexResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing packaged resource: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    /** {@return the shipped character id to index mapping, read through the production loader} */
    private static CardIndex.Table index() {
        CardIndex.reloadForTest();
        return CardIndex.table();
    }

    /**
     * <b>The file loads, and it lists the four shipped characters.</b>
     *
     * <p>Read through {@link CardIndex#table()} — the same call the roll path makes — not through a test-local
     * parser. A test that re-implemented the parse could pass while the game read nothing.
     */
    @Test
    void theShippedIndexLoadsAndListsEveryCharacter() {
        CardIndex.Table table = index();
        assertFalse(table.byCharacter().isEmpty(),
                "assets/astrum/card_index.json must load; an empty table means the path or the JSON is wrong, "
                        + "and every rolled card would silently keep the generic art");
        for (String id : List.of("djeeta", "kokkoro", "pecorine", "kyaru", "sheffy", "hiyori", "rei", "kurumi", "saren", "mimi", "muimi", "suzume", "misogi", "kyoka", "rino", "ayane", "yui", "shizuru", "nozomi", "tsumugi", "chika", "christina")) {
            ResourceLocation characterId = ResourceLocation.parse("astrum:" + id);
            assertTrue(table.indexOf(characterId) > 0,
                    characterId + " must have a non-zero custom_model_data index");
        }
        assertTrue(table.everyIndexIsPositive(),
                "no index may be 0: ItemProperties reads an absent component as 0.0f, so an override at 0 "
                        + "would repaint every blank card");
        assertEquals(22, table.byCharacter().size(),
                "twenty-two characters ship; adding one is a line in the index file <b>and</b> a model, and "
                        + "bumping this number is what stops the two from drifting apart");
    }

    /**
     * <b>The indices are distinct.</b> Two characters sharing one number would make one of them draw the
     * other's face — the same class of defect {@code ThreeCharactersResourceTest} catches for portraits.
     */
    @Test
    void noTwoCharactersShareAnIndex() {
        CardIndex.Table table = index();
        assertEquals(table.byCharacter().size(), table.byIndex().size(),
                "index → character must be a bijection, or two characters draw one face: " + table.byIndex());
    }

    /**
     * <b>Every character in the index has a model file, and that model draws her portrait.</b>
     *
     * <p>The {@code layer0} path is asserted against {@code astrum:gui/portrait/<id>} rather than merely
     * checked to exist: a model pointing at the right file for the wrong girl is the bug that reads as
     * working. The texture itself is <b>not</b> required to be on the classpath — the portraits are
     * gitignored derived art shipped only in the optional pack, and a rolled card with no pack installed is
     * meant to draw the checkerboard (the human's accepted "install the pack" signal).
     */
    @Test
    void everyIndexedCharacterHasAModelThatDrawsHerPortrait() {
        for (Map.Entry<ResourceLocation, Integer> entry : index().byCharacter().entrySet()) {
            String id = entry.getKey().getPath();
            String path = modelPath(id);
            JsonObject model = json(path);   // asserts the file exists
            assertEquals("minecraft:item/generated", model.get("parent").getAsString(),
                    path + " must be a flat item layer, like the card it overrides");
            // The path must live under item/, and that is not cosmetic: vanilla's blocks atlas collects
            // textures/item/ (and block/), and an item model can only draw a sprite from that atlas. The
            // first version of this pointed at gui/portrait/, which no atlas collects, so every card baked
            // with a missing texture and showed the magenta checkerboard however many resource packs were
            // installed. Asserting the prefix is what keeps that from coming back.
            String layer = model.getAsJsonObject("textures").get("layer0").getAsString();
            assertEquals("astrum:item/card_" + id, layer,
                    path + " must draw " + id + "'s own portrait, from a path the blocks atlas collects; "
                            + "the file is pack-only by design, so this asserts the path and not the bytes");
            assertTrue(layer.startsWith("astrum:item/"),
                    path + " draws " + layer + ", which is outside textures/item/ and therefore not in the "
                            + "blocks atlas — an item model cannot see it");
        }
    }

    /**
     * <b>The overrides array is ascending, complete, and exactly the index.</b>
     *
     * <h2>Why ascending is not style</h2>
     * {@code ItemOverrides} bakes the list <b>in reverse</b> ({@code for (j = size - 1; j >= 0; j--)}) and
     * {@code BakedOverride.test} matches with a <b>{@code >=} threshold</b>, not equality. So the highest
     * matching threshold wins, but only because the array is authored ascending. Author the four the other
     * way round and every card whose index is 1 or more would resolve to the entry baked first — index 4's
     * model — and all four characters would wear 凯露's face. Asserting the order pins the one property the
     * vanilla code depends on and does not state.
     *
     * <p>It also asserts the <b>count</b>: an override with no character behind it is a model nobody can
     * reach, and a character with no override is a card that draws the generic art for no reason.
     */
    @Test
    void theOverridesAreAscendingAndMatchTheIndexExactly() {
        JsonObject card = json(CARD_MODEL);
        JsonArray overrides = card.getAsJsonArray("overrides");
        assertNotNull(overrides, CARD_MODEL + " must carry the four overrides a rolled card needs");

        CardIndex.Table table = index();
        List<Integer> authored = new ArrayList<>();
        Map<Integer, String> modelByIndex = new LinkedHashMap<>();
        for (JsonElement element : overrides) {
            JsonObject override = element.getAsJsonObject();
            int index = override.getAsJsonObject("predicate").get("custom_model_data").getAsInt();
            authored.add(index);
            modelByIndex.put(index, override.get("model").getAsString());
        }

        for (int i = 1; i < authored.size(); i++) {
            assertTrue(authored.get(i) > authored.get(i - 1),
                    "the overrides must be authored in ASCENDING custom_model_data order: ItemOverrides "
                            + "bakes the list in reverse and matches with a >= threshold, so any other order "
                            + "resolves every index to one model. Authored: " + authored);
        }

        assertEquals(table.byIndex().keySet().stream().sorted().toList(), authored,
                "the override indices must be exactly the index file's: a missing one is a card that keeps "
                        + "the generic art, an extra one is an unreachable model");

        for (Map.Entry<Integer, String> entry : modelByIndex.entrySet()) {
            ResourceLocation characterId = table.byIndex().get(entry.getKey());
            assertNotNull(characterId, "override index " + entry.getKey() + " has no character behind it");
            assertEquals("astrum:item/card_" + characterId.getPath(), entry.getValue(),
                    "index " + entry.getKey() + " must draw " + characterId + "'s model and nobody else's");
            assertNotNull(getClass().getResource(modelPath(characterId.getPath())),
                    "override index " + entry.getKey() + " names " + entry.getValue()
                            + ", which has no model file");
        }
    }

    /**
     * <b>A blank card carries neither component, so it keeps the generic art.</b>
     *
     * <p>The creative-mode card and the "the pack defined no characters" card are the same stack: no
     * {@code astrum:character_id} and no {@code custom_model_data}. Writing an index without a character
     * would paint a face on it, and writing a character without an index is the un-indexed case
     * {@link #anUnlistedCharacterKeepsTheGenericArt()} covers.
     *
     * <p>This half is a <b>reading</b> of the shipped resources plus {@link CardStamp}'s contract, which the
     * unit test {@code CardStampTest} asserts against a real {@code ItemStack}; here the point is that the
     * index file has no zero entry that could catch such a stack.
     */
    @Test
    void aBlankCardHasNoIndexToMatch() {
        assertTrue(index().everyIndexIsPositive(),
                "with every authored index > 0, a stack with no custom_model_data (which reads 0.0f) matches "
                        + "no override and keeps the generic card art");
        assertEquals(0, CardIndex.indexOf(null), "no character, no index");
        assertEquals(0, CardIndex.indexOf(ResourceLocation.parse("astrum:not_a_character")),
                "a character the index does not list gets no index either — a generic card, not a wrong face");
    }

    /**
     * <b>A character the index lists but the pack does not define still resolves its model.</b>
     *
     * <p>The three files are checked against each other, not against the character registry: a pack that
     * adds a fifth character adds a line to the index and a model, and this test does not care whether
     * {@code data/astrum/astrum/character/} has it — {@code CharacterDirectoryTest} owns that question.
     * The separation is deliberate, because the two can legitimately be updated in different orders.
     */
    @Test
    void anUnlistedCharacterKeepsTheGenericArt() {
        CardIndex.Table table = CardIndex.parse(JsonParser.parseString("{\"astrum:x\": 9}"));
        assertEquals(9, table.indexOf(ResourceLocation.parse("astrum:x")));
        assertEquals(0, table.indexOf(ResourceLocation.parse("astrum:y")));
    }

    /**
     * <b>The loader refuses what would silently paint the wrong face.</b>
     *
     * <p>Each case here is a mistake a person actually makes in a JSON file, and each one fails in a way this
     * project treats as the worst kind: a card that renders, is usable, and is wrong. So the loader drops the
     * entry — one WARN each — rather than loading it.
     */
    @Test
    void theLoaderDropsEntriesThatWouldPaintTheWrongFace() {
        // Index 0: reads identically to "no component", so an override at 0 would catch every blank card.
        assertEquals(0, CardIndex.parse(JsonParser.parseString("{\"astrum:a\": 0}"))
                .indexOf(ResourceLocation.parse("astrum:a")),
                "index 0 must be refused: ItemProperties reads an absent component as 0.0f");
        // A duplicate index: the second girl would wear the first one's face.
        CardIndex.Table duplicate = CardIndex.parse(JsonParser.parseString(
                "{\"astrum:a\": 1, \"astrum:b\": 1}"));
        assertEquals(1, duplicate.byCharacter().size(),
                "two characters cannot share one index: " + duplicate.byCharacter());
        // A key that is not a resource location.
        assertEquals(0, CardIndex.parse(JsonParser.parseString("{\"not an id\": 3}"))
                .byCharacter().size());
        // Entries that are not integers at all.
        assertEquals(0, CardIndex.parse(JsonParser.parseString("{\"astrum:c\": \"one\"}"))
                .byCharacter().size());
        // A document that is not an object.
        assertEquals(CardIndex.Table.EMPTY, CardIndex.parse(JsonParser.parseString("[1, 2, 3]")));
        assertEquals(CardIndex.Table.EMPTY, CardIndex.parse(null));
        // `_notes` is documentation, not a character, and must not be warned about or loaded.
        CardIndex.Table documented = CardIndex.parse(JsonParser.parseString(
                "{\"_notes\": [\"doc\"], \"astrum:d\": 7}"));
        assertEquals(Optional.of(7), Optional.of(documented.indexOf(ResourceLocation.parse("astrum:d"))));
        assertEquals(1, documented.byCharacter().size(), "_notes must not become a character");
    }

    /** {@return the card model's override array as (index, model) pairs, for the report's numbers} */
    static List<String> authoredOverrides() {
        JsonArray overrides = json(CARD_MODEL).getAsJsonArray("overrides");
        List<String> rows = new ArrayList<>();
        for (JsonElement element : overrides) {
            JsonObject override = element.getAsJsonObject();
            rows.add(override.getAsJsonObject("predicate").get("custom_model_data").getAsInt()
                    + " -> " + override.get("model").getAsString());
        }
        return rows;
    }
}
