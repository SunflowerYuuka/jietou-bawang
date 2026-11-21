package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 技能详情 page's geometry, asserted instead of looked at.
 *
 * <h2>Why this file exists</h2>
 * The human drew the page — 「技能名1 | 技能名2 | 技能名3 / 效果 | 效果 | 效果」 — and nothing about that drawing can be
 * checked on this machine: {@code runClient} cannot start here (arm64 natives), so a screenshot is not
 * available as evidence. Every claim therefore becomes arithmetic in {@link SkillDetailLayout} and an
 * assertion here:
 *
 * <ul>
 *   <li><b>how many columns fit</b>, for a body that is the compact tier's 257 pixels and the comfortable
 *       tier's 336, and for a width so small only one fits;</li>
 *   <li><b>each column's rectangle</b>, and that no two columns — or two wrapped rows — share a pixel;</li>
 *   <li><b>where it wraps</b>: the fourth entry starts a new line under the first, not beside the third;</li>
 *   <li><b>hit-testing</b>: a click on a column's centre is that column, and a click outside the body is
 *       nothing — including after the page has been scrolled, which is the case a scroll offset applied at
 *       only one of the two call sites always breaks;</li>
 *   <li><b>the numbers panel</b>: it stays inside the book, below its column when there is room and above it
 *       when there is not.</li>
 * </ul>
 *
 * <p>The two window sizes are the same extremes {@code BookLayoutTest} uses: 320x240 is the smallest common
 * effective resolution at GUI scale 2, and 1920x1080 the largest anyone runs.
 */
class SkillDetailLayoutTest {

    private static final int SMALL_WIDTH = 320;
    private static final int SMALL_HEIGHT = 240;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;

    private static final SkillDetailLayout.Metrics COMPACT = SkillDetailLayout.Metrics.COMPACT;
    private static final SkillDetailLayout.Metrics COMFORTABLE = SkillDetailLayout.Metrics.COMFORTABLE;

    private static BookLayout.Box compactBody() {
        return BookLayout.at(SMALL_WIDTH, SMALL_HEIGHT).skillDetailBody();
    }

    private static BookLayout.Box comfortableBody() {
        return BookLayout.at(LARGE_WIDTH, LARGE_HEIGHT).skillDetailBody();
    }

    // ---------------------------------------------------------------------------------------
    // How many columns fit
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The compact body takes exactly three columns</b> — the count in the human's drawing.
     *
     * <p>78 pixels minimum per column and a 6-pixel gap in a 257-pixel body: {@code (257 + 6) / (78 + 6)} is
     * 3.13, so three. If a future metric change made this two, the page would silently become a list of pairs
     * and the sketch would stop matching the screen.
     */
    @Test
    void theCompactBodyTakesThreeColumns() {
        BookLayout.Box body = compactBody();
        assertEquals(3, SkillDetailLayout.columnCount(body.width(), COMPACT),
                "the compact body (" + body.width() + "px) must take three columns");
        assertEquals(3, SkillDetailLayout.columnCount(comfortableBody().width(), COMFORTABLE),
                "and so must the comfortable one, so the page does not re-flow when a window grows");
    }

    /** A body too narrow for even one column still answers one: a page of no columns draws and hits nothing. */
    @Test
    void aNarrowBodyStillAnswersOneColumn() {
        assertEquals(1, SkillDetailLayout.columnCount(0, COMPACT));
        assertEquals(1, SkillDetailLayout.columnCount(1, COMPACT));
        assertEquals(1, SkillDetailLayout.columnCount(77, COMPACT));
        assertEquals(1, SkillDetailLayout.columnCount(78, COMPACT));
        assertEquals(2, SkillDetailLayout.columnCount(78 + 6 + 78, COMPACT));
    }

