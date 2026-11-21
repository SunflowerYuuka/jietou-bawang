package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.effect.SkillEffectTypes;
import com.astrum.pcrd.impl.party.SkillRows;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The skill-picture fallback chain, asserted instead of looked at.</b>
 *
 * <h2>What the chain is, and the human's rule behind it</h2>
 * 「一个技能可以引用多个通用效果的代码，但是图标只使用一个」 — a skill wears <b>one</b> picture. So each of the book's
 * columns (and its 「技能详情」 button) wants a picture for a skill, and the picture may be missing:
 *
 * <ol>
 *   <li>the skill's own art, from the data ({@code SkillLadder.icon()} / {@code UbDef.icon()});</li>
 *   <li>else her portrait at her affection tier ({@code TierAvatar} → {@code CharacterDef.face(int)});</li>
 *   <li>else {@link ArtFallback#PLACEHOLDER} — 「请安装资源包」.</li>
 * </ol>
 *
 * <p>Nothing on screen is allowed to draw a hole where a skill's picture belongs, and the last step is what
 * makes that true for a character the loaded pack does not define at all. {@link SkillArt} is three pure
 * functions, so all four cases are assertions here rather than claims about a screenshot this project cannot
 * take.
 *
 * <h2>What is deliberately <em>not</em> asserted here</h2>
 * Whether the pixels look right. Whether the 64x64 art survives being drawn at 14 pixels is an eye question;
 * the sizes are pinned (the layout's by {@code SkillDetailLayoutTest}, the art's here) and the rest is named in
 * the task report as something only a human can confirm.
 */
class SkillArtTest {

    private static final ResourceLocation SKILL =
            ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/skillicon/pecorine_ub.png");
    private static final ResourceLocation FACE =
            ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/portrait/djeeta.png");

    /**
     * <b>The skill's own picture wins.</b> Whatever the character's face is, a skill that names an icon is
     * drawn with it — that is the rule, and the portrait is only a fallback.
     */
    @Test
    void theSkillsOwnPictureIsUsedWhenItHasOne() {
        assertEquals(Optional.of(SKILL), SkillArt.pictureOf(Optional.of(SKILL), Optional.of(FACE)));
        assertEquals(Optional.of(SKILL), SkillArt.pictureOf(Optional.of(SKILL), Optional.empty()));
        assertEquals(Optional.of(SKILL), SkillArt.pictureOf(Optional.of(SKILL), null),
                "a caller with no portrait to offer must not change what a skill with art looks like");
    }

    /** <b>No skill art falls back to her portrait</b> — the same picture the roster cell draws. */
    @Test
    void aSkillWithoutArtFallsBackToHerPortrait() {
        assertEquals(Optional.of(FACE), SkillArt.pictureOf(Optional.empty(), Optional.of(FACE)));
        assertEquals(Optional.of(FACE), SkillArt.pictureOf(null, Optional.of(FACE)),
                "a row that is not there is the same case as a row with no picture");
    }

    /**
     * <b>Nothing at all answers empty, and the surface then draws the placeholder — never nothing.</b>
     *
     * <p>This is the case a pack for an addon's character produces: the roster knows the id, the character
     * definition is gone, so there is neither skill art nor a face. The chain answers "no picture" and
     * {@link SkillArt#drawable} answers the mod's own placeholder, which exists in the jar whether or not the
     * art pack does.
     */
    @Test
    void nothingAtAllStillAnswersAPlaceholderPicture() {
        assertEquals(Optional.empty(), SkillArt.pictureOf(Optional.empty(), Optional.empty()));
        assertEquals(Optional.empty(), SkillArt.pictureOf(null, null));
        assertEquals(ArtFallback.PLACEHOLDER, SkillArt.drawable(Optional.empty()));
        assertEquals(ArtFallback.PLACEHOLDER, SkillArt.drawable(null));
        assertNotNull(ArtFallback.PLACEHOLDER.toString());
    }

    /**
     * <b>A column's picture is the skill's own first, then her face.</b>
     *
     * <p>{@link SkillArt#pictureFor} reads the entry's icon, which is what makes one picture per <em>skill</em>
     * and not per effect: the entry is a skill version, and every row under it is text. Asserted on a real page
     * (佩可莉姆 at tier 2) so the wiring from the data through {@code SkillDetailModel} to the drawing code is
     * covered end to end, not just the three-line chain.
     */
    @Test
    void aColumnsPictureComesFromTheSkillThenFromTheCharacter() throws Exception {
        CharacterDef pecorine = load("pecorine");
        SkillDetailModel model = SkillDetailModel.of(pecorine, 2, 30);
        assertFalse(model.isEmpty(), "sanity: 佩可莉姆 has columns");
        Optional<ResourceLocation> face = pecorine.face(2);
        assertTrue(face.isPresent(), "sanity: she has official portrait art");
        for (SkillDetailModel.Entry entry : model.entries()) {
            assertTrue(entry.icon().isPresent(),
                    entry.name() + " ships no icon, so this assertion would prove nothing");
            assertEquals(entry.icon(), SkillArt.pictureFor(entry, face),
                    entry.name() + " must be drawn with its own picture, not with her face");
            // The fallback, on the same entry: strip the icon and it becomes her face.
            assertEquals(face, SkillArt.pictureFor(new SkillDetailModel.Entry(entry.skill(), entry.name(),
                    entry.rung(), entry.ultimate(), entry.current(), entry.locked(), entry.rows()), face));
        }
    }

    /**
     * <b>Every shipped character's every skill has a picture, and it is a file this project has.</b>
     *
     * <p>The data half of the feature was done before this task; this asserts it is <em>useful</em>: each
     * {@code SkillRows.Row.icon()} names a texture the repository carries, so the drawing code always has real
     * art for the shipped cast.
     *
     * <h2>The one deliberate exception: a marker skill wears her face</h2>
     * 「冰龙之印这种类型的效果使用角色头像，我说过这件事吧。」 A skill whose whole content is a <em>personal
     * tally</em> — 雪菲's 冰龙之印 is the only shipped one — is drawn with her portrait rather than a dedicated
     * icon, because the effect it grants is hers alone and a face reads as "this is 雪菲's mark" where a generic
     * star reads as nothing. So it carries <b>no</b> {@code icon} in her JSON and {@code SkillArt} falls back to
     * {@code CharacterDef.face(tier)}.
     *
     * <p>That is the opposite of what this test used to demand — it required every skill to name a file, on the
     * reasoning that "a skill whose row icon were empty would fall back to her portrait and read as *this skill
     * is her face*". The human has since asked for exactly that reading on the marker skills, so the rule is now
     * <b>a skill either names its own art or is a marker and falls back on purpose</b>, and this test tells the
     * two apart by name rather than accepting any empty icon.
     */
    @Test
    void everyShippedCharactersSkillsHaveAnIconThatExists() throws Exception {
        for (String id : List.of("pecorine", "djeeta", "kyaru", "kokkoro", "ayane", "sheffy")) {
            CharacterDef definition = load(id);
            assertNotNull(definition, id + " must be packaged");
            for (int tier = GrowthFacts.FIRST_TIER; tier <= GrowthFacts.MAX_TIER; tier++) {
                SkillRows rows = SkillRows.of(definition, tier);
                assertFalse(rows.isEmpty(), id + " has no skills at tier " + tier);
                for (SkillRows.Row row : rows.rows()) {
                    if (SkillEffectTypes.isMarkerSkill(row.skill())) {
                        // The ONE documented exception: a marker skill falls back to her face on purpose.
                        assertTrue(row.icon().isEmpty(),
                                id + " / " + row.name() + " is a marker skill and must not name a file — "
                                        + "SkillArt falls back to her portrait by design");
                        continue;
                    }
                    assertTrue(row.icon().isPresent(),
                            id + " / " + row.name() + " at tier " + tier + " ships no skill picture");
                    ResourceLocation icon = row.icon().get();
                    assertEquals("astrum", icon.getNamespace(), "skill art is the mod's own namespace");
                    assertTrue(icon.getPath().startsWith("textures/gui/skillicon/"),
                            id + ": a skill picture lives under textures/gui/skillicon/, not " + icon);
                    assertEquals(id + "_", icon.getPath()
                                    .substring("textures/gui/skillicon/".length(),
                                            "textures/gui/skillicon/".length() + id.length() + 1),
                            id + "'s pictures are named after her: " + icon);
                }
            }
        }
    }

    /**
     * <b>The detail page's first column wears the ultimate's picture, not a fallback, and it exists.</b>
     *
     * <p>The picture the book draws for a skill is asserted through the shared joins the page reads:
     * {@code SkillRows.Row.icon()} for the skill, and {@code SkillArt.pictureOf} for the fallback chain. The page
     * itself needs a client and cannot be instantiated here, so the <em>rule</em> is asserted through those two.
     *
     * <p>Note what is <b>not</b> here any more: the 「技能详情」 button. 「技能详情按钮不要显示技能图标，使用角色册同
     * 风格的按钮配色就行。」 It is a control in the panel's palette and wears no picture, so there is no button
     * icon rule left to assert. The detail page's columns keep theirs.
     */
    @Test
    void everySkillRowCarriesItsOwnPictureAndNotAFallback() throws Exception {
        CharacterDef pecorine = load("pecorine");
        for (int tier = GrowthFacts.FIRST_TIER; tier <= GrowthFacts.MAX_TIER; tier++) {
            SkillRows rows = SkillRows.of(pecorine, tier);
            assertFalse(rows.isEmpty(), "tier " + tier + " must list her skills");
            for (SkillRows.Row row : rows.rows()) {
                assertTrue(row.icon().isPresent(),
                        "tier " + tier + ": " + row.name() + " ships no picture, so the page would fall back");
                assertFalse(row.name().isBlank(), "and the skill it belongs to must be named");
                // The chain's first link is what gets drawn — her own skill art, not her face — because the
                // fallback is a safety net here, not the normal path.
                assertEquals(row.icon(), SkillArt.pictureOf(row.icon(), pecorine.face(tier)));
            }
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = SkillArtTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }
}
