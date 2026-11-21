package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.growth.Dishes;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two 角色册-family items and the dish, as files on the classpath.
 *
 * <h2>Why an item-registration test looks at JSON</h2>
 * Nothing this task added is a rule: it is <b>content</b>, and content fails silently. A new item with no
 * model renders as the missing-model cube; a new item with no lang key shows {@code item.astrum.x} in the
 * inventory; a recipe whose result names an id that was never registered is dropped with one log line at
 * world load, and the player simply cannot craft it. None of those throws, and none of them is visible to
 * {@code AstrumItems}' own compilation.
 *
 * <p>So the ids are derived from the same path constants the registration uses
 * ({@link AstrumItems#themedItemIds()}), and each one is checked for the three files that make it a real
 * item: a model, a texture behind that model, and a name in both language files. The lang half is also
 * covered by {@code LangParityTest}; it is repeated here because the failure it prevents — an item whose
 * name is a raw key — is worth failing twice.
 *
 * <p>The one thing this cannot check is that the registry actually contains these ids; that needs a
 * running game, and it is checked by the client probe instead.
 */
class AstrumItemsTest {

    /** The item the dish is, spelled once here so the test fails if the registration and the rule drift. */
    private static final String RICE_BOWL = "astrum:rice_bowl";

    /**
     * Every themed id maps to a model file whose {@code layer0} names a texture that exists.
     *
     * <p>The chain matters: the model is what the client loads, and its {@code layer0} is the path it looks
     * up. A model pointing at {@code astrum:item/character_book} when the file is at
     * {@code item/character_book.png} under a different name is the exact bug this catches — the model parses,
     * the item registers, and the icon is a purple-and-black checkerboard.
     */
    @Test
    void everyThemedItemHasAModelThatPointsAtARealTexture() {
        Map<String, String> ids = AstrumItems.themedItemIds();
        // Three ids: the one card, the one book, and the token. The two re-skinned books and their ids were
        // deleted (「将2个皮肤版本的角色册道具删除，仅保留最开始的那个」), and the 选择券 was deleted with the
        // pick flow (「直接随机获取一个没有的角色」) — so this number going <b>down</b> is a change of feature,
        // not a lost file.
        assertEquals(3, ids.size(), "card, book and token: " + ids.keySet());

        for (Map.Entry<String, String> entry : ids.entrySet()) {
            ResourceLocation id = ResourceLocation.parse(entry.getKey());
            String path = entry.getValue();
            assertEquals("astrum", id.getNamespace(), "every themed item is this mod's");
            assertEquals(path, id.getPath(), "the map must key a model path by its own item id");

            JsonObject model = json("/assets/astrum/models/item/" + path + ".json");
            assertEquals("minecraft:item/generated", model.get("parent").getAsString(),
                    path + " must be a flat generated item, or the icon needs a custom renderer");
            JsonObject textures = model.getAsJsonObject("textures");
            assertNotNull(textures, path + " has no textures block");
            String layer0 = textures.get("layer0").getAsString();
            assertTrue(layer0.startsWith("astrum:item/"),
                    path + " layer0 must be an item texture, got " + layer0);
            String texturePath = layer0.substring("astrum:".length());
            // A model's layer0 omits the "textures/" directory the file actually lives in; that
            // translation is exactly where a hand-written model goes wrong, so it is spelled here.
            assertNotNull(
                    AstrumItemsTest.class.getResourceAsStream(
                            "/assets/astrum/textures/" + texturePath + ".png"),
                    path + " points layer0 at " + layer0 + ", which is not on the classpath");
        }
    }

    /** The dish has its own model, and it is the icon that was committed for it. */
    @Test
    void theDishHasAModelThatPointsAtTheCommittedIcon() {
        JsonObject model = json("/assets/astrum/models/item/rice_bowl.json");
        assertEquals("minecraft:item/generated", model.get("parent").getAsString());
        String layer0 = model.getAsJsonObject("textures").get("layer0").getAsString();
        assertEquals("astrum:item/dish_rice_bowl", layer0,
                "the rice bowl must use the icon that is already in the repository");
        assertNotNull(AstrumItemsTest.class.getResourceAsStream("/assets/astrum/textures/item/dish_rice_bowl.png"),
                "the committed dish icon must be there");
    }

    /**
     * Every themed book and card has a name in both languages, and the six names are distinct.
     *
     * <p>Distinct matters: three books all called "Character Book" would be three different panels a player
     * cannot tell apart in the inventory, which is the whole point of the ids.
     */
    @Test
    void everyThemedItemHasItsOwnNameInBothLanguages() {
        Set<String> english = new LinkedHashSet<>();
        Set<String> chinese = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : AstrumItems.themedItemIds().entrySet()) {
            String key = "item.astrum." + entry.getValue();
            for (String language : List.of("en_us", "zh_cn")) {
                JsonObject lang = json("/assets/astrum/lang/" + language + ".json");
                assertTrue(lang.has(key), language + " is missing " + key);
                String name = lang.get(key).getAsString();
                assertTrue(!name.isBlank(), language + " has a blank " + key);
                (language.equals("en_us") ? english : chinese).add(name);
            }
        }
        assertEquals(AstrumItems.themedItemIds().size(), english.size(),
                "two themed items share an English name: " + english);
        assertEquals(AstrumItems.themedItemIds().size(), chinese.size(),
                "two themed items share a Chinese name: " + chinese);
    }

    /** The dish has a name in both languages, and it is not a book's. */
    @Test
    void theDishHasANameInBothLanguages() {
        for (String language : List.of("en_us", "zh_cn")) {
            JsonObject lang = json("/assets/astrum/lang/" + language + ".json");
            assertTrue(lang.has(Dishes.DISH_NAME_KEY), language + " is missing the dish's name");
            String name = lang.get(Dishes.DISH_NAME_KEY).getAsString();
            assertTrue(!name.isBlank(), language + " has a blank dish name");
            assertTrue(!name.startsWith("item."),
                    language + " would show a raw key: " + name);
        }
    }

    /**
     * The id table is the registration's own paths.
     *
     * <p>{@code AstrumItems.themedItemIds()} is built from {@link AstrumItems#CARD_PATH} and
     * {@link AstrumItems#BOOK_PATH}, so this looks tautological; it is not. The point is that the
     * <em>registration</em> also builds its {@code DeferredItem}s from those two constants, so a test that
     * starts from the constants cannot pass while the registration used a literal string.
     *
     * <p>Also pins the part of the deletion that a file-existence check cannot see: there is no
     * {@code astrum:character_book_sakura} and no {@code astrum:character_book_forest} in this table, and
     * therefore no model or lang key for them either.
     */
    @Test
    void theIdTableIsTheRegistrationTable() {
        assertTrue(AstrumItems.themedItemIds().containsKey(InternalIds.id(AstrumItems.BOOK_PATH).toString()),
                "the one book must be in the table");
        assertTrue(AstrumItems.themedItemIds().containsKey(InternalIds.id(AstrumItems.CARD_PATH).toString()),
                "the one card must be in the table");
        assertEquals(AstrumItems.BOOK_PATH, AstrumItems.themedItemIds()
                .get(InternalIds.id(AstrumItems.BOOK_PATH).toString()),
                "the map must key a model path by its own item id");
        assertEquals(AstrumItems.CARD_PATH, AstrumItems.cardPath());

        for (String gone : List.of("astrum:character_book_sakura", "astrum:character_book_forest")) {
            assertTrue(!AstrumItems.themedItemIds().containsKey(gone), gone + " must be gone");
            assertFalse(AstrumItemsTest.class.getResource("/assets/astrum/models/item/"
                    + gone.substring("astrum:".length()) + ".json") != null, gone + " model must be gone");
            for (String language : List.of("en_us", "zh_cn")) {
                assertTrue(!json("/assets/astrum/lang/" + language + ".json").has("item." + gone),
                        language + " still names the deleted " + gone);
            }
        }
        assertTrue(!AstrumItems.themedItemIds().containsKey(RICE_BOWL),
                "the dish is not a 角色册-family item");
    }

    private static JsonObject json(String resource) {
        try (InputStream in = AstrumItemsTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource on the classpath: " + resource);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            assertTrue(parsed.isJsonObject(), resource + " is not a JSON object");
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
