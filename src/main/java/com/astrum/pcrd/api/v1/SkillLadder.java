package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>One skill's three rungs</b> — the affection ladder, stated as data.
 *
 * <h2>The rule the human fixed, in his own words</h2>
 * <ul>
 *   <li><b>Tier 1 — 白板.</b> Every skill at its base version.</li>
 *   <li><b>Tier 2 — 专武 (unique weapon) / 5★.</b> Every skill whose upgraded name ends in {@code +}
 *       switches to that version, and <b>the name shown gains the {@code +}</b>. Skills with no {@code +}
 *       version stay as they are.</li>
 *   <li><b>Tier 3 — 六星开花 (6★).</b> Every skill whose <b>name changes</b> switches to the renamed
 *       version. <b>A rename supersedes a {@code +}</b>: 姬塔's 技能1 has both {@code 广域斩击+} (tier 2)
 *       and {@code 黄金耀光} (tier 3), and at tier 3 it is 黄金耀光, not 广域斩击+.</li>
 * </ul>
 *
 * <h2>How "supersedes" is expressed</h2>
 * {@link #nameAt(int)} asks tier 3 <b>first</b> and only falls back to tier 2. That is the whole rule,
 * written once: a ladder whose tier 3 exists can never show the tier-2 {@code +} name, and a ladder
 * without a tier 3 keeps showing it (コッコロ's 技能1 is {@code 掠阵三段击+} at tier 2 <em>and</em> at tier 3,
 * because コッコロ has no 6★ rename of it). {@link #upgradeAt(int)} is the same question for the hooks, so
 * the name and the numbers cannot resolve to different rungs.
 *
 * <h2>What {@code skill} is</h2>
 * The same tag the hooks carry in {@code triggerParams.skill} — the key {@code SkillGroups} already groups
 * by. So a ladder names a skill <b>the way the rotation names it</b>, and there is no second vocabulary. The
 * one reserved tag is {@value #EX_SKILL}, which no hook ever carries: an EX rung is recorded with an empty
 * {@code effects} list because EX is a battle-start stat buff <b>that is not a hook</b> — the
 * {@link Trigger#BATTLE_START} trigger fires a character's hooks on a fight's first blow and cannot apply an
 * ownership, so its strength travels in {@link #passive()} instead, and {@code impl.effect.ExPassives} applies
 * it on enrolment.
 *
 * <p>The <b>ultimate</b> is deliberately not a ladder: its two rungs already exist as
 * {@code CharacterDef.ub.base} / {@code ub.enhanced}, each of which carries its own {@code name}. A third
 * copy here would be the duplication this project forbids.
 *
 * @param skill    the skill tag this ladder describes, matching {@code TriggerParams.skill}
 * @param baseName the name at tier 1 (and at every tier with no upgrade)
 * @param tier2    the 专武 / 5★ rung, if this skill has one
 * @param tier3    the 6★ rung, if this skill's name changes at 6★
 * @param passive  what this skill grants <b>as a passive</b> while the character is enrolled, for the one
 *                 ladder that has one: {@value #EX_SKILL}. Empty — the normal case — means this skill is
 *                 only ever fired by a trigger, which is every skill but EX.
 * @param icon     the skill's <b>one</b> picture, drawn wherever a picture of this skill is wanted: the
 *                 roster's skill list, the detail page, and the entry the effect bar shows while the skill's
 *                 effects are live.
 *                 <p><b>Why the icon lives on the skill and not on the effect types.</b> A generic effect has
 *                 no picture any more (「通用效果现在不需要图标，只作为代码引用」): one skill fires several of
 *                 them, and the human's rule is 「一个技能可以引用多个通用效果的代码，但是图标只使用一个」. So the
 *                 picture belongs to the skill, and a skill has exactly one at every rung — a tier that
 *                 renames 广域斩击 to 黄金耀光 keeps the same picture, because it is the same skill.
 *                 Empty means "no art for this skill", and callers fall back to the character's face.
 *                 <p><b>Why EX lives here and not on {@link CharacterDef}.</b> {@code CharacterDef} is at
 *                 sixteen {@code RecordCodecBuilder.group} components, which is the ceiling; a seventeenth
 *                 does not compile. So EX is not a new top-level field: it is one more entry in the
 *                 per-skill structure that already exists, keyed by the reserved tag {@code "ex"}. See
 *                 {@link ExPassive} for the shape and the reasoning.
 */
@ApiStatus.Experimental
public record SkillLadder(String skill, String baseName, Optional<SkillUpgrade> tier2,
                          Optional<SkillUpgrade> tier3, Optional<ExPassive> passive,
                          Optional<ResourceLocation> icon) {

    /**
     * The one reserved skill tag that is not a hook: an EX skill is a battle-start stat buff, so no
     * {@link EffectHook} ever carries it.
     *
     * <p>Declared here because this is where the tag's meaning lives — a ladder named {@code ex} is the
     * character's EX skill, and {@code impl.effect.ExPassives} is what turns its {@link #passive()} block into
     * real effects on enrolment.
     */
    public static final String EX_SKILL = "ex";

    public static final Codec<SkillLadder> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("skill").forGetter(SkillLadder::skill),
            Codec.STRING.fieldOf("baseName").forGetter(SkillLadder::baseName),
            SkillUpgrade.CODEC.optionalFieldOf("tier2").forGetter(SkillLadder::tier2),
            SkillUpgrade.CODEC.optionalFieldOf("tier3").forGetter(SkillLadder::tier3),
            ExPassive.CODEC.optionalFieldOf("passive").forGetter(SkillLadder::passive),
            ResourceLocation.CODEC.optionalFieldOf("icon").forGetter(SkillLadder::icon)
    ).apply(instance, SkillLadder::new));

    public SkillLadder {
        skill = skill == null ? "" : skill;
        baseName = baseName == null ? "" : baseName;
        tier2 = tier2 == null ? Optional.empty() : tier2;
        tier3 = tier3 == null ? Optional.empty() : tier3;
        passive = passive == null ? Optional.empty() : passive;
        icon = icon == null ? Optional.empty() : icon;
    }

    /** {@return a ladder whose skill never changes: one rung, named once} */
    public static SkillLadder fixed(String skill, String baseName) {
        return new SkillLadder(skill, baseName, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /**
     * The four-field form every ladder written before {@code passive} existed uses.
     *
     * <p>Kept as a constructor rather than making callers pass two more {@code Optional.empty()} so the
     * meaning of the new fields is stated once, here — and so a ladder with no passive reads as the ordinary
     * case it is rather than as a caller who forgot something. The codec is unaffected: both are
     * {@code optionalFieldOf}, so a pack that does not write them decodes to this shape.
     */
    public SkillLadder(String skill, String baseName, Optional<SkillUpgrade> tier2,
            Optional<SkillUpgrade> tier3) {
        this(skill, baseName, tier2, tier3, Optional.empty(), Optional.empty());
    }

    /** {@return the five-field form, for a ladder with a passive but no icon} */
    public SkillLadder(String skill, String baseName, Optional<SkillUpgrade> tier2,
            Optional<SkillUpgrade> tier3, Optional<ExPassive> passive) {
        this(skill, baseName, tier2, tier3, passive, Optional.empty());
    }

    /** {@return whether this ladder describes the character's EX skill} */
    public boolean isEx() {
        return EX_SKILL.equals(skill);
    }

    /**
     * {@return this skill's picture, if the pack ships one}
     *
     * <p>The same picture at every rung: a tier that renames 广域斩击 to 黄金耀光 is still the same skill, and
     * the human's rule is one icon per skill. Callers fall back to the character's face when this is empty.
     */
    public Optional<ResourceLocation> icon() {
        return icon;
    }

    /**
     * {@return the rung that applies at affection {@code tier}, or empty when this skill is unchanged}
     *
     * <p>Tier 3 first: that ordering <b>is</b> "a rename supersedes a {@code +}".
     *
     * @param tier the affection tier, {@code 1}–{@code 3}; anything else is clamped
     */
    public Optional<SkillUpgrade> upgradeAt(int tier) {
        if (tier >= 3 && tier3.isPresent()) {
            return tier3;
        }
        if (tier >= 2 && tier2.isPresent()) {
            return tier2;
        }
        return Optional.empty();
    }

    /**
     * {@return the name to show at affection {@code tier}}
     *
     * <p>The single place the {@code +} and the rename are decided, so the roster, the log line and any
     * future surface cannot disagree about what her 技能1 is called right now.
     *
     * @param tier the affection tier, {@code 1}–{@code 3}
     */
    public String nameAt(int tier) {
        return upgradeAt(tier).map(SkillUpgrade::name)
                .filter(name -> !name.isBlank())
                .orElse(baseName);
    }
}
