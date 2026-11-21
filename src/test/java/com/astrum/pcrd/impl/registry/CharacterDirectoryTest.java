package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.impl.item.AstrumDataComponents;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The datapack side of "adding a character needs no code change", asserted rather than asserted-in-prose.
 *
 * <h2>The claim, and why it needs a test</h2>
 * The brief's requirement (2) was that {@code CharacterRegistry} and the loader pick the three new
 * characters up with <b>no code change beyond adding the JSONs</b>, and that if any list of ids existed
 * it would be a bug to fix toward a directory scan. This file pins the two halves of that:
 *
 * <ul>
 *   <li><b>No id list anywhere.</b> The registry is a NeoForge <em>datapack registry</em>
 *       ({@code RegistryKeys.CHARACTERS}), so its contents come from scanning
 *       {@code data/<pack>/astrum/character/} — there is no Java-side enumeration of characters, and
 *       {@link #theRegistryDirectoryHoldsEveryShippedCharacter()} asserts the directory is what the
 *       registry declares, so a file moved out of it is caught here.</li>
 *   <li><b>Each character is reachable by exactly the id its card names.</b> A card's
 *       {@code astrum:character_id} component must be an id the registry will hold, or a player crafts a
 *       ticket that grants nothing. That pair is checked from the recipe JSON, not from a constant.</li>
 * </ul>
 *
 * <h2>What cannot be asserted without a world</h2>
 * The actual registry contents. {@code CharacterRegistryTest} covers the salvage rules with a real
 * {@code MappedRegistry}; what is provable here is that every shipped file is in the scanned directory
 * and decodes, which is what makes the directory scan find four characters instead of one.
 */
class CharacterDirectoryTest {

    /** Every character this build ships: the file name is the {@code astrum:} path. */
    private static final List<String> SHIPPED =
            List.of("djeeta", "kokkoro", "pecorine", "kyaru", "sheffy");

    /**
     * The JSON key a card recipe writes to enrol a character.
     *
     * <p>Derived from the component that reads it rather than retyped: the registry id of
     * {@code AstrumDataComponents.CHARACTER_ID} is {@code astrum:character_id}, and this is the string
     * a recipe's {@code components} object must use. Building it from {@code Astrum.MOD_ID} and the
     * registration path means renaming either fails here instead of shipping recipes that grant nothing.
     */
    private static final String CHARACTER_ID_KEY =
            AstrumDataComponents.CHARACTER_ID.getId().toString();

    private static String characterPath(String id) {        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static String recipePath(String id) {
        return "/data/astrum/recipe/" + id + "_card.json";
    }

    /**
     * The one card recipe, the lottery's entry point.
     *
     * <p>Not derived from a character id, because it names no character — which is the whole point of the
     * replace-the-four-with-one change this constant records.
     */
    private static final String CARD_RECIPE = "/data/astrum/recipe/character_card.json";

    /** The duplicate-recycling recipe: one character card in, one memory token out. */
    private static final String TOKEN_RECIPE = "/data/astrum/recipe/memory_token.json";

    private static String read(String path) {
        try (InputStream in = CharacterDirectoryTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing packaged resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    /**
     * The directory the registry scans holds exactly the characters this build ships, and every one of
     * them decodes.
     *
     * <p>The path is built from {@link Registries#elementsDirPath} — the function the loader itself
     * uses — so this cannot drift from where NeoForge actually looks. A character filed at
     * {@code data/astrum/character/} (one {@code astrum} short) is <b>never read</b> and the registry
     * comes up missing it with no warning at all, which is the failure this asserts against.
     */
    @Test
    void theRegistryDirectoryHoldsEveryShippedCharacter() {
        assertEquals("/data/astrum/astrum/character/" + "kokkoro.json", characterPath("kokkoro"),
                "the doubled astrum is NeoForge's layout, not a typo: data/astrum/character/ is never read");
        for (String id : SHIPPED) {
            String text = read(characterPath(id));
            var def = CharacterDef.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                    JsonParser.parseString(text)).getOrThrow();
            assertNotNull(def.displayName(), id + " must decode with a display name");
            assertTrue(!def.displayName().isBlank(), id + "'s display name must not be blank");
        }
        assertEquals(5, SHIPPED.size(), "the build ships 姬塔, the three characters of task 30, and 雪菲 (task 42)");
    }

    /**
     * Every character has a card recipe, and the card names <b>that</b> character.
     *
     * <p>The card item is shared — one {@code astrum:character_card} for everybody — and the enrolled
     * character comes from the {@code astrum:character_id} component. So a recipe whose component names
     * a different id than its file name would hand the player the wrong girl, and nothing else in the
     * build would notice: the item, the tab and the book are all id-agnostic by design.
     */
    /**
     * <b>There is exactly ONE character-card recipe, and it names nobody.</b>
     *
     * <h2>What this used to assert, and why the human replaced it</h2>
     * This test was {@code everyCharacterHasACardRecipeNamingItsOwnId}: one recipe per shipped character, each
     * producing {@code astrum:character_card} with that character's {@code astrum:character_id} baked into the
     * result. So a player could craft 姬塔's card, or コッコロ's, on demand — there was no lottery.
     *
     * <p>The human asked for a gacha: 「一张角色卡，随机出一个角色」 with the roll decided at craft time and the
     * odds depending on what the player already owns. A per-character recipe cannot express that at all — its
     * result is a fixed template every player shares — so the four recipes collapsed into one
     * ({@code data/astrum/recipe/character_card.json}) whose result carries <b>no</b> {@code character_id}, and
     * {@code CardRollOnCraft} fills it in when the player takes the card.
     *
     * <p>That makes "the result names nobody" a <b>requirement</b> rather than an omission: a recipe that named
     * a character would make that character deterministic again — the exact feature that was removed — and it
     * would also silently win over the roll, because {@code CardRollOnCraft} leaves an already-named card
     * alone (it must, so a datapack's own card still works).
     */
    @Test
    void thereIsOneCardRecipeAndItNamesNoCharacter() {
        JsonObject recipe = JsonParser.parseString(read(CARD_RECIPE)).getAsJsonObject();

        assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString(),
                "the card recipe is shapeless, like every other recipe this mod ships");
        JsonObject result = recipe.getAsJsonObject("result");
        assertEquals("astrum:character_card", result.get("id").getAsString(),
                "one card item for every character — the character is a component, not an item");

        // The roll is what decides. A components block here would pin a character and remove the lottery.
        if (result.has("components")) {
            assertFalse(result.getAsJsonObject("components").has(CHARACTER_ID_KEY),
                    CARD_RECIPE + " must not bake " + CHARACTER_ID_KEY + " into its result: the character is "
                            + "rolled at craft time (CardRollOnCraft), and a pinned id would make that one "
                            + "character deterministic again — the feature the human removed");
        }

        // And the four per-character recipes are gone: their existence is what made the draw deterministic.
        for (String id : SHIPPED) {
            assertNull(getClass().getResourceAsStream(recipePath(id)),
                    "the per-character recipe " + recipePath(id) + " must be deleted: one lottery recipe "
                            + "replaced it (「一张角色卡，随机出一个角色」)");
        }
    }

    /** The recipe file sits where the vanilla recipe loader reads it, so it is craftable at all. */
    @Test
    void theCardRecipeIsInTheVanillaRecipeDirectory() {
        assertEquals("/data/astrum/recipe/character_card.json", CARD_RECIPE,
                "recipes are read from data/<pack>/recipe/, not from the datapack registry's layout");
        JsonObject recipe = JsonParser.parseString(read(CARD_RECIPE)).getAsJsonObject();
        assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString());
        assertTrue(recipe.getAsJsonArray("ingredients").size() >= 2,
                "a card costs more than one ingredient, or the lottery would be free");
    }

    /**
     * The recycle recipe exists: a duplicate card becomes a token. That is the <b>only</b> recipe half.
     *
     * <p>The second half of the human's loop (「重复的角色卡可以销毁，或者参与合成得到一个新物品」,
     * 「64个换一个自选角色卡」) is not a recipe at all: 「一组，又不是放在合成台，是背包里右键直接使用的东西」 —
     * the crystal is right-clicked, so {@code MemoryTokenItem} spends a stack of a hundred on the spot. An
     * {@code Ingredient} carries no count, so a hundred could never have been expressed in a nine-slot grid
     * anyway, which is why the two halves are checked in different ways.
     *
     * <p>Asserted here rather than in the gacha package because it is a statement about the recipe directory,
     * which is this test's subject — and because a missing recipe is invisible in every other test.
     */
    @Test
    void aDuplicateCardRecyclesIntoAToken() {
        JsonObject recipe = JsonParser.parseString(read(TOKEN_RECIPE)).getAsJsonObject();
        assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString());
        var ingredients = recipe.getAsJsonArray("ingredients");
        assertEquals(1, ingredients.size(), "one card in, one token out");
        assertEquals("astrum:character_card",
                ingredients.get(0).getAsJsonObject().get("item").getAsString(),
                "the token is made from a character card — specifically a duplicate, which is why the "
                        + "recipe cannot ask which character it was");
        assertEquals("astrum:memory_token", recipe.getAsJsonObject("result").get("id").getAsString());

        // Nothing spends the tokens here: the count lives in TokenExchange, and this asserts the constant is
        // the number he gave.
        assertEquals(64, com.astrum.pcrd.impl.gacha.TokenExchange.TOKENS_PER_VOUCHER,
                "「64个换一个自选角色卡」，一组的数量");

        // And the one-off 选择券 is gone from the recipe directory as well as from the registry: the
        // previous revision shipped no recipe for it at all, which the human found by trying to craft one in
        // creative (「现在记忆结晶使用后，不会获得角色卡选择券」). Deleting the item is the fix, so a recipe
        // reappearing here would resurrect a deleted feature.
        assertNull(getClass().getResourceAsStream("/data/astrum/recipe/character_card_voucher.json"),
                "the voucher has no recipe and no item any more — the crystal is right-clicked instead");
    }

    /**
     * <b>No Java source enumerates the characters.</b>
     *
     * <p>This is the "if the registry needs a list of ids anywhere, that is a bug" requirement, checked
     * mechanically: it reads every main source file and fails if one names two or more shipped character
     * ids in the same file. A registry that needed a list would trip this immediately.
     *
     * <p>Doc comments are stripped first, because {@code CharacterRegistry}'s javadoc and
     * {@code UbDamageSettlement}'s both <em>mention</em> character ids as worked examples — that is
     * prose, not a dispatch table.
     */
    @Test
    void noSourceFileEnumeratesTheCharacters() {
        java.nio.file.Path root = mainSourceRoot();
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null,
                "main sources not reachable; this assertion would be vacuous rather than wrong");
        List<String> ids = List.of("djeeta", "kokkoro", "pecorine", "kyaru", "sheffy");
        List<String> offenders = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = stripComments(java.nio.file.Files.readString(file));
                long named = ids.stream().filter(source::contains).count();
                if (named >= 2) {
                    offenders.add(file.getFileName() + " names " + named + " character ids");
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("could not walk " + root, e);
        }
        assertTrue(offenders.isEmpty(),
                "a source file enumerates characters, which means the loader is not a directory scan: "
                        + offenders);
    }

    /** Removes line and block comments, so only executable text is searched. */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    /**
     * {@return the {@code src/main/java} tree this build compiles, or {@code null} when unreachable}
     *
     * <h2>Why the working directory cannot be trusted</h2>
     * The unit-test JVM runs with Gradle's own scratch directory as its working directory
     * ({@code build/minecraft-junit}), not the repository root — so a hard-coded relative path would make
     * this assertion silently vacuous, which is worse than no assertion. Instead the root is found by
     * walking <b>up</b> from the working directory and from the classpath location of this class until a
     * directory containing {@code src/main/java} appears. Both are tried because a Gradle test worker can
     * report either; when neither works the caller skips loudly.
     */
    private static java.nio.file.Path mainSourceRoot() {
        java.nio.file.Path working = java.nio.file.Path.of(System.getProperty("user.dir"));
        java.nio.file.Path found = searchUpwards(working);
        if (found != null) {
            return found;
        }
        // The class file lives in build/classes/java/test, i.e. four levels under the project root.
        try {
            java.nio.file.Path classFile = java.nio.file.Path.of(
                    CharacterDirectoryTest.class.getProtectionDomain().getCodeSource()
                            .getLocation().toURI());
            return searchUpwards(classFile);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@return the first ancestor of {@code start} holding {@code src/main/java}, or {@code null}} */
    private static java.nio.file.Path searchUpwards(java.nio.file.Path start) {
        java.nio.file.Path candidate = start;
        for (int hops = 0; hops < 8 && candidate != null; hops++) {
            java.nio.file.Path sources = candidate.resolve("src").resolve("main").resolve("java");
            if (java.nio.file.Files.isDirectory(sources)) {
                return sources;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}

