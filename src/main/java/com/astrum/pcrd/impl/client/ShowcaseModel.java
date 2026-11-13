package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.showcase.ShowcaseEntity;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import software.bernie.geckolib.model.GeoModel;

/**
 * The showcase entity's GeckoLib model: where its three files are, spelled out.
 *
 * <h2>Why not {@code DefaultedEntityGeoModel}</h2>
 * That class derives its paths from a base id and hard-codes the {@code entity} leaf
 * ({@code geo/entity/<path>.geo.json}, {@code animations/entity/<path>.animation.json},
 * {@code textures/entity/<path>.png}). Two of those three are not where this project's files are, and
 * the brief fixes the paths ({@code assets/astrum/geo/djeeta.geo.json},
 * {@code assets/astrum/animations/djeeta.animation.json}); the texture is under
 * {@code textures/entity/}. Moving the model files to match the default would put them in a
 * placeholder-shaped directory for no gain, so the three paths are written out here instead. That also
 * makes the one fact a future artist cares about — "replace these three files" — visible in one place.
 *
 * <h2>Why these three ids and not the character id</h2>
 * The animation files are per <b>model</b>, not per character: a second character with the same
 * placeholder bones plays the same {@code ub_cast}, and the tier's own {@code animation} name selects a
 * clip inside the file. When a real per-character model arrives, this class gains a mapping from
 * character id to base id — one place, not a re-derivation in the renderer.
 */
@ApiStatus.Internal
public final class ShowcaseModel extends GeoModel<ShowcaseEntity> {

    /** The model file, i.e. {@code assets/astrum/geo/djeeta.geo.json} (spec §10's {@code geo/} folder). */
    public static final ResourceLocation MODEL = InternalIds.id("geo/djeeta.geo.json");

    /** The animation file, {@code assets/astrum/animations/djeeta.animation.json}. */
    public static final ResourceLocation ANIMATIONS = InternalIds.id("animations/djeeta.animation.json");

    /**
     * The skin, {@code assets/astrum/textures/entity/djeeta.png}.
     *
     * <p>Drawn by {@code tools/gen_showcase_skin.py} at the geo's declared 64x64. Crude on purpose — see
     * that script's docstring.
     */
    public static final ResourceLocation TEXTURE = InternalIds.id("textures/entity/djeeta.png");

    @Override
    public ResourceLocation getModelResource(ShowcaseEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(ShowcaseEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(ShowcaseEntity animatable) {
        return ANIMATIONS;
    }
}
