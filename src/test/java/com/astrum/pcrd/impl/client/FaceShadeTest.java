package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.UbCooldown;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shade over the UB gauge's face: <b>full at the start of her cooldown, none when she is castable</b>
 * (「从阴影全覆盖，到没有阴影」), linear in between.
 *
 * <h2>Why this is asserted at the value level</h2>
 * The drawing cannot be seen from here (that is the human's part of the verification), but the three
 * claims that make the picture mean what he asked for are arithmetic and can be pinned exactly:
 *
 * <ul>
 *   <li><b>{@code 1.0} at the cast.</b> The face is completely shaded on the tick the server armed the
 *       deadline. Not "nearly" — full, because a shade that starts at 90% reads as a cooldown that has
 *       already been running.</li>
 *   <li><b>{@code 0.0} at expiry, and never negative.</b> The shade is gone on exactly the tick
 *       {@code CastGate} starts allowing the cast, so "no shadow" and "castable" are the same moment.
 *       Before expiry it is gone too: the shade must not come back.</li>
 *   <li><b>Monotone between.</b> Over the cooldown it only shrinks, so the picture can be read as time
 *       passing rather than flickering.</li>
 * </ul>
 *
 * <p>The last assertion here is the join rather than the arithmetic: the total the shade divides by is
 * that cooldown's own {@code totalTicks}, so two cooldowns of different lengths show different shades at
 * the same instant. The "which character's cooldown" join has its own test, {@link CooldownTrackerTest}.
 *
 * <p>The <b>total</b> is the cooldown's own {@code totalTicks}, not a constant: the test drives two
 * different lengths and expects the half-way alpha in the middle of each, which is the property that
 * would silently break if the denominator were {@code ServerConfig}'s current value.
 */
class FaceShadeTest {

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KYARU = InternalIds.id("kyaru");

    /** Start of the cooldown, and its length: the pair the server stamps on a cast. */
    private static final long START = 1_000L;
    private static final long TOTAL = 100L;

    private static UbCooldown running(long total) {
        return new UbCooldown(START + total, total);
    }

    @Test
    void theFaceIsFullyShadedAtTheCast() {
        assertEquals(FaceShade.MAX_ALPHA, FaceShade.alphaFor(running(TOTAL), START));
        assertEquals(FaceShade.MAX_ALPHA, FaceShade.alphaOf(1.0));
    }

    @Test
    void theShadeIsGoneOnTheTickSheBecomesCastable() {
        long end = START + TOTAL;
        assertEquals(0, FaceShade.alphaFor(running(TOTAL), end),
                "no shadow on the tick the gate starts accepting casts");
        assertEquals(0, FaceShade.alphaFor(running(TOTAL), end + 1L), "and none afterwards");
        assertEquals(0, FaceShade.alphaFor(running(TOTAL), end + 10_000L));
        assertEquals(0, FaceShade.alphaOf(0.0));
    }

    /**
     * Between start and expiry the shade only ever shrinks — the whole cooldown, tick by tick.
     *
     * <p>A sweep rather than three samples: a shape that dipped and recovered (an absolute value, a
     * modulo, a triangle wave) would pass a three-point test and look wrong on screen.
     */
    @Test
    void theShadeOnlyEverShrinksAcrossTheWholeCooldown() {
        int previous = FaceShade.MAX_ALPHA + 1;
        for (long elapsed = 0; elapsed <= TOTAL; elapsed++) {
            int alpha = FaceShade.alphaFor(running(TOTAL), START + elapsed);
            assertTrue(alpha <= previous,
                    "the shade grew back at elapsed=" + elapsed + ": " + previous + " -> " + alpha);
            assertTrue(alpha >= 0 && alpha <= FaceShade.MAX_ALPHA,
                    "alpha out of range at elapsed=" + elapsed + ": " + alpha);
            previous = alpha;
        }
        assertEquals(0, previous, "and the last step lands on none");
    }

    /** Half the cooldown left is half the shade, whatever the length: the total is the denominator. */
    @Test
    void halfWayThroughIsHalfTheShadeForAnyCooldownLength() {
        for (long total : new long[] {20L, 100L, 1_200L}) {
            assertEquals(FaceShade.MAX_ALPHA / 2, FaceShade.alphaFor(running(total), START + total / 2),
                    "the denominator must be this cooldown's own total (" + total + ")");
        }
    }

    /**
     * The total is the length <b>this</b> cooldown was armed with, so a config retune mid-cooldown
     * cannot make the shade jump.
     *
     * <p>Two cooldowns of different lengths starting on the same tick: at the same instant they show
     * different shades, which is only true if each carries its own total.
     */
    @Test
    void twoCooldownsOfDifferentLengthsShowDifferentShadesAtTheSameInstant() {
        UbCooldown shortOne = new UbCooldown(START + 40L, 40L);
        UbCooldown longOne = new UbCooldown(START + 400L, 400L);

        // 40 ticks in: the 40-tick cooldown is over (no shade), the 400-tick one has 360 left of 400 —
        // i.e. 90% of full shade. With one shared denominator both would read the same number.
        assertEquals(0, FaceShade.alphaFor(shortOne, START + 40L));
        assertEquals(FaceShade.alphaOf(0.9), FaceShade.alphaFor(longOne, START + 40L),
                "the same instant, a different fraction — because the totals differ");
        assertTrue(FaceShade.alphaFor(longOne, START + 40L) > FaceShade.alphaFor(shortOne, START + 40L));
    }

    /** A character with no cooldown at all is unshaded: {@link UbCooldown#NONE} answers fraction 0. */
    @Test
    void aCharacterWhoNeverCastIsUnshaded() {
        assertEquals(0, FaceShade.alphaFor(UbCooldown.NONE, START));
        assertEquals(0, FaceShade.alphaFor(UbCooldown.NONE, 0L));
    }

    /** A garbage reading must not paint an opaque disc over her face. */
    @Test
    void aNonFiniteFractionShadesNothing() {
        assertEquals(0, FaceShade.alphaOf(Double.NaN));
        assertEquals(0, FaceShade.alphaOf(-1.0), "clamped, not wrapped to a full disc");
        assertEquals(FaceShade.MAX_ALPHA, FaceShade.alphaOf(2.0));
    }

    /** The wash is ARGB with the shade's RGB, so it darkens the face without tinting it. */
    @Test
    void theWashCarriesTheAlphaInTheTopByte() {
        int full = FaceShade.argbOf(1.0);
        assertEquals(FaceShade.MAX_ALPHA, full >>> 24);
        assertEquals(FaceShade.SHADE_RGB, full & 0x00FFFFFF);
        assertEquals(0, FaceShade.argbOf(0.0), "nothing to draw is fully transparent");
    }

}
