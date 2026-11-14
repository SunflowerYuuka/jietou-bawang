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
 * <h2>Every effect has its own picture — and that is now true literally</h2>
 * Nine effect types, nine distinct official icons, nine distinct files. The earlier revision asserted the
 * <em>opposite</em> for {@code attack_buff} and {@code stack}: that they were byte-identical, on a
 * "every self-buff shares one picture" rule. The rule was misfiled — {@code stack} is a per-target layer
 * counter that lands on the <em>victim</em> (spec §4.4), not a self-buff — so the assertion is now
 * inverted: the two files must <b>differ</b>. {@link #attackBuffIsIconSkill3010()} pins the number the
 * human named (「我要的是3010/2003/1003」), because "different bytes" alone would also accept the wrong
 * picture.
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

    /** The only self-buff is {@code attack_buff}: {@code stack} is a victim-side counter, not a self-buff. */
    @Test
    void theSelfBuffSetIsExactlyAttackBuff() {
        Set<EffectType> selfBuffs = new HashSet<>();
        for (EffectType type : EffectType.values()) {
            if (SkillIcons.selfBuff(type)) {
                selfBuffs.add(type);
            }
        }
        assertEquals(Set.of(EffectType.ATTACK_BUFF), selfBuffs,
                "a new self-buff has to be a deliberate edit, not a silent addition");
        assertFalse(SkillIcons.selfBuff(EffectType.STACK),
                "stack is a per-target layer counter on the victim (spec §4.4), not a self-buff");
        assertFalse(SkillIcons.selfBuff(null), "a null type is not a self-buff");
    }

    /**
     * {@code attack_buff} is drawn from the official {@code icon_skill_3010}, by name.
     *
     * <p>「你用的3015/2012/1012，我要的是3010/2003/1003。」 — the human picked the number. 3010 is the figure
     * engulfed in golden flames in a gold frame; 3015 (a figure on a podium under a spotlight) is what
     * shipped and is wrong for this effect. Pinning the number rather than only "different from stack"
     * is what stops a future re-pick from quietly choosing some other picture that also differs.
     */
    @Test
    void attackBuffIsIconSkill3010() {
        assertEquals(3010, SkillIcons.sourceId(EffectType.ATTACK_BUFF).orElse(-1),
                "attack_buff must be drawn from icon_skill_3010");
    }

    /** {@code stack} keeps 3015 — the per-target counter's picture is unchanged. */
    @Test
    void stackKeepsIconSkill3015() {
        assertEquals(3015, SkillIcons.sourceId(EffectType.STACK).orElse(-1),
                "stack keeps its own picture; nothing overrode it and 姬塔 never uses it");
    }

    /**
     * <b>{@code attack_buff.png} and {@code stack.png} are different bytes.</b>
     *
     * <p>The inverse of what this test asserted before. It used to require byte-identical files, on the
     * "self-buffs share one picture" rule; that rule was misapplied (see the class javadoc), and the two
     * effects are now two official pictures. Both halves are asserted because either one alone is
     * insufficient: "different" without a pinned number accepts the wrong art, and a pinned number without
     * "different" would not notice the files still being copies.
     */
    @Test
    void attackBuffAndStackShipDifferentPictures() throws Exception {
        assertFalse(java.util.Arrays.equals(bytesOf(SkillIcons.textureFor(EffectType.STACK)),
                        bytesOf(SkillIcons.textureFor(EffectType.ATTACK_BUFF))),
                "attack_buff (icon_skill_3010) and stack (icon_skill_3015) must be different pictures: "
                        + "tools/gen_buff_icon.py and tools/gen_skill_icons.py write them from different "
                        + "official sources");
    }

    /**
     * No two effect types share a picture — all nine files differ.
     *
     * <p>This is the general form of the pair above, and it is the property the original
     * {@code everyEffectTypeNamesItsOwnIconFile} premise was reaching for. It is asserted across every
     * pair rather than only the one that was wrong, so a future re-pick that collapses two types onto one
     * number fails here even if the pinned numbers are updated.
     */
    @Test
    void everyEffectTypePaintsFromItsOwnPicture() throws Exception {
        List<EffectType> types = List.of(EffectType.values());
        for (int i = 0; i < types.size(); i++) {
            for (int j = i + 1; j < types.size(); j++) {
                assertFalse(java.util.Arrays.equals(bytesOf(SkillIcons.textureFor(types.get(i))),
                                bytesOf(SkillIcons.textureFor(types.get(j)))),
                        types.get(i) + " and " + types.get(j) + " ship identical art; every effect type "
                                + "must have its own picture (" + SkillIcons.sourceId(types.get(i)) + " vs "
                                + SkillIcons.sourceId(types.get(j)) + ")");
            }
        }
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
     * The edge each shipped {@code mob_effect} file is committed at, with the reason for the one number.
     *
     * <p><b>64</b> for {@code attack_buff}, and {@code 18} is deliberately gone. The old value matched
     * vanilla's own {@code textures/mob_effect/*.png} on the theory that the HUD needs an 18x18 file; that
     * theory was wrong in the useful direction. {@code MobEffectTextureManager} stitches through
     * {@code SpriteLoader}, which builds a {@code Stitcher} from {@code maxSupportedTextureSize} and
     * imposes <b>no per-sprite size</b> — any sprite size is packed as-is. The HUD then blits it at a fixed
     * 18x18 ({@code Gui.renderEffects} → {@code Graphics.blit(..., 0, 18, 18, sprite)}), so a bigger file
     * does not make the icon bigger; it gives the sampler more pixels, which is the sharpness the human
     * asked for (「分辨率有点低了」). {@code 18} was never enforced, so it was not the ceiling.
     *
     * <p><b>32</b> for the five party fallbacks, which {@code tools/gen_party_effect_textures.py} draws as
     * <em>runes</em> with a 2-pixel outline and pips; 18px turned the pips to mush.
     */
    private static final class EffectSprite {

        /** {@code tools/gen_buff_icon.py}'s {@code HUD_SIZE}: the official art survives the atlas. */
        static final int ATTACK_BUFF_EDGE = 64;

        /** {@code tools/gen_party_effect_textures.py}'s {@code SIZE}: the rune fallbacks. */
        static final int RUNE_EDGE = 32;

        private EffectSprite() {
        }

        /** {@return the committed edge for one effect's sprite} */
        static int edgeOf(ResourceLocation effectId) {
            return PartyEffectSlots.ids().contains(effectId) ? RUNE_EDGE : ATTACK_BUFF_EDGE;
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
