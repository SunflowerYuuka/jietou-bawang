package com.astrum.pcrd.api.v1;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The tier→avatar rule</b>: affection tier 1 draws the 1★ art, tier 2 the 3★, tier 3 the 6★ — and tier 3
 * degrades to the 3★ art when the character has no 6★ picture.
 *
 * <h2>What the human asked for</h2>
 * 「角色头像要跟随好感度进行变化，符合前面的1* 3* 6*的要求」. Spec §9.4 already said the same thing; it had never
 * been implemented, so every surface drew one {@code avatar} forever.
 *
 * <h2>Why this is a test and not a comment</h2>
 * The rule is four mappings and one fallback, and every wrong version of it is <b>silent</b>: a table that
 * answered the 1★ art at every tier, or one that answered {@code null} at tier 3 for a character without 6★
 * art, would compile, ship, and look like "the feature does not work" on a screen nobody here can see. So each
 * mapping is asserted, then the degradation, then the two backwards-compatibility cases that keep old packs
 * looking exactly as they did.
 *
 * <p>The definitions are built directly rather than decoded: this is the record's own behaviour, and
 * {@code CharacterDefCodecTest} already pins that the three fields round-trip through JSON.
 */
class TierAvatarTest {

    private static final ResourceLocation AVATAR = texture("avatar");
    private static final ResourceLocation ONE = texture("icon1");
    private static final ResourceLocation THREE = texture("icon3");
    private static final ResourceLocation SIX = texture("icon6");

    /** The headline: the three tiers, and the picture each one draws. */
    @Test
    void eachTierDrawsItsOwnRarity() {
        CharacterDef full = definition(Optional.of(AVATAR), Optional.of(ONE), Optional.of(THREE),
                Optional.of(SIX));

        assertEquals(Optional.of(ONE), full.face(1), "tier 1 draws the 1★ art");
        assertEquals(Optional.of(THREE), full.face(2), "tier 2 draws the 3★ art");
        assertEquals(Optional.of(SIX), full.face(3), "tier 3 draws the 6★ art");
    }

    /**
     * The one degradation spec §9.4 allows: tier 3 with no 6★ art draws the <b>3★</b> art, not the plain avatar
     * and certainly not nothing.
     *
     * <p>This is the case that actually happens with a real pack: the official set does not ship a 6★ avatar
     * for every unit, so a character grown to the top tier is the normal way to reach this branch.
     */
    @Test
    void tierThreeWithoutSixStarArtDegradesToTheThreeStarArt() {
        CharacterDef noSix = definition(Optional.of(AVATAR), Optional.of(ONE), Optional.of(THREE),
                Optional.empty());

        assertEquals(Optional.of(THREE), noSix.face(3),
                "no 6★ art means her 3★ art at tier 3, never the enrolled picture");
        assertEquals(Optional.of(THREE), noSix.face(2));
        assertEquals(Optional.of(ONE), noSix.face(1));
    }

