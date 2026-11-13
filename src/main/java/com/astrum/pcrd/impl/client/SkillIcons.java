package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Which official skill icon a hook row shows, and how many rows the book has room for.
 *
 * <h2>The mapping is per effect type, not per hook</h2>
 * A character's hooks differ in numbers, not in kind: "combo 3 → splash 3/4" and "combo 3 → area
 * charge 8/3" are the same picture plus different words. So the icon is chosen by {@link EffectType},
 * one of the eight official {@code icon_skill_<n>.png} files, copied into
 * {@code assets/astrum/textures/gui/skill/<effect>.png} by {@code tools/gen_skill_icons.py}. Eight
 * icons, not sixty-one: the other fifty-three stay in the official asset tree.
 *
 * <h2>Which icon, and why</h2>
 * The pick was made by looking at a labelled contact sheet of all 61 icons, so the reasons are visual:
 *
 * <pre>
 *   extra_damage  3002  crossed slashes with sparks  - a second hit, not the first
 *   lifesteal     3001  heart with a fist            - taking something back
 *   splash        3006  an explosion's rays          - damage radiating to neighbours
 *   gain_charge   3015  figure wrapped in sparkles   - power gathering into the caster
 *   area_charge   3013  a radiant orb                - power radiating to allies
 *   stack         2016  layered green pillars        - layers piling up
 *   detonate      1023  a fireball                   - the accumulated thing going off
 *   apply_effect  2022  a bubbling flask             - the canonical "apply a debuff" picture
 * </pre>
 *
 * <p>{@code SkillIconsTest} pins the mapping as data (all eight types distinct, all eight textures on
 * the classpath, each 64x64) so a re-pick has to change the test too, and {@code --check} in the
 * generator can prove the files are byte-identical to their official sources.
 */
@ApiStatus.Internal
public final class SkillIcons {

    /** Edge of the shipped icon files. Drawn smaller; see {@link #ROW_ICON}. */
    public static final int TEXTURE_SIZE = 64;

    /** How big the icon is in a hook row. A row is 9px tall, so the icon is a 9x9 square. */
    public static final int ROW_ICON = 9;

    private static final String DIRECTORY = "textures/gui/skill/";

    private SkillIcons() {
    }

    /**
     * {@return the texture of one effect type}
     *
     * @param type the hook's effect type
     */
    public static ResourceLocation textureFor(EffectType type) {
        return ResourceLocation.fromNamespaceAndPath("astrum",
                DIRECTORY + type.getSerializedName().toLowerCase(Locale.ROOT) + ".png");
    }

    /** {@return the icon to draw for {@code hook}, chosen by its effect type} */
    public static ResourceLocation iconFor(EffectHook hook) {
        return textureFor(hook.type());
    }

    /**
     * {@return how many hook rows to draw and how many hooks do not fit}
     *
     * <p>The growth column has room for {@link BookLayout#HOOK_ROWS} rows. A character with more hooks
     * than that shows the first rows and a "+N" line, rather than silently dropping the rest: an
     * invisible hook is a rule the player cannot see, and this project treats that as a defect.
     *
     * @param hooks how many hooks the character defines
     */
    public static Rows rows(int hooks) {
        if (hooks < 0) {
            throw new IllegalArgumentException("hook count must not be negative: " + hooks);
        }
        if (hooks <= BookLayout.HOOK_ROWS) {
            return new Rows(hooks, 0, false);
        }
        // One row goes to the "+N" line, so the visible hooks are one fewer than the row count.
        int shown = BookLayout.HOOK_ROWS - 1;
        return new Rows(shown, hooks - shown, true);
    }

    /**
     * How a character's hooks are shown in the book.
     *
     * @param shownHooks  hooks drawn as icon + text rows
     * @param overflow    hooks that did not fit; {@code 0} when everything fits
     * @param overflowRow whether one extra row is needed for the "+N" line
     */
    public record Rows(int shownHooks, int overflow, boolean overflowRow) {

        /** {@return the rows this uses, including the overflow line} */
        public int rows() {
            return shownHooks + (overflowRow ? 1 : 0);
        }
    }
}