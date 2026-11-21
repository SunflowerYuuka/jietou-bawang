package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The client-side answer to a missing art texture: draw the mod's own 「请安装资源包」 placeholder instead of the
 * purple-black checkerboard.
 *
 * <h2>Why the mod needs this, and what it can honestly do</h2>
 * The mod ships <b>zero</b> official art. A player who has not installed {@code astrum-assets.zip} gets
 * {@link ResourceLocation}s that resolve to nothing, and Minecraft's own response to a missing texture is the
 * missing-texture atlas sprite — magenta and black squares. That is not a neutral default: it is the same
 * picture a corrupt install shows, and the human has photographed it twice.
 *
 * <p>Minecraft offers <b>no</b> hook that substitutes a texture by its id. There are three candidate
 * mechanisms and the honest position on each is different, so this class states which is used where:
 *
 * <ol>
 *   <li><b>Check before drawing</b> — {@link #resolve} answers "the placeholder" when the requested texture is
 *       not in the {@link ResourceManager}, and the caller blits the answer instead. This works everywhere
 *       this mod draws something itself: the book's roster cells, the party slots, the panel's still, the
 *       skill rows, the wheel's faces, the HUD gauge, the party row, the inventory effect icon. <b>This is the
 *       mechanism.</b></li>
 *   <li><b>Register a fallback atlas sprite</b> — vanilla has no per-id fallback; {@code SpriteLoader}'s
 *       missing sprite is global and internal. Substituting it would repaint <em>every</em> missing
 *       texture in the game, including other mods'. Not used, and not attempted.</li>
 *   <li><b>Ship the placeholder under the art paths themselves</b> — i.e. commit a 64x64 placeholder at
 *       {@code textures/gui/portrait/djeeta.png}. Tempting, and wrong: a player who <em>has</em> the art pack
 *       would then have the pack's file win by pack order for every path, which is fine, but the repository
 *       would contain files at art paths — the one thing ``.gitignore`` exists to prevent. Not used.</li>
 * </ol>
 *
 * <h2>Two surfaces where the placeholder is the wrong <em>answer</em>, and what they do instead</h2>
 * "The placeholder instead of the checkerboard" is a rule about <em>pictures</em>, and two of the audited
 * surfaces do not draw a picture: they sample one narrow part of a texture whose geometry is part of a
 * widget. Blitting a 128x128 sheet of text into those was worse than the checkerboard, so they ask this
 * class only the <b>existence</b> question ({@link #isInstalled}) and degrade to a flat shape in the
 * mod's own ink:
 * <ul>
 *   <li><b>The book panel's nine-slice frame</b> ({@code textures/gui/book_panel.png}) — nine cells of one
 *       sheet. Nine stretched fragments of the placeholder's text is noise, not an instruction; the panel
 *       instead draws a one-pixel stroke of the frame's own gold. See {@code OfficialPanel}.</li>
 *   <li><b>The UB gauge's two layers</b> ({@code textures/gui/ub_gauge_track.png}, {@code _fill.png}) — a
 *       256x24 capsule strip sampled by fraction. The gauge instead stays a flat channel plus a flat gold
 *       fill, which is exactly what the bar was before it gained official art. See {@code UbHud}.</li>
 * </ul>
 * Both remain <b>one</b> existence rule: this class. A surface never asks the resource manager itself.
 *
 * <h2>What cannot be intercepted, and why</h2>
 * <ul>
 *   <li><b>Vanilla's effect HUD sprite</b> ({@code textures/mob_effect/<id>.png}). It is sampled by
 *       {@code MobEffectTextureManager} from the {@code mob_effects} <em>atlas</em>, whose sprite id is the
 *       effect's registry id. The mod never blits it, so there is no call site to redirect, and an atlas
 *       sprite cannot be replaced per-id without repainting every mod's missing effects. So each registered
 *       effect <b>ships a file</b>: the generic effects and the 91 skill types wear the mod's own self-drawn
 *       64x64 placeholder (which is why JEI never shows the checkerboard for them), while the two portrait
 *       markers {@code counter} / {@code spirit} are derived official art and therefore pack-only — a player
 *       without the pack does see the checkerboard on those two rows, which the human accepted. That is a limit
 *       of the mechanism rather than something this class can fix. The surfaces whose icons the mod
 *       <em>does</em> draw are covered — see {@code PartyEffectClient}, {@code SkillEntryClient} and
 *       {@code PartyStatusHud}.</li>
 *   <li><b>An item's own model texture</b> ({@code textures/item/character_card.png},
 *       {@code textures/item/memory_token.png}, and the {@code textures/gui/portrait/*} the per-character
 *       {@code card_<id>.json} models point at). It is resolved by the item model pipeline from the model
 *       JSON, not by a call this mod makes, so a checked card shows the checkerboard without the pack.
 *       <b>The human accepted this</b> (「就紫黑格」) and it is deliberately not "fixed". Note that
 *       {@code item/character_book.png} and {@code item/dish_rice_bowl.png} <em>are</em> committed, so those
 *       two item icons are unaffected.</li>
 *   <li><b>Particle atlases</b> ({@code textures/particle/ub_mote.png}, {@code ub_spark.png}). A
 *       {@code TextureSheetParticle} gets a {@code SpriteSet} built from {@code assets/astrum/particles/*.json}
 *       at spawn time; the mod never blits it. Same atlas limit as the effect sprites: not interceptable.
 *       Unlike them, a missing particle texture is only visible during the UB animation.</li>
 *   <li><b>The GeckoLib showcase entity's skin</b> ({@code textures/entity/djeeta.png}) — committed, so it
 *       cannot be missing; listed here only because it is a texture the mod asks for and does not draw
 *       itself.</li>
 * </ul>
 *
 * <h2>Why one cached answer per id, and why the cache is cleared on reload</h2>
 * {@link #resolve} is called per roster cell per frame, and {@link ResourceManager#getResource} is a map
 * lookup followed by a pack walk — cheap, but not free at 60 fps times a full grid. So <b>both</b> verdicts
 * are remembered, in two sets: a cached hit costs one set lookup and never touches the resource manager, and
 * "present" is asked once per texture rather than once per texture per frame.
 *
 * <p>More importantly, the answer <b>changes</b>: a player can install the pack and press F3+T, which
 * reloads resources. So both sets are dropped by {@code ArtFallbackReloadListener} (registered from
 * {@code ClientEvents}), and every texture is re-asked exactly once after the reload. Dropping only the
 * missing verdicts would leave a pack that was <em>removed</em> still answering "present" — which is the
 * same checkerboard by a slower route.
 *
 * <p>{@code null} is never cached and never means "missing": "this character has no art at all" is a
 * different fact from "the art is not installed", and callers already branch on it (a glyph disc, a rune).
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class ArtFallback {

    /**
     * The one placeholder picture, drawn by {@code tools/gen_placeholder.py}.
     *
     * <p>Committed (it is the mod's own drawing), {@link #PLACEHOLDER_SIDE} pixels square, and it says
     * 「请安装」/「资源包」 with the pack's file name under a rule. It is also inside {@code astrum-assets.zip},
     * so the picture a player sees is the same whether or not the pack is installed.
     */
    public static final ResourceLocation PLACEHOLDER =
            ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/placeholder.png");

    /**
     * Edge of {@link #PLACEHOLDER}, in pixels — the same number as {@code tools/gen_placeholder.py}'s
     * {@code SIZE}, and asserted against the committed file by {@code ArtFallbackTest}.
     *
     * <p>Published because two callers cannot treat the placeholder as "some texture that exists":
     * {@code OfficialPanel} fits it into the panel body, and a caller that assumed the <em>still</em>'s
     * 512x288 would sample the wrong region of it. Everything that draws it as a square icon blits the whole
     * image through the {@code uWidth == textureWidth} form and needs no size at all.
     */
    public static final int PLACEHOLDER_SIDE = 128;

    /**
     * Textures already found <b>missing</b>, so a grid does not re-walk the pack stack every frame.
     *
     * <p>Concurrent because a render pass can read it while the reload listener's {@code apply} clears it;
     * the cleared-then-asked-again window is harmless (one extra pack walk), a torn set would not be.
     */
    private static final Set<ResourceLocation> ABSENT = ConcurrentHashMap.newKeySet();

    /**
     * Textures already found <b>installed</b>.
     *
     * <p>The mirror of {@link #ABSENT}, and the reason {@code resolve} is genuinely cheap on the hit path:
     * a present texture is looked up once per reload rather than once per frame per roster cell.
     */
    private static final Set<ResourceLocation> PRESENT = ConcurrentHashMap.newKeySet();

    private ArtFallback() {
    }

    /**
     * {@return the texture to draw for {@code wanted}, or {@link #PLACEHOLDER} when it is not installed}
     *
     * <p>Never throws: this is on a render path, and a resource-manager hiccup must not take the screen down.
     * A {@code null} id answers {@code null} — "this character has no art at all" is a different fact from
     * "the art is missing", and callers already branch on it (a glyph disc, a rune).
     *
     * @param wanted the texture the definition names, possibly {@code null} or empty
     */
    public static @Nullable ResourceLocation resolve(@Nullable ResourceLocation wanted) {
        return resolve(wanted, null);
    }

    /**
     * The whole algorithm, with the existence answer supplied by {@code installed} when it is not
     * {@code null}.
     *
     * <p>The seam exists for {@code ArtFallbackTest}: the real answer needs a client and a resource manager,
     * and "a pack was installed, then removed" is a fact a test can only express as a predicate that changes
     * its mind. Production passes {@code null} — a predicate per frame would be an allocation per frame —
     * and the lookup goes to {@link #inResourceManager}.
     *
     * @param wanted    the texture the definition names, possibly {@code null}
     * @param installed the test's existence answer, or {@code null} to ask the client's resource manager
     */
    static @Nullable ResourceLocation resolve(@Nullable ResourceLocation wanted,
            @Nullable Predicate<ResourceLocation> installed) {
        if (wanted == null) {
            return null;
        }
        if (PLACEHOLDER.equals(wanted)) {
            return PLACEHOLDER;
        }
        // Cache first: this runs per roster cell per frame, so a remembered verdict must not cost a lookup.
        if (ABSENT.contains(wanted)) {
            return PLACEHOLDER;
        }
        if (PRESENT.contains(wanted)) {
            return wanted;
        }
        Boolean present;
        try {
            present = installed == null ? inResourceManager(wanted) : Boolean.valueOf(installed.test(wanted));
        } catch (RuntimeException e) {
            // A resource manager that is mid-reload can throw. Answer the wanted id and let the frame draw
            // whatever it draws; the next frame will ask again, and nothing is remembered from a failure.
            Astrum.LOGGER.debug("astrum: could not resolve {}; drawing it as-is for this frame", wanted, e);
            return wanted;
        }
        if (present == null) {
            return wanted;   // no client yet: the caller's own blit will fail the way it always did
        }
        if (present) {
            PRESENT.add(wanted);
            return wanted;
        }
        if (ABSENT.add(wanted)) {
            // INFO once per texture, not per frame: this is the line that tells a player *why* the book is
            // full of placeholder cells, and it names the file they need. It says "the surface that draws it"
            // rather than "the placeholder is drawn", because two of the audited surfaces (the panel frame and
            // the gauge strip) degrade to a flat shape — a picture cannot go where a nine-slice cell or a
            // 10-pixel capsule belongs. Which surface did what is logged by that surface, once.
            Astrum.LOGGER.info("astrum: texture {} is not installed, so the surface that draws it shows the "
                    + "「请安装资源包」 placeholder — or a flat stand-in where a picture cannot go. Install "
                    + "astrum-assets.zip into resourcepacks/ to get the official art.", wanted);
        }
        return PLACEHOLDER;
    }

    /**
     * {@return whether {@code wanted} is installed, i.e. whether drawing it would show real art}
     *
     * <p>{@code null} is not installed ("there is no art to install"), and neither is {@link #PLACEHOLDER}
     * ("this is the stand-in") — asking about the placeholder is asking whether the art behind it exists,
     * and by construction it does not.
     *
     * @param wanted the texture the definition names, possibly {@code null}
     */
    public static boolean isInstalled(@Nullable ResourceLocation wanted) {
        return isInstalled(wanted, null);
    }

    /** {@link #isInstalled} with the test seam of {@link #resolve(ResourceLocation, Predicate)}. */
    static boolean isInstalled(@Nullable ResourceLocation wanted,
            @Nullable Predicate<ResourceLocation> installed) {
        // One call, not two: `resolve(wanted) != null && !PLACEHOLDER.equals(resolve(wanted))` asked the
        // resource manager twice per terminal frame for every cell.
        ResourceLocation resolved = resolve(wanted, installed);
        return resolved != null && !PLACEHOLDER.equals(resolved);
    }

    /**
     * Forgets every cached verdict. Called from the client's resource-reload hook, so a player who installs
     * the pack and presses F3+T sees the art without restarting.
     */
    public static void invalidate() {
        int missing = ABSENT.size();
        int installed = PRESENT.size();
        if (missing > 0 || installed > 0) {
            Astrum.LOGGER.info("astrum: resources reloaded; forgetting {} missing-texture verdict(s) and {} "
                    + "installed-texture verdict(s) so an art pack added or removed is picked up",
                    missing, installed);
        }
        ABSENT.clear();
        PRESENT.clear();
    }

    /**
     * {@return whether the client's resource manager carries {@code wanted}, or {@code null} when no client
     * exists yet}
     *
     * <p>{@code null} rather than {@code false}: "the manager says no" means draw the placeholder, while
     * "there is no manager" means this is not a frame that can answer at all.
     */
    private static @Nullable Boolean inResourceManager(ResourceLocation wanted) {
        ResourceManager resources = resources();
        return resources == null ? null : Boolean.valueOf(resources.getResource(wanted).isPresent());
    }

    /** {@return the client's resource manager, or {@code null} before a client exists} */
    private static @Nullable ResourceManager resources() {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            return minecraft == null ? null : minecraft.getResourceManager();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@return every texture currently known to be missing, for the diagnostic line and for a test} */
    public static Set<ResourceLocation> missing() {
        return Set.copyOf(ABSENT);
    }

    /** {@return every texture currently known to be installed, for the diagnostic line and for a test} */
    public static Set<ResourceLocation> installed() {
        return Set.copyOf(PRESENT);
    }
}
