package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The two UB tiers of a character: the base one and the one unlocked at affection tier 3 — and, for the
 * one character who has one, the <b>second small-skill loop</b> the ultimate switches her into.
 *
 * @param base           the ultimate before 六星
 * @param enhanced       the ultimate at affection tier 3
 * @param icon           the ultimate's <b>one</b> picture, shared by both tiers
 *                       <p><b>Why the icon is here and not on {@link UbTier}.</b> 六星 renames the ultimate —
 *                       疾风剑雨 becomes 星河漫天 — but it is the same skill and therefore the same picture
 *                       (「一个技能可以引用多个通用效果的代码，但是图标只使用一个」). Putting it on the tier would invite two
 *                       tiers to disagree about one skill's face. Empty means "no art", and callers fall
 *                       back to the character's portrait.
 * @param secondRotation the loop this character enters <b>after her ultimate resolves</b> — 矛依未's
 *                       行动模式2 — or empty for every character who has only one loop. See
 *                       {@link SecondRotation} for the mechanism and for what the human ruled about the
 *                       charge.
 *                       <p><b>Why it hangs off {@code ub} and not off {@link CharacterDef}.</b> That record is
 *                       at exactly sixteen {@link RecordCodecBuilder#group} components, which is the
 *                       ceiling: a seventeenth does not compile (the same constraint {@code SkillLadder}'s
 *                       javadoc records for EX). It also belongs here by meaning — the loop is entered by
 *                       the ultimate and lasts as long as the mode the ultimate equipped, so the two are
 *                       read together. A pack that declares no {@code ub.secondRotation} keeps loading and
 *                       keeps one loop, which is every character but one.
 * @param landHooks      the hooks each rung fires on the bodies its damage lands on — {@link Trigger#UB_LAND},
 *                       one {@link EffectHook} list per rung — or empty when the ultimate carries no such
 *                       clause. See {@link UbHooks} for why the per-tier lists live here rather than on
 *                       {@link UbTier} and for what "fires when it lands" means.
 */
@ApiStatus.Experimental
public record UbDef(UbTier base, UbTier enhanced, Optional<ResourceLocation> icon,
                    Optional<SecondRotation> secondRotation, Optional<UbHooks> landHooks) {

    public static final Codec<UbDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UbTier.CODEC.fieldOf("base").forGetter(UbDef::base),
            UbTier.CODEC.fieldOf("enhanced").forGetter(UbDef::enhanced),
            ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(UbDef::icon),
            // Additive and last, for the reason the icon field above is optional: a pack written before this
            // field existed keeps decoding with one loop, which is exactly the behaviour it had.
            SecondRotation.CODEC.optionalFieldOf("secondRotation").forGetter(UbDef::secondRotation),
            // 「大招命中时」's hooks, one list per rung. Additive and last for the same reason: a pack
            // written before it existed declares no UB debuff and keeps loading with none. It lives here
            // rather than on UbTier because the per-tier shape is the same question this record already
            // answers (which rung carries what) — see UbHooks' javadoc.
            UbHooks.CODEC.optionalFieldOf("landHooks").forGetter(UbDef::landHooks)
    ).apply(instance, UbDef::new));

    public UbDef {
        icon = icon == null ? Optional.empty() : icon;
        secondRotation = secondRotation == null ? Optional.empty() : secondRotation;
        landHooks = landHooks == null ? Optional.empty() : landHooks;
    }

    /** The two-argument form every character written before the ultimate had a picture uses. */
    public UbDef(UbTier base, UbTier enhanced) {
        this(base, enhanced, Optional.empty());
    }

    /** The three-argument form, for a character whose ultimate has a picture but no second loop. */
    public UbDef(UbTier base, UbTier enhanced, Optional<ResourceLocation> icon) {
        this(base, enhanced, icon, Optional.empty());
    }

    /** The four-argument form, for a character whose ultimate has a picture and a second loop. */
    public UbDef(UbTier base, UbTier enhanced, Optional<ResourceLocation> icon,
            Optional<SecondRotation> secondRotation) {
        this(base, enhanced, icon, secondRotation, Optional.empty());
    }

    /**
     * {@return the hooks the fired rung carries for {@link Trigger#UB_LAND}, in declaration order}
     *
     * <p>The one accessor the settlement and a resource test read, so neither has to spell out "which list
     * for this rung, and what if the block is absent". A missing block, a null {@code ub} in a hand-built
     * fixture and a tier with no hooks all answer the empty list — the same additive promise the rest of this
     * record makes.
     *
     * @param enhanced whether the 6★ rung fired
     */
    public List<EffectHook> landHooksFor(boolean enhanced) {
        return landHooks.map(hooks -> hooks.forTier(enhanced)).orElse(List.of());
    }

    /**
     * {@return the second loop this character declares, or empty when she has only one}
     *
     * <h2>Why the filter is here and not at the call sites</h2>
     * "Declared but with no skills" is not a loop: a mode that switched her to an empty hook list would leave
     * her with no small skills at all, which is worse than not having the mode. That is a fact about the
     * declaration, so it is decided here — the reader and the runtime then cannot disagree about whether a
     * block in the JSON describes a loop.
     *
     * <h2>Why it is a method and not the bare field</h2>
     * {@code ub} is non-null in a decoded definition but <b>is</b> null in {@code CharacterRegistry.INVALID}'s
     * neighbourhood and in a hand-built fixture. This method keeps every caller from writing the same
     * {@code null} check plus the same filter, which is how the two would eventually drift.
     */
    public Optional<SecondRotation> secondRotationOf() {
        return secondRotation.filter(SecondRotation::isDeclared);
    }

    /**
     * {@return the <b>base</b> ultimate's name, or empty when the pack names none}
     *
     * <p>The convenience the roster and the log line want: 「what is her ultimate called at this tier」 is one
     * question, and answering it with {@code def.ub().base().name()} at every call site is the shape that
     * grows a null check per reader.
     */
    public Optional<String> baseName() {
        return base == null ? Optional.empty() : base.name();
    }

    /** {@return the <b>6★</b> ultimate's name, or empty when the pack names none} */
    public Optional<String> enhancedName() {
        return enhanced == null ? Optional.empty() : enhanced.name();
    }
}
