package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.config.ClientConfig;
import java.util.Optional;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * The UB gauge on the HUD: a charge bar, a cooldown ring, and the refusal shake (spec §5.4, §7).
 *
 * <h2>Where it goes, and what it must not cover</h2>
 * The position comes from {@link HudLayout}, whose default anchor is the bottom-left well above the
 * hotbar — clear of the hotbar (bottom centre) and of the top-right corner where Jade/AppleSkin-class
 * mods draw. Both are asserted as rectangles in {@code HudLayoutTest}, because "does not collide" is
 * a claim about geometry, not a taste. Players who rearranged their own HUD can move it or turn it
 * off entirely ({@code [hud] enableUbHud}).
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

    /** Background of the gauge bar. */
    private static final int BAR_BACKGROUND = 0xA0101010;

    /** Outline drawn around the bar when the ultimate is ready to fire. */
    private static final int READY_OUTLINE = 0xFFFFC93C;

    /** Colour the bar flashes when a cast was refused. */
    private static final int REFUSAL_FLASH = 0xFFFF4040;

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

        drawCooldownRing(graphics, x, y, now, refusal.isPresent());
        double fraction = HudLayout.chargeFraction(charge);
        drawBar(graphics, x, y, fraction, now, refusal.isPresent());
    }

    private static void drawCooldownRing(GuiGraphics graphics, int x, int y, long now,
            boolean refusing) {
        double cx = x + HudLayout.RING_DIAMETER / 2.0;
        double cy = y + HudLayout.WIDGET_HEIGHT / 2.0;
        double radius = HudLayout.RING_DIAMETER / 2.0;
        GuiShapes.disc(graphics, cx, cy, radius, BAR_BACKGROUND);
        double remaining = CooldownTracker.fraction(span, now);
        int colour = refusing ? REFUSAL_FLASH : (remaining > 0.0 ? COOLDOWN_COLOUR : READY_COLOUR);
        GuiShapes.outline(graphics, cx, cy, radius - 1.0, 2.0, colour);
        if (remaining > 0.0) {
            GuiShapes.arc(graphics, cx, cy, radius - 1.0, 2.0, remaining, COOLDOWN_COLOUR);
        }
    }

    private static void drawBar(GuiGraphics graphics, int x, int y, double fraction, long now,
            boolean refusing) {
        int barX = x + HudLayout.RING_DIAMETER + HudLayout.GAP;
        int barY = y + (HudLayout.WIDGET_HEIGHT - HudLayout.BAR_HEIGHT) / 2;
        graphics.fill(barX, barY, barX + HudLayout.BAR_WIDTH, barY + HudLayout.BAR_HEIGHT,
                BAR_BACKGROUND);

        int filled = (int) Math.round(HudLayout.BAR_WIDTH * fraction);
        if (filled > 0) {
            int colour = refusing ? REFUSAL_FLASH : HudLayout.chargeColor(fraction);
            graphics.fill(barX, barY, barX + filled, barY + HudLayout.BAR_HEIGHT, colour);
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
}
