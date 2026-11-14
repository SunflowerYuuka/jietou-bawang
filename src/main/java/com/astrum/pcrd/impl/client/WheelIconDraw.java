package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Draws one character icon — its frame(s), its portrait or glyph disc, and the tier-3 marker.
 *
 * <h2>Two frames, because there are two shapes</h2>
 * The same character appears in a <b>circular</b> place (the ring wheel's sectors, the cast pad's chips) and a
 * <b>square</b> one (the 角色册's roster cells and party slots). Those are not the same drawing, and the
 * difference is not cosmetic:
 *
 * <ul>
 *   <li>{@link #draw} — the circular form. Rings concentric with the disc, which is what a radial menu's
 *       vocabulary is and what a portrait-less character's disc needs.</li>
 *   <li>{@link #drawSquare} — the square form, for the book. The picture there is a square blit, so a circular
 *       ring <b>crosses it</b>: the ring's radius is half the picture's edge, so it touches the four edge
 *       midpoints, cuts through the corners of her face, and leaves the picture's corners sticking out past the
 *       ring. That is the "empty circle around the avatar" the human reported twice (「角色册里面头像的那个圆圈」,
 *       「要么你用角色头像填充这个圆，要么把圆删除」). A square frame is the frame that fits a square picture: it hugs
 *       the slot the layout reserved, and nothing is cut.</li>
 * </ul>
 *
 * <h2>Why not mask the picture into a circle instead</h2>
 * That would be the other half of his sentence ("fill this circle with her avatar"), and it is the one thing
 * the GUI pipeline here cannot do: {@code GuiGraphics.blit} has no circular clip, and doing it with a shader
 * would put a per-icon render pass behind every roster cell. A frame that matches the picture's own shape gets
 * the same result — her face, whole, with a gold outline — without one.
 *
 * <h2>Why it is a class and not two copies</h2>
 * Two overlays and the book all draw the same icon, and the icon is not a trivial blit: the frames, the glyph
 * fallback and the {@code II} marker are what make tier 3 visible when a pack ships no portrait art (spec §6.2),
 * which {@link WheelIconTest} pins as <em>data</em>. Drawing that per surface would mean one of them eventually
 * losing the marker. So it lives once, here, and every surface calls it.
 *
 * <p>Everything it draws is opaque (spec §5.5: 图标与文字不透明) whatever the plate behind it does, which
 * is why the colours come from {@link WheelIcon} rather than from a per-frame alpha.
 */
@ApiStatus.Internal
final class WheelIconDraw {

    /** Opaque colour of the glyph drawn inside a portrait-less disc: dark, on the light tint. */
    private static final int GLYPH_COLOR = 0xFF101010;

    /** Prefix length of the one-character glyph used when a character has no portrait. */
    private static final int GLYPH_LENGTH = 1;

    /** Thickness of one frame stroke, square or round: one GUI pixel, the same as the old ring. */
    private static final int STROKE = 1;

    /**
     * The most frame strokes an icon can carry.
     *
     * <p>Bounded because the frames are nested <em>inside</em> the slot: every stroke costs the picture one pixel
     * per side, so an unbounded count could shrink the portrait to nothing. Two is {@link WheelIcon#ENHANCED_RINGS};
     * the guard is here so a future third ring is a deliberate decision about the picture's size rather than a
     * silent one.
     */
    private static final int MAX_FRAMES = 2;
    private WheelIconDraw() {
    }

    /**
     * Draws one circular icon centred on a point: rings, then the portrait or glyph disc, then the marker.
     *
     * @param graphics   the GUI buffer
     * @param x          centre x
     * @param y          centre y
     * @param icon       what to draw, from {@link WheelIcon#of}
     * @param definition the character, for the name-derived glyph; empty when unknown
     * @param iconRadius radius of the disc
     * @return how many vertices were emitted, for the overlay's per-frame diagnostic
     */
    static int draw(GuiGraphics graphics, double x, double y, WheelIcon icon,
            Optional<CharacterDef> definition, double iconRadius) {
        int vertices = 0;
        for (int ringIndex = 0; ringIndex < icon.rings(); ringIndex++) {
            vertices += GuiShapes.outline(graphics, x, y, iconRadius + 1.0 + ringIndex * 1.5, 1.0,
                    icon.tint());
        }
        if (icon.texture().isPresent()) {
            ResourceLocation texture = icon.texture().get();
            int diameter = (int) (iconRadius * 2);
            graphics.blit(texture, (int) (x - iconRadius), (int) (y - iconRadius), 0, 0,
                    diameter, diameter, diameter, diameter);
        } else {
            vertices += GuiShapes.disc(graphics, x, y, iconRadius, icon.tint());
            vertices += drawGlyph(graphics, definition, x, y);
        }
        drawMarker(graphics, icon, x + iconRadius, y - iconRadius);
        return vertices;
    }

    /**
     * Draws one <b>square</b> icon into a slot of {@code slotEdge} pixels at {@code (x, y)}: frames at the slot's
     * edge, the picture inset inside them, then the marker.
     *
     * <p>The book's form. See the class javadoc for why the frame is square here and round in {@link #draw}:
     * the picture is square, so a round frame cuts it.
     *
     * <h2>Why the picture is inset rather than the frame drawn outside</h2>
     * The slot's edge is fixed by {@code BookLayout} — the roster cell leaves {@code iconInset} pixels beside it
     * and starts the text column {@code textGap} pixels after it, and in the compact tier that gap is <b>one
     * pixel</b>. A frame drawn outside the picture would therefore land on the level/name text at the small
     * tier. So the frames live <em>inside</em> the slot and the picture gives up one pixel per stroke: nothing
     * is drawn outside the rectangle the layout reserved, at either tier, and no stroke can touch the text.
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
            graphics.blit(texture, pictureX, pictureY, edge, edge, 0.0F, 0.0F, edge, edge, edge, edge);
        } else {
            // No art: a tinted plate plus her initial, the same fallback the circular form draws.
            graphics.fill(pictureX, pictureY, pictureX + edge, pictureY + edge, icon.tint());
            drawGlyph(graphics, definition, pictureX + edge / 2.0, pictureY + edge / 2.0);
        }
        int drawn = drawSquareFrames(graphics, x, y, slot, icon);
        // The marker sits on the picture's top-right corner, exactly where the circular form puts it — not on
        // the slot's corner, which is the text column's first pixel at the compact tier.
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
     * @return the vertices this emitted (always {@code 0}: text is not ours to batch)
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
