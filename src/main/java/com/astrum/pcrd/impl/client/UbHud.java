package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.config.ClientConfig;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * The UB gauge on the HUD: the <b>preset character's face</b>, a cooldown ring, an official-art charge
 * bar, and the refusal shake (spec §5.4, §7).
 *
 * <h2>Where it goes, and what it must not cover</h2>
 * The position comes from {@link HudLayout}, whose default anchor is the bottom-left well above the
 * hotbar — clear of the hotbar (bottom centre) and of the top-right corner where Jade/AppleSkin-class
 * mods draw. Both are asserted as rectangles in {@code HudLayoutTest}, because "does not collide" is
 * a claim about geometry, not a taste. Players who rearranged their own HUD can move it or turn it
 * off entirely ({@code [hud] enableUbHud}).
 *
 * <h2>What the left circle is for</h2>
 * It used to be an empty cooldown ring, so the gauge did not say <em>whose</em> ultimate the bar was
 * charging. It now draws the <b>current preset character's face</b> (「UB条最左边的圆圈，显示的应该是当前
 * 预设的角色的头像」): the ring is the cooldown, the face inside it is who will cast. The resolution is
 * {@link PresetFace}'s, and it answers "nothing" — leaving the circle empty — for an empty preset slot,
 * a party the client has not received, or a character with no face art.
 *
 * <h2>What the bar is drawn from</h2>
 * <b>Official PCR art</b>, two layers: {@code gui/ub_gauge_track.png} under {@code
 * gui/ub_gauge_fill.png}, both cut from the game's own <b>horizontal fever bar</b>
 * ({@code pct_fever_bar.png}) by {@code tools/gen_ub_gauge_texture.py}. The fill is <b>clipped</b>, not
 * squashed: the destination and the source crop are the same charge fraction, which is {@link GaugeArt}'s
 * arithmetic and {@code GaugeArtTest}'s assertion.
 *
 * <p><b>Why the hue ramp is no longer painted over it.</b> The previous bar was a <em>square</em> gauge tile
 * stretched into a bar — the human's 「UB槽的UI不行，用官方的」, with a screenshot of a yellow bar and a dark
 * three-segment tail. The fever bar is a real bar and already carries the game's own rainbow ramp
 * (mint → cyan → blue → violet → pink → peach → yellow), so the cold-to-hot reading the tint used to add is
 * <em>in the art</em>. A wash over it would mute precisely the gradient the human asked to see, so the
 * ordinary charge has no wash at all and only the refusal flash tints the fill. {@code HudLayout}'s green-to-gold
 * {@code chargeColor} ramp went with the wash: its one remaining job (the ready outline) is {@link #READY_OUTLINE}
 * below, which is a constant the four {@code fill} calls read rather than a curve nothing drew.
 *
 * <h2>What it reads</h2>
 * {@link ClientUbCache} — the client's mirror of the server's gauge, the readers of which exist for
 * exactly this. The HUD never computes charge or cooldown itself and never sends anything: the server
 * owns both (spec §5.5).
 *
 * <h2>Why the refusal is shown here</h2>
 * Spec §5.4 forbids silent failure, and by the time the server answers, the wheel is usually already
 * closed — so the place a refusal has to be visible is the gauge itself: it shakes and it flashes, once
 * per refusal ({@link RefusalFeedback}).
 *
 * <p>Only the <b>sight</b> of it is drawn here. This layer returns early whenever the player turns the
 * optional HUD off or hides the overlay with {@code F1} — both of which spec §7 allows — and a refusal
 * must not become silent just because a display is hidden. The sound therefore lives in
 * {@link RefusalSound}, driven by the client tick, and plays regardless of this class.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class UbHud {

    /** Ring colour of the remaining cooldown. */
    private static final int COOLDOWN_COLOUR = 0xFFFF5555;

    /** Ring colour when nothing is cooling down. */
    private static final int READY_COLOUR = 0xFF2E2E2E;

    /**
     * Background painted under the track art.
     *
     * <p>The fever bar's capsule has fully transparent corners, so without a floor behind it the world (or a
     * chest, or the sun) shows through the rounded ends of the gauge. Opaque enough to be a channel, dark
     * enough that the trough art on top of it still reads as the trough.
     */
    private static final int BAR_BACKGROUND = 0xE0101010;

    /** Outline drawn around the bar when the ultimate is ready to fire. */
    private static final int READY_OUTLINE = 0xFFFFC93C;

    /** Colour the bar flashes when a cast was refused. */
    private static final int REFUSAL_FLASH = 0xFFFF4040;

    /** Alpha of the refusal wash: strong enough to read as "no" at a glance. */
    private static final int REFUSAL_TINT_ALPHA = 0xB0;

    /** Tint of the disc behind a circle the preset has not filled: dark, so the ring still reads. */
    private static final int FACE_BACKDROP = 0xFF14161C;

    /** The face the HUD last logged, so the log line fires when it changes and not per frame. */
    private static volatile ResourceLocation loggedFace;

    /**
     * The cooldown span this client is drawing.
     *
     * <p>A client presentation cache, in the same category as {@link ClientUbCache} and
     * {@link WheelPreset}: one value for the one local player, never persisted, never consulted for a
     * decision. It exists because the server sends an absolute end tick and the ring needs a total
     * ({@link CooldownTracker} explains why observing it is the honest answer).
     */
    private static volatile CooldownTracker.Span span = CooldownTracker.Span.NONE;

    private UbHud() {
    }

    /**
     * Draws the gauge. Registered as a GUI layer above vanilla's hotbar.
     *
     * @param graphics the GUI buffer
     * @param delta    frame delta, unused: the gauge is drawn from server state and the game clock
     */
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!ClientConfig.HUD.enableUbHud.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }

        long now = minecraft.level.getGameTime();
        float charge = ClientUbCache.charge();
        span = CooldownTracker.observe(span, ClientUbCache.cdEndTick(), now);

        Optional<ClientCastFeedback.Refusal> refusal =
                ClientCastFeedback.active(now, RefusalFeedback.WINDOW_TICKS);
        int shake = refusal
                .map(r -> RefusalFeedback.shakeOffset(now - r.gameTime(), RefusalFeedback.WINDOW_TICKS))
                .orElse(0);

        HudLayout.Rect rect = HudLayout.anchor(graphics.guiWidth(), graphics.guiHeight(),
                ClientConfig.HUD.hudAnchor.get(), ClientConfig.HUD.hudOffsetX.get(),
                ClientConfig.HUD.hudOffsetY.get(), HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT);
        int x = rect.x() + shake;
        int y = rect.y();

        Optional<ResourceLocation> face = PresetFace.face();
        logFaceOnce(face);
        drawCooldownRing(graphics, x, y, now, refusal.isPresent(), face);
        double fraction = HudLayout.chargeFraction(charge);
        drawBar(graphics, x, y, fraction, now, refusal.isPresent());
    }

    /**
     * Logs the resolved face once per change.
     *
     * <p>Once per <em>change</em>, not once per frame: a frame log at 300 fps would be a formatting cost
     * for a fact that only moves when the wheel does. This line is the only evidence available that the
     * preset → party → definition → face chain resolved on a real client, since the picture itself
     * cannot be asserted.
     */
    private static void logFaceOnce(Optional<ResourceLocation> face) {
        ResourceLocation current = face.orElse(null);
        if (Objects.equals(current, loggedFace)) {
            return;
        }
        loggedFace = current;
        if (current == null) {
            Astrum.LOGGER.debug("astrum: UB gauge circle draws no face — preset slot {} is empty, the "
                    + "party is not synced yet, or that character has no face art", WheelPreset.slot());
        } else {
            Astrum.LOGGER.info("astrum: UB gauge circle draws the preset character's face {} (slot {})",
                    current, WheelPreset.slot());
        }
    }

    private static void drawCooldownRing(GuiGraphics graphics, int x, int y, long now,
            boolean refusing, Optional<ResourceLocation> face) {
        double cx = x + HudLayout.RING_DIAMETER / 2.0;
        double cy = y + HudLayout.WIDGET_HEIGHT / 2.0;
        double radius = HudLayout.RING_DIAMETER / 2.0;
        GuiShapes.disc(graphics, cx, cy, radius, BAR_BACKGROUND);
        if (face.isPresent()) {
            // The face fills the disc minus the ring's own band, so the ring reads as a frame around
            // her rather than as a line across her.
            int inset = PresetFace.FACE_INSET;
            int side = (int) (radius * 2) - inset * 2;
            blitFace(graphics, face.get(), (int) cx - side / 2, (int) cy - side / 2, side);
        } else {
            // Nothing to name: a dark disc, still framed by the ring, so the circle reads as
            // "empty" rather than as "this widget failed to draw".
            GuiShapes.disc(graphics, cx, cy, radius - 1.5, FACE_BACKDROP);
        }
        double remaining = CooldownTracker.fraction(span, now);
        int colour = refusing ? REFUSAL_FLASH : (remaining > 0.0 ? COOLDOWN_COLOUR : READY_COLOUR);
        GuiShapes.outline(graphics, cx, cy, radius - 1.0, 2.0, colour);
        if (remaining > 0.0) {
            GuiShapes.arc(graphics, cx, cy, radius - 1.0, 2.0, remaining, COOLDOWN_COLOUR);
        }
    }

    /**
     * Blits a whole face texture into a square, at whatever resolution the pack ships.
     *
     * <p>{@code uWidth == textureWidth == the destination side} normalises the source coordinates to
     * {@code 0..1}, so the whole image is sampled — the same technique {@code PartyEffectClient} uses.
     * Passing the texture's true pixel size instead would crop every face larger than the circle.
     */
    private static void blitFace(GuiGraphics graphics, ResourceLocation texture, int x, int y,
            int side) {
        if (side <= 0) {
            return;
        }
        graphics.blit(texture, x, y, side, side, 0.0F, 0.0F, side, side, side, side);
    }

    /**
     * Draws the charge bar from the two official-art layers, clipped by the fraction.
     *
     * <p>Order is: the flat floor (the art's glow is translucent and the world would show through the
     * text), the track, then the fill cropped to its fraction, then the tint and the ready outline.
     */
    private static void drawBar(GuiGraphics graphics, int x, int y, double fraction, long now,
            boolean refusing) {
        int barX = x + HudLayout.RING_DIAMETER + HudLayout.GAP;
        int barY = y + (HudLayout.WIDGET_HEIGHT - HudLayout.BAR_HEIGHT) / 2;
        graphics.fill(barX, barY, barX + HudLayout.BAR_WIDTH, barY + HudLayout.BAR_HEIGHT,
                BAR_BACKGROUND);
        GaugeArt.Rect track = new GaugeArt.Rect(barX, barY, HudLayout.BAR_WIDTH,
                HudLayout.BAR_HEIGHT);
        if (track.isDrawable()) {
            graphics.blit(GaugeArt.TRACK, track.x(), track.y(), track.width(), track.height(),
                    0.0F, 0.0F, GaugeArt.SOURCE_WIDTH, GaugeArt.SOURCE_HEIGHT,
                    GaugeArt.SOURCE_WIDTH, GaugeArt.SOURCE_HEIGHT);
        }

        int filled = GaugeArt.fillWidth(fraction, HudLayout.BAR_WIDTH);
        if (filled > 0) {
            // Source crop = the same fraction as the destination, so the rainbow is clipped rather than
            // squashed into a shorter copy of itself.
            int sourceWidth = Math.max(1, GaugeArt.sourceWidth(fraction));
            graphics.blit(GaugeArt.FILL, barX, barY, filled, HudLayout.BAR_HEIGHT,
                    0.0F, 0.0F, sourceWidth, GaugeArt.SOURCE_HEIGHT, GaugeArt.SOURCE_WIDTH,
                    GaugeArt.SOURCE_HEIGHT);
            if (refusing) {
                // Only the refusal washes over the art. The ordinary charge does not: the fever bar's own
                // ramp IS the cold-to-hot reading, and a wash over it was muting the thing the human asked
                // for. A refusal still has to be unmistakable (spec §5.4 forbids silent failure), and red
                // over a rainbow reads as "no" at a glance.
                graphics.fill(barX, barY, barX + filled, barY + HudLayout.BAR_HEIGHT,
                        tintOverlay(REFUSAL_FLASH, true));
            }
        }
        if (fraction >= 1.0 && CooldownTracker.fraction(span, now) <= 0.0) {
            // Ready: a thin gold frame around the bar, so "full but still cooling" reads differently
            // from "full and legal to fire" (spec §5.4: the cooldown is shared and may outlast a bar).
            graphics.fill(barX - 1, barY - 1, barX + HudLayout.BAR_WIDTH + 1, barY, READY_OUTLINE);
            graphics.fill(barX - 1, barY + HudLayout.BAR_HEIGHT, barX + HudLayout.BAR_WIDTH + 1,
                    barY + HudLayout.BAR_HEIGHT + 1, READY_OUTLINE);
            graphics.fill(barX - 1, barY, barX, barY + HudLayout.BAR_HEIGHT, READY_OUTLINE);
            graphics.fill(barX + HudLayout.BAR_WIDTH, barY, barX + HudLayout.BAR_WIDTH + 1,
                    barY + HudLayout.BAR_HEIGHT, READY_OUTLINE);
        }
    }

    /**
     * {@return {@code argb} as a translucent wash}
     *
     * <p>Opaque would hide the art; invisible would not be a flash. {@link #REFUSAL_TINT_ALPHA} keeps the
     * bar's shape and its fill length readable while making the colour unmistakably red — the one state that
     * must be obvious, because a refusal that reads as "charging slowly" is the silent failure spec §5.4
     * forbids.
     *
     * @param argb     the opaque colour
     * @param refusing whether this frame is a refusal flash (the only caller that tints at all)
     */
    private static int tintOverlay(int argb, boolean refusing) {
        int alpha = refusing ? REFUSAL_TINT_ALPHA : 0x00;
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