    /** The columns and their gaps never add up to more than the body, at any width. */
    @Test
    void theColumnsNeverOverflowTheBody() {
        for (int width = 0; width <= 400; width++) {
            int columns = SkillDetailLayout.columnCount(width, COMPACT);
            int columnWidth = SkillDetailLayout.columnWidth(width, COMPACT, columns);
            int total = columns * columnWidth + (columns - 1) * COMPACT.columnGap();
            assertTrue(total <= width,
                    width + "px of body: " + columns + " columns of " + columnWidth + " = " + total);
            assertTrue(columns >= 1 && columnWidth >= 0);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The grid, and where it wraps
    // ---------------------------------------------------------------------------------------

    /**
     * <b>Six skills on the compact tier make a sane grid</b>: two lines of three, nothing overlapping, all of
     * it inside the body, and short enough that nothing has to be scrolled to.
     */
    @Test
    void sixSkillsOnTheCompactTierMakeASaneGrid() {
        List<SkillDetailLayout.Column> counts = cols(2, 2, 1, 1, 2, 2);
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT, counts);
        assertEquals(6, grid.size());
        assertEquals(3, grid.columnsPerRow());
        assertNoOverlap(grid.cells());
        assertInsideBody(grid);
        // Two grid lines: the first three entries share a top, the last three share a lower one.
        assertEquals(grid.cells().get(0).y(), grid.cells().get(1).y());
        assertEquals(grid.cells().get(0).y(), grid.cells().get(2).y());
        assertEquals(grid.cells().get(3).y(), grid.cells().get(5).y());
        assertTrue(grid.cells().get(3).y() > grid.cells().get(0).bottom(),
                "the second line starts below the first");
        assertEquals(0, grid.clamp(0));
        assertTrue(grid.contentHeight() <= grid.area().height(),
                "six skills on the compact tier must not need scrolling: " + grid.contentHeight() + "px of "
                        + grid.area().height() + "px");
    }

    /** The fourth entry wraps to the left edge; it does not continue to the right of the third. */
    @Test
    void theGridWrapsBackToTheLeftEdge() {
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT,
                cols(1, 1, 1, 1, 1, 1, 1));
        assertEquals(7, grid.size());
        assertEquals(grid.cells().get(0).x(), grid.cells().get(3).x(),
                "entry 4 must start a new line at the same x as entry 1");
        assertTrue(grid.cells().get(3).y() > grid.cells().get(0).y());
    }

    /** A wrapped line is as tall as the tallest column in it, so the next line never overlaps a long one. */
    @Test
    void aLineIsAsTallAsItsTallestColumn() {
        BookLayout.Box body = compactBody();
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(body, COMPACT, cols(5, 1, 1, 1));
        int tallest = Math.max(grid.cells().get(0).height(), Math.max(grid.cells().get(1).height(),
                grid.cells().get(2).height()));
        assertEquals(COMPACT.columnHeight(5), tallest);
        assertEquals(grid.cells().get(0).y() + tallest + COMPACT.rowGap(), grid.cells().get(3).y(),
                "the second line starts one row gap below the tallest column of the first");
    }

    // ---------------------------------------------------------------------------------------
    // Hit-testing
    // ---------------------------------------------------------------------------------------

