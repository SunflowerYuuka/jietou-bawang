package com.astrum.pcrd.impl.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;

/**
 * Filled ring slices, discs and strokes as GUI quads.
 *
 * <h2>Why not {@code GuiGraphics.fill}</h2>
 * A radial menu is made of circular bands, and {@code fill} only draws axis-aligned rectangles; a
 * wheel built from those is a stepped polygon that reads as a bug at every GUI scale. So the slices
 * are emitted as quads into the same {@code RenderType.gui()} batch vanilla HUD code uses — one
 * buffer source, blended, depth-tested the same way.
 *
 * <h2>⚠ The one thing that is not free: the winding</h2>
 * {@code RenderType.gui()} is built from {@code RenderType.CompositeState.builder()} with only a
 * shader, a transparency and a depth-test state set — so it inherits the builder's default
 * {@code cullState = RenderStateShard.CULL}, i.e. <b>back-face culling is on</b>. A quad wound the other
 * way is not drawn darker or thinner; it is not drawn at all. This class used to emit
 * {@code inner@start → outer@start → outer@end → inner@end}, which is the opposite winding to
 * {@code GuiGraphics.fill}'s {@code (x1,y1) (x1,y2) (x2,y2) (x2,y1)} and was therefore culled: the wheel's
 * plate, the HUD's cooldown ring and every arc were invisible while icons, text and vanilla
 * {@code fill}s drew normally. It was found by reading the pixel back in a probe (the disc's centre pixel
 * was sky, an immediate vanilla {@code fill} at the same point was not) — no screenshot involved.
 *
 * <p>So the order is now <b>{@code inner@start → inner@end → outer@end → outer@start}</b>, which has the
 * same signed area sign as vanilla's rectangle ({@link #ringQuad} is exposed precisely so
 * {@code GuiShapesTest} can assert that sign, and the assertion names the cull state it protects).
 *
 * <h2>The single primitive</h2>
 * Everything is {@link #band}: an annulus between two radii and two angles. A disc is a band whose
 * inner radius is {@code 0} (two of its four corners coincide, which the rasteriser handles), and a
 * stroke is a band around a radius. Having one primitive means the geometry tests cover all of them.
 *
 * <p>Angles are clockwise degrees from 12 o'clock, the same convention as {@link WheelGeometry}, so
 * no caller has to convert.
 */
@ApiStatus.Internal
public final class GuiShapes {

    /** Degrees per quad along the arc. 6° keeps a full turn at 60 quads — smooth at any GUI scale. */
    private static final double STEP_DEGREES = 6.0;

    /** Vertices one quad contributes; the unit {@link #band} reports its work in. */
    public static final int VERTICES_PER_QUAD = 4;

    private GuiShapes() {
    }

