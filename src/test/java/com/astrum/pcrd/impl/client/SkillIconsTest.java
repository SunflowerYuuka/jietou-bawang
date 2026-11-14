package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.impl.effect.AttackBuffs;
import com.astrum.pcrd.impl.effect.PartyEffectSlots;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hook -> icon mapping, the fact that the icons it names are shipped, and <b>the HUD sprite every
 * registered effect resolves at runtime</b>.
 *
 * <h2>Why this is a test and not a comment</h2>
 * The mapping is a table of arbitrary art choices. A typo in one file name, a re-pick that forgets to
 * copy the new PNG, or a renamed effect type would all leave the book drawing the purple
 * missing-texture checker — and nobody can screenshot the screen here to notice. So the table is
 * asserted four ways: every type names a texture, every texture exists on the classpath, every icon is
 * the 64x64 PNG the drawing code assumes, and the two self-buffs really are one picture.
 *
 * <p>{@code tools/gen_skill_icons.py --check} and {@code tools/gen_buff_icon.py --check} prove something
 * stronger, that the committed PNGs are byte-for-byte resamples of the official files; this test only has
 * to stop the classpath and the code from drifting apart.
 *
 * <h2>The two self-buffs share one picture — by his rule</h2>
 * 「这种自buff提升，统一使用之前决定的自buff图标」. {@code attack_buff} and {@code stack} both draw the official
 * 自buff icon ({@code icon_skill_3015}), so their two committed files are <b>byte-identical</b>. The test
 * below asserts the bytes rather than the intention, because the failure mode — two files that were one
 * picture and then quietly became two — is invisible on any surface this project can inspect.
 *
 * <h2>The HUD sprite, which is the bug this suite did not have a test for</h2>
 * {@link #everyRegisteredEffectHasTheSpriteVanillaAsksFor()} is the regression test for the defect the
 * human reported twice: vanilla's effect HUD resolves a {@code MobEffect}'s sprite from
 * {@code assets/<namespace>/textures/mob_effect/<effect path>.png}, indexed by
 * {@code assets/minecraft/atlases/mob_effects.json} (a plain directory source over {@code mob_effect}).
 * The mod shipped {@code party_1..party_5.png} and nothing else, so {@code astrum:attack_buff} resolved to
 * Minecraft's missing-texture sprite: a purple/magenta checkerboard. The path is derived from the
 * <em>registry ids</em> here, not written out as a string, so a rename cannot silently reintroduce it.
 */
class SkillIconsTest {

    /** Every effect type must name its own file, or two hooks draw the same asset by accident. */
    @Test
    void everyEffectTypeNamesItsOwnIconFile() {
        Set<ResourceLocation> textures = new HashSet<>();
        for (EffectType type : EffectType.values()) {
            ResourceLocation texture = SkillIcons.textureFor(type);
            assertTrue(textures.add(texture), "two effect types share an icon file: " + type + " -> " + texture);
            assertEquals("astrum:textures/gui/skill/" + type.getSerializedName() + ".png",
                    texture.toString());
        }
        assertEquals(EffectType.values().length, textures.size(),
                "every effect type must be mapped to its own file name");
    }

    /** The hook's icon is chosen by its type: two hooks of one type share a picture, by design. */
    @Test
    void aHookUsesItsEffectTypesIcon() {
        for (EffectHook hook : oneHookOfEachType()) {
            assertEquals(SkillIcons.textureFor(hook.type()), SkillIcons.iconFor(hook),
                    "hook " + hook.type() + " must draw its type's icon");
        }
    }

    /**
     * Every effect type is drawn from an official {@code icon_skill_<n>}, so {@link SkillIcons#sourceId}
     * answers a number for all of them and only empty for {@code null}.
     *
     * <p>This used to be the other way round for {@code attack_buff}: it was drawn from a
     * {@code caravan_icon_buff_*} badge, had no number at all, and the test pinned that hole. The human's
     * rule replaced the badge with the official 自buff icon, so the hole is closed and the totality is what
     * is asserted now.
     */
    @Test
    void everyEffectTypeNamesAnOfficialIconNumber() {
        for (EffectType type : EffectType.values()) {
            assertTrue(SkillIcons.sourceId(type).isPresent(),
                    type + " must name the official icon_skill_<n> it is drawn from");
        }
        assertTrue(SkillIcons.sourceId(null).isEmpty(), "a null type has no number rather than throwing");
    }

    /** The two self-buff types are the shared set, and it is exactly those two. */
    @Test
    void theSharedSelfBuffSetIsExactlyTheTwoSelfBuffs() {
        Set<EffectType> shared = new HashSet<>();
        for (EffectType type : EffectType.values()) {
            if (SkillIcons.selfBuff(type)) {
                shared.add(type);
            }
        }
        assertEquals(Set.of(EffectType.ATTACK_BUFF, EffectType.STACK), shared,
                "a third shared picture has to be a deliberate edit, not a silent copy");
        assertFalse(SkillIcons.selfBuff(null), "a null type is not a self-buff");
        assertEquals(SkillIcons.sourceId(EffectType.STACK), SkillIcons.sourceId(EffectType.ATTACK_BUFF),
                "the two self-buffs must name the same official icon_skill_<n>");
    }

    /**
     * {@code attack_buff.png} and {@code stack.png} are the <b>same bytes</b>.
     *
     * <p>「统一使用之前决定的自buff图标」 is only true while the two files are one picture. Two files that
     * diverge after a re-pick would look like two categories to a player who has learned one.
     */
    @Test
    void theTwoSelfBuffsShipTheSamePicture() throws Exception {
        assertArrayEquals(bytesOf(SkillIcons.textureFor(EffectType.STACK)),
                bytesOf(SkillIcons.textureFor(EffectType.ATTACK_BUFF)),
                "the two self-buff rows must draw the same picture (tools/gen_buff_icon.py and "
                        + "tools/gen_skill_icons.py write it from icon_skill_3015)");
    }

    /** Every mapped texture is packaged, and 64x64 as {@link SkillIcons#TEXTURE_SIZE} claims. */
    @Test
    void everyMappedIconIsAShippedSixtyFourSquarePng() throws Exception {
        ImageIO.setUseCache(false);
        for (EffectType type : EffectType.values()) {
            String path = classpathOf(SkillIcons.textureFor(type));
            try (InputStream in = SkillIconsTest.class.getResourceAsStream(path)) {
                assertNotNull(in, "missing skill icon: " + path + " (run the tools/gen_*.py scripts)");
                var image = ImageIO.read(in);
                assertNotNull(image, "not a readable PNG: " + path);
                assertEquals(SkillIcons.TEXTURE_SIZE, image.getWidth(), path + " width");
                assertEquals(SkillIcons.TEXTURE_SIZE, image.getHeight(), path + " height");
            }
        }
    }

    /**
     * <b>Every effect this mod registers has the sprite vanilla's effect HUD will ask for.</b>
     *
     * <h2>The path is the rule, not a guess</h2>
     * {@code MobEffectTextureManager.get(holder)} asks the {@code mob_effects} atlas for
     * {@code <effect registry id>}, and that atlas is
     * {@code assets/minecraft/atlases/mob_effects.json} with one directory source over
     * {@code textures/mob_effect}. So the file the client samples is
     * {@code assets/<namespace>/textures/mob_effect/<path>.png}, which is exactly what
     * {@link #spriteOf} builds from the registry id. A missing file is not "no icon" — it is the purple
     * checkerboard the human photographed.
     *
     * <p>The five party effects were covered by {@code tools/gen_party_effect_textures.py}; the sixth
     * effect, {@code astrum:attack_buff}, was not, and that is the whole defect. Listing the ids from
     * {@link PartyEffectSlots#ids()} and {@link AttackBuffs#ID} rather than from a literal array is what
     * makes this a regression test rather than a second copy of the bug.
     */
    @Test
    void everyRegisteredEffectHasTheSpriteVanillaAsksFor() throws Exception {
        ImageIO.setUseCache(false);
        List<ResourceLocation> effects = new ArrayList<>(PartyEffectSlots.ids());
        effects.add(AttackBuffs.ID);

        for (ResourceLocation effect : effects) {
            ResourceLocation sprite = spriteOf(effect);
            assertEquals("astrum:textures/mob_effect/" + effect.getPath() + ".png", sprite.toString(),
                    "the sprite path is derived from the effect id, so a rename has to move the file too");
            try (InputStream in = SkillIconsTest.class.getResourceAsStream(classpathOf(sprite))) {
                assertNotNull(in, "astrum:" + effect.getPath() + " would draw the missing-texture "
                        + "checkerboard on the effect HUD: " + classpathOf(sprite));
                var image = ImageIO.read(in);
                assertNotNull(image, "not a readable PNG: " + sprite);
                int edge = EffectSprite.edgeOf(effect);
                assertEquals(edge, image.getWidth(), sprite + " width");
                assertEquals(edge, image.getHeight(), sprite + " height");
            }
        }
    }

    /**
     * The edge each shipped {@code mob_effect} file is committed at, with the reason for the two numbers.
     *
     * <p>There are two and they are both deliberate, so the table is written down rather than a single
     * constant: <b>18</b> is what vanilla's own {@code textures/mob_effect/*.png} are
     * ({@code strength.png}, {@code health_boost.png}), which is the size the {@code attack_buff} sprite is
     * derived at so it sits in the 18x18 slot like a vanilla effect; <b>32</b> is what
     * {@code tools/gen_party_effect_textures.py} draws the five party fallbacks at, because those are
     * <em>runes</em> with a 2-pixel outline and pips, and 18px turned the pips to mush. The blit samples the
     * whole texture whatever its size, so both edges work; the table exists so a silent re-export cannot
     * change one of them.
     */
    private static final class EffectSprite {

        /** Matches vanilla's own effect sprites, and {@code tools/gen_buff_icon.py}'s {@code HUD_SIZE}. */
        static final int VANILLA_EDGE = 18;

        /** {@code tools/gen_party_effect_textures.py}'s {@code SIZE}: the rune fallbacks. */
        static final int RUNE_EDGE = 32;

        private EffectSprite() {
        }

        /** {@return the committed edge for one effect's sprite} */
        static int edgeOf(ResourceLocation effectId) {
            return PartyEffectSlots.ids().contains(effectId) ? RUNE_EDGE : VANILLA_EDGE;
        }
    }

    /** {@return {@code astrum:textures/mob_effect/<effect path>.png}, the path the effect atlas samples} */
    private static ResourceLocation spriteOf(ResourceLocation effectId) {
        return ResourceLocation.fromNamespaceAndPath(effectId.getNamespace(),
                "textures/mob_effect/" + effectId.getPath() + ".png");
    }

    /** {@return the classpath path a texture location names} */
    private static String classpathOf(ResourceLocation texture) {
        return "/assets/" + texture.getNamespace() + "/" + texture.getPath();
    }

    /** {@return every byte of a packaged texture} */
    private static byte[] bytesOf(ResourceLocation texture) throws Exception {
        String path = classpathOf(texture);
        try (InputStream in = SkillIconsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing texture: " + path);
            return in.readAllBytes();
        }
    }

    /** The row icon fits inside a hook row, or it would overlap the row below. */
    @Test
    void theRowIconFitsARow() {
        assertTrue(SkillIcons.ROW_ICON <= BookLayout.HOOK_ROW_HEIGHT,
                "the icon (" + SkillIcons.ROW_ICON + "px) must fit a " + BookLayout.HOOK_ROW_HEIGHT
                        + "px row");
        assertTrue(SkillIcons.ROW_ICON < SkillIcons.TEXTURE_SIZE, "the 64px art is downscaled in the row");
    }

    /**
     * Hooks beyond the row budget become one "+N" row instead of disappearing.
     *
     * <p>Three rows: a character with three hooks shows all three; a fourth forces the overflow row,
     * which is why only two hooks are drawn then.
     */
    @Test
    void moreHooksThanRowsProduceAnOverflowRow() {
        SkillIcons.Rows none = SkillIcons.rows(0);
        assertEquals(0, none.shownHooks());
        assertFalse(none.overflowRow());
        assertEquals(0, none.rows());

        SkillIcons.Rows full = SkillIcons.rows(BookLayout.HOOK_ROWS);
        assertEquals(BookLayout.HOOK_ROWS, full.shownHooks());
        assertFalse(full.overflowRow());
        assertEquals(BookLayout.HOOK_ROWS, full.rows());

        SkillIcons.Rows more = SkillIcons.rows(BookLayout.HOOK_ROWS + 1);
        assertEquals(BookLayout.HOOK_ROWS - 1, more.shownHooks());
        assertEquals(2, more.overflow());
        assertTrue(more.overflowRow());
        assertEquals(BookLayout.HOOK_ROWS, more.rows(), "the overflow row is the last of the budget");

        SkillIcons.Rows many = SkillIcons.rows(12);
        assertEquals(2, many.shownHooks());
        assertEquals(10, many.overflow());
        assertEquals(BookLayout.HOOK_ROWS, many.rows());
    }

    @Test
    void aNegativeHookCountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SkillIcons.rows(-1));
    }

    /** One concrete hook per effect type, so the mapping is exercised through {@code iconFor}. */
    private static List<EffectHook> oneHookOfEachType() {
        Trigger trigger = Trigger.MELEE_HIT;
        TriggerParams none = TriggerParams.NONE;
        return List.of(
                new EffectHook.ApplyEffect(trigger, none, 1.0,
                        ResourceLocation.parse("minecraft:poison"), 100, 0),
                new EffectHook.ExtraDamage(trigger, none, 1.0, 3.0),
                new EffectHook.Lifesteal(trigger, none, 1.0, 0.2),
                new EffectHook.GainCharge(trigger, none, 1.0, 2.0),
                new EffectHook.AreaCharge(trigger, none, 1.0, 8.0, 3.0, true),
                new EffectHook.Splash(trigger, none, 1.0, 3.0, 4.0),
                new EffectHook.Stack(trigger, none, 1.0, ResourceLocation.parse("astrum:poison"), 5),
                new EffectHook.AttackBuff(trigger, none, 1.0, 1.5, 18.0, java.util.OptionalDouble.empty()),
                new EffectHook.Detonate(trigger, none, 1.0, 2.0, 3.0));
    }
}
