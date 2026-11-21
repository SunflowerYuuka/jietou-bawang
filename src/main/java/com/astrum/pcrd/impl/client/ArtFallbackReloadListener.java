package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client resource-reload hook that tells {@link ArtFallback} to forget which textures were missing.
 *
 * <h2>Why a reload listener and not a tick check</h2>
 * "A player just installed the art pack" is exactly the moment resources reload — dragging the zip into
 * {@code resourcepacks/} and pressing F3+T, or enabling it in the pack list. So the event exists, it is
 * client-only, and it fires once per reload. A periodic re-check would be a tick poll (forbidden by this
 * project's red line) and would still be up to a second late.
 *
 * <h2>Why the body is one call</h2>
 * Nothing is read during {@code prepare}: there is no data to load, only a cache to drop. Doing the work in
 * {@code apply} — the synchronous main-thread stage — is what makes it safe to touch a static set the render
 * threads read.
 *
 * <p>{@code SimplePreparableReloadListener}'s two stages exist so a slow pack can be read off-thread; a
 * cache invalidation has nothing to read, so both stages are trivial and the shape is chosen for the
 * ordering guarantee rather than the parallelism.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class ArtFallbackReloadListener extends SimplePreparableReloadListener<Void> {

    /** The single instance the client registers; the listener holds no state of its own. */
    public static final ArtFallbackReloadListener INSTANCE = new ArtFallbackReloadListener();

    private ArtFallbackReloadListener() {
    }

    /** Nothing to read off-thread: the cache is the only state, and it is dropped in {@link #apply}. */
    @Override
    protected Void prepare(ResourceManager resources, ProfilerFiller profiler) {
        return null;
    }

    /**
     * Drops every cached missing-texture verdict, so a newly installed art pack is picked up.
     *
     * @param unused   the (empty) prepared value
     * @param resources the freshly reloaded resource manager
     * @param profiler  the reload profiler
     */
    @Override
    protected void apply(Void unused, ResourceManager resources, ProfilerFiller profiler) {
        profiler.push("astrum_art_fallback");
        try {
            ArtFallback.invalidate();
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: clearing the missing-texture cache threw; the art may stay "
                    + "unresolved until the next reload", e);
        } finally {
            profiler.pop();
        }
    }
}
