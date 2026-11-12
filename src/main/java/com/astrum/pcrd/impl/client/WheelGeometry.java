package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.Party;
import org.jetbrains.annotations.ApiStatus;

/**
 * Where the radial menu's band actually is on screen, which segment a direction or a position falls in,
 * and where a sector's own centre point lies (spec §5.5). Pure arithmetic: no
 * {@code net.minecraft.client} types, no screen, no font.
 *
 * <h2>Why the numbers live here and not in the renderer</h2>
 * The spec makes <em>visual</em> claims — ring radius 20–33% of screen height (default 27%), band width
 * 6–10% (default 8%), band alpha 0.00–1.00 (default 0.75), and a selection that lands on exactly one
 * segment — and a screenshot cannot assert any of them. As constants plus pure functions they become
 * assertions ({@code WheelGeometryTest}): a screen height maps to a measurable inner/outer radius, a
 * direction maps to a segment, and {@link #segmentCentroidX} says where a sector's centroid is.
 * {@code WheelOverlay} is then only quads and text on top of these answers.
 *
 * <h2>Two layouts share all of this</h2>
 * {@code ClientConfig.wheelMode} chooses between a hollow {@code RING} and a complete {@code DISC}
 * (spec §5.5). That is a <b>draw-time</b> difference and it lives in the renderer:
 *
 * <ul>
 *   <li>The <b>band</b> is {@link Ring#innerRadius()} outward in both modes; {@code DISC} additionally
 *       paints the interior with the same plate ({@link #plateInnerRadius} answers where a sector's
 *       fill starts in each mode).</li>
 *   <li>{@code RING} puts icons and labels on the band ({@link #segmentCenterX} at the mid radius);
 *       {@code DISC} puts them at the sector's centroid ({@link #segmentCentroidX}, spec §5.5
 *       扇形自身的中心点).</li>
 *   <li><b>Selection is identical</b> in both: {@link #segmentAt} answers
 *       {@link #NO_SEGMENT} inside the inner radius — the centre dead zone — so a filled centre cannot
 *       make the preset flicker when the player moves through the middle. Drawing the middle is not a
 *       reason to make it clickable.</li>
 * </ul>
 *
 * <p>So "the interior is empty" is a statement about {@code RING} only, and the reason it exists is
 * visibility (more battlefield through the middle), not hit testing. {@code DISC} trades a little of
 * that visibility for a plate that reads as one surface; the player picks with {@code wheelMode}.
 *
 * <h2>Why these numbers, and not the first ones</h2>
 * The first cut drew a 0.40 ring at alpha 0.30. The human ran it on his own screen and said it was
 * <b>about 1.5× too large</b> and that the band looked <b>almost fully transparent</b> against a
 * colourful background, so icons blended into the scene. Spec §5.5 carries the verdict (committed with
 * it, <em>before</em> this code): radius to two thirds (0.27), band narrowed in proportion (0.08, not
 * the old 0.12 — leaving it fat on a smaller radius reads as a small, fat wheel), alpha up to 0.55.
 *
 * <p><b>Second playtest (2026-11-12, fix round 4):</b> 0.55 still read as transparent over a busy
 * background, so the default moved to 0.75 — and, more importantly, the clamp window became the whole
 * 0.00–1.00 range the config declares. The alpha used to be clamped into a 0.45–0.70 window that the
 * config did not declare, so a player who typed 0.9 got 0.70 with nothing to tell him why: a config
 * value that is silently rewritten is a defect, not a guardrail. Keeping the clamp at exactly the
 * config's own range makes "the value you set is the value you get" true, and a fully opaque plate
 * reachable for anyone who wants one.
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
     * Band background alpha. Spec §5.5: 0.00–1.00, default 0.75 = {@code ClientConfig.wheelOpacity}.
     *
     * <p>The dark base colour ({@code 0x16203A} in {@code WheelOverlay}) was always there; at 0.30 the
     * human could not see it as a plate at all on a colourful background, and the 0.55 he first settled
     * on still read as transparent over a busy scene. 0.75 is the second playtest's answer — one
     * surface under the icons. The top of the window is 1.00 on purpose: a player who wants the plate
     * fully opaque must be able to have it, which the old 0.70 ceiling made impossible while the config
     * file claimed 0.0–1.0.
     */
    public static final double BAND_ALPHA_DEFAULT = 0.75;

    /** Lowest band alpha the config is allowed to produce (spec §5.5: fully transparent is a choice). */
    public static final double BAND_ALPHA_MIN = 0.0;

    /** Highest band alpha the config is allowed to produce (spec §5.5: 1.00 is fully opaque). */
    public static final double BAND_ALPHA_MAX = 1.0;

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

    /**
     * Clamps a configured band alpha into the config's own 0.00–1.00 range (spec §5.5).
     *
     * <p>Only {@code NaN} is rewritten: there is no value inside the declared range that this clamp
     * would change, which is the point — the old 0.45–0.70 window silently overrode a legal config
     * value (see the class javadoc).
     */
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
     * height. That band is what both layout modes have in common: it is where the segments start in
     * {@code DISC} and the whole drawn wheel in {@code RING}.
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
     * <p>This is {@code RING}'s icon/label position: on the band, at the mid radius. {@code DISC} uses
     * {@link #segmentCentroidX} instead (spec §5.5: 图标与文字画在扇形自身的中心点).
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
     * @see #segmentCenterX
     */
    public static double segmentCenterY(Ring ring, int index) {
        return ring.centerY() - Math.cos(Math.toRadians(index * sectorDegrees())) * ring.midRadius();
    }

    /**
     * {@return how far out a circular sector's centroid lies, as a fraction of the sector's radius}
     *
     * <p>The real centroid of a circular sector of half-angle α sits along the bisector at
     * {@code (2/3)·R·sin(α)/α} from the centre — not at {@code 0.5R}. At 5 equal segments
     * (α = 36°) that is ≈ {@code 0.624R}, and it approaches {@code 2/3·R} as α → 0. Spec §5.5 asks for
     * the centroid by name, so the formula is written down here and asserted in
     * {@code WheelGeometryTest} rather than being chosen to look about right.
     *
     * @param halfAngleDegrees the sector's half-angle α, in degrees, {@code (0, 180]}
     */
    public static double centroidRadiusFraction(double halfAngleDegrees) {
        double alpha = Math.toRadians(Math.abs(halfAngleDegrees));
        if (!(alpha > 1e-6)) {
            return 2.0 / 3.0;
        }
        return (2.0 / 3.0) * Math.sin(alpha) / alpha;
    }

    /** {@return {@link #centroidRadiusFraction} for this wheel's own sector angle} */
    public static double centroidRadiusFraction() {
        return centroidRadiusFraction(sectorDegrees() / 2.0);
    }

    /**
     * {@return the x of segment {@code index}'s centroid, where {@code DISC} draws its icon and label}
     *
     * @param ring  the wheel
     * @param index segment index in {@code [0, SEGMENTS)}
     */
    public static double segmentCentroidX(Ring ring, int index) {
        return ring.centerX() + Math.sin(Math.toRadians(index * sectorDegrees()))
                * centroidRadius(ring);
    }

    /**
     * {@return the y of segment {@code index}'s centroid, where {@code DISC} draws its icon and label}
     *
     * @param ring  the wheel
     * @param index segment index in {@code [0, SEGMENTS)}
     */
    public static double segmentCentroidY(Ring ring, int index) {
        return ring.centerY() - Math.cos(Math.toRadians(index * sectorDegrees()))
                * centroidRadius(ring);
    }

    /** {@return the distance from the centre to a sector's centroid, in GUI pixels} */
    public static double centroidRadius(Ring ring) {
        return ring.outerRadius() * centroidRadiusFraction();
    }

    /**
     * {@return the radius a segment's translucent fill starts at, in this layout mode}
     *
     * <p>{@code centerFilled} is {@code DISC}: the fill reaches the centre, so the wheel is one
     * complete plate. {@code RING} starts at the band's inner edge, leaving the middle clear. The
     * <b>band</b> is {@link Ring#innerRadius()} outward in both modes, which is why the ring itself
     * does not move between them.
     *
     * @param ring         the wheel
     * @param centerFilled whether the centre is painted with the same plate
     */
    public static double plateInnerRadius(Ring ring, boolean centerFilled) {
        return centerFilled ? 0.0 : ring.innerRadius();
    }

    /**
     * Which segment a point is over.
     *
     * <p>Two separate no-answers, both deliberate: inside {@link Ring#innerRadius()} nothing is
     * selected — the <b>centre dead zone</b>, kept in both layout modes so that moving the mouse
     * through the middle of the wheel does not shuffle the preset (spec §5.5) — and outside the outer
     * edge there is nothing at all. Both return {@link #NO_SEGMENT}, so the caller cannot select a
     * segment the player is not actually pointing at.
     *
     * <p>{@code DISC} paints that middle, but painting is not selecting: this function is
     * mode-independent on purpose.
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
