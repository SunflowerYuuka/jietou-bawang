package com.astrum.pcrd.impl.client;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Which picture stands for a skill on the book's screens — and what is drawn when it has none.</b>
 *
 * <h2>The rule, in the human's words</h2>
 * 「一个技能可以引用多个通用效果的代码，但是图标只使用一个」 — a skill may name any number of generic effects, and it
 * wears <b>one</b> picture. So a skill's picture is not derived from its effects at all: it comes from the
 * data ({@link com.astrum.pcrd.api.v1.SkillLadder#icon()} for a small skill,
 * {@link com.astrum.pcrd.api.v1.UbDef#icon()} for the ultimate), it is carried through
 * {@code SkillRows.Row.icon()}, and every surface that wants to draw "this skill" reads that one value.
 *
 * <h2>The fallback chain, and why it is three deep</h2>
 * <ol>
 *   <li><b>the skill's own picture</b> — {@code astrum:textures/gui/skillicon/<id>_{ub,main1,main2,sp1,ex}.png},
 *       64x64, one per skill per character;</li>
 *   <li><b>her portrait at her affection tier</b> — {@code TierAvatar}/{@code CharacterDef.face(int)}, for a
 *       pack that names a skill but ships no picture for it. It is the same picture the roster cell and the
 *       party slot draw, so a character whose skill art is missing still reads as <em>her</em>;</li>
 *   <li><b>{@link ArtFallback#PLACEHOLDER}</b> — 「请安装资源包」, for a character the loaded pack does not define
 *       at all (an addon's character in a client whose pack went away).</li>
 * </ol>
 * A surface that draws nothing leaves a hole in a row and reads as a layout bug, so the chain ends at a
 * picture rather than at {@code null}. The three steps are <b>one</b> function each, not a copy per call site:
 * the tier join stays in {@link TierAvatar}, the existence check for an uninstalled texture stays in
 * {@link ArtFallback}, and only the <em>order</em> lives here.
 *
 * <h2>What this class is not</h2>
 * It is not a per-effect icon table. There is no such table any more — 43 per-effect pictures and the
 * {@code SkillIcons} class were deleted deliberately, and an effect row is text from its own left edge. The
 * only thing that may put an image beside a skill is the skill's own {@code icon}.
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class SkillArt {

    private SkillArt() {
    }

    /**
     * {@return the picture to draw for a skill: its own, else the character's, else empty}
     *
     * <p>The order is the whole content of this method, and it is pure — no client, no resource manager, no
     * roster — so {@code SkillArtTest} can assert all four cases without a window.
     *
     * @param skillIcon the skill's own picture, from the data; {@code null} or empty when it has none
     * @param portrait  the character's face at her affection tier, from {@link TierAvatar}
     */
    public static Optional<ResourceLocation> pictureOf(@Nullable Optional<ResourceLocation> skillIcon,
            @Nullable Optional<ResourceLocation> portrait) {
        if (skillIcon != null && skillIcon.isPresent()) {
            return skillIcon;
        }
        return portrait == null ? Optional.empty() : portrait;
    }

    /** {@return the picture for one detail-page column: the skill's own, else the character's} */
    public static Optional<ResourceLocation> pictureFor(@Nullable SkillDetailModel.Entry entry,
            @Nullable Optional<ResourceLocation> portrait) {
        return pictureOf(entry == null ? Optional.empty() : entry.icon(), portrait);
    }

    /**
     * {@return the texture a surface blits for {@code picture} — never {@code null}, never the checkerboard}
     *
     * <p>{@link ArtFallback#resolve} answers the placeholder for a picture that is named but not installed
     * (the player has no {@code astrum-assets.zip}), and {@code null} when there is no picture at all or no
     * client yet. Both of those become {@link ArtFallback#PLACEHOLDER} here, which is the last link of the
     * chain and the reason no caller has to invent its own "draw nothing" branch.
     *
     * @param picture the chain's answer, possibly empty
     */
    public static ResourceLocation drawable(@Nullable Optional<ResourceLocation> picture) {
        if (picture == null || picture.isEmpty()) {
            return ArtFallback.PLACEHOLDER;
        }
        ResourceLocation resolved = ArtFallback.resolve(picture.get());
        return resolved == null ? ArtFallback.PLACEHOLDER : resolved;
    }
}
