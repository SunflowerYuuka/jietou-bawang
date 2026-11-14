package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Static definition of one character, loaded from
 * {@code data/<pack namespace>/astrum/character/<id>.json}.
 *
 * <p>The doubled {@code astrum} is not a typo: NeoForge puts the registry key's own namespace into
 * the directory, so the registry {@code astrum:character} is read from
 * {@code data/<pack namespace>/astrum/character/}. A file at {@code data/astrum/character/} is
 * never read, and the registry comes up empty with no warning.
 *
 * @param schemaVersion version of this layout, used by later migration code
 * @param displayName   name shown in the party UI
 * @param dimension     damage dimension used for same-tick falloff
 * @param portrait      the <b>book-only</b> override: a dedicated 角色册 portrait, used instead of
 *                      {@link #avatar} where one is shipped. Empty is the normal case — a character
 *                      has one picture, and it is her avatar (see {@link #face()})
 * @param model         GeckoLib model file, empty for none
 * @param effects       hook list, indexed by {@link HookModifier#hookIndex()}
 * @param ub            both UB tiers
 * @param growth        experience curve
 * @param affection     affection tiers
 * @param skillName     the character's skill name, shown on her party effect; empty falls back to
 *                      {@link #displayName()}
 * @param avatar        the character's <b>one official picture</b>, as a full texture path
 *                      ({@code astrum:textures/gui/portrait/djeeta.png}). It is drawn as the party
 *                      effect icon <em>and</em> as her portrait in the 角色册's roster cells and party
 *                      slots; a pack that ships a different picture for the book adds {@link #portrait}.
 *                      Empty means "this character has no face art", and the book falls back to a glyph
 *                      disc while the party effect falls back to its own per-slot sprite.
 *                      <p><b>Never the item texture.</b> {@code astrum:textures/item/character_card.png}
 *                      is the generic card — the blank ticket every character is enrolled with — so it
 *                      cannot also be one girl's face. Pointing this field at it is exactly the defect
 *                      of 2026-11-14: the roster drew the shared card while her real face sat unused
 *                      at {@code textures/gui/portrait/djeeta.png}
 */
@ApiStatus.Experimental
public record CharacterDef(int schemaVersion, String displayName, Dimension dimension,
                           Optional<ResourceLocation> portrait, Optional<ResourceLocation> model,
                           List<EffectHook> effects, UbDef ub, GrowthDef growth, AffectionDef affection,
                           Optional<String> skillName, Optional<ResourceLocation> avatar) {

    public static final Codec<CharacterDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("schemaVersion").forGetter(CharacterDef::schemaVersion),
            Codec.STRING.fieldOf("displayName").forGetter(CharacterDef::displayName),
            Dimension.CODEC.fieldOf("dimension").forGetter(CharacterDef::dimension),
            ResourceLocation.CODEC.optionalFieldOf("portrait").forGetter(CharacterDef::portrait),
            ResourceLocation.CODEC.optionalFieldOf("model").forGetter(CharacterDef::model),
            EffectHook.CODEC.listOf().fieldOf("effects").forGetter(CharacterDef::effects),
            UbDef.CODEC.fieldOf("ub").forGetter(CharacterDef::ub),
            GrowthDef.CODEC.fieldOf("growth").forGetter(CharacterDef::growth),
            AffectionDef.CODEC.fieldOf("affection").forGetter(CharacterDef::affection),
            Codec.STRING.optionalFieldOf("skillName").forGetter(CharacterDef::skillName),
            ResourceLocation.CODEC.optionalFieldOf("avatar").forGetter(CharacterDef::avatar)
    ).apply(instance, CharacterDef::new));

    /**
     * {@return the name to show as this character's skill, falling back to {@link #displayName()}}
     *
     * <p>One rule, used by both the party effect and anything else that names a skill: a pack that
     * predates {@code skillName} keeps working, and a pack that writes a blank one is treated as if it
     * had omitted the field rather than showing an empty buff name.
     */
    public String effectiveSkillName() {
        return skillName.filter(name -> !name.isBlank()).orElse(displayName());
    }

    /**
     * {@return the picture to draw for this character where a face is wanted: her dedicated
     * {@link #portrait} if the pack ships one, otherwise her official {@link #avatar}}
     *
     * <h2>Two concepts, and the reason they are written down here</h2>
     * <ul>
     *   <li>{@code avatar} = <b>her one official picture</b>. One picture per character, and it is the
     *       same picture everywhere a face is wanted: the party-effect icon and the book portrait. A
     *       pack that ships nothing else should only ever fill this in.</li>
     *   <li>{@code portrait} = <b>the book's own override</b>. It exists for a pack that wants the
     *       角色册 to show a different crop or a different era of the character than the small HUD
     *       icon. When present it wins <em>for the book</em>; the party effect keeps {@code avatar}.</li>
     * </ul>
     *
     * <p>Neither is the item texture. {@code astrum:textures/item/character_card.png} is the
     * <b>generic card</b> — one blank ticket shared by every character, which is what the item is —
     * so a character whose face points at it shows a ticket where her face should be. That is
     * precisely the defect this javadoc exists to prevent recurring: {@code djeeta.json} had
     * {@code avatar} pointing at the card and no {@code portrait}, so the roster (and the party
     * effect beside it) drew the shared card while her real 64x64 face sat unused at
     * {@code textures/gui/portrait/djeeta.png}. The shipped arrangement is now: {@code avatar} = that
     * face (so the party effect and the book both draw it), no {@code portrait} (nothing needs the
     * override), and the card untouched. {@code DjeetaResourceTest} asserts both halves — the book
     * portrait is the portrait file <b>and</b> it differs from the card texture — so the two roles
     * cannot be re-merged by an edit nobody reads.
     *
     * <h2>Why this is a method and not a field</h2>
     * Writing the order down once — here, next to the two fields — is what keeps the roster cell, the
     * party slot, the wheel and the cast pad from each deciding for itself; the first defect of this
     * kind was the book asking {@code definition.portrait()} alone, which drew a {@code 姬} glyph in a
     * disc while the party effect right beside it drew her face.
     *
     * <p>Empty means the character has no face art at all; every caller then falls back to its own
     * stand-in (a glyph disc in the book, the per-slot rune on the effect HUD). It never means "look
     * this up somewhere else" — there is nowhere else.
     */
    public Optional<ResourceLocation> face() {
        return portrait.isPresent() ? portrait : avatar;
    }
}
