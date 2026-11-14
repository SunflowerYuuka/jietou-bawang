package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The three visual themes a 角色册 exists in, and the art each one is drawn from.
 *
 * <h2>The three themes, and the item that carries each</h2>
 * <table border="1">
 *   <caption>theme to book item to art</caption>
 *   <tr><th>theme</th><th>book item</th><th>panel pieces</th><th>divider</th><th>body tint</th></tr>
 *   <tr><td>{@link #DEFAULT}</td><td>{@code astrum:character_book}</td>
 *       <td>{@code book_panel_*}</td><td>{@code divider}</td><td>{@code #1D3965}</td></tr>
 *   <tr><td>{@link #SAKURA}</td><td>{@code astrum:character_book_sakura}</td>
 *       <td>{@code book_panel_sakura_*}</td><td>{@code sakura_divider}</td><td>{@code #471335}</td></tr>
 *   <tr><td>{@link #FOREST}</td><td>{@code astrum:character_book_forest}</td>
 *       <td>{@code book_panel_forest_*}</td><td>{@code forest_divider}</td><td>{@code #144229}</td></tr>
 * </table>
 *
 * <h2>Three <em>books</em>, one <em>card</em></h2>
 * A theme is a property of the 角色册 — the screen — and of nothing else. The 角色卡 is a single item,
 * {@code astrum:character_card}, with no theme at all: it is a ticket that enrols the character named by
 * its {@code astrum:character_id} component, and the human's rule is 「角色卡一个角色只用做一个…一个角色卡
 * 右键使用后应该是放进三个角色册」 — one card per character, and <em>any</em> of the three books can show
 * that character afterwards. A per-theme card would have implied the opposite (that a sakura card belongs
 * to the sakura book), and it needed three items, three recipes, three models and three lang keys to say
 * something untrue. {@code CHARACTER_CARD_SAKURA} / {@code CHARACTER_CARD_FOREST} are therefore gone, and
 * this enum carries no card path or card id.
 *
 * <h2>Why three book items, and no theme data component — the decision, and its cost</h2>
 * The brief allowed either "one {@code astrum:book_theme} component type" or "three separate items". This
 * is <b>three items, with the theme derived from the item id</b> ({@link #ofItemId}), because a theme is
 * not one fact but four:
 *
 * <ol>
 *   <li>the item <b>id</b> — what a recipe result names;</li>
 *   <li>the <b>model file</b> — {@code models/item/character_book_sakura.json} is chosen by the id, and
 *       no component can change it without a custom item renderer;</li>
 *   <li>the <b>lang key</b> — {@code item.astrum.character_book_sakura}, and a single item whose name
 *       varied with a component would need a name component written on every stack;</li>
 *   <li>the <b>panel art</b> — which is what the screen asks about.</li>
 * </ol>
 *
 * <p>Three of those four are already per-item facts that Minecraft fixes by id. Making the fourth a
 * component would mean the icon and the panel could <em>disagree</em>: a datapack writing
 * {@code book_theme: sakura} on a plain book would open a pink panel while the inventory still showed the
 * blue book. So the component is not added, and the id is the single source of truth for all four. The
 * cost is stated plainly: <b>a datapack cannot re-theme a book without registering an item</b>. That is
 * acceptable because the theme is cosmetic and the ids are cheap to add — and it is the only shape in
 * which "the item they right-clicked decides the theme" cannot contradict itself.
 *
 * <h2>Why it is not a client class</h2>
 * A theme is read on both sides: the item reads it to pass to the screen, and a dedicated server holds the
 * items in players' inventories. This enum therefore stores identifiers, integers and a name key — no
 * {@code net.minecraft.client} type — and {@code impl/client/ThemedPanel} turns those identifiers into
 * blits. That split is what lets {@code BookThemeTest} assert the whole table without a client, and what
 * keeps {@code guard}'s 端隔离 rule satisfied.
 *
 * <h2>The body tint is not a panel tint</h2>
 * The nine-slice art is <b>already coloured</b>: every piece of every theme is its own hue and the centre
 * tiles are opaque to alpha 252/255. Multiplying a tint over them would darken the ornament into mud and
 * hide exactly the difference the three themes exist for, so <b>the panel is not tinted</b>.
 *
 * <p>{@link #bodyTint()} is a different thing and it <em>is</em> used: the flat colour painted under the
 * nine pieces, sampled from the centre pixel of that theme's own centre tile. It exists for two reasons:
 * <ul>
 *   <li>the centre piece is stretched to whatever body size the panel has, and on a degenerate target (a
 *       window so small that the slice clamps to half the box) the centre draws nothing — without a
 *       filled body the panel would be a frame with the world showing through the middle;</li>
 *   <li>the centre tiles are alpha 252, i.e. 1% transparent; over a bright sky that is a visible haze.
 *       An opaque fill under it costs one {@code fill} call and removes the question.</li>
 * </ul>
 * The values are read from the art, not invented: {@code DEFAULT} {@code rgb(29,57,101)}, {@code SAKURA}
 * {@code rgb(71,19,53)}, {@code FOREST} {@code rgb(20,66,41)}. {@code BookThemeTest} re-reads those three
 * PNG files from the classpath and asserts they still match, so a re-exported texture that drifts fails a
 * test instead of making a panel slightly the wrong shade.
 */
@ApiStatus.Internal
public enum BookTheme {

    /** Blue: the theme the book shipped with, and what an item this enum does not recognise resolves to. */
    DEFAULT("default", "", "book_panel", "divider", 0xFF1D3965),

    /** Pink (anime): cherry blossom frame and divider. */
    SAKURA("sakura", "_sakura", "book_panel_sakura", "sakura_divider", 0xFF471335),

    /** Green (anime): forest frame and divider. */
    FOREST("forest", "_forest", "book_panel_forest", "forest_divider", 0xFF144229);

    /** Directory every panel piece lives in, relative to the {@code astrum} namespace. */
    private static final String GUI_DIR = "textures/gui/";

    /** The item path every book starts from, before the theme's suffix. */
    public static final String BOOK_PATH = "character_book";

    /**
     * The mod-relative path of the one character card.
     *
     * <p>No suffix and no per-theme variant: see the class javadoc. Kept here because this enum is
     * already the one place that knows what the mod's two content families are called, and the creative
     * tab's family ranking keys off it.
     */
    public static final String CARD_PATH = "character_card";

    /**
     * Source size of one panel piece, in pixels: the art is nine 64x64 tiles.
     *
     * <p>Spelled here rather than passed by the screen because it is a fact about the art, and
     * {@code BookThemeTest} asserts every piece file really is this size. A {@code blit} that sampled a
     * different source rectangle would not fail; it would stretch the wrong part of the tile.
     */
    public static final int PIECE_PIXELS = 64;

    /** Source width of a divider: 256x16, of which the middle ~48 columns carry the ornament. */
    public static final int DIVIDER_PIXELS_W = 256;

    /** Source height of a divider. */
    public static final int DIVIDER_PIXELS_H = 16;

    /** How far {@link #accentTint()} mixes the body colour towards white, {@code 0}–{@code 1}. */
    public static final float LIGHTEN = 0.55F;

    /** How far {@link #surfaceTint()} mixes the body colour towards black, {@code 0}–{@code 1}. */
    public static final float SURFACE_DARKEN = 0.42F;

    /**
     * {@return every language key this enum asks for}, for {@code LangParityTest}
     *
     * <p>A method rather than a cached static field: the set is derived from the enum, it is three strings,
     * and a static container field is one more thing the project's own {@code guard} has to warn about and
     * a reader has to check is immutable. Rebuilding it is cheaper than that conversation.
     */
    public static Set<String> langKeys() {
        return nameKeys();
    }

    private final String id;
    private final String itemSuffix;
    private final String panelPrefix;
    private final String dividerFile;
    private final int bodyTint;

    BookTheme(String id, String itemSuffix, String panelPrefix, String dividerFile, int bodyTint) {
        this.id = id;
        this.itemSuffix = itemSuffix;
        this.panelPrefix = panelPrefix;
        this.dividerFile = dividerFile;
        this.bodyTint = bodyTint;
    }

    /**
     * One of the nine panel pieces.
     *
     * <p>Declared as an enum rather than derived from a string list in the screen so a missing or
     * misspelled piece cannot compile, and so the texture name is built in one place. The nine-slice
     * helper indexes its destination rectangles in exactly this order; {@code BookThemeTest} asserts the
     * two orders agree so they cannot drift apart.
     */
    public enum Part {
        /** Top-left corner. */
        TL("tl"),
        /** Top edge, stretched horizontally. */
        T("t"),
        /** Top-right corner. */
        TR("tr"),
        /** Left edge, stretched vertically. */
        L("l"),
        /** Centre, stretched both ways. */
        C("c"),
        /** Right edge, stretched vertically. */
        R("r"),
        /** Bottom-left corner. */
        BL("bl"),
        /** Bottom edge, stretched horizontally. */
        B("b"),
        /** Bottom-right corner. */
        BR("br");

        private final String suffix;

        Part(String suffix) {
            this.suffix = suffix;
        }

        /** {@return the {@code _tl}-style suffix this piece's file name ends with} */
        public String suffix() {
            return suffix;
        }
    }

    /** {@return the name used in lang keys and logs, e.g. {@code sakura}} */
    public String id() {
        return id;
    }

    /**
     * {@return the suffix that makes an item id themed, e.g. {@code _sakura}}
     *
     * <p>Empty for {@link #DEFAULT}, so the original {@code astrum:character_book} keeps its id — an item
     * id that already exists in players' worlds must not move because a feature was added around it.
     */
    public String itemSuffix() {
        return itemSuffix;
    }

    /** {@return the mod-relative item path of this theme's book, e.g. {@code character_book_sakura}} */
    public String bookPath() {
        return BOOK_PATH + itemSuffix;
    }

    /** {@return the item id of this theme's book, e.g. {@code astrum:character_book_sakura}} */
    public ResourceLocation bookItemId() {
        return InternalIds.id(bookPath());
    }

    /**
     * {@return the texture of one panel piece of this theme}
     *
     * @param part which of the nine
     */
    public ResourceLocation panel(Part part) {
        return InternalIds.id(GUI_DIR + panelPrefix + "_" + part.suffix() + ".png");
    }

    /** {@return the ornament drawn between the title and the body of this theme} */
    public ResourceLocation divider() {
        return InternalIds.id(GUI_DIR + dividerFile + ".png");
    }

    /**
     * {@return the opaque ARGB colour painted under the nine pieces}
     *
     * <p>Sampled from this theme's centre tile — see the class javadoc for why it is a body fill rather
     * than a tint over the art.
     */
    public int bodyTint() {
        return bodyTint;
    }

    /**
     * {@return a lighter shade of {@link #bodyTint()}, for the thin lines drawn on top of the panel}
     *
     * <p>The party-slot hover frame and the feeding entry point's top rule used to be one hard-coded
     * blue ({@code #2E3A58}), which is the wrong hue on a pink or green panel. Deriving them from the
     * theme's own body colour means the three themes cannot disagree about their accent, and a
     * re-exported panel moves the accent with it — no fourth palette to keep in step.
     *
     * <p>{@link #LIGHTEN} is a mix towards white in sRGB, which is what the art itself does: the ornament
     * is a lighter version of the body rather than a saturated complement. Alpha is forced to opaque
     * because these are 1-pixel lines and a translucent one over a textured panel reads as a smudge.
     */
    public int accentTint() {
        return mix(bodyTint, 0xFFFFFFFF, LIGHTEN);
    }

    /**
     * {@return the colour of a surface cut into the panel — roster cells, party slots, bar backs}
     *
     * <p>Darker than the body, so a cell reads as a well rather than a raised plate, and in the theme's
     * own hue so it does not look pasted on. {@code 0.42} was checked against the three centre tiles: it
     * puts every cell at 12–16% luminance, dark enough for the light text on all three panels.
     */
    public int surfaceTint() {
        return mix(bodyTint, 0xFF000000, SURFACE_DARKEN);
    }

    /** {@return a slightly lifted {@link #surfaceTint()}, for a hovered roster cell} */
    public int surfaceHoverTint() {
        return mix(surfaceTint(), 0xFFFFFFFF, 0.06F);
    }

    /** {@return the colour of a selected roster cell: the surface lifted again, clearly still a cell} */
    public int surfaceSelectedTint() {
        return mix(surfaceTint(), 0xFFFFFFFF, 0.16F);
    }

    /** {@return a filled party slot: the surface lifted a little, distinct from an empty one} */
    public int slotFilledTint() {
        return mix(surfaceTint(), 0xFFFFFFFF, 0.10F);
    }

    /** {@return the translation key naming this theme, e.g. {@code astrum.theme.sakura}} */
    public String nameKey() {
        return "astrum.theme." + id;
    }

    /**
     * {@return the theme of an item id, or {@link #DEFAULT} when the id is not a themed book}
     *
     * <p><b>The theme rule</b>, written once: a themed <em>book's</em> id decides its theme, and every
     * other id — including the one {@code astrum:character_card}, which carries no theme — is the blue
     * book. The book item, the card tooltip and the asset test all go through here, so "which panel does
     * book X open" has exactly one answer and cannot be contradicted by a second field somewhere else.
     * {@code minecraft:character_book_sakura} is a different item in another namespace and therefore
     * resolves to {@link #DEFAULT}.
     *
     * <p>Total on purpose: the alternative — throwing, or returning {@code null} for the caller to forget
     * — trades a wrong colour for a lost item, which is the wrong way round for a purely visual value.
     *
     * @param itemId the item's registry id, possibly {@code null} for an unregistered stack
     */
    public static BookTheme ofItemId(@Nullable ResourceLocation itemId) {
        if (itemId == null || !Astrum.MOD_ID.equals(itemId.getNamespace())) {
            return DEFAULT;   // minecraft:character_book_sakura is a different item, not our theme
        }
        String path = itemId.getPath();
        for (BookTheme theme : values()) {
            if (theme.bookPath().equals(path)) {
                return theme;
            }
        }
        return DEFAULT;
    }

    /**
     * {@return {@code theme}, or {@link #DEFAULT} when it is {@code null}}
     *
     * <p>The null-shaped entry into the same rule, for callers that already hold a theme (the screen's
     * constructor, a probe): one line, one test, no branching at the call sites.
     *
     * @param theme the theme, possibly {@code null}
     */
    public static BookTheme resolve(@Nullable BookTheme theme) {
        return theme == null ? DEFAULT : theme;
    }

    /**
     * {@return {@code argb} mixed {@code amount} of the way towards {@code towards}, alpha {@code 0xFF}}
     *
     * <p>One mixing function for every derived colour above, so "a lighter accent" and "a darker well"
     * cannot drift apart in rounding. It is the small, testable kernel of the palette: {@code amount 0}
     * returns the colour, {@code amount 1} returns the target, and the result is always opaque.
     *
     * @param argb    the source colour
     * @param towards the colour to move towards
     * @param amount  {@code 0}–{@code 1}, clamped
     */
    public static int mix(int argb, int towards, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int r = mixChannel((argb >> 16) & 0xFF, (towards >> 16) & 0xFF, t);
        int g = mixChannel((argb >> 8) & 0xFF, (towards >> 8) & 0xFF, t);
        int b = mixChannel(argb & 0xFF, towards & 0xFF, t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mixChannel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    private static Set<String> nameKeys() {
        // A LinkedHashSet, not Set.of: a duplicated key would make Set.of throw inside a static
        // initialiser, i.e. take the item table down at class-load time. A duplicate is a bug, but it
        // must not be a crash in a player's game.
        Set<String> keys = new LinkedHashSet<>();
        for (BookTheme theme : values()) {
            keys.add(theme.nameKey());
        }
        return Set.copyOf(keys);
    }
}
