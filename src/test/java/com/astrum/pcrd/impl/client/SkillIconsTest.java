package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import java.io.InputStream;
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
 * The hook -> icon mapping, and the fact that the icons it names are really shipped.
 *
 * <h2>Why this is a test and not a comment</h2>
 * The mapping is a table of eight arbitrary art choices. A typo in one file name, a re-pick that
 * forgets to copy the new PNG, or a renamed effect type would all leave the book drawing the purple
 * missing-texture checker — and nobody can screenshot the screen here to notice. So the table is
 * asserted three ways: every type has its own icon, every icon exists on the classpath, and every
 * icon is the 64x64 PNG the drawing code assumes.
 *
 * <p>{@code tools/gen_skill_icons.py --check} proves something stronger, that the committed PNGs are
 * byte-for-byte the official {@code icon_skill_<n>.png} resamples; this test only has to stop the
 * classpath and the code from drifting apart.
 */
class SkillIconsTest {

    /** Every effect type must have its own picture, or two hooks look identical. */
    @Test
    void everyEffectTypeHasItsOwnIcon() {
        Set<ResourceLocation> textures = new HashSet<>();
        for (EffectType type : EffectType.values()) {
            ResourceLocation texture = SkillIcons.textureFor(type);
            assertTrue(textures.add(texture), "two effect types share an icon: " + type + " -> " + texture);
            assertEquals("astrum:textures/gui/skill/" + type.getSerializedName() + ".png",
                    texture.toString());
        }
        assertEquals(EffectType.values().length, textures.size(),
                "every effect type must be mapped to its own icon");
    }

    /** The hook's icon is chosen by its type: two hooks of one type share a picture, by design. */
    @Test
    void aHookUsesItsEffectTypesIcon() {
        for (EffectHook hook : oneHookOfEachType()) {
            assertEquals(SkillIcons.textureFor(hook.type()), SkillIcons.iconFor(hook),
                    "hook " + hook.type() + " must draw its type's icon");
        }
    }

    /** Every mapped texture is packaged, and 64x64 as {@link SkillIcons#TEXTURE_SIZE} claims. */
    @Test
    void everyMappedIconIsAShippedSixtyFourSquarePng() throws Exception {
        ImageIO.setUseCache(false);
        for (EffectType type : EffectType.values()) {
            ResourceLocation texture = SkillIcons.textureFor(type);
            String path = "/assets/" + texture.getNamespace() + "/" + texture.getPath();
            try (InputStream in = SkillIconsTest.class.getResourceAsStream(path)) {
                assertNotNull(in, "missing skill icon: " + path + " (run tools/gen_skill_icons.py)");
                var image = ImageIO.read(in);
                assertNotNull(image, "not a readable PNG: " + path);
                assertEquals(SkillIcons.TEXTURE_SIZE, image.getWidth(), path + " width");
                assertEquals(SkillIcons.TEXTURE_SIZE, image.getHeight(), path + " height");
            }
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
                new EffectHook.Detonate(trigger, none, 1.0, 2.0, 3.0));
    }
}