package com.astrum.pcrd.impl.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where our own <b>party status row</b> sits, and what each cell contains — the geometry half of
 * {@link PartyStatusHud}, kept separate so it is assertable without a GL context.
 *
 * <h2>Why the party effects left vanilla's effect bar</h2>
 * The five slot effects answer 「who is in my party」, which is not what vanilla's effect bar is for. That bar
 * is a column of small square sprites with a duration, sized for potions: it grew to five extra entries the
 * player did not cast and cannot see the numbers of, and it competed with real effects for the same corner.
 * So the party entries moved to a row of their own, drawn beside the UB gauge, where each cell can carry the
 * character's face, her name and the remaining time at a size that reads. Vanilla's bar keeps exactly what it
 * is good at: real, timed effects.
 *
 * <p>The effects themselves stay registered and stay on the entity — the server needs them as the
 * authoritative record of who is buffed ({@code PartyBuffs}), and {@code /effect give} still works. What
 * changed is only that the <em>client no longer draws them in the bar</em>:
 * {@link PartyEffectClient#isVisibleInGui} answers {@code false} for the five slot types. That is the
 * minimum-surface way to move them: no new state, no second source of truth, and the effect bar becomes
 * honest again by subtraction.
 *
 * <h2>Where the row goes, and why there</h2>
 * Directly <b>above the UB gauge</b>, left-aligned with it, so the row and the gauge read as one widget: the
 * party on top, the resource they are charging below. It is measured from the gauge's own
 * {@link HudLayout.Rect} rather than from a second set of constants, so moving the gauge moves the row and
 * the two cannot drift apart. There is deliberately no separate anchor config — two anchors for one widget is
 * how a HUD ends up half in one place.
 */
@ApiStatus.Internal
public final class PartyStatusRow {

    /** Side of one cell, in GUI pixels. Big enough for a 16px face plus a 1px frame on each side. */
    public static final int CELL = 18;

    /** Gap between cells. Vanilla's effect bar uses 1px between 24px slots; this is looser at 18px. */
    public static final int GAP = 2;

    /** Gap between the row and the gauge below it. */
    public static final int TO_GAUGE = 2;

    /** The face is drawn inset by this much, which is the cell's frame. */
    public static final int INSET = 1;

    /**
     * The fill a cell falls back to when no face resolves, indexed by slot.
     *
     * <p>The same five colours {@code PartyEffectSlots.SLOT_COLORS} gives the slot <em>effects</em>, minus
     * the alpha handling vanilla does: a cell whose character registry has not arrived yet still marks its
     * slot, and the colour says which slot it is. A flat fill rather than a texture, because the alternative
     * — drawing a missing {@code ResourceLocation} — is the purple checkerboard.
     */
    public static final int[] SLOT_FALLBACK_COLOURS = {
            0xFF569CFF, 0xFFFF7ABE, 0xFF70D680, 0xFFFFC648, 0xFFBA84FF,
    };

    private PartyStatusRow() {
    }

    /**
     * {@return the row's rectangle for {@code cells} cells, sitting just above {@code gauge}}
     *
     * <p>A zero-cell row is a zero-width rectangle at the gauge's left edge, so a caller that draws nothing
     * still has a well-defined place to draw nothing at — the alternative (a {@code null} rect) invites a
     * caller to guess.
     *
     * @param gauge the UB gauge's rectangle, from {@link HudLayout#anchor}
     * @param cells how many party members to show
     */
    public static HudLayout.Rect rectFor(HudLayout.Rect gauge, int cells) {
        int width = widthOf(cells);
        int y = gauge.y() - TO_GAUGE - CELL;
        return new HudLayout.Rect(gauge.x(), y, width, CELL);
    }

    /** {@return the row's width for {@code cells} cells, including the gaps between them} */
    public static int widthOf(int cells) {
        if (cells <= 0) {
            return 0;
        }
        return cells * CELL + (cells - 1) * GAP;
    }

    /**
     * {@return one rectangle per cell, left to right}
     *
     * <p>The whole layout in one call, so a test can assert left-to-right order and non-overlap without a
     * client — the two things that go wrong when a row is drawn with an accumulator inside a render loop.
     *
     * @param row   the row's rectangle, from {@link #rectFor}
     * @param cells how many cells
     */
    public static List<HudLayout.Rect> cells(HudLayout.Rect row, int cells) {
        if (cells <= 0) {
            return List.of();
        }
        List<HudLayout.Rect> out = new ArrayList<>(cells);
        for (int i = 0; i < cells; i++) {
            out.add(new HudLayout.Rect(row.x() + i * (CELL + GAP), row.y(), CELL, CELL));
        }
        return List.copyOf(out);
    }

    /**
     * {@return the row the client should draw: one cell per equipped party slot}
     *
     * <p>Read from {@link ClientPartyCache}, the same party push the wheel and the book use, so the row shows
     * the party the player actually has rather than the effects that happen to be on her. That distinction
     * matters during the tick between a party edit and the server's {@code addEffect}: the row would otherwise
     * flicker a member out and back in.
     */
    public static List<ResourceLocation> members() {
        List<ResourceLocation> members = new ArrayList<>();
        for (int slot = 0; slot < com.astrum.pcrd.impl.attach.Party.SLOTS; slot++) {
            ClientPartyCache.slot(slot).ifPresent(members::add);
        }
        return List.copyOf(members);
    }

    /**
     * {@return the remaining ticks of {@code slot}'s effect on the local player, empty when absent}
     *
     * <p>Read from the player's own effect map rather than from a cache: the effect instance is the server's
     * authoritative record and vanilla already syncs it, so a second copy would be a second answer to "how
     * long does she have left". {@code Optional} rather than a sentinel, because "not buffed" and "one tick
     * left" are different states and only one of them should draw a number.
     *
     * @param slot the 0-based party slot
     */
    public static Optional<Integer> remainingTicks(int slot) {
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null) {
            return Optional.empty();
        }
        // Through the Holder view: a DeferredHolder is a registry entry whose value is not available before
        // the registry is bound, and `holderFor` throws for an out-of-range slot — neither is acceptable on a
        // render path, so the optional `bound` lookup is used instead.
        return com.astrum.pcrd.impl.effect.PartyEffects.partyHolderFor(slot)
                .map(holder -> minecraft.player.getEffect(holder))
                .map(instance -> instance.getDuration());
    }
}
