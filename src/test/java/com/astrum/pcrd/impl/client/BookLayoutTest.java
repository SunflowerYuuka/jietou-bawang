package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 角色册 screen's geometry, asserted instead of looked at.
 *
 * <h2>Why this file exists at all</h2>
 * This project cannot take screenshots, so a layout claim that is not an assertion stays a guess. Every
 * property the screen relies on is therefore arithmetic here:
 *
 * <ul>
 *   <li>hit-testing is the <b>inverse</b> of layout — a click at a cell's centre must select that cell,
 *       for every cell on the page, and a click off the cells must select nothing;</li>
 *   <li>cells tile without overlapping, so no pixel belongs to two characters;</li>
 *   <li>the panel and every part of it stay on screen at the GUI scales players actually use, including
 *       a deliberately small one;</li>
 *   <li>the growth bars and the slot row live inside their columns, not on top of each other.</li>
 * </ul>
 *
 * <p>Sizes chosen as the two extremes: 320x240 is the smallest common effective resolution at GUI scale
 * 2 on a 640x480 window, and 1920x1080 is the largest anyone runs.
 */
class BookLayoutTest {

    private static final int SMALL_WIDTH = 320;
    private static final int SMALL_HEIGHT = 240;
    private static final int LARGE_WIDTH = 1920;
    private static final int LARGE_HEIGHT = 1080;

    private static BookLayout small() {
        return BookLayout.at(SMALL_WIDTH, SMALL_HEIGHT);
    }

    private static BookLayout large() {
        return BookLayout.at(LARGE_WIDTH, LARGE_HEIGHT);
    }

    // ---------------------------------------------------------------------------------------
    // The panel
    // ---------------------------------------------------------------------------------------

    @Test
    void thePanelFitsOnBothScreens() {
        assertPanelFits(BookLayout.at(SMALL_WIDTH, SMALL_HEIGHT), SMALL_WIDTH, SMALL_HEIGHT);
        assertPanelFits(BookLayout.at(LARGE_WIDTH, LARGE_HEIGHT), LARGE_WIDTH, LARGE_HEIGHT);
    }

    private static void assertPanelFits(BookLayout layout, int screenWidth, int screenHeight) {
        BookLayout.Box panel = layout.panel();
        assertTrue(panel.x() >= 0 && panel.y() >= 0, "the panel must not start off screen");
        assertTrue(panel.right() <= screenWidth,
                "the panel must fit " + screenWidth + "px wide, ends at " + panel.right());
        assertTrue(panel.bottom() <= screenHeight,
                "the panel must fit " + screenHeight + "px tall, ends at " + panel.bottom());
        assertTrue(panel.width() > 0 && panel.height() > 0);
    }

    @Test
    void thePanelIsCentredOnAScreenBigEnoughForIt() {
        BookLayout layout = large();
        BookLayout.Box panel = layout.panel();
        // Ask the INSTANCE for its width, not the class constant: BookLayout picks a metrics tier
        // from the screen, so the constant is only the compact tier's value. Deriving the expectation
        // from PANEL_WIDTH made this test fail the moment a wider tier was introduced, while the
        // centring itself was already correct.
        int expectedX = (LARGE_WIDTH - panel.width()) / 2;
        int expectedY = (LARGE_HEIGHT - panel.height()) / 2;
        assertEquals(expectedX, panel.x());
        assertEquals(expectedY, panel.y());
    }

    @Test
    void aScreenTooSmallForThePanelClampsInsteadOfGoingNegative() {
        BookLayout layout = BookLayout.at(100, 80);
        BookLayout.Box panel = layout.panel();
        assertEquals(0, panel.x());
        assertEquals(0, panel.y());
        assertTrue(panel.width() > 0 && panel.height() > 0, "still a drawable panel, just a cramped one");
    }

    @Test
    void everyPartOfThePanelIsInsideIt() {
        BookLayout layout = small();
        BookLayout.Box panel = layout.panel();
        List<BookLayout.Box> parts = List.of(layout.title(), layout.divider(), layout.rosterGrid(),
                layout.partyHeading(), layout.partyRow(), layout.growthHeading(), layout.levelLine(),
                layout.expBar(), layout.affectionLine(), layout.affectionBar(),
                layout.nextTierLine(), layout.feedEntry(), layout.skillsHeading(),
                layout.hookRow(0), layout.hookRow(1), layout.hookRow(2));
        for (BookLayout.Box part : parts) {
            assertTrue(part.x() >= panel.x() && part.right() <= panel.right(),
                    "a part sticks out horizontally: " + part);
            assertTrue(part.y() >= panel.y() && part.bottom() <= panel.bottom(),
                    "a part sticks out vertically: " + part);
        }
        BookLayout.Box footer = layout.refusalLine();
        assertTrue(footer.bottom() <= panel.bottom(), "the footer must stay inside the panel");
        assertTrue(footer.y() >= layout.rosterGrid().bottom(),
                "the footer must sit below the body, not on top of it");
    }