    /**
     * A pack written before these fields existed names none of them, and every tier must answer exactly what it
     * answered before: the {@code portrait} override if present, else the {@code avatar}.
     *
     * <p>This is the whole additive promise. If it broke, every character in every existing datapack would lose
     * her face on the first world load after the update.
     */
    @Test
    void aDefinitionWithoutTierArtKeepsItsOnePictureAtEveryTier() {
        CharacterDef plain = definition(Optional.of(AVATAR), Optional.empty(), Optional.empty(),
                Optional.empty());

        for (int tier = 1; tier <= 3; tier++) {
            assertEquals(Optional.of(AVATAR), plain.face(tier), "tier " + tier + " keeps the plain avatar");
        }

        // …and the portrait override still wins for the book, exactly as face() promises.
        CharacterDef overridden = new CharacterDef(1, "test", Dimension.CHARGE,
                Optional.of(texture("book_only")), Optional.empty(), List.of(), ub(), growth(),
                affection(), Optional.empty(), Optional.of(AVATAR), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
        for (int tier = 1; tier <= 3; tier++) {
            assertEquals(Optional.of(texture("book_only")), overridden.face(tier),
                    "the book override is what a tierless pack draws, at every tier");
        }
    }

    /**
     * A character with only <em>some</em> rarities still gets a picture everywhere, by climbing the chain.
     *
     * <p>Real packs are like this: one unit might ship 1★ and 6★ and no 3★. Answering {@code empty()} in the
     * middle would put a glyph disc in the roster for a character who has two pictures.
     */
    @Test
    void aPartialRaritySetStillAnswersEveryTier() {
        CharacterDef noThree = definition(Optional.empty(), Optional.of(ONE), Optional.empty(),
                Optional.of(SIX));
        assertEquals(Optional.of(ONE), noThree.face(1));
        assertEquals(Optional.of(ONE), noThree.face(2), "no 3★ art: the 1★ art stands in, not nothing");
        assertEquals(Optional.of(SIX), noThree.face(3));

        CharacterDef onlySix = definition(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(SIX));
        assertEquals(Optional.of(SIX), onlySix.face(1), "the only picture she has, at every tier");
        assertEquals(Optional.of(SIX), onlySix.face(2));
        assertEquals(Optional.of(SIX), onlySix.face(3));
    }

    /**
     * The tier argument is a tier, not an affection number, and the method is total for anything a caller can
     * pass: an out-of-range tier reads as the nearest real one rather than throwing on the render path.
     */
    @Test
    void anOutOfRangeTierDoesNotThrow() {
        CharacterDef full = definition(Optional.of(AVATAR), Optional.of(ONE), Optional.of(THREE),
                Optional.of(SIX));

        assertEquals(full.face(1), full.face(0), "0 is below the ladder: the first tier");
        assertEquals(full.face(1), full.face(-7), "a negative tier is a bug, not a crash");
        assertEquals(full.face(3), full.face(4), "above the ladder is the top tier");
        assertEquals(full.face(3), full.face(Integer.MAX_VALUE));
    }

    /**
     * {@link CharacterDef#faceAtLowestTier()} — the "surface without a tier" answer — is the 1★ art.
     *
     * <p>Used where a picture is needed before a progress row exists (and by anything that simply does not know
     * a tier). The lowest tier is chosen because it is the only one nobody had to earn.
     */
    @Test
    void theTierlessFaceIsTheLowestTier() {
        CharacterDef full = definition(Optional.of(AVATAR), Optional.of(ONE), Optional.of(THREE),
                Optional.of(SIX));
        assertEquals(full.face(1), full.faceAtLowestTier());

        CharacterDef plain = definition(Optional.of(AVATAR), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertEquals(Optional.of(AVATAR), plain.faceAtLowestTier(),
                "with no tier art it is the one picture, as before");
    }

    // -----------------------------------------------------------------------------------------
    // The still, which follows the same tier for a different picture family
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The background still follows the affection tier</b> — 「背景立绘要随着好感的升阶更换背景立绘」.
     *
     * <h2>Why tier 1 and tier 2 are the same picture, deliberately</h2>
     * The official dump has a 3★ still and a 6★ still for Djeeta and <b>no 1★ still at all</b>
     * ({@code still_unit_105711} does not exist). So {@code still} is the 3★ rung and is drawn at tier 1 and
     * tier 2; tier 3 draws {@code still6th}. Asserting that tier 1 == tier 2 is the point — the alternative,
     * a tier-1 character with no background, is the dark plate the human asked to be rid of.
     */
    @Test
    void eachTierDrawsItsOwnStillWithTheDocumentedDegradation() {
        CharacterDef full = withStills(Optional.of(stillTexture("3")), Optional.of(stillTexture("6")));

        assertEquals(Optional.of(stillTexture("3")), full.still(1),
                "there is no 1★ still in the official set, so tier 1 draws the 3★ art");
        assertEquals(Optional.of(stillTexture("3")), full.still(2), "tier 2 draws the 3★ art");
        assertEquals(Optional.of(stillTexture("6")), full.still(3), "tier 3 draws the 6★ art");
    }

    /**
     * A character with no 6★ still degrades to her 3★ one — the same rule {@code face(int)} follows for
     * avatars, so a unit the art set skipped is not left with no background at the top tier.
     */
    @Test
    void tierThreeWithoutASixStarStillDegradesToTheThreeStarStill() {
        CharacterDef noSix = withStills(Optional.of(stillTexture("3")), Optional.empty());

        assertEquals(Optional.of(stillTexture("3")), noSix.still(3),
                "no 6★ still means the 3★ still at tier 3, never no background");
        assertEquals(Optional.of(stillTexture("3")), noSix.still(2));
        assertEquals(Optional.of(stillTexture("3")), noSix.still(1));
    }

    /**
     * A pack written before {@code still6th} existed names only {@code still}, and every tier must answer
     * exactly what it answered before: that one picture.
     *
     * <p>This is the additive promise, and it is the case that keeps every existing datapack working.
     */
    @Test
    void aTierlessStillDefinitionKeepsItsOnePictureAtEveryTier() {
        CharacterDef plain = withStills(Optional.of(stillTexture("3")), Optional.empty());

        for (int tier = 1; tier <= 3; tier++) {
            assertEquals(Optional.of(stillTexture("3")), plain.still(tier),
                    "tier " + tier + " keeps the tierless still");
        }

        CharacterDef none = withStills(Optional.empty(), Optional.empty());
        for (int tier = 1; tier <= 3; tier++) {
            assertTrue(none.still(tier).isEmpty(), "no still art at all stays empty at tier " + tier);
        }
    }

    /** An out-of-range tier reads as the nearest real one rather than throwing on the render path. */
    @Test
    void anOutOfRangeTierStillAnswersAStill() {
        CharacterDef full = withStills(Optional.of(stillTexture("3")), Optional.of(stillTexture("6")));

        assertEquals(full.still(1), full.still(0), "0 is below the ladder: the first tier");
        assertEquals(full.still(1), full.still(-7), "a negative tier is a bug, not a crash");
        assertEquals(full.still(3), full.still(4), "above the ladder is the top tier");
        assertEquals(full.still(3), full.still(Integer.MAX_VALUE));
    }

    /**
     * A character who ships <b>only</b> a 6★ still has no background at tier 1 or 2 — and that is the honest
     * answer, not a bug to paper over.
     *
     * <p>{@code still} is the lower rung, so a pack that fills only {@code still6th} is saying "she has 6★ art
     * and nothing below it". Inventing a tier-1 picture would mean drawing the 6★ art at tier 1, which is the
     * opposite of the rule this feature implements. The panel then keeps its plain dark body, the documented
     * "no still art" case — and no character in the shipped pack is in this state ({@code djeeta.json} names
     * both rungs).
     */
    @Test
    void aSixStarOnlyDefinitionHasNoLowerRungRatherThanBorrowingTheTopOne() {
        CharacterDef onlySix = withStills(Optional.empty(), Optional.of(stillTexture("6")));

        assertTrue(onlySix.still(1).isEmpty(), "tier 1 must not borrow the 6★ art");
        assertTrue(onlySix.still(2).isEmpty(), "tier 2 either");
        assertEquals(Optional.of(stillTexture("6")), onlySix.still(3), "tier 3 draws it");
    }

    // -----------------------------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------------------------

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/portrait/" + name + ".png");
    }

    /** {@return a still texture location; a separate tree from the avatars, so a mix-up would show} */
    private static ResourceLocation stillTexture(String rung) {
        return ResourceLocation.fromNamespaceAndPath("astrum",
                "textures/gui/still/djeeta_" + rung + ".png");
    }

    /** {@return a definition carrying only the two still fields, which is all {@code still(int)} reads} */
    private static CharacterDef withStills(Optional<ResourceLocation> still,
            Optional<ResourceLocation> six) {
        return new CharacterDef(1, "姬塔", Dimension.CHARGE, Optional.empty(), Optional.empty(),
                List.of(), ub(), growth(), affection(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), still, six);
    }

    private static CharacterDef definition(Optional<ResourceLocation> avatar,
            Optional<ResourceLocation> one, Optional<ResourceLocation> three,
            Optional<ResourceLocation> six) {
        return new CharacterDef(1, "姬塔", Dimension.CHARGE, Optional.empty(), Optional.empty(),
                List.of(), ub(), growth(), affection(), Optional.empty(), avatar, one, three, six,
                Optional.empty(), Optional.empty());
    }

    private static UbDef ub() {
        UbTier tier = new UbTier("base", 1, 20.0, OptionalDouble.empty(), List.of());
        return new UbDef(tier, tier);
    }

    private static GrowthDef growth() {
        return new GrowthDef(100, 1.15, 30);
    }

    private static AffectionDef affection() {
        return new AffectionDef(3, 8, List.of());
    }
}
