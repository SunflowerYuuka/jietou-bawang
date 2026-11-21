package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.attach.UbCooldown;
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
 * <p><b>And the circle is shaded while she cools</b> (「UB 槽头像用阴影逐渐覆盖」, corrected to
 * 「这个反过来，从阴影全覆盖，到没有阴影」): at the instant of her cast the face is under full shade, and the
 * shade lifts linearly until it is gone on the tick she is castable again. Both the ring's sweep and the
 * shade come from <b>one</b> number — {@link CooldownTracker#fraction} over that character's own
 * {@link UbCooldown} — so the picture cannot claim two different remaining times. The cooldown is
 * <b>per character</b> (「每个角色UB的CD不共享」), and the circle shows the one belonging to whoever the preset
 * names, which is exactly who a tap cast would fire.
 *
 * <h2>What the bar is drawn from</h2>
 * <b>Official PCR art</b>, two layers: {@code gui/ub_gauge_track.png} under {@code
 * gui/ub_gauge_fill.png}, both cut from the game's own <b>horizontal fever bar</b>
 * ({@code pct_fever_bar.png}) by {@code tools/gen_ub_gauge_texture.py}. The fill is <b>clipped</b>, not
 * squashed: the destination and the source crop are the same charge fraction, which is {@link GaugeArt}'s
 * arithmetic and {@code GaugeArtTest}'s assertion.
 *
 * <p><b>Both layers are pack-only</b> (they are cut from the game's own fever bar, so {@code .gitignore}
 * keeps them out of the repository). A player without {@code astrum-assets.zip} therefore gets neither, and
 * {@link #drawBar} gates each layer on {@link ArtFallback#isInstalled}: the bar degrades to the flat dark
 * channel plus a flat gold fill clipped to the charge — the shape it had before it gained official art —
 * rather than to a checkerboard or to a stretched placeholder.
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

    /**
     * The flat ink the charge bar falls back to when the gauge art is not installed: the placeholder's own
     * border gold ({@code tools/gen_placeholder.py}'s {@code BORDER}), so a bar without official art still
     * reads as this mod's gauge rather than as a foreign shape.
     */
    private static final int FLAT_FILL = 0xFFDDA93C;

    /**
     * Set once, when the gauge's art turned out not to be installed, so the log says <em>which surface</em>
     * degraded — {@link ArtFallback} says which file is missing, this says what the gauge did about it.
     */
    private static boolean flatBarLogged;

    /** The face the HUD last logged, so the log line fires when it changes and not per frame. */
    private static volatile ResourceLocation loggedFace;

    /**
     * The cooldown the circle showed on the last frame, kept only so the log line can fire when it
     * changes rather than every frame.
     *
     * <p>Not a presentation cache any more: the remaining fraction is recomputed each frame from the
     * server's own deadline and total ({@link UbCooldown#remainingFraction}), so nothing has to be
     * remembered for the drawing to be correct.
     */
    private static volatile String loggedCooldown;

    /**
     * Where the gauge was drawn on the last frame, for {@link PartyStatusHud} to sit above.
     *
     * <p>{@code volatile} and a fresh value each frame, not a mutated one: the party row's layer may run on
     * the same thread but reads this after the gauge's layer has written it, and publishing a whole immutable
     * rectangle is the cheapest way to make that read see a consistent pair of coordinates.
     */
    private static volatile HudLayout.Rect lastGauge;

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
        // 每个角色UB的CD不共享: the circle shows the PRESET character's own cooldown, resolved once here
        // and used by both the ring and the shade, so they cannot disagree.
        Optional<UbCooldown> shownCooldown = CooldownTracker.shown();
        double cooldownLeft = CooldownTracker.fraction(shownCooldown, now);
        logCooldownOnce(shownCooldown, now);

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
        lastGauge = new HudLayout.Rect(x, y, HudLayout.WIDGET_WIDTH, HudLayout.WIDGET_HEIGHT);

        Optional<ResourceLocation> face = PresetFace.face();
        logFaceOnce(face);
        drawCooldownRing(graphics, x, y, cooldownLeft, refusal.isPresent(), face);
        double fraction = HudLayout.chargeFraction(charge);
        drawBar(graphics, x, y, fraction, cooldownLeft, refusal.isPresent());
    }

    /**
     * {@return where the gauge was drawn last frame, or {@code null} before the first draw}
     *
     * <p>Published so {@link PartyStatusHud} can sit directly above the gauge without calling
     * {@link HudLayout#anchor} a second time. Two calls could disagree — the anchor argument comes from live
     * config, and a config screen can change it between two GUI layers of the same frame — and a party row
     * that jumps one frame after the gauge would be the sort of one-frame bug nobody can photograph. Reading
     * the drawn rectangle makes the row a function of what is on screen.
     *
     * <p>A client presentation value, in the same category as {@link #loggedCooldown}: one per client,
     * never persisted, never consulted for a decision.
     */
    static HudLayout.Rect lastGauge() {
        return lastGauge;
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

    private static void drawCooldownRing(GuiGraphics graphics, int x, int y, double remaining,
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
            int faceX = (int) cx - side / 2;
            int faceY = (int) cy - side / 2;
            blitFace(graphics, face.get(), faceX, faceY, side);
            // 从阴影全覆盖，到没有阴影: full shade at the start of HER cooldown, none when she is castable
            // again. The fill is the SAME rectangle the face was just blitted into — not a disc — because
            // the face is a square picture inside a round ring: a disc of shade would leave four bright
            // corners of her face uncovered, which reads as a drawing bug. Shaded at the face's own size,
            // the silhouette that darkens is exactly the picture, and the ring's band (drawn after this)
            // stays at full brightness so the sweep is still legible. One fill: FaceShade owns the maths.
            int shade = FaceShade.argbOf(remaining);
            if ((shade >>> 24) != 0) {
                graphics.fill(faceX, faceY, faceX + side, faceY + side, shade);
            }
        } else {
            // Nothing to name: a dark disc, still framed by the ring, so the circle reads as
            // "empty" rather than as "this widget failed to draw".
            GuiShapes.disc(graphics, cx, cy, radius - 1.5, FACE_BACKDROP);
        }
        int colour = refusing ? REFUSAL_FLASH : (remaining > 0.0 ? COOLDOWN_COLOUR : READY_COLOUR);
        GuiShapes.outline(graphics, cx, cy, radius - 1.0, 2.0, colour);
        if (remaining > 0.0) {
            GuiShapes.arc(graphics, cx, cy, radius - 1.0, 2.0, remaining, COOLDOWN_COLOUR);
        }
    }

    /**
     * Logs the shown cooldown once per change.
     *
     * <p>Once per <em>change</em>, not once per frame, and only for the two facts a probe cares about:
     * which character the circle is honouring and whether her cooldown is running. The shade's exact
     * alpha is not logged — it moves every frame by design, so logging it would be a formatting cost
     * with no reader.
     */
    private static void logCooldownOnce(Optional<UbCooldown> shown, long now) {
        String current = shown.map(cooldown -> (cooldown.isOver(now) ? "ready " : "cooling ")
                + cooldown.endTick() + "/" + cooldown.totalTicks()).orElse("none");
        if (current.equals(loggedCooldown)) {
            return;
        }
        loggedCooldown = current;
        Astrum.LOGGER.debug("astrum: UB gauge circle cooldown {} (preset slot {})", current,
                WheelPreset.slot());
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
        // The face is official art the repository does not ship; a player without the pack gets the
        // 「请安装资源包」 placeholder rather than vanilla's magenta checkerboard. See ArtFallback.
        graphics.blit(ArtFallback.resolve(texture), x, y, side, side, 0.0F, 0.0F, side, side, side, side);
    }

    /**
     * Draws the charge bar from the two official-art layers, clipped by the fraction.
     *
     * <p>Order is: the flat floor (the art's glow is translucent and the world would show through the
     * text), the track, then the fill cropped to its fraction, then the tint and the ready outline.
     *
     * <h2>Without the art pack</h2>
     * Both layers are <b>pack-only</b> (cut from the game's own fever bar), so a player who has not installed
     * {@code astrum-assets.zip} gets neither. Drawing them anyway is the magenta checkerboard — and the
     * placeholder would be worse here than anywhere else: a 128x128 square of text squeezed into a 10-pixel
     * tall capsule by a blit whose source rectangle is 256x24. So the two layers are each gated on
     * {@link ArtFallback#isInstalled} and the bar degrades to what it was before it gained official art: the
     * flat {@link #BAR_BACKGROUND} channel (already painted as the floor) and a flat {@link #FLAT_FILL} bar
     * clipped to the charge. The charge still reads, the fraction is still honest, and the player is told by
     * {@link ArtFallback}'s own line which file would make it pretty.
     *
     * <p>Two gates rather than one: the track and the fill are separate files, and a player who somehow has
     * one of them should get the half that exists instead of a checkerboard or an all-or-nothing bar.
     */
    private static void drawBar(GuiGraphics graphics, int x, int y, double fraction,
            double cooldownLeft, boolean refusing) {
        int barX = x + HudLayout.RING_DIAMETER + HudLayout.GAP;
        int barY = y + (HudLayout.WIDGET_HEIGHT - HudLayout.BAR_HEIGHT) / 2;
        graphics.fill(barX, barY, barX + HudLayout.BAR_WIDTH, barY + HudLayout.BAR_HEIGHT,
                BAR_BACKGROUND);
        GaugeArt.Rect track = new GaugeArt.Rect(barX, barY, HudLayout.BAR_WIDTH,
                HudLayout.BAR_HEIGHT);
        boolean trackInstalled = ArtFallback.isInstalled(GaugeArt.TRACK);
        boolean fillInstalled = ArtFallback.isInstalled(GaugeArt.FILL);
        if (!trackInstalled || !fillInstalled) {
            logFlatBarOnce(trackInstalled, fillInstalled);
        }
        if (trackInstalled && track.isDrawable()) {
            graphics.blit(GaugeArt.TRACK, track.x(), track.y(), track.width(), track.height(),
                    0.0F, 0.0F, GaugeArt.SOURCE_WIDTH, GaugeArt.SOURCE_HEIGHT,
                    GaugeArt.SOURCE_WIDTH, GaugeArt.SOURCE_HEIGHT);
        }

        int filled = GaugeArt.fillWidth(fraction, HudLayout.BAR_WIDTH);
        if (filled > 0) {
            if (fillInstalled) {
                // Source crop = the same fraction as the destination, so the rainbow is clipped rather than
                // squashed into a shorter copy of itself.
                int sourceWidth = Math.max(1, GaugeArt.sourceWidth(fraction));
                graphics.blit(GaugeArt.FILL, barX, barY, filled, HudLayout.BAR_HEIGHT,
                        0.0F, 0.0F, sourceWidth, GaugeArt.SOURCE_HEIGHT, GaugeArt.SOURCE_WIDTH,
                        GaugeArt.SOURCE_HEIGHT);
            } else {
                graphics.fill(barX, barY, barX + filled, barY + HudLayout.BAR_HEIGHT, FLAT_FILL);
            }
            if (refusing) {
                // Only the refusal washes over the art. The ordinary charge does not: the fever bar's own
                // ramp IS the cold-to-hot reading, and a wash over it was muting the thing the human asked
                // for. A refusal still has to be unmistakable (spec §5.4 forbids silent failure), and red
                // over a rainbow reads as "no" at a glance.
                graphics.fill(barX, barY, barX + filled, barY + HudLayout.BAR_HEIGHT,
                        tintOverlay(REFUSAL_FLASH, true));
            }
        }
        if (fraction >= 1.0 && cooldownLeft <= 0.0) {
            // Ready: a thin gold frame around the bar, so "full but still cooling" reads differently
            // from "full and legal to fire" (spec §5.4). The cooldown read here is the PRESET
            // character's — the one a tap cast would fire — not a wheel-wide timer.
            graphics.fill(barX - 1, barY - 1, barX + HudLayout.BAR_WIDTH + 1, barY, READY_OUTLINE);
            graphics.fill(barX - 1, barY + HudLayout.BAR_HEIGHT, barX + HudLayout.BAR_WIDTH + 1,
                    barY + HudLayout.BAR_HEIGHT + 1, READY_OUTLINE);
            graphics.fill(barX - 1, barY, barX, barY + HudLayout.BAR_HEIGHT, READY_OUTLINE);
            graphics.fill(barX + HudLayout.BAR_WIDTH, barY, barX + HudLayout.BAR_WIDTH + 1,
                    barY + HudLayout.BAR_HEIGHT, READY_OUTLINE);
        }
    }

    /**
     * Logs once per session which of the bar's two art layers is missing, so a run's log says the gauge
     * degraded on purpose rather than looking like a bar that failed to draw.
     *
     * @param trackInstalled whether {@code gui/ub_gauge_track.png} resolved
     * @param fillInstalled  whether {@code gui/ub_gauge_fill.png} resolved
     */
    private static void logFlatBarOnce(boolean trackInstalled, boolean fillInstalled) {
        if (flatBarLogged) {
            return;
        }
        flatBarLogged = true;
        Astrum.LOGGER.info("astrum: UB gauge art is not installed (track {}, fill {}), so the charge bar is "
                        + "drawn flat — the dark channel plus a 0x{} fill clipped to the charge, which is the "
                        + "shape the bar had before it gained official art. Install astrum-assets.zip for the "
                        + "trough and the rainbow ramp.",
                trackInstalled ? "present" : GaugeArt.TRACK_PATH + " missing",
                fillInstalled ? "present" : GaugeArt.FILL_PATH + " missing",
                Integer.toHexString(FLAT_FILL));
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