    /**
     * The ornament sits between the title and the body, full body width, and <b>no content box overlaps
     * it</b>.
     *
     * <p>This is the whole point of the divider row: it replaced a one-pixel flat line, and it is 16px
     * tall, so every content box moved down by 16+2·gap. A layout that kept the old {@code bodyTop} would
     * draw the roster grid on top of the ornament — visible as the art's centre jewel behind the first
     * row of cells.
     */
    @Test
    void theDividerSeparatesTheTitleFromTheBodyWithoutOverlappingAnything() {
        BookLayout layout = small();
        BookLayout.Box divider = layout.divider();
        BookLayout.Box title = layout.title();
        assertEquals(title.x(), divider.x(), "title and ornament align on the left");
        assertEquals(title.width(), divider.width(), "and are the same width");
        assertEquals(BookLayout.DIVIDER_HEIGHT, divider.height(),
                "the box is the art's natural height, so the ornament is not squashed");
        assertTrue(divider.y() >= title.bottom(), "the ornament sits below the title");
        assertTrue(divider.bottom() <= layout.rosterGrid().y(),
                "the body starts below the ornament, not under it");
        assertTrue(divider.bottom() <= layout.partyHeading().y());
        for (int i = 0; i < BookLayout.PAGE; i++) {
            assertTrue(layout.cell(i).y() >= divider.bottom(), "cell " + i + " overlaps the ornament");
        }
    }

    /**
     * The inner padding is wider than the frame band the nine-slice draws, <b>per tier</b>.
     *
     * <p>The two numbers are independent constants — {@link BookLayout#slice()} decides how thick the
     * drawn ornament is, the tier's {@code padding} how far the content is inset — and if the padding is
     * the smaller of the two the title and the footer sit <em>on</em> the frame. Nothing crashes; it just
     * looks wrong, so the relation is asserted here, next to the constants it constrains, and asserted
     * for <b>both</b> tiers because the slice is now a per-tier number: the comfortable panel draws a
     * 30-pixel frame (the ornament was being compressed 3.6× at 18) while the compact one keeps 18.
     */
    @Test
    void theContentPaddingClearsTheNineSliceFrame() {
        for (BookLayout.Metrics metrics : new BookLayout.Metrics[] {
                BookLayout.Metrics.COMPACT, BookLayout.Metrics.COMFORTABLE}) {
            assertTrue(metrics.padding() > metrics.slice(),
                    "padding (" + metrics.padding() + ") must exceed the panel's slice ("
                            + metrics.slice() + "), or content is drawn on the frame");
            assertTrue(metrics.padding() - metrics.slice() >= 2,
                    "at least two spare pixels, or the first text row touches the ornament");
        }
        // The shipped slice constants must agree with the tiers that use them, or the screen would draw
        // a frame the test never checked.
        assertEquals(BookLayout.Metrics.COMPACT.slice(), BookLayout.sliceFor(BookLayout.Metrics.COMPACT),
                "the compact tier's own slice is the one the layout reports");
        assertEquals(NineSlice.SHIPPED_SLICE, BookLayout.sliceFor(BookLayout.Metrics.COMFORTABLE),
                "the comfortable tier draws the shipped frame thickness");
    }

    /**
     * The frame is drawn at a <b>readable scale</b>: the frame cell is never reduced below 1:1, and the
     * comfortable tier magnifies it by less than 2.5x.
     *
     * <p>This test is the same rule as before, re-expressed for the official art. It used to bound a
     * <em>compression</em> ratio, because the frame was nine 64-pixel tiles squeezed into a 30-pixel slice and
     * anything past ~2.5x blurred its 8-pixel filigree band into the body — the human's 「UI很粗糙，上下像是被压缩了」.
     * The frame is now nine 16-pixel cells of the game's own rounded frame
     * ({@code fx_ui_gachabutton_frame_tex.png}, sliced by {@code tools/gen_book_panel_texture.py}), so the same
     * slice <em>magnifies</em> it instead. Both directions are bounded: shrinking the cell blurs its one-pixel
     * stroke, and magnifying it past 2.5x turns that stroke into a fat band.
     *
     * <h2>Why the compact tier is exempt, on purpose</h2>
     * {@link BookLayout.Metrics#COMPACT} exists for windows that cannot fit the comfortable panel at all (it is
     * chosen when the comfortable panel would not fit with {@code MIN_MARGIN} to spare), and there the <b>body</b>
     * is worth more than the frame: a 30-pixel slice plus 30 of padding makes a panel that no longer fits the
     * 320x240 screen this tier exists for. So the compact tier draws a thinner frame, and the comfortable one —
     * the shipped look — is held to the rule. Stating the exemption here is what keeps it a decision rather than
     * an oversight.
     */
    @Test
    void theFrameIsDrawnAtAReadableScale() {
        double comfortable = NineSlice.SHIPPED_SLICE / (double) NineSlice.PIECE_PIXELS;
        assertTrue(comfortable <= 2.5,
                "the " + NineSlice.SHIPPED_SLICE + "-pixel slice magnifies the " + NineSlice.PIECE_PIXELS
                        + "-pixel frame cell " + String.format("%.2f", comfortable)
                        + "x, which turns its one-pixel stroke into a band");
        assertTrue(comfortable >= 1.0,
                "a slice smaller than one cell (" + String.format("%.2f", comfortable)
                        + "x) shrinks the art and blurs the stroke");

        // The comfortable tier really uses it, and the compact tier really is the thinner one.
        assertEquals(NineSlice.SHIPPED_SLICE, BookLayout.Metrics.COMFORTABLE.slice(),
                "the comfortable tier is the one the human sees: it must draw the shipped frame");
        assertTrue(BookLayout.Metrics.COMPACT.slice() < NineSlice.SHIPPED_SLICE,
                "the compact tier deliberately keeps the thinner frame, for a smaller panel");
        assertTrue(BookLayout.Metrics.COMPACT.slice() >= NineSlice.PIECE_PIXELS,
                "but never below one whole cell: " + BookLayout.Metrics.COMPACT.slice());
    }