    /**
     * <b>A click at a column's centre is that column</b> — for every column on the page.
     *
     * <p>This is the inverse of the layout, and the property that makes the numbers panel reachable at all. It
     * is asserted for every cell rather than for one, because a rounding error in the column arithmetic shows
     * up at the right-hand edge first.
     */
    @Test
    void aClickOnAColumnHitsThatColumn() {
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT,
                cols(2, 3, 1, 4, 2, 1));
        for (int index = 0; index < grid.size(); index++) {
            BookLayout.Box cell = grid.cells().get(index);
            OptionalInt hit = grid.entryAt(cell.centreX() + 0.5, cell.centreY() + 0.5);
            assertEquals(OptionalInt.of(index), hit,
                    "the centre of column " + index + " at " + cell + " must hit column " + index);
        }
        assertTrue(grid.entryAt(compactBody().x() - 10, compactBody().y() - 10).isEmpty(),
                "a click outside the body is nothing");
    }

    /** The area clips the hit test: the rows scrolled above the body are not clickable. */
    @Test
    void aClickAboveTheBodyHitsNothingEvenWhenContentIsThere() {
        BookLayout.Box body = compactBody();
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(body, COMPACT,
                cols(6, 6, 6, 6, 6, 6, 6, 6, 6));
        assertTrue(grid.contentHeight() > body.height(), "sanity: this page is taller than the body");
        BookLayout.Box first = grid.cells().get(0);
        assertEquals(OptionalInt.of(0), grid.entryAt(first.centreX(), first.centreY()));
        SkillDetailLayout.Grid scrolled = grid.scrolled(first.height());
        assertTrue(scrolled.entryAt(first.centreX(), body.y() - 1).isEmpty(),
                "content scrolled above the body is not part of the page any more");
    }

    /** Scrolling moves the boxes and the hit test together — the one thing a scroll can get wrong. */
    @Test
    void scrollingMovesTheBoxesAndTheHitTestTogether() {
        BookLayout.Box body = compactBody();
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(body, COMPACT,
                cols(6, 6, 6, 6, 6, 6, 6, 6, 6));
        int scroll = 17;
        SkillDetailLayout.Grid scrolled = grid.scrolled(scroll);
        for (int index = 0; index < scrolled.size(); index++) {
            BookLayout.Box before = grid.cells().get(index);
            BookLayout.Box after = scrolled.cells().get(index);
            assertEquals(before.y() - scroll, after.y(), "column " + index + " must move by the scroll");
            OptionalInt hit = scrolled.entryAt(after.centreX(), after.centreY());
            if (scrolled.area().contains(after.centreX(), after.centreY())) {
                assertEquals(OptionalInt.of(index), hit,
                        "after scrolling, column " + index + "'s own centre must still hit it");
            } else {
                assertTrue(hit.isEmpty(), "column " + index + " is off the page, so nothing is hit there");
            }
        }
    }

    /** The scroll stops at both ends, which is what lets the wheel fall through instead of swallowing. */
    @Test
    void theScrollIsClampedToTheContent() {
        assertEquals(0, SkillDetailLayout.clampScroll(50, 100, 10), "short content never scrolls");
        assertEquals(0, SkillDetailLayout.clampScroll(0, 0, 0));
        assertEquals(0, SkillDetailLayout.clampScroll(300, 100, -20), "no scrolling above the top");
        assertEquals(200, SkillDetailLayout.clampScroll(300, 100, 500), "nor past the last pixel");
        assertEquals(120, SkillDetailLayout.clampScroll(300, 100, 120));
    }

    // ---------------------------------------------------------------------------------------
    // The numbers panel
    // ---------------------------------------------------------------------------------------

    /** The numbers panel stays inside the book, whichever column it is anchored to. */
    @Test
    void theNumbersPanelStaysInsideTheBook() {
        BookLayout layout = BookLayout.at(SMALL_WIDTH, SMALL_HEIGHT);
        BookLayout.Box bounds = layout.panel();
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(layout.skillDetailBody(), COMPACT,
                cols(2, 2, 2, 2, 2, 2));
        for (BookLayout.Box cell : grid.cells()) {
            BookLayout.Box popup = SkillDetailLayout.popup(cell,
                    SkillDetailLayout.popupWidth(grid, COMPACT), COMPACT.popupHeight(9), bounds);
            assertTrue(popup.x() >= bounds.x() && popup.right() <= bounds.right(),
                    "the numbers panel must stay inside the book horizontally: " + popup);
            assertTrue(popup.y() >= bounds.y() && popup.bottom() <= bounds.bottom(),
                    "the numbers panel must stay inside the book vertically: " + popup);
        }
    }

    /** Below the column when there is room, above it when there is not — never half off the page. */
    @Test
    void theNumbersPanelGoesAboveWhenThereIsNoRoomBelow() {
        BookLayout.Box bounds = new BookLayout.Box(0, 0, 200, 100);
        BookLayout.Box high = new BookLayout.Box(10, 10, 60, 20);
        BookLayout.Box low = new BookLayout.Box(10, 70, 60, 20);
        assertEquals(high.bottom(), SkillDetailLayout.popup(high, 100, 40, bounds).y(),
                "room below: the panel hangs under the column");
        BookLayout.Box above = SkillDetailLayout.popup(low, 100, 40, bounds);
        assertTrue(above.bottom() <= low.y(), "no room below: the panel sits above the column");
        assertTrue(above.y() >= bounds.y(), "and is pushed inside the panel it belongs to: " + above);
        assertTrue(above.bottom() <= bounds.bottom());
    }

    /** The panel's lines are inside its frame, and there are never more of them than fit. */
    @Test
    void thePanelLinesFitInsideThePanel() {
        BookLayout.Box popup = new BookLayout.Box(20, 20, 120, COMPACT.popupHeight(6));
        int lines = SkillDetailLayout.visibleLines(popup, COMPACT);
        assertEquals(6, lines);
        for (int index = 0; index < lines; index++) {
            BookLayout.Box line = SkillDetailLayout.popupLine(popup, COMPACT, index);
            assertTrue(line.x() >= popup.x() && line.right() <= popup.right());
            assertTrue(line.y() >= popup.y() && line.bottom() <= popup.bottom());
        }
        assertEquals(0, SkillDetailLayout.popupLine(popup, COMPACT, lines).width(),
                "past the last line there is nothing to draw");
    }

    // ---------------------------------------------------------------------------------------
    // Inside a column
    // ---------------------------------------------------------------------------------------

    /** Every effect row stays inside its own column, and the label stops where the tier's strip starts. */
    @Test
    void everyEffectRowStaysInsideItsColumn() {
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT, cols(4, 2, 1));
        for (int index = 0; index < grid.size(); index++) {
            BookLayout.Box cell = grid.cells().get(index);
            int rows = cols(4, 2, 1).get(index).rows();
            for (int row = 0; row < rows; row++) {
                BookLayout.Box line = grid.effectRow(index, row);
                assertNotNull(line);
                assertTrue(line.bottom() <= cell.bottom(),
                        "row " + row + " of column " + index + " must end inside its column");
                BookLayout.Box label = grid.effectLabel(index, row);
                BookLayout.Box tier = grid.effectTier(index, row);
                // No icon any more: the label starts at the row's own left edge and owns what the picture
                // used to take (「通用效果现在不需要图标」).
                assertEquals(line.x(), label.x(), "the label starts at the row's left edge");
                assertTrue(label.right() <= tier.x(), "the label must not run into the tier");
                assertTrue(tier.right() <= line.right(), "the tier stays inside the row");
            }
            assertNull(grid.effectRow(index, rows), "past that column's own rows there is no row");
        }
    }

    // ---------------------------------------------------------------------------------------
    // The column's one skill picture (「一个技能……图标只使用一个」)
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The skill's picture sits beside its name, inside the column, and never covers the label.</b>
     *
     * <p>This is the whole geometry of "the book now draws a skill's icon": a square slot at the column's top
     * left, the name starting after it, and both inside the column the grid handed out. Asserted for every
     * column and for both tiers, because the picture's edge is a per-tier number (14 compact, 16 comfortable)
     * and a copy of the arithmetic in the render method is exactly what this test exists to prevent.
     */
    @Test
    void theSkillPictureSitsBesideTheNameInsideTheColumn() {
        for (SkillDetailLayout.Metrics metrics : List.of(COMPACT, COMFORTABLE)) {
            BookLayout.Box body = metrics == COMPACT ? compactBody() : comfortableBody();
            SkillDetailLayout.Grid grid = SkillDetailLayout.grid(body, metrics, cols(2, 3, 1, 4, 2, 1));
            for (int index = 0; index < grid.size(); index++) {
                BookLayout.Box cell = grid.cells().get(index);
                BookLayout.Box icon = grid.nameIcon(index);
                BookLayout.Box name = grid.nameBox(index);
                assertEquals(metrics.nameIcon(), icon.width(), "the picture is nameIcon pixels wide");
                assertEquals(icon.width(), icon.height(), "and square: the art is 64x64");
                assertEquals(cell.x(), icon.x(), "the picture starts at the column's left edge");
                assertEquals(cell.y(), icon.y(), "and at its top edge, on the name row");
                assertTrue(icon.right() <= cell.right(), "the picture stays inside the column");
                assertTrue(icon.bottom() <= cell.bottom(), "and above the column's last row");
                assertTrue(name.x() >= icon.right(), "the name starts after the picture, never under it");
                assertTrue(name.right() <= cell.right(), "the name stays inside the column");
                assertTrue(name.width() > 0, "the name has room to be drawn at all: " + name);
                assertEquals(metrics.lineHeight(), name.height(), "the name is one font line");
                assertEquals(cell.y() + (metrics.nameRowHeight() - metrics.lineHeight()) / 2, name.y(),
                        "and is vertically centred on the picture's row");
            }
        }
    }

    /**
     * <b>Every effect row starts below the picture.</b>
     *
     * <p>The picture is taller than a font line (14 against 10), so the name row grew. A column whose first
     * effect row still began at {@code cell.y() + lineHeight} would draw that row's text across the bottom
     * third of the picture — the exact defect this assertion pins, and the reason the name row has its own
     * height function instead of two call sites reading {@code lineHeight}.
     */
    @Test
    void noEffectRowIsDrawnUnderTheSkillPicture() {
        for (SkillDetailLayout.Metrics metrics : List.of(COMPACT, COMFORTABLE)) {
            BookLayout.Box body = metrics == COMPACT ? compactBody() : comfortableBody();
            SkillDetailLayout.Grid grid = SkillDetailLayout.grid(body, metrics, cols(4, 2, 1));
            assertTrue(metrics.nameRowHeight() > metrics.lineHeight(),
                    "sanity: the picture is taller than a font line, or this test proves nothing");
            for (int index = 0; index < grid.size(); index++) {
                BookLayout.Box icon = grid.nameIcon(index);
                BookLayout.Box first = grid.effectRow(index, 0);
                assertNotNull(first);
                assertTrue(first.y() >= icon.bottom(),
                        "the first effect row starts below the picture: " + first + " vs " + icon);
            }
        }
    }

    /**
     * <b>The label is truncated, not spilled, when a long skill name sits beside the picture.</b>
     *
     * <p>The name box is what makes truncation possible at all: {@code drawClipped} cuts the text to the box
     * it is given, so "the name never reaches the neighbouring column" is really "the box ends before it".
     * Asserted with the widest name the shipped data carries, and against a column whose width is barely more
     * than the picture.
     */
    @Test
    void aLongSkillNameIsTruncatedInsideItsOwnColumn() {
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT, cols(1, 1, 1));
        for (int index = 0; index < grid.size(); index++) {
            BookLayout.Box name = grid.nameBox(index);
            BookLayout.Box icon = grid.nameIcon(index);
            assertTrue(name.right() <= grid.cells().get(index).right(),
                    "a name longer than its box is cut by the box, not drawn past the column");
            assertTrue(name.width() < grid.columnWidth(),
                    "the picture and the gap really do cost the name some width");
            assertEquals(name.x(), icon.right() + COMPACT.iconGap(), "the gap is the tier's, not a literal");
        }
        // A column narrower than the picture: still a box, still inside the column, never negative.
        BookLayout.Box narrow = new BookLayout.Box(10, 10, 8, COMPACT.columnHeight(1));
        SkillDetailLayout.Grid squeezed = SkillDetailLayout.grid(narrow, COMPACT, cols(1));
        BookLayout.Box icon = squeezed.nameIcon(0);
        assertTrue(icon.right() <= narrow.right(), "the picture is clamped to the column");
        assertTrue(squeezed.nameBox(0).width() >= 0, "and the name box is never negative");
    }

    /**
     * <b>No picture slot is left in an effect row.</b>
     *
     * <p>One picture per skill means one picture per <em>column</em>: a per-row slot would be a place for the
     * per-effect icons the human removed (「通用效果现在不需要图标」). The label owns the row's full width up to the
     * tier strip, which is what {@code everyEffectRowStaysInsideItsColumn} already asserts; this one states
     * the rule that produced it, so a future "put the icon beside each effect" refactor fails here first.
     */
    @Test
    void anEffectRowHasNoPictureSlotOfItsOwn() {
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT, cols(3, 2));
        for (int index = 0; index < grid.size(); index++) {
            BookLayout.Box row = grid.effectRow(index, 0);
            BookLayout.Box label = grid.effectLabel(index, 0);
            assertNotNull(row);
            assertNotNull(label);
            assertEquals(row.x(), label.x(),
                    "the label starts at the row's own left edge — there is no icon inset in an effect row");
            assertEquals(row.height(), COMPACT.effectRowHeight(),
                    "a row is a font line tall, not an icon's square");
        }
    }

    /**
     * <b>佩可莉姆's real page still fits the compact body after the picture made the name row taller.</b>
     *
     * <p>Adding a 14-pixel picture beside each name grows every column by 4 pixels (the name row went 10 → 14),
     * and a page that used to fit without scrolling but no longer does would be a real regression the
     * arithmetic can see: the wheel would become the only way to reach her last column. So the fit is asserted
     * on her real data, at the tier the page is most often opened on.
     */
    @Test
    void pecorinesPageStillFitsTheCompactBodyWithoutScrolling() throws Exception {
        CharacterDef pecorine = load("pecorine");
        for (int tier = 1; tier <= 3; tier++) {
            SkillDetailModel model = SkillDetailModel.of(pecorine, tier, 30);
            SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT, model.columns());
            assertTrue(grid.contentHeight() <= grid.area().height(),
                    "tier " + tier + ": her " + grid.size() + " columns are " + grid.contentHeight()
                            + "px of content in a " + grid.area().height() + "px body, so the last column "
                            + "is only reachable by scrolling");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Against the shipped data
    // ---------------------------------------------------------------------------------------

    /**
     * <b>佩可莉姆's real page fits the compact panel — ultimate included.</b>
     *
     * <p>The synthetic grids above pin the arithmetic; this one pins that the arithmetic is being fed a real
     * character, and that her ultimate is one of the columns. Her data is the case the page exists for —
     * 「角色册界面的4技能，满了不显示」 — and the ultimate is the column a player looks at first.
     */
    @Test
    void pecorinesColumnsFitTheCompactBody() throws Exception {
        CharacterDef pecorine = load("pecorine");
        BookLayout.Box body = compactBody();
        SkillDetailModel model = SkillDetailModel.of(pecorine, 2, 30);
        assertFalse(model.isEmpty(), "sanity: 佩可莉姆 has skills to show");
        assertTrue(model.entry(0).ultimate(), "her ultimate is the first column");
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(body, COMPACT, model.columns());
        assertEquals(model.size(), grid.size());
        assertEquals(3, grid.columnsPerRow());
        assertNoOverlap(grid.cells());
        assertInsideBody(grid);
        for (int index = 0; index < grid.size(); index++) {
            assertNotNull(grid.column(index));
            assertEquals(model.entry(index).name(), grid.column(index).title(),
                    "the grid's column " + index + " is the model's entry " + index);
            assertFalse(model.entry(index).rows().isEmpty(),
                    model.entry(index).name() + " would be a column with no rows");
            assertNotNull(grid.effectRow(index, 0));
        }
    }

    /**
     * <b>The ultimate's extra column does not disturb the wrap or the hit test.</b>
     *
     * <p>Adding a column to the page is the one thing that could have broken the geometry, so it is asserted
     * rather than assumed: six columns still make two lines of three, the fourth starts a new line, and every
     * column's centre still hits itself — the ultimate's included.
     */
    @Test
    void theLayoutStillWrapsAndHitTestsWithTheUltimateColumn() {
        List<SkillDetailLayout.Column> columns = cols(4, 2, 2, 1, 3, 2);
        SkillDetailLayout.Grid grid = SkillDetailLayout.grid(compactBody(), COMPACT, columns);
        assertEquals(6, grid.size());
        assertEquals(3, grid.columnsPerRow());
        assertEquals(grid.cells().get(0).x(), grid.cells().get(3).x(),
                "with the ultimate present the fourth column still wraps to the left edge");
        assertTrue(grid.cells().get(3).y() > grid.cells().get(0).bottom());
        assertEquals(List.of("c0", "c1", "c2", "c3", "c4", "c5"), grid.titles());
        assertNoOverlap(grid.cells());
        for (int index = 0; index < grid.size(); index++) {
            BookLayout.Box cell = grid.cells().get(index);
            assertEquals(OptionalInt.of(index), grid.entryAt(cell.centreX(), cell.centreY()),
                    "column " + index + "'s centre must hit column " + index);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * {@return one column per given row count, titled {@code c0..cn}}
     *
     * <p>The layout only cares about a column's title and its row count, which is the whole point of
     * {@link SkillDetailLayout.Column}: the arithmetic does not need to know whether a row came from an effect
     * hook or from an ultimate.
     */
    private static List<SkillDetailLayout.Column> cols(Integer... rows) {
        List<SkillDetailLayout.Column> columns = new ArrayList<>(rows.length);
        for (int index = 0; index < rows.length; index++) {
            columns.add(new SkillDetailLayout.Column("c" + index, rows[index]));
        }
        return columns;
    }

    private static void assertNoOverlap(List<BookLayout.Box> cells) {
        for (int a = 0; a < cells.size(); a++) {
            for (int b = a + 1; b < cells.size(); b++) {
                BookLayout.Box one = cells.get(a);
                BookLayout.Box two = cells.get(b);
                boolean overlap = one.x() < two.right() && two.x() < one.right()
                        && one.y() < two.bottom() && two.y() < one.bottom();
                assertFalse(overlap, "columns " + a + " and " + b + " overlap: " + one + " / " + two);
            }
        }
    }

    private static void assertInsideBody(SkillDetailLayout.Grid grid) {
        BookLayout.Box area = grid.area();
        for (BookLayout.Box cell : new ArrayList<>(grid.cells())) {
            assertTrue(cell.x() >= area.x() && cell.right() <= area.right(),
                    "a column must stay inside the body horizontally: " + cell + " in " + area);
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = SkillDetailLayoutTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }
}
