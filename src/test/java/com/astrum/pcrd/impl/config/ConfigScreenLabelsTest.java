package com.astrum.pcrd.impl.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.astrum.pcrd.Astrum;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the half of the in-game config screen (spec §8.0) that is ours: the names the generated screen
 * asks for, and the widget NeoForge picks for the options a player must be able to change in game.
 *
 * <h2>Where the keys come from — read out of the NeoForge sources, not guessed</h2>
 * {@code ConfigurationScreen} builds a row per entry and labels it with
 * {@code ConfigurationSectionScreen.getTranslationComponent(key)}, which resolves through
 * {@code getTranslationKey}:
 *
 * <pre>
 * result = valueSpec.getTranslationKey();                   // null unless the spec called .translation()
 * if (result == null) result = modSpec.getLevelTranslationKey(makeKeyList(key));
 * return result != null ? result : modId + ".configuration." + key;
 * </pre>
 *
 * <p>Our spec declares no translation keys, so the fallback is the one that runs and the label key is
 * {@code astrum.configuration.<leafName>} — the <em>leaf</em> name only, the section is not part of it.
 * Sections are labelled by the neighbouring branch, which appends {@code .button} to the section's own
 * name. Both spellings are derived here from the specs themselves, so a new option without a label
 * fails this test instead of showing a raw key in the player's face.
 *
 * <h2>Why the widget types are pinned too</h2>
 * {@code ConfigurationSectionScreen.rebuild} switches on the value's own type:
 * {@code ModConfigSpec.EnumValue -> createEnumValue} and
 * {@code ModConfigSpec.BooleanValue -> createBooleanValue}, and both of those build an
 * {@code OptionInstance} over a {@code CycleButton} — a button the player clicks. A plain
 * {@code ConfigValue<String>}, by contrast, becomes an {@code EditBox}: a text field the player has to
 * type into, which is not what {@code wheelMode} / {@code hudAnchor} are supposed to be. Declaring
 * {@code wheelMode} as a string would therefore have silently degraded the screen, and this test is
 * what makes that impossible to do by accident.
 */
class ConfigScreenLabelsTest {

    /** Both files' names as {@code ConfigurationScreen} spells them: {@code <modid>-<type>.toml}. */
    private static final String CLIENT_FILE_KEY = Astrum.MOD_ID + ".configuration.section.astrum.client.toml";

    private static final String SERVER_FILE_KEY = Astrum.MOD_ID + ".configuration.section.astrum.server.toml";

    @Test
    void everyOptionTheScreenShowsIsNamedInBothLanguages() {
        Set<String> keys = new TreeSet<>();
        keys.addAll(labelKeys(ClientConfig.SPEC));
        keys.addAll(labelKeys(ServerConfig.SPEC));
        assertTrue(keys.size() > 20, "sanity: the walk should have found every option and section, found " + keys);

        for (String language : new String[] { "en_us", "zh_cn" }) {
            String json = lang(language);
            for (String key : keys) {
                assertTrue(json.contains("\"" + key + "\""),
                        () -> language + ".json is missing the label the config screen will ask for: " + key);
            }
        }
    }

    @Test
    void bothConfigFilesAndTheScreenItselfHaveTitles() {
        Set<String> keys = Set.of(
                Astrum.MOD_ID + ".configuration.title",
                CLIENT_FILE_KEY, CLIENT_FILE_KEY + ".title",
                SERVER_FILE_KEY, SERVER_FILE_KEY + ".title");
        for (String language : new String[] { "en_us", "zh_cn" }) {
            String json = lang(language);
            for (String key : keys) {
                assertTrue(json.contains("\"" + key + "\""), () -> language + ".json is missing " + key);
            }
        }
    }

    /**
     * The three options the player must be able to change without typing go through the cycling
     * widgets. {@code wheelMode} and {@code hudAnchor} are {@code EnumValue}; {@code enableUbHud} is a
     * {@code BooleanValue} — both branches of {@code rebuild}'s switch.
     */
    @Test
    void thePlayerFacingChoicesAreCycleButtonsNotTextBoxes() {
        assertEquals(ModConfigSpec.EnumValue.class, ClientConfig.WHEEL.wheelMode.getClass(),
                "wheelMode must stay an EnumValue: a String ConfigValue renders as an EditBox in the config screen");
        assertEquals(ModConfigSpec.EnumValue.class, ClientConfig.HUD.hudAnchor.getClass(),
                "hudAnchor must stay an EnumValue, or the player has to type BOTTOM_LEFT correctly");
        assertEquals(ModConfigSpec.BooleanValue.class, ClientConfig.HUD.enableUbHud.getClass(),
                "enableUbHud must stay a BooleanValue: it renders as an on/off cycle button");
        // Not a new option, but the same requirement: the third cyclable value in [wheel].
        assertEquals(ModConfigSpec.BooleanValue.class, ClientConfig.WHEEL.enableScrollSwitching.getClass());
    }

    /**
     * {@return every translation key {@code ConfigurationScreen} will ask for, given one spec}
     *
     * <p>Leaves are labelled by their own name ({@code astrum.configuration.<leaf>}). A section row asks
     * for <b>two</b> keys — {@code createSection} builds the left-hand label from
     * {@code getTranslationComponent(key)} and the button from {@code getTranslationKey(key) + ".button"}
     * (that one has a NeoForge fallback, "Edit", which is why it is only a nice-to-have; the label has
     * none, so without it the player reads a raw key). The section label used to be the one I missed:
     * the live screen showed {@code astrum.configuration.wheel…} until this list grew the plain key.
     */
    private static Set<String> labelKeys(ModConfigSpec spec) {
        Set<String> keys = new TreeSet<>();
        collect(spec.getSpec(), keys);
        return keys;
    }

    private static void collect(UnmodifiableConfig config, Set<String> into) {
        // entrySet() rather than valueMap(), which is deprecated in nightconfig 3.8.3.
        for (var entry : config.entrySet()) {
            String key = Astrum.MOD_ID + ".configuration." + entry.getKey();
            if (entry.getValue() instanceof UnmodifiableConfig subsection) {
                into.add(key);
                into.add(key + ".button");
                collect(subsection, into);
            } else {
                into.add(key);
            }
        }
    }

    private static String lang(String code) {
        String path = "/assets/astrum/lang/" + code + ".json";
        try (InputStream in = ConfigScreenLabelsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing language file on the classpath: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
