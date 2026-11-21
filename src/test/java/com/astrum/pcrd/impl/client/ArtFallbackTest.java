package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.EffectType;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.profiling.InactiveProfiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 「请安装资源包」 fallback: <b>one</b> answer to "does this texture exist", cached, and re-asked after a
 * resource reload.
 *
 * <h2>What this class can and cannot prove</h2>
 * {@link ArtFallback#resolve} in production asks {@code Minecraft.getInstance().getResourceManager()}, which
 * does not exist in a unit-test JVM. So the whole algorithm lives in the package-private
 * {@code resolve(wanted, Predicate)} overload and these tests supply the existence answer themselves. What
 * that buys is the three things a screenshot could never show:
 *
 * <ul>
 *   <li><b>the verdict is remembered</b> — a 60-frame grid asks the predicate <em>once</em> per texture,
 *       which is the difference between "cheap enough to call per cell per frame" and a pack walk 60 times
 *       a second;</li>
 *   <li><b>the verdict is forgotten on reload</b> — a test can flip the predicate from "absent" to
 *       "installed" in the middle, which is exactly what installing {@code astrum-assets.zip} and pressing
 *       F3+T does, and see that the cache keeps the stale answer until {@link ArtFallback#invalidate()}
 *       runs. Without that second half the first half would be a cache nobody can clear;</li>
 *   <li><b>the reload listener is the thing that clears it</b> — {@link ArtFallbackReloadListener#apply} is
 *       called directly, so the wiring "NeoForge reload → listener → cache" is asserted at its one
 *       client-side joint rather than assumed from reading {@code ClientEvents}.</li>
 * </ul>
 *
 * <h2>What it cannot prove, stated plainly</h2>
 * That a picture reached the screen. {@code blit} needs a live {@code GuiGraphics}; the size audit in
 * {@code task-41-report.md} and the call sites themselves are the evidence for "which rect the placeholder
 * is drawn into", and only a human running the client can confirm any of it looks right.
 */
class ArtFallbackTest {

    static {
        // ImageIO otherwise stages the stream through a temp file, and this sandbox denies the write:
        // "Can't create cache file!" is the failure that teaches you this, once. Same idiom as GaugeArtTest.
        ImageIO.setUseCache(false);
    }

    /** A pack-only portrait: declared by {@code .gitignore}, so absent from a checkout without the pack. */
    private static final ResourceLocation PORTRAIT =
            ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/portrait/djeeta.png");

    /** A pack-only still, from {@code data/astrum/astrum/character/djeeta.json}. */
    private static final ResourceLocation STILL =
            ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/still/djeeta_3.png");

    @BeforeEach
    @AfterEach
    void forgetEveryVerdict() {
        // The cache is static, so one test's "this texture is missing" would be the next test's answer.
        ArtFallback.invalidate();
    }

    // ------------------------------------------------------------------ the picture

    /**
     * The placeholder is <b>committed</b> (it is the mod's own drawing, not official art) and is the size the
     * callers assume.
     *
     * <p>{@link ArtFallback#PLACEHOLDER_SIDE} is published because {@code OfficialPanel} letterboxes it into
     * the panel body; a re-render of the PNG at another size would silently break that fit, so the number is
     * asserted against the file rather than trusted.
     */
    @Test
    void thePlaceholderIsTheCommittedFileAtTheDeclaredSize() throws Exception {
        BufferedImage image;
        try (InputStream in = ArtFallbackTest.class.getResourceAsStream("/assets/astrum/"
                + texturePathOf(ArtFallback.PLACEHOLDER))) {
            assertNotNull(in, "the placeholder must be on the classpath: it is committed, not pack-only — "
                    + "a player without astrum-assets.zip has nothing else to see");
            image = ImageIO.read(in);
        }
        assertEquals(ArtFallback.PLACEHOLDER_SIDE, image.getWidth(), "the declared width");
        assertEquals(ArtFallback.PLACEHOLDER_SIDE, image.getHeight(), "the declared height");
    }

    // ------------------------------------------------------------------ the answer

    /** A texture the pack does not provide answers the placeholder, and is remembered as missing. */
    @Test
    void aMissingTextureAnswersThePlaceholder() {
        assertSame(ArtFallback.PLACEHOLDER, ArtFallback.resolve(PORTRAIT, id -> false),
                "a missing art texture must draw the placeholder, not the checkerboard");
        assertTrue(ArtFallback.missing().contains(PORTRAIT), "the verdict is remembered");
        assertTrue(ArtFallback.installed().isEmpty(), "and it is remembered as missing, not as present");
    }

    /** A texture the pack does provide answers itself — the common case, unchanged. */
    @Test
    void anInstalledTextureAnswersItself() {
        assertSame(PORTRAIT, ArtFallback.resolve(PORTRAIT, id -> true),
                "with the pack installed the real art is drawn");
        assertTrue(ArtFallback.installed().contains(PORTRAIT), "the verdict is remembered");
        assertTrue(ArtFallback.missing().isEmpty(), "and it is not remembered as missing");
    }

    /**
     * The cache is the reason {@code resolve} is allowed on a per-cell-per-frame path: one lookup per texture,
     * not one per frame.
     */
    @Test
    void theVerdictIsRememberedSoAFrameCostsOneLookup() {
        AtomicInteger probes = new AtomicInteger();
        for (int frame = 0; frame < 60; frame++) {
            ArtFallback.resolve(PORTRAIT, id -> {
                probes.incrementAndGet();
                return false;
            });
        }
        assertEquals(1, probes.get(), "60 frames of a missing texture must ask once, not 60 times");

        ArtFallback.invalidate();
        probes.set(0);
        for (int frame = 0; frame < 60; frame++) {
            ArtFallback.resolve(PORTRAIT, id -> {
                probes.incrementAndGet();
                return true;
            });
        }
        assertEquals(1, probes.get(),
                "and an installed texture must be asked once too — 'present is the common case' is the argument "
                        + "for caching the miss, not for walking the packs every frame");
    }

    /**
     * <b>The flip.</b> Installing the pack does not change the answer until a reload clears the cache.
     *
     * <p>This is the test the cache exists to survive: the stale answer is <em>correct</em> for the frame it
     * was computed on and wrong the instant the player drags the zip into {@code resourcepacks/}. So the
     * stale answer is asserted first (otherwise the invalidation would not be needed), then the reload is
     * simulated and the new answer asserted.
     */
    @Test
    void theAnswerOnlyChangesAfterAReload() {
        AtomicBoolean installed = new AtomicBoolean(false);
        assertSame(ArtFallback.PLACEHOLDER, ArtFallback.resolve(PORTRAIT, id -> installed.get()));

        installed.set(true);
        assertSame(ArtFallback.PLACEHOLDER, ArtFallback.resolve(PORTRAIT, id -> installed.get()),
                "the cached miss survives the pack being installed — this is why the reload hook exists");

        ArtFallback.invalidate();
        assertSame(PORTRAIT, ArtFallback.resolve(PORTRAIT, id -> installed.get()),
                "after a reload the same texture resolves to the real art without restarting the game");
    }

    /**
     * The client-side reload hook really is what drops the cache.
     *
     * <p>{@code apply} is called directly — same package — so this asserts the listener's body and not a
     * comment in {@code ClientEvents}. It takes a resource manager it does not use, which is asserted too:
     * an invalidation that started <em>reading</em> resources would be a second existence rule.
     */
    @Test
    void theReloadListenerIsWhatClearsTheCache() {
        assertSame(ArtFallback.PLACEHOLDER, ArtFallback.resolve(PORTRAIT, id -> false));
        assertTrue(ArtFallback.missing().contains(PORTRAIT), "the miss is cached before the reload");

        ArtFallbackReloadListener.INSTANCE.apply(null, null, InactiveProfiler.INSTANCE);

        assertTrue(ArtFallback.missing().isEmpty(), "the reload listener cleared the missing verdicts");
        assertTrue(ArtFallback.installed().isEmpty(), "and the installed ones, so a removed pack is re-asked");
        assertSame(PORTRAIT, ArtFallback.resolve(PORTRAIT, id -> true),
                "so the next frame after the reload answers the real texture");
    }

    /**
     * {@code null} and the placeholder itself are answered without asking anything.
     *
     * <p>{@code null} means "this character has no art at all", which is a different fact from "the art is
     * not installed": callers branch on it to draw a glyph disc or a rune, and turning it into the
     * placeholder would replace an honest absence with a broken-looking cell.
     */
    @Test
    void nullAndThePlaceholderItselfAreAnsweredWithoutAsking() {
        AtomicInteger probes = new AtomicInteger();
        assertNull(ArtFallback.resolve(null, id -> {
            probes.incrementAndGet();
            return false;
        }), "null stays null");
        assertSame(ArtFallback.PLACEHOLDER, ArtFallback.resolve(ArtFallback.PLACEHOLDER, id -> {
            probes.incrementAndGet();
            return false;
        }), "the placeholder answers itself, so a caller that resolved twice cannot ask for a placeholder of "
                + "a placeholder");
        assertEquals(0, probes.get(), "neither case needs a resource lookup");
        assertFalse(ArtFallback.isInstalled(null), "null is not installed — there is no art to install");
        assertFalse(ArtFallback.isInstalled(ArtFallback.PLACEHOLDER),
                "and neither is the placeholder: asking about it is asking about the art behind it");
    }

    /**
     * A resource manager that throws mid-reload must not take the frame down, and must not poison the cache.
     *
     * <p>Nothing is remembered from a failure, so the next frame asks again — the difference between a
     * one-frame cosmetic glitch and a texture that stays wrong until the player restarts.
     */
    @Test
    void aProbeThatThrowsAnswersTheWantedIdAndRemembersNothing() {
        assertSame(PORTRAIT, ArtFallback.resolve(PORTRAIT, id -> {
            throw new IllegalStateException("resource manager mid-reload");
        }), "a hiccup answers the wanted id and lets the caller draw whatever it draws");
        assertTrue(ArtFallback.missing().isEmpty(), "a failure is not a verdict");
        assertTrue(ArtFallback.installed().isEmpty(), "in either direction");
    }

    // ------------------------------------------------------------------ the audit

    /**
     * <b>Every audited surface asks this one question.</b>
     *
     * <p>The table is surface → texture, taken from the {@code blit} call sites in this package rather than
     * from memory. Each row asserts the same two facts the drawing code relies on: absent → the placeholder
     * (or, for the two surfaces that degrade to a flat shape, "not installed"), present → the real id. A
     * surface added to the client without joining this table is a surface nobody checked.
     */
    @Test
    void everyAuditedSurfaceAsksThisOneAnswer() {
        Map<String, ResourceLocation> surfaces = auditedSurfaces();
        for (Map.Entry<String, ResourceLocation> surface : surfaces.entrySet()) {
            ArtFallback.invalidate();
            ResourceLocation id = surface.getValue();
            assertSame(ArtFallback.PLACEHOLDER, ArtFallback.resolve(id, wanted -> false),
                    surface.getKey() + " must fall back to the placeholder when its art is absent");
            assertFalse(ArtFallback.isInstalled(id, wanted -> false),
                    surface.getKey() + " must report 'not installed', or a degrade-to-flat surface would blit a "
                            + "missing texture");

            ArtFallback.invalidate();
            assertSame(id, ArtFallback.resolve(id, wanted -> true),
                    surface.getKey() + " must resolve to the real art when the pack is installed");
            assertTrue(ArtFallback.isInstalled(id, wanted -> true),
                    surface.getKey() + " must report 'installed', or the real art would never be drawn");
        }
    }

    /**
     * <b>The audit's factual basis:</b> every texture in the table is declared by {@code .gitignore} as
     * pack-only art, or it is the committed placeholder.
     *
     * <p>That is what makes the fallback <em>reachable</em>: a file committed to the repository cannot be
     * missing, so a surface that blits one needs no fallback. The check reads {@code .gitignore} itself —
     * the file {@code tools/build_asset_pack.py} also treats as the one declaration of "this path is derived
     * official art" — so a texture that moves from pack-only to committed (or a new surface whose art is
     * committed and therefore needs nothing) is visible here rather than discovered in a screenshot.
     */
    @Test
    void everyAuditedSurfaceDrawsArtTheRepositoryDoesNotShip() throws Exception {
        Path gitignore = TestProjectRoot.resolve(".gitignore");
        assertNotNull(gitignore, "the project root could not be located, so the art declarations cannot be "
                + "read — that is an environment failure, not a passing test");
        List<String> declared = Files.readAllLines(gitignore).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("/src/main/resources/") && !line.startsWith("#"))
                .toList();

        for (Map.Entry<String, ResourceLocation> surface : auditedSurfaces().entrySet()) {
            String path = texturePathOf(surface.getValue());
            if (path.equals(texturePathOf(ArtFallback.PLACEHOLDER))) {
                continue;   // the mod's own drawing, deliberately committed
            }
            assertTrue(isDeclaredPackOnly(declared, "/src/main/resources/assets/astrum/" + path),
                    surface.getKey() + " draws " + path + ", which nothing declares as pack-only art. Either "
                            + "it is committed (and then this surface needs no fallback at all) or the .gitignore "
                            + "art block is incomplete and the pack would ship without it.");
        }
    }


    /**
     * {@return whether any {@code .gitignore} art line covers {@code fullPath}}
     *
     * <p>Line-by-line {@code contains} was enough while every art file was listed individually. It stopped
     * being enough when the per-character art became directory and glob patterns (one line for all 24
     * portraits instead of 24 lines), and this test — which exists to catch an art file that would ship in the
     * jar — started failing on a rule that was already correct. So the declarations are matched as the globs
     * they are: {@code **} matches anything, {@code *} matches inside one path segment.
     */
    private static boolean isDeclaredPackOnly(List<String> declared, String fullPath) {
        for (String pattern : declared) {
            // The declarations are globs now (one line for all portraits, one for all card icons), so match
            // them as globs. The marker is a literal character no path contains; using it keeps the two
            // star forms apart without a regex escape sequence in a string literal.
            String regex = pattern
                    .replace(".", "[.]")
                    .replace("**", "§§")
                    .replace("*", "[^/]*")
                    .replace("§§", ".*");
            if (fullPath.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return surface → the texture that surface blits}
     *
     * <p>The labels are the surfaces, not the classes: two of the rows are the same portrait drawn by three
     * different widgets, and the report's table lists them separately because a player sees them separately.
     */
    private static Map<String, ResourceLocation> auditedSurfaces() {
        Map<String, ResourceLocation> surfaces = new LinkedHashMap<>();
        // Already wired before this task: the roster cell, party slot, wheel face and cast pad all blit the
        // character's tier avatar through WheelIconDraw.drawSquare.
        surfaces.put("character book roster / party slot / wheel / cast pad", PORTRAIT);
        // The two that were wired before but only for the face: the party HUD row and the inventory icon.
        surfaces.put("UB gauge preset face", PORTRAIT);
        surfaces.put("party status row face", PORTRAIT);
        // The book's skill rows.
        // The panel body.
        surfaces.put("character book still (panel body)", STILL);
        // Closed by this task.
        surfaces.put("character book panel frame", OfficialPanel.FRAME);
        surfaces.put("UB gauge track", GaugeArt.TRACK);
        surfaces.put("UB gauge fill", GaugeArt.FILL);
        return surfaces;
    }

    /** {@return the {@code textures/...} path of {@code id}, which is what {@code .gitignore} lists} */
    private static String texturePathOf(ResourceLocation id) {
        return id.getPath();
    }

    /**
     * The two surfaces that must <b>not</b> blit the placeholder, asserted as a decision rather than a picture.
     *
     * <p>A nine-slice cell and a 256x24 capsule strip are not places a 128x128 sheet of text can go, so those
     * two ask {@link ArtFallback#isInstalled} and degrade to a flat shape. This test pins the contract those
     * two call sites rely on — "not installed" is answerable without a placeholder image — and is the reason
     * {@code isInstalled} exists as a public method rather than each surface re-deriving it.
     *
     * <p>The {@code invalidate()} calls between the two halves are load-bearing: without them the second half
     * would be answered from the first half's cache. That is the cache's whole behaviour, so it is asserted
     * here in passing rather than left implicit.
     */
    @Test
    void theTwoFlatDegradationsAskIsInstalledNotResolve() {
        assertFalse(ArtFallback.isInstalled(OfficialPanel.FRAME, id -> false));
        assertFalse(ArtFallback.isInstalled(GaugeArt.TRACK, id -> false));
        assertFalse(ArtFallback.isInstalled(GaugeArt.FILL, id -> false));

        ArtFallback.invalidate();
        assertTrue(ArtFallback.isInstalled(OfficialPanel.FRAME, id -> true));
        assertTrue(ArtFallback.isInstalled(GaugeArt.TRACK, id -> true));
        assertTrue(ArtFallback.isInstalled(GaugeArt.FILL, id -> true));
        // The degenerate inputs a render path can actually reach.
        assertFalse(ArtFallback.isInstalled(null, id -> true));
        assertFalse(ArtFallback.isInstalled(ArtFallback.PLACEHOLDER, id -> true));
    }

    /** {@code invalidate} is safe to call when there is nothing cached, which is the common case. */
    @Test
    void invalidatingAnEmptyCacheIsHarmless() {
        ArtFallback.invalidate();
        ArtFallback.invalidate();
        assertTrue(ArtFallback.missing().isEmpty());
        assertTrue(ArtFallback.installed().isEmpty());
        // And it is not a state that makes a later answer throw.
        assertSame(PORTRAIT, ArtFallback.resolve(PORTRAIT, id -> true));
    }

    /**
     * A probe that throws leaves {@code isInstalled} answering what it answers to a frame that cannot ask:
     * the texture is drawn as-is (<em>not</em> replaced), and nothing is remembered.
     *
     * <p>Answering {@code true} here is deliberate and is the same choice {@code resolve} makes — "the
     * resource manager hiccuped" must not silently repaint a texture the player may well have. The
     * consequence for a degrade-to-flat surface is that one frame draws its art and the next frame asks
     * again; both are better than a permanent verdict derived from a failure.
     */
    @Test
    void aThrowingProbeDoesNotThrowOutOfIsInstalled() {
        assertTrue(ArtFallback.isInstalled(PORTRAIT, id -> {
            throw new IllegalStateException("mid-reload");
        }), "isInstalled must answer rather than propagate: it is called per frame");
        assertTrue(ArtFallback.missing().isEmpty(), "and must not remember the failure as a miss");
        assertTrue(ArtFallback.installed().isEmpty(), "nor as a hit");
    }
}
