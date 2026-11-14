package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.client.BookLayout.Box;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the 角色册 panel: <b>official art only</b>, still nine-sliced.
 *
 * <h2>What the human asked for, and what each half means</h2>
 * 「九宫格保留，边框不保留，使用官方背景」:
 * <ul>
 *   <li><b>九宫格保留</b> — the panel is still <em>built</em> by stretching a nine-slice. That mechanism
 *       is not decoration: a single fixed picture cannot resize, and this panel has two metric tiers and
 *       any window size, so its frame has to come from nine pieces. {@link NineSlice} still owns the
 *       nine rectangles and is still what {@code NineSliceTest} pins.</li>
 *   <li><b>边框不保留</b> — our own hand-drawn themed frame is gone. The three book themes, their nine
 *       64x64 tiles each, their dividers and their body tints were deleted with {@code BookTheme}.</li>
 *   <li><b>使用官方背景</b> — the art is now the game's own. The frame is
 *       {@code fx_ui_gachabutton_frame_tex.png}, sliced into nine 16x16 cells in one 48x48 sheet by
 *       {@code tools/gen_book_panel_texture.py}; the <b>body</b> is the selected character's official
 *       {@code still_unit_*} (see {@link #drawStill}).</li>
 * </ul>
 *
 * <h2>Why this file is called "official" rather than "themed"</h2>
 * The old name described a theme — which of three pink/blue/green panels to draw. There is one panel now,
 * and the interesting fact about it is where its pixels come from. Naming it after that is what stops the
 * next reader from looking for a theme parameter that no longer exists.
 *
 * <h2>What is drawn, in order, and why that order</h2>
 * <ol>
 *   <li><b>the flat floor</b> — {@link #BODY_FLOOR}, a dark neutral sampled from the frame's own stroke
 *       family. It is painted under everything for two reasons: the still is drawn on top of it, and a
 *       character with no still must still have an opaque panel body rather than a hole onto the world.
 *       It is painted <b>inside the frame's silhouette</b>, not inside the panel's rectangle — see
 *       {@link #paintFloor} and {@link CornerCutout}, which is the fix for the black corner blocks;</li>
 *   <li><b>the still</b>, cover-cropped to the body (never the whole panel: the frame's ink lives in the
 *       outer band, and running her face under the border is what would look wrong), with a dimming wash
 *       over it so the cream text stays readable;</li>
 *   <li><b>the nine frame cells</b> — corners at the cell's own 16 pixels, edges stretched one way, the
 *       centre skipped entirely (its cell is empty on purpose: the body is the still, and a filled centre
 *       would hide it);</li>
 *   <li><b>the divider rule</b> — a one-pixel line in the stroke's gold. The old divider was a 256x16
 *       painted ornament belonging to the deleted themes; with that art gone, the honest replacement is
 *       the simplest mark the frame's own colour can make. {@link BookLayout#divider()} still reserves the
 *       same 16 pixels, so no metric moved.</li>
 * </ol>
 *
 * <h2>The one thing no test can cover, and the log line for it</h2>
 * The panel size comes from the player's window and GUI scale, so "the panel we just drew has a non-empty
 * body" is not assertable offline. {@link #draw} returns the geometry it drew and the screen logs it once
 * per open — the same technique the rest of this package uses for "did the thing that cannot be asserted
 * actually happen".
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class OfficialPanel {

    /**
     * The nine-cell frame sheet, in {@link NineSlice#PIECE_NAMES} order, written by
     * {@code tools/gen_book_panel_texture.py}.
     *
     * <p>One texture for nine cells rather than nine files: the frame is a single picture that happens to
     * be sampled in nine places, and nine {@code ResourceLocation} constants would be nine chances to
     * mistype one.
     */
    static final ResourceLocation FRAME =
            ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/book_panel.png");

    /**
     * Edge of one cell in {@link #FRAME}, in pixels.
     *
     * <p>One fact with two homes is one home too many: this <em>is</em> {@link NineSlice#PIECE_PIXELS}, which is
     * where the source cell size lives because the slice and the cell are the two halves of "how thick is the
     * frame". Kept as a name here so the drawing code reads as "a cell" rather than "16".
     */
    static final int CELL = NineSlice.PIECE_PIXELS;

    /**
     * The opaque floor under everything: the darkest neutral of the frame's own stroke family.
     *
     * <p>Not a new colour — the frame's cream-gold stroke sits on {@code rgb(20,22,28)} in the game's own
     * composition, and that is the value here. It is what a character with no still art shows, so it has to
     * be dark enough for the cream text and neutral enough not to read as "a theme".
     */
    static final int BODY_FLOOR = 0xFF14161C;

    /**
     * Alpha of the wash over the still, {@code 0x00}–{@code 0xFF}.
     *
     * <p>The still is a bright illustration (sky, gold light, a white dress) and the panel draws cream body
     * text straight on it. 62% black keeps her legible as <em>the background</em> while putting the text at
     * a contrast ratio that survives a sunset and a night sky alike. Higher would make the still pointless;
     * lower would make the level and affection lines unreadable on the bright half of the art.
     */
    static final int STILL_DIM = 0x9E000000;

    /** The divider rule's colour: the frame stroke's own cream-gold, at the value its darkest row carries. */
    static final int DIVIDER_INK = 0xFFDDA93C;

    private OfficialPanel() {
    }

    /**
     * Draws the panel, its still background and its divider.
     *
     * @param graphics  the GUI buffer
     * @param panel     the panel's outer rectangle
     * @param body      the interior the still covers; {@link BookLayout#panel()} minus the frame band
     * @param still     the selected character's still texture, or {@code null} for "no still art"
     * @param divider   where the rule goes; {@code null} to skip it
     * @param slice     nine-slice thickness, {@link BookLayout#slice()}
     * @return the nine rectangles that were drawn, in {@link NineSlice#PIECE_NAMES} order
     */
    static NineSlice.Rects draw(GuiGraphics graphics, Box panel, Box body,
            @Nullable ResourceLocation still, @Nullable Box divider, int slice) {
        NineSlice.Rects rects = NineSlice.rects(panel, slice);

        // 1. The floor. Painted under the still, so a still with soft edges (or none at all) never shows
        //    the world through the panel's own body — but painted INSIDE the frame's rounded silhouette,
        //    because the frame art's corners are curves on transparency and a rectangular fill turns the
        //    four cut-outs into solid dark blocks. That was the defect: 「四个角是黑色的方块」.
        paintFloor(graphics, panel, slice);

        // 2. The still, cover-cropped into the body.
        if (still != null) {
            drawStill(graphics, still, body);
        }

        // 3. The nine frame cells. The centre is skipped by construction: its cell is empty.
        for (int i = 0; i < NineSlice.PIECES; i++) {
            if (i == CENTRE) {
                continue;   // the body is the still; the centre cell exists only to make the sheet square
            }
            Box piece = rects.piece(i);
            if (piece.width() <= 0 || piece.height() <= 0) {
                continue;   // a degenerate target: nothing to draw here, and a 0x0 blit is a lie
            }
            blitCell(graphics, i, piece);
        }

        // 4. The divider rule.
        if (divider != null && divider.width() > 0 && divider.height() > 0) {
            int y = divider.y() + divider.height() / 2;
            graphics.fill(divider.x(), y, divider.right(), y + 1, DIVIDER_INK);
        }
        return rects;
    }

    /**
     * Paints {@link #BODY_FLOOR} inside the frame's rounded silhouette.
     *
     * <h2>Why this is not one {@code fill}</h2>
     * One rectangle fill was the defect. The frame's corner cells are anti-aliased <b>arcs on
     * transparency</b>: the outermost row of each corner has alpha 0 for its first five-to-seven pixels.
     * A rectangle fill covers that empty wedge with the floor, and at the comfortable tier's 30-pixel slice
     * the 16-pixel cell is magnified 1.9x, so the wedge reads as a solid dark block — the four black
     * squares in the human's screenshot. Nothing about the sheet needed regenerating; the fill had to stop
     * covering pixels the art does not paint.
     *
     * <h2>The three bands</h2>
     * The panel's rows split into three groups, painted as horizontal spans:
     * <ul>
     *   <li><b>the top corner band</b> — {@code slice} rows below the panel's top edge, where each row
     *       starts {@link CornerCutout#cleared} pixels in from the left and stops that many short of the
     *       right;</li>
     *   <li><b>the middle</b> — between the two bands, filled edge to edge. This is the bulk of the panel
     *       and the reason the body stays opaque;</li>
     *   <li><b>the bottom corner band</b> — the same, mirrored, counting inward from the bottom edge.</li>
     * </ul>
     * A row whose two insets meet or cross has nothing inside the silhouette and is skipped: that is a
     * panel narrower than its own corner arcs, which {@link NineSlice#clampSlice} already keeps from
     * happening, and drawing a negative-width span would be the one way to paint outside the panel.
     *
     * <h2>Why adjacent rows are merged, and why the merge has to be exact</h2>
     * A row at a time would be one {@code fill} per pixel of panel height on every frame — several hundred
     * draws to paint one flat colour. So consecutive rows with the <b>identical</b> span are emitted as one
     * rectangle. The comparison is on both edges, not just the left one: merging rows that differ by a
     * pixel on the right would paint a block a pixel outside the silhouette, which is the defect again in
     * miniature.
     *
     * @param graphics the GUI buffer
     * @param panel    the panel's outer rectangle
     * @param slice    the nine-slice thickness the corners are drawn at
     */
    private static void paintFloor(GuiGraphics graphics, Box panel, int slice) {
        if (panel.width() <= 0 || panel.height() <= 0) {
            return;
        }
        int band = NineSlice.clampSlice(panel, slice);
        int runTop = -1;
        int runLeft = 0;
        int runRight = 0;
        for (int row = 0; row < panel.height(); row++) {
            int left = floorLeft(panel, band, row);
            int right = floorRight(panel, band, row);
            boolean drawable = right > left;
            boolean sameSpan = drawable && runTop >= 0 && left == runLeft && right == runRight;
            if (sameSpan) {
                continue;   // extend the open run downward; the flush below closes it
            }
            if (runTop >= 0) {
                graphics.fill(runLeft, panel.y() + runTop, runRight, panel.y() + row, BODY_FLOOR);
                runTop = -1;
            }
            if (drawable) {
                runTop = row;
                runLeft = left;
                runRight = right;
            }
        }
        if (runTop >= 0) {
            graphics.fill(runLeft, panel.y() + runTop, runRight, panel.bottom(), BODY_FLOOR);
        }
    }

    /**
     * {@return the first column of {@code row} that is inside the frame's silhouette}
     *
     * <p>Zero inset unless the row falls in the top or bottom corner band; see {@link #paintFloor}.
     */
    private static int floorLeft(Box panel, int band, int row) {
        if (band <= 0) {
            return panel.x();
        }
        if (row < band) {
            return panel.x() + CornerCutout.cleared(CornerCutout.TOP_LEFT_INDEX, row, band);
        }
        int fromBottom = panel.height() - 1 - row;
        if (fromBottom < band) {
            return panel.x() + CornerCutout.cleared(CornerCutout.BOTTOM_LEFT_INDEX, fromBottom, band);
        }
        return panel.x();
    }

    /**
     * {@return the first column past the end of {@code row} that is inside the frame's silhouette}
     *
     * <p>The mirror of {@link #floorLeft}; kept separate rather than returned as a pair so the two
     * asymmetric corner tables cannot be swapped by accident.
     */
    private static int floorRight(Box panel, int band, int row) {
        if (band <= 0) {
            return panel.right();
        }
        if (row < band) {
            return panel.right() - CornerCutout.cleared(CornerCutout.TOP_RIGHT_INDEX, row, band);
        }
        int fromBottom = panel.height() - 1 - row;
        if (fromBottom < band) {
            return panel.right()
                    - CornerCutout.cleared(CornerCutout.BOTTOM_RIGHT_INDEX, fromBottom, band);
        }
        return panel.right();
    }

    /**
     * Draws one cell of {@link #FRAME} into a destination box.
     *
     * <p>{@code uWidth == textureWidth} area arithmetic: the source rectangle is the cell's own 16x16
     * region of the 48x48 sheet, and the destination is stretched to the box. Passing the sheet's full
     * size as the source extent would sample the whole sheet into every piece.
     */
    private static void blitCell(GuiGraphics graphics, int index, Box box) {
        int u = (index % 3) * CELL;
        int v = (index / 3) * CELL;
        graphics.blit(FRAME, box.x(), box.y(), box.width(), box.height(), u, v, CELL, CELL, CELL * 3,
                CELL * 3);
    }

    /**
     * Draws the still as a cover-fit background with the readability wash over it.
     *
     * <p>The texture's true pixel size is not known here (it is whatever the pack ships), so the crop is computed
     * from the <em>committed</em> size {@link StillCover#STILL_WIDTH}x{@link StillCover#STILL_HEIGHT} — 512x288,
     * the derived copy {@code tools/gen_still_backgrounds.py} writes. That is safe rather than fragile:
     * {@code OfficialPanelTest} reads the committed file and asserts that size, so a re-export at another
     * resolution fails a test instead of cropping the picture wrongly.
     *
     * @param graphics the GUI buffer
     * @param texture  the still
     * @param body     the box to cover
     */
    private static void drawStill(GuiGraphics graphics, ResourceLocation texture, Box body) {
        if (body.width() <= 0 || body.height() <= 0) {
            return;
        }
        StillCover.Crop crop = StillCover.of(StillCover.STILL_WIDTH, StillCover.STILL_HEIGHT, body);
        graphics.blit(texture, body.x(), body.y(), body.width(), body.height(),
                crop.x(), crop.y(), crop.width(), crop.height(), StillCover.STILL_WIDTH,
                StillCover.STILL_HEIGHT);
        graphics.fill(body.x(), body.y(), body.right(), body.bottom(), STILL_DIM);
    }

    /** Index of the centre cell in {@link NineSlice#PIECE_NAMES}: the one that is empty on purpose. */
    private static final int CENTRE = 4;

    /**
     * {@return whether the boxes just drawn form a usable frame}
     *
     * <p>Logged by the screen. A panel narrower than two slices still draws (the slice clamps), so this
     * answers {@code true} for every size a real window can produce — which is the point: the log line
     * proves the clamp ran, not that it was needed.
     *
     * @param rects what {@link #draw} returned
     */
    static boolean isUsable(NineSlice.Rects rects) {
        if (!rects.frameIsDrawable()) {
            com.astrum.pcrd.Astrum.LOGGER.warn("astrum: book panel is degenerate — the drawn pieces "
                    + "have no area: {}", rects.all());
            return false;
        }
        return true;
    }
}
