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
 * one of the official {@code icon_skill_<n>.png} files, copied into
 * {@code assets/astrum/textures/gui/skill/<effect>.png} by {@code tools/gen_skill_icons.py}. Nine
 * icons, not sixty-one: the other fifty-two stay in the official asset tree.
 *
 * <h2>Which icon, and why — the official category</h2>
 * The 61 official icons are filed in <b>families by their first digit</b>, and the category a picture
 * belongs to is what the review asks about. The first version of this table invented a reason per
 * picture instead ("a figure wrapped in sparkles = power gathering"), which read plausibly and was
 * wrong for several rows — an icon's family is a fact about the art set, a story about the shapes is
 * not. So every row below names the <b>official category</b> the human assigned, and the picture is
 * described only as far as it identifies the file.
 *
 * <pre>
 *   effect         icon   official category   picture
 *   extra_damage   1001   物理攻击            crossed blades with a spark
 *   lifesteal      2005   回血                a green plus-sign over a crescent
 *   splash         3013   破甲                a violet ring of blades bursting outward
 *   gain_charge    3014   充能                a plus-sign and a blue arc
 *   area_charge    2006   充能                a plus-sign and a blue arc
 *   stack          3015   自buff              a small figure on a podium, spotlight above
 *   detonate       1023   灼烧                an orange flame
 *   apply_effect   1022   中毒                a stoppered flask with bubbles
 *   attack_buff    3010   自buff              golden flame engulfing a figure
 * </pre>
 *
 * <p><b>The one reservation worth stating.</b> {@code gain_charge} (3014) and {@code area_charge}
 * (2006) are both 充能 — the official set has exactly two charge icons and the two effects are exactly
 * two charge effects (self and area), so the pairing is right, but at the 9x9 a hook row draws they are
 * near-identical pictures. They are distinguished by their row text and by which character's list they
 * appear in, never by shape. {@code attack_buff} is 3010 rather than 3015 because 3015 is already
 * {@code stack}: both are officially 自buff, and two rows of the same book must not share a picture.
 *
 * <p>{@code SkillIconsTest} pins the mapping as data (all types distinct, all textures on the
 * classpath, each 64x64, and this table equal to the generator's {@code ICONS}), so a re-pick has to
 * change the test too, and {@code --check} in the generator proves the files are byte-identical to
 * their official sources.
 */
@ApiStatus.Internal
public final class SkillIcons {

    /** Edge of the shipped icon files. Drawn smaller; see {@link #ROW_ICON}. */
    public static final int TEXTURE_SIZE = 64;

    /** How big the icon is in a hook row. A row is 9px tall, so the icon is a 9x9 square. */
    public static final int ROW_ICON = 9;

    private static final String DIRECTORY = "textures/gui/skill/";

    /**
     * The official {@code icon_skill_<n>.png} each effect type is drawn from.
     *
     * <p>The numbers live here, in code, rather than only in {@code tools/gen_skill_icons.py}: the
     * generator copies files and this table says <em>which</em> files, and the shipped texture is a
     * 64x64 resample whose name carries no number at all. Without this table "the book shows
     * {@code icon_skill_1001} for 附加伤害" would be a claim about a script nobody runs, and a re-pick
     * could change the generator while the code kept blitting the old picture.
     * {@code SkillIconsTest} asserts the table and the generator's {@code ICONS} dict are the same pairs.
     */
    private static final java.util.Map<EffectType, Integer> SOURCE_ID = java.util.Map.of(
            EffectType.EXTRA_DAMAGE, 1001,
            EffectType.LIFESTEAL, 2005,
            EffectType.SPLASH, 3013,
            EffectType.GAIN_CHARGE, 3014,
            EffectType.AREA_CHARGE, 2006,
            EffectType.STACK, 3015,
            EffectType.DETONATE, 1023,
            EffectType.APPLY_EFFECT, 1022,
            EffectType.ATTACK_BUFF, 3010);

    private SkillIcons() {
    }

    /**
     * {@return the official icon number an effect type is drawn from, e.g. {@code 1001}}
     *
     * @param type the hook's effect type
     */
    public static int sourceId(EffectType type) {
        Integer id = SOURCE_ID.get(type);
        if (id == null) {
            throw new IllegalStateException("no skill icon chosen for effect type " + type);
        }
        return id;
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
    public static Rows rows(int hooks, int hookRows) {
        if (hooks < 0) {
            throw new IllegalArgumentException("hook count must not be negative: " + hooks);
        }
        if (hookRows < 1) {
            // No room at all: show nothing rather than a row outside the panel.
            return new Rows(0, hooks, false);
        }
        if (hooks <= hookRows) {
            return new Rows(hooks, 0, false);
        }
        // One row goes to the "+N" line, so the visible hooks are one fewer than the row count.
        int shown = Math.max(0, hookRows - 1);
        return new Rows(shown, hooks - shown, shown < hooks);
    }

    /**
     * {@return how a character's hooks are shown, using the compact layout's row count}
     *
     * <p>Kept for callers that have no layout instance. Production code should prefer
     * {@link #rows(int, int)} with the live layout's {@code hookRows()}: the tier-aware layout can
     * report a different row count than the class constant, and a caller that assumes the constant
     * silently draws outside its panel.
     */
    public static Rows rows(int hooks) {
        return rows(hooks, BookLayout.HOOK_ROWS);
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