package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where the radial menu's band actually is on screen, and which segment a mouse position falls in
 * (spec §5.5). Pure arithmetic: no {@code net.minecraft.client} types, no screen, no font.
 *
 * <h2>Why the numbers live here and not in the renderer</h2>
 * The spec makes four <em>visual</em> claims — ring radius 20–33% of screen height (default 27%),
 * band width 6–10% (default 8%), interior completely empty, band alpha 0.45–0.70 (default 0.55) — and
 * a screenshot cannot assert any of them. As constants plus two pure functions they become assertions
 * ({@code WheelGeometryTest}): a screen height maps to a measurable inner/outer radius, and a mouse
 * position maps to a segment or to nothing at all. {@code WheelScreen} is then only quads and text on
 * top of these answers.
 *
 * <h2>Why these numbers, and not the first ones</h2>
 * The first cut drew a 0.40 ring at alpha 0.30. The human ran it on his own screen and said it was
 * <b>about 1.5× too large</b> and that the band looked <b>almost fully transparent</b> against a
 * colourful background, so icons blended into the scene. Spec §5.5 carries the verdict (committed with
 * it, <em>before</em> this code): radius to two thirds (0.27), band narrowed in proportion (0.08, not
 * the old 0.12 — leaving it fat on a smaller radius reads as a small, fat wheel), alpha up to 0.55.
 *
 * <p>What did <b>not</b> change: nothing is drawn inside the inner radius at any size. The empty
 * middle is what keeps the crosshair and the enemy visible (spec §5.5: 环内区域完全留空), and it is a
 * hard requirement, not a side effect of the ring being small — it does not scale with the ring.
 *
 * <h2>The one ordering both meanings share</h2>
 * Segment {@code i} is party slot {@code i} — the order {@link Party} already owns (slot order is
 * both diminishing rank and wheel position, spec §5.2/§5.5). This class therefore never sorts,
 * filters or re-indexes: index in, same index out.
 *
 * <h2>Angles</h2>
 * Degrees measured clockwise from 12 o'clock, so segment 0 sits at the top and the wheel reads
 * clockwise 1→2→3→4→5 like the hotbar it maps onto. {@code 0°} is up, {@code 90°} is right.
 */
@ApiStatus.Internal
public final class WheelGeometry {

    /** Segments on the ring. Fixed by the design: one per {@link Party} slot. */
    public static final int SEGMENTS = Party.SLOTS;

    /**
     * Radius <em>through the middle of the band</em>, as a fraction of screen height.
     *
     * <p>Spec §5.5: 20–33%, default 27%. The old 0.40 was the human's playtest verdict "about 1.5× too
     * large"; 0.27 is that value taken down to two thirds.
     */
    public static final double RADIUS_FRACTION_DEFAULT = 0.27;

    /** Lowest radius the config is allowed to produce (spec §5.5 lower bound). */
    public static final double RADIUS_FRACTION_MIN = 0.20;

    /** Highest radius the config is allowed to produce (spec §5.5 upper bound). */
    public static final double RADIUS_FRACTION_MAX = 0.33;

    /**
     * Band width as a fraction of screen height. Spec §5.5: 6–10%, default 8%.
     *
     * <p>Measured against screen height rather than against the radius so the band keeps an absolute
     * size at every GUI scale: at 1080p/scale 3 (360 px of GUI height) it is ~29 px, enough for the icon
     * disc and one line of name text. As a fraction of the radius it would shrink with the wheel and
     * clip that text.
     *
     * <p>The playtest also narrowed it from 0.12 to 0.08 <em>in proportion</em> with the radius: the
     * band is a fraction of height, so leaving it at 0.12 while the radius fell to 0.27 would have
     * produced a small, fat ring instead of the same ring drawn smaller.
     */
    public static final double BAND_FRACTION = 0.08;

    /** Lowest band width the config is allowed to produce (spec §5.5). */
    public static final double BAND_FRACTION_MIN = 0.06;

    /** Highest band width the config is allowed to produce (spec §5.5). */
    public static final double BAND_FRACTION_MAX = 0.10;

