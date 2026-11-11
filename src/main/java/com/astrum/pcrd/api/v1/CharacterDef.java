package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * Static definition of one character, loaded from {@code data/astrum/character/<id>.json}.
 *
 * @param schemaVersion version of this layout, used by later migration code
 * @param displayName   name shown in the party UI
 * @param dimension     damage dimension used for same-tick falloff
 * @param portrait      GUI portrait texture, empty for none
 * @param model         GeckoLib model file, empty for none
 * @param effects       hook list, indexed by {@link HookModifier#hookIndex()}
 * @param ub            both UB tiers
 * @param growth        experience curve
 * @param affection     affection tiers
 */
public record CharacterDef(int schemaVersion, String displayName, Dimension dimension,
                           Optional<ResourceLocation> portrait, Optional<ResourceLocation> model,
                           List<EffectHook> effects, UbDef ub, GrowthDef growth, AffectionDef affection) {

    public static final Codec<CharacterDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("schemaVersion").forGetter(CharacterDef::schemaVersion),
            Codec.STRING.fieldOf("displayName").forGetter(CharacterDef::displayName),
            Dimension.CODEC.fieldOf("dimension").forGetter(CharacterDef::dimension),
            ResourceLocation.CODEC.optionalFieldOf("portrait").forGetter(CharacterDef::portrait),
            ResourceLocation.CODEC.optionalFieldOf("model").forGetter(CharacterDef::model),
            EffectHook.CODEC.listOf().fieldOf("effects").forGetter(CharacterDef::effects),
            UbDef.CODEC.fieldOf("ub").forGetter(CharacterDef::ub),
            GrowthDef.CODEC.fieldOf("growth").forGetter(CharacterDef::growth),
            AffectionDef.CODEC.fieldOf("affection").forGetter(CharacterDef::affection)
    ).apply(instance, CharacterDef::new));
}