    /**
     * Draws an annular slice.
     *
     * @param graphics  the GUI buffer
     * @param centerX   centre x
     * @param centerY   centre y
     * @param inner     inner radius
     * @param outer     outer radius
     * @param startDeg  start angle, clockwise from up
     * @param endDeg    end angle, clockwise from up; may be less than {@code startDeg}
     * @param argb      fill colour, alpha included
     * @return how many vertices were emitted, so a caller can log that the draw really happened
     */
    public static int band(GuiGraphics graphics, double centerX, double centerY, double inner,
            double outer, double startDeg, double endDeg, int argb) {
        double sweep = endDeg - startDeg;
        int steps = stepsFor(startDeg, endDeg);
        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < steps; i++) {
            double a0 = startDeg + sweep * i / steps;
            double a1 = startDeg + sweep * (i + 1) / steps;
            double[][] quad = ringQuad(centerX, centerY, inner, outer, a0, a1);
            addQuad(vertices, matrix, quad, argb);
        }
        graphics.flush();
        return steps * VERTICES_PER_QUAD;
    }

    /**
     * Draws a filled circle.
     *
     * @param graphics the GUI buffer
     * @param centerX  centre x
     * @param centerY  centre y
     * @param radius   radius
     * @param argb     fill colour, alpha included
     * @return how many vertices were emitted
     */
    public static int disc(GuiGraphics graphics, double centerX, double centerY, double radius,
            int argb) {
        return band(graphics, centerX, centerY, 0.0, radius, 0.0, 360.0, argb);
    }

    /**
     * Draws a circular outline.
     *
     * @param graphics  the GUI buffer
     * @param centerX   centre x
     * @param centerY   centre y
     * @param radius    radius of the stroke's centre line
     * @param thickness stroke width
     * @param argb      stroke colour, alpha included
     * @return how many vertices were emitted
     */
    public static int outline(GuiGraphics graphics, double centerX, double centerY, double radius,
            double thickness, int argb) {
        double half = thickness / 2.0;
        return band(graphics, centerX, centerY, Math.max(0.0, radius - half), radius + half, 0.0, 360.0,
                argb);
    }

    /**
     * Draws an arc along a circle: the cooldown gauge.
     *
     * @param graphics  the GUI buffer
     * @param centerX   centre x
     * @param centerY   centre y
     * @param radius    radius of the stroke's centre line
     * @param thickness stroke width
     * @param fraction  sweep as a fraction of a full turn, {@code 0..1}, starting at 12 o'clock
     * @param argb      stroke colour, alpha included
     * @return how many vertices were emitted
     */
    public static int arc(GuiGraphics graphics, double centerX, double centerY, double radius,
            double thickness, double fraction, int argb) {
        double sweep = Math.max(0.0, Math.min(1.0, fraction)) * 360.0;
        if (sweep <= 0.0) {
            return 0;
        }
        double half = thickness / 2.0;
        return band(graphics, centerX, centerY, Math.max(0.0, radius - half), radius + half, 0.0, sweep,
                argb);
    }

    /** {@return the x of a point at {@code degrees} clockwise from up} */
    public static double pointX(double centerX, double radius, double degrees) {
        return centerX + Math.sin(Math.toRadians(degrees)) * radius;
    }

    /** {@return the y of a point at {@code degrees} clockwise from up} */
    public static double pointY(double centerY, double radius, double degrees) {
        return centerY - Math.cos(Math.toRadians(degrees)) * radius;
    }

    /**
     * {@return how many quads one band is split into}
     *
     * @param startDeg start angle, clockwise from up
     * @param endDeg   end angle; may be less than {@code startDeg}
     */
    public static int stepsFor(double startDeg, double endDeg) {
        return Math.max(1, (int) Math.ceil(Math.abs(endDeg - startDeg) / STEP_DEGREES));
    }

    /**
     * {@return the four corners of one band slice, <b>in drawing order</b>}
     *
     * <p>The order is {@code inner@start, inner@end, outer@end, outer@start} and it is the whole
     * subject of this class's winding note: {@code RenderType.gui()} culls back faces, so the opposite
     * order (which this class used to emit) draws nothing at all. It is public so
     * {@code GuiShapesTest} can assert the signed area has the same sign as vanilla's own rectangle
     * rather than trusting a comment.
     *
     * @param centerX  centre x
     * @param centerY  centre y
     * @param inner    inner radius
     * @param outer    outer radius
     * @param startDeg start angle, clockwise from up
     * @param endDeg   end angle, clockwise from up
     */
    public static double[][] ringQuad(double centerX, double centerY, double inner, double outer,
            double startDeg, double endDeg) {
        // A reversed sweep describes the same slice, but traversing it backwards would flip the winding
        // — and a flipped winding is culled, i.e. invisible. So the direction is normalised here rather
        // than left to every caller: "sweep backwards" is a legal argument, "draw nothing" is not.
        double from = endDeg < startDeg ? endDeg : startDeg;
        double to = endDeg < startDeg ? startDeg : endDeg;
        return new double[][] {
            {pointX(centerX, inner, from), pointY(centerY, inner, from)},
            {pointX(centerX, inner, to), pointY(centerY, inner, to)},
            {pointX(centerX, outer, to), pointY(centerY, outer, to)},
            {pointX(centerX, outer, from), pointY(centerY, outer, from)},
        };
    }

    /**
     * {@return the signed area of a polygon given as {@code {x, y}} pairs}
     *
     * <p>The shoelace formula, in GUI coordinates (y grows downwards). Its sign is what OpenGL's
     * winding test sees, so it is the only thing standing between "drawn" and "culled". Zero means a
     * degenerate polygon — which a band slice with {@code inner == 0} is <em>not</em>: two of its corners
     * coincide, but the other two are apart.
     *
     * @param points at least three {@code {x, y}} pairs
     */
    public static double signedArea(double[][] points) {
        double sum = 0.0;
        for (int i = 0; i < points.length; i++) {
            double[] a = points[i];
            double[] b = points[(i + 1) % points.length];
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum / 2.0;
    }

    private static void addQuad(VertexConsumer vertices, Matrix4f matrix, double[][] quad, int argb) {
        for (double[] corner : quad) {
            vertices.addVertex(matrix, (float) corner[0], (float) corner[1], 0.0f).setColor(argb);
        }
    }
}
