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
 *   | | cell cell                        |  growth heading       |
 *   | | cell cell                        |  level / exp bar      |
 *   | +----------------------------------+  affection / bar       |
 *   |                                       feed entry           |
 *   |                                       skills heading       |
 *   |                                       技能详情              >|
 *   | refusal line (only while a refusal is fresh)               |
 *   +------------------------------------------------------------+
 * </pre>
 *
 * <p>That strip has <b>no picture</b>: 「技能详情按钮不要显示技能图标，使用角色册同风格的按钮配色就行。」 The button
 * is a control drawn in the panel's palette, and the skill pictures live on the detail page's columns, which
 * put their skill's one picture on the name row ({@code SkillDetailLayout}). An effect row has no picture at
 * all (「通用效果不需要图标」).
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
    // BookLayoutTest reads several of them by name.
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
     * @param slice        nine-slice thickness of the panel frame, in GUI pixels
     */
    public record Metrics(int padding, int headerHeight, int dividerGap, int columnGap, int rightWidth,
                          Cell cell, int slotSize, int slotGap, int barHeight, int feedHeight,
                          int hookRows, int lineGap, int slice) {

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

        /**
         * Rows per page.
         *
         * <p>Three, and it stays three. The human's screenshot showed <b>one</b> roster cell above a
         * large empty band, which reads as "the grid is too short" — but the arithmetic says otherwise:
         * three 56-pixel rows is a 178-pixel body against a 170-pixel growth column, so the two columns
         * already agree. The band was <b>unpainted cells</b>: {@code drawRoster} drew only the cells that
         * had a character in them, so an owner of one character got one cell and five cells' worth of
         * background. The fix is in the screen (every cell on the page is painted, empty ones as an
         * empty slot), not here — growing the grid would have moved the void, not removed it.
         */
        public static final int ROWS = 3;

        /**
         * The tier this screen shipped with: 66x44 cells, {@code 297x227} panel.
         *
         * <p>Kept for small windows, where the comfortable tier does not fit — and it is the definition
         * of the named constants above, so "the old numbers" exist in exactly one place.
         */
        public static final Metrics COMPACT = new Metrics(20, 14, 4, 10, 112,
                new Cell(66, 44, 3, 20, 5, 1, 8, 20), 18, 3, 5, 12, 3, 1, 18);

        /**
         * The tier the human's report asked for: bigger cells, roomier lines and four hook rows.
         *
         * <p>Read as three groups. <b>The portrait grows with the cell</b> (20 → 40 pixels inside a 66 →
         * 96-pixel cell), because a bigger cell that keeps the small disc is the same "cramped" look with
         * more empty space. <b>The type gets room</b> (a 40-pixel text column instead of 38, gaps 3/1 →
         * 4/2, slots 18 → 20, bars 5 → 6) so two lines and a level are not stacked 2 pixels apart. <b>The
         * column gains a hook row</b> (3 → 4), which is what a character with three hooks plus the "+N"
         * line needs.
         *
         * <p><b>Padding and slice rose together</b> for the frame report (「UI很粗糙，上下像是被压缩了」):
         * 22 → 32 of padding and 18 → 30 of nine-slice. The ornament ink lives in each tile's outer
         * ~8 pixels, so the slice is really a compression ratio — 64/18 squeezed it 3.6× and blurred it
         * into the body, 64/30 is 2.1× and reads as filigree. Padding must stay ahead of the slice, which
         * is why the two numbers moved as one; {@code BookLayoutTest} asserts the margin for both tiers.
         */
        public static final Metrics COMFORTABLE = new Metrics(32, 15, 5, 12, 128,
                new Cell(96, 56, 4, 40, 8, 5, 9, 21), 20, 4, 6, 13, 4, 2, 30);

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

    /**
     * The seven boxes of one growth view, stacked top to bottom.
     *
     * <h2>Why a record and not seven more methods</h2>
     * The growth view is drawn twice now — in the right-hand column of the 角色编队 page, and in the bottom
     * right of the 角色一览 front page — from <b>one</b> drawing method, because a second copy of "level line,
     * experience bar, affection line, affection bar, next tier" is a second copy that drifts the first time a
     * line is added. The renderer therefore takes this record and asks it for boxes instead of asking
     * {@code BookLayout} by name; which page is on screen decides which column is passed, and the drawing code
     * cannot tell the difference. The order of the components <b>is</b> the drawing order.
     *
     * @param heading       the 「③ 养成」 heading
     * @param levelLine     the level, with the experience numbers right-aligned inside it
     * @param expBar        the experience bar
     * @param affectionLine affection and her tier
     * @param affectionBar  the affection bar
     * @param nextTierLine  the next tier's threshold, or "already at the last tier"
     * @param feedEntry     the feeding entry point: a button when a dish handler is installed, text otherwise
     */
    public record GrowthColumn(Box heading, Box levelLine, Box expBar, Box affectionLine, Box affectionBar,
                               Box nextTierLine, Box feedEntry) {
    }

    private final Metrics metrics;
    private final int panelX;
    private final int panelY;

    /**
     * The growth view of the 角色编队 page (right-hand column) and of the front page (bottom right).
     *
     * <p>Both are built once, in the constructor: a layout is immutable for one screen size, the front page is
     * the page the book opens on, and the front page asks for its column on <b>every</b> frame it is drawn. Both
     * are the same seven-box stack ({@link #growthColumn}) anchored at a different top, so the two pages cannot
     * disagree about their internal rhythm — only about where the stack starts.
     */
    private final GrowthColumn growth;
    private final GrowthColumn boardGrowth;

    private BookLayout(Metrics metrics, int panelX, int panelY) {
        this.metrics = metrics;
        this.panelX = panelX;
        this.panelY = panelY;
        // Neither of these reads the fields assigned below, so the constructor can build them directly:
        // rightLeft()/partyRow() are pure functions of (metrics, panelX, panelY).
        this.growth = growthColumn(rightLeft(), partyRow().bottom() + metrics.lineGap());
        this.boardGrowth = growthColumn(boardGrowthPanel().x(), boardGrowthPanel().y());
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

    /** {@return the nine-slice thickness this layout's panel frame is drawn at} */
    public int slice() {
        return metrics.slice();
    }

    /**
     * {@return the nine-slice thickness a tier's panel frame is drawn at}
     *
     * <p>One function rather than a field read at the call site, so the screen, the log line and the
     * test cannot each pick a different tier's number.
     *
     * @param metrics the tier
     */
    public static int sliceFor(Metrics metrics) {
        return metrics.slice();
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

    /**
     * {@return the seven boxes of a growth view anchored at {@code (x, y)} with the right column's width}
     *
     * <p>The <b>one</b> definition of the stack's internal rhythm: seven boxes, each one {@code lineGap} below
     * the previous, at heights this tier's metrics give them. Both pages read it through
     * {@link #growthColumn()} and {@link #boardGrowthColumn()}, so a change to a line's height or to the gap
     * between two of them moves both pages together — which is the only way two copies of one panel can stay
     * one panel.
     *
     * @param x the column's left edge
     * @param y the top of the heading row
     */
    private GrowthColumn growthColumn(int x, int y) {
        int width = metrics.rightWidth();
        int gap = metrics.lineGap();
        Box heading = new Box(x, y, width, Metrics.LINE_HEIGHT);
        Box level = new Box(x, heading.bottom() + gap, width, Metrics.LINE_HEIGHT);
        Box exp = new Box(x, level.bottom() + gap, width, metrics.barHeight());
        Box affectionLine = new Box(x, exp.bottom() + gap, width, Metrics.LINE_HEIGHT);
        Box affectionBar = new Box(x, affectionLine.bottom() + gap, width, metrics.barHeight());
        Box nextTier = new Box(x, affectionBar.bottom() + gap, width, Metrics.LINE_HEIGHT);
        Box feed = new Box(x, nextTier.bottom() + gap, width, metrics.feedHeight());
        return new GrowthColumn(heading, level, exp, affectionLine, affectionBar, nextTier, feed);
    }

    /** {@return the growth view of the right-hand column of the 角色编队 page} */
    public GrowthColumn growthColumn() {
        return growth;
    }

    /** {@return the box the "growth" heading occupies on the roster page} */
    public Box growthHeading() {
        return growth.heading();
    }

    /** {@return the box the level line occupies on the roster page} */
    public Box levelLine() {
        return growth.levelLine();
    }

    /** {@return the experience bar on the roster page} */
    public Box expBar() {
        return growth.expBar();
    }

    /** {@return the affection line on the roster page} */
    public Box affectionLine() {
        return growth.affectionLine();
    }

    /** {@return the affection bar on the roster page} */
    public Box affectionBar() {
        return growth.affectionBar();
    }

    /** {@return the line naming the next affection tier, on the roster page} */
    public Box nextTierLine() {
        return growth.nextTierLine();
    }

    /** {@return the feeding entry point on the roster page: a button when a dish handler is installed} */
    public Box feedEntry() {
        return growth.feedEntry();
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

    /**
     * {@return the button that opens the skill-detail page, in the strip the skill list used to fill}
     *
     * <h2>Why the strip is one box, and not the heading plus N rows</h2>
     * The list needed a heading row and {@link #hookRows()} effect rows, and on the compact tier that is two
     * rows for six of ペコリーヌ's hooks — 「角色册界面的4技能，满了不显示」. Paging the strip was the previous
     * answer and it did not solve the real problem: the strip is <b>too small to read a skill in</b>. So the
     * whole strip — heading row included — is now one target, and clicking it opens the page that has room.
     * The rect is exactly the union of the old heading and rows, so no metric moves and nothing else on the
     * panel has to be re-measured.
     */
    public Box skillsEntry() {
        BookLayout.Box heading = skillsHeading();
        BookLayout.Box last = hookRow(Math.max(0, metrics.hookRows() - 1));
        int height = last == null ? heading.height() : last.bottom() - heading.y();
        return new BookLayout.Box(heading.x(), heading.y(), heading.width(), height);
    }

    /**
     * Inset of the 「技能详情」 button's label from the button's own left edge, in GUI pixels.
     *
     * <p>The button has <b>no picture</b> — 「技能详情按钮不要显示技能图标，使用角色册同风格的按钮配色就行。」 — so
     * the label starts at the padding every other control in the panel uses, and there is no reserved square
     * left over from a removed picture. The value is the one the label used before the picture was introduced
     * and after it was removed again.
     */
    private static final int ENTRY_TEXT_INSET = 5;

    /**
     * Width kept free at the button's right end for the chevron, in GUI pixels.
     *
     * <p>A constant rather than the chevron's measured width because this class does no font work and the
     * label box has to be answerable without a font: the game's own {@code >} is 5 pixels wide, it is drawn 5
     * pixels in from the button's right edge, and one more pixel is air — 11 in all. A little too much only
     * shortens the label; too little would let a full-width label run under the glyph.
     */
    private static final int ENTRY_CHEVRON = 11;

    /**
     * {@return the box the 「技能详情」 button's label is drawn in}
     *
     * <p>One font line tall and vertically centred, starting at the button's own left inset and stopping before
     * the chevron strip, so the two decorations cannot overlap the words. The vertical centring lives here and
     * not in the render method for the same reason the detail page's rectangles do.
     *
     * <p><b>There is no picture box.</b> The button is a control in the panel's palette, not a place where a
     * skill is identified — 「技能详情按钮不要显示技能图标，使用角色册同风格的按钮配色就行。」 The detail page's
     * columns keep their skill pictures; this button has none, so the label starts where the text always did.
     */
    public Box skillsEntryLabel() {
        Box box = skillsEntry();
        int left = box.x() + ENTRY_TEXT_INSET;
        int right = box.right() - ENTRY_CHEVRON;
        return new Box(left, box.y() + (box.height() - Metrics.LINE_HEIGHT) / 2,
                Math.max(0, right - left), Metrics.LINE_HEIGHT);
    }

    /**
     * {@return the whole body of the panel, for the skill-detail page}
     *
     * <p>The detail page is a <b>mode</b> of this screen, not a second screen, so it reuses this panel — and
     * then it has the entire body rather than the right-hand column: the roster grid, the party slots and the
     * growth bars are not drawn while it is open, so their rectangles are free. The box is the full body
     * width ({@link #bodyWidth()}) and the body height, i.e. exactly the area between the ornament and the
     * footer line, which {@code SkillDetailLayout} then divides into columns.
     *
     * <p>The footer ({@link #refusalLine()}) is deliberately <b>not</b> part of it: while the page is open that
     * line carries the way back ({@code Esc}), which must stay visible while the grid scrolls under it.
     */
    public Box skillDetailBody() {
        return new BookLayout.Box(panelX + metrics.padding(), bodyTop(), bodyWidth(),
                metrics.bodyHeight());
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

    /** {@return the edge of a roster cell's square portrait} */
    public int cellIconDiameter() {
        return metrics.cell().iconDiameter();
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

    /**
     * {@return the left edge of a roster cell's square portrait, or {@code 0} past the page}
     *
     * <p>The square drawing path takes the picture's left and top edges rather than a centre and a radius,
     * because a square has no radius. Both are derived from the same {@code Cell.iconInset} the centre form
     * uses, so the two cannot disagree about where the picture is.
     */
    public int cellIconLeft(int indexInPage) {
        Box cell = cell(indexInPage);
        return cell == null ? 0 : cell.x() + metrics.cell().iconInset();
    }

    /** {@return the top edge of a roster cell's square portrait, or {@code 0} past the page} */
    public int cellIconTop(int indexInPage) {
        Box cell = cell(indexInPage);
        return cell == null ? 0 : cell.y() + metrics.cell().iconInset();
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

    /** {@return the vertical span a growth view needs, from its heading to its last line} */
    public int rightColumnHeight() {
        return metrics.rightColumnHeight();
    }

    /** {@return the horizontal span the roster grid's grid + right column covers (for wide banners)} */
    public int bodyWidth() {
        return metrics.gridWidth() + metrics.columnGap() + metrics.rightWidth();
    }

    // ---------------------------------------------------------------------------------------------
    // The front page: 角色一览
    //
    // 「进入现在这个界面之前的界面，一排5个角色横着摆，角色行下面左侧两个按钮，一个是角色编队，一个是技能详情，
    //  角色行下面右侧显示养成选中角色的养成信息。」
    //
    // The page is the book's <b>landing</b> page: five party slots in one horizontal row across the top, two
    // buttons under the row on the left, the selected character's growth view under the row on the right.
    // Everything below is derived from the same two body corners the roster page uses, so the front page and
    // the roster page are the same panel seen twice — not two panels.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@return the edge of one large party portrait on the front page}
     *
     * <p>The five portraits <b>fill the body's width</b>: the edge is what is left of {@code bodyWidth} after
     * four {@code cellGap}s, divided by {@link Party#SLOTS}. That is the sketch — 「一排5个角色横着摆」 — taken
     * literally, and it is also the reason the row needs no alignment rule: five equal squares whose widths
     * and gaps add up to the body end exactly at the body's right edge, so the row is balanced by construction
     * rather than by a centring offset that has to be re-derived per tier.
     *
     * <p>Square (same edge on both axes), so a portrait is not stretched, and derived from the <b>body</b> and
     * not from a pixel constant, because the body's size is per-tier. {@code BookLayoutTest} asserts the
     * five-slot row stays inside the body at both tiers and at both screen sizes.
     */
    public int boardSlotSize() {
        int gaps = (Party.SLOTS - 1) * metrics.cell().gap();
        return Math.max(0, (bodyWidth() - gaps) / Party.SLOTS);
    }

    /**
     * {@return the row the five large portraits occupy: the sketch's 角色行}
     *
     * <p>At the top of the body, exactly as wide as its five slots and four gaps — so
     * {@code boardSlot(0).x() == boardRow().x()} and the row's right edge is the last slot's.
     */
    public Box boardRow() {
        int size = boardSlotSize();
        int gap = metrics.cell().gap();
        return new Box(panelX + metrics.padding(), bodyTop(),
                Party.SLOTS * size + (Party.SLOTS - 1) * gap, size);
    }

    /**
     * {@return the box of large portrait {@code index} on the front page, or {@code null} past the last one}
     *
     * <p>Laid out left to right from the row's own left edge with the roster grid's cell gap between
     * neighbours: these portraits are several times the size of a party slot, and the party row's 3-4 pixel gap
     * would read as one solid block of faces.
     *
     * @param index 0-based party slot
     */
    public Box boardSlot(int index) {
        if (index < 0 || index >= Party.SLOTS) {
            return null;
        }
        Box row = boardRow();
        int size = boardSlotSize();
        return new Box(row.x() + index * (size + metrics.cell().gap()), row.y(), size, size);
    }

    /** {@return every large portrait box on the front page, in slot order, for drawing} */
    public List<Box> boardSlots() {
        List<Box> boxes = new ArrayList<>(Party.SLOTS);
        for (int i = 0; i < Party.SLOTS; i++) {
            boxes.add(boardSlot(i));
        }
        return List.copyOf(boxes);
    }

    /**
     * {@return the slot index under a point on the front page, or empty when the point is not on a portrait}
     *
     * <p>The inverse of {@link #boardSlot}, for the click that selects whose growth information the front page
     * is describing. Selecting is <b>all</b> a click does here: placing a character into a slot is the roster
     * page's gesture ({@code 角色编队}), and the front page must not become a second way to write the party.
     *
     * @param px mouse x in GUI pixels
     * @param py mouse y in GUI pixels
     */
    public OptionalInt boardSlotAt(double px, double py) {
        for (int i = 0; i < Party.SLOTS; i++) {
            Box box = boardSlot(i);
            if (box != null && box.contains(px, py)) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /** Height of one front-page button: two lines, so it reads as a control and not as a line of text. */
    public int boardButtonHeight() {
        return 2 * Metrics.LINE_HEIGHT;
    }

    /** {@return the horizontal gap between the two front-page buttons} */
    public int boardButtonGap() {
        return metrics.lineGap() * 2;
    }

    /**
     * {@return the band the two buttons and the growth panel share, under the portrait row}
     *
     * <p>One band, two halves: the buttons on the left, the growth information on the right, both starting
     * {@code lineGap} under the portrait row — 「角色行下面左侧两个按钮……角色行下面右侧显示养成信息」. Having the
     * band as one function is what keeps the two halves on the same baseline; the growth column is simply taller
     * than a button.
     */
    private int boardBandTop() {
        return boardRow().bottom() + metrics.lineGap();
    }

    /** {@return how much width the button half of the band has: everything the growth column does not take} */
    private int boardButtonSpan() {
        return Math.max(0, bodyWidth() - metrics.rightWidth() - metrics.columnGap());
    }

    /**
     * {@return the width one of the two front-page buttons gets}
     *
     * <p>The button half of the band, minus the gap the pair keeps between them, halved. Derived from the
     * <b>growth column it must clear</b> rather than from a fraction of the body, so "the buttons never reach
     * the growth panel" is arithmetic rather than a coincidence that holds at two screen sizes.
     */
    public int boardButtonWidth() {
        return Math.max(0, (boardButtonSpan() - boardButtonGap()) / 2);
    }

    /**
     * {@return the box of the 「角色编队」 button — the way into the page that used to be the whole book}
     *
     * <p>Leftmost of the two, under the portrait row. Clicking it opens {@link #boardPage()}'s other page, i.e.
     * the roster grid, the party column and the growth panel: everything this book was before the front page
     * existed, unchanged.
     */
    public Box boardPartyButton() {
        return new Box(panelX + metrics.padding(), boardBandTop(), boardButtonWidth(),
                boardButtonHeight());
    }

    /**
     * {@return the box of the 「技能详情」 button on the front page, right of the party button}
     *
     * <p>Opens the <b>existing</b> skill-detail page for the selected character — the page the roster screen
     * already reaches through its skills entry. This page gets a second door to it, not a second copy of it.
     */
    public Box boardSkillsButton() {
        Box party = boardPartyButton();
        return new Box(party.right() + boardButtonGap(), party.y(), boardButtonWidth(),
                boardButtonHeight());
    }

    /**
     * {@return the box a front-page button's label is drawn in}
     *
     * <p>One font line, inset from both ends and vertically centred, so the two buttons of the pair are worded
     * the same way whatever their labels are: {@code CharacterBookScreen} centres the text inside this box, and a
     * label longer than it is truncated rather than allowed to run out of the button.
     *
     * @param button the button box ({@link #boardPartyButton()} or {@link #boardSkillsButton()})
     */
    public Box boardButtonLabel(Box button) {
        int inset = 3;
        return new Box(button.x() + inset, button.centreY() - Metrics.LINE_HEIGHT / 2,
                Math.max(0, button.width() - 2 * inset), Metrics.LINE_HEIGHT);
    }

    /**
     * {@return the box the front page's growth information occupies}
     *
     * <p>The right-hand half of the band, {@code rightWidth} wide — the tier's own column width, which is the
     * width the growth view was designed to be read at (the same 112/128 pixels the roster page gives it). Taking
     * the proven width rather than "whatever is left" is what keeps the front page's numbers laid out exactly like
     * the roster page's.
     *
     * <p>It is anchored to the body's <b>right</b> edge, so the panel is flush with the portrait row above it
     * however narrow the buttons get.
     */
    public Box boardGrowthPanel() {
        return new Box(panelX + metrics.padding() + bodyWidth() - metrics.rightWidth(), boardBandTop(),
                metrics.rightWidth(), growthStackHeight());
    }

    /**
     * {@return the vertical span one growth view needs, heading to feeding entry}
     *
     * <p>Read off the stack itself ({@link #growthColumn(int, int)}) rather than written a second time: the
     * difference between the first box's top and the last box's bottom <em>is</em> the height, so a change to a
     * line's height or to a gap moves the panel that has to hold it as well.
     */
    private int growthStackHeight() {
        GrowthColumn stack = growthColumn(0, 0);
        return stack.feedEntry().bottom();
    }

    /** {@return the seven boxes of the front page's growth view, under the portrait row on the right} */
    public GrowthColumn boardGrowthColumn() {
        return boardGrowth;
    }

    /**
     * {@return the edge of a front-page portrait's picture, inside the slot's plate}
     *
     * <p>Two pixels of plate on each side, the same rule {@link #slotIconDiameter()} follows, so a front-page
     * portrait is the party slot scaled up rather than a different way of framing a face.
     */
    public int boardSlotIconDiameter() {
        return Math.max(0, boardSlotSize() - 4);
    }

    /** {@return the box the front page's title row is drawn in — the same row the other pages use} */
    public Box boardTitle() {
        return title();
    }

    /** {@return the radius of a party-slot portrait} */
    public double slotIconRadius() {
        return metrics.slotSize() / 2.0 - 2.0;
    }

    /**
     * {@return the edge of a party slot's square portrait}
     *
     * <p>Derived from {@link #slotIconRadius()} rather than restated, so the two drawing forms cannot drift:
     * the slot leaves 2 pixels of plate on each side of the picture, and the square side is that radius
     * doubled.
     */
    public int slotIconDiameter() {
        return (int) (slotIconRadius() * 2);
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
