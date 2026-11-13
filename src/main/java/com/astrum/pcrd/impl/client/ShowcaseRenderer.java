package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.showcase.ShowcaseEntity;
import com.astrum.pcrd.impl.showcase.ShowcaseTypes;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jetbrains.annotations.ApiStatus;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * The client half of the 演出 entity: one GeckoLib renderer, one registration.
 *
 * <h2>Why this file lives under {@code impl/client}</h2>
 * It imports {@code net.minecraft.client} (through GeckoLib's renderer, which extends
 * {@code EntityRenderer}) and {@code EntityRenderersEvent}, so it must never be class-loaded on a
 * dedicated server. {@code guard} fails on any {@code net.minecraft.client} import outside this
 * package, and the registration below is reached only from {@code ClientEvents}, which
 * {@code Astrum} calls inside its {@code Dist.CLIENT} branch — the same rule every other client class
 * in this mod follows (spec §7 端隔离).
 *
 * <h2>Nothing here decides anything about the entity</h2>
 * The renderer draws whatever {@link ShowcaseModel} names and plays whatever the entity's synched
 * {@code animation} field says; the spawn, the animation name, the budget and every despawn are
 * server-side ({@code ShowcaseManager}, {@code ShowcaseLifetime}). That split is spec §9.5: the client
 * renders and nothing else.
 *
 * <h2>What a human has to confirm, because no log can</h2>
 * That it <b>looks</b> right: that the six-bone placeholder reads as a figure, that the skin covers
 * every face of every cube, and that {@code ub_cast} is recognisable as "raise an arm, strike". The
 * logs this task produced prove the entity exists, plays the named animation and goes away; they
 * cannot prove a single pixel.
 */
@ApiStatus.Internal
public final class ShowcaseRenderer extends GeoEntityRenderer<ShowcaseEntity> {

    /**
     * Creates the renderer for the showcase type.
     *
     * @param context the renderer provider context vanilla hands every entity renderer
     */
    public ShowcaseRenderer(EntityRendererProvider.Context context) {
        super(context, new ShowcaseModel());
        // No shadow: a cutscene figure casting a moving blob on the ground would read as a mob standing
        // there, which is the one thing this entity must not look like.
        this.shadowRadius = 0.0F;
    }

    /**
     * Registers the renderer for the showcase entity type.
     *
     * <p>Called from {@link ClientEvents#register}, i.e. only in the {@code Dist.CLIENT} branch.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(EntityRenderersEvent.RegisterRenderers.class,
                event -> event.registerEntityRenderer(ShowcaseTypes.SHOWCASE.get(), ShowcaseRenderer::new));
        Astrum.LOGGER.info("astrum: showcase renderer registered for {} (model {}, animations {}, skin {})",
                ShowcaseTypes.ENTITY_ID, ShowcaseModel.MODEL, ShowcaseModel.ANIMATIONS,
                ShowcaseModel.TEXTURE);
    }
}
