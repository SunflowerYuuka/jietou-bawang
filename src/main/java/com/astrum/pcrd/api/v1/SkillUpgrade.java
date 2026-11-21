package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>One tier's replacement for one skill</b>: the new name, and the hooks the skill becomes.
 *
 * <h2>Why a replacement and not a multiplier</h2>
 * The affection ladder used to scale a hook ({@code tier2HookModifiers[].mult}). That model cannot
 * express what the real game actually does. 姬塔's 技能1 is 广域斩击
 * 「【1.6*物理攻击力+20+20*技能等级】物理伤害」; her 专武 version is
 * 「【2.25*物理攻击力+28+28*技能等级】物理伤害 + 自身回复200技能值 + 自身以外我方全体回复100技能值」;
 * and her 6★ version is 黄金耀光 「【4.8*物理攻击力+60+60*技能等级】+ 200 TP」. Three skills with a
 * different <em>coefficient</em>, a different <em>constant</em> and a different <em>set of clauses</em> —
 * no single multiplier of the base skill produces the third, and the second has a clause the first does
 * not have at all.
 *
 * <p>So a rung is a <b>replacement skill set</b>, and this record is it. The rotation kernels
 * ({@code SkillRotation}, {@code SkillGroups}) are untouched: they operate on whatever hook list the
 * resolver hands them, so "which skill is next" keeps exactly one implementation.
 *
 * <h2>Why the name travels with the hooks</h2>
 * 「the roster screen must show the current tier's skill names (with {@code +} where applicable)」. The name
 * is not decoration on the number: 広域斬撃 → 広域斬撃**+** → 黄金耀光 is how a player knows which rung she is on
 * without reading the coefficient. Keeping the name in the same object as the hooks is what makes
 * "the name says X" and "the skill does X" impossible to disagree.
 *
 * @param name    the skill's name at this rung, e.g. {@code 广域斩击+} or {@code 黄金耀光}. Required
 *                rather than optional: a rung without a name would be a rung the roster cannot show
 * @param effects the hooks this skill fires at this rung, in firing order. An <b>empty</b> list is legal
 *                and is how an EX skill is recorded: EX is a battle-start stat buff, which this build has
 *                no trigger for, so it is carried in the data (and shown by the roster) without being
 *                applied — {@code docs/balance-derivation.md} lists it among the clauses this build cannot
 *                express, rather than the field being silently dropped
 */
@ApiStatus.Experimental
public record SkillUpgrade(String name, List<EffectHook> effects) {

    public static final Codec<SkillUpgrade> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(SkillUpgrade::name),
            EffectHook.CODEC.listOf().optionalFieldOf("effects", List.of())
                    .forGetter(SkillUpgrade::effects)
    ).apply(instance, SkillUpgrade::new));

    public SkillUpgrade {
        name = name == null ? "" : name;
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    /** {@return the rung that renames a skill but adds no clause — the shape every EX rung has} */
    public static SkillUpgrade named(String name) {
        return new SkillUpgrade(name, List.of());
    }
}
