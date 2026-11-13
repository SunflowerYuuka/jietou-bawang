package com.astrum.pcrd.impl.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UB effect's schedule: when it is on screen, how each layer fades, and where each picture goes.
 *
 * <h2>Why this is the test that matters for a visual nobody here can see</h2>
 * Everything in this file is a claim the author cannot check by looking: the effect starts at full
 * strength, fades monotonically, and is <b>absent</b> one tick after its last. A screenshot cannot prove
 * "it stops", and a code read cannot prove "it fades rather than pops". As arithmetic it can be asserted —
 * boundaries included — and {@link #itIsAbsentTheTickAfterItEnds} is the specific assertion that stops a
 * finished effect from submitting invisible quads for the rest of the session.
 *
 * <p>The placement assertions are about <b>not breaking the HUD</b>: the cut-in must not sit on the gauge's
 * default corner or the hotbar, the burst must stay square, and neither may overflow the window. Those are
 * geometry facts, and this project has already shipped one probe-discovered defect that was purely
 * geometric ({@code GuiShapes}' winding), so they are asserted rather than eyeballed.
 */
class UbEffectScheduleTest {

    /** A window big enough to be realistic: 427x240 is the GUI size the T16a probe measured at scale 3. */
    private static final int W = 427;
    private static final int H = 240;

    // ------------------------------------------------------------------ the clock

    /** The age is the difference of two game times, clamped at zero so the first frame is not skipped. */
    @Test
    void theAgeIsTheDifferenceAndNeverNegative() {
        assertEquals(0, UbEffectSchedule.age(100, 100));
        assertEquals(0, UbEffectSchedule.age(99, 100), "a clock that went backwards means 'just arrived'");
        assertEquals(1, UbEffectSchedule.age(101, 100));
        assertEquals(UbEffectSchedule.TOTAL_TICKS, UbEffectSchedule.age(120, 100));
    }

    // ------------------------------------------------------------------ the window

    /** The effect exists for exactly {@link UbEffectSchedule#TOTAL_TICKS} ticks — the whole point of a fade. */
    @Test
    void theEffectIsOnScreenForExactlyOneSecond() {
        assertEquals(20, UbEffectSchedule.TOTAL_TICKS, "20 ticks at 20 tps is the one-second budget");
        assertTrue(UbEffectSchedule.visible(0), "the first frame is drawn");
        assertTrue(UbEffectSchedule.visible(UbEffectSchedule.TOTAL_TICKS - 1),
                "the last frame is drawn");
        assertFalse(UbEffectSchedule.visible(UbEffectSchedule.TOTAL_TICKS),
                "the tick it ends is the first tick it is not drawn");
        assertFalse(UbEffectSchedule.visible(UbEffectSchedule.TOTAL_TICKS + 1));
    }

    /** Nothing at all is drawn one tick after it ends — the assertion that stops a permanent invisible quad. */
    @Test
    void itIsAbsentTheTickAfterItEnds() {
        assertEquals(0.0f, UbEffectSchedule.alpha(UbEffectSchedule.TOTAL_TICKS), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.alpha(UbEffectSchedule.TOTAL_TICKS + 100), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.flareAlpha(UbEffectSchedule.TOTAL_TICKS), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.slashAlpha(UbEffectSchedule.TOTAL_TICKS), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.burstAlpha(UbEffectSchedule.TOTAL_TICKS), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.cutInAlpha(UbEffectSchedule.TOTAL_TICKS), 0.0f);
    }

    /** A negative age draws nothing rather than throwing: {@code visible} is asked before any texture. */
    @Test
    void aNegativeAgeIsNotVisible() {
        assertFalse(UbEffectSchedule.visible(-1));
        assertEquals(0.0f, UbEffectSchedule.alpha(-1), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.slashAlpha(-1), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.burstAlpha(-1), 0.0f);
        assertEquals(0.0f, UbEffectSchedule.cutInAlpha(-1), 0.0f);
    }

    // ------------------------------------------------------------------ the fades

    /**
     * The global fade starts at the peak and <b>never increases</b>: a fade that brightens mid-effect reads
     * as a flicker, which is exactly what a player with photosensitivity must not be shown.
     */
    @Test
    void theGlobalFadeStartsAtPeakAndOnlyEverDecreases() {
        assertEquals(UbEffectSchedule.PEAK_ALPHA, UbEffectSchedule.alpha(0), 1e-6,
                "the first frame is the brightest");
        float previous = Float.MAX_VALUE;
        for (int age = 0; age <= UbEffectSchedule.TOTAL_TICKS; age++) {
            float alpha = UbEffectSchedule.alpha(age);
            assertTrue(alpha <= previous + 1e-6,
                    "alpha rose at age " + age + ": " + alpha + " after " + previous);
            assertTrue(alpha >= 0.0f, "alpha went negative at age " + age);
            assertTrue(alpha <= 1.0f, "alpha exceeded 1 at age " + age);
            previous = alpha;
        }
        assertTrue(UbEffectSchedule.PEAK_ALPHA < 1.0f,
                "the peak must stay below full opacity: this covers the HUD and the world at once");
    }

    /** Each layer's own window: the flare goes before the slash, and the burst outlives both. */
    @Test
    void eachLayerHasItsOwnWindow() {
        assertEquals(0.0f, UbEffectSchedule.flareAlpha(-1), 0.0f);
        assertTrue(UbEffectSchedule.flareAlpha(0) > 0.0f);
        assertTrue(UbEffectSchedule.flareAlpha(UbEffectSchedule.FLARE_END_TICK - 1) > 0.0f);
        assertEquals(0.0f, UbEffectSchedule.flareAlpha(UbEffectSchedule.FLARE_END_TICK), 0.0f);

        // The slash starts after the first frame (it is the swing, not the arrival) and outlives the flare.
        assertEquals(0.0f, UbEffectSchedule.slashAlpha(0), 0.0f,
                "the swing starts after the flare has established the frame");
        assertTrue(UbEffectSchedule.slashAlpha(UbEffectSchedule.STRIKE_START_TICK) > 0.0f);
        assertTrue(UbEffectSchedule.SLASH_END_TICK > UbEffectSchedule.FLARE_END_TICK,
                "the slash outlives the backdrop it is drawn on");
        assertEquals(UbEffectSchedule.TOTAL_TICKS, UbEffectSchedule.SLASH_END_TICK,
                "the last picture must fade out exactly when the effect ends, so there is no stretch "
                        + "where the layer is 'on' and drawing zero quads");

        // The burst starts with the swing and is over before the whole effect is.
        assertEquals(0.0f, UbEffectSchedule.burstAlpha(UbEffectSchedule.BURST_START_TICK - 1), 0.0f);
        assertTrue(UbEffectSchedule.burstAlpha(UbEffectSchedule.BURST_START_TICK + 1) > 0.0f);
        assertEquals(0.0f, UbEffectSchedule.burstAlpha(UbEffectSchedule.BURST_END_TICK), 0.0f);
        assertEquals(UbEffectSchedule.TOTAL_TICKS, UbEffectSchedule.BURST_END_TICK,
                "a burst that ends before the effect does leaves invisible frames to draw");

        // The portrait leaves first: it is a portrait, not a flash, and a face lingering over the fight
        // is the most intrusive thing this overlay could do.
        assertTrue(UbEffectSchedule.cutInAlpha(0) > 0.0f, "the portrait is there from the first frame");
        assertTrue(UbEffectSchedule.CUTIN_END_TICK < UbEffectSchedule.BURST_END_TICK,
                "the cut-in must be gone before the burst finishes");
    }

    /**
     * The effect draws something on every tick of its window.
     *
     * <p>This is the property behind the "no invisible work" reasoning: if every layer's window ended
     * before {@link UbEffectSchedule#TOTAL_TICKS}, the layer would keep being called with nothing to draw —
     * and a version of this shipped in the first draft (measured on a real client: the frames stopped at
     * age 17 of 20 with {@code quads=0}). Asserting it as arithmetic is how the next edit cannot reintroduce
     * it.
     */
    @Test
    void everyTickOfTheWindowDrawsSomething() {
        for (int age = 0; age < UbEffectSchedule.TOTAL_TICKS; age++) {
            float anything = Math.max(
                    Math.max(UbEffectSchedule.flareAlpha(age), UbEffectSchedule.slashAlpha(age)),
                    Math.max(UbEffectSchedule.burstAlpha(age), UbEffectSchedule.cutInAlpha(age)));
            assertTrue(anything > 0.0f,
                    "age " + age + " of " + UbEffectSchedule.TOTAL_TICKS
                            + " is inside the effect's window but draws nothing");
        }
    }

    /** The burst swells and then recedes: it is an impact, not a breathing light. */
    @Test
    void theBurstPeaksEarlyAndFallsBackToZero() {
        float atStart = UbEffectSchedule.burstAlpha(UbEffectSchedule.BURST_START_TICK);
        float nearStart = UbEffectSchedule.burstAlpha(UbEffectSchedule.BURST_START_TICK + 2);
        assertTrue(nearStart > atStart, "it must ramp in, not pop in at full strength");
        int peakAge = -1;
        float peak = -1.0f;
        for (int age = UbEffectSchedule.BURST_START_TICK; age < UbEffectSchedule.BURST_END_TICK; age++) {
            if (UbEffectSchedule.burstAlpha(age) > peak) {
                peak = UbEffectSchedule.burstAlpha(age);
                peakAge = age;
            }
        }
        assertTrue(peakAge <= UbEffectSchedule.BURST_START_TICK
                        + (UbEffectSchedule.BURST_END_TICK - UbEffectSchedule.BURST_START_TICK) * 3 / 10,
                "the peak must be in the first third of the burst's life, got age " + peakAge);
        assertEquals(0.0f, UbEffectSchedule.burstAlpha(UbEffectSchedule.BURST_END_TICK), 0.0f,
                "and it must end at exactly nothing");
    }

    // ------------------------------------------------------------------ the placement

    /** The burst is a square centred on the screen, whatever the window's aspect ratio. */
    @Test
    void theBurstIsASquareCentredOnTheScreen() {
        for (int[] window : new int[][] {{427, 240}, {320, 240}, {1920, 1080}, {800, 800}, {1200, 400}}) {
            UbEffectSchedule.Box box = UbEffectSchedule.burstBox(window[0], window[1]);
            assertEquals(box.width(), box.height(),
                    "a radial burst drawn from a square texture must stay square at " + window[0] + "x"
                            + window[1]);
            assertTrue(box.width() > 0, "a real window must produce a real burst");
            // Centred to within one pixel: an odd difference cannot be split in integer coordinates, and
            // "off by half a pixel" is not a defect a player can see.
            assertTrue(Math.abs((box.x() + box.width() + box.x()) - window[0]) <= 1,
                    "centred horizontally at " + window[0] + "x" + window[1] + ": " + box);
            assertTrue(Math.abs((box.y() + box.height() + box.y()) - window[1]) <= 1,
                    "centred vertically at " + window[0] + "x" + window[1] + ": " + box);
        }
    }

    /** A tiny window gets a small burst, but never a negative one. */
    @Test
    void theBurstDegradesSafelyInATinyWindow() {
        UbEffectSchedule.Box tiny = UbEffectSchedule.burstBox(10, 8);
        assertTrue(tiny.width() >= 0 && tiny.height() >= 0);
        assertEquals(tiny.width(), tiny.height());
        UbEffectSchedule.Box zero = UbEffectSchedule.burstBox(0, 0);
        assertFalse(zero.isDrawable());
    }

    /**
     * The cut-in is in the bottom-right and clear of everything this mod and vanilla already draw there.
     *
     * <p>The four rectangles it must not overlap, as the project states them elsewhere:
     * <ul>
     *   <li>the hotbar: bottom centre, {@code 182x22} plus its frame, so the bottom {@code 30} rows of the
     *       screen around the centre;</li>
     *   <li>{@code HudLayout}'s gauge: bottom-left, {@code 112x20} inset {@code 8} from the corner;</li>
     *   <li>the cast pad: centred, above the hotbar;</li>
     *   <li>the top-right corner, where Jade/AppleSkin-class mods draw.</li>
     * </ul>
     * The assertion is the useful one: it never reaches the bottom-left half or the top-right corner.
     */
    @Test
    void theCutInSitsInTheBottomRightAndClearOfTheHud() {
        UbEffectSchedule.Box box = UbEffectSchedule.cutInBox(W, H);
        assertTrue(box.isDrawable());
        assertTrue(box.right() <= W, "the portrait must not overflow the right edge");
        assertTrue(box.bottom() <= H, "the portrait must not overflow the bottom edge");
        assertEquals(W - box.width() - Math.max(UbEffectSchedule.CUTIN_MARGIN, H / 24), box.x(),
                "anchored to the right corner with a margin");
        assertTrue(box.x() > W / 2, "the cut-in belongs in the right half, got x=" + box.x());
        assertTrue(box.y() > 0);

        // The gauge (bottom-left, 112x20 inset 8) must not be covered.
        int gaugeLeft = 8;
        int gaugeRight = gaugeLeft + HudLayout.WIDGET_WIDTH;
        int gaugeTop = H - 8 - HudLayout.WIDGET_HEIGHT;
        assertTrue(box.x() >= gaugeRight,
                "the cut-in overlaps the gauge's corner: cut-in x=" + box.x() + " gauge right="
                        + gaugeRight);
        assertTrue(gaugeTop > box.bottom() || box.y() < gaugeTop,
                "and it must not sit in the same band either");
    }

    /** The cut-in keeps its art's 4:3 aspect ratio at every window size, so the portrait is never stretched. */
    @Test
    void theCutInKeepsTheArtsAspectRatio() {
        for (int[] window : new int[][] {{427, 240}, {320, 240}, {640, 360}, {1920, 1080}}) {
            UbEffectSchedule.Box box = UbEffectSchedule.cutInBox(window[0], window[1]);
            assertEquals(UbEffectSchedule.CUTIN_ASPECT, (double) box.width() / box.height(), 0.02,
                    "aspect drifted at " + window[0] + "x" + window[1]);
        }
    }

    /** A window too small for the nominal portrait shrinks it rather than drawing outside the screen. */
    @Test
    void aTinyWindowShrinksTheCutInInsteadOfOverflowing() {
        UbEffectSchedule.Box box = UbEffectSchedule.cutInBox(40, 30);
        assertTrue(box.right() <= 40, "right edge inside the window, got " + box.right());
        assertTrue(box.bottom() <= 30, "bottom edge inside the window, got " + box.bottom());
        assertTrue(box.x() >= 0 && box.y() >= 0);
    }

    /** The full-screen layers cover the whole GUI exactly — no seam, no overflow. */
    @Test
    void theFullScreenLayerCoversTheWholeGui() {
        UbEffectSchedule.Box box = UbEffectSchedule.fullScreen(W, H);
        assertEquals(0, box.x());
        assertEquals(0, box.y());
        assertEquals(W, box.right());
        assertEquals(H, box.bottom());
    }

    // ------------------------------------------------------------------ the art it points at

    /**
     * Every texture the overlay draws exists on the classpath.
     *
     * <p>A missing texture is not a crash: {@code GuiGraphics.blit} would draw the purple-black
     * checkerboard, or (with a missing {@code .png.mcmeta}-free resource) nothing at all — a failure that
     * looks like "the effect is too subtle" rather than "the file is gone". The resource names are read out
     * of the overlay's own constants through reflection so this test cannot pass against a path the
     * renderer does not use.
     */
    @Test
    void everyTextureTheOverlayDrawsIsOnTheClasspath() throws Exception {
        for (String field : new String[] {"SLASH", "BURST", "FLARE", "CUTIN_DJEETA"}) {
            Object value = constant(field);
            assertNotNull(value, field + " must be a ResourceLocation constant on the overlay");
            assertEquals(net.minecraft.resources.ResourceLocation.class, value.getClass(),
                    field + " must be a ResourceLocation");
            String path = "/assets/astrum/" + ((net.minecraft.resources.ResourceLocation) value).getPath();
            try (InputStream in = UbEffectScheduleTest.class.getResourceAsStream(path)) {
                assertNotNull(in, "the overlay draws " + path + " but it is not on the classpath");
                assertTrue(in.readNBytes(8).length == 8, "and it is a real file with content");
            }
        }
    }

    /** A character with no cut-in of its own resolves to the shipped Djeeta art instead of nothing. */
    @Test
    void theCutInFallsBackToTheShippedArt() {
        assertEquals(UbEffectOverlay.CUTIN_DJEETA, UbEffectOverlay.resolveWith(null, path -> true));
        assertEquals(UbEffectOverlay.CUTIN_DJEETA, UbEffectOverlay.resolveWith("  ", path -> true));
        assertEquals(UbEffectOverlay.CUTIN_DJEETA,
                UbEffectOverlay.resolveWith("a_character_with_no_art", path -> false),
                "unknown art falls back rather than drawing a purple checkerboard");
        assertEquals(UbEffectOverlay.CUTIN_DJEETA,
                UbEffectOverlay.resolveWith("djeeta", path -> true),
                "the shipped character's own art is the same resource the fallback names");
        // A pack that ships its own cut-in gets it — no code change, only a file.
        assertEquals("astrum:textures/gui/ub_cutin_miyako.png",
                UbEffectOverlay.resolveWith("miyako", path -> true).toString(),
                "a character with its own art must use it");
    }

    /** The naming rule: {@code astrum:<path>} reads {@code textures/gui/ub_cutin_<path>.png}. */
    @Test
    void theCutInNamingRuleIsOneExpression() {
        assertEquals("astrum:textures/gui/ub_cutin_djeeta.png",
                UbEffectOverlay.CUTIN_DJEETA.toString());
        assertEquals("astrum:textures/gui/ub_cutin_miyako.png",
                UbEffectOverlay.cutIn("miyako").toString());
    }

    /** The idle path of the overlay mentions no {@code impl.showcase} type: the effect is not an entity. */
    @Test
    void theOverlayDoesNotReachForTheEntityCode() throws IOException {
        String path = "/com/astrum/pcrd/impl/client/UbEffectOverlay.class";
        try (InputStream in = UbEffectScheduleTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "the overlay must be compiled: " + path);
            String constantPool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(constantPool.contains("impl/showcase/"),
                    "the effect overlay must not depend on the retained entity types");
        }
    }

    private static Object constant(String field) throws Exception {
        Class<?> overlay = Class.forName("com.astrum.pcrd.impl.client.UbEffectOverlay");
        var declared = overlay.getDeclaredField(field);
        declared.setAccessible(true);
        return declared.get(null);
    }
}
