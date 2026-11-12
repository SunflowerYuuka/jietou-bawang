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
        BookLayout.Box panel = large().panel();
        int expectedX = (LARGE_WIDTH - BookLayout.PANEL_WIDTH) / 2;
        int expectedY = (LARGE_HEIGHT - BookLayout.PANEL_HEIGHT) / 2;
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
        List<BookLayout.Box> parts = List.of(layout.title(), layout.rosterGrid(), layout.partyHeading(),
                layout.partyRow(), layout.growthHeading(), layout.levelLine(), layout.expBar(),
                layout.expLine(), layout.affectionLine(), layout.affectionBar(), layout.nextTierLine(),
                layout.feedEntry());
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

    @Test
    void theRightColumnDoesNotOverlapTheRosterGrid() {
        BookLayout layout = small();
        assertTrue(layout.rosterGrid().right() <= layout.partyHeading().x(),
                "the party column starts after the grid ends");
        assertTrue(layout.feedEntry().bottom() <= layout.rosterGrid().bottom(),
                "the right column must fit inside the body the grid defines");
    }

    @Test
    void theGrowthPartsAreStackedInOrder() {
        BookLayout layout = small();
        assertTrue(layout.partyHeading().bottom() <= layout.partyRow().y());
        assertTrue(layout.partyRow().bottom() <= layout.growthHeading().y());
        assertTrue(layout.growthHeading().bottom() <= layout.levelLine().y());
        assertTrue(layout.levelLine().bottom() <= layout.expBar().y());
        assertTrue(layout.expBar().bottom() <= layout.expLine().y());
        assertTrue(layout.expLine().bottom() <= layout.affectionLine().y());
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
}
