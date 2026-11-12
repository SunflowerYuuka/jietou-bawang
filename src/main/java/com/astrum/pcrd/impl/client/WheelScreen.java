package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.config.ClientConfig;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/**
 * The radial UB menu: five segments, no interior, no background dim (spec §5.5).
 *
 * <h2>Shape of the thing</h2>
 * Segment {@code i} is party slot {@code i}. The band is drawn where {@link WheelGeometry} says it is,
 * so the "ring at 35–45% of screen height, band ≈12%, interior completely empty" claims are the same
 * numbers the tests assert. Nothing is drawn inside the inner radius: the crosshair, and whatever the
 * player is aiming at, must stay visible through the middle of the menu — which is also why
 * {@link #renderBackground} is a no-op instead of vanilla's blurred menu background.
 *
 * <h2>Two meanings of a click</h2>
 * Hovering a segment only <b>moves the preset</b> ({@link WheelPreset}); it never casts. Casting is the
 * digit {@code 1}–{@code 5} (handled here, see {@link #keyPressed}) or letting go of the key, which
 * {@code ClientEvents} turns into a tap cast of the preset. That split is spec §5.5's input table, and
 * it exists so a player can sweep the wheel to look without firing anything.
 *
 * <h2>The hotbar, and why the digits never leak</h2>
 * While this screen is up, {@code 1}–{@code 5} must not change the hotbar slot. Two things enforce it:
 * vanilla does not record key bindings at all while a screen is open, and this screen additionally
 * consumes the digit (returning {@code true}) so nothing downstream sees it either. The rule "is the
 * wheel capturing digits?" is asked of {@code WheelInputMachine.capturesHotbarDigits()} rather than
 * re-derived here, so there is one answer to that question in the codebase.
 *
 * <h2>What is deliberately not done</h2>
 * No {@code pauseGame}, no movement lock, no mouse grab of its own: opening a {@link Screen} already
 * hands the mouse to the GUI (so the camera stops turning) and hands it back on close, which is
 * exactly "capture the mouse only". The world keeps ticking behind the menu.
 */
@ApiStatus.Internal
public final class WheelScreen extends Screen {

    /** Band colour behind the segments; the alpha is added per frame from the config. */
    private static final int BAND_RGB = 0x16203A;

    /** Band colour of the segment under the mouse. */
    private static final int HOVER_RGB = 0x2C3F6B;

    /** Band colour of an empty slot: present, but clearly not a choice. */
    private static final int EMPTY_RGB = 0x1A1A1F;

    /** Opaque outline of the preset segment (gold, spec §6.2's tier-3 colour family). */
    private static final int PRESET_OUTLINE = 0xFFFFC93C;

    /** Opaque outline of the hovered segment. */
    private static final int HOVER_OUTLINE = 0xFFFFFFFF;

    /** Opaque name text. */
    private static final int NAME_COLOR = 0xFFFFFFFF;

    /** Name text of a slot with nothing in it. */
    private static final int EMPTY_NAME_COLOR = 0xFF8A8A8A;

    /** Radius of the icon disc drawn at a segment's centre. */
    private static final double ICON_RADIUS = 8.0;

    /** Thickness of the outlines, in GUI pixels. */
    private static final double OUTLINE_THICKNESS = 1.0;

    /** Angular width of the radial edges of an outline, in degrees. */
    private static final double RADIAL_EDGE_DEGREES = 0.8;

    private int hovered = WheelGeometry.NO_SEGMENT;

    public WheelScreen() {
        super(Component.translatable("screen.astrum.ub_wheel"));
    }

    /**
     * The world keeps running behind the wheel (spec §5.5: 不暂停世界、不锁玩家移动).
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Draws nothing: no blur, no menu-background texture, no dim.
     *
     * <p>Everything vanilla's default would paint here would sit behind the empty middle of the ring
     * and hide the very thing the interior is kept clear for.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // intentionally empty
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        float alpha = (float) WheelGeometry.clampAlpha(ClientConfig.WHEEL.wheelOpacity.get());
        WheelGeometry.Ring ring = ring();
        updateHover(mouseX, mouseY);

        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            drawSegment(graphics, ring, slot, alpha);
        }
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            if (slot == WheelPreset.slot() || slot == hovered) {
                drawOutline(graphics, ring, slot);
            }
        }
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            drawContents(graphics, ring, slot);
        }
        // Nothing else: the middle of the screen stays empty on purpose.
    }

    /**
     * Digits {@code 1}–{@code 5} cast the slot under them; nothing else here intercepts keys.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (ClientEvents.machine().capturesHotbarDigits()) {
            int digit = hotbarDigit(keyCode);
            if (digit > 0) {
                ClientEvents.onWheelDigit(digit);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Keeps the hover live while the mouse moves, so the preset follows the pointer immediately. */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        updateHover((int) mouseX, (int) mouseY);
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public void onClose() {
        ClientEvents.onWheelScreenClosed();
        super.onClose();
    }

    /** {@return the band for this frame, from the configured radius and the screen size} */
    WheelGeometry.Ring ring() {
        return WheelGeometry.ring(width, height,
                ClientConfig.WHEEL.wheelScale.get(), WheelGeometry.BAND_FRACTION);
    }

    /**
     * Recomputes which segment the mouse is over, and moves the preset to it.
     *
     * <p>Only a segment that actually holds a character can become the preset: hovering empty space
     * must not silently redirect the next tap cast. The hover highlight itself is still dropped when
     * the pointer leaves the band, so the player sees the difference between "over slot 3" and
     * "over nothing".
     */
    private void updateHover(int mouseX, int mouseY) {
        int segment = WheelGeometry.segmentAt(ring(), mouseX, mouseY);
        hovered = segment;
        if (segment != WheelGeometry.NO_SEGMENT && ClientPartyCache.slot(segment).isPresent()) {
            WheelPreset.select(segment);
        }
    }

