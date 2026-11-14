package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * The 角色册 panel's colours: <b>the one palette</b>, with no theme dimension left.
 *
 * <h2>What this replaced</h2>
 * It was {@code impl.item.BookTheme}, an enum of three themes (blue / sakura / forest) whose whole purpose
 * was to pick a different frame, divider and body tint per book item. The human deleted that feature —
 * 「将2个皮肤版本的角色册道具删除，仅保留最开始的那个」, and then 「边框不保留，使用官方背景」 — so the enum's
 * <em>one remaining</em> theme's worth of numbers moved here, and the enum, its third-party item ids, its
 * art and its tests are gone.
 *
 * <h2>Why a class and not constants inside the screen</h2>
 * Two reasons, both about the same risk:
 *
 * <ul>
 *   <li><b>It is testable.</b> Every value below is a claim — "cells are darker than the body so light
 *       text stays readable", "a selected cell is brighter than a hovered one" — and those claims used to be
 *       asserted by {@code BookThemeTest} against the art. The art is now official and not ours to sample
 *       (the frame's body is transparent on purpose: the still fills it), so the assertions move to the
 *       palette itself: {@code BookPaletteTest} pins the ladder and the derivation.</li>
 *   <li><b>The screen is already large.</b> It draws five surfaces and three bars; keeping the numbers that
 *       decide their colours next to the rule that derives them is what stops a sixth surface from getting a
 *       hand-picked colour that is not in the family.</li>
 * </ul>
 *
 * <h2>The derivation, in one place</h2>
 * The old enum derived every surface from the theme's body colour, so a re-exported panel moved the whole
 * palette with it. That property is kept: {@link #BODY} is the base and everything else is
 * {@link #mix}ed from it, so "the accent is lighter than the body" and "a cell is darker than the body" are
 * structural rather than coincidental.
 *
 * <p>{@link #BODY} is now the floor the panel paints under the still — {@code OfficialPanel.BODY_FLOOR},
 * the dark neutral of the official frame's own composition. The panel's art itself is not tinted: the frame
 * is a one-pixel cream-gold stroke and a tint over it would just mute the gold.
 */
@ApiStatus.Internal
public final class BookPalette {

    /**
     * The panel's base colour: what is painted under the still, and what a character without one shows.
     *
     * <p>The same value as {@code OfficialPanel.BODY_FLOOR} by construction — asserted, not assumed.
     */
    public static final int BODY = 0xFF14161C;

    /** How far {@link #accent} mixes the body towards white, {@code 0}–{@code 1}. */
    public static final float LIGHTEN = 0.55F;

    /** How far {@link #surface} mixes the body towards black, {@code 0}–{@code 1}. */
    public static final float SURFACE_DARKEN = 0.42F;

    private BookPalette() {
    }

    /**
     * {@return the colour of a thin rule drawn on the panel: hover frames, the feeding entry's top rule}
     *
     * <p>A mix towards white, which is what the official art itself does — its stroke is a lighter version
     * of the surface behind it rather than a saturated complement. Alpha is forced opaque because these are
     * one-pixel lines, and a translucent one over a textured background reads as a smudge.
     */
    public static int accent() {
        return mix(BODY, 0xFFFFFFFF, LIGHTEN);
    }

    /**
     * {@return the colour of a surface cut into the panel — roster cells, party slots, bar backs}
     *
     * <p>Darker than the body, so a cell reads as a well rather than a raised plate. {@code 0.42} keeps every
     * cell at 12–16% luminance, dark enough for the light text drawn on it <em>and</em> dark enough to stay
     * readable over the still on the far side of it.
     */
    public static int surface() {
        return mix(BODY, 0xFF000000, SURFACE_DARKEN);
    }

    /** {@return a slightly lifted {@link #surface()}, for a hovered roster cell} */
    public static int surfaceHover() {
        return mix(surface(), 0xFFFFFFFF, 0.06F);
    }

    /** {@return the colour of a selected roster cell: the surface lifted again, clearly still a cell} */
    public static int surfaceSelected() {
        return mix(surface(), 0xFFFFFFFF, 0.16F);
    }

    /** {@return a filled party slot: the surface lifted a little, distinct from an empty one} */
    public static int slotFilled() {
        return mix(surface(), 0xFFFFFFFF, 0.10F);
    }

    /**
     * {@return {@code argb} mixed {@code amount} of the way towards {@code towards}, alpha {@code 0xFF}}
     *
     * <p>One mixing function for every derived colour above, so "a lighter accent" and "a darker well"
     * cannot drift apart in rounding. {@code amount 0} returns the colour, {@code amount 1} returns the
     * target, anything outside {@code 0}–{@code 1} is clamped, and the result is always opaque.
     *
     * @param argb    the source colour
     * @param towards the colour to move towards
     * @param amount  {@code 0}–{@code 1}, clamped
     */
    public static int mix(int argb, int towards, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int r = mixChannel((argb >> 16) & 0xFF, (towards >> 16) & 0xFF, t);
        int g = mixChannel((argb >> 8) & 0xFF, (towards >> 8) & 0xFF, t);
        int b = mixChannel(argb & 0xFF, towards & 0xFF, t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mixChannel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}