    /**
     * The panel the layout asks for has a drawable nine-slice body at every GUI scale players use.
     *
     * <p>The panel size is fixed, so this is really an assertion about the slice: a slice larger than half
     * the panel would leave no centre. It is the one link between the layout constants and the drawing
     * helper, and it is arithmetic, so it belongs in a test rather than in a log line. Both tiers are
     * checked, and the compact one is checked at the smallest screen the screen supports — that is
     * where a raised slice would bite first.
     */
    @Test
    void thePanelHasADrawableNineSliceBody() {
        for (BookLayout layout : new BookLayout[] {small(), large()}) {
            int slice = layout.slice();
            NineSlice.Rects rects = NineSlice.rects(layout.panel(), slice);
            assertTrue(rects.centreIsDrawable(),
                    "the " + layout.tierName() + " panel's body must be drawable at slice " + slice
                            + ": " + rects.centre());
            assertTrue(rects.centre().width() > layout.metrics().gridWidth(),
                    "the body must be wider than the roster grid, or the grid is drawn off the panel");
            assertTrue(rects.centre().height() >= layout.metrics().bodyHeight(),
                    "the body must be at least as tall as the roster grid");
        }
    }

    @Test
    void theRightColumnDoesNotOverlapTheRosterGrid() {
        BookLayout layout = small();
        assertTrue(layout.rosterGrid().right() <= layout.partyHeading().x(),
                "the party column starts after the grid ends");
        assertTrue(layout.feedEntry().bottom() <= layout.rosterGrid().bottom(),
                "the right column must fit inside the body the grid defines");
    }

    /**
     * The skills strip is the free space the experience line used to take: heading + three rows must
     * end inside the body, above the footer.
     *
     * <p>Arithmetic, not taste: the panel is 227px tall at the GUI scale the human plays at, so a row
     * that runs one pixel past the body is content drawn on the footer, and that is the failure this
     * pins.
     */
    @Test
    void theSkillsStripFitsUnderTheFeedEntryInsideTheBody() {
        BookLayout layout = small();
        BookLayout.Box heading = layout.skillsHeading();
        assertTrue(layout.feedEntry().bottom() <= heading.y(), "the strip starts under the feed entry");
        assertTrue(heading.bottom() <= layout.refusalLine().y(), "the heading stays above the footer");
        for (int i = 0; i < BookLayout.HOOK_ROWS; i++) {
            BookLayout.Box row = layout.hookRow(i);
            assertNotNull(row, "row " + i + " is within the row budget");
            assertEquals(BookLayout.HOOK_ROW_HEIGHT, row.height());
            assertTrue(row.y() >= heading.bottom(), "row " + i + " is under the heading");
            assertTrue(row.bottom() <= layout.refusalLine().y(), "row " + i + " stays inside the body");
            assertEquals(layout.partyHeading().x(), row.x(), "rows share the right column");
            assertEquals(layout.partyHeading().width(), row.width());
        }
        assertNull(layout.hookRow(BookLayout.HOOK_ROWS), "there is no row past the budget");
        assertNull(layout.hookRow(-1));
    }

    /** Rows tile downward without overlapping each other or the parts above them. */
    @Test
    void theHookRowsDoNotOverlapEachOther() {
        BookLayout layout = small();
        BookLayout.Box previous = layout.skillsHeading();
        for (int i = 0; i < BookLayout.HOOK_ROWS; i++) {
            BookLayout.Box row = layout.hookRow(i);
            assertFalse(overlaps(previous, row), "row " + i + " overlaps the row above it");
            previous = row;
        }
        assertTrue(previous.bottom() <= layout.rosterGrid().bottom(),
                "the last row stays inside the body the grid defines");
    }

