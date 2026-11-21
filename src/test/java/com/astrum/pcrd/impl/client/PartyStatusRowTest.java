package com.astrum.pcrd.impl.client;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The party status row's geometry</b>: where it sits, in what order, and that the cells do not overlap.
 *
 * <h2>Why geometry is a test and not "it looks fine"</h2>
 * A row is drawn by accumulating an x offset, and the two defects that shape produces are invisible in a
 * screenshot at a glance and obvious in an assertion: cells that overlap by a pixel (so a face is clipped),
 * and cells in the wrong order (so the row disagrees with the party slots above it). Neither is a crash, so
 * neither would be noticed until a player complained about a picture being cut. {@code WheelCellLayout},
 * {@code CastPadGeometry} and {@code HudLayoutTest} exist for the same reason.
 *
 * <h2>What cannot be asserted here</h2>
 * Whether the faces are <em>legible</em> at 18px, whether the frame reads at the player's GUI scale, and
 * whether the row and the gauge look like one widget. Those need a screenshot of a running client, and the
 * report says so instead of implying this test covers them.
 */
class PartyStatusRowTest {

    private static final HudLayout.Rect GAUGE = new HudLayout.Rect(10, 100, 109, 16);

    /** The row sits directly above the gauge, left-aligned with it, so the two read as one widget. */
    @Test
    void theRowSitsDirectlyAboveTheGaugeAndSharesItsLeftEdge() {
        HudLayout.Rect row = PartyStatusRow.rectFor(GAUGE, 5);
        assertEquals(GAUGE.x(), row.x(), "the row is left-aligned with the gauge below it");
        assertEquals(GAUGE.y() - PartyStatusRow.TO_GAUGE - PartyStatusRow.CELL, row.y(),
                "the row's bottom edge is TO_GAUGE above the gauge's top edge");
        assertEquals(GAUGE.y(), row.bottom() + PartyStatusRow.TO_GAUGE,
                "and it touches nothing: the gap is exactly TO_GAUGE");
        assertEquals(PartyStatusRow.CELL, row.height());
    }

    /**
     * A full party is five cells wide, and the row's width is the cells plus the gaps between them.
     *
     * <p>Five cells of 18 with four 2px gaps is 98, which is narrower than the gauge's 109 — so the row does
     * not overhang the widget it belongs to, and a player with an off-centre HUD does not get a stray cell
     * past the bar.
     */
    @Test
    void aFullPartyFitsInsideTheGaugesWidth() {
        int width = PartyStatusRow.widthOf(5);
        assertEquals(5 * PartyStatusRow.CELL + 4 * PartyStatusRow.GAP, width);
        assertEquals(98, width, "five 18px cells and four 2px gaps");
        assertTrue(width <= HudLayout.WIDGET_WIDTH,
                "the row (" + width + "px) must not be wider than the gauge widget ("
                        + HudLayout.WIDGET_WIDTH + "px), or it would overhang its own bar");
    }

    /** One cell per member, left to right, each one CELL right of the last plus the gap. */
    @Test
    void cellsAreLaidOutLeftToRightWithNoOverlap() {
        for (int count : new int[] {1, 2, 3, 4, 5}) {
            HudLayout.Rect row = PartyStatusRow.rectFor(GAUGE, count);
            List<HudLayout.Rect> cells = PartyStatusRow.cells(row, count);
            assertEquals(count, cells.size(), "one cell per member");
            for (int i = 0; i < cells.size(); i++) {
                HudLayout.Rect cell = cells.get(i);
                assertEquals(row.y(), cell.y(), "every cell sits on the row's line");
                assertEquals(PartyStatusRow.CELL, cell.width());
                assertEquals(PartyStatusRow.CELL, cell.height());
                assertEquals(row.x() + i * (PartyStatusRow.CELL + PartyStatusRow.GAP), cell.x(),
                        "cell " + i + " of " + count + " is in order");
            }
            // Non-overlap, asserted pairwise rather than by trusting the arithmetic above: this is the
            // property the drawing code depends on, and it is what a future change to GAP would break.
            for (int i = 1; i < cells.size(); i++) {
                assertTrue(cells.get(i - 1).right() <= cells.get(i).x(),
                        "cells " + (i - 1) + " and " + i + " overlap: " + cells.get(i - 1) + " / "
                                + cells.get(i));
            }
            // And the row is exactly as wide as its cells need.
            assertEquals(PartyStatusRow.widthOf(count), row.width());
        }
    }

    /** An empty party is a well-defined zero rather than a {@code null} to dereference. */
    @Test
    void anEmptyPartyIsAZeroWidthRow() {
        HudLayout.Rect row = PartyStatusRow.rectFor(GAUGE, 0);
        assertEquals(0, row.width());
        assertEquals(GAUGE.x(), row.x());
        assertEquals(List.of(), PartyStatusRow.cells(row, 0));
        assertEquals(0, PartyStatusRow.widthOf(0));
        assertEquals(0, PartyStatusRow.widthOf(-3), "a negative count is no cells, not a negative width");
        assertEquals(List.of(), PartyStatusRow.cells(row, -1));
    }

    /**
     * The row does not run off the top of the screen when the gauge is near it.
     *
     * <p>A HUD anchored at the top with the row above it can ask for a negative y. This is the case a
     * screenshot at the default anchor never shows, so it is asserted: the caller gets the negative rectangle
     * and can clamp, rather than the geometry silently moving the row somewhere unrelated. {@code HudLayout}
     * owns the on-screen clamp for the widget itself, and the row inherits its anchor from that.
     */
    @Test
    void aGaugeNearTheTopPutsTheRowAboveItEvenIfThatIsNegative() {
        HudLayout.Rect top = new HudLayout.Rect(4, 2, HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT);
        HudLayout.Rect row = PartyStatusRow.rectFor(top, 5);
        assertEquals(2 - PartyStatusRow.TO_GAUGE - PartyStatusRow.CELL, row.y(),
                "the row is placed relative to the gauge; clamping it on screen is HudLayout's job, not a "
                        + "second, silently disagreeing rule here");
        assertTrue(row.y() < 0, "a gauge at y=2 leaves no room above it, and that is a fact the caller sees");
    }
}
