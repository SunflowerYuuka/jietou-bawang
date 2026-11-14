package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "One skill = several effects", as data (spec §5.2's 一个 trigger 挂多个 effect, applied to the
 * rotation).
 *
 * <h2>Why the rotation needed a grouping at all</h2>
 * The rotation used to count <b>hooks</b>. That was correct while every skill was one effect, and it
 * silently became wrong the moment a skill had two: djeeta's 技能2 ウェポンバースト is 「自分の物理攻撃力を
 * 小アップし、TPを特大回復」, so writing it as two {@code melee_hit} hooks would have made her rotation
 * {@code 广域斩击 → 攻击力上升 → 充能} — three entries — and one swing would have cast attack-up while
 * asserting it cast 武器爆发. That is the bug these tests exist to make impossible.
 *
 * <h2>What is pinned here</h2>
 * <ol>
 *   <li>Two hooks with the same tag are one skill, at one index, and one turn of the cursor fires
 *       both.</li>
 *   <li>An untagged hook is a skill of its own — the pre-tag shape keeps its exact old rotation,
 *       including two untagged hooks <b>not</b> being merged.</li>
 *   <li>Non-{@code melee_hit} hooks never join a skill and never appear in the cursor's period.</li>
 *   <li>A blank tag means "no tag", not "a skill named the empty string".</li>
 * </ol>
 */
class SkillGroupsTest {

    private static EffectHook hit(String tag, int amount) {
        TriggerParams params = tag == null
                ? TriggerParams.NONE
                : new TriggerParams(0, Optional.empty(), Optional.of(tag));
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT, params, 1.0, amount);
    }

    private static EffectHook untaggedHit() {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT, TriggerParams.NONE, 1.0, 1.0);
    }

    private static EffectHook killHook() {
        return new EffectHook.GainCharge(Trigger.KILL, TriggerParams.NONE, 1.0, 5.0);
    }

    @Test
    void twoHooksWithTheSameTagAreOneSkill() {
        SkillGroups groups = SkillGroups.of(List.of(hit("weapon_burst", 1), hit("weapon_burst", 2)));
        assertEquals(1, groups.count(), "两钩同标签 = 一个技能，周期是 1 而不是 2");
        assertEquals(List.of(0, 1), groups.hooksOf(0));
        assertEquals(0, groups.skillOf(0));
        assertEquals(0, groups.skillOf(1));
    }

    @Test
    void untaggedHooksAreEachASkillOfTheirOwn() {
        SkillGroups groups = SkillGroups.of(List.of(untaggedHit(), untaggedHit(), untaggedHit()));
        assertEquals(3, groups.count(), "无标签的钩各自成技能：这是标签出现之前的形状，必须原样保留");
        assertEquals(List.of(0), groups.hooksOf(0));
        assertEquals(List.of(2), groups.hooksOf(2));
    }

    @Test
    void tagsGroupAcrossOtherHooksAndKeepFirstAppearanceOrder() {
        // wide_slash first, weapon_burst second, then a third plain hook, then weapon_burst again.
        SkillGroups groups = SkillGroups.of(List.of(
                hit("wide_slash", 1), hit("weapon_burst", 2), untaggedHit(), hit("weapon_burst", 3)));
        assertEquals(3, groups.count(), "三种技能：wide_slash / weapon_burst / 无标签");
        assertEquals(List.of(0), groups.hooksOf(0), "第一个出现的标签是第一个技能");
        assertEquals(List.of(1, 3), groups.hooksOf(1),
                "同标签的第二个钩归入同一个技能，即使中间隔着别的钩");
        assertEquals(List.of(2), groups.hooksOf(2));
        assertEquals(SkillGroups.NOT_ROTATING, groups.skillOf(-1));
        assertEquals(List.of(), groups.hooksOf(9), "越界技能回答空，不抛");
    }

    @Test
    void nonMeleeHooksNeverRotate() {
        SkillGroups groups = SkillGroups.of(List.of(hit("a", 1), killHook(), hit("a", 2)));
        assertEquals(1, groups.count(), "kill 钩不是挥砍技能，不进周期");
        assertEquals(SkillGroups.NOT_ROTATING, groups.skillOf(1));
        assertEquals(List.of(0, 2), groups.hooksOf(0));
    }

    @Test
    void aBlankTagMeansNoTag() {
        SkillGroups groups = SkillGroups.of(List.of(hit(" ", 1), hit("", 2)));
        assertEquals(2, groups.count(),
                "空字符串不是技能名；否则两个不同的技能会被静默合并成一个");
    }

    @Test
    void nullAndEmptyInputsAreSafe() {
        assertEquals(0, SkillGroups.of(null).count());
        assertEquals(0, SkillGroups.of(List.of()).count());
        assertTrue(SkillGroups.of(List.of(untaggedHit())).count() > 0);
    }
}
