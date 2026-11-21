package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbSegment;
import com.astrum.pcrd.api.v1.UbTargetRule;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.combat.AllyMode;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>An ultimate that declares several damage clauses, one target rule each</b> — the planner's half.
 *
 * <h2>What this class proves, and what it deliberately does not repeat</h2>
 * {@code UbDamageTest} proves the <em>shorthand</em> plan: {@code hits × damage}, on the nearest body or on
 * everything inside a radius, with the ally rule and the reach applied. This class proves the clause list on top
 * of it, and re-states none of those rules: eligibility is {@code UbDamage.eligible}'s single copy, and the
 * caster is never a victim, an untouchable body is never a victim, and an ally is never a victim for every
 * clause here because it is one filter, not five.
 *
 * <h2>Backward compatibility is asserted against the <em>old</em> arithmetic</h2>
 * The first two tests do not compare a plan with another plan — that would pass even if both were wrong. They
 * compare a plan with the numbers this planner produced before segments existed, spelled out by hand:
 * {@code hits} applications of {@code damage} on one body, or {@code hits × (bodies inside the radius)}
 * applications on those bodies.
 */
class UbSegmentPlanTest {

    private static final UUID CASTER = new UUID(0L, 99L);
    private static final UbDamage.Caster ALIVE = new UbDamage.Caster(CASTER, true);

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static UbDamage.Target mob(long n, double distance) {
        return new UbDamage.Target(id(n), false, false, Optional.empty(), distance, false);
    }

    /** A body at a real position, for the one rule that needs geometry. */
    private static UbDamage.Target mob(long n, double distance, double x, double z) {
        return new UbDamage.Target(id(n), false, false, Optional.empty(), distance, false,
                new UbDamage.Point(x, 0.0, z));
    }

    private static final UbDamage.Target SELF =
            new UbDamage.Target(CASTER, true, true, Optional.of("red"), 0.0, false);

    private static UbTier clauses(UbSegment... segments) {
        return new UbTier("ub_cast", List.of(segments), List.of());
    }

    /** A tier with no clauses at all, i.e. the shape every shipped character except three still uses. */
    private static UbTier shorthand(int hits, double damage, OptionalDouble radius) {
        return new UbTier("ub_cast", hits, damage, radius, List.of());
    }

    // ------------------------------------------------------------------ backward compatibility

    /**
     * <b>The shorthand tier still plans exactly what it planned before clauses existed.</b>
     *
     * <p>Hand arithmetic, not a self-comparison: {@code hits: 4, damage: 20.0}, no radius, one body in reach →
     * four applications of 20 on that body, 80 in total, one target. That is the number 姬塔's shipped enhanced
     * tier has always dealt, and the rewritten planner must not move it by a decimal.
     */
    @Test
    void aTierWithoutClausesKeepsTheOldSingleTargetArithmetic() {
        UbDamage.Plan plan = UbDamage.plan(shorthand(4, 20.0, OptionalDouble.empty()), ALIVE,
                List.of(SELF, mob(1, 2.0), mob(2, 5.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(1)), plan.targets(), "the nearest body, and only it");
        assertEquals(4, plan.hits().size(), "hits: 4 is four separate applications");
        assertEquals(List.of(0, 1, 2, 3), plan.hits().stream().map(UbDamage.Hit::ordinal).toList());
        for (UbDamage.Hit hit : plan.hits()) {
            assertEquals(20.0F, hit.amount(), 1e-6, "each application is the tier's damage, not the total");
            assertEquals(id(1), hit.target());
        }
        assertEquals(80.0, plan.total(), 1e-6, "4 × 20");
        assertEquals(1, plan.segments().size(), "the shorthand is ONE clause — that is the whole conversion");
    }

    /**
     * <b>And the shorthand's area form is unchanged too.</b>
     *
     * <p>{@code hits: 2, damage: 5.0, radius: 4.0} with two bodies inside and one outside → two hits on two
     * bodies = four applications, 20 in total, and the body at 5.0 is not among them.
     */
    @Test
    void aTierWithoutClausesKeepsTheOldAreaArithmetic() {
        UbDamage.Plan plan = UbDamage.plan(shorthand(2, 5.0, OptionalDouble.of(4.0)), ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 3.9), mob(3, 5.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(1), id(2)), plan.targets(), "inside the radius, boundary excluded, one outside");
        assertEquals(4, plan.hits().size(), "2 hits × 2 bodies");
        assertEquals(20.0, plan.total(), 1e-6, "2 × 5 × 2");
    }