    private void drawSegment(GuiGraphics graphics, WheelGeometry.Ring ring, int slot, float alpha) {
        boolean occupied = ClientPartyCache.slot(slot).isPresent();
        int rgb = !occupied ? EMPTY_RGB : (slot == hovered ? HOVER_RGB : BAND_RGB);
        GuiShapes.band(graphics, ring.centerX(), ring.centerY(), ring.innerRadius(),
                ring.outerRadius(), WheelGeometry.segmentStartDegrees(slot),
                WheelGeometry.segmentEndDegrees(slot), argb(alpha, rgb));
    }

    /**
     * Draws the segment's edges only, so an empty or hovered segment is marked without the band
     * itself getting a different alpha from the configured one.
     */
    private void drawOutline(GuiGraphics graphics, WheelGeometry.Ring ring, int slot) {
        int colour = slot == WheelPreset.slot() ? PRESET_OUTLINE : HOVER_OUTLINE;
        double cx = ring.centerX();
        double cy = ring.centerY();
        double start = WheelGeometry.segmentStartDegrees(slot);
        double end = WheelGeometry.segmentEndDegrees(slot);
        GuiShapes.band(graphics, cx, cy, ring.innerRadius() - OUTLINE_THICKNESS,
                ring.innerRadius() + OUTLINE_THICKNESS, start, end, colour);
        GuiShapes.band(graphics, cx, cy, ring.outerRadius() - OUTLINE_THICKNESS,
                ring.outerRadius() + OUTLINE_THICKNESS, start, end, colour);
        GuiShapes.band(graphics, cx, cy, ring.innerRadius(), ring.outerRadius(), start,
                start + RADIAL_EDGE_DEGREES, colour);
        GuiShapes.band(graphics, cx, cy, ring.innerRadius(), ring.outerRadius(),
                end - RADIAL_EDGE_DEGREES, end, colour);
    }

    private void drawContents(GuiGraphics graphics, WheelGeometry.Ring ring, int slot) {
        double x = WheelGeometry.segmentCenterX(ring, slot);
        double y = WheelGeometry.segmentCenterY(ring, slot);
        Optional<ResourceLocation> id = ClientPartyCache.slot(slot);
        if (id.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("-"), (int) x, (int) y - 4,
                    EMPTY_NAME_COLOR);
            return;
        }

        boolean enhanced = ClientPartyCache.enhanced(slot);
        Optional<CharacterDef> def = definition(id.get());
        WheelIcon icon = WheelIcon.of(def.flatMap(CharacterDef::portrait), enhanced);
        drawIcon(graphics, x, y - 5.0, icon, def);

        String name = def.map(CharacterDef::displayName).orElse(id.get().getPath());
        graphics.drawCenteredString(font, Component.literal(name), (int) x, (int) y + 5,
                NAME_COLOR);
    }

    /**
     * Draws one icon: its ring(s), the portrait or a glyph disc, and the tier-3 marker.
     *
     * <p>The rings and the marker are what make tier 3 visible when a pack ships no portrait art:
     * gold instead of white, two rings instead of one, and the {@code II} marker.
     */
    private void drawIcon(GuiGraphics graphics, double x, double y, WheelIcon icon,
            Optional<CharacterDef> def) {
        for (int ringIndex = 0; ringIndex < icon.rings(); ringIndex++) {
            GuiShapes.outline(graphics, x, y, ICON_RADIUS + 1.0 + ringIndex * 1.5, 1.0,
                    icon.tint());
        }
        if (icon.texture().isPresent()) {
            ResourceLocation texture = icon.texture().get();
            graphics.blit(texture, (int) (x - ICON_RADIUS), (int) (y - ICON_RADIUS), 0, 0,
                    (int) (ICON_RADIUS * 2), (int) (ICON_RADIUS * 2),
                    (int) (ICON_RADIUS * 2), (int) (ICON_RADIUS * 2));
        } else {
            GuiShapes.disc(graphics, x, y, ICON_RADIUS, icon.tint());
            String glyph = def.map(CharacterDef::displayName).filter(text -> !text.isEmpty())
                    .map(text -> text.substring(0, 1)).orElse("?");
            graphics.drawCenteredString(font, Component.literal(glyph), (int) x,
                    (int) (y - font.lineHeight / 2.0), 0xFF101010);
        }
        if (!icon.marker().isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(icon.marker()),
                    (int) (x + ICON_RADIUS), (int) (y - ICON_RADIUS - font.lineHeight / 2.0), 0xFF101010);
        }
    }

    /** {@return the character definition, if the pack still defines it and the client has the registry} */
    private static Optional<CharacterDef> definition(ResourceLocation id) {
        return AstrumApi.characters().flatMap(registry ->
                Optional.ofNullable(registry.get(id)));
    }

    /**
     * {@return the hotbar slot a key code would select, {@code 1}–{@code 5}, or {@code 0} for other keys}
     *
     * <p>Deliberately narrow: {@code 1}–{@code 5} are the five wheel slots. Digits {@code 6}–{@code 9}
     * are hotbar slots with no wheel meaning, so they fall through to vanilla's (which does nothing
     * while a screen is open).
     *
     * @param keyCode GLFW key code
     */
    private static int hotbarDigit(int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_1 -> 1;
            case GLFW.GLFW_KEY_2 -> 2;
            case GLFW.GLFW_KEY_3 -> 3;
            case GLFW.GLFW_KEY_4 -> 4;
            case GLFW.GLFW_KEY_5 -> 5;
            default -> 0;
        };
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

}
