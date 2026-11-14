package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.InternalIds;
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
 * {@code 1}–{@code 5} or the tap on the <em>other</em> key. That split is spec §5.5's input table, and
 * it exists so a player can sweep the wheel to look without firing anything. Since the 2026-11-12
 * decision the split is even sharper: <b>this</b> overlay belongs to the preset wheel key only
 * ({@code key.astrum.ub_preset_wheel}, default {@code B}), a digit pressed while it is up sets the preset
 * and never casts, and the cast key's hold raises {@link CastPadOverlay} instead of this ring. The
 * overlay draws while {@link ClientEvents#presetWheelUp()} is true, which is that key's own machine.
 *
 * <h2>What this class does not do</h2>
 * No input handling at all (the highlight it draws comes from {@link WheelSession}, fed by the client
 * tick), no cursor work (in game the cursor is already grabbed by the game; the selection is by flick
 * direction, see {@link WheelFlick}), no pause, and nothing client-authoritative: a cast is sent as
 * {@code UbCastPayload} and the server validates (spec §5.5/§9.5).
 *
 * <p>The overlay is visible only while the wheel is up and — like every other HUD layer — not while
 * {@code F1} hides the GUI, because vanilla skips the whole GUI layer stack then. That is vanilla's
 * behaviour for the HUD, inherited rather than invented.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class WheelOverlay {

    /** This layer's own id, so the per-frame diagnostic can name the layer it drew. */
    static final ResourceLocation LAYER_ID = InternalIds.id("ub_wheel");

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
        if (!ClientEvents.presetWheelUp()) {
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

        int vertices = 0;
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            vertices += drawSegment(graphics, ring, slot, alpha, centreFilled, selected);
        }
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            if (slot == WheelPreset.slot() || slot == selected) {
                vertices += drawOutline(graphics, ring, slot);
            }
        }
        for (int slot = 0; slot < WheelGeometry.SEGMENTS; slot++) {
            vertices += drawContents(graphics, ring, slot, mode, layout);
        }
        Astrum.LOGGER.debug("astrum: wheel frame layer={} mode={} alpha={} plate={} emptyPlate={} "
                        + "radius={}..{} (band {} px, {} of height) preset={} hovered={} vertices={}",
                LAYER_ID, mode, alpha, hex(argb(alpha, BAND_RGB)), hex(argb(alpha, EMPTY_RGB)),
                ring.innerRadius(), ring.outerRadius(), ring.bandWidth(),
                ring.midRadius() / Math.max(1.0, graphics.guiHeight()), WheelPreset.slot(), selected,
                vertices);
    }

    /**
     * Draws one sector's translucent plate.
     *
     * <p>The plate starts at the centre in {@code DISC} and at the band's inner edge in {@code RING}
     * ({@link WheelGeometry#plateInnerRadius}), so the sectors tile one disc or one ring — either way
     * every pixel of the wheel is painted once, at the configured alpha, and no two sectors overlap
     * into a darker seam.
     */
    private static int drawSegment(GuiGraphics graphics, WheelGeometry.Ring ring, int slot,
            float alpha, boolean centreFilled, int selected) {
        boolean occupied = ClientPartyCache.slot(slot).isPresent();
        int rgb = !occupied ? EMPTY_RGB : (slot == selected ? HOVER_RGB : BAND_RGB);
        return GuiShapes.band(graphics, ring.centerX(), ring.centerY(),
                WheelGeometry.plateInnerRadius(ring, centreFilled), ring.outerRadius(),
                WheelGeometry.segmentStartDegrees(slot), WheelGeometry.segmentEndDegrees(slot),
                argb(alpha, rgb));
    }

    /**
     * Draws the sector's edges only, so a selected or preset sector is marked without its fill getting
     * a different alpha from the configured one.
     */
    private static int drawOutline(GuiGraphics graphics, WheelGeometry.Ring ring, int slot) {
        int colour = slot == WheelPreset.slot() ? PRESET_OUTLINE : HOVER_OUTLINE;
        double cx = ring.centerX();
        double cy = ring.centerY();
        double start = WheelGeometry.segmentStartDegrees(slot);
        double end = WheelGeometry.segmentEndDegrees(slot);
        int vertices = 0;
        vertices += GuiShapes.band(graphics, cx, cy, ring.innerRadius() - OUTLINE_THICKNESS,
                ring.innerRadius() + OUTLINE_THICKNESS, start, end, colour);
        vertices += GuiShapes.band(graphics, cx, cy, ring.outerRadius() - OUTLINE_THICKNESS,
                ring.outerRadius() + OUTLINE_THICKNESS, start, end, colour);
        vertices += GuiShapes.band(graphics, cx, cy, ring.innerRadius(), ring.outerRadius(), start,
                start + RADIAL_EDGE_DEGREES, colour);
        vertices += GuiShapes.band(graphics, cx, cy, ring.innerRadius(), ring.outerRadius(),
                end - RADIAL_EDGE_DEGREES, end, colour);
        return vertices;
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
    private static int drawContents(GuiGraphics graphics, WheelGeometry.Ring ring, int slot,
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
            return 0;
        }

        boolean enhanced = ClientPartyCache.enhanced(slot);
        Optional<CharacterDef> def = definition(id.get());
        // The tier avatar, not the plain face: the wheel is one of the surfaces a player reads "how grown is
        // she" from, and a wheel that showed the enrolled picture while the book beside it showed her 6★ art
        // would be the same two-fields defect the book's face resolution exists to prevent.
        WheelIcon icon = WheelIcon.of(
                def.flatMap(definition -> TierAvatar.forPartySlot(definition, id.get())), enhanced);
        int vertices = 0;
        if (layout.showIcon()) {
            // The icon itself is drawn by WheelIconDraw, which the cast pad also uses: the rings, the
            // glyph fallback and the tier-3 marker exist once, so the two overlays cannot drift apart.
            vertices += WheelIconDraw.draw(graphics, x, y + layout.iconOffsetY(), icon, def,
                    layout.iconRadius());
        }

        String name = def.map(CharacterDef::displayName).orElse(id.get().getPath());
        graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal(name),
                (int) x, (int) (y + layout.labelOffsetY()), NAME_COLOR);
        return vertices;
    }

    /** {@return the character definition, if the pack still defines it and the client has the registry} */
    private static Optional<CharacterDef> definition(ResourceLocation id) {
        return AstrumApi.characters().flatMap(registry -> Optional.ofNullable(registry.get(id)));
    }

    /** {@return an ARGB colour as a printable {@code #AARRGGBB}, for the per-frame diagnostic} */
    private static String hex(int argb) {
        return String.format("#%08X", argb);
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        return (a << 24) | (rgb & 0x00FFFFFF);
    }
}