    @Test
    void theGrowthPartsAreStackedInOrder() {
        BookLayout layout = small();
        assertTrue(layout.partyHeading().bottom() <= layout.partyRow().y());
        assertTrue(layout.partyRow().bottom() <= layout.growthHeading().y());
        assertTrue(layout.growthHeading().bottom() <= layout.levelLine().y());
        assertTrue(layout.levelLine().bottom() <= layout.expBar().y());
        assertTrue(layout.expBar().bottom() <= layout.affectionLine().y());
        assertTrue(layout.affectionLine().bottom() <= layout.affectionBar().y());
        assertTrue(layout.affectionBar().bottom() <= layout.nextTierLine().y());
        assertTrue(layout.nextTierLine().bottom() <= layout.feedEntry().y());
    }

    // ---------------------------------------------------------------------------------------
    // The roster cells: tiling, hit-testing and paging
    // ---------------------------------------------------------------------------------------

    @Test
    void thePageHasExactlyTheCellsTheConstantsPromise() {
        assertEquals(BookLayout.PAGE, BookLayout.COLUMNS * BookLayout.ROWS);
        BookLayout layout = small();
        for (int i = 0; i < BookLayout.PAGE; i++) {
            assertNotNull(layout.cell(i), "cell " + i + " is on the page");
        }
        assertNull(layout.cell(BookLayout.PAGE), "there is no cell past the page");
        assertNull(layout.cell(-1));
    }

    @Test
    void cellsTileWithoutOverlapping() {
        BookLayout layout = small();
        for (int a = 0; a < BookLayout.PAGE; a++) {
            for (int b = a + 1; b < BookLayout.PAGE; b++) {
                assertFalse(overlaps(layout.cell(a), layout.cell(b)),
                        "cells " + a + " and " + b + " overlap");
            }
        }
    }

    @Test
    void cellsStayInsideTheGrid() {
        BookLayout layout = small();
        BookLayout.Box grid = layout.rosterGrid();
        for (int i = 0; i < BookLayout.PAGE; i++) {
            BookLayout.Box cell = layout.cell(i);
            assertTrue(cell.x() >= grid.x() && cell.right() <= grid.right(), "cell " + i + " escapes the grid horizontally");
            assertTrue(cell.y() >= grid.y() && cell.bottom() <= grid.bottom(), "cell " + i + " escapes the grid vertically");
        }
    }

    /**
     * The inverse property, asserted for every cell: a click on a cell's centre selects that same cell.
     *
     * <p>This is the assertion that would have caught the classic grid bug (a row/column mix-up in the
     * hit test) — the layout and the hit test are written separately and can disagree, which is exactly
     * what a click that selects the wrong character looks like.
     */
    @Test
    void clickingACellsCentreSelectsThatCell() {
        BookLayout layout = small();
        int count = BookLayout.PAGE;
        for (int i = 0; i < BookLayout.PAGE; i++) {
            BookLayout.Box cell = layout.cell(i);
            assertEquals(OptionalInt.of(i),
                    layout.rosterIndexAt(cell.centreX(), cell.centreY(), count, 0),
                    "the centre of cell " + i + " must hit cell " + i);
            assertEquals(OptionalInt.of(i),
                    layout.rosterIndexAt(cell.x() + 0.5, cell.y() + 0.5, count, 0),
                    "the top-left pixel of cell " + i + " belongs to cell " + i);
        }
    }

    @Test
    void clickingOutsideTheCellsSelectsNothing() {
        BookLayout layout = small();
        BookLayout.Box grid = layout.rosterGrid();
        assertTrue(layout.rosterIndexAt(grid.right() + 5, grid.centreY(), BookLayout.PAGE, 0).isEmpty(),
                "a click right of the grid is on the party column");
        assertTrue(layout.rosterIndexAt(grid.centreX(), grid.bottom() + 5, BookLayout.PAGE, 0).isEmpty());
        assertTrue(layout.rosterIndexAt(grid.x() - 2, grid.centreY(), BookLayout.PAGE, 0).isEmpty());
    }

    @Test
    void anEmptyRosterHasNoCellsToHit() {
        assertTrue(small().rosterIndexAt(small().rosterGrid().centreX(),
                small().rosterGrid().centreY(), 0, 0).isEmpty());
    }

    @Test
    void onlyAsManyCellsAsThereAreCharactersAreHittable() {
        BookLayout layout = small();
        BookLayout.Box third = layout.cell(2);
        assertTrue(layout.rosterIndexAt(third.centreX(), third.centreY(), 2, 0).isEmpty(),
                "the player owns two characters, so the third cell is empty and inert");
        assertEquals(2, layout.visibleCells(2, 0));
    }

    @Test
    void theFirstIndexNeverLeavesAGapAtTheEnd() {
        BookLayout layout = small();
        int total = BookLayout.PAGE + 3;
        assertEquals(0, layout.clampFirstIndex(total, 0));
        assertEquals(3, layout.clampFirstIndex(total, 3),
                "the window slides so the last page ends on the last character");
        assertEquals(3, layout.clampFirstIndex(total, 999));
        assertEquals(0, layout.clampFirstIndex(total, -4), "scrolling up at the top stays on the first page");
        assertEquals(0, layout.clampFirstIndex(2, 5), "a roster shorter than a page never scrolls");
    }

