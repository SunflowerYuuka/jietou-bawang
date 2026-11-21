package com.astrum.pcrd.impl.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A character card with no {@code astrum:character_id} draws the shipped blank-card art.</b>
 *
 * <h2>What the human asked for</h2>
 * 「The blank card must use the new art」 — {@code textures/item/character_card.png} was replaced, and the question is
 * whether anything still points elsewhere. It does not, and this is the evidence:
 *
 * <ul>
 *   <li>the base model's {@code layer0} is {@code astrum:item/character_card}, so the <b>un-overridden</b> stack —
 *       a card with no component, i.e. no {@code custom_model_data} — draws exactly that file;</li>
 *   <li>every override is gated on a <b>non-zero</b> {@code custom_model_data}, so no override can capture the
 *       blank card. An override with no {@code predicate} at all is the failure mode this rules out: it would
 *       replace the base texture for every card in the game while each per-character model still looked
 *       correct;</li>
 *   <li>the texture it names is on disk in the packaged tree, at the size an item layer must be.</li>
 * </ul>
 *
 * <h2>Why the per-character models are checked too</h2>
 * The blank card's art is only half the question: a per-character model that pointed at a texture nobody shipped
 * would draw the magenta checkerboard in the creative tab, and that looks like "the new art is broken" even though
 * the base layer is fine. The cross-check walks every model in the folder and asserts its layer exists.
 */
class CardArtTest {

    private static Path packRoot() {
        java.net.URL meta = CardArtTest.class.getResource("/pack.mcmeta");
        assertNotNull(meta, "the mod's pack.mcmeta must be on the test classpath");
        try {
            return Path.of(meta.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("pack.mcmeta has no usable path: " + meta, e);
        }
    }

    private static JsonObject json(String pathWithinAssets) {
        String resource = "/assets/astrum/" + pathWithinAssets;
        try (InputStream in = CardArtTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be packaged");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * <b>The blank card's model names the shipped art, and no override can take the card away from it.</b>
     */
    @Test
    void theBlankCardDrawsTheShippedArt() throws IOException {
        JsonObject model = json("models/item/character_card.json");
        assertEquals("minecraft:item/generated", model.get("parent").getAsString(),
                "a flat item layer, which is what the card is");
        String layer0 = model.getAsJsonObject("textures").get("layer0").getAsString();
        assertEquals("astrum:item/character_card", layer0,
                "the base layer is the shipped card art, which is what an empty card falls through to");

        // The texture the layer names really is there, in the tree this build packaged.
        Path texture = packRoot().resolve("assets/astrum/textures/item/character_card.png");
        assertTrue(Files.isRegularFile(texture), "the layer must have a file behind it: " + texture);
        assertTrue(Files.size(texture) > 1000,
                "and it must be real art rather than a placeholder of a few bytes: " + Files.size(texture));

        for (var element : model.getAsJsonArray("overrides")) {
            JsonObject override = element.getAsJsonObject();
            JsonObject predicate = override.getAsJsonObject("predicate");
            assertNotNull(predicate,
                    "an override with no predicate would replace the base texture for EVERY card: " + override);
            assertTrue(predicate.has("custom_model_data"),
                    "and its predicate must be the card's character component: " + override);
            assertTrue(predicate.get("custom_model_data").getAsInt() >= 1,
                    "a predicate of 0 would capture the blank card and hide the new art: " + override);
        }
    }

    /**
     * <b>Every per-character card model names a texture that exists.</b>
     *
     * <p>A missing one is the missing-texture checkerboard in the creative tab — which would read as "the card art
     * is broken" even with the blank card correct.
     */
    @Test
    void everyCardModelNamesATextureThatExists() {
        Path models = packRoot().resolve("assets/astrum/models/item");
        assertTrue(Files.isDirectory(models), "the item model folder must be packaged: " + models);
        try (var files = Files.list(models)) {
            int checked = 0;
            for (Path model : files.filter(path -> path.getFileName().toString().startsWith("card_")
                    && path.toString().endsWith(".json")).toList()) {
                String name = model.getFileName().toString();
                JsonObject parsed = json("models/item/" + name);
                String layer0 = parsed.getAsJsonObject("textures").get("layer0").getAsString();
                assertTrue(layer0.startsWith("astrum:item/"),
                        name + " must name a texture of this mod, got " + layer0);
                Path texture = packRoot().resolve("assets/astrum/textures/"
                        + layer0.substring("astrum:".length()) + ".png");
                assertTrue(Files.isRegularFile(texture),
                        name + " points at " + layer0 + " but " + texture + " does not exist");
                checked++;
            }
            assertTrue(checked >= 20, "sanity: the shipped cast has many cards, checked " + checked);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * <b>The blank card's art is <em>not</em> in the jar by accident of a stale working tree — it is ignored and
     * therefore official-derived.</b>
     *
     * <p>Stated as a test so the decision is visible rather than implied: <code>character_card.png</code> is derived
     * from the official card art, so it is in {@code .gitignore} and therefore excluded from the jar by
     * {@code build.gradle}. That is the correct rule for it — unlike the generic effect sprites, which are
     * self-drawn and must ship (see {@code EffectSpriteCopyTest}).
     */
    @Test
    void theBlankCardArtStaysOfficialDerived() throws IOException {
        Path repo = packRoot();
        while (repo != null && !Files.isRegularFile(repo.resolve(".gitignore"))) {
            repo = repo.getParent();
        }
        if (repo == null) {
            repo = Path.of("").toAbsolutePath();
            while (repo != null && !Files.isRegularFile(repo.resolve(".gitignore"))) {
                repo = repo.getParent();
            }
        }
        assertNotNull(repo, "could not find the repository root");
        boolean ignored = false;
        for (String line : Files.readAllLines(repo.resolve(".gitignore"))) {
            if (line.trim().equals(
                    "/src/main/resources/assets/astrum/textures/item/character_card.png")) {
                ignored = true;
                break;
            }
        }
        assertTrue(ignored,
                "the card art is derived from Cygames' official card and must stay out of git — and therefore out "
                        + "of the jar. If this ever changes deliberately, change this assertion with it.");
        // The only mob_effect files that may be ignored are the two PORTRAITS (counter.png / spirit.png are
        // 雪菲's and 千歌's 6★ avatars shrunk to 64x64 — derived official art, moved to the pack with the
        // avatars themselves by 「官方素材都不进jar，放资源包」). Every self-drawn placeholder — the generic effects and
        // the 91 skill types — must ship in the jar, or JEI shows the checkerboard with no pack installed.
        java.util.List<String> ignoredEffectFiles = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(repo.resolve(".gitignore"))) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || !trimmed.contains("textures/mob_effect")) {
                continue;
            }
            ignoredEffectFiles.add(trimmed.substring(trimmed.lastIndexOf('/') + 1));
        }
        assertEquals(new java.util.TreeSet<>(java.util.Set.of("counter.png", "spirit.png")),
                new java.util.TreeSet<>(ignoredEffectFiles),
                "the two portrait markers are official-derived and pack-only; every self-drawn placeholder must "
                        + "NOT be ignored, because build.gradle drops ignored paths from the jar");
    }
}
