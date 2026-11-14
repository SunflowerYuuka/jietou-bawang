package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.config.ClientConfig;
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
 * The <b>cast pad</b>: five numbered chips, for the cast key's hold (spec §5.5, 2026-11-12).
 *
 * <h2>What it is for, and why it is not the ring</h2>
 * Holding the cast key means "I am about to fire, which number is which slot?". That is one question,
 * and the ring — five sectors, a flick to select, a preset outline — answers a different one ("which
 * direction am I sweeping?"). So the hold shows this strip and nothing else: a light hint in peripheral
 * vision, exactly as the spec asks (给一个轻量的槽位高亮条，不画整圈轮盘). The ring appears only for the
 * other key, where the only job is changing which slot a tap fires.
 *
 * <h2>A non-modal overlay, like the wheel</h2>
 * This is a GUI layer above the hotbar, not a {@code Screen}: {@code setScreen} releases every held key
 * and stops vanilla feeding the player's movement (proven on a real client, see
 * {@code task-12-report.md} §12 fix round 1, I5). A player is holding the cast key mid-fight; the pad
 * must not stop him walking.
 *
 * <h2>What it draws</h2>
 * Per chip: the digit the player must press ({@link CastPadGeometry#digit}), the slot's icon (or a dash
 * when the party slot is empty), and a plate that is brighter when the slot is occupied — so "which of
 * these five can I actually fire?" is answerable at a glance. The chip of the <b>current preset</b> gets
 * a gold outline, the same colour the ring uses for it, so the two overlays agree about what the preset
 * is. Geometry comes entirely from {@link CastPadGeometry}; icons from {@link WheelIconDraw}.
 *
 * <h2>What this class does not do</h2>
 * No input handling (the decision comes from {@link com.astrum.pcrd.impl.ub.WheelInputMachine}),
 * no casting (the digits go through {@code ClientEvents}), and no state of its own: it reads the
 * machine's purpose and the party cache on the frame it draws.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class CastPadOverlay {

    /** This layer's own id, so the per-frame diagnostic can name the layer it drew. */
    static final ResourceLocation LAYER_ID = InternalIds.id("ub_cast_pad");

    /** Plate colour of the strip; the alpha is added per frame from the wheel-opacity config. */
    private static final int PLATE_RGB = 0x16203A;

    /** Plate colour of an occupied slot: brighter, so "you can fire this one" reads instantly. */
    private static final int OCCUPIED_RGB = 0x2C3F6B;

    /** Plate colour of an empty slot: present, but clearly not a choice. */
    private static final int EMPTY_RGB = 0x1A1A1F;

    /** Opaque outline of the current preset's chip (gold, the same colour the ring uses for it). */
    private static final int PRESET_OUTLINE = 0xFFFFC93C;

    /** Opaque outline of every other chip. */
    private static final int CHIP_OUTLINE = 0xFF3A4A70;

    /** Opaque digit colour. */
    private static final int DIGIT_COLOR = 0xFFFFFFFF;

    /** Digit colour of an empty slot. */
    private static final int EMPTY_DIGIT_COLOR = 0xFF8A8A8A;

    /** Radius of the icon inside a chip, and of the disc a portrait-less character gets. */
    private static final double ICON_RADIUS = 6.0;

    /** Distance from a chip's top edge to the top of its digit line, in GUI pixels. */
    private static final double DIGIT_OFFSET_Y = 3.0;

    /** Distance from a chip's centre to the centre of its icon, in GUI pixels (below the digit). */
    private static final double ICON_OFFSET_Y = 5.0;

    private CastPadOverlay() {
    }

    /**
     * Draws the pad. Registered as a GUI layer above the hub HUD and above the wheel layer.
     *
     * @param graphics the GUI buffer
     * @param delta    frame delta; unused, the pad is drawn from input state, not from time
     */
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!ClientEvents.castPadUp()) {
            return;
        }

        float alpha = (float) WheelGeometry.clampAlpha(ClientConfig.WHEEL.wheelOpacity.get());
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int preset = WheelPreset.slot();
        int vertices = 0;

        for (int slot = 0; slot < CastPadGeometry.CHIPS; slot++) {
            CastPadGeometry.Chip chip = CastPadGeometry.chip(screenWidth, screenHeight, slot);
            Optional<ResourceLocation> id = ClientPartyCache.slot(slot);
            boolean occupied = id.isPresent();

            graphics.fill((int) chip.left(), (int) chip.top(), (int) chip.right(),
                    (int) chip.bottom(), argb(alpha, occupied ? OCCUPIED_RGB : EMPTY_RGB));
            graphics.renderOutline((int) chip.left(), (int) chip.top(), (int) chip.width(),
                    (int) chip.height(), slot == preset ? PRESET_OUTLINE : CHIP_OUTLINE);
            drawDigit(graphics, chip, slot, occupied);
            vertices += drawIcon(graphics, chip, id, slot);
        }
        Astrum.LOGGER.debug("astrum: cast pad frame layer={} alpha={} plate={} emptyPlate={} "
                        + "chips={} chip={}x{} at y={} preset={} vertices={}",
                LAYER_ID, alpha, hex(argb(alpha, OCCUPIED_RGB)), hex(argb(alpha, EMPTY_RGB)),
                CastPadGeometry.CHIPS, (int) CastPadGeometry.CHIP_WIDTH,
                (int) CastPadGeometry.CHIP_HEIGHT,
                (int) CastPadGeometry.chip(screenWidth, screenHeight, 0).top(), preset, vertices);
    }

    /** Draws the digit the player must press, centred over the chip. */
    private static void drawDigit(GuiGraphics graphics, CastPadGeometry.Chip chip, int slot,
            boolean occupied) {
        String digit = Integer.toString(CastPadGeometry.digit(slot));
        graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal(digit),
                (int) chip.centerX(), (int) (chip.top() + DIGIT_OFFSET_Y),
                occupied ? DIGIT_COLOR : EMPTY_DIGIT_COLOR);
    }

    /**
     * Draws the slot's icon under its digit, or a dash for an empty slot.
     *
     * <p>An empty slot still shows its digit: the pad's job is to say which key fires which slot, and
     * hiding the number of an empty slot would make the strip's numbering look broken.
     */
    private static int drawIcon(GuiGraphics graphics, CastPadGeometry.Chip chip,
            Optional<ResourceLocation> id, int slot) {
        double x = chip.centerX();
        double y = chip.centerY() + ICON_OFFSET_Y;
        if (id.isEmpty()) {
            graphics.drawCenteredString(Minecraft.getInstance().font, Component.literal("-"),
                    (int) x, (int) (y - Minecraft.getInstance().font.lineHeight / 2.0), EMPTY_DIGIT_COLOR);
            return 0;
        }
        Optional<CharacterDef> definition = AstrumApi.characters()
                .flatMap(registry -> Optional.ofNullable(registry.get(id.get())));
        WheelIcon icon = WheelIcon.of(definition.flatMap(CharacterDef::face),
                ClientPartyCache.enhanced(slot));
        return WheelIconDraw.draw(graphics, x, y, icon, definition, ICON_RADIUS);
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