    @Test
    void hitTestingAccountsForThePagedWindow() {
        BookLayout layout = small();
        int total = BookLayout.PAGE + 2;
        int first = layout.clampFirstIndex(total, 2);
        BookLayout.Box firstCellOnScreen = layout.cell(0);
        assertEquals(OptionalInt.of(first),
                layout.rosterIndexAt(firstCellOnScreen.centreX(), firstCellOnScreen.centreY(), total, 2),
                "the cell drawn first carries the absolute index the window starts at");
    }

    // ---------------------------------------------------------------------------------------
    // The party slots
    // ---------------------------------------------------------------------------------------

    @Test
    void thereIsOneSlotPerPartySlotAndTheyTile() {
        BookLayout layout = small();
        assertEquals(Party.SLOTS, layout.slots().size());
        for (int i = 0; i < Party.SLOTS; i++) {
            BookLayout.Box box = layout.slot(i);
            assertNotNull(box);
            assertTrue(box.x() >= layout.partyRow().x() && box.right() <= layout.partyRow().right(),
                    "slot " + i + " must fit the row");
        }
        assertNull(layout.slot(Party.SLOTS));
        assertNull(layout.slot(-1));
        for (int a = 0; a < Party.SLOTS; a++) {
            for (int b = a + 1; b < Party.SLOTS; b++) {
                assertFalse(overlaps(layout.slot(a), layout.slot(b)),
                        "slots " + a + " and " + b + " overlap");
            }
        }
    }

    /**
     * The slot hit test, asserted per slot and against the wheel convention.
     *
     * <p>Slot index is the wheel position ({@code WheelSlots.slotForDigit(1) → 0}), so the first square
     * on screen must be slot 0 — a click on it places the character that key {@code 1} will cast.
     */
    @Test
    void clickingASlotCentreHitsThatSlot() {
        BookLayout layout = small();
        for (int i = 0; i < Party.SLOTS; i++) {
            BookLayout.Box box = layout.slot(i);
            assertEquals(OptionalInt.of(i), layout.slotAt(box.centreX(), box.centreY()));
        }
    }

    @Test
    void theFirstSlotIsTheFirstSquare() {
        BookLayout layout = small();
        assertEquals(layout.partyRow().x(), layout.slot(0).x(),
                "slot 0 is the leftmost square, matching wheel slot 0");
    }

    @Test
    void clickingOffTheSlotsHitsNoSlot() {
        BookLayout layout = small();
        BookLayout.Box row = layout.partyRow();
        assertTrue(layout.slotAt(row.right() + 4, row.centreY()).isEmpty());
        assertTrue(layout.slotAt(row.centreX(), row.bottom() + 4).isEmpty());
        assertTrue(layout.slotAt(layout.rosterGrid().centreX(), layout.rosterGrid().centreY()).isEmpty(),
                "the roster grid is not a party slot");
    }

    // ---------------------------------------------------------------------------------------
    // Bars
    // ---------------------------------------------------------------------------------------

    @Test
    void theBarsSpanTheirColumnAndSitInsideIt() {
        BookLayout layout = small();
        BookLayout.Box exp = layout.expBar();
        BookLayout.Box affection = layout.affectionBar();
        assertEquals(exp.x(), affection.x());
        assertEquals(exp.width(), affection.width());
        assertEquals(exp.right(), layout.levelLine().right());
        assertEquals(BookLayout.BAR_HEIGHT, exp.height());
        assertEquals(BookLayout.BAR_HEIGHT, affection.height());
    }

    private static boolean overlaps(BookLayout.Box a, BookLayout.Box b) {
        return a.x() < b.right() && b.x() < a.right() && a.y() < b.bottom() && b.y() < a.bottom();
    }

    // ---------------------------------------------------------------------------------------
    // The skill strip, and the page behind it
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The 「技能详情」 button occupies exactly the strip the skill list used to fill.</b>
     *
     * <p>That is the whole geometry of the change: the heading row and its hook rows became one control, so the
     * button starts where the heading did and ends where the last row did. No other metric moved, which is the
     * reason the rest of this file is untouched.
     */
    @Test
    void theSkillEntryButtonIsTheOldSkillStrip() {
        for (BookLayout layout : List.of(small(), large())) {
            BookLayout.Box button = layout.skillsEntry();
            BookLayout.Box heading = layout.skillsHeading();
            BookLayout.Box lastRow = layout.hookRow(layout.hookRows() - 1);
            assertEquals(heading.x(), button.x());
            assertEquals(heading.y(), button.y(), layout.tierName() + ": the button starts at the heading");
            assertEquals(heading.width(), button.width());
            assertNotNull(lastRow);
            assertEquals(lastRow.bottom(), button.bottom(),
                    layout.tierName() + ": the button ends at the last hook row");
            assertTrue(button.height() > BookLayout.LINE_HEIGHT, "it is taller than one line, or nobody hits it");
            assertTrue(layout.panel().contains(button.x(), button.y())
                            && button.right() <= layout.panel().right()
                            && button.bottom() <= layout.panel().bottom(),
                    layout.tierName() + ": the button must stay inside the panel");
            assertFalse(overlaps(button, layout.feedEntry()),
                    layout.tierName() + ": the button must not cover the feeding entry point");
        }
    }

