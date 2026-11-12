package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Draws one character icon — its ring(s), its portrait or glyph disc, and the tier-3 marker.
 *
 * <h2>Why this is a class and not two copies</h2>
 * Two overlays show the same character in the same way: the ring wheel's sectors ({@link WheelOverlay})
 * and the cast pad's chips ({@link CastPadOverlay}). The icon is not a trivial blit — the rings, the
 * glyph fallback and the {@code II} marker are what make tier 3 visible when a pack ships no portrait
 * art (spec §6.2), which {@link WheelIconTest} pins as <em>data</em>. Drawing that twice would mean one
 * of the two eventually losing the marker. So the drawing lives once, here, and both overlays call it.
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

    private WheelIconDraw() {
    }

    /**
     * Draws one icon centred on a point.
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
            String glyph = definition.map(CharacterDef::displayName).filter(text -> !text.isEmpty())
                    .map(text -> text.substring(0, GLYPH_LENGTH)).orElse("?");
            graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal(glyph),
                    (int) x, (int) (y - Minecraft.getInstance().font.lineHeight / 2.0), GLYPH_COLOR);
        }
        if (!icon.marker().isEmpty()) {
            graphics.drawCenteredString(Minecraft.getInstance().font,
                    Component.literal(icon.marker()), (int) (x + iconRadius),
                    (int) (y - iconRadius - Minecraft.getInstance().font.lineHeight / 2.0),
                    GLYPH_COLOR);
        }
        return vertices;
    }
}

