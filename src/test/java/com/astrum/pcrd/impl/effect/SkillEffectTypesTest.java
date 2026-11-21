package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.SecondRotation;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>One effect type per <em>skill</em>, for every skill of every shipped character — asserted over the data, not
 * a sample.</b>
 *
 * <h2>The defect this answers</h2>
 * 「buff给予了后，给予新buff不是覆盖，而是同时存在，给予同一个buff才是刷新持续cd。我只装上雪菲，他只会显示冻结之湖或者雪鞋，
 * 不会两个同时存在。」
 *
 * <p>Vanilla merges a player's effects by effect <b>type</b>, so the old five types ({@code astrum:skill_1..5},
 * one per party slot) could hold one of 雪菲's skills at a time. The fix is one type per
 * {@code (characterId, skillTag)}, and this test is the guard that the set really follows the data:
 *
 * <ul>
 *   <li>{@link #everySkillOfEveryShippedCharacterHasAType()} re-derives the whole set from the <b>decoded</b>
 *       {@link CharacterDef} of every JSON in the pack's character directory and compares it with the registered
 *       declaration. A character added without rerunning {@code tools/gen_skill_effect_types.py} fails here
 *       instead of shipping one skill whose row never appears.</li>
 *   <li>{@link #theDeclarationIsRegisteredOneHolderPerSkill()} proves the manifest actually became registry
 *       holders — the gap between "a list exists" and "the game registered it".</li>
 *   <li>{@link #theIdRoundTripsAndRejectsForeignPaths()} and
 *       {@link #theTwoFamiliesDoNotShareAnId()} pin the path rule: a skill entry must never be classifiable as a
 *       party rune, or vanilla would merge the two families into one row.</li>
 *   <li>{@link #everyTypesDescriptionKeyIsTranslatedInBothLanguages()} is why the 91 types need no new language
 *       key: the name they can resolve at type level is one per <b>character</b>, and those 22 keys exist.</li>
 * </ul>
 *
 * <h2>What this cannot prove</h2>
 * That two rows <em>appear</em> on a player's effect bar: that needs a live {@code ServerPlayer} and a client,
 * and this JVM has neither. The mechanism is vanilla's own (a map keyed by type, {@code MobEffectInstance.update}
 * for a repeat), and {@code SkillEntryCoexistenceTest} asserts the two branches of it on real instances. The
 * report says which half is a human's check.
 */
class SkillEffectTypesTest {

    /** The count the brief names, pinned so a regression in the generator is visible as a number. */
    private static final int EXPECTED_TYPES = 91;

    /**
     * <b>Every skill of every shipped character has its own type.</b>
     *
     * <p>The expected set is derived from the decoded definitions — base hooks, affection ladders (which include
     * the {@code ex} tag), the second rotation's two hook lists, and the ultimate's own {@code ub} identity — so
     * it is the same four places the generator reads and the same fields the game reads. The character directory
     * comes from the classpath ({@code /pack.mcmeta}'s sibling), so a new JSON joins this assertion with no edit
     * here.
     */
    @Test
    void everySkillOfEveryShippedCharacterHasAType() throws Exception {
        Set<SkillEffectTypes.Identity> expected = new TreeSet<>(comparingIdentities());
        java.util.Map<String, CharacterDef> characters = shippedCharacters();
        for (var entry : characters.entrySet()) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("astrum", entry.getKey());
            for (String skill : tagsOf(entry.getValue())) {
                expected.add(new SkillEffectTypes.Identity(id, skill));
            }
        }
        assertTrue(characters.size() >= 22, "sanity: the shipped cast is 22 characters, found " + characters.size());

        Set<SkillEffectTypes.Identity> declared = new TreeSet<>(comparingIdentities());
        declared.addAll(SkillEffectTypes.declared());
        assertEquals(expected.size(), declared.size(),
                "the declaration must hold exactly one identity per (character, skill) in the data");
        assertEquals(expected, declared,
                "a declared type has no character/skill behind it, or a character's skill has no type — "
                        + "rerun tools/gen_skill_effect_types.py");
        assertEquals(EXPECTED_TYPES, declared.size(),
                "22 characters x (their skills + their UB) = 91; a changed number means the data changed and the "
                        + "brief's count is stale, not that the test is wrong");
    }

    /**
     * <b>The declaration became registry holders: one per identity, each keyed by its own id.</b>
     *
     * <p>{@code skillHolderFor} cannot answer in a unit test — a {@code DeferredHolder} is only bound once the
     * game's {@code RegisterEvent} runs — so this asserts the two facts that are about <em>registration</em>:
     * the holder list is one entry per declared identity, and each holder's key is the identity's id. A
     * registration loop that silently dropped one (a duplicate path, an exception swallowed) fails here.
     */
    @Test
    void theDeclarationIsRegisteredOneHolderPerSkill() {
        Set<ResourceLocation> keys = new TreeSet<>(comparingIds());
        for (var holder : PartyEffects.skillHolders()) {
            assertTrue(keys.add(holder.getKey().location()),
                    "two holders share one id, so one registration silently won: " + holder.getKey().location());
        }
        assertEquals(EXPECTED_TYPES, keys.size(),
                "one holder per declared type — the family is not five slots any more");
        Set<ResourceLocation> declared = new TreeSet<>(comparingIds());
        for (SkillEffectTypes.Identity identity : SkillEffectTypes.declared()) {
            declared.add(identity.id());
        }
        assertEquals(declared, keys);
        // And the grant path refuses a skill the jar has no type for — the third-party-datapack case
        // SkillEffectTypes documents. No exception, an empty answer.
        assertTrue(PartyEffects.skillHolderFor(ResourceLocation.parse("astrum:sheffy"), "no_such_skill").isEmpty(),
                "an undeclared skill has no type, and must answer empty rather than throw");
    }

    /**
     * <b>The path round-trips, and a path that merely looks like one is not one.</b>
     *
     * <p>Strictness matters: {@code PartyEffectSlots.slotOf} answers empty for {@code astrum:party_6}, and the
     * twin rule here is what keeps a stray id from being drawn as a skill row. Every rejection below is a shape
     * a hand-written id could take.
     */
    @Test
    void theIdRoundTripsAndRejectsForeignPaths() {
        SkillEffectTypes.Identity identity =
                new SkillEffectTypes.Identity(ResourceLocation.parse("astrum:sheffy"), "snow_boots");
        ResourceLocation id = identity.id();
        assertEquals("astrum", id.getNamespace());
        assertEquals("skill/astrum/sheffy/snow_boots", id.getPath(),
                "the path is the sprite path too: textures/mob_effect/<path>.png");
        assertEquals(Optional.of(identity), SkillEffectTypes.identityOf(id));

        assertTrue(SkillEffectTypes.identityOf((ResourceLocation) null).isEmpty());
        assertTrue(SkillEffectTypes.identityOf(ResourceLocation.parse("astrum:skill_1")).isEmpty(),
                "the retired per-slot id is not a skill type");
        assertTrue(SkillEffectTypes.identityOf(ResourceLocation.parse("astrum:skill/astrum/sheffy")).isEmpty(),
                "a missing skill segment is not an identity");
        assertTrue(SkillEffectTypes.identityOf(ResourceLocation.parse("astrum:skill/astrum//snow_boots")).isEmpty());
        assertTrue(SkillEffectTypes.identityOf(ResourceLocation.parse("minecraft:skill/astrum/sheffy/ex"))
                        .isEmpty(),
                "another namespace is not this mod's family");
        assertTrue(SkillEffectTypes.identityOf(ResourceLocation.parse("astrum:party_1")).isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> new SkillEffectTypes.Identity(null, "snow_boots"), "an identity needs a character");
        assertThrows(IllegalArgumentException.class,
                () -> new SkillEffectTypes.Identity(ResourceLocation.parse("astrum:sheffy"), "  "),
                "an identity needs a skill tag");
        assertThrows(IllegalArgumentException.class,
                () -> new SkillEffectTypes.Identity(ResourceLocation.parse("astrum:sheffy"), "snow/boots"),
                "a tag with a separator could not be parsed back out of the registry path");
    }

    /**
     * <b>The ultimate's reserved tag is the one the book and the cast path already use.</b>
     *
     * <p>{@link SkillEffectTypes#UB_SKILL} is a literal here rather than an import, because importing
     * {@code AffectionKernel} would drag {@code impl.growth} (and the whole codec tree) into a class that the
     * client's effect-render path loads. Two literals are only safe while something asserts they are one, and
     * this is that assertion: the entry the cast path writes and the row the book draws must be the same row.
     */
    @Test
    void theUltimateTagIsTheBooksOwnTag() {
        assertEquals(com.astrum.pcrd.impl.growth.AffectionKernel.ubKey(), SkillEffectTypes.UB_SKILL,
                "AffectionKernel.ubKey() is the tag the book's last row and the cast path use; a drift here "
                        + "would make the cast's row unresolvable");
    }

    /** The two id families are disjoint: a skill entry can never be classified as a party rune. */
    @Test
    void theTwoFamiliesDoNotShareAnId() {
        List<ResourceLocation> party = PartyEffectSlots.ids();
        List<ResourceLocation> mechanics = new ArrayList<>();
        for (var holder : PartyEffects.mechanicHolders()) {
            mechanics.add(holder.getKey().location());
        }
        for (SkillEffectTypes.Identity identity : SkillEffectTypes.declared()) {
            ResourceLocation id = identity.id();
            assertFalse(party.contains(id), "a skill type must not also be a party rune: " + id);
            assertFalse(mechanics.contains(id), "nor a mechanic: " + id);
            assertFalse(SkillEffectTypes.identityOf(ResourceLocation.parse("astrum:party_1")).isPresent());
        }
        assertEquals(EXPECTED_TYPES, PartyEffects.skillHolders().size());
    }

    /**
     * <b>Every type's description key is translated in both languages — and there are 22 of them, not 91.</b>
     *
     * <h2>Why no per-type key exists</h2>
     * A skill's name is <b>data</b>: {@code SkillLadder.baseName} plus the {@code +} and 6★ renames. A translation
     * cannot hold it, so the 91 types would need 91 keys that duplicate the JSON. What a type <em>can</em> answer
     * is the character it belongs to ({@link SkillEffectTypes#nameKeyFor}), and those 22 keys already exist.
     *
     * <p>So this asserts the keys the types really ask for — built by constructing the real
     * {@link AstrumSkillEffect}s and reading {@code getDescriptionId}, not by copying the format string — are
     * present in both files. A new character whose name key was forgotten fails here, with her id in the message.
     */
    @Test
    void everyTypesDescriptionKeyIsTranslatedInBothLanguages() throws Exception {
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        assertTrue(zh.has(SkillEffectTypes.FALLBACK_NAME_KEY), "the one fallback key must exist");
        assertEquals(zh.has(SkillEffectTypes.FALLBACK_NAME_KEY), en.has(SkillEffectTypes.FALLBACK_NAME_KEY));
        Set<String> keys = new TreeSet<>();
        for (SkillEffectTypes.Identity identity : SkillEffectTypes.declared()) {
            String key = new AstrumSkillEffect(identity).getDescriptionId();
            assertTrue(key.startsWith(SkillEffectTypes.FALLBACK_NAME_KEY + "."),
                    "a type-level name key, per character: " + key);
            keys.add(key);
            assertTrue(zh.has(key), "zh_cn is missing the name key of " + identity.label() + ": " + key);
            assertTrue(en.has(key), "en_us is missing the name key of " + identity.label() + ": " + key);
        }
        assertEquals(shippedCharacters().size(), keys.size(),
                "one key per character, not per skill — that is the point of the decision");
    }

    /**
     * <b>The skill family's palette is its own, and it is the party family's opposite in character.</b>
     *
     * <p>A skill row and a party rune sit in the same column; two rows of unrelated kinds sharing a tint read as
     * two halves of one thing. The palette is not per type (91 colours would be noise) but per character, so one
     * character's skills share a family tint and two characters' rows stay tellable apart.
     */
    @Test
    void theSkillPaletteIsDisjointFromThePartys() {
        Set<Integer> party = new LinkedHashSet<>();
        for (int colour : PartyEffectSlots.SLOT_COLORS) {
            party.add(colour);
        }
        Set<Integer> skill = new LinkedHashSet<>();
        for (int colour : AstrumSkillEffect.SKILL_COLORS) {
            assertTrue(skill.add(colour), "each palette entry must be distinct");
            assertFalse(party.contains(colour),
                    "the skill family's colours are not the party family's: " + Integer.toHexString(colour));
        }
        assertTrue(skill.size() >= 4, "at least a handful of tints, or two characters' rows look the same");
        // Same character, same tint; different characters are not forced apart by the hash but the palette is
        // shared, which is all a colour can honestly promise.
        int first = new AstrumSkillEffect(
                new SkillEffectTypes.Identity(ResourceLocation.parse("astrum:sheffy"), "snow_boots"))
                .getColor();
        int second = new AstrumSkillEffect(
                new SkillEffectTypes.Identity(ResourceLocation.parse("astrum:sheffy"), "frozen_lake"))
                .getColor();
        assertEquals(first, second, "one character's skills share her family tint");
    }

    // -----------------------------------------------------------------------------------------
    // Helpers: the pack's own data, decoded through the real codec
    // -----------------------------------------------------------------------------------------

    /** {@return the character directory's JSONs, decoded, keyed by file stem (the {@code astrum:} path)} */
    private static java.util.Map<String, CharacterDef> shippedCharacters() throws java.io.IOException {
        Path directory = packRoot().resolve("data/astrum/"
                + net.minecraft.core.registries.Registries.elementsDirPath(RegistryKeys.CHARACTERS));
        assertTrue(Files.isDirectory(directory), "the character directory must be packaged: " + directory);
        java.util.Map<String, CharacterDef> characters = new java.util.TreeMap<>();
        List<Path> files;
        try (var listed = Files.list(directory)) {
            files = listed.filter(path -> path.toString().endsWith(".json")).toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            name = name.substring(0, name.length() - ".json".length());
            characters.put(name, decode(name));
        }
        return characters;
    }

    /** {@return {@code name}'s definition, decoded through {@link CharacterDef#CODEC}} */
    private static CharacterDef decode(String name) {
        String path = "/data/astrum/" + net.minecraft.core.registries.Registries.elementsDirPath(
                RegistryKeys.CHARACTERS) + "/" + name + ".json";
        try (InputStream in = SkillEffectTypesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, name + ".json must be on the test classpath: " + path);
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(
                    error -> new IllegalStateException(name + ".json does not decode: " + error));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * {@return every skill tag {@code def} can fire or show}
     *
     * <p>The four places the game reads, in the generator's order: base hooks, the affection ladders (which
     * include {@code ex}), the second loop's two hook lists, and the ultimate's own identity. Deliberately
     * <b>not</b> {@code ub.<rung>.effects}: a {@code UbEffect} has no trigger-params component, so a skill tag
     * written there is a field the codec ignores — a type derived from it could never be granted.
     */
    private static Set<String> tagsOf(CharacterDef def) {
        Set<String> tags = new LinkedHashSet<>();
        for (EffectHook hook : def.effects()) {
            if (hook != null) {
                hook.triggerParams().skillTag().ifPresent(tags::add);
            }
        }
        AffectionDef affection = def.affection();
        if (affection != null) {
            for (SkillLadder ladder : affection.skills()) {
                tags.add(ladder.skill());
            }
        }
        if (def.ub() != null) {
            Optional<SecondRotation> second = def.ub().secondRotationOf();
            if (second.isPresent()) {
                for (EffectHook hook : second.get().hooksAt(1)) {
                    if (hook != null) {
                        hook.triggerParams().skillTag().ifPresent(tags::add);
                    }
                }
                for (EffectHook hook : second.get().hooksAt(2)) {
                    if (hook != null) {
                        hook.triggerParams().skillTag().ifPresent(tags::add);
                    }
                }
            }
            tags.add(SkillEffectTypes.UB_SKILL);
        }
        tags.removeIf(tag -> tag == null || tag.isBlank());
        return tags;
    }

    /** {@return the resource tree this build packaged, found through {@code /pack.mcmeta}} */
    private static Path packRoot() {
        URL meta = SkillEffectTypesTest.class.getResource("/pack.mcmeta");
        assertNotNull(meta, "the mod's pack.mcmeta must be on the test classpath");
        try {
            return Path.of(meta.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("pack.mcmeta has no usable path: " + meta, e);
        }
    }

    /** {@return one language file, parsed} */
    private static JsonObject lang(String code) throws java.io.IOException {
        String path = "/assets/astrum/lang/" + code + ".json";
        try (InputStream in = SkillEffectTypesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing language file on the classpath: " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private static java.util.Comparator<SkillEffectTypes.Identity> comparingIdentities() {
        return java.util.Comparator.comparing(SkillEffectTypes.Identity::label);
    }

    private static java.util.Comparator<ResourceLocation> comparingIds() {
        return java.util.Comparator.comparing(ResourceLocation::toString);
    }
}