    /**
     * <b>The detail page gets the whole body</b>, and the footer line below it stays free.
     *
     * <p>The page is a mode of this screen, so it may use the rectangles the roster, the slots and the growth
     * column give up while it is open — but not the footer, which carries the way back ({@code Esc}) and must
     * stay visible while the grid scrolls under it.
     */
    @Test
    void theDetailPageGetsTheWholeBodyAndNotTheFooter() {
        for (BookLayout layout : List.of(small(), large())) {
            BookLayout.Box body = layout.skillDetailBody();
            assertEquals(layout.rosterGrid().x(), body.x(), layout.tierName() + ": the body starts at the left");
            assertEquals(layout.rosterGrid().y(), body.y(), "and at the top, under the ornament");
            assertEquals(layout.bodyWidth(), body.width(), "and spans both columns");
            assertTrue(body.right() <= layout.panel().right() - layout.metrics().padding(),
                    "the body must not run under the frame");
            assertTrue(body.bottom() <= layout.refusalLine().y(),
                    "the footer line must stay below the scrollable body");
            assertTrue(body.height() > 0 && body.width() > 0);
            assertFalse(overlaps(body, layout.refusalLine()), "the page must not cover the footer");
        }
    }

    /**
     * <b>The 「技能详情」 button is a label and a chevron — and it wears no picture.</b>
     *
     * <p>「技能详情按钮不要显示技能图标，使用角色册同风格的按钮配色就行。」 So the button's label starts at the
     * button's own left inset (no reserved picture slot pushing it right), is one font line tall and vertically
     * inside the button, and stops before the chevron strip at the right end. Both rectangles come from
     * {@link BookLayout}, which is the point — the render method used to hard-code {@code box.x() + 5}, then a
     * picture slot, and now goes back to the layout box.
     */
    @Test
    void theSkillEntryButtonIsALabelAndAChevronWithNoPicture() {
        for (BookLayout layout : List.of(small(), large())) {
            BookLayout.Box button = layout.skillsEntry();
            BookLayout.Box label = layout.skillsEntryLabel();
            String tier = layout.tierName();
            assertEquals(BookLayout.LINE_HEIGHT, label.height(), tier + ": the label is one font line");
            assertTrue(label.width() > 0, tier + ": the label must have room to be drawn at all");
            assertTrue(label.x() >= button.x(), tier + ": the label stays inside the button on the left");
            assertTrue(label.y() >= button.y() && label.bottom() <= button.bottom(),
                    tier + ": the label stays inside the button vertically");
            assertTrue(label.right() < button.right(), tier + ": and clear of the right edge for the chevron");
            // No picture slot: the label begins at the button's own inset, not after a square reserved for
            // skill art. A picture box that merely became zero-width would leave the label where it was.
            assertTrue(label.x() - button.x() <= 8,
                    tier + ": the label starts at the button's own inset, not after a picture slot: " + label);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The front page: five portraits, two buttons, the growth information
    // ---------------------------------------------------------------------------------------

    /**
     * <b>The five portraits are equal squares in one row, inside the body.</b>
     *
     * <p>「一排5个角色横着摆」 is the whole top of the page, and it is arithmetic: five squares of one edge, four
     * gaps, all of it inside the body. Asserted for both tiers because the body is a different size in each —
     * a row that fits only when the window is big is a row that is cropped for somebody.
     */
    @Test
    void theFrontPageHasFiveEqualPortraitsInsideTheBody() {
        for (BookLayout layout : List.of(small(), large())) {
            String tier = layout.tierName();
            BookLayout.Box body = new BookLayout.Box(layout.rosterGrid().x(), layout.rosterGrid().y(),
                    layout.bodyWidth(), layout.metrics().bodyHeight());
            assertEquals(Party.SLOTS, layout.boardSlots().size(), tier + ": one box per party slot");
            int edge = layout.boardSlotSize();
            assertTrue(edge > 0, tier + ": a portrait must have a size");
            for (int i = 0; i < Party.SLOTS; i++) {
                BookLayout.Box slot = layout.boardSlot(i);
                assertNotNull(slot, tier + ": slot " + i + " exists");
                assertEquals(edge, slot.width(), tier + ": slot " + i + " is the row's edge");
                assertEquals(edge, slot.height(), tier + ": slot " + i + " is square");
                assertTrue(slot.x() >= body.x() && slot.right() <= body.right(),
                        tier + ": slot " + i + " escapes the body horizontally: " + slot);
                assertTrue(slot.y() >= body.y() && slot.bottom() <= body.bottom(),
                        tier + ": slot " + i + " escapes the body vertically: " + slot);
            }
            assertNull(layout.boardSlot(Party.SLOTS), tier + ": there is no sixth portrait");
            assertNull(layout.boardSlot(-1));
            // They tile left to right without overlapping, which is what "one row" means in arithmetic.
            for (int a = 0; a < Party.SLOTS; a++) {
                for (int b = a + 1; b < Party.SLOTS; b++) {
                    assertFalse(overlaps(layout.boardSlot(a), layout.boardSlot(b)),
                            tier + ": portraits " + a + " and " + b + " overlap");
                }
            }
            // Left to right, in slot order: the wheel convention the party row already keeps.
            for (int i = 1; i < Party.SLOTS; i++) {
                assertTrue(layout.boardSlot(i).x() > layout.boardSlot(i - 1).right(),
                        tier + ": portrait " + i + " is right of portrait " + (i - 1));
            }
            assertEquals(layout.boardRow().x(), layout.boardSlot(0).x(),
                    tier + ": the row starts at the first portrait");
        }
    }

    /** A click on a portrait's centre selects that slot — the inverse of {@link BookLayout#boardSlot}. */
    @Test
    void clickingAFrontPagePortraitHitsThatSlot() {
        for (BookLayout layout : List.of(small(), large())) {
            for (int i = 0; i < Party.SLOTS; i++) {
                BookLayout.Box slot = layout.boardSlot(i);
                assertEquals(OptionalInt.of(i), layout.boardSlotAt(slot.centreX(), slot.centreY()),
                        layout.tierName() + ": the centre of portrait " + i + " must hit slot " + i);
                assertEquals(OptionalInt.of(i), layout.boardSlotAt(slot.x() + 0.5, slot.y() + 0.5));
            }
            BookLayout.Box row = layout.boardRow();
            assertTrue(layout.boardSlotAt(row.centreX(), row.bottom() + 4).isEmpty(),
                    layout.tierName() + ": below the row is not a portrait");
        }
    }

    /**
     * <b>The two buttons do not overlap the growth panel</b>, and they sit under the portrait row.
     *
     * <p>This is the page's structural claim: left half buttons, right half growth, one band. Asserted for both
     * tiers, because the width of each half is derived from the tier's own {@code rightWidth}.
     */
    @Test
    void theFrontPageButtonsClearTheGrowthPanel() {
        for (BookLayout layout : List.of(small(), large())) {
            String tier = layout.tierName();
            BookLayout.Box party = layout.boardPartyButton();
            BookLayout.Box skills = layout.boardSkillsButton();
            BookLayout.Box growth = layout.boardGrowthPanel();
            assertFalse(overlaps(party, growth), tier + ": 角色编队 must not reach the growth panel");
            assertFalse(overlaps(skills, growth), tier + ": 技能详情 must not reach the growth panel");
            assertFalse(overlaps(party, skills), tier + ": the two buttons must not overlap each other");
            assertTrue(party.right() <= skills.x(), tier + ": 角色编队 is the leftmost button");
            assertTrue(skills.right() <= growth.x(), tier + ": both buttons are left of the growth panel");
            assertTrue(party.width() > 0 && skills.width() > 0,
                    tier + ": a button with no width cannot be hit: " + party + " " + skills);
            assertEquals(party.width(), skills.width(), tier + ": the pair is equal width");
            assertEquals(party.y(), skills.y(), tier + ": the pair shares a baseline");
            assertEquals(party.y(), growth.y(), tier + ": the band starts on one line");
            // Under the portrait row, which is what "角色行下面" says.
            assertTrue(party.y() >= layout.boardRow().bottom(),
                    tier + ": the buttons are under the portrait row");
            assertTrue(growth.y() >= layout.boardRow().bottom(),
                    tier + ": the growth panel is under the portrait row");
            assertTrue(party.height() >= BookLayout.LINE_HEIGHT,
                    tier + ": a button shorter than a line has nowhere to write its label");
        }
    }

    /**
     * <b>The growth panel is inside the body</b> and holds a complete seven-box growth view.
     *
     * <p>The panel is the front page's half of the band; the seven boxes inside it come from the same
     * {@code GrowthColumn} record the roster page uses, so this test pins both the panel and the stack:
     * heading, level, bar, affection, bar, next tier, feeding entry — all inside, in that order.
     */
    @Test
    void theFrontPageGrowthPanelIsInsideTheBodyAndStacked() {
        for (BookLayout layout : List.of(small(), large())) {
            String tier = layout.tierName();
            BookLayout.Box body = new BookLayout.Box(layout.rosterGrid().x(), layout.rosterGrid().y(),
                    layout.bodyWidth(), layout.metrics().bodyHeight());
            BookLayout.Box panel = layout.boardGrowthPanel();
            assertTrue(panel.x() >= body.x() && panel.right() <= body.right(),
                    tier + ": the growth panel escapes the body horizontally: " + panel);
            assertTrue(panel.bottom() <= body.bottom(),
                    tier + ": the growth panel escapes the body vertically: " + panel
                            + " vs body bottom " + body.bottom());
            assertEquals(layout.metrics().rightWidth(), panel.width(),
                    tier + ": it keeps the column width the growth view was designed at");

            BookLayout.GrowthColumn stack = layout.boardGrowthColumn();
            List<BookLayout.Box> boxes = List.of(stack.heading(), stack.levelLine(), stack.expBar(),
                    stack.affectionLine(), stack.affectionBar(), stack.nextTierLine(), stack.feedEntry());
            BookLayout.Box previous = null;
            for (int i = 0; i < boxes.size(); i++) {
                BookLayout.Box box = boxes.get(i);
                assertTrue(box.x() >= panel.x() && box.right() <= panel.right(),
                        tier + ": growth box " + i + " escapes the panel horizontally: " + box);
                assertTrue(box.y() >= panel.y() && box.bottom() <= panel.bottom(),
                        tier + ": growth box " + i + " escapes the panel: " + box + " vs " + panel);
                assertEquals(panel.x(), box.x(), tier + ": growth box " + i + " starts at the column's left");
                assertEquals(panel.width(), box.width(), tier + ": growth box " + i + " spans the column");
                if (previous != null) {
                    assertTrue(previous.bottom() <= box.y(),
                            tier + ": growth box " + i + " is not below box " + (i - 1));
                }
                previous = box;
            }
        }
    }

    /**
     * <b>The front page's boxes are sane on their own.</b>
     *
     * <p>Deliberately <b>not</b> a claim that the front page's rectangles are disjoint from the roster page's:
     * the two pages are modes of one panel, and a mode is drawn <em>instead of</em> the other page, so the
     * front page legitimately occupies the body the roster grid also occupies — the same relationship the
     * skill-detail page has to the grid (see {@link #theDetailPageGetsTheWholeBodyAndNotTheFooter}). Asserting
     * across the two pages would be asserting that one of them cannot be drawn at all.
     *
     * <p>What <em>is</em> arithmetic, and what this test asserts, is that the front page is coherent: every box
     * is non-degenerate, every box is inside the panel it is drawn in, and no two front-page boxes overlap. That
     * last one matters because these boxes are hit-tested — an overlap would make a click ambiguous between a
     * portrait and a button.
     */
    @Test
    void theFrontPageBoxesAreSaneAndDoNotOverlapEachOther() {
        for (BookLayout layout : List.of(small(), large())) {
            String tier = layout.tierName();
            List<BookLayout.Box> board = new java.util.ArrayList<>(layout.boardSlots());
            board.add(layout.boardPartyButton());
            board.add(layout.boardSkillsButton());
            board.add(layout.boardGrowthPanel());
            for (BookLayout.Box box : board) {
                assertTrue(box.width() > 0 && box.height() > 0,
                        tier + ": a front-page box is degenerate: " + box);
                assertTrue(box.x() >= layout.panel().x() && box.right() <= layout.panel().right(),
                        tier + ": a front-page box escapes the panel horizontally: " + box);
                assertTrue(box.y() >= layout.panel().y() && box.bottom() <= layout.panel().bottom(),
                        tier + ": a front-page box escapes the panel vertically: " + box);
                assertFalse(overlaps(box, layout.refusalLine()),
                        tier + ": a front-page box covers the footer line: " + box);
            }
            for (int a = 0; a < board.size(); a++) {
                for (int b = a + 1; b < board.size(); b++) {
                    assertFalse(overlaps(board.get(a), board.get(b)),
                            tier + ": front-page boxes " + a + " and " + b + " overlap: "
                                    + board.get(a) + " / " + board.get(b));
                }
            }
        }
    }

    /**
     * The two front-page buttons carry a label box that fits inside them, one font line tall.
     *
     * <p>The label is centred by the renderer inside this box, so the box is what has to be sane: inside the
     * button on both axes and narrower than it, or a centred long word would start outside the button.
     */
    @Test
    void theFrontPageButtonLabelsFitTheirButtons() {
        for (BookLayout layout : List.of(small(), large())) {
            String tier = layout.tierName();
            for (BookLayout.Box button : List.of(layout.boardPartyButton(), layout.boardSkillsButton())) {
                BookLayout.Box label = layout.boardButtonLabel(button);
                assertEquals(BookLayout.LINE_HEIGHT, label.height(), tier + ": one font line");
                assertTrue(label.width() > 0, tier + ": the label must have room to be drawn");
                assertTrue(label.x() >= button.x() && label.right() <= button.right(),
                        tier + ": the label escapes the button horizontally: " + label);
                assertTrue(label.y() >= button.y() && label.bottom() <= button.bottom(),
                        tier + ": the label escapes the button vertically: " + label);
                assertTrue(label.right() < button.right(), tier + ": the label is inset from both ends");
            }
        }
    }
}
