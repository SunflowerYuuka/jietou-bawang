package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.client.BookLayout.Box;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where the 技能详情 page's columns are, as pure arithmetic — {@link BookLayout}'s sibling for the second
 * mode of the same screen.
 *
 * <h2>The layout the human drew</h2>
 * <pre>
 *   [图标]技能名1 | [图标]技能名2 | [图标]技能名3
 *   效果          |  效果        |  效果
 *   效果          |  效果        |  效果
 *   [图标]技能名4
 *   效果
 * </pre>
 * Multi-column, one column per skill version: a name row, then one row per effect. Columns are equal width,
 * the count is {@code floor(available / minColumnWidth)}, and skills past the last column wrap onto a new
 * line — which is why {@link #grid} needs the effect counts up front: a column's height is its own name plus
 * its own rows, and the row it sits in is as tall as the tallest column in it.
 *
 * <h2>One picture per column, and none per row</h2>
 * The bracketed {@code 图标} above is the <b>skill's</b> picture and there is exactly one per column, because a
 * column <em>is</em> one skill version — the human's rule 「一个技能可以引用多个通用效果的代码，但是图标只使用一个」. It is
 * {@link Grid#nameIcon(int)}, a square at the left of the name row, and {@link Grid#nameBox(int)} starts after
 * it. The effect rows under it carry no picture at all: a generic effect has no image any more, so
 * {@link Grid#effectLabel(int, int)} begins at the row's own left edge. The name row is therefore
 * {@link Metrics#nameRowHeight()} tall rather than one font line, which is the only height this costs a column.
 *
 * <h2>Why this class exists instead of arithmetic inside {@code render}</h2>
 * This project cannot take screenshots, so a layout claim that is not an assertion stays a guess. "Six
 * skills on the compact tier produce a sane grid", "no two columns overlap", "a click on a column's centre
 * hits that column", "the grid wraps after the third column" and "the numbers panel stays inside the panel"
 * are all arithmetic, so they are asserted in {@code SkillDetailLayoutTest} instead of being claimed after
 * looking at a window. {@code CharacterBookScreen} only asks for boxes and puts pixels in them.
 *
 * <h2>Two metric tiers, chosen by the book's own tier</h2>
 * The page is drawn inside {@link BookLayout}'s panel, so it inherits that panel's tier: {@link Metrics#COMPACT}
 * when the panel came from {@link BookLayout.Metrics#COMPACT}, {@link Metrics#COMFORTABLE} otherwise. Nothing
 * here re-derives which tier the window got — {@link #metricsFor(BookLayout)} reads the decision
 * {@code BookLayout.at} already made, so the page cannot disagree with the panel around it.
 *
 * <h2>Scrolling, and why the grid is shifted rather than parameterised</h2>
 * {@link #grid} answers boxes at scroll {@code 0}; {@link Grid#scrolled} answers the same grid moved up by a
 * number of pixels. The screen takes <b>one</b> grid per frame and uses it for both drawing and hit-testing,
 * so "the box I drew" and "the box I hit" cannot drift apart — which is the bug a scroll offset applied at
 * only one of the two call sites always produces.
 */
@ApiStatus.Internal
public final class SkillDetailLayout {

    /**
     * One tier of measurements for the skill-detail page.
     *
     * @param minColumnWidth   narrowest a column may be before one fewer fits; sets the column count
     * @param columnGap        gap between two columns, and the horizontal air the wrap rule counts
     * @param lineHeight       one font line: the height of the name's <em>text</em>, and every line of the
     *                         numbers panel
     * @param effectRowHeight  one effect row: a font line, no icon (an effect has no picture any more)
     * @param rowGap           vertical gap between two wrapped grid rows
     * @param iconGap          gap between the name row's <b>skill picture</b> and the skill's name
     * @param tierReserve      width kept free at an effect row's right end for the roman-numeral tier
     * @param popupPadding     padding inside the numbers panel
     * @param nameIcon         edge of the skill picture at the left of a column's name row
     */
    public record Metrics(int minColumnWidth, int columnGap, int lineHeight, int effectRowHeight,
                          int rowGap, int iconGap, int tierReserve, int popupPadding, int nameIcon) {

        /**
         * The compact tier's numbers: three columns in the compact body's 257 pixels.
         *
         * <p>78 is not arbitrary — {@code (257 + 6) / (78 + 6) = 3.1}, so the body takes exactly three
         * columns, which is the sketch's own count. The tierReserve of 16 leaves room for the widest roman
         * numeral ({@code VIII}) the ladder can produce (a five-rung effect plus a +3 band clamps to V, so
         * {@code V} is in practice the widest, and 16 pixels is deliberately more than that).
         *
         * <p>{@code nameIcon} is 14 — one and a half font lines in a 78-pixel column — because the picture has
         * to be readable beside the name without turning a column of effects into a column of pictures. The
         * name row is then 14 tall instead of 10 ({@link #nameRowHeight()}), which is the only height this
         * change adds to a column.
         */
        public static final Metrics COMPACT = new Metrics(78, 6, 10, 9, 5, 2, 16, 6, 14);

        /**
         * The comfortable tier's: the same three columns in a 336-pixel body, roomier.
         *
         * <p>{@code (336 + 8) / (96 + 8) = 3.3} — still three, so the page does not re-flow into a different
         * shape when a player's window grows; only the type and the gaps do. That is the same choice
         * {@link BookLayout.Metrics#COMFORTABLE} makes: the tier buys legibility, not a new arrangement. The
         * skill picture grows with the type (14 → 16) for the same reason.
         */
        public static final Metrics COMFORTABLE = new Metrics(96, 8, 10, 10, 6, 3, 18, 7, 16);

        /**
         * {@return how tall a column's name row is: the skill picture's edge, or one font line if that is
         * taller}
         *
         * <p>One function rather than {@code lineHeight} read at three call sites: the name row, the first
         * effect row's top and {@link #columnHeight} all have to agree, or the picture is drawn over the first
         * effect row of its own column.
         */
        public int nameRowHeight() {
            return Math.max(lineHeight, nameIcon);
        }

        /** {@return how tall a column showing {@code effectRows} effects is} */
        public int columnHeight(int effectRows) {
            return nameRowHeight() + Math.max(0, effectRows) * effectRowHeight;
        }

        /** {@return how tall a numbers panel showing {@code lines} lines of text is} */
        public int popupHeight(int lines) {
            return 2 * popupPadding + Math.max(1, lines) * lineHeight;
        }
    }

    private SkillDetailLayout() {
    }

    /** {@return the detail page's metrics for the tier the book's panel was built at} */
    public static Metrics metricsFor(BookLayout layout) {
        return layout.metrics() == BookLayout.Metrics.COMFORTABLE
                ? Metrics.COMFORTABLE
                : Metrics.COMPACT;
    }

    /**
     * {@return how many columns fit in {@code width}, never less than one}
     *
     * <p>The division is on <em>pitch</em> ({@code width + gap}) rather than on the width alone: with
     * {@code n} columns there are {@code n - 1} gaps, so a strip of {@code n * width + (n - 1) * gap} fits
     * exactly, and {@code floor((available + gap) / (min + gap))} is the largest such {@code n}. A width that
     * cannot hold even one column still answers 1 — a page with no columns would draw nothing and hit-test
     * nothing, which is a worse failure than a cramped column.
     *
     * @param width   available width in GUI pixels
     * @param metrics the tier's measurements
     */
    public static int columnCount(int width, Metrics metrics) {
        int min = Math.max(1, metrics.minColumnWidth());
        int pitch = min + Math.max(0, metrics.columnGap());
        return Math.max(1, (Math.max(0, width) + Math.max(0, metrics.columnGap())) / pitch);
    }

    /**
     * {@return the width one column gets, with the gaps taken out of {@code width} first}
     *
     * <p>Integer division, so the columns may end a few pixels short of the area's right edge. That is the
     * honest outcome: spreading the remainder over the columns would make them unequal, and an unequal grid
     * is exactly the "the columns look misaligned" impression this page is meant to remove.
     *
     * @param width   available width
     * @param metrics the tier's measurements
     * @param columns how many columns {@link #columns} answered
     */
    public static int columnWidth(int width, Metrics metrics, int columns) {
        int count = Math.max(1, columns);
        int gaps = (count - 1) * Math.max(0, metrics.columnGap());
        return Math.max(0, (Math.max(0, width) - gaps) / count);
    }

    /**
     * <b>One column of the page, as the geometry sees it: a title, and how many rows it has.</b>
     *
     * <h2>Why the geometry takes this and not the model's rows</h2>
     * The page has two kinds of row — an effect hook's, and an ultimate's damage/effect/charge rows — and a
     * layout that knew the difference would need a second copy of itself the day a third kind appeared. It does
     * not need to: a column is a title and a number of rows whether those rows came from an
     * {@code EffectHook} or a {@code UbTier}. Both builders ({@code SkillDetailModel#rowsOf} and
     * {@code SkillDetailModel#ubRows}) produce the same row value, the model hands this class one of these per
     * column, and there is therefore <b>one</b> grid, one wrap rule and one hit test for the whole page.
     *
     * <p>The title is carried for the caller's benefit (the open line logs every column's name) and for the
     * tests: "the grid's title at index 3 is the model's entry 3" is the assertion that the two lists cannot
     * slip out of step.
     *
     * @param title what the column is called
     * @param rows  how many rows it draws under that title
     */
    public record Column(String title, int rows) {

        public Column {
            title = title == null ? "" : title;
            rows = Math.max(0, rows);
        }
    }

    /**
     * Lays the columns out in a wrapped, multi-column grid at scroll {@code 0}.
     *
     * @param area    the body the page may use, from {@link BookLayout#skillDetailBody()}
     * @param metrics the tier's measurements
     * @param columns the page's columns, in drawing order
     */
    public static Grid grid(Box area, Metrics metrics, List<Column> columns) {
        List<Column> content = columns == null ? List.of() : columns;
        int count = columnCount(area.width(), metrics);
        int width = columnWidth(area.width(), metrics, count);
        List<Box> cells = new ArrayList<>(content.size());
        int rowTop = area.y();
        int contentBottom = area.y();
        for (int index = 0; index < content.size(); index++) {
            int column = index % count;
            if (column == 0) {
                // A new grid row starts: the previous row's tallest column has already advanced rowTop.
                if (index > 0) {
                    rowTop = contentBottom + metrics.rowGap();
                }
            }
            int height = metrics.columnHeight(content.get(index).rows());
            int x = area.x() + column * (width + metrics.columnGap());
            cells.add(new Box(x, rowTop, width, height));
            contentBottom = Math.max(contentBottom, rowTop + height);
        }
        int contentHeight = content.isEmpty() ? 0 : contentBottom - area.y();
        return new Grid(metrics, area, content, cells, count, width, contentHeight);
    }

    /**
     * {@return {@code scroll} pulled into range: never negative, never past the last pixel of content}
     *
     * <p>Content shorter than the view answers {@code 0} rather than a negative maximum, so a one-row page
     * cannot be "scrolled up" into blank space, and a wheel that keeps turning at the end changes nothing —
     * which is what lets {@code mouseScrolled} answer {@code false} and leave the event alone.
     *
     * @param contentHeight how tall the whole grid is, from {@link Grid#contentHeight()}
     * @param viewHeight    how tall the visible area is
     * @param scroll        the requested offset in pixels, positive is down
     */
    public static int clampScroll(int contentHeight, int viewHeight, int scroll) {
        int max = Math.max(0, contentHeight - Math.max(0, viewHeight));
        return Math.max(0, Math.min(max, scroll));
    }

    /**
     * {@return the numbers panel's rectangle, anchored to the column it describes and kept inside
     * {@code bounds}}
     *
     * <p>Below the anchor when there is room, above it otherwise, and clamped on both axes as a last resort:
     * the anchor's column may itself be the last one on the page, and a panel half outside the book would
     * draw over the world. Never wider or taller than {@code bounds} — a numbers list longer than the panel
     * is truncated by the caller's line budget, not by a box that leaves the book.
     *
     * @param anchor        the column the numbers belong to
     * @param contentWidth  the width wanted
     * @param contentHeight the height wanted
     * @param bounds        the area the panel must stay inside
     */
    public static Box popup(Box anchor, int contentWidth, int contentHeight, Box bounds) {
        int width = Math.max(0, Math.min(contentWidth, bounds.width()));
        int height = Math.max(0, Math.min(contentHeight, bounds.height()));
        int x = clamp(anchor.x(), bounds.x(), bounds.right() - width);
        boolean below = anchor.bottom() + height <= bounds.bottom();
        int wanted = below ? anchor.bottom() : anchor.y() - height;
        int y = clamp(wanted, bounds.y(), bounds.bottom() - height);
        return new Box(x, y, width, height);
    }

    /**
     * {@return how wide the numbers panel is: two columns, so a label and a value fit side by side}
     *
     * <p>Two columns rather than a fixed 160 pixels: the panel's job is to show "参数  数值" pairs, the pair is
     * as wide as the type the page is drawn in, and a hard-coded width would be too narrow at the comfortable
     * tier and too wide at the compact one.
     *
     * @param grid    the laid-out page
     * @param metrics the tier's measurements
     */
    public static int popupWidth(Grid grid, Metrics metrics) {
        return Math.min(grid.area().width(), Math.max(metrics.minColumnWidth(), grid.columnWidth() * 2));
    }

    /**
     * {@return how many lines of text fit inside {@code popup}}
     *
     * <p>Needed because the numbers of a column are not a fixed list: a skill version with four effects and
     * three numbers each can want more lines than the book is tall. The caller draws
     * {@code visibleLines} of them and stops, which is the one behaviour that cannot put text outside the
     * panel.
     *
     * @param popup   the panel {@link #popup} answered
     * @param metrics the tier's measurements
     */
    public static int visibleLines(Box popup, Metrics metrics) {
        int usable = Math.max(0, popup.height() - 2 * metrics.popupPadding());
        return usable / Math.max(1, metrics.lineHeight());
    }

    /**
     * {@return the box line {@code index} of a numbers panel is drawn in}
     *
     * <p>Empty (a zero-width box) past the panel's last line, so the caller's loop can be bounded by
     * {@link #visibleLines} and cannot draw past the frame either way.
     *
     * @param popup   the panel {@link #popup} answered
     * @param metrics the tier's measurements
     * @param index   0-based line
     */
    public static Box popupLine(Box popup, Metrics metrics, int index) {
        int top = popup.y() + metrics.popupPadding() + Math.max(0, index) * metrics.lineHeight();
        if (index < 0 || top + metrics.lineHeight() > popup.bottom() - metrics.popupPadding()) {
            return new Box(0, 0, 0, 0);
        }
        return new Box(popup.x() + metrics.popupPadding(), top,
                Math.max(0, popup.width() - 2 * metrics.popupPadding()), metrics.lineHeight());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }

    /**
     * The laid-out page: one box per column, plus the arithmetic every caller would otherwise redo.
     *
     * @param metrics       the tier the grid was built with
     * @param area          the body box the grid was built in
     * @param columns       the page's columns, in the same order as {@code cells} — the title is what lets a
     *                      caller log or assert <em>which</em> column a box belongs to
     * @param cells         one box per column, in drawing order, at the scroll this grid was built at
     * @param columnsPerRow how many columns one grid line holds
     * @param columnWidth   the width one column got
     * @param contentHeight the height of the whole grid, for {@link #clampScroll}
     */
    public record Grid(Metrics metrics, Box area, List<Column> columns, List<Box> cells, int columnsPerRow,
                       int columnWidth, int contentHeight) {

        public Grid {
            columns = columns == null ? List.of() : List.copyOf(columns);
            cells = cells == null ? List.of() : List.copyOf(cells);
        }

        /** {@return how many columns the grid holds} */
        public int size() {
            return cells.size();
        }

        /** {@return whether there is nothing to draw} */
        public boolean isEmpty() {
            return cells.isEmpty();
        }

        /** {@return the column at {@code index}, or {@code null} past the end} */
        public Column column(int index) {
            return index < 0 || index >= columns.size() ? null : columns.get(index);
        }

        /** {@return every column's title, for a log line or an assertion} */
        public List<String> titles() {
            List<String> titles = new ArrayList<>(columns.size());
            for (Column column : columns) {
                titles.add(column.title());
            }
            return List.copyOf(titles);
        }

        /** {@return {@code scroll} pulled into range for this grid} */
        public int clamp(int scroll) {
            return clampScroll(contentHeight, area.height(), scroll);
        }

        /**
         * {@return this grid moved up by {@code scroll} pixels}
         *
         * <p>The one place the scroll offset is applied. The screen takes the scrolled grid and uses it for
         * drawing <b>and</b> for hit-testing, so the two cannot disagree about where a column is.
         *
         * @param scroll offset in pixels, positive is down; clamped first
         */
        public Grid scrolled(int scroll) {
            int offset = clamp(scroll);
            if (offset == 0) {
                return this;
            }
            List<Box> shifted = new ArrayList<>(cells.size());
            for (Box cell : cells) {
                shifted.add(new Box(cell.x(), cell.y() - offset, cell.width(), cell.height()));
            }
            return new Grid(metrics, area, columns, shifted, columnsPerRow, columnWidth, contentHeight);
        }

        /** {@return the box of entry {@code index}, or {@code null} past the end} */
        public Box cell(int index) {
            return index < 0 || index >= cells.size() ? null : cells.get(index);
        }

        /**
         * {@return the entry under a point, or empty when the point is not on one}
         *
         * <p>Two conditions, both needed. The point must be inside {@link #area} — the rows scrolled above
         * the body have boxes too, and a click on the title must not select the entry that happens to sit
         * there in content coordinates. And the point must be inside the column's own box, which is
         * <b>half-open</b> on both axes, so adjacent columns and adjacent wrapped rows share no pixel.
         *
         * @param px mouse x in GUI pixels
         * @param py mouse y in GUI pixels
         */
        public OptionalInt entryAt(double px, double py) {
            if (!area.contains(px, py)) {
                return OptionalInt.empty();
            }
            for (int index = 0; index < cells.size(); index++) {
                if (cells.get(index).contains(px, py)) {
                    return OptionalInt.of(index);
                }
            }
            return OptionalInt.empty();
        }

        /**
         * {@return the square slot the skill's own picture is drawn in, at the left of entry {@code index}'s
         * name row}
         *
         * <h2>Why the picture is beside the name and not on every effect row</h2>
         * The human's rule is one picture per <em>skill</em>: 「一个技能可以引用多个通用效果的代码，但是图标只使用一个」.
         * A column <b>is</b> one skill version, so the column gets exactly one picture, and the effect rows
         * under it are text — a generic effect has no image any more, which is why there is no per-row icon
         * box here to put one in.
         *
         * <p>Square, and as tall as the name row: the skill icons are 64x64, so a square slot blits them
         * undistorted. The slot is the leftmost {@code nameIcon} pixels of the row, and
         * {@link #nameBox(int)} starts where it ends — one derivation, so the two cannot overlap.
         *
         * @param index which entry, in the model's order
         */
        public Box nameIcon(int index) {
            Box cell = cell(index);
            if (cell == null) {
                return new Box(0, 0, 0, 0);
            }
            int edge = Math.max(0, Math.min(metrics.nameIcon(), Math.min(cell.width(), cell.height())));
            return new Box(cell.x(), cell.y(), edge, edge);
        }

        /**
         * {@return the box entry {@code index}'s <b>name</b> is drawn in: the rest of its name row}
         *
         * <p>Starts after {@link #nameIcon(int)} plus {@link Metrics#iconGap()}, and is a font line tall
         * (<em>not</em> the whole name row): the name is text, and drawing it from the name row's top edge
         * beside a picture that is taller than the glyphs would leave it hanging at the top. The vertical
         * centring of that line inside the row is the caller's, because it depends on the font the screen has;
         * the box here is the horizontal promise — the label never runs into the picture and never past the
         * column's right edge.
         *
         * @param index which entry, in the model's order
         */
        public Box nameBox(int index) {
            Box cell = cell(index);
            if (cell == null) {
                return new Box(0, 0, 0, 0);
            }
            Box icon = nameIcon(index);
            int left = icon.right() + Math.max(0, metrics.iconGap());
            int top = cell.y() + Math.max(0, (metrics.nameRowHeight() - metrics.lineHeight()) / 2);
            return new Box(left, top, Math.max(0, cell.right() - left), metrics.lineHeight());
        }

        /**
         * {@return one effect row of entry {@code index}, or {@code null} past that entry's effect count}
         *
         * <p>The row's height is {@link Metrics#effectRowHeight()}, and the first row starts under the
         * <b>name row</b> — whose height is the skill picture's edge, not the font line — so a picture taller
         * than the type cannot be drawn over the column's first effect.
         */
        public Box effectRow(int index, int row) {
            Box cell = cell(index);
            if (cell == null || row < 0) {
                return null;
            }
            int top = cell.y() + metrics.nameRowHeight() + row * metrics.effectRowHeight();
            if (top + metrics.effectRowHeight() > cell.bottom()) {
                return null;
            }
            return new Box(cell.x(), top, cell.width(), metrics.effectRowHeight());
        }

        /**
         * {@return the box an effect's label is drawn in, or {@code null} past the row}
         *
         * <p>Starts at the row's own left edge, because a generic effect no longer has a picture: the space
         * the icon used to take now belongs to the label (「角色册里的数值显示……纯文字」). It still stops
         * where the tier's reserved strip begins, so the label is <b>truncated</b> rather than allowed to run
         * into the roman numeral or the neighbouring column.
         */
        public Box effectLabel(int index, int row) {
            Box line = effectRow(index, row);
            if (line == null) {
                return null;
            }
            int width = line.right() - metrics.tierReserve() - line.x();
            return new Box(line.x(), line.y(), Math.max(0, width), line.height());
        }

        /** {@return the box the resolved tier is right-aligned in, or {@code null} past the row} */
        public Box effectTier(int index, int row) {
            Box line = effectRow(index, row);
            if (line == null) {
                return null;
            }
            int width = Math.min(metrics.tierReserve(), line.width());
            return new Box(line.right() - width, line.y(), width, line.height());
        }
    }
}