    // ------------------------------------------------------------------ the two-clause tier

    /**
     * <b>日和莉's base ultimate: the nearest body for one amount, everything in a radius for another.</b>
     *
     * <p>「对最近的敌人造成 10.0」 and 「对范围半径400内全部敌人造成 4.5」 are two numbers on two kinds of receiver.
     * One {@code damage} field could hold one of them; a clause list holds both, and this asserts the split:
     * the near mob takes 10.0 from the first clause and the far mob inside the blast takes 4.5 from the second,
     * while a mob outside both gets nothing.
     */
    @Test
    void aTwoClauseTierHitsTheNearestForOneAmountAndTheRadiusForAnother() {
        UbTier tier = clauses(
                new UbSegment(10.0, 1, UbTargetRule.NEAREST, OptionalDouble.empty()),
                new UbSegment(4.5, 1, UbTargetRule.ALL_IN_RADIUS, OptionalDouble.of(4.0)));

        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 3.5), mob(3, 9.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(1), id(2)), plan.targets(), "the near body twice, the distant one not at all");
        assertEquals(2, plan.segments().size());
        assertEquals(List.of(id(1)), plan.segments().get(0).targets(), "clause 1 is the nearest body");
        assertEquals(List.of(id(1), id(2)), plan.segments().get(1).targets(),
                "clause 2 is everything inside four blocks — including the body clause 1 already hit");

        assertEquals(3, plan.hits().size(), "one application for the near body twice, one for the blast");
        assertEquals(10.0F, amountOn(plan, id(1), 0), 1e-6, "clause 1: 10.0 on the nearest");
        assertEquals(4.5F, amountOn(plan, id(1), 1), 1e-6, "clause 2: 4.5 on the same body");
        assertEquals(4.5F, amountOn(plan, id(2), 1), 1e-6, "clause 2: 4.5 on the body only it reached");
        assertEquals(19.0, plan.total(), 1e-6, "10.0 + 4.5 + 4.5");
    }

    /** The caster is never a victim of any clause, not only of the first: eligibility is one filter. */
    @Test
    void noClauseEverHitsTheCaster() {
        UbTier tier = clauses(
                new UbSegment(10.0, 1, UbTargetRule.NEAREST, OptionalDouble.empty()),
                new UbSegment(4.5, 1, UbTargetRule.ALL_IN_RADIUS, OptionalDouble.of(9.0)));
        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE, List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS);

        assertFalse(plan.targets().contains(CASTER), "a self-inflicted ultimate is a bug players would notice");
    }

    // ------------------------------------------------------------------ the rank rules

    /**
     * <b>{@link UbTargetRule#SECOND_NEAREST} skips the nearest body and picks the next one.</b>
     *
     * <p>美美's 「对第二近的一名敌人造成 15.0」. Three bodies at 1.0 / 3.0 / 5.0: the nearest is 1.0, so the
     * clause must land on 3.0 — and only there.
     */
    @Test
    void secondNearestSkipsTheNearestBody() {
        UbTier tier = clauses(
                new UbSegment(15.0, 1, UbTargetRule.SECOND_NEAREST, OptionalDouble.empty()));
        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 3.0), mob(3, 5.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(2)), plan.targets(), "the second nearest, not the first and not the third");
        assertEquals(15.0F, plan.hits().get(0).amount(), 1e-6);
    }

    /**
     * <b>A pair of clauses reads like the clause does: nearest 7.8 <em>and</em> second-nearest 15.0.</b>
     *
     * <p>美美's base ultimate, whose two numbers could not coexist before: one body takes the small number and
     * the body beside it takes the large one.
     */
    @Test
    void nearestAndSecondNearestAreTwoClausesOnTwoBodies() {
        UbTier tier = clauses(
                new UbSegment(7.8, 1, UbTargetRule.NEAREST, OptionalDouble.empty()),
                new UbSegment(15.0, 1, UbTargetRule.SECOND_NEAREST, OptionalDouble.empty()));
        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 3.0)), AllyMode.ALL_PLAYERS);

        assertEquals(7.8F, amountOn(plan, id(1), 0), 1e-6, "clause 1 on the nearest");
        assertEquals(15.0F, amountOn(plan, id(2), 1), 1e-6, "clause 2 on the second nearest");
        assertEquals(2, plan.hits().size());
    }

    /** 「第二近」 of one body is nobody: the clause contributes nothing rather than hitting the nearest twice. */
    @Test
    void secondNearestOfASingleBodyHitsNobody() {
        UbTier tier = clauses(
                new UbSegment(15.0, 1, UbTargetRule.SECOND_NEAREST, OptionalDouble.empty()));
        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE, List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS);

        assertTrue(plan.isEmpty(), "there is no second body, so there is no application");
    }

    /**
     * <b>{@link UbTargetRule#FARTHEST} picks the distant one</b> — 紬希's 「将最远的敌人拉近 300」 needs to know
     * <em>which</em> body that is, and before this rule existed the pull could only be applied to all of them.
     */
    @Test
    void farthestPicksTheDistantBody() {
        UbTier tier = clauses(
                new UbSegment(6.0, 1, UbTargetRule.FARTHEST, OptionalDouble.empty()));
        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 5.5), mob(3, 3.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(2)), plan.targets(), "5.5 blocks is the farthest of the three");
        assertEquals(6.0F, plan.hits().get(0).amount(), 1e-6);
    }

    /** A rank rule with a radius ranks only the bodies inside it: 「半径 3 内最近的」, not 「最近的」. */
    @Test
    void aRankInsideARadiusIgnoresBodiesOutsideIt() {
        UbTier tier = clauses(
                new UbSegment(9.0, 1, UbTargetRule.NEAREST, OptionalDouble.of(3.0)));
        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 0.5), mob(2, 2.5), mob(3, 5.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(1)), plan.targets());
        UbDamage.Plan narrow = UbDamage.plan(clauses(
                        new UbSegment(9.0, 1, UbTargetRule.NEAREST, OptionalDouble.of(0.4))),
                ALIVE, List.of(SELF, mob(1, 0.5), mob(2, 2.5)), AllyMode.ALL_PLAYERS);
        assertTrue(narrow.isEmpty(), "a radius that holds nobody reaches nobody");
    }

    /**
     * <b>The arena clause's centre is another body.</b>
     *
     * <p>美美's 6★ 「以前方第2名角色为中心、对范围275内的敌人」: the second-nearest body is the centre, and the
     * blast is measured from <em>there</em>. The layout below puts a third body beside the second one (2.0
     * blocks away) and a fourth next to nobody — a radius from the caster would have caught the wrong pair.
     */
    @Test
    void aroundSecondNearestCentresItsBlastOnThatBody() {
        UbTier tier = clauses(
                new UbSegment(18.3, 1, UbTargetRule.AROUND_SECOND_NEAREST, OptionalDouble.of(2.75)));
        // caster at the origin; body 1 is the nearest (1 block, off the x axis), body 2 is the anchor
        // (3 blocks, at x=3), body 3 is 2 blocks from the anchor (x=5) and body 4 is 3.5 blocks out (x=6.5).
        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 1.0, 0.0, 1.0), mob(2, 3.0, 3.0, 0.0), mob(3, 5.0, 5.0, 0.0),
                        mob(4, 6.5, 6.5, 0.0)),
                AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(2), id(3)), plan.targets(),
                "the anchor and the body beside it — not the nearest body, not the one 3.5 blocks out");
        assertEquals(2, plan.hits().size(), "one hit each, and nothing for the bodies outside");
    }

    /** Fewer than two bodies means no anchor, so the clause selects nobody rather than guessing a centre. */
    @Test
    void aroundSecondNearestNeedsTwoBodies() {
        UbTier tier = clauses(
                new UbSegment(18.3, 1, UbTargetRule.AROUND_SECOND_NEAREST, OptionalDouble.of(2.75)));
        assertTrue(UbDamage.plan(tier, ALIVE, List.of(SELF, mob(1, 1.0, 1.0, 0.0)),
                AllyMode.ALL_PLAYERS).isEmpty());
    }

    // ------------------------------------------------------------------ the target-count scaling

    /**
     * <b>「伤害随目标数量提升」: the amount grows by one term per extra body the <em>clause</em> hits.</b>
     *
     * <p>紬希's 6★ is {@code +0.5×物理攻击力×目标数量}, i.e. {@code 9.2 + 0.9 × n} in this mod's units, which is
     * the authored {@code 10.1} at one body plus {@code 0.9} for each further one. Three bodies in the blast
     * therefore take {@code 10.1 + 0.9 × 2 = 11.9} each — the same number on every body, not a budget split
     * between them.
     */
    @Test
    void theTargetCountScalingAddsPerExtraTarget() {
        UbSegment blast = new UbSegment(OptionalDouble.of(10.1), 1, UbTargetRule.ALL_IN_RADIUS,
                OptionalDouble.of(6.0), List.of(), Optional.empty(), OptionalDouble.empty(), 0.9, false,
                OptionalDouble.empty());
        UbTier tier = clauses(blast);

        UbDamage.Plan one = UbDamage.plan(tier, ALIVE, List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS);
        UbDamage.Plan three = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 2.0), mob(3, 3.0)), AllyMode.ALL_PLAYERS);

        assertEquals(10.1F, one.hits().get(0).amount(), 1e-5, "one body: the authored amount, no scaling");
        assertEquals(3, three.hits().size());
        for (UbDamage.Hit hit : three.hits()) {
            assertEquals(11.9F, hit.amount(), 1e-5, "10.1 + 0.9 × (3 - 1)");
        }
        assertEquals(35.7, three.total(), 1e-5, "3 × 11.9");
    }

    /**
     * <b>And it stays the clause's own number.</b>
     *
     * <p>A second clause in the same tier does not scale with the first one's receiver count: 日和莉's nearest
     * strike is one body and its amount must not grow because her blast found two. This is why the scaling is a
     * field of {@link UbSegment} and not of {@link UbTier}.
     */
    @Test
    void theScalingDoesNotLeakIntoTheOtherClause() {
        UbSegment strike = new UbSegment(10.0, 1, UbTargetRule.NEAREST, OptionalDouble.empty());
        UbSegment blast = new UbSegment(OptionalDouble.of(10.1), 1, UbTargetRule.ALL_IN_RADIUS,
                OptionalDouble.of(6.0), List.of(), Optional.empty(), OptionalDouble.empty(), 0.9, false,
                OptionalDouble.empty());
        UbDamage.Plan plan = UbDamage.plan(clauses(strike, blast), ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 2.0), mob(3, 3.0)), AllyMode.ALL_PLAYERS);

        assertEquals(10.0F, amountOn(plan, id(1), 0), 1e-5,
                "the nearest-strike clause hits one body, so it adds nothing");
        assertEquals(11.9F, amountOn(plan, id(1), 1), 1e-5, "the blast's own count is three");
    }

    // ------------------------------------------------------------------ the effect clause

    /**
     * <b>A clause with no damage still selects its bodies.</b>
     *
     * <p>紬希's 「将最远的敌人拉近 300」 states a distance, not a number, so it is an effect clause: it must appear
     * in the plan with the <em>farthest</em> body in its target list, and contribute no application — otherwise
     * the settlement would have nothing to apply the pull to, or would apply it to everybody the blast hit,
     * which is what the data did before clauses existed.
     */
    @Test
    void anEffectClauseSelectsItsBodyAndDealsNothing() {
        UbEffect pull = new UbEffect(UbEffect.Scope.VICTIM,
                com.astrum.pcrd.api.v1.EffectType.PULL, TieredParams.ofTier(3));
        UbTier tier = new UbTier("ub_cast", List.of(
                new UbSegment(6.0, 1, UbTargetRule.ALL_IN_RADIUS, OptionalDouble.of(6.0)),
                new UbSegment(UbTargetRule.FARTHEST, OptionalDouble.empty(), List.of(pull))),
                List.of());

        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 5.0)), AllyMode.ALL_PLAYERS);

        assertEquals(2, plan.segments().size(), "both clauses are in the plan");
        assertEquals(List.of(id(2)), plan.segments().get(1).targets(), "the effect clause chose the farthest body");
        assertTrue(plan.segments().get(1).effectOnly(), "and it is flagged as dealing no damage");
        assertEquals(2, plan.hits().size(), "only the damage clause applied anything");
        assertEquals(12.0, plan.total(), 1e-6, "6.0 on each of the two bodies");
    }

    /** A clause that neither deals damage nor carries effects is not in the plan at all. */
    @Test
    void anEmptyClauseContributesNothing() {
        UbTier tier = clauses(new UbSegment(OptionalDouble.empty(), 1, UbTargetRule.NEAREST,
                OptionalDouble.empty(), List.of(), Optional.empty(), OptionalDouble.empty(), 0.0, false,
                OptionalDouble.empty()));

        assertTrue(UbDamage.plan(tier, ALIVE, List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS).isEmpty());
    }

    // ------------------------------------------------------------------ hostile numbers, per clause

    /**
     * One hostile clause must not cost the others: a blast with a {@code NaN} radius reaches nobody, and the
     * nearest-target clause beside it still fires.
     */
    @Test
    void oneUnusableClauseDoesNotSilenceItsNeighbours() {
        UbTier tier = clauses(
                new UbSegment(10.0, 1, UbTargetRule.NEAREST, OptionalDouble.empty()),
                new UbSegment(4.5, 1, UbTargetRule.ALL_IN_RADIUS, OptionalDouble.of(Double.NaN)));

        UbDamage.Plan plan = UbDamage.plan(tier, ALIVE, List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS);

        assertEquals(1, plan.hits().size(), "the nearest-target clause still lands");
        assertEquals(10.0F, plan.hits().get(0).amount(), 1e-6);
    }

    /** The reach of the whole tier is its widest clause, or a narrow one would drop the wide one's victims. */
    @Test
    void theTiersReachIsItsWidestClause() {
        UbTier tier = clauses(
                new UbSegment(10.0, 1, UbTargetRule.NEAREST, OptionalDouble.empty()),
                new UbSegment(4.5, 1, UbTargetRule.ALL_IN_RADIUS, OptionalDouble.of(20.0)));

        assertEquals(20.0, UbDamage.range(tier), 1e-9);
        assertEquals(UbDamage.SINGLE_TARGET_RANGE,
                UbDamage.range(shorthand(1, 20.0, OptionalDouble.empty())), 1e-9,
                "a shorthand tier with no radius keeps the documented six-block reach");
        assertEquals(4.0, UbDamage.range(shorthand(1, 20.0, OptionalDouble.of(4.0))), 1e-9,
                "and one with a radius keeps the radius");
    }

    /** {@return the amount of {@code target}'s application from clause {@code segment}} */
    private static float amountOn(UbDamage.Plan plan, UUID target, int segment) {
        for (UbDamage.Hit hit : plan.hits()) {
            if (hit.target().equals(target) && hit.segment() == segment) {
                return hit.amount();
            }
        }
        throw new AssertionError("no hit on " + target + " from clause " + segment + " in " + plan.hits());
    }
}
