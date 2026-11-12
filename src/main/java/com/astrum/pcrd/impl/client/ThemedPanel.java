package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.client.BookLayout.Box;
import com.astrum.pcrd.impl.item.BookTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * Draws the 角色册 panel of one theme: the body fill, nine panel pieces, and the ornament.
 *
 * <h2>Why the drawing is its own class</h2>
 * {@link CharacterBookScreen} is already the biggest class in {@code impl/client} and it decides grid,
 * party, growth, footer and input. The panel is a different job: nine blits whose <em>geometry</em> is
 * {@link NineSlice}'s (pure, tested) and whose <em>textures</em> are {@link BookTheme}'s (pure, tested).
 * Keeping the three lines that lose information — the {@code blit} call per piece, the skip of a
 * zero-sized piece, the stretched divider — in one small class means the screen asks for "the panel in
 * this theme, in this box" and cannot get the piece order wrong.
 *
 * <h2>What is drawn, in order</h2>
 * <ol>
 *   <li>the theme's {@link BookTheme#bodyTint()} over the whole panel rectangle — an opaque floor, so
 *       the 1%-transparent centre art cannot show a bright sky through the text, and so a degenerate
 *       target (centre size zero) is still a body rather than a hole;</li>
 *   <li>the nine pieces, corners at their slice size, edges stretched one way, centre both ways, with
 *       every zero-sized piece skipped ({@code blit} with a zero extent is a no-op but a wasted state
 *       change);</li>
 *   <li>the theme's divider, stretched across the box {@link BookLayout#divider()} gives it.</li>
 * </ol>
 *
 * <h2>Why no tint is multiplied over the art</h2>
 * See {@link BookTheme}: the pieces are already coloured per theme, so a tint would darken the ornament
 * and flatten the very difference the themes exist for. The body fill is <em>under</em> the art, not over
 * it.
 *
 * <h2>The one thing no test can cover, and the log line for it</h2>
 * The panel size comes from the player's window and GUI scale, so "the panel we just drew has a non-empty
 * body" is not assertable offline. {@link #draw} therefore returns the nine boxes it drew, and the screen
 * logs them once per open — the same technique the rest of this package uses for "did the thing that
 * cannot be asserted actually happen".
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
final class ThemedPanel {

    private ThemedPanel() {
    }

    /**
     * Draws one themed panel and its ornament.
     *
     * @param graphics the GUI buffer
     * @param theme    which theme's art to draw
     * @param panel    the panel's outer rectangle
     * @param divider  where the ornament goes; {@code null} to skip it
     * @return the nine rectangles that were drawn, in {@link NineSlice#PIECE_NAMES} order
     */
    static NineSlice.Rects draw(GuiGraphics graphics, BookTheme theme, Box panel, Box divider) {
        NineSlice.Rects rects = NineSlice.rects(panel, NineSlice.DEFAULT_SLICE);
        // The opaque floor first: the centre art is alpha 252 and, on a degenerate target, absent.
        graphics.fill(panel.x(), panel.y(), panel.right(), panel.bottom(), theme.bodyTint());

        for (int i = 0; i < NineSlice.PIECES; i++) {
            Box piece = rects.piece(i);
            if (piece.width() <= 0 || piece.height() <= 0) {
                continue;   // a degenerate target: nothing to draw here, and a 0x0 blit is a lie
            }
            // The 64x64 tile is sampled in full (source 0,0 ... 64,64) and scaled to the destination
            // box: that is what makes the centre stretch and the edges repeat their pattern.
            graphics.blit(theme.panel(BookTheme.Part.values()[i]), piece.x(), piece.y(),
                    piece.width(), piece.height(), 0.0F, 0.0F, BookTheme.PIECE_PIXELS,
                    BookTheme.PIECE_PIXELS, BookTheme.PIECE_PIXELS, BookTheme.PIECE_PIXELS);
        }

        if (divider != null && divider.width() > 0 && divider.height() > 0) {
            graphics.blit(theme.divider(), divider.x(), divider.y(), divider.width(),
                    divider.height(), 0.0F, 0.0F, BookTheme.DIVIDER_PIXELS_W,
                    BookTheme.DIVIDER_PIXELS_H, BookTheme.DIVIDER_PIXELS_W,
                    BookTheme.DIVIDER_PIXELS_H);
        }
        return rects;
    }

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
            Astrum.LOGGER.warn("astrum: book panel is degenerate — the drawn pieces have no area: {}",
                    rects.all());
            return false;
        }
        return true;
    }
}
