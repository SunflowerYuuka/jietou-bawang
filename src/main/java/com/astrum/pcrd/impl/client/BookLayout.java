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
 *   | refusal line (only while a refusal is fresh)               |
 *   +------------------------------------------------------------+
 * </pre>
 *
 * <p>Everything is derived from {@code screenWidth}/{@code screenHeight} and the constants below; there
 * are no absolute pixel coordinates anywhere, because GUI scale is the player's choice
 * ({@code 1}–{@code 4}) and a hard-coded panel is a panel that is off-screen for somebody. A screen
 * smaller than the panel (which can happen with a large GUI scale) yields a panel clamped to the top
 * left and cells computed the same way — cramped, never negative, never an exception.
 *
 * <h2>Paging</h2>
 * The grid shows {@link #PAGE} cells at a time. {@link #clampFirstIndex} keeps the window aligned so the
 * last page is never half empty, and {@link #rosterIndexAt} maps a point back to an absolute roster
 * index, which is what the screen selects and what a drag carries.
 */
@ApiStatus.Internal
public final class BookLayout {

    /** Outer padding inside the panel. */
    public static final int PADDING = 20;

    /** Height of the title row. */
    public static final int HEADER_HEIGHT = 14;

    /** Height of the themed ornament that separates the title from the body: the divider art is 256x16. */
    public static final int DIVIDER_HEIGHT = 16;

    /** Gap above and below the ornament. */
    public static final int DIVIDER_GAP = 4;

    /** Gap between the roster column and the right-hand column. */
    public static final int COLUMN_GAP = 10;

    /** Width of the right-hand column (party slots + growth view). */
    public static final int RIGHT_WIDTH = 112;

    /** Roster grid: columns per page. */
    public static final int COLUMNS = 2;

    /** Roster grid: rows per page. */
    public static final int ROWS = 3;

    /** Cells one page holds. */
    public static final int PAGE = COLUMNS * ROWS;

    /** Roster cell width: portrait, tier pips and a level label have to fit. */
    public static final int CELL_WIDTH = 66;

    /** Roster cell height. */
    public static final int CELL_HEIGHT = 44;

    /** Gap between roster cells, horizontally and vertically. */
    public static final int CELL_GAP = 3;

    /** Party slot edge length: one square per wheel slot. */
    public static final int SLOT_SIZE = 18;

    /** Gap between party slots. */
    public static final int SLOT_GAP = 3;

    /** Height of a section heading or a line of text. */
    public static final int LINE_HEIGHT = 10;

    /** Height of a progress bar (experience, affection). */
    public static final int BAR_HEIGHT = 5;

    /** Height of the feeding entry point. */
    public static final int FEED_HEIGHT = 12;

    /** Gap between two lines inside the growth view. */
    public static final int LINE_GAP = 1;

    /** Roster grid width, derived. */
    public static final int GRID_WIDTH = COLUMNS * CELL_WIDTH + (COLUMNS - 1) * CELL_GAP;

    /** Roster grid height, derived. */
    public static final int GRID_HEIGHT = ROWS * CELL_HEIGHT + (ROWS - 1) * CELL_GAP;

    /** Panel width, derived from the two columns. */
    public static final int PANEL_WIDTH = PADDING + GRID_WIDTH + COLUMN_GAP + RIGHT_WIDTH + PADDING;

    /** Body height: the taller of the two columns. The right column is designed to fit inside the grid. */
    public static final int BODY_HEIGHT = GRID_HEIGHT;

    /** Panel height, derived: padding, header, ornament, body, and a footer line for a refusal. */
    public static final int PANEL_HEIGHT = PADDING + HEADER_HEIGHT + DIVIDER_GAP + DIVIDER_HEIGHT
            + DIVIDER_GAP + BODY_HEIGHT + LINE_GAP + LINE_HEIGHT + PADDING;

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

    private final int panelX;
    private final int panelY;

    private BookLayout(int panelX, int panelY) {
        this.panelX = panelX;
        this.panelY = panelY;
    }

    /**
     * {@return the layout for a screen of this size, with the panel centred}
     *
     * <p>Clamped at the top left rather than centred when the screen is too small: a negative origin
     * would push the title off-screen, and there is no layout that makes a 100-pixel-tall screen fit a
     * 168-pixel panel.
     *
     * @param screenWidth  the screen's width in GUI pixels
     * @param screenHeight the screen's height in GUI pixels
     */
    public static BookLayout at(int screenWidth, int screenHeight) {
        int x = Math.max(0, (screenWidth - PANEL_WIDTH) / 2);
        int y = Math.max(0, (screenHeight - PANEL_HEIGHT) / 2);
        return new BookLayout(x, y);
    }

    /** {@return the panel this layout draws in} */
    public Box panel() {
        return new Box(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
    }

    /** {@return the row the title is drawn in} */
    public Box title() {
        return new Box(panelX + PADDING, panelY + PADDING, PANEL_WIDTH - 2 * PADDING, HEADER_HEIGHT);
    }

    /**
     * {@return the box the themed ornament between the title and the body is struck in}
     *
     * <p>Full body width, {@link #DIVIDER_HEIGHT} tall. The divider art is 256x16 with its ink in the
     * middle ~48 columns, so the caller blits it stretched to this box rather than tiling it: stretching
     * keeps the ornament centred and symmetric, which is what it is drawn to be, while tiling would
     * repeat its centre jewel across the width.
     */
    public Box divider() {
        return new Box(panelX + PADDING, title().bottom() + DIVIDER_GAP, PANEL_WIDTH - 2 * PADDING,
                DIVIDER_HEIGHT);
    }

    /** {@return the top-left corner of the body (below the ornament), both columns align here} */
    private int bodyTop() {
        return divider().bottom() + DIVIDER_GAP;
    }

    /** {@return the x of the roster column} */
    private int rosterLeft() {
        return panelX + PADDING;
    }

    /** {@return the x of the right-hand column} */
    private int rightLeft() {
        return rosterLeft() + GRID_WIDTH + COLUMN_GAP;
    }

    /** {@return the box the roster grid occupies} */
    public Box rosterGrid() {
        return new Box(rosterLeft(), bodyTop(), GRID_WIDTH, GRID_HEIGHT);
    }

    /** {@return the box the party slot row occupies} */
    public Box partyRow() {
        return new Box(rightLeft(), bodyTop() + LINE_HEIGHT + LINE_GAP, RIGHT_WIDTH,
                SLOT_SIZE);
    }

    /** {@return the box the "party" heading occupies} */
    public Box partyHeading() {
        return new Box(rightLeft(), bodyTop(), RIGHT_WIDTH, LINE_HEIGHT);
    }

    /** {@return the box the "growth" heading occupies} */
    public Box growthHeading() {
        return new Box(rightLeft(), partyRow().bottom() + LINE_GAP, RIGHT_WIDTH, LINE_HEIGHT);
    }

    /** {@return the box the level line occupies} */
    public Box levelLine() {
        return new Box(rightLeft(), growthHeading().bottom() + LINE_GAP, RIGHT_WIDTH, LINE_HEIGHT);
    }

    /** {@return the experience bar} */
    public Box expBar() {
        return new Box(rightLeft(), levelLine().bottom() + LINE_GAP, RIGHT_WIDTH, BAR_HEIGHT);
    }

    /** {@return the line under the experience bar (numbers)} */
    public Box expLine() {
        return new Box(rightLeft(), expBar().bottom() + LINE_GAP, RIGHT_WIDTH, LINE_HEIGHT);
    }

    /** {@return the affection line} */
    public Box affectionLine() {
        return new Box(rightLeft(), expLine().bottom() + LINE_GAP, RIGHT_WIDTH, LINE_HEIGHT);
    }

    /** {@return the affection bar} */
    public Box affectionBar() {
        return new Box(rightLeft(), affectionLine().bottom() + LINE_GAP, RIGHT_WIDTH, BAR_HEIGHT);
    }

    /** {@return the line naming the next affection tier} */
    public Box nextTierLine() {
        return new Box(rightLeft(), affectionBar().bottom() + LINE_GAP, RIGHT_WIDTH, LINE_HEIGHT);
    }

    /** {@return the feeding entry point: a button when a dish handler is installed, text otherwise} */
    public Box feedEntry() {
        return new Box(rightLeft(), nextTierLine().bottom() + LINE_GAP, RIGHT_WIDTH, FEED_HEIGHT);
    }

    /**
     * {@return the footer line: the refusal while one is fresh, the interaction hint otherwise}
     *
     * <p>One line, two jobs, because they can never both be true at once — a hint under a "you do not
     * own that character" message would just be noise, and the refusal is exactly what the player needs
     * where the hint was.
     */
    public Box refusalLine() {
        return new Box(panelX + PADDING,
                bodyTop() + BODY_HEIGHT + LINE_GAP, PANEL_WIDTH - 2 * PADDING, LINE_HEIGHT);
    }

    /**
     * {@return the box of the cell at {@code indexInPage}, or {@code null} past the last cell}
     *
     * @param indexInPage position within the page, {@code 0}-{@code PAGE-1}
     */
    public Box cell(int indexInPage) {
        if (indexInPage < 0 || indexInPage >= PAGE) {
            return null;
        }
        int column = indexInPage % COLUMNS;
        int row = indexInPage / COLUMNS;
        return new Box(rosterLeft() + column * (CELL_WIDTH + CELL_GAP),
                bodyTop() + row * (CELL_HEIGHT + CELL_GAP), CELL_WIDTH, CELL_HEIGHT);
    }

    /**
     * {@return how many cells are visible for a roster of {@code count} entries starting at
     * {@code firstIndex}}
     *
     * @param count      how many characters the player owns
     * @param firstIndex absolute index of the first cell on screen
     */
    public int visibleCells(int count, int firstIndex) {
        return Math.max(0, Math.min(PAGE, count - clampFirstIndex(count, firstIndex)));
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
        int maxFirst = Math.max(0, count - PAGE);
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
        return new Box(row.x() + index * (SLOT_SIZE + SLOT_GAP), row.y(), SLOT_SIZE, SLOT_SIZE);
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

