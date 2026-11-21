package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * Affection tiers of a character: <b>the thresholds, and what each rung unlocks</b>.
 *
 * <h2>What changed, and why the old shape could not be kept</h2>
 * This record used to carry {@code tier2HookModifiers} — a list of {@code (hookIndex, mult)} pairs that
 * <b>scaled</b> a hook once the character reached tier 2. That model is gone. It cannot express what the
 * game actually does, and 姬塔's 技能1 is the counter-example that settles it:
 *
 * <pre>
 *   base 广域斩击    【1.6*物理攻击力+20+20*技能等级】物理伤害
 *   专武 广域斩击+   【2.25*物理攻击力+28+28*技能等级】物理伤害 + 自身200技能值 + 队友100技能值
 *   6★   黄金耀光    【4.8*物理攻击力+60+60*技能等级】物理伤害 + 自身200技能值 + 队友100技能值
 * </pre>
 *
 * <p>Three different <em>coefficients</em>, three different <em>constants</em>, and a clause the base version
 * does not have. No single multiplier of the base skill produces the 6★ one, and "multiply the damage by
 * 1.5" silently leaves out the TP grant entirely. So a rung supplies a <b>replacement skill set</b>
 * ({@link SkillLadder}), and the ladder is data rather than arithmetic.
 *
 * <h2>The rotation kernels are untouched</h2>
 * {@code SkillRotation} and {@code SkillGroups} still operate on a plain {@code List<EffectHook>}; they are
 * simply handed the list the current tier resolves to ({@code SkillSet#hooks}). So "which skill is next"
 * still has exactly one implementation, and a tier swap cannot change the rotation's period by accident —
 * the resolved set has the same skills in the same order, with the upgraded bodies.
 *
 * @param tier2Threshold affection needed for tier 2 (专武 / 5★); {@code 0} means the pack defines no tier 2
 * @param tier3Threshold affection needed for tier 3 (六星开花 / 6★); {@code 0} means no tier 3
 * @param skills         one {@link SkillLadder} per skill whose name or body changes with affection. A skill
 *                       absent from this list is the same at every tier — which is the normal case for a
 *                       character whose 6★ only changes her ultimate, and the reason this is a list rather
 *                       than a field per skill
 * @param critChance     this character's own <b>暴击率</b>, as a fraction in {@code [0, 1]}, {@code 0.05} =
 *                       one hit in twenty crits. Absent (or {@code 0}) means "she grants none", which is
 *                       exactly the behaviour every pack written before this field had — and it is why
 *                       {@code Crit.DEFAULT_CHANCE} stays {@code 0.0}: the attribute still defaults to
 *                       "nobody crits", and this is a <b>grant</b> on top of it, applied while she holds a
 *                       party slot and removed when she leaves
 *                       ({@code impl.effect.CritChances} owns that lifecycle).
 *                       <p><b>Why the field lives here.</b> {@code CharacterDef} is at sixteen
 *                       {@code RecordCodecBuilder.group} components, which is the ceiling — a seventeenth
 *                       does not compile — so a per-character value needs a nested record, and this is the
 *                       character-level one: it is the block that already carries the data that is not one
 *                       skill's ({@code skills} is a list of per-skill ladders, and a crit chance is not a
 *                       skill). The alternative shapes were considered and rejected in {@code
 *                       task-58-report.md}: a sibling of {@link ExPassive} would have made the value
 *                       undeclarable for a character with no EX ladder (every pack that predates EX), and a
 *                       {@code crit} entry in {@code skills} would have made a rate look like a skill the
 *                       rotation could fire. The placement under {@code affection} is the price of the
 *                       sixteen-component ceiling, not a claim that crit belongs to affection: the field is
 *                       <b>tier-independent</b> — it is one number for the character, not one per rung
 */
@ApiStatus.Experimental
public record AffectionDef(int tier2Threshold, int tier3Threshold, List<SkillLadder> skills,
                           double critChance) {

    public static final Codec<AffectionDef> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("tier2Threshold").forGetter(AffectionDef::tier2Threshold),
            Codec.INT.fieldOf("tier3Threshold").forGetter(AffectionDef::tier3Threshold),
            SkillLadder.CODEC.listOf().optionalFieldOf("skills", List.of()).forGetter(AffectionDef::skills),
            // Additive and last: a pack that names no chance decodes to 0.0, i.e. the behaviour it had before
            // this field existed. See the javadoc for why the default is 0 and stays 0.
            Codec.DOUBLE.optionalFieldOf("critChance", 0.0).forGetter(AffectionDef::critChance)
    ).apply(instance, AffectionDef::new));

    public AffectionDef {
        skills = skills == null ? List.of() : List.copyOf(skills);
        critChance = Double.isFinite(critChance) ? Math.max(0.0, Math.min(1.0, critChance)) : 0.0;
    }

    /**
     * The three-field form every pack and test written before {@code critChance} existed uses: no crit grant.
     *
     * <p>Kept as a constructor rather than making eight callers pass {@code 0.0} so the meaning of the new field
     * is stated once, here — and so a character who declares none reads as the ordinary case it is.
     */
    public AffectionDef(int tier2Threshold, int tier3Threshold, List<SkillLadder> skills) {
        this(tier2Threshold, tier3Threshold, skills, 0.0);
    }

    /** {@return a ladder with no skill upgrades at all — a character whose tiers change only her ultimate} */
    public static AffectionDef thresholds(int tier2Threshold, int tier3Threshold) {
        return new AffectionDef(tier2Threshold, tier3Threshold, List.of());
    }

    /**
     * {@return the ladder for {@code skillTag}, or empty when that skill is the same at every tier}
     *
     * <p>Matched on the same tag {@code SkillGroups} groups by, so a ladder and the rotation name a skill
     * identically. Compared exactly: two tags differing in case are two skills, because the rotation
     * compares them exactly too.
     *
     * @param skillTag the {@code triggerParams.skill} tag
     */
    public java.util.Optional<SkillLadder> ladderFor(String skillTag) {
        if (skillTag == null || skillTag.isBlank()) {
            return java.util.Optional.empty();
        }
        for (SkillLadder ladder : skills) {
            if (ladder != null && skillTag.equals(ladder.skill())) {
                return java.util.Optional.of(ladder);
            }
        }
        return java.util.Optional.empty();
    }
}