    /**
     * Band background alpha. Spec §5.5: 0.45–0.70, default 0.55 = {@code ClientConfig.wheelOpacity}.
     *
     * <p>The dark base colour ({@code 0x16203A} in {@code WheelScreen}) was always there; at 0.30 the
     * human could not see it as a plate at all on a colourful background. 0.55 is the value his
     * playtest settled on — enough to read as one surface under the icons, still see-through.
     */
    public static final double BAND_ALPHA_DEFAULT = 0.55;

    /** Lowest band alpha the config is allowed to produce (spec §5.5). */
    public static final double BAND_ALPHA_MIN = 0.45;

    /** Highest band alpha the config is allowed to produce (spec §5.5). */
    public static final double BAND_ALPHA_MAX = 0.70;

    /** Returned by {@link #segmentAt} when the point is not over the band. */
    public static final int NO_SEGMENT = -1;

    private WheelGeometry() {
    }

    /**
     * The band, in GUI pixels.
     *
     * @param centerX     screen centre x — the wheel is centred, so the crosshair stays on the axis
     * @param centerY     screen centre y
     * @param innerRadius distance from the centre to the inside edge of the band
     * @param outerRadius distance from the centre to the outside edge of the band
     */
    public record Ring(double centerX, double centerY, double innerRadius, double outerRadius) {

        /** {@return the radius through the middle of the band, where icons and labels go} */
        public double midRadius() {
            return (innerRadius + outerRadius) / 2.0;
        }

        /** {@return the band width in pixels} */
        public double bandWidth() {
            return outerRadius - innerRadius;
        }

        /**
         * {@return whether a distance from the centre lands on the band itself}
         *
         * @param distance radial distance from {@link #centerX}/{@link #centerY}
         */
        public boolean containsRadius(double distance) {
            return distance >= innerRadius && distance <= outerRadius;
        }
    }

    /** Clamps a configured radius fraction into the spec's band. */
    public static double clampRadiusFraction(double fraction) {
        if (Double.isNaN(fraction)) {
            return RADIUS_FRACTION_DEFAULT;
        }
        return Math.max(RADIUS_FRACTION_MIN, Math.min(RADIUS_FRACTION_MAX, fraction));
    }

    /** Clamps a band fraction into the range this class is willing to draw. */
    public static double clampBandFraction(double fraction) {
        if (Double.isNaN(fraction)) {
            return BAND_FRACTION;
        }
        return Math.max(BAND_FRACTION_MIN, Math.min(BAND_FRACTION_MAX, fraction));
    }

    /** Clamps a configured band alpha into the spec's 0.45–0.70 window (spec §5.5). */
    public static double clampAlpha(double alpha) {
        if (Double.isNaN(alpha)) {
            return BAND_ALPHA_DEFAULT;
        }
        return Math.max(BAND_ALPHA_MIN, Math.min(BAND_ALPHA_MAX, alpha));
    }

    /**
     * Builds the band for one frame.
     *
     * <p>{@code radiusFraction} is the radius <em>through the middle of the band</em>, so the band
     * straddles it: at the default 0.27 of a 360 px height the band runs from 0.23 to 0.31 of the
     * height, which keeps everything inside 0.23 untouched — the crosshair and whatever the player is
     * aiming at (spec §5.5: 环内区域完全留空). That empty interior is a property of the geometry, not
     * something drawn over it: nothing is painted inside the inner radius at any size, and making the
     * ring smaller does not move the crosshair or the aim point.
     *
     * <p>The outer edge is additionally kept inside the screen. Inside the spec's 20–33% window and on
     * any landscape window the outer edge lands at most 0.37 of the height, well inside the frame; the
     * clamp is what stops a <em>hand-edited</em> config, or a window narrower than it is tall, from
     * drawing a wheel whose last segment is cut in half. Clamping here rather than in the config keeps
     * the configured numbers the spec's numbers.
     *
     * @param screenWidth     GUI-scaled screen width
     * @param screenHeight    GUI-scaled screen height
     * @param radiusFraction  configured radius as a fraction of height ({@code wheelScale})
     * @param bandFraction    band width as a fraction of height
     * @return the band, centred on the screen
     */
    public static Ring ring(int screenWidth, int screenHeight, double radiusFraction, double bandFraction) {
        double band = Math.max(0.0, screenHeight * clampBandFraction(bandFraction));
        double radius = Math.max(0.0, screenHeight * clampRadiusFraction(radiusFraction));
        // Keep the outer edge on screen, and never let a thick band swallow the interior.
        radius = Math.max(0.0, Math.min(radius, Math.min(screenWidth, screenHeight) / 2.0 - band / 2.0));
        return new Ring(screenWidth / 2.0, screenHeight / 2.0,
                Math.max(0.0, radius - band / 2.0), radius + band / 2.0);
    }

