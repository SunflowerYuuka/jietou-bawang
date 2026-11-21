package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.config.ClientConfig;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Our own party status row</b>: one cell per equipped party member, drawn above the UB gauge.
 *
 * <h2>Why this row exists at all</h2>
 * The five party effects used to be five entries in vanilla's effect bar. That bar is a column of 24px
 * sprites built for potions: it answered "how long" for effects the player did not cast, it competed with
 * real effects for the same corner, and it could only ever show a picture and a duration. A party is
 * different information — <em>who is with me</em> — and it deserves its own place. So the party entries
 * moved here and {@link PartyEffectClient#isVisibleInGui} answers {@code false} for them. Vanilla's bar now
 * shows exactly what it is good at, and nothing of ours.

 * <h2>What a cell shows, and why each part</h2>
 * <ul>
 *   <li><b>The face</b> — the same {@link TierAvatar#forPartySlot} resolution the book and the gauge use, so
 *       the row, the gauge and the book cannot draw three different pictures of one character. That
 *       "three surfaces, one chain" rule is the fix for the 2026-11-14 defect, not a convenience.</li>
 *   <li><b>A frame</b> — a 1px border, so a face that happens to be dark does not merge into the HUD
 *       background. It is also the only outline the row has; there is no panel behind it.</li>
 *   <li><b>A duration pip</b> — a short bar along the cell's bottom edge, filled by the remaining fraction.
 *       A number would be unreadable at 18px, and the row's question is "is my party still buffed", which is
 *       a fraction and not a value.</li>
 * </ul>
 *
 * <h2>Nothing is decided here</h2>
 * This class reads two caches and draws. It holds no state, never asks the server anything, and never touches
 * an attachment — the same rule {@code UbHud} and {@code WheelOverlay} follow, and the reason none of the
 * three can disagree with the server about a value.
 *
 * <h2>What a screenshot would be needed for</h2>
 * Whether the row <em>looks</em> right — that the faces are legible at 18px, that the frame reads at the
 * player's GUI scale, that the row and the gauge look like one widget. {@link PartyStatusRowTest} pins the
 * geometry (order, non-overlap, the clamp), and no unit test can see a pixel.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class PartyStatusHud {

    /** The frame around a face: a neutral dark line that reads on sand, stone and netherrack alike. */
    private static final int FRAME = 0xFF1A1C22;

    /** The pip's empty track: the frame colour at half alpha, so it is present but not a second frame. */
    private static final int PIP_TRACK = 0x801A1C22;

    /** The pip's fill: the same gold the gauge uses for "ready", so one palette means "active". */
    private static final int PIP_FILL = 0xFFFFC93C;

    /** Height of the duration pip along the cell's bottom edge. */
    private static final int PIP_HEIGHT = 2;

    /** Logged once per resolved member list, for the "did the chain resolve" evidence a screenshot cannot give. */
    private static volatile String loggedMembers;

    private PartyStatusHud() {
    }

    /**
     * Draws the row above {@code gauge}.
     *
     * <p>Registers as a GUI layer above the gauge's own, so the row is never covered by it. Takes the gauge's
     * rectangle rather than recomputing it: {@link HudLayout#anchor} is the one place a HUD position is
     * decided, and a second call could land somewhere else after a config change.
     *
     * @param graphics the GUI buffer
     * @param gauge    the UB gauge's rectangle, already shaken by any refusal
     */
    public static void render(GuiGraphics graphics, HudLayout.Rect gauge) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }
        if (!ClientConfig.HUD.enableUbHud.get()) {
            // One setting for the whole bottom-left widget: a player who hid the gauge does not want a
            // party row floating where it used to be.
            return;
        }
        List<ResourceLocation> members = PartyStatusRow.members();
        if (members.isEmpty()) {
            logMembers("(none)");
            return;
        }
        logMembers(members.toString());

        HudLayout.Rect row = PartyStatusRow.rectFor(gauge, members.size());
        List<HudLayout.Rect> cells = PartyStatusRow.cells(row, members.size());
        for (int slot = 0; slot < cells.size(); slot++) {
            drawCell(graphics, cells.get(slot), members.get(slot), slot);
        }
    }

    /** Draws one member: frame, face, duration pip. */
    private static void drawCell(GuiGraphics graphics, HudLayout.Rect cell, ResourceLocation characterId,
            int slot) {
        // The frame is drawn as four 1px edges rather than a filled rect behind the face, so a translucent
        // avatar is not tinted by it.
        graphics.fill(cell.x(), cell.y(), cell.right(), cell.y() + PartyStatusRow.INSET, FRAME);
        graphics.fill(cell.x(), cell.bottom() - PartyStatusRow.INSET, cell.right(), cell.bottom(), FRAME);
        graphics.fill(cell.x(), cell.y(), cell.x() + PartyStatusRow.INSET, cell.bottom(), FRAME);
        graphics.fill(cell.right() - PartyStatusRow.INSET, cell.y(), cell.right(), cell.bottom(), FRAME);

        int faceSide = PartyStatusRow.CELL - 2 * PartyStatusRow.INSET;
        ResourceLocation face = faceOf(characterId);
        if (face != null) {
            // uWidth == textureWidth == the destination side, so the whole file is sampled whatever
            // resolution the pack ships — the same technique the gauge's ring uses.
            graphics.blit(ArtFallback.resolve(face), cell.x() + PartyStatusRow.INSET, cell.y() + PartyStatusRow.INSET, faceSide,
                    faceSide, 0.0F, 0.0F, faceSide, faceSide, faceSide, faceSide);
        } else {
            // No face resolvable: a flat fill rather than nothing, so the cell still marks the slot. A
            // missing texture would draw the purple checkerboard, which is the one outcome to avoid.
            graphics.fill(cell.x() + PartyStatusRow.INSET, cell.y() + PartyStatusRow.INSET,
                    cell.right() - PartyStatusRow.INSET, cell.bottom() - PartyStatusRow.INSET,
                    PartyStatusRow.SLOT_FALLBACK_COLOURS[slot % PartyStatusRow.SLOT_FALLBACK_COLOURS.length]);
        }
        drawPip(graphics, cell, slot);
    }

    /**
     * Draws the remaining-duration pip along the cell's bottom edge.
     *
     * <p>The fraction comes from the effect instance's own duration and vanilla's nominal 20 ticks a second.
     * It is deliberately not a countdown number and not a per-tick interpolation: the server's duration is
     * what the player cares about, and it decrements once a tick anyway, so a smooth animation would be
     * inventing precision.
     */
    private static void drawPip(GuiGraphics graphics, HudLayout.Rect cell, int slot) {
        int pipY = cell.bottom() - PartyStatusRow.INSET - PIP_HEIGHT;
        int innerX = cell.x() + PartyStatusRow.INSET;
        int innerWidth = cell.width() - 2 * PartyStatusRow.INSET;
        graphics.fill(innerX, pipY, innerX + innerWidth, pipY + PIP_HEIGHT, PIP_TRACK);
        Optional<Integer> remaining = PartyStatusRow.remainingTicks(slot);
        if (remaining.isEmpty()) {
            return;   // not buffed: an empty track, which is the honest picture
        }
        double fraction = Math.min(1.0, Math.max(0.0, remaining.get() / (double) NOMINAL_TICKS));
        int filled = (int) Math.round(innerWidth * fraction);
        if (filled > 0) {
            graphics.fill(innerX, pipY, innerX + filled, pipY + PIP_HEIGHT, PIP_FILL);
        }
    }

    /**
     * {@return the face to draw for {@code characterId}, or {@code null} when it cannot be resolved}
     *
     * <p>{@link TierAvatar#forPartySlot} is the one chain from (character id, affection tier) to a texture,
     * read here exactly as the party-effect icon and the gauge read it. A client that has not received the
     * character registry yet answers {@code null}, and the cell falls back to its colour.
     */
    private static @Nullable ResourceLocation faceOf(ResourceLocation characterId) {
        CharacterDef definition = AstrumApi.characters()
                .map(registry -> registry.get(characterId))
                .orElse(null);
        if (definition == null) {
            return null;
        }
        return TierAvatar.forPartySlot(definition, characterId).orElse(null);
    }

    /** Vanilla's nominal tick rate: the divisor for a duration in ticks, and the only 20 in this class. */
    private static final int NOMINAL_TICKS = 20;

    /**
     * Logs the resolved member list once per change.
     *
     * <p>Once per change, not per frame: this is the only evidence available that the party →
     * definition → face chain resolved on a real client, since the picture itself cannot be asserted. A
     * per-frame line at 300 fps would be a formatting cost for a fact that only moves when the party does.
     */
    private static void logMembers(String current) {
        if (current.equals(loggedMembers)) {
            return;
        }
        loggedMembers = current;
        Astrum.LOGGER.info("astrum: party status row — {} member(s): {}", Party.SLOTS, current);
    }
}
