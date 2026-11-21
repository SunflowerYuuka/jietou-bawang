package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.impl.combat.HookPlan;
import com.astrum.pcrd.impl.combat.SkillRotation;
import com.astrum.pcrd.impl.combat.TriggerFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「不同技能都相同效果，取最高等级」</b> — the same effect on two skills resolves to one application, at the
 * highest tier, and the resolution happens <b>once before dispatch</b>.
 *
 * <h2>The rule, stated as the human stated it</h2>
 * Two skills both applying {@code buff_attack_area}, declared at tiers 1 and 2, on a <b>level-35</b> character
 * (band {@code +1}): {@code max(1 + 1, 2 + 1) = 3}, applied <b>once</b>. Not 3 twice, not 1 + 2, and not
 * {@code max(1,2)} with the band lost.
 *
 * <h2>Why the test drives {@code HookPlan.fireTiered}</h2>
 * Because that is where production resolves it. A test that called {@link EffectCollapse#collapse} alone would
 * still pass if the collapse were dropped from the firing loop — the "one layer out" defect
 * {@code HookPlanTest}'s header warns about. So most assertions below run the real loop through its
 * {@link HookPlan.TieredEffectRun} seam and read what it <em>ran</em>.
 *
 * <h2>Also pinned here: what must NOT collapse</h2>
 * A non-tiered effect is never merged (two skills that both deal damage are two hits), and two shields with
 * different reaches are two applications to different bodies. Both are one test each, because "collapse
 * everything of one type" is the plausible over-correction.
 *
 * <p><b>No {@code CharacterDef} and no world.</b> The rule is a pure function of a hook list and a level, so a
 * fixture character would be a third thing that could be wrong.
 *
 * <h2>Why the second hook is often a {@code crit} hook</h2>
 * The rotation fires <b>one</b> of a character's {@code melee_hit} skills per swing ({@code HookPlan}), so two
 * {@code melee_hit} skills literally never fire together and could never demonstrate the rule. The case the
 * human is describing is two skills whose effects land in the <b>same event</b> — which happens when one is a
 * different trigger ({@code crit}, {@code kill}, {@code block}) or when one skill carries two effects of the
 * same type. Both shapes are used below; the {@code crit} hit is driven with {@code crit = true} so the hook
 * really is observed.
 */
class EffectCollapseTest {

    /** The skill tag a hook belongs to — two skills are made distinct by their tag, never by their effect. */
    private static TriggerParams skill(String tag) {
        return new TriggerParams(0, Optional.empty(), Optional.of(tag));
    }

    private static EffectHook.BuffAttackArea attack(String tag, int tier) {
        return new EffectHook.BuffAttackArea(Trigger.MELEE_HIT, skill(tag), 1.0,
                TieredParams.ofTier(tier));
    }

    /** The same effect on a <b>second skill</b>, reached by a different trigger so it fires in the same event. */
    private static EffectHook.BuffAttackArea attackOnCrit(String tag, int tier) {
        return new EffectHook.BuffAttackArea(Trigger.CRIT, skill(tag), 1.0, TieredParams.ofTier(tier));
    }

    private static EffectHook.Haste haste(String tag, int tier) {
        return new EffectHook.Haste(Trigger.MELEE_HIT, skill(tag), 1.0, TieredParams.ofTier(tier));
    }

    private static EffectHook.Shield shield(String tag, int tier, double radius) {
        return new EffectHook.Shield(Trigger.MELEE_HIT, skill(tag), 1.0,
                new TieredParams(tier, OptionalDouble.of(8.0), OptionalDouble.of(radius)));
    }

    private static EffectHook.DefenceDown defenceDown(String tag, int tier) {
        return new EffectHook.DefenceDown(Trigger.MELEE_HIT, skill(tag), 1.0, TieredParams.ofTier(tier));
    }

    private static EffectHook.ExtraDamage damage(String tag, double amount) {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT, skill(tag), 1.0, amount);
    }

    private static EffectHook.ExtraDamage damageOnCrit(String tag, double amount) {
        return new EffectHook.ExtraDamage(Trigger.CRIT, skill(tag), 1.0, amount);
    }

    /** What the firing loop actually ran: the effect type and the <b>resolved</b> tier it was handed. */
    private record Ran(EffectType type, int tier) {
    }

    /** One melee <b>crit</b> firing at {@code level}: both a melee skill and a crit skill are observed. */
    private static List<Ran> fire(List<EffectHook> hooks, int level) {
        List<Ran> ran = new ArrayList<>();
        HookPlan.fireTiered(hooks, TriggerFacts.forHit(true, false, true, 3, false), chance -> true,
                (index, hook, tier) -> {
                    ran.add(new Ran(hook.type(), tier));
                    return Optional.empty();
                },
                invalid -> { }, SkillRotation.START, level);
        return ran;
    }

    // ------------------------------------------------------------------ the rule itself

    /**
     * <b>The headline case.</b> Two skills, one effect, tiers 1 and 2, level 35 → one application at tier 3.
     */
    @Test
    void twoSkillsWithTheSameEffectResolveToOneApplicationAtTheHighestTier() {
        List<Ran> ran = fire(List.of(attack("skill_a", 1), attackOnCrit("skill_b", 2)), 35);
        assertEquals(1, ran.size(), "one application, not two: 「不同技能都相同效果」 is one effect");
        assertEquals(EffectType.BUFF_ATTACK_AREA, ran.get(0).type());
        assertEquals(3, ran.get(0).tier(), "max(1 + 1, 2 + 1) = 3 — the band applies to BOTH before the max");
    }

    /** The same case at level 1: no band, so the answer is the declared maximum, 2. */
    @Test
    void atLevelOneTheSameCaseResolvesToTheDeclaredMaximum() {
        List<Ran> ran = fire(List.of(attack("skill_a", 1), attackOnCrit("skill_b", 2)), 1);
        assertEquals(1, ran.size());
        assertEquals(2, ran.get(0).tier(), "max(1 + 0, 2 + 0) = 2");
    }

    /**
     * <b>One skill carrying the same effect twice</b> collapses too — the rule is about the effect, not about
     * how many skills the pack spread it over. This is the shape a pack most often writes by accident.
     */
    @Test
    void oneSkillWithTheSameEffectTwiceCollapsesToOne() {
        List<Ran> ran = fire(List.of(attack("skill_a", 1), attack("skill_a", 2)), 35);
        assertEquals(1, ran.size());
        assertEquals(3, ran.get(0).tier(), "max(1 + 1, 2 + 1) = 3 again");
    }

    /**
     * <b>The clamp runs after the maximum, and never invents a rung.</b>
     *
     * <p>Tiers 4 and 5 on a level-95 character ask for {@code max(4+3, 5+3) = 8} on a five-rung ladder, so the
     * answer is 5 — the ladder's top, which is a real ceiling rather than an error.
     */
    @Test
    void theClampRunsAfterTheMaximumNotBefore() {
        List<Ran> ran = fire(List.of(attackOnCrit("skill_a", 4), attack("skill_b", 5)), 95);
        assertEquals(1, ran.size());
        assertEquals(5, ran.get(0).tier(), "8 clamps to the ladder's top of 5");

        // A four-rung ladder where the winning ask is 4+3=7: still the ladder's top, 4.
        List<EffectHook> fourRung = List.of(defenceDown("d1", 3), defenceDown("d2", 4));
        assertEquals(4, EffectCollapse.collapse(fourRung, 95).get(0).tier(),
                "3+3=6 and 4+3=7 both clamp to the four-rung top");
    }

    /**
     * <b>The band is added before the maximum, and the ordering that is actually observable is add-then-clamp.</b>
     *
     * <p>Worth being precise, because the first version of this test asserted the wrong thing:
     * {@code max(a + b, c + b) = max(a, c) + b} <em>identically</em>, so "add before max" and "max before add"
     * cannot be told apart by any input. The order that <b>does</b> change the answer is <b>add before clamp</b>:
     * a level-95 character on a tier-4 buff of a five-rung ladder asks for 7 and gets 5, where clamping first
     * would give {@code min(4, 5) + 3 = 7} — a rung that does not exist. That is what these two assertions pin.
     */
    @Test
    void theBandIsAddedBeforeTheClamp() {
        // 1 + 3 = 4 and 2 + 3 = 5, both inside the five-rung ladder, so nothing clamps and the max is 5.
        List<EffectCollapse.Applied> applied =
                EffectCollapse.collapse(List.of(attack("a", 1), attack("b", 2)), 95);
        assertEquals(1, applied.size());
        assertEquals(5, applied.get(0).tier(), "max(min(1+3,5), min(2+3,5)) = max(4,5) = 5");

        // The discriminating one: 4 + 3 = 7 clamps to 5. Clamp-then-add would answer 7, which the ladder
        // cannot express, and would then be re-clamped by the executor — two answers to "the highest rung".
        assertEquals(5, EffectCollapse.collapse(List.of(attack("a", 4)), 95).get(0).tier(),
                "add then clamp: min(4 + 3, 5) = 5, not 4 + 3 = 7 and not min(4,5) + 3 = 7");
    }

    /** Three skills carrying one effect collapse to one, at the highest of the three. */
    @Test
    void threeSkillsCollapseToOne() {
        List<EffectCollapse.Applied> applied = EffectCollapse.collapse(
                List.of(attack("a", 1), attack("b", 3), attack("c", 2)), 1);
        assertEquals(1, applied.size());
        assertEquals(3, applied.get(0).tier(), "the declared maximum of the three");
    }

    /** The survivor keeps the <b>first</b> position, so every other effect keeps its order. */
    @Test
    void theSurvivorKeepsTheFirstPositionAndTheOthersKeepTheirOrder() {
        List<EffectHook> hooks = List.of(attack("a", 1), damage("b", 5.0), attack("c", 2));
        List<EffectHook> collapsed = EffectCollapse.collapse(hooks, 1).stream()
                .map(EffectCollapse.Applied::hook).toList();
        assertEquals(2, collapsed.size(), "the buff collapsed; the damage did not");
        assertInstanceOf(EffectHook.BuffAttackArea.class, collapsed.get(0),
                "the buff survives in position 0");
        assertInstanceOf(EffectHook.ExtraDamage.class, collapsed.get(1), "the damage keeps position 1");
    }

    // ------------------------------------------------------------------ what must not collapse

    /**
     * <b>A non-tiered effect is never collapsed.</b> Two skills that both deal damage is two hits, which is what
     * the pack means; merging them would halve a character's damage with no line of data saying so.
     */
    @Test
    void nonTieredEffectsAreNeverCollapsed() {
        List<Ran> ran = fire(List.of(damage("a", 5.0), damageOnCrit("b", 7.0)), 95);
        assertEquals(2, ran.size(), "two extra_damage hooks are two hits, not one maximised hit");
        assertEquals(0, ran.get(0).tier(), "a non-tiered hook reports no tier");
    }

    /**
     * <b>Two shields at different reaches are two effects.</b> One is self-only and one covers the party; merging
     * them would silently shrink the party shield to the caster or extend the self one to everybody.
     */
    @Test
    void theSameTypeAtDifferentReachesIsTwoApplications() {
        List<EffectCollapse.Applied> applied =
                EffectCollapse.collapse(List.of(shield("a", 1, 0.0), shield("b", 1, 8.0)), 1);
        assertEquals(2, applied.size(), "radius is part of the effect's identity");
    }

    /** The same reach collapses, so the key is genuinely (type, reach, clock) and not just "type differs". */
    @Test
    void theSameTypeAtTheSameReachCollapses() {
        List<EffectCollapse.Applied> applied =
                EffectCollapse.collapse(List.of(shield("a", 1, 8.0), shield("b", 3, 8.0)), 1);
        assertEquals(1, applied.size());
        assertEquals(3, applied.get(0).tier(), "the higher declared rung wins");
    }

    /** Two <em>different</em> types do not interact, however similar: haste and an attack buff are two buffs. */
    @Test
    void differentTypesDoNotCollapse() {
        // The haste has to be a crit hook too, or the rotation fires only the melee skill and the test would
        // pass for the wrong reason (one skill fired, not "two types survived the collapse").
        EffectHook hasteOnCrit = new EffectHook.Haste(Trigger.CRIT, skill("b"), 1.0,
                TieredParams.ofTier(1));
        assertEquals(2, fire(List.of(attack("a", 1), hasteOnCrit), 95).size(),
                "an attack buff and a haste are two different effects");
    }

    /** An empty or null list is an empty answer, not a throw: this runs on a damage path. */
    @Test
    void anEmptyListAnswersEmpty() {
        assertTrue(EffectCollapse.collapse(List.of(), 95).isEmpty());
        assertTrue(EffectCollapse.collapse(null, 95).isEmpty());
    }

    /** A level the build cannot name reads as the first band, so no band is invented. */
    @Test
    void anUnknownLevelGrantsNoBand() {
        List<EffectCollapse.Applied> applied =
                EffectCollapse.collapse(List.of(attack("a", 2), attack("b", 3)), 0);
        assertEquals(1, applied.size());
        assertEquals(3, applied.get(0).tier(), "no band: the declared maximum stands");
    }

    /** A collapsed application reports how many hooks it stands for, which is what a log line shows. */
    @Test
    void theApplicationCountsItsSources() {
        List<EffectCollapse.Applied> applied =
                EffectCollapse.collapse(List.of(attack("a", 1), attack("b", 2), attack("c", 2)), 1);
        assertEquals(1, applied.size());
        assertEquals(3, applied.get(0).sources());
        assertEquals(1, applied.get(0).declared(), "the survivor's own declaration, not the winner's");
    }

    /** A tie keeps the first hook, so the survivor is stable across runs and log order is reproducible. */
    @Test
    void aTieKeepsTheFirstHook() {
        EffectHook first = attack("a", 2);
        EffectHook second = attack("b", 2);
        List<EffectCollapse.Applied> applied = EffectCollapse.collapse(List.of(first, second), 1);
        assertEquals(1, applied.size());
        assertEquals(first, applied.get(0).hook(), "the first hook of the key survives");
        assertEquals(2, applied.get(0).sources());
    }
}
