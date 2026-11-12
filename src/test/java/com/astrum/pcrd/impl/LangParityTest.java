package com.astrum.pcrd.impl;

import com.astrum.pcrd.impl.client.ClientKeyBindings;
import com.astrum.pcrd.impl.command.AstrumCommand;
import com.astrum.pcrd.impl.item.CharacterCardItem;
import com.astrum.pcrd.impl.party.BookText;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Language parity for the <b>whole</b> lang file, not just the config screen's keys (spec §8.0).
 *
 * <h2>Why a whole-file test next to {@code ConfigScreenLabelsTest}</h2>
 * That test derives the keys the generated config screen will ask for and checks they exist — which
 * covers exactly one family, {@code astrum.configuration.*}. Everything else in the file was unchecked:
 * key-binding names, the three death messages (a missing one shows a raw {@code death.attack...} key in
 * the death screen, in front of the player), and anything a later task adds. So this test compares the
 * two files against <em>each other</em> instead of against a list someone has to remember to extend:
 * the key sets must be identical in both directions, no value may be empty or blank, and the keys the
 * code holds constants for must be present.
 *
 * <p>English is not the reference language and Chinese is not a translation to be tolerated: either
 * file missing a key is the same defect (one player reads a raw key), so the assertion is symmetric.
 *
 * <h2>Why it parses instead of grepping</h2>
 * A grep for {@code "key"} cannot tell a key from a value that happens to contain a quote, and it
 * cannot see a duplicate key (which JSON parsing resolves silently, but which is always a mistake).
 * Parsing also makes "the value is blank" expressible: {@code ""} and {@code "   "} are both broken,
 * and a whitespace-only translation is invisible in a diff.
 */
class LangParityTest {

    /** The two languages shipped today. A new file is expected to be added here with its translation. */
    private static final List<String> LANGUAGES = List.of("en_us", "zh_cn");

    private static final String REFERENCE = "en_us";

    /** The names the code asks for by constant, plus the category the bindings are filed under. */
    private static final Set<String> REQUIRED_KEYS = requiredKeys();

    /**
     * Collects the keys the code names, from every class that holds them.
     *
     * <p>Sourced from the owning classes rather than retyped here: the item and the command each expose
     * a {@code LANG_KEYS} set containing exactly what their branches ask for, so adding a message
     * without a translation — or renaming a key and leaving the old one behind — fails this test instead
     * of showing a raw {@code astrum.card.*} string to a player.
     */
    private static Set<String> requiredKeys() {
        Set<String> keys = new TreeSet<>(Set.of(
                ClientKeyBindings.CATEGORY,
                ClientKeyBindings.UB_CAST_KEY,
                ClientKeyBindings.UB_PRESET_KEY,
                ClientKeyBindings.CONFIG_KEY));
        keys.addAll(AstrumCommand.LANG_KEYS);
        keys.addAll(CharacterCardItem.LANG_KEYS);
        // The 角色册 item and screen, including every astrum.party.reason.* key derived from the enum.
        keys.addAll(BookText.LANG_KEYS);
        return Set.copyOf(keys);
    }

    private static JsonObject lang(String code) {
        String path = "/assets/astrum/lang/" + code + ".json";
        try (InputStream in = LangParityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing language file on the classpath: " + path);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void bothLanguageFilesDefineExactlyTheSameKeys() {
        JsonObject reference = lang(REFERENCE);
        assertTrue(reference.size() > 20, "sanity: the reference file should not be nearly empty, found "
                + reference.size() + " keys");

        for (String language : LANGUAGES) {
            JsonObject other = lang(language);
            Set<String> missing = new TreeSet<>(reference.keySet());
            missing.removeAll(other.keySet());
            Set<String> extra = new TreeSet<>(other.keySet());
            extra.removeAll(reference.keySet());
            assertEquals(Set.of(), missing, language + ".json is missing keys that "
                    + REFERENCE + ".json has (those players would read a raw translation key)");
            assertEquals(Set.of(), extra, language + ".json has keys that "
                    + REFERENCE + ".json does not (a rename left a stale key behind)");
        }
    }

    @Test
    void noTranslationIsEmptyOrBlank() {
        for (String language : LANGUAGES) {
            JsonObject file = lang(language);
            Set<String> empty = new TreeSet<>();
            Set<String> blank = new TreeSet<>();
            for (String key : file.keySet()) {
                JsonElement value = file.get(key);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                    blank.add(key);
                } else if (value.getAsString().isEmpty()) {
                    empty.add(key);
                } else if (value.getAsString().isBlank()) {
                    blank.add(key);
                }
            }
            assertEquals(Set.of(), empty, language + ".json has keys with an empty value");
            assertEquals(Set.of(), blank, language + ".json has keys with a blank value");
        }
    }

    /**
     * The keys the code holds constants for must exist in every language.
     *
     * <p>This is the half a set comparison cannot see: both files can be perfectly symmetric and still
     * be missing a row a new binding needs, because nobody added it to either. Sourcing the names from
     * {@link ClientKeyBindings} means renaming a constant fails here rather than in a player's controls
     * screen.
     */
    @Test
    void everyKeyTheCodeAsksForByNameIsTranslated() {
        for (String language : LANGUAGES) {
            JsonObject file = lang(language);
            for (String key : REQUIRED_KEYS) {
                assertTrue(file.has(key), language + ".json is missing " + key);
                assertFalse(file.get(key).getAsString().isBlank(), language + ".json has a blank " + key);
            }
        }
    }

    /**
     * The split-key labels are pinned as words, not just as presence (spec §5.5, 2026-11-12).
     *
     * <p>The two bindings used to share one name ("UB cast / wheel (hold)"), which was exactly as
     * misleading as the one-key design it described. Now that they are separate actions, each label has
     * to name only its own action: a cast label mentioning the wheel, or a wheel label mentioning a
     * cast, is the old confusion coming back.
     */
    @Test
    void eachBindingLabelDescribesOnlyItsOwnAction() {
        for (String language : LANGUAGES) {
            JsonObject file = lang(language);
            String cast = file.get(ClientKeyBindings.UB_CAST_KEY).getAsString();
            String preset = file.get(ClientKeyBindings.UB_PRESET_KEY).getAsString();
            assertFalse(cast.equals(preset), language + ": the two bindings must not share one label");
            assertTrue(cast.toLowerCase(java.util.Locale.ROOT).contains("ub")
                            || cast.contains("UB") || cast.contains("释放"),
                    language + ": the cast label must name the cast it performs, got: " + cast);
            assertTrue(preset.contains("轮盘") || preset.toLowerCase(java.util.Locale.ROOT).contains("wheel"),
                    language + ": the preset label must name the wheel it opens, got: " + preset);
        }
    }
}