    /** {@return the width of one segment's wedge, in degrees} */
    public static double sectorDegrees() {
        return 360.0 / SEGMENTS;
    }

    /**
     * {@return the clockwise-from-up angle where segment {@code index} starts}
     *
     * @param index segment index in {@code [0, SEGMENTS)}
     */
    public static double segmentStartDegrees(int index) {
        return index * sectorDegrees() - sectorDegrees() / 2.0;
    }

    /**
     * {@return the clockwise-from-up angle where segment {@code index} ends}
     *
     * @param index segment index in {@code [0, SEGMENTS)}
     */
    public static double segmentEndDegrees(int index) {
        return index * sectorDegrees() + sectorDegrees() / 2.0;
    }

    /**
     * {@return the x of the point in the middle of segment {@code index}'s band}
     *
     * @param ring  the band
     * @param index segment index in {@code [0, SEGMENTS)}
     */
    public static double segmentCenterX(Ring ring, int index) {
        return ring.centerX() + Math.sin(Math.toRadians(index * sectorDegrees())) * ring.midRadius();
    }

    /**
     * {@return the y of the point in the middle of segment {@code index}'s band}
     *
     * @param ring  the band
     * @param index segment index in {@code [0, SEGMENTS)}
     */
    public static double segmentCenterY(Ring ring, int index) {
        return ring.centerY() - Math.cos(Math.toRadians(index * sectorDegrees())) * ring.midRadius();
    }

    /**
     * Which segment a point is over.
     *
     * <p>Two separate no-answers, both deliberate: inside {@link Ring#innerRadius()} the wheel is
     * empty by design (the point belongs to the crosshair, not to a slot), and outside the outer
     * edge there is nothing at all. Both return {@link #NO_SEGMENT}, so the caller cannot hover a
     * segment the player is not actually pointing at.
     *
     * @param ring the band
     * @param x    GUI x of the point
     * @param y    GUI y of the point
     * @return the segment index in {@code [0, SEGMENTS)}, or {@link #NO_SEGMENT}
     */
    public static int segmentAt(Ring ring, double x, double y) {
        double dx = x - ring.centerX();
        double dy = y - ring.centerY();
        if (!ring.containsRadius(Math.sqrt(dx * dx + dy * dy))) {
            return NO_SEGMENT;
        }
        return segmentAtAngle(clockwiseDegreesFromUp(dx, dy));
    }

    /**
     * Which segment an angle lands in.
     *
     * @param degrees clockwise from up; any real value is accepted and normalised
     * @return the segment index in {@code [0, SEGMENTS)}
     */
    public static int segmentAtAngle(double degrees) {
        double normalised = degrees % 360.0;
        if (normalised < 0) {
            normalised += 360.0;
        }
        double half = sectorDegrees() / 2.0;
        int index = (int) Math.floor((normalised + half) / sectorDegrees());
        return Math.floorMod(index, SEGMENTS);
    }

    /**
     * {@return the clockwise-from-up angle of a screen-space offset}
     *
     * @param dx offset to the right
     * @param dy offset downwards
     */
    public static double clockwiseDegreesFromUp(double dx, double dy) {
        return Math.toDegrees(Math.atan2(dx, -dy));
    }
}
