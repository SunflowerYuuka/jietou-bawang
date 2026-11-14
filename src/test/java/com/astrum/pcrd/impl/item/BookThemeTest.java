package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.client.NineSlice;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The theme table — three themes, their art, and the one rule that decides which theme a book opens.
 *
 * <h2>What is worth asserting about a "purely visual" enum</h2>
 * Five things, and each is a defect that ships with no error message:
 *
 * <ol>
 *   <li><b>Every theme names nine textures that exist.</b> A misspelled prefix does not throw; the client
 *       draws the missing-texture checkerboard. So the test <em>opens the PNG files from the classpath</em>

 *       and reads their sizes — the only offline proof that the name is real.</li>
 *   <li><b>The body tint equals the art.</b> The tint is painted under the nine pieces; if a centre tile is
 *       re-exported in another shade the panel becomes a slightly wrong colour with no symptom.</li>
 *   <li><b>Resolution is total.</b> A {@code null} id, another namespace, a near-miss path and a plain
 *       non-themed item all have to answer {@link BookTheme#DEFAULT} rather than throw: a book whose id
 *       this build does not know must still open.</li>
 *   <li><b>The item ids are exactly the ones the assets use.</b> The theme is derived from the id, so the
 *       id table and the model/lang/texture paths must be built from the same string.</li>
 *   <li><b>The two orderings agree.</b> The nine-slice helper indexes its rectangles by position and this
 *       enum names the pieces; a mismatch blits the left edge across the top.</li>
 * </ol>
 *
 * <p>Reading a PNG needs {@code javax.imageio}, which is in the JDK: no Minecraft client, no GL, and the
 * test runs in the same headless JVM as every other one.
 */
class BookThemeTest {

    /** The panel art is nine 64x64 tiles; a piece that is not that size is the wrong file. */
    private static final int PIECE_PIXELS = BookTheme.PIECE_PIXELS;

    /** The divider art is 256x16. */
    private static final int DIVIDER_WIDTH = BookTheme.DIVIDER_PIXELS_W;
    private static final int DIVIDER_HEIGHT = BookTheme.DIVIDER_PIXELS_H;

    // -----------------------------------------------------------------------------------------
    // The table itself
    // -----------------------------------------------------------------------------------------

    @Test
    void thereAreExactlyThreeThemes() {
        assertEquals(List.of(BookTheme.DEFAULT, BookTheme.SAKURA, BookTheme.FOREST),
                List.of(BookTheme.values()),
                "the brief's three themes: default blue, sakura pink, forest green");
    }

    /**
     * The theme ids and item suffixes are distinct and stable, and {@link BookTheme#DEFAULT} keeps the
     * empty suffix so {@code astrum:character_book} keeps the id it already has in players' worlds.
     */
    @Test
    void everyThemeHasItsOwnIdAndItemSuffix() {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> suffixes = new LinkedHashSet<>();
        Set<String> books = new LinkedHashSet<>();
        Set<String> cards = new LinkedHashSet<>();
        for (BookTheme theme : BookTheme.values()) {
            assertTrue(ids.add(theme.id()), "duplicate theme id " + theme.id());
            assertTrue(suffixes.add(theme.itemSuffix()), "duplicate item suffix "
                    + theme.itemSuffix());
            assertTrue(books.add(theme.bookItemId().toString()), "duplicate book id");
            assertEquals(BookTheme.BOOK_PATH + theme.itemSuffix(), theme.bookPath());
            assertEquals(theme.bookPath(), theme.bookItemId().getPath());
            assertEquals("astrum", theme.bookItemId().getNamespace());
        }
        assertEquals("", BookTheme.DEFAULT.itemSuffix(),
                "the blue book must not rename the item every existing player already owns");
        assertEquals("astrum:character_book", BookTheme.DEFAULT.bookItemId().toString());
        assertEquals("astrum:character_book_sakura", BookTheme.SAKURA.bookItemId().toString());
    }

    // -----------------------------------------------------------------------------------------
    // The art each theme names
    // -----------------------------------------------------------------------------------------

    /**
     * Every one of the nine pieces of every theme exists on the classpath and is a 64x64 image.
     *
     * <p>This is the assertion a "does the texture path exist" question really needs: the path is built
     * by string concatenation in {@link BookTheme#panel}, and a wrong prefix or suffix is a
     * missing-texture checkerboard in game with no log line at all.
     */
    @Test
    void everyThemeNamesNinePanelPiecesThatExist() {
        for (BookTheme theme : BookTheme.values()) {
            List<String> names = new ArrayList<>();
            for (BookTheme.Part part : BookTheme.Part.values()) {
                BufferedImage image = read(theme.panel(part));
                assertEquals(PIECE_PIXELS, image.getWidth(),
                        theme + " " + part + " must be " + PIECE_PIXELS + "px wide");
                assertEquals(PIECE_PIXELS, image.getHeight(),
                        theme + " " + part + " must be " + PIECE_PIXELS + "px tall");
                names.add(part.suffix());
            }
            assertEquals(List.of("tl", "t", "tr", "l", "c", "r", "bl", "b", "br"), names,
                    "the piece suffixes must be the nine-slice helper's order, " + theme);
            assertEquals(NineSlice.PIECES, names.size());
        }
    }

    @Test
    void everyThemeNamesADividerThatExists() {
        for (BookTheme theme : BookTheme.values()) {
            BufferedImage divider = read(theme.divider());
            assertEquals(DIVIDER_WIDTH, divider.getWidth(), theme + " divider width");
            assertEquals(DIVIDER_HEIGHT, divider.getHeight(), theme + " divider height");
            assertTrue(hasVisibleInk(divider), theme + " divider is fully transparent");
        }
    }

    /**
     * The three panels are <b>visually different</b>, measured rather than assumed: the average colour of
     * each theme's centre tile is far from the other two.
     *
     * <p>"Two books of different themes must open different-looking screens" is the requirement, and three
     * themes that all named the same texture would satisfy every existence check above. RGB distance is a
     * crude proxy for "looks different", but it is decisive for "is the same file".
     */
    @Test
    void theThreeThemesAreActuallyDifferentColours() {
        int[] defaultTint = averageOfCentreTile(BookTheme.DEFAULT);
        int[] sakuraTint = averageOfCentreTile(BookTheme.SAKURA);
        int[] forestTint = averageOfCentreTile(BookTheme.FOREST);
        assertTrue(rgbDistance(defaultTint, sakuraTint) > 40,
                "default vs sakura centre tiles are too close: " + rgb(defaultTint) + " / "
                        + rgb(sakuraTint));
        assertTrue(rgbDistance(defaultTint, forestTint) > 40,
                "default vs forest centre tiles are too close: " + rgb(defaultTint) + " / "
                        + rgb(forestTint));
        assertTrue(rgbDistance(sakuraTint, forestTint) > 40,
                "sakura vs forest centre tiles are too close: " + rgb(sakuraTint) + " / "
                        + rgb(forestTint));
    }

    /** Two themes must never resolve to the same panel texture: that is "they look the same". */
    @Test
    void noTwoThemesShareAPanelTexture() {
        Set<String> textures = new LinkedHashSet<>();
        for (BookTheme theme : BookTheme.values()) {
            for (BookTheme.Part part : BookTheme.Part.values()) {
                assertTrue(textures.add(theme.panel(part).toString()),
                        "two themes share " + theme.panel(part));
            }
            assertTrue(textures.add(theme.divider().toString()),
                    "two themes share a divider: " + theme.divider());
        }
        assertEquals(BookTheme.values().length * (BookTheme.Part.values().length + 1),
                textures.size());
    }

    // -----------------------------------------------------------------------------------------
    // The palette, derived from the art
    // -----------------------------------------------------------------------------------------

    /**
     * The body tint is the art's own body colour, not a number somebody typed.
     *
     * <p>Within 24 per RGB distance of the tile's mean: the tiles are a soft gradient (the default one
     * runs from {@code rgb(25,50,92)} to {@code rgb(18,38,74)}), so the tint cannot equal every pixel. What
     * is asserted is that it is in that tile's family and not, say, the sakura pink.
     */
    @Test
    void theBodyTintComesFromThatThemesOwnCentreTile() {
        for (BookTheme theme : BookTheme.values()) {
            int[] tile = averageOfCentreTile(theme);
            int[] tint = channels(theme.bodyTint());
            assertTrue(rgbDistance(tile, tint) <= 24,
                    theme + " body tint " + rgb(tint) + " is not the centre tile's colour "
                            + rgb(tile));
            assertEquals(0xFF, alpha(theme.bodyTint()),
                    theme + " body tint must be opaque, or the world shows through the panel");
        }
    }

    @Test
    void everyThemeHasATintAndTheyAreAllDifferent() {
        Set<Integer> tints = new LinkedHashSet<>();
        for (BookTheme theme : BookTheme.values()) {
            assertTrue(tints.add(theme.bodyTint()), "two themes share the body tint "
                    + Integer.toHexString(theme.bodyTint()));
        }
        assertEquals(BookTheme.values().length, tints.size());
    }

    /**
     * Every derived palette colour is opaque, and the accent/surface/selected ladder is actually ordered.
     *
     * <p>A 1-pixel accent line with alpha in it reads as a smudge over a textured panel, and a "selected"
     * cell that is not lighter than a normal one is a selection the player cannot see. Both are asserted
     * as numbers, since neither can be looked at here.
     */
    @Test
    void thePaletteLadderIsOpaqueAndOrdered() {
        for (BookTheme theme : BookTheme.values()) {
            int accent = theme.accentTint();
            int surface = theme.surfaceTint();
            int hover = theme.surfaceHoverTint();
            int selected = theme.surfaceSelectedTint();
            int filled = theme.slotFilledTint();
            for (int colour : new int[] {accent, surface, hover, selected, filled}) {
                assertEquals(0xFF, alpha(colour), theme + " produced a translucent colour");
            }
            assertTrue(luminance(accent) > luminance(theme.bodyTint()),
                    theme + ": the accent must be lighter than the body it is drawn on");
            assertTrue(luminance(surface) < luminance(theme.bodyTint()),
                    theme + ": a cell must be darker than the body, or it does not read as a well");
            assertTrue(luminance(hover) > luminance(surface), theme + ": hover must lift the cell");
            assertTrue(luminance(selected) > luminance(hover),
                    theme + ": selection must be the brightest of the three cell states");
            assertTrue(luminance(filled) > luminance(surface),
                    theme + ": a filled slot must differ from an empty one");
            assertTrue(luminance(surface) < 60,
                    theme + ": cells must stay dark enough for the light text drawn on them");
        }
    }

    /** {@link BookTheme#mix} is the palette's one kernel, so its endpoints and clamping are pinned. */
    @Test
    void mixReturnsItsEndpointsAndClampsTheAmount() {
        int from = 0xFF102030;
        int to = 0xFFFFFFFF;
        assertEquals(from, BookTheme.mix(from, to, 0.0F), "amount 0 is the source colour");
        assertEquals(0xFFFFFFFF, BookTheme.mix(from, to, 1.0F), "amount 1 is the target colour");
        assertEquals(BookTheme.mix(from, to, 1.0F), BookTheme.mix(from, to, 99.0F),
                "an amount above 1 clamps instead of overshooting into an invalid channel");
        assertEquals(from, BookTheme.mix(from, to, -3.0F), "a negative amount clamps to the source");
        assertEquals(0xFF808080, BookTheme.mix(0xFF000000, 0xFFFFFFFF, 0.5F),
                "halfway between black and white is mid grey (and still opaque)");
        assertEquals(0xFF, alpha(BookTheme.mix(0x00102030, 0x00FFFFFF, 0.5F)),
                "alpha is forced opaque even when both inputs are transparent");
    }

    // -----------------------------------------------------------------------------------------
    // Which theme a book opens — the rule the item asks about
    // -----------------------------------------------------------------------------------------

    /**
     * The whole resolution rule: an item id that is one of this mod's books or cards resolves to its
     * theme, and everything else resolves to the blue book.
     *
     * <p>This is the only logic the item runs, so it is asserted here rather than through an
     * {@code ItemStack}: building a stack needs the item registry, and the rule does not.
     */
    @Test
    void everyThemedItemIdResolvesToItsOwnTheme() {
        for (BookTheme theme : BookTheme.values()) {
            assertSame(theme, BookTheme.ofItemId(theme.bookItemId()),
                    theme + "'s book must open " + theme);
        }
    }

    /**
     * Resolution is total: an unknown, absent or foreign id is the blue book, never an exception.
     *
     * <p>Case-sensitivity and the namespace check are deliberate — an item id is data written by a
     * registry, and silently accepting near-misses would hide a typo in a recipe file.
     */
    @Test
    void anUnknownItemIdFallsBackToTheDefaultTheme() {
        assertSame(BookTheme.DEFAULT, BookTheme.ofItemId(null));
        assertSame(BookTheme.DEFAULT, BookTheme.ofItemId(InternalIds.id("character_book_neon")),
                "a theme from a future build");
        assertSame(BookTheme.DEFAULT, BookTheme.ofItemId(InternalIds.id("character_book_")),
                "no near-matches");
        assertSame(BookTheme.DEFAULT, BookTheme.ofItemId(InternalIds.id("rice_bowl")),
                "the dish is not a themed item");
        assertSame(BookTheme.DEFAULT, BookTheme.ofItemId(InternalIds.id("character_book_sakura2")),
                "a longer path is not a near-match either");
        assertSame(BookTheme.DEFAULT,
                BookTheme.ofItemId(ResourceLocation.withDefaultNamespace("character_book_sakura")),
                "minecraft:character_book_sakura is not this mod's item");
        assertSame(BookTheme.DEFAULT, BookTheme.ofItemId(ResourceLocation.parse("other:character_book_forest")));
    }

    /** A null theme is the blue book: the {@code null} door into the same rule, for the screen. */
    @Test
    void aMissingThemeIsTheDefaultOne() {
        assertSame(BookTheme.DEFAULT, BookTheme.resolve(null),
                "a book with no theme must still open, in blue");
        for (BookTheme theme : BookTheme.values()) {
            assertSame(theme, BookTheme.resolve(theme), "a themed book keeps its theme");
        }
        assertNotSame(BookTheme.SAKURA, BookTheme.resolve(null));
    }

    /**
     * Every theme has a name key, and no two share one — the tooltip that tells a player which book they
     * are holding is the only place the three are distinguishable in text.
     */
    @Test
    void everyThemeHasItsOwnNameKey() {
        Set<String> keys = new LinkedHashSet<>();
        for (BookTheme theme : BookTheme.values()) {
            assertTrue(keys.add(theme.nameKey()), "duplicate name key " + theme.nameKey());
            assertEquals("astrum.theme." + theme.id(), theme.nameKey());
        }
        assertEquals(keys, BookTheme.langKeys(),
                "langKeys() must be every theme's key, which LangParityTest then demands in both files");
        assertEquals(BookTheme.values().length, BookTheme.langKeys().size());
    }

    /**
     * A name change is not a silent re-theme: the lang key follows {@link BookTheme#id()}, and the id is
     * what the item path is built from. Renaming one without the other is the bug this pins.
     */
    @Test
    void theNameKeyFollowsTheThemeId() {
        for (BookTheme theme : BookTheme.values()) {
            assertTrue(theme.bookItemId().getPath().endsWith(theme.itemSuffix()),
                    theme + ": the book path must end with the theme suffix");
            assertTrue(theme.nameKey().endsWith("." + theme.id()),
                    theme + ": the lang key must end with the theme id");
            assertTrue(theme.bookItemId().getPath().startsWith(BookTheme.BOOK_PATH),
                    theme + ": every book id shares the base path, so /give is guessable");
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers: read the shipped art, which is the only offline source of truth about it
    // -----------------------------------------------------------------------------------------

    /**
     * {@return the image at {@code path}, which must be a classpath resource}
     *
     * <p>{@code path} is {@code textures/gui/...} in the {@code astrum} namespace, i.e. exactly the
     * resource path under {@code assets/astrum}; {@code ImageIO} reads it from the same classpath the game
     * does.
     */
    private static BufferedImage read(ResourceLocation path) {
        String resource = "/assets/" + path.getNamespace() + "/" + path.getPath();
        try (InputStream in = BookThemeTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing texture on the classpath: " + resource);
            BufferedImage image = ImageIO.read(in);
            assertNotNull(image, "not a readable image: " + resource);
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@return the mean RGB of a tile, as a crude but decisive "which file is this" fingerprint} */
    private static int[] averageOfCentreTile(BookTheme theme) {
        BufferedImage image = read(theme.panel(BookTheme.Part.C));
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 200) {
                    continue;   // transparent pixels have no colour to average
                }
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                n++;
            }
        }
        assertTrue(n > 0, "the centre tile of " + theme + " is entirely transparent");
        return new int[] {(int) (r / n), (int) (g / n), (int) (b / n)};
    }

    /** {@return whether any pixel is visible at all} */
    private static boolean hasVisibleInk(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) > 32) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int rgbDistance(int[] a, int[] b) {
        int dr = a[0] - b[0];
        int dg = a[1] - b[1];
        int db = a[2] - b[2];
        return (int) Math.round(Math.sqrt((double) (dr * dr + dg * dg + db * db)));
    }

    private static int[] channels(int argb) {
        return new int[] {red(argb), green(argb), blue(argb)};
    }

    private static double luminance(int argb) {
        return 0.2126 * red(argb) + 0.7152 * green(argb) + 0.0722 * blue(argb);
    }

    private static String rgb(int[] colour) {
        return "rgb(" + colour[0] + "," + colour[1] + "," + colour[2] + ")";
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }
}
