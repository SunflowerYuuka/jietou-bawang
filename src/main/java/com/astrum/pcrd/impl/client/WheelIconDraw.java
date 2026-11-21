package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Draws one character icon — its picture, its frame(s) where a surface wants them, and the tier-3 marker.
 *
 * <h2>One shape, because the picture is a square</h2>
 * Every avatar this mod draws is a <b>square blit</b>: a 128x128 pack file sampled into a
 * {@code diameter x diameter} box. So every frame around one is a square, at every surface — the 角色册's
 * roster cells and party slots, the wheel's sectors, the cast pad's chips.
 *
 * <p>That is the fix for a defect the human reported three times in three places:
 * 「角色册里面头像的那个圆圈」, 「编队槽也有这个问题」, then 「按B呼出的轮盘菜单，还是原来的带圆圈的头像，把圆圈删除。」
 * A circle of radius {@code edge / 2} around a square of side {@code edge} touches it at the four edge
 * midpoints and <b>cuts through the corners</b>: the stroke crosses her face, and the picture's corners
 * stick out past the frame. Round peg, square hole — the frame has to be the picture's shape.
 *
 * <h2>Why not mask the picture into a circle instead</h2>
 * That was the other half of his sentence ("fill this circle with her avatar"), and it is the one thing the
 * GUI pipeline here cannot do: {@code GuiGraphics.blit} has no circular clip, and doing it with a shader
 * would put a per-icon render pass behind every roster cell and every wheel sector. A frame that matches
 * the picture's own shape gets the same result — her face, whole, with a gold outline — without one.
 *
 * <h2>Why it is a class and not four copies</h2>
 * Two overlays and the book all draw the same icon, and the icon is not a trivial blit: the frames, the
 * glyph fallback and the {@code II} marker are what make tier 3 visible when a pack ships no portrait art
 * (spec §6.2), which {@link WheelIconTest} pins as <em>data</em>. Drawing that per surface would mean one of
 * them eventually losing the marker — and, in fact, one of them keeping the circle for two releases. So it
 * lives once, here, and every surface calls it: {@link #drawCentred} is the only entry point left, and it
 * is {@link #drawSquare} with the centre converted to a corner.
 *
 * <p>Everything it draws is opaque (spec §5.5: 图标与文字不透明) whatever the plate behind it does, which
 * is why the colours come from {@link WheelIcon} rather than from a per-frame alpha.
 */
@ApiStatus.Internal
final class WheelIconDraw {

    /** Opaque colour of the glyph drawn inside a portrait-less plate: dark, on the light tint. */
    private static final int GLYPH_COLOR = 0xFF101010;

    /** Prefix length of the one-character glyph used when a character has no portrait. */
    private static final int GLYPH_LENGTH = 1;

    /** Thickness of one frame stroke, square: one GUI pixel, the same as the old ring. */
    private static final int STROKE = 1;

    /**
     * The most frame strokes an icon can carry.
     *
     * <p>Bounded because the frames are nested <em>inside</em> the slot: every stroke costs the picture one
     * pixel per side, so an unbounded count could shrink the portrait to nothing. Two is
     * {@link WheelIcon#ENHANCED_RINGS}; the guard is here so a future third ring is a deliberate decision
     * about the picture's size rather than a silent one.
     */
    private static final int MAX_FRAMES = 2;

    private WheelIconDraw() {
    }

    /**
     * Draws one icon centred on a point, into a square of {@code 2 * radius} pixels.
     *
     * <p>The wheel's sectors and the cast pad's chips are radial and rectangular respectively, but both
     * place a square picture by its centre — so both come through here, and neither has a circle any more.
     * The conversion from centre to corner is done once, here, rather than at each call site.
     *
     * <p>Rounding is done once and shared by every part of the drawing (the picture, the frames, the
     * marker), so a fractional radius cannot put the frame half a pixel off the picture.
     *
     * @param graphics   the GUI buffer
     * @param x          centre x
     * @param y          centre y
     * @param icon       what to draw, from {@link WheelIcon#of}
     * @param definition the character, for the name-derived glyph; empty when unknown
     * @param radius     half the edge of the square the icon occupies
     * @return how many frame strokes were drawn, for the overlay's per-frame diagnostic
     */
    static int drawCentred(GuiGraphics graphics, double x, double y, WheelIcon icon,
            Optional<CharacterDef> definition, double radius) {
        int edge = Math.max(1, (int) Math.round(radius * 2.0));
        int left = (int) Math.round(x - edge / 2.0);
        int top = (int) Math.round(y - edge / 2.0);
        return drawSquare(graphics, left, top, edge, icon, definition);
    }

    /**
     * Draws one <b>square</b> icon into a slot of {@code slotEdge} pixels at {@code (x, y)}: frames at the slot's
     * edge, the picture inset inside them, then the marker.
     *
     * <p>The only form there is. See the class javadoc for why the frame is square on every surface: the picture
     * is square, so a round frame cuts it.
     *
     * <h2>Why the picture is inset rather than the frame drawn outside</h2>
     * The slot's edge is fixed by the surface's own layout — {@code BookLayout} leaves {@code iconInset} pixels
     * beside the roster cell and starts the text column {@code textGap} pixels after it, and in the compact tier
     * that gap is <b>one pixel</b>. A frame drawn outside the picture would therefore land on the level/name text
     * at the small tier. So the frames live <em>inside</em> the slot and the picture gives up one pixel per
     * stroke: nothing is drawn outside the rectangle the layout reserved, at either tier, and no stroke can touch
     * the text.
     *
     * @param graphics   the GUI buffer
     * @param x          left edge of the slot
     * @param y          top edge of the slot
     * @param slotEdge   the slot's edge in pixels, at least 1
     * @param icon       what to draw, from {@link WheelIcon#of}
     * @param definition the character, for the name-derived glyph; empty when unknown
     * @return how many frame strokes were drawn, for the screen's diagnostic
     */
    static int drawSquare(GuiGraphics graphics, int x, int y, int slotEdge, WheelIcon icon,
            Optional<CharacterDef> definition) {
        int slot = Math.max(1, slotEdge);
        int inset = insetFor(icon.rings());
        int edge = Math.max(1, slot - 2 * inset);
        int pictureX = x + inset;
        int pictureY = y + inset;

        if (icon.texture().isPresent()) {
            ResourceLocation texture = icon.texture().get();
            // Official art: a player without astrum-assets.zip gets the 「请安装资源包」 placeholder instead
            // of the magenta checkerboard. See ArtFallback.
            graphics.blit(ArtFallback.resolve(texture), pictureX, pictureY, edge, edge, 0.0F, 0.0F, edge,
                    edge, edge, edge);
        } else {
            // No art: a tinted plate plus her initial. A square plate, not a disc — this is the same slot shape,
            // only without a picture to fill it.
            graphics.fill(pictureX, pictureY, pictureX + edge, pictureY + edge, icon.tint());
            drawGlyph(graphics, definition, pictureX + edge / 2.0, pictureY + edge / 2.0);
        }
        int drawn = drawSquareFrames(graphics, x, y, slot, icon);
        // The marker sits on the picture's top-right corner — not on the slot's corner, which is the text
        // column's first pixel at the compact tier.
        drawMarker(graphics, icon, pictureX + edge, pictureY);
        return drawn;
    }

    /**
     * {@return how many pixels the picture gives up on each side for {@code rings} frame strokes}
     *
     * <p>One pixel per stroke, so an enhanced icon's picture is two pixels smaller per side than a base one's.
     * The smallest slot this project draws into is a <b>14-pixel</b> party slot at the compact tier
     * ({@code slotSize} 18 minus 2 pixels of plate per side), where an enhanced icon still keeps a 10-pixel
     * picture; {@code WheelIconTest} asserts that rather than leaving it as a claim here.
     *
     * @param rings how many strokes the icon asks for
     */
    static int insetFor(int rings) {
        return Math.max(1, Math.min(rings, MAX_FRAMES));
    }

    /**
     * The outermost rectangle frame {@code frameIndex} occupies, in GUI pixels.
     *
     * <p>Pure, and the <b>only</b> place the frame's geometry is computed, so
     * {@code WheelIconDrawTest} can assert what the fix actually claims: the frame is a square that hugs the
     * slot, its stroke never covers the picture, and it never leaves the slot — which is what keeps it off the
     * text column and off the neighbouring cell. All three were wrong in the circular version, which cut the
     * picture's corners and hung outside them.
     *
     * @param x          the slot's left edge
     * @param y          the slot's top edge
     * @param slotEdge   the slot's edge in pixels
     * @param frameIndex {@code 0} for the inner frame, {@code 1} for the enhanced one
     */
    static Frame frameAt(int x, int y, int slotEdge, int frameIndex) {
        int depth = Math.max(0, Math.min(frameIndex, MAX_FRAMES - 1));
        return new Frame(x + depth, y + depth, x + slotEdge - depth, y + slotEdge - depth);
    }

    /**
     * One square frame, as the rectangle its one-pixel stroke is drawn on.
     *
     * @param left   the stroke's left column
     * @param top    the stroke's top row
     * @param right  one past the stroke's right column
     * @param bottom one past the stroke's bottom row
     */
    record Frame(int left, int top, int right, int bottom) {

        /** {@return whether this frame's stroke lies entirely outside a picture at {@code (x, y)} of {@code size}} */
        boolean clears(int pictureX, int pictureY, int pictureSize) {
            return left + WheelIconDraw.STROKE <= pictureX
                    && top + WheelIconDraw.STROKE <= pictureY
                    && right - WheelIconDraw.STROKE >= pictureX + pictureSize
                    && bottom - WheelIconDraw.STROKE >= pictureY + pictureSize;
        }

        /** {@return whether this frame lies inside the slot at {@code (x, y)} of {@code slotEdge}} */
        boolean inside(int x, int y, int slotEdge) {
            return left >= x && top >= y && right <= x + slotEdge && bottom <= y + slotEdge;
        }

        /** {@return the frame's width in pixels} */
        int width() {
            return right - left;
        }
    }

    /**
     * Draws the icon's frames as squares hugging the slot.
     *
     * <p>Each stroke is one pixel, nested inside the previous one, so the gold of an enhanced icon never covers
     * her face and never grows past the slot. Drawn as four {@code fill} calls per stroke rather than as a
     * polygon: a rectangle needs no winding argument and cannot be culled.
     *
     * @return how many strokes were drawn
     */
    private static int drawSquareFrames(GuiGraphics graphics, int x, int y, int slot,
            WheelIcon icon) {
        int drawn = 0;
        for (int frame = 0; frame < icon.rings(); frame++) {
            Frame bounds = frameAt(x, y, slot, frame);
            int colour = icon.tint();
            graphics.fill(bounds.left(), bounds.top(), bounds.right(), bounds.top() + STROKE, colour);
            graphics.fill(bounds.left(), bounds.bottom() - STROKE, bounds.right(), bounds.bottom(), colour);
            graphics.fill(bounds.left(), bounds.top(), bounds.left() + STROKE, bounds.bottom(), colour);
            graphics.fill(bounds.right() - STROKE, bounds.top(), bounds.right(), bounds.bottom(), colour);
            drawn++;
        }
        return drawn;
    }

    /**
     * Draws her initial, centred on a point, inside a plate that has no art.
     *
     * @return how many vertices this emitted (always {@code 0}: text is not ours to batch)
     */
    private static int drawGlyph(GuiGraphics graphics, Optional<CharacterDef> definition, double x,
            double y) {
        String glyph = definition.map(CharacterDef::displayName).filter(text -> !text.isEmpty())
                .map(text -> text.substring(0, GLYPH_LENGTH)).orElse("?");
        graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal(glyph),
                (int) x, (int) (y - Minecraft.getInstance().font.lineHeight / 2.0), GLYPH_COLOR);
        return 0;
    }

    /** Draws the tier-3 marker at the icon's top-right corner. */
    private static void drawMarker(GuiGraphics graphics, WheelIcon icon, double cornerX, double cornerY) {
        if (icon.marker().isEmpty()) {
            return;
        }
        graphics.drawCenteredString(Minecraft.getInstance().font,
                Component.literal(icon.marker()), (int) cornerX,
                (int) (cornerY - Minecraft.getInstance().font.lineHeight / 2.0), GLYPH_COLOR);
    }
}
