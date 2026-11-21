package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.UbCooldown;
import org.jetbrains.annotations.ApiStatus;

/**
 * The shade that covers the UB gauge's face while <b>that character</b> is on cooldown: fully covered
 * when the cooldown starts, gone when she is castable again.
 *
 * <h2>What the human asked for, and the correction</h2>
 * 「UB 槽头像用阴影逐渐覆盖，全覆盖后 CD 转完。」 then, immediately, 「这个反过来，从阴影全覆盖，到没有阴影。」
 * So the direction is: <b>full → none</b>. At the instant of the cast the face is completely in shadow;
 * as the cooldown runs the shadow lifts, linearly in remaining time, and reaches nothing on exactly the
 * tick she becomes castable. The earlier reading (shade accumulating, "CD 转完" when full) is the one he
 * corrected, and the "CD 转完" half survives in the direction he corrected it to: the end of the
 * cooldown is when the shade is gone.
 *
 * <h2>Where the numbers come from</h2>
 * The fraction is {@link UbCooldown#remainingFraction(long)} — <b>the same call the cooldown ring
 * sweeps with</b>, so the ring and the shade can never disagree about how much time is left (they used
 * to be separate arithmetic: the ring had a client-observed span because the packet carried no total).
 * The denominator is the {@code totalTicks} the server stamped into the cooldown when it armed it —
 * the length that cooldown <em>actually</em> has, not {@code ServerConfig}'s current value. An operator
 * retuning {@code ubGlobalCooldownTicks} mid-cooldown therefore does not make the shade jump: the old
 * deadline keeps the old divisor.
 *
 * <h2>Why the mapping lives here and not inline in the HUD</h2>
 * {@code UbHud} cannot be instantiated in a test (it wants a {@code GuiGraphics}, a {@code Minecraft}
 * and a framebuffer), so anything about the shade that is only written there is unverified. The two
 * claims that matter — "alpha is {@link #MAX_ALPHA} at the start, 0 at expiry" and "it never goes
 * backwards while the clock runs forwards" — are assertions about this class instead, and the HUD is
 * left with one {@code fill}.
 */
@ApiStatus.Internal
public final class FaceShade {

    /**
     * Opacity of the shade at the very start of a cooldown, out of {@code 255}.
     *
     * <p>{@code 0xC0} rather than {@code 0xFF}: at full opacity the circle is a black disc and the
     * player cannot tell <em>whose</em> ultimate is cooling — which defeats the point of putting her
     * face there (「UB条最左边的圆圈，显示的应该是当前预设的角色的头像」). A quarter of the picture showing
     * through is still unmistakably "shaded", and a shade that lifts to nothing reads as time passing.
     */
    public static final int MAX_ALPHA = 0xC0;

    /** The colour of the shade: near-black, so it darkens the face without tinting it a colour. */
    public static final int SHADE_RGB = 0x000000;

    private FaceShade() {
    }

    /**
     * {@return the shade's alpha for a cooldown fraction, in {@code [0, MAX_ALPHA]}}
     *
     * <p>Linear in the remaining fraction, which is linear in remaining time — so the shade lifts at a
     * constant rate, and the player can read the remaining cooldown off it without a number.
     *
     * <p>{@link Double#NaN} answers {@code 0} (nothing shaded): a garbage reading must not paint an
     * opaque disc over the face, the same "a NaN must not draw a full bar" rule {@code GaugeArt} uses.
     * Values outside {@code [0, 1]} are clamped rather than trusted.
     *
     * @param remainingFraction {@code 1.0} at the start of the cooldown, {@code 0.0} at its end
     */
    public static int alphaOf(double remainingFraction) {
        if (!(remainingFraction > 0.0)) {
            return 0;
        }
        double clamped = Math.min(1.0, remainingFraction);
        return (int) Math.round(MAX_ALPHA * clamped);
    }

    /**
     * {@return the ARGB wash to {@code fill} over the face, fully transparent when there is no
     *         cooldown to draw}
     *
     * @param remainingFraction {@code 1.0} at the start of the cooldown, {@code 0.0} at its end
     */
    public static int argbOf(double remainingFraction) {
        return (alphaOf(remainingFraction) << 24) | SHADE_RGB;
    }

    /**
     * {@return the alpha for one character's cooldown at {@code nowTick}}
     *
     * <p>The join between "whose cooldown" and the drawing: {@code UbCooldown} answers the fraction for
     * a deadline and this class answers the opacity for a fraction, so neither has to know about the
     * other. A character with no running cooldown has {@link UbCooldown#NONE}, which answers fraction
     * {@code 0.0}, which answers alpha {@code 0} — an unshaded face, which is what "she may cast" looks
     * like.
     *
     * @param cooldown that character's cooldown, never {@code null}
     * @param nowTick  the client's game time
     */
    public static int alphaFor(UbCooldown cooldown, long nowTick) {
        return alphaOf(cooldown.remainingFraction(nowTick));
    }
}
