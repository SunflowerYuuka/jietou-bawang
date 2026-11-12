package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.config.ClientConfig;
import com.astrum.pcrd.impl.config.WheelMode;
import java.util.Optional;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * The radial UB menu, drawn as a <b>non-modal overlay</b>: a GUI layer above the hotbar, not a
 * {@code Screen} (spec §5.5).
 *
 * <h2>Why not a Screen — the whole reason this class exists</h2>
 * {@code Minecraft.setScreen} calls {@code KeyMapping.releaseAll()} and, while any screen is up,
 * {@code KeyMapping.isDown()} answers {@code false} for the movement keys, so vanilla stops feeding the
 * player's own input. A player holding {@code W} when the wheel opened stood still for its whole
 * duration — verified on a real client in {@code task-12-report.md} §12 fix round 1 (I5), judged a
 * playability defect by the human ("战斗的时候把所有按键锁住的话，肯定很卡手"). So the wheel is painted by
 * a {@link net.minecraft.client.gui.LayeredDraw.Layer} registered above the hotbar, exactly like
 * {@link UbHud}, the world keeps ticking, and no key is released by opening or closing it.
 *
 * <h2>Shape of the thing</h2>
 * Segment {@code i} is party slot {@code i}. {@link WheelGeometry} says where the ring is, so the
 * "ring radius 27% of screen height, band 8%, alpha 0.75" claims are the same numbers the tests assert;
 * this class is quads and text on top of those answers and computes no geometry of its own.
 *
 * <p>Two layouts, chosen by the player in {@code [wheel] wheelMode} (spec §5.5):
 *
 * <ul>
 *   <li>{@code DISC} (default): each sector's plate runs from the centre out to the outer edge, so the
 *       wheel is one complete translucent disc, and each sector's icon+label sits at that sector's
 *       <b>centroid</b> ({@link WheelGeometry#segmentCentroidX}).</li>
 *   <li>{@code RING}: the plate is the band only ({@code innerRadius..outerRadius}), leaving the middle
 *       clear so more of the battlefield shows through, with icons and labels on the band.</li>
 * </ul>
 *
 * <p>Both modes keep the <b>centre dead zone</b>: a position inside the inner radius selects nothing
 * ({@link WheelGeometry#segmentAt}), so movement through the middle of the wheel never shuffles the
 * preset, whether or not the middle is painted.
 *
 * <h2>Two meanings of a pick</h2>
 * Moving selects <b>the preset</b> ({@link WheelPreset}) and never casts; casting is the digit
 * {@code 1}–{@code 5} (handled by {@link ClientEvents}) or letting go of the release key. That split is
 * spec §5.5's input table, and it exists so a player can sweep the wheel to look without firing
 * anything.
 *
 * <h2>What this class does not do</h2>
 * No input handling at all (the highlight it draws comes from {@link WheelSession}, fed by the client
 * tick), no cursor work (in game the cursor is already grabbed by the game; the selection is by flick
 * direction, see {@link WheelFlick}), no pause, and nothing client-authoritative: picking sends
 * {@code UbCastPayload} and the server validates (spec §5.5/§9.5).
 *
 * <p>The overlay is visible only while the wheel is up and — like every other HUD layer — not while
 * {@code F1} hides the GUI, because vanilla skips the whole GUI layer stack then. That is vanilla's
 * behaviour for the HUD, inherited rather than invented.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class WheelOverlay {

    /** Band colour behind the segments; the alpha is added per frame from the config. */
    private static final int BAND_RGB = 0x16203A;

    /** Band colour of the segment the last flick selected. */
    private static final int HOVER_RGB = 0x2C3F6B;

    /** Band colour of an empty slot: present, but clearly not a choice. */
    private static final int EMPTY_RGB = 0x1A1A1F;

    /** Opaque outline of the preset segment (gold, spec §6.2's tier-3 colour family). */
    private static final int PRESET_OUTLINE = 0xFFFFC93C;

    /** Opaque outline of the selected segment. */
    private static final int HOVER_OUTLINE = 0xFFFFFFFF;

    /** Opaque name text. */
    private static final int NAME_COLOR = 0xFFFFFFFF;

    /** Name text of a slot with nothing in it. */
    private static final int EMPTY_NAME_COLOR = 0xFF8A8A8A;

    /** Thickness of the outlines, in GUI pixels. */
    private static final double OUTLINE_THICKNESS = 1.0;

    /** Angular width of the radial edges of an outline, in degrees. */
    private static final double RADIAL_EDGE_DEGREES = 0.8;

    private WheelOverlay() {
    }

    /**
     * Draws the wheel. Registered as a GUI layer above the hotbar.
     *
     * @param graphics the GUI buffer
     * @param delta    frame delta; unused, the wheel is drawn from input state, not from time
     */
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!ClientEvents.machine().capturesHotbarDigits()) {
            return;
        }

        WheelMode mode = ClientConfig.WHEEL.wheelMode.get();
        float alpha = (float) WheelGeometry.clampAlpha(ClientConfig.WHEEL.wheelOpacity.get());
        WheelGeometry.Ring ring = WheelGeometry.ring(graphics.guiWidth(), graphics.guiHeight(),
                ClientConfig.WHEEL.wheelScale.get(), WheelGeometry.BAND_FRACTION);
        int selected = ClientEvents.session().hovered();
        WheelCellLayout layout = mode == WheelMode.DISC
                ? WheelCellLayout.unconstrained(minecraft.font.lineHeight)
                : WheelCellLayout.fitBand(ring.bandWidth(), minecraft.font.lineHeight);
        boolean centreFilled = mode == WheelMode.DISC;

        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            drawSegment(graphics, ring, slot, alpha, centreFilled, selected);
        }
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            if (slot == WheelPreset.slot() || slot == selected) {
                drawOutline(graphics, ring, slot);
            }
        }
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            drawContents(graphics, ring, slot, mode, layout);
        }
    }

    /**
     * Draws one sector's translucent plate.
     *
     * <p>The plate starts at the centre in {@code DISC} and at the band's inner edge in {@code RING}
     * ({@link WheelGeometry#plateInnerRadius}), so the sectors tile one disc or one ring — either way
     * every pixel of the wheel is painted once, at the configured alpha, and no two sectors overlap
     * into a darker seam.
     */
    private static void drawSegment(GuiGraphics graphics, WheelGeometry.Ring ring, int slot,
            float alpha, boolean centreFilled, int selected) {
        boolean occupied = ClientPartyCache.slot(slot).isPresent();
        int rgb = !occupied ? EMPTY_RGB : (slot == selected ? HOVER_RGB : BAND_RGB);
        GuiShapes.band(graphics, ring.centerX(), ring.centerY(),
                WheelGeometry.plateInnerRadius(ring, centreFilled), ring.outerRadius(),
                WheelGeometry.segmentStartDegrees(slot), WheelGeometry.segmentEndDegrees(slot),
                argb(alpha, rgb));
    }

    /**
     * Draws the sector's edges only, so a selected or preset sector is marked without its fill getting
     * a different alpha from the configured one.
     */
    private static void drawOutline(GuiGraphics graphics, WheelGeometry.Ring ring, int slot) {
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

    /**
     * Draws the sector's icon and label.
     *
     * <p>Where they go is the one placement difference between the modes: the band's mid radius
     * ({@link WheelGeometry#segmentCenterX}) for {@code RING}, the sector's own centroid
     * ({@link WheelGeometry#segmentCentroidX}) for {@code DISC}. How big they are is
     * {@link WheelCellLayout}'s answer, which shrinks the stack with the band so a thin band cannot
     * clip it.
     */
    private static void drawContents(GuiGraphics graphics, WheelGeometry.Ring ring, int slot,
            WheelMode mode, WheelCellLayout layout) {
        double x = mode == WheelMode.DISC
                ? WheelGeometry.segmentCentroidX(ring, slot)
                : WheelGeometry.segmentCenterX(ring, slot);
        double y = mode == WheelMode.DISC
                ? WheelGeometry.segmentCentroidY(ring, slot)
                : WheelGeometry.segmentCenterY(ring, slot);

        Optional<ResourceLocation> id = ClientPartyCache.slot(slot);
        if (id.isEmpty()) {
            graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal("-"),
                    (int) x, (int) (y + layout.labelOffsetY()), EMPTY_NAME_COLOR);
            return;
        }

        boolean enhanced = ClientPartyCache.enhanced(slot);
        Optional<CharacterDef> def = definition(id.get());
        WheelIcon icon = WheelIcon.of(def.flatMap(CharacterDef::portrait), enhanced);
        if (layout.showIcon()) {
            drawIcon(graphics, x, y + layout.iconOffsetY(), icon, def, layout.iconRadius());
        }

        String name = def.map(CharacterDef::displayName).orElse(id.get().getPath());
        graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal(name),
                (int) x, (int) (y + layout.labelOffsetY()), NAME_COLOR);
    }

    /**
     * Draws one icon: its ring(s), the portrait or a glyph disc, and the tier-3 marker.
     *
     * <p>The rings and the marker are what make tier 3 visible when a pack ships no portrait art:
     * gold instead of white, two rings instead of one, and the {@code II} marker.
     */
    private static void drawIcon(GuiGraphics graphics, double x, double y, WheelIcon icon,
            Optional<CharacterDef> def, double iconRadius) {
        for (int ringIndex = 0; ringIndex < icon.rings(); ringIndex++) {
            GuiShapes.outline(graphics, x, y, iconRadius + 1.0 + ringIndex * 1.5, 1.0, icon.tint());
        }
        if (icon.texture().isPresent()) {
            ResourceLocation texture = icon.texture().get();
            int diameter = (int) (iconRadius * 2);
            graphics.blit(texture, (int) (x - iconRadius), (int) (y - iconRadius), 0, 0,
                    diameter, diameter, diameter, diameter);
        } else {
            GuiShapes.disc(graphics, x, y, iconRadius, icon.tint());
            String glyph = def.map(CharacterDef::displayName).filter(text -> !text.isEmpty())
                    .map(text -> text.substring(0, 1)).orElse("?");
            graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal(glyph),
                    (int) x, (int) (y - Minecraft.getInstance().font.lineHeight / 2.0), 0xFF101010);
        }
        if (!icon.marker().isEmpty()) {
            graphics.drawCenteredString(Minecraft.getInstance().font,
                    Component.literal(icon.marker()), (int) (x + iconRadius),
                    (int) (y - iconRadius - Minecraft.getInstance().font.lineHeight / 2.0),
                    0xFF101010);
        }
    }

    /** {@return the character definition, if the pack still defines it and the client has the registry} */
    private static Optional<CharacterDef> definition(ResourceLocation id) {
        return AstrumApi.characters().flatMap(registry -> Optional.ofNullable(registry.get(id)));
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}
