package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.config.ClientConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * The UB effect, drawn as a <b>non-modal screen-space overlay</b>: a GUI layer like {@link WheelOverlay},
 * not a world-space billboard and not a {@code Screen}.
 *
 * <h2>Why GUI space, and what it costs</h2>
 * This is the cheap, reliable path and it was chosen <b>because the risky one cannot be verified here</b>.
 * The alternatives and why each was rejected:
 *
 * <ul>
 *   <li><b>A world-space billboard in the entity's place</b> — a {@code GeoRenderLayer} or a custom
 *       {@code RenderType} with additive blending. This needs its own quad builder, its own blend state,
 *       its own camera basis, and its own depth interaction with the terrain. Every one of those can fail
 *       <em>invisibly</em>: additive blending with a black background renders as nothing at all, a
 *       mis-declared {@code RenderType} can cover the whole screen with a black box, and a wrong depth test
 *       can put the effect behind the ground. <b>None of it can be checked without a human's eyes</b>, and
 *       the failure mode is "the screen is black", which is worse than "the effect is subtle".</li>
 *   <li><b>A particle</b> — a registered {@code ParticleType} plus a client provider, and the art here is a
 *       512x512 figure rather than a sprite; it would be sampled down to a few pixels.</li>
 *   <li><b>A texture on a model bone</b> — would throw away the art and require re-authoring the skin.</li>
 * </ul>
 *
 * <p>So the effect is a GUI blit: {@code GuiGraphics.blit} into an already-blended, already-orthographic
 * GUI pass, with the alpha applied through {@code setColor}. That is the same call vanilla uses for the
 * pumpkin and powder-snow overlays ({@code Gui.renderTextureOverlay}) and the same one this mod already
 * uses for the wheel's icons, so it is the path with the fewest unknowns. The trade is that the effect is
 * <b>flat on the screen</b> instead of standing at the caster's position in the world.
 *
 * <h2>It cannot block input and cannot pause the game</h2>
 * Nothing here is a {@code Screen}: no {@code setScreen}, no key handling, no mouse grab. The world keeps
 * ticking and every key still reaches vanilla and the mod's own gesture machines (the reason
 * {@link WheelOverlay} is an overlay too). The layer is purely additive to what is already drawn.
 *
 * <h2>What it draws, in order (back to front)</h2>
 * {@link #FLARE} fills the screen (a diagonal energy stream, 55% of the peak alpha), {@link #SLASH} fills
 * it on the same diagonal (the golden trail), {@link #BURST} is a centred square, and {@link #CUTIN} is the
 * character portrait in the bottom-right — the one derived from the official 6★ card art. All four fade on
 * the schedule {@link UbEffectSchedule} states and none of them survives past one second.
 *
 * <p>{@code F1} hides it, like every other HUD layer, because vanilla skips the whole GUI stack then: an
 * inherited behaviour, not an invented one. The {@code [effect] enableUbCastEffect} option turns it off
 * outright for a player who finds a full-screen flash unpleasant.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class UbEffectOverlay {

    /** This layer's own id, so the per-frame and per-cast diagnostics can name the layer they drew. */
    static final ResourceLocation LAYER_ID = InternalIds.id("ub_effect");

    /** The golden slash trail (512x512, RGBA with its own alpha ramp). */
    static final ResourceLocation SLASH = InternalIds.id("textures/effect/ub_strike.png");

    /** The radial golden burst (512x512). */
    static final ResourceLocation BURST = InternalIds.id("textures/effect/ub_burst.png");

    /** The diagonal energy flare (512x512). */
    static final ResourceLocation FLARE = InternalIds.id("textures/effect/ub_flare.png");

    /**
     * The character cut-in, derived from the official 6★ card art by
     * {@code tools/cutin_from_official_card.py}. 512x384 (4:3).
     */
    static final ResourceLocation CUTIN_DJEETA = cutIn("djeeta");

    /**
     * {@return the cut-in texture for a character, whether or not it ships art}
     *
     * <p>One expression, so {@link #resolve} and {@link #CUTIN_DJEETA} cannot disagree about the naming
     * rule: {@code ub_cutin_<path>.png} for {@code astrum:<path>}.
     *
     * @param artName the character's resource <b>path</b> (not the full id)
     */
    static ResourceLocation cutIn(String artName) {
        return InternalIds.id("textures/gui/ub_cutin_" + artName + ".png");
    }

    /** The tick of the last cast this layer logged a start for, so the line is once per cast. */
    private static long loggedStart = Long.MIN_VALUE;

    /**
     * How many frames this layer has drawn since the game started, and the last age it drew at.
     *
     * <p>Diagnostics, not state: they exist because "the layer drew, at which ages" is the only claim a log
     * can make about a visual. One int each, written from the render thread only.
     */
    private static int framesDrawn;

    /** The age of the last frame that actually emitted a quad, or {@code -1} before the first one. */
    private static int lastFrameAge = -1;

    /**
     * {@return how many frames this layer has drawn over its life}
     *
     * <p>Read by the probe and quoted by the per-frame DEBUG line; nothing in production depends on it.
     */
    static int framesDrawn() {
        return framesDrawn;
    }

    /**
     * {@return the effect age of the most recent frame that drew anything, or {@code -1} if none}
     *
     * <p>The assertion this exists for: the last drawn age must be {@code TOTAL_TICKS - 1}, not less. A
     * first draft ended its layers at tick 16 and then kept being called for four ticks drawing nothing —
     * invisible work that no screenshot and no human eye would ever report. It was found by logging the
     * ages, and this getter is what lets a probe assert it on a real client.
     */
    static int lastFrameAge() {
        return lastFrameAge;
    }    private UbEffectOverlay() {
    }

    /**
     * Draws the effect. Registered as a GUI layer above the cast pad, i.e. above everything this mod draws.
     *
     * @param graphics the GUI buffer
     * @param delta    frame delta; unused, the fade is a function of the game clock, not of frame time
     */
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        if (!ClientConfig.EFFECT.enableUbCastEffect.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        ClientCastEffect.Cast cast = ClientCastEffect.latest();
        if (cast == null) {
            return;
        }
        long now = minecraft.level.getGameTime();
        int age = UbEffectSchedule.age(now, cast.gameTime());
        if (!UbEffectSchedule.visible(age)) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (loggedStart != cast.gameTime()) {
            // One INFO line per cast: the layer's own start time, which is the second half of "the effect
            // was triggered" (the first half is the payload's arrival, logged by AstrumNetwork). A per-frame
            // line would be noise; this is evidence.
            loggedStart = cast.gameTime();
            Astrum.LOGGER.info("astrum: UB effect layer {} began at client tick {} — character {}{}, "
                            + "gui {}x{}, burst {}, cut-in {}",
                    LAYER_ID, cast.gameTime(), cast.characterId(),
                    cast.enhanced() ? ", enhanced" : "", width, height,
                    UbEffectSchedule.burstBox(width, height), UbEffectSchedule.cutInBox(width, height));
        }

        int drawn = 0;
        drawn += drawFullScreen(graphics, FLARE, UbEffectSchedule.flareAlpha(age), width, height);
        drawn += drawFullScreen(graphics, SLASH, UbEffectSchedule.slashAlpha(age), width, height);
        drawn += drawBox(graphics, BURST, UbEffectSchedule.burstAlpha(age),
                UbEffectSchedule.burstBox(width, height));
        drawn += drawBox(graphics, resolve(cast.characterId()), UbEffectSchedule.cutInAlpha(age),
                UbEffectSchedule.cutInBox(width, height));
        if (drawn > 0) {
            framesDrawn++;
            lastFrameAge = age;
        }
        Astrum.LOGGER.debug("astrum: UB effect frame layer={} age={}/{}t quads={} frames={} (flare {} "
                        + "slash {} burst {} cut-in {}, character {}{})",
                LAYER_ID, age, UbEffectSchedule.TOTAL_TICKS, drawn, framesDrawn,
                hex(UbEffectSchedule.flareAlpha(age)), hex(UbEffectSchedule.slashAlpha(age)),
                hex(UbEffectSchedule.burstAlpha(age)), hex(UbEffectSchedule.cutInAlpha(age)),
                cast.characterId(), cast.enhanced() ? ", enhanced" : "");
    }

    /**
     * {@return the texture to draw for {@code characterIdPath}, falling back to the shipped Djeeta art}
     *
     * <p>The fallback is a <b>resource existence check</b>, not a hard-coded id list: a pack that adds
     * {@code textures/gui/ub_cutin_miyako.png} gets its own cut-in with no code, and a character with no art
     * shows Djeeta's rather than the missing-texture chequerboard (or nothing at all). The check runs once
     * per frame on a miss and costs one map lookup; the alternative — a registered table — would make the
     * fallback a second place to keep in step with the art directory.
     *
     * <p>The client lookup is guarded: {@code Minecraft.getInstance()} is {@code null} before the game
     * boots, and a resource manager that is not there yet must answer "no art" rather than throw inside a
     * HUD layer (which would take the frame down).
     *
     * @param artName the character's resource path, from {@code UbCastEffectPayload.artName()}
     */
    static ResourceLocation resolve(String artName) {
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.server.packs.resources.ResourceManager resources =
                minecraft == null ? null : minecraft.getResourceManager();
        return resolveWith(artName, candidate -> resources != null
                && resources.getResource(candidate).isPresent());
    }

    /**
     * {@return the cut-in texture to use, given a test for "does this resource exist"}
     *
     * <p>The pure half of {@link #resolve}, split out so the naming rule and the fallback can be asserted
     * without a client — {@code UbEffectScheduleTest} drives it with a predicate instead of a resource
     * manager, which is the only way to test the rule at all in a unit test (spec §8.4's "pure where it can
     * be" applies to a HUD layer too).
     *
     * @param artName the character's resource path
     * @param exists  whether a texture resource is present in the running packs
     */
    static ResourceLocation resolveWith(String artName, java.util.function.Predicate<ResourceLocation> exists) {
        if (artName == null || artName.isBlank()) {
            return CUTIN_DJEETA;
        }
        ResourceLocation candidate = cutIn(artName);
        if (CUTIN_DJEETA.equals(candidate) || exists.test(candidate)) {
            return candidate;
        }
        return CUTIN_DJEETA;
    }

    private static int drawFullScreen(GuiGraphics graphics, ResourceLocation texture, float alpha,
            int width, int height) {
        if (alpha <= 0.0f || width <= 0 || height <= 0) {
            return 0;
        }
        blitColoured(graphics, texture, 0, 0, width, height, width, height, alpha);
        return 1;
    }

    private static int drawBox(GuiGraphics graphics, ResourceLocation texture, float alpha,
            UbEffectSchedule.Box box) {
        if (alpha <= 0.0f || !box.isDrawable()) {
            return 0;
        }
        blitColoured(graphics, texture, box.x(), box.y(), box.width(), box.height(), box.width(),
                box.height(), alpha);
        return 1;
    }

    /**
     * One textured quad, tinted by {@code alpha}.
     *
     * <h2>Why {@code setColor} and not a custom {@code RenderType}</h2>
     * {@code GuiGraphics.blit} draws through {@code position_tex} with the GUI's own blend state already
     * enabled, and {@code setColor} writes the shader's {@code ColorModulator} — the exact mechanism
     * {@code Gui.renderTextureOverlay} uses for the pumpkin blur and the powder-snow outline. No new render
     * type, no new shader, no blend-function guess: the one thing that cannot be eyeballed here is also the
     * one thing this method does not invent.
     *
     * <p>The colour is restored immediately afterwards. Leaving it set is how a later HUD layer (the gauge,
     * the hotbar, another mod's widget) ends up tinted by a fade that finished drawing two layers ago — a
     * bug that looks like the HUD "randomly" dimming.
     *
     * @param texture     the texture to draw
     * @param x           left edge, GUI pixels
     * @param y           top edge, GUI pixels
     * @param width       destination width
     * @param height      destination height
     * @param sourceWidth the texture's width, so the UVs span the whole image
     * @param sourceHeight the texture's height
     * @param alpha       the tint's alpha
     */
    private static void blitColoured(GuiGraphics graphics, ResourceLocation texture, int x, int y,
            int width, int height, int sourceWidth, int sourceHeight, float alpha) {
        graphics.setColor(1.0f, 1.0f, 1.0f, alpha);
        graphics.blit(texture, x, y, width, height, 0.0f, 0.0f, sourceWidth, sourceHeight,
                sourceWidth, sourceHeight);
        graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** {@return a float alpha as a printable {@code 0.00}, for the per-frame diagnostic} */
    private static String hex(float alpha) {
        return String.format("%.2f", alpha);
    }
}
