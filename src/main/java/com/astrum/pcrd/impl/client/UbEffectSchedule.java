package com.astrum.pcrd.impl.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * When a UB effect is on screen, how faded each of its pictures is, and where it goes — as arithmetic.
 *
 * <h2>Why the effect's timing is a class and not four constants in the renderer</h2>
 * A fade is the one part of a visual that <b>cannot be reviewed from a log and cannot be seen by whoever
 * wrote it</b>: the only honest way to know that the overlay starts at full strength, fades monotonically,
 * and is <em>gone</em> afterwards is to state those properties as assertions. So the whole schedule — how
 * long the effect lasts, the alpha of each layer at any age, the rectangle each one is drawn into — lives
 * here as pure functions over {@code (width, height, ageTicks)}, and {@code UbEffectScheduleTest} pins the
 * boundaries (first frame, last frame, one tick after) and the "does not cover the gauge or the crosshair"
 * placement rule.
 *
 * <h2>The schedule: one second, four pictures, staggered</h2>
 * <pre>
 *   age (ticks)   0    2    4    8   12   14   16   20
 *   flare         ██████████████████████░░░░░░░░░░░░   diagonal energy stream, gone by 12
 *   slash         ░░░░░█████████████████████████████   the golden trail, 2..20
 *   burst         ░░░░░░░░░█████████████████████████   radial burst, 2..20, attack over 2 ticks
 *   cut-in        ████████████████████████████░░░░░░   the character portrait, gone by 14
 * </pre>
 *
 * <p>The numbers are read as "how long a player needs to register it": a tenth of a second to appear, half
 * a second to be legible, and the whole second to be gone before it becomes a screen you are fighting
 * behind. The <b>last</b> picture fades out exactly at {@link #TOTAL_TICKS}, because a fade that finishes
 * its last layer early leaves a stretch where the effect is technically "on" but drawing nothing — which is
 * the invisible work this class exists to make impossible.
 *
 * <p>{@link #TOTAL_TICKS} is also the only clock involved — no timer and no tick listener: the age is
 * computed from two game times by the layer that draws, the same mechanism {@code RefusalFeedback} uses for
 * the refusal shake.
 *
 * <h2>What this class deliberately does not do</h2>
 * No world position, no billboard, no camera basis. The effect is a <b>screen-space</b> overlay in GUI
 * coordinates, which is why every rectangle here takes only the GUI width and height: it cannot be culled
 * by a block, cannot z-fight with the model it is standing in for, and needs no render type of its own.
 */
@ApiStatus.Internal
public final class UbEffectSchedule {

    /** How long the whole effect is on screen, in ticks. One second at 20 tps. */
    public static final int TOTAL_TICKS = 20;

    /** The tick the streak layers (flare, slash) start on: they read as the swing itself. */
    public static final int STRIKE_START_TICK = 2;

    /** The tick the flare is fully gone. */
    public static final int FLARE_END_TICK = 12;

    /** The tick the slash is fully gone: the whole effect's span, so the last picture fades out with it. */
    public static final int SLASH_END_TICK = TOTAL_TICKS;

    /** The tick the radial burst starts on, so it reads as the <em>result</em> of the swing. */
    public static final int BURST_START_TICK = 2;

    /** The tick the radial burst is fully gone — with the slash, at the end of the whole effect. */
    public static final int BURST_END_TICK = TOTAL_TICKS;

    /** The tick the character cut-in is fully gone (earlier than the burst: it reads as a portrait, not flash). */
    public static final int CUTIN_END_TICK = 14;

    /**
     * Peak alpha of the whole effect.
     *
     * <p>Not 1.0: the effect is drawn over the world <b>and</b> over the HUD, and full opacity on the
     * brightest art is a white flash rather than a slash. 0.85 keeps the art's own alpha ramp visible (the
     * source PNGs are 50–70% transparent at the edges) while still reading as bright.
     */
    public static final float PEAK_ALPHA = 0.85f;

    /** How much of the screen's height the cut-in portrait occupies at full scale. */
    public static final double CUTIN_HEIGHT_FRACTION = 0.55;

    /** How much of the screen's <b>shorter</b> side the radial burst spans, so it stays round. */
    public static final double BURST_FRACTION = 0.9;

    /** Minimum burst side in GUI pixels, so a tiny window still shows something rather than nothing. */
    public static final double BURST_MIN_PIXELS = 64.0;

    /** The aspect ratio (width / height) the derived cut-in art is written at: 4:3, the card's own crop. */
    public static final double CUTIN_ASPECT = 4.0 / 3.0;

    /** Minimum margin between the cut-in and the screen's edges, in GUI pixels. */
    public static final int CUTIN_MARGIN = 8;

    private UbEffectSchedule() {
    }

    // ------------------------------------------------------------------ the clock

    /**
     * {@return how many ticks old an effect triggered at {@code triggerTick} is at {@code now}}
     *
     * <p>Clamped at {@code 0}: the server's tick and the client's are not the same clock (the payload
     * carries no time at all), so a negative age is possible and means "just arrived". Reading it as
     * "not yet started" would skip the first frame, which is the brightest one.
     *
     * @param now         the client's current game time
     * @param triggerTick the client game time the payload arrived
     */
    public static int age(long now, long triggerTick) {
        long age = now - triggerTick;
        if (age <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, age);
    }

    /**
     * {@return whether anything should be drawn at {@code ageTicks}}
     *
     * <p>The one predicate the renderer asks before touching a texture, so "it is over" is answered in one
     * place — and the same place the test asserts, including the tick after it ends.
     *
     * @param ageTicks ticks since the effect started
     */
    public static boolean visible(int ageTicks) {
        return ageTicks >= 0 && ageTicks < TOTAL_TICKS;
    }

    // ------------------------------------------------------------------ the fades

    /**
     * {@return the alpha every layer is multiplied by at {@code ageTicks}, in {@code [0, PEAK_ALPHA]}}
     *
     * <p>One linear ramp over {@link #TOTAL_TICKS}, so the layers fade on their own schedules but all arrive
     * at exactly zero together. Exactly zero at and after {@link #TOTAL_TICKS}: "faded" and "absent" have
     * to be the same thing, or the layer keeps submitting invisible quads for the rest of the session.
     *
     * @param ageTicks ticks since the effect started
     */
    public static float alpha(int ageTicks) {
        if (ageTicks < 0 || ageTicks >= TOTAL_TICKS) {
            return 0.0f;
        }
        double remaining = 1.0 - (double) ageTicks / (double) TOTAL_TICKS;
        return (float) (PEAK_ALPHA * remaining);
    }

    /**
     * {@return the diagonal flare's alpha, already multiplied by the global fade}
     *
     * @param ageTicks ticks since the effect started
     */
    public static float flareAlpha(int ageTicks) {
        if (ageTicks < 0 || ageTicks >= FLARE_END_TICK) {
            return 0.0f;
        }
        // The flare is the backdrop of the swing, so it is drawn at a fraction of the peak: it must be
        // present without competing with the slash on top of it.
        return alpha(ageTicks) * 0.55f;
    }

    /**
     * {@return the golden slash trail's alpha, already multiplied by the global fade}
     *
     * <p>Zero before the swing starts, ramping in over {@link #STRIKE_START_TICK} ticks — the same
     * two-tick attack the burst uses, so the swing appears rather than popping.
     *
     * @param ageTicks ticks since the effect started
     */
    public static float slashAlpha(int ageTicks) {
        if (ageTicks < 0 || ageTicks >= SLASH_END_TICK) {
            return 0.0f;
        }
        double attack = Math.min(1.0, (double) ageTicks / Math.max(1, STRIKE_START_TICK));
        return (float) (alpha(ageTicks) * attack);
    }

    /**
     * {@return the radial burst's alpha, already multiplied by the global fade}
     *
     * <p>Zero before {@link #BURST_START_TICK} (the swing goes first) and after {@link #BURST_END_TICK};
     * rises over the first fifth of its span and falls over the rest, so it does not brighten for half its
     * life like a breathing light.
     *
     * @param ageTicks ticks since the effect started
     */
    public static float burstAlpha(int ageTicks) {
        if (ageTicks < BURST_START_TICK || ageTicks >= BURST_END_TICK) {
            return 0.0f;
        }
        double span = BURST_END_TICK - BURST_START_TICK;
        double progress = (ageTicks - BURST_START_TICK) / span;
        double envelope = progress < 0.2 ? progress / 0.2 : (1.0 - progress) / 0.8;
        return (float) (alpha(ageTicks) * clamp01(envelope));
    }

    /**
     * {@return the character cut-in's alpha, already multiplied by the global fade}
     *
     * @param ageTicks ticks since the effect started
     */
    public static float cutInAlpha(int ageTicks) {
        if (ageTicks < 0 || ageTicks >= CUTIN_END_TICK) {
            return 0.0f;
        }
        return alpha(ageTicks);
    }

    // ------------------------------------------------------------------ the rectangles

    /**
     * {@return the whole GUI as a rectangle}
     *
     * @param screenWidth  the GUI width in pixels
     * @param screenHeight the GUI height in pixels
     */
    public static Box fullScreen(int screenWidth, int screenHeight) {
        return new Box(0, 0, Math.max(0, screenWidth), Math.max(0, screenHeight));
    }

    /**
     * {@return the radial burst's square, centred on the screen and sized from its shorter side}
     *
     * <p>Square, and sized from the <b>shorter</b> side, because the source art is a radial burst: scaling
     * it to the window's aspect ratio would draw an ellipse, which reads as a stretched texture. The
     * minimum keeps a small window (a 320x240 GUI on a laptop) from producing a burst smaller than a
     * hotbar slot.
     *
     * @param screenWidth  the GUI width in pixels
     * @param screenHeight the GUI height in pixels
     */
    public static Box burstBox(int screenWidth, int screenHeight) {
        double shorter = Math.min(Math.max(0, screenWidth), Math.max(0, screenHeight)) * BURST_FRACTION;
        int side = (int) Math.round(Math.max(Math.min(BURST_MIN_PIXELS, Math.min(screenWidth, screenHeight)),
                shorter));
        if (side <= 0) {
            return new Box(0, 0, 0, 0);
        }
        return new Box((screenWidth - side) / 2, (screenHeight - side) / 2, side, side);
    }

    /**
     * {@return the cut-in portrait's rectangle}
     *
     * <p>Anchored to the <b>bottom-right</b> corner and lifted clear of the hotbar: the cast pad and the
     * gauge live in the middle and the bottom-left ({@code HudLayout}'s default), the top-right is where
     * Jade/AppleSkin-class mods draw, so the bottom-right is the one corner this mod does not already use.
     * A portrait that covered the crosshair or the gauge would be a HUD bug, not a cut-in.
     *
     * <p>The aspect ratio is the derived art's own ({@link #CUTIN_ASPECT}), so the picture is never
     * stretched; height follows the window, and the rectangle is pulled inside the screen if the window is
     * small in either direction.
     *
     * @param screenWidth  the GUI width in pixels
     * @param screenHeight the GUI height in pixels
     */
    public static Box cutInBox(int screenWidth, int screenHeight) {
        int height = (int) Math.round(Math.max(0, screenHeight) * CUTIN_HEIGHT_FRACTION);
        int width = (int) Math.round(height * CUTIN_ASPECT);
        int margin = Math.max(CUTIN_MARGIN, Math.max(0, screenHeight) / 24);
        int x = Math.max(0, screenWidth - width - margin);
        int y = Math.max(0, screenHeight - height - margin);
        int clampedWidth = Math.min(width, Math.max(0, screenWidth));
        int clampedHeight = Math.min(height, Math.max(0, screenHeight));
        return new Box(Math.min(x, Math.max(0, screenWidth - clampedWidth)),
                Math.min(y, Math.max(0, screenHeight - clampedHeight)), clampedWidth, clampedHeight);
    }

    /**
     * {@return the vertical middle of the screen, where the slash reads best}
     *
     * <p>The slash art is a diagonal trail; drawing it full-screen keeps its diagonal intact (a square
     * scaled to the window becomes a shallower diagonal, which is the cheapest way to fill the frame
     * without a second texture).
     *
     * @param screenHeight the GUI height in pixels
     */
    public static int centreY(int screenHeight) {
        return Math.max(0, screenHeight) / 2;
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : Math.min(value, 1.0);
    }

    /**
     * A rectangle in GUI pixels, half-open like every other rectangle in this project.
     *
     * @param x      left edge
     * @param y      top edge
     * @param width  width
     * @param height height
     */
    public record Box(int x, int y, int width, int height) {

        /** {@return the first pixel column past this box} */
        public int right() {
            return x + width;
        }

        /** {@return the first pixel row past this box} */
        public int bottom() {
            return y + height;
        }

        /** {@return whether this box has any area to draw} */
        public boolean isDrawable() {
            return width > 0 && height > 0;
        }
    }
}
