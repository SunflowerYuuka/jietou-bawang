package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where everything on the 角色册 screen is, as pure arithmetic.
 *
 * <h2>Why the screen's geometry is its own class</h2>
 * This project cannot take screenshots — every visual claim has to become an assertion or stay
 * unverified. "The grid cells do not overlap", "clicking a cell's centre selects that same cell",
 * "the growth bars sit inside the panel on a 320x240 screen and on a 1920x1080 one" are all
 * <em>arithmetic</em>, so they live here, where {@code BookLayoutTest} can assert them without a
 * client. The screen keeps only drawing and input dispatch: it asks this class for a box and puts
 * pixels in it.
 *
 * <h2>Layout in one picture</h2>
 * <pre>
 *   +----------------------------------------- panel (centred) ---+
 *   | title                                                      |
 *   | ~~~~~~~~~~~~ ornament (theme divider) ~~~~~~~~~~~~~~~~~~~  |
 *   | +--- roster grid (2 x 3 cells) ---+  party heading         |
 *   | | cell cell                        |  [1][2][3][4][5]      |
 *   | | cell cell                        |  growth heading       |
 *   | | cell cell                        |  level / exp bar      |
 *   | +----------------------------------+  affection / bar       |
 *   |                                       feed entry           |
 *   |                                       skills heading       |
 *   |                                       [icon] trigger · effect|
 *   |                                       [icon] trigger · effect|
 *   | refusal line (only while a refusal is fresh)               |
 *   +------------------------------------------------------------+
 * </pre>
 *
 * <p>Everything is derived from {@code screenWidth}/{@code screenHeight} and one of the two
 * {@link Metrics} tiers below; there are no absolute pixel coordinates anywhere, because GUI scale is
 * the player's choice ({@code 1}–{@code 4}) and a hard-coded panel is a panel that is off-screen for
 * somebody. A screen smaller than the panel (which can happen with a large GUI scale) yields a panel
 * clamped to the top left and cells computed the same way — cramped, never negative, never an
 * exception.
 *
 * <h2>Two metric tiers, and why the panel is not one fixed size</h2>
 * The human's report on the first real screen was 「这个UI像是被压缩了一样」. The panel it drew was
 * 297x227 in a 20-pixel-cell / 44-pixel-row grid whose growth column had <b>two pixels</b> to spare at
 * the bottom: every corner of it was spent, so it read as one dense block with no margin anywhere.
 *
 * <p>The panel was therefore <b>not</b> scaled with the GUI. A pose-matrix scale would also scale the
 * nine-slice ornament and the font, and a scaled font is a blurry font at GUI scale 2 — the ornament art
 * is 64x64 per piece and the type is 9 pixels of glyph, both fixed-resolution. What grows instead is the
 * <em>content</em>: two tiers of measurements, chosen once per {@link #at} by comparing the panel each
 * one needs against the screen.
 *
 * <ul>
 *   <li>{@link Metrics#COMFORTABLE} — 96x56 cells (a 40-pixel portrait and two 10-pixel lines with 40
 *       pixels of text each), 20-pixel party slots, 6-pixel bars, <b>four</b> hook rows, 22-pixel
 *       padding, 4/5-pixel gaps. Its panel is {@code 380x273}. This is what a player sees whenever it
 *       fits, and it ends <b>17</b> pixels above the body bottom instead of 2.</li>
 *   <li>{@link Metrics#COMPACT} — the numbers this screen shipped with (66x44 cells, {@code 297x227}
 *       panel). Used only when the comfortable panel does not fit with a {@link #MIN_MARGIN} border, so
 *       a small window still gets a complete panel rather than a cropped one.</li>
 * </ul>
 *
 * <p>The constants on this class are the <b>compact</b> tier's, because they predate the second tier and
 * other code (and the tests) read them by name. A caller that wants the numbers actually in use asks the
 * instance ({@link #page()}, {@link #hookRows()}, {@link #cellIcon()}, …).
 *
 * <h2>Paging</h2>
 * The grid shows {@link #page()} cells at a time. {@link #clampFirstIndex} keeps the window aligned so
 * the last page is never half empty, and {@link #rosterIndexAt} maps a point back to an absolute roster
 * index, which is what the screen selects and what a drag carries.
 */
@ApiStatus.Internal
public final class BookLayout {

    /**
     * The border a panel must leave around itself, in GUI pixels, for its tier to be chosen.
     *
     * <p>Zero margin is not "fitting": the panel would touch the screen edge and read as cropped, which
     * is the impression the comfortable tier exists to remove. Eight pixels is air on each side — enough
     * to see that the panel is a panel floating over the world.
     */
    public static final int MIN_MARGIN = 8;

    // ---------------------------------------------------------------------------------------------
    // The compact tier's numbers, kept as named constants: they are what the shipped screen used, and
    // BookLayoutTest plus SkillIcons read several of them by name.
    // ---------------------------------------------------------------------------------------------

    /** Outer padding inside the compact panel. */
    public static final int PADDING = Metrics.COMPACT.padding();

    /** Height of the title row. */
    public static final int HEADER_HEIGHT = Metrics.COMPACT.headerHeight();

    /** Height of the themed ornament that separates the title from the body: the divider art is 256x16. */
    public static final int DIVIDER_HEIGHT = Metrics.DIVIDER_HEIGHT;

    /** Gap above and below the ornament. */
    public static final int DIVIDER_GAP = Metrics.COMPACT.dividerGap();

    /** Gap between the roster column and the right-hand column. */
    public static final int COLUMN_GAP = Metrics.COMPACT.columnGap();

    /** Width of the right-hand column (party slots + growth view). */
    public static final int RIGHT_WIDTH = Metrics.COMPACT.rightWidth();

    /** Roster grid: columns per page. */
    public static final int COLUMNS = Metrics.COLUMNS;

    /** Roster grid: rows per page. */
    public static final int ROWS = Metrics.ROWS;

    /** Cells one page holds. */
    public static final int PAGE = COLUMNS * ROWS;

    /** Roster cell width. */
    public static final int CELL_WIDTH = Metrics.COMPACT.cell().width();

    /** Roster cell height. */
    public static final int CELL_HEIGHT = Metrics.COMPACT.cell().height();

    /** Gap between roster cells, horizontally and vertically. */
    public static final int CELL_GAP = Metrics.COMPACT.cell().gap();

    /** Party slot edge length: one square per wheel slot. */
    public static final int SLOT_SIZE = Metrics.COMPACT.slotSize();

    /** Gap between party slots. */
    public static final int SLOT_GAP = Metrics.COMPACT.slotGap();

    /** Height of a section heading or a line of text. */
    public static final int LINE_HEIGHT = Metrics.LINE_HEIGHT;

    /** Height of a progress bar (experience, affection). */
    public static final int BAR_HEIGHT = Metrics.COMPACT.barHeight();

    /** Height of the feeding entry point. */
    public static final int FEED_HEIGHT = Metrics.COMPACT.feedHeight();

    /** Height of one hook row: one font line, no gap. */
    public static final int HOOK_ROW_HEIGHT = Metrics.HOOK_ROW_HEIGHT;

    /** Hook rows the compact growth column shows. */
    public static final int HOOK_ROWS = Metrics.COMPACT.hookRows();

    /** Gap between two lines inside the growth view. */
    public static final int LINE_GAP = Metrics.COMPACT.lineGap();

    /** Roster grid width, derived (compact). */
    public static final int GRID_WIDTH = Metrics.COMPACT.gridWidth();

    /** Roster grid height, derived (compact). */
    public static final int GRID_HEIGHT = Metrics.COMPACT.gridHeight();

    /** Panel width, derived (compact). */
    public static final int PANEL_WIDTH = Metrics.COMPACT.panelWidth();

    /** Body height: the taller of the two columns (compact). */
    public static final int BODY_HEIGHT = Metrics.COMPACT.bodyHeight();

    /** Panel height, derived (compact). */
    public static final int PANEL_HEIGHT = Metrics.COMPACT.panelHeight();

    /**
     * What one roster cell looks like inside: how big its portrait is and where its two lines go.
     *
     * <p>Part of the tier and not a constant on the class, because a bigger cell that keeps the old
     * 20-pixel disc would be a bigger cell with the same postage stamp in it — which is the "it looked
     * squashed" impression in its other form.
     *
     * @param width        cell width
     * @param height       cell height
     * @param gap          gap to the neighbouring cell
     * @param iconDiameter edge of the square portrait
     * @param iconInset    distance from the cell's left and top edges to the portrait
     * @param textGap      gap between the portrait and the text column
     * @param levelTop     distance from the cell's top edge to the level line
     * @param nameTop      distance from the cell's top edge to the name line
     */
    public record Cell(int width, int height, int gap, int iconDiameter, int iconInset, int textGap,
                       int levelTop, int nameTop) {

        /** {@return the x of the portrait's centre, relative to the cell's left edge} */
        public int iconCentreX() {
            return iconInset + iconDiameter / 2;
        }

        /** {@return the portrait's radius} */
        public double iconRadius() {
            return iconDiameter / 2.0;
        }

        /** {@return the x of the text column, relative to the cell's left edge} */
        public int textLeft() {
            return iconInset + iconDiameter + textGap;
        }
    }

    /**
     * One tier of measurements for the whole screen.
     *
     * <p>A record rather than a bag of integers copied into the methods: the two tiers then cannot
     * disagree about <em>which</em> numbers exist, and every derivation ({@link #gridWidth()},
     * {@link #panelWidth()}, {@link #rightColumnHeight()}) is written once and used by both.
     *
     * @param padding      outer padding inside the panel
     * @param headerHeight the title row
     * @param dividerGap   gap above and below the ornament
     * @param columnGap    gap between the roster column and the right column
     * @param rightWidth   width of the right-hand column
     * @param cell         what one roster cell looks like inside
     * @param slotSize     party slot edge
     * @param slotGap      gap between party slots
     * @param barHeight    height of a progress bar
     * @param feedHeight   height of the feeding entry point
     * @param hookRows     hook rows the growth column has room for
     * @param lineGap      gap between two lines of the growth view
     */
    public record Metrics(int padding, int headerHeight, int dividerGap, int columnGap, int rightWidth,
                          Cell cell, int slotSize, int slotGap, int barHeight, int feedHeight,
                          int hookRows, int lineGap) {

        /** The ornament's natural height: the divider art is 256x16 and is never stretched vertically. */
        public static final int DIVIDER_HEIGHT = 16;

        /** One font line. Not a metric of a tier: a line is 9 pixels of glyph and one of leading. */
        public static final int LINE_HEIGHT = 10;

        /** One hook row: a font line, no gap. */
        public static final int HOOK_ROW_HEIGHT = 9;

        /**
         * Columns per page. Two, because a portrait cell needs width and the grid has to stay narrow
         * enough to leave the growth column a readable 112–128 pixels.
         */
        public static final int COLUMNS = 2;

        /** Rows per page. */
        public static final int ROWS = 3;

        /**
         * The tier this screen shipped with: 66x44 cells, {@code 297x227} panel.
         *
         * <p>Kept for small windows, where the comfortable tier does not fit — and it is the definition
         * of the named constants above, so "the old numbers" exist in exactly one place.
         */
        public static final Metrics COMPACT = new Metrics(20, 14, 4, 10, 112,
                new Cell(66, 44, 3, 20, 5, 1, 8, 20), 18, 3, 5, 12, 3, 1);

        /**
         * The tier the human's report asked for: bigger cells, roomier lines and four hook rows.
         *
         * <p>Read as three groups. <b>The portrait grows with the cell</b> (20 → 40 pixels inside a 66 →
         * 96-pixel cell), because a bigger cell that keeps the small disc is the same "cramped" look with
         * more empty space. <b>The type gets room</b> (a 40-pixel text column instead of 38, gaps 3/1 →
         * 4/2, slots 18 → 20, bars 5 → 6) so two lines and a level are not stacked 2 pixels apart. <b>The
         * column gains a hook row</b> (3 → 4), which is what a character with three hooks plus the "+N"
         * line needs. The growth column then ends 17 pixels above the body bottom instead of 2 — the
         * arithmetic {@link #rightColumnHeight()} states and {@code BookLayoutTest} asserts per tier.
         */
        public static final Metrics COMFORTABLE = new Metrics(22, 15, 5, 12, 128,
                new Cell(96, 56, 4, 40, 8, 5, 9, 21), 20, 4, 6, 13, 4, 2);

        /** {@return {@code COLUMNS * ROWS} — cells one page holds} */
        public int page() {
            return COLUMNS * ROWS;
        }

        /** {@return the roster grid's width} */
        public int gridWidth() {
            return COLUMNS * cell.width() + (COLUMNS - 1) * cell.gap();
        }

        /** {@return the roster grid's height} */
        public int gridHeight() {
            return ROWS * cell.height() + (ROWS - 1) * cell.gap();
        }

        /** {@return the body height: the roster grid's, which the growth column is designed to fit} */
        public int bodyHeight() {
            return gridHeight();
        }

        /** {@return the panel's width} */
        public int panelWidth() {
            return padding + gridWidth() + columnGap + rightWidth + padding;
        }

        /** {@return the panel's height: padding, header, ornament, body, footer, padding} */
        public int panelHeight() {
            return padding + headerHeight + dividerGap + DIVIDER_HEIGHT + dividerGap + bodyHeight()
                    + lineGap + LINE_HEIGHT + padding;
        }

        /**
         * {@return how tall the growth column runs, from the party heading to the last hook row}
         *
         * <p>Written out so "does the right column fit the body" is arithmetic instead of a screenshot:
         * heading, slot row, growth heading, level, bar, affection, bar, next tier, feed, skills heading,
         * then the hook rows.
         */
        public int rightColumnHeight() {
            return LINE_HEIGHT + lineGap
                    + slotSize + lineGap
                    + LINE_HEIGHT + lineGap
                    + LINE_HEIGHT + lineGap
                    + barHeight + lineGap
                    + LINE_HEIGHT + lineGap
                    + barHeight + lineGap
                    + LINE_HEIGHT + lineGap
                    + feedHeight + lineGap
                    + LINE_HEIGHT
                    + hookRows * HOOK_ROW_HEIGHT;
        }
    }

    /**
     * A rectangle in GUI pixels.
     *
     * <p>Half-open on both axes ({@code x <= px < x + width}), which is what makes adjacent cells tile
     * without a pixel belonging to two of them — the property {@code BookLayoutTest} asserts directly.
     *
     * @param x      left edge
     * @param y      top edge
     * @param width  width, non-negative
     * @param height height, non-negative
     */
    public record Box(int x, int y, int width, int height) {

        /** {@return the first pixel column past this box} */
        public int right() {
            return x + width;
        }

        /** {@return the first pixel row past this box} */
        public int bottom() {
            return y + height;
        }

        /** {@return whether a point lies inside this box} */
        public boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }

        /** {@return the horizontal centre} */
        public int centreX() {
            return x + width / 2;
        }

        /** {@return the vertical centre} */
        public int centreY() {
            return y + height / 2;
        }
    }

    private final Metrics metrics;
    private final int panelX;
    private final int panelY;

    private BookLayout(Metrics metrics, int panelX, int panelY) {
        this.metrics = metrics;
        this.panelX = panelX;
        this.panelY = panelY;
    }

    /**
     * {@return the layout for a screen of this size, with the panel centred}
     *
     * <p>The tier is the comfortable one when its panel fits the screen with {@link #MIN_MARGIN} on
     * every side, and the compact one otherwise — see the class javadoc for why the panel is not scale
     * transformed.
     *
     * <p>Clamped at the top left rather than centred when the screen is too small for even the compact
     * panel: a negative origin would push the title off-screen, and there is no layout that makes a
     * 100-pixel-tall screen fit a 227-pixel panel.
     *
     * @param screenWidth  the screen's width in GUI pixels
     * @param screenHeight the screen's height in GUI pixels
     */
    public static BookLayout at(int screenWidth, int screenHeight) {
        Metrics metrics = tierFor(screenWidth, screenHeight);
        int x = Math.max(0, (screenWidth - metrics.panelWidth()) / 2);
        int y = Math.max(0, (screenHeight - metrics.panelHeight()) / 2);
        return new BookLayout(metrics, x, y);
    }

    /**
     * {@return the tier {@link #at} uses for a screen of this size}
     *
     * <p>Public so the screen's log line and the test can both state which tier a real window got,
     * instead of the report claiming one and the screen drawing the other.
     *
     * @param screenWidth  the screen's width in GUI pixels
     * @param screenHeight the screen's height in GUI pixels
     */
    public static Metrics tierFor(int screenWidth, int screenHeight) {
        return fits(Metrics.COMFORTABLE, screenWidth, screenHeight)
                ? Metrics.COMFORTABLE
                : Metrics.COMPACT;
    }

    private static boolean fits(Metrics metrics, int screenWidth, int screenHeight) {
        return metrics.panelWidth() + 2 * MIN_MARGIN <= screenWidth
                && metrics.panelHeight() + 2 * MIN_MARGIN <= screenHeight;
    }

    /** {@return the tier this layout was built from} */
    public Metrics metrics() {
        return metrics;
    }

    /** {@return {@code "comfortable"} or {@code "compact"}, for logs and assertions} */
    public String tierName() {
        return metrics == Metrics.COMFORTABLE ? "comfortable" : "compact";
    }

    /** {@return the roster cell metrics of this tier} */
    public Cell cellMetrics() {
        return metrics.cell();
    }

    /** {@return the panel this layout draws in} */
    public Box panel() {
        return new Box(panelX, panelY, metrics.panelWidth(), metrics.panelHeight());
    }

    /** {@return the row the title is drawn in} */
    public Box title() {
        return new Box(panelX + metrics.padding(), panelY + metrics.padding(),
                metrics.panelWidth() - 2 * metrics.padding(), metrics.headerHeight());
    }

    /**
     * {@return the box the themed ornament between the title and the body is struck in}
     *
     * <p>Full body width, {@link Metrics#DIVIDER_HEIGHT} tall. The divider art is 256x16 with its ink in
     * the middle ~48 columns, so the caller blits it stretched to this box rather than tiling it:
     * stretching keeps the ornament centred and symmetric, which is what it is drawn to be, while tiling
     * would repeat its centre jewel across the width.
     */
    public Box divider() {
        return new Box(panelX + metrics.padding(), title().bottom() + metrics.dividerGap(),
                metrics.panelWidth() - 2 * metrics.padding(), Metrics.DIVIDER_HEIGHT);
    }

    /** {@return the top-left corner of the body (below the ornament), both columns align here} */
    private int bodyTop() {
        return divider().bottom() + metrics.dividerGap();
    }

    /** {@return the x of the roster column} */
    private int rosterLeft() {
        return panelX + metrics.padding();
    }

    /** {@return the x of the right-hand column} */
    private int rightLeft() {
        return rosterLeft() + metrics.gridWidth() + metrics.columnGap();
    }

    /** {@return the box the roster grid occupies} */
    public Box rosterGrid() {
        return new Box(rosterLeft(), bodyTop(), metrics.gridWidth(), metrics.gridHeight());
    }

    /** {@return the box the party slot row occupies} */
    public Box partyRow() {
        return new Box(rightLeft(), bodyTop() + Metrics.LINE_HEIGHT + metrics.lineGap(),
                metrics.rightWidth(), metrics.slotSize());
    }

    /** {@return the box the "party" heading occupies} */
    public Box partyHeading() {
        return new Box(rightLeft(), bodyTop(), metrics.rightWidth(), Metrics.LINE_HEIGHT);
    }

    /** {@return the box the "growth" heading occupies} */
    public Box growthHeading() {
        return new Box(rightLeft(), partyRow().bottom() + metrics.lineGap(), metrics.rightWidth(),
                Metrics.LINE_HEIGHT);
    }

    /** {@return the box the level line occupies} */
    public Box levelLine() {
        return new Box(rightLeft(), growthHeading().bottom() + metrics.lineGap(),
                metrics.rightWidth(), Metrics.LINE_HEIGHT);
    }

    /** {@return the experience bar} */
    public Box expBar() {
        return new Box(rightLeft(), levelLine().bottom() + metrics.lineGap(),
                metrics.rightWidth(), metrics.barHeight());
    }

    /** {@return the affection line} */
    public Box affectionLine() {
        return new Box(rightLeft(), expBar().bottom() + metrics.lineGap(), metrics.rightWidth(),
                Metrics.LINE_HEIGHT);
    }

    /** {@return the affection bar} */
    public Box affectionBar() {
        return new Box(rightLeft(), affectionLine().bottom() + metrics.lineGap(),
                metrics.rightWidth(), metrics.barHeight());
    }

    /** {@return the line naming the next affection tier} */
    public Box nextTierLine() {
        return new Box(rightLeft(), affectionBar().bottom() + metrics.lineGap(),
                metrics.rightWidth(), Metrics.LINE_HEIGHT);
    }

    /** {@return the feeding entry point: a button when a dish handler is installed, text otherwise} */
    public Box feedEntry() {
        return new Box(rightLeft(), nextTierLine().bottom() + metrics.lineGap(),
                metrics.rightWidth(), metrics.feedHeight());
    }

    /**
     * {@return the box the "skills" heading occupies}
     *
     * <p>The skills list lives in the strip under the feeding entry, in the right-hand column: it belongs
     * to the selected character, next to her level and affection, and it is the only place in the panel
     * with room left.
     */
    public Box skillsHeading() {
        return new Box(rightLeft(), feedEntry().bottom() + metrics.lineGap(),
                metrics.rightWidth(), Metrics.LINE_HEIGHT);
    }

    /** {@return the number of hook rows this tier has room for} */
    public int hookRows() {
        return metrics.hookRows();
    }

    /** {@return the number of cells one page of this tier holds} */
    public int page() {
        return metrics.page();
    }

    /** {@return the number of grid columns this tier draws} */
    public int columns() {
        return Metrics.COLUMNS;
    }

    /**
     * {@return the box of hook row {@code index}, or {@code null} past the last row}
     *
     * <p>Rows are {@link Metrics#HOOK_ROW_HEIGHT} tall — a font line, no gap — because the rows plus the
     * heading have to fit the strip that is left; {@code BookLayoutTest} asserts they end inside the
     * body.
     *
     * @param index 0-based row within the list, {@code 0 <= index < hookRows()}
     */
    public Box hookRow(int index) {
        if (index < 0 || index >= metrics.hookRows()) {
            return null;
        }
        return new Box(rightLeft(), skillsHeading().bottom() + index * Metrics.HOOK_ROW_HEIGHT,
                metrics.rightWidth(), Metrics.HOOK_ROW_HEIGHT);
    }

    /**
     * {@return the footer line: the refusal while one is fresh, the interaction hint otherwise}
     *
     * <p>One line, two jobs, because they can never both be true at once — a hint under a "you do not own
     * that character" message would just be noise, and the refusal is exactly what the player needs where
     * the hint was.
     */
    public Box refusalLine() {
        return new Box(panelX + metrics.padding(),
                bodyTop() + metrics.bodyHeight() + metrics.lineGap(),
                metrics.panelWidth() - 2 * metrics.padding(), Metrics.LINE_HEIGHT);
    }

    /**
     * {@return the box of the cell at {@code indexInPage}, or {@code null} past the last cell}
     *
     * @param indexInPage position within the page, {@code 0}-{@code page()-1}
     */
    public Box cell(int indexInPage) {
        if (indexInPage < 0 || indexInPage >= metrics.page()) {
            return null;
        }
        int column = indexInPage % Metrics.COLUMNS;
        int row = indexInPage / Metrics.COLUMNS;
        return new Box(rosterLeft() + column * (metrics.cell().width() + metrics.cell().gap()),
                bodyTop() + row * (metrics.cell().height() + metrics.cell().gap()),
                metrics.cell().width(), metrics.cell().height());
    }

    /** {@return the radius of a roster cell's portrait} */
    public double cellIconRadius() {
        return metrics.cell().iconRadius();
    }

    /** {@return the x of a roster cell's portrait centre, or {@code 0} past the page} */
    public int cellIconCentreX(int indexInPage) {
        Box cell = cell(indexInPage);
        return cell == null ? 0 : cell.x() + metrics.cell().iconCentreX();
    }

    /** {@return the box of the level line inside a roster cell (empty past the page)} */
    public Box cellLevelBox(int indexInPage) {
        return cellTextBox(indexInPage, metrics.cell().levelTop());
    }

    /** {@return the box of the name line inside a roster cell (empty past the page)} */
    public Box cellNameBox(int indexInPage) {
        return cellTextBox(indexInPage, metrics.cell().nameTop());
    }

    private Box cellTextBox(int indexInPage, int topInset) {
        Box cell = cell(indexInPage);
        if (cell == null) {
            return new Box(0, 0, 0, 0);
        }
        int textX = cell.x() + metrics.cell().textLeft();
        return new Box(textX, cell.y() + topInset, Math.max(0, cell.right() - textX - 3),
                Metrics.LINE_HEIGHT);
    }

    /** {@return the horizontal span the roster grid's grid + right column covers (for wide banners)} */
    public int bodyWidth() {
        return metrics.gridWidth() + metrics.columnGap() + metrics.rightWidth();
    }

    /** {@return the radius of a party-slot portrait} */
    public double slotIconRadius() {
        return metrics.slotSize() / 2.0 - 2.0;
    }

    /**
     * {@return how many cells are visible for a roster of {@code count} entries starting at
     * {@code firstIndex}}
     *
     * @param count      how many characters the player owns
     * @param firstIndex absolute index of the first cell on screen
     */
    public int visibleCells(int count, int firstIndex) {
        return Math.max(0, Math.min(page(), count - clampFirstIndex(count, firstIndex)));
    }

    /**
     * {@return {@code firstIndex} clamped so the window never starts past the end}
     *
     * <p>The last page is aligned to end on the last character, so a roster of 13 shows cells 9-12 rather
     * than 12 and eleven empties. A negative offset (which a scroll wheel can produce at the top) means
     * the first page.
     *
     * @param count      how many characters the player owns
     * @param firstIndex the requested first index
     */
    public int clampFirstIndex(int count, int firstIndex) {
        int maxFirst = Math.max(0, count - page());
        return Math.max(0, Math.min(maxFirst, firstIndex));
    }

    /**
     * {@return the absolute roster index under a point, or empty when the point is not on a cell}
     *
     * <p>The inverse of {@link #cell}: the screen turns a click into "which character is that", and the
     * test asserts that every cell's centre maps back to its own index.
     *
     * @param px         mouse x in GUI pixels
     * @param py         mouse y in GUI pixels
     * @param count      how many characters the player owns
     * @param firstIndex absolute index of the first cell on screen
     */
    public OptionalInt rosterIndexAt(double px, double py, int count, int firstIndex) {
        int first = clampFirstIndex(count, firstIndex);
        for (int i = 0; i < visibleCells(count, first); i++) {
            Box box = cell(i);
            if (box != null && box.contains(px, py)) {
                return OptionalInt.of(first + i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * {@return the box of party slot {@code index}, or {@code null} past the last slot}
     *
     * @param index 0-based wheel slot, {@code 0}-{@code Party.SLOTS-1}
     */
    public Box slot(int index) {
        if (index < 0 || index >= Party.SLOTS) {
            return null;
        }
        Box row = partyRow();
        return new Box(row.x() + index * (metrics.slotSize() + metrics.slotGap()), row.y(),
                metrics.slotSize(), metrics.slotSize());
    }

    /** {@return every party slot box, in slot order, for drawing} */
    public List<Box> slots() {
        List<Box> boxes = new ArrayList<>(Party.SLOTS);
        for (int i = 0; i < Party.SLOTS; i++) {
            boxes.add(slot(i));
        }
        return List.copyOf(boxes);
    }

    /**
     * {@return the slot index under a point, or empty when the point is not on a slot}
     *
     * @param px mouse x in GUI pixels
     * @param py mouse y in GUI pixels
     */
    public OptionalInt slotAt(double px, double py) {
        for (int i = 0; i < Party.SLOTS; i++) {
            Box box = slot(i);
            if (box != null && box.contains(px, py)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }
}
