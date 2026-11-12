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
 * buffer source, blended, depth-tested the same way — and flushed at the end of the call.
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
     */
    public static void band(GuiGraphics graphics, double centerX, double centerY, double inner,
            double outer, double startDeg, double endDeg, int argb) {
        double sweep = endDeg - startDeg;
        int steps = Math.max(1, (int) Math.ceil(Math.abs(sweep) / STEP_DEGREES));
        Matrix4f matrix = graphics.pose().last().pose();
        VertexConsumer vertices = graphics.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < steps; i++) {
            double a0 = startDeg + sweep * i / steps;
            double a1 = startDeg + sweep * (i + 1) / steps;
            addQuad(vertices, matrix,
                    pointX(centerX, inner, a0), pointY(centerY, inner, a0),
                    pointX(centerX, outer, a0), pointY(centerY, outer, a0),
                    pointX(centerX, outer, a1), pointY(centerY, outer, a1),
                    pointX(centerX, inner, a1), pointY(centerY, inner, a1),
                    argb);
        }
        graphics.flush();
    }

    /**
     * Draws a filled circle.
     *
     * @param graphics the GUI buffer
     * @param centerX  centre x
     * @param centerY  centre y
     * @param radius   radius
     * @param argb     fill colour, alpha included
     */
    public static void disc(GuiGraphics graphics, double centerX, double centerY, double radius,
            int argb) {
        band(graphics, centerX, centerY, 0.0, radius, 0.0, 360.0, argb);
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
     */
    public static void outline(GuiGraphics graphics, double centerX, double centerY, double radius,
            double thickness, int argb) {
        double half = thickness / 2.0;
        band(graphics, centerX, centerY, Math.max(0.0, radius - half), radius + half, 0.0, 360.0, argb);
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
     */
    public static void arc(GuiGraphics graphics, double centerX, double centerY, double radius,
            double thickness, double fraction, int argb) {
        double sweep = Math.max(0.0, Math.min(1.0, fraction)) * 360.0;
        if (sweep <= 0.0) {
            return;
        }
        double half = thickness / 2.0;
        band(graphics, centerX, centerY, Math.max(0.0, radius - half), radius + half, 0.0, sweep, argb);
    }

    /** {@return the x of a point at {@code degrees} clockwise from up} */
    public static double pointX(double centerX, double radius, double degrees) {
        return centerX + Math.sin(Math.toRadians(degrees)) * radius;
    }

    /** {@return the y of a point at {@code degrees} clockwise from up} */
    public static double pointY(double centerY, double radius, double degrees) {
        return centerY - Math.cos(Math.toRadians(degrees)) * radius;
    }

    private static void addQuad(VertexConsumer vertices, Matrix4f matrix,
            double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4,
            int argb) {
        vertices.addVertex(matrix, (float) x1, (float) y1, 0.0f).setColor(argb);
        vertices.addVertex(matrix, (float) x2, (float) y2, 0.0f).setColor(argb);
        vertices.addVertex(matrix, (float) x3, (float) y3, 0.0f).setColor(argb);
        vertices.addVertex(matrix, (float) x4, (float) y4, 0.0f).setColor(argb);
    }
}
