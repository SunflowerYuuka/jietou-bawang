package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.combat.AllyMode;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ultimate's damage plan: who it hits, how many times, and who it must never hit.
 *
 * <h2>Why this is a unit test and not only a server run</h2>
 * The gap this class closes was invisible for three tasks: `UbTier` carried {@code hits} and
 * {@code damage}, no code read them, and "the ultimate does 20 damage" was true of no path at all.
 * A number that is never read cannot fail a test that only checks the entity spawns — so the planning
 * half is a pure value with its own assertions, and the server run (see the task report) is the second
 * half of the evidence, not the only half.
 *
 * <h2>What "who" means here, and the one rule this class does not invent</h2>
 * The ally rule — "who may a player's effect reach?" — already exists as
 * {@code impl.combat.AllySelector}, written for {@code area_charge}. It is reused rather than
 * re-written: this project has shipped three bugs from having two answers to one question. Its input
 * space is <b>players</b> (it compares scoreboard team names and has no notion of a mob), so a
 * {@link UbDamage.Target} carries a {@code player} flag and only the player subset is fed through it.
 * Feeding a mob in as if it were a player would make {@link AllyMode#ALL_PLAYERS} — "every other
 * player is an ally" — classify every mob as an ally, and the ultimate would damage nothing.
 */
class UbDamageTest {

    private static final UUID CASTER = new UUID(0L, 99L);

    private static UUID id(long n) {
        return new UUID(0L, n);
    }

    private static UbTier singleTarget(double damage, int hits) {
        return new UbTier("tempest_blade", hits, damage, OptionalDouble.empty(), OptionalDouble.empty(),
                0, OptionalDouble.empty(), Optional.empty());
    }

    private static UbTier area(double damage, int hits, double radius) {
        return new UbTier("seiga_itten", hits, damage, OptionalDouble.of(radius),
                OptionalDouble.empty(), 0, OptionalDouble.empty(), Optional.empty());
    }

    private static UbDamage.Target mob(long n, double distance) {
        return new UbDamage.Target(id(n), false, false, Optional.empty(), distance, false);
    }

    private static UbDamage.Target otherPlayer(long n, double distance, String team) {
        return new UbDamage.Target(id(n), true, false, Optional.ofNullable(team), distance, false);
    }

    private static final UbDamage.Target SELF =
            new UbDamage.Target(CASTER, true, true, Optional.of("red"), 0.0, false);

    private static final UbDamage.Caster ALIVE = new UbDamage.Caster(CASTER, true);
    private static final UbDamage.Caster DEAD = new UbDamage.Caster(CASTER, false);

    // ------------------------------------------------------------------ single target

    /**
     * The shipped base tier: {@code hits: 1, damage: 20.0} and no radius. It picks exactly one
     * victim — not every candidate — and the one application is worth the tier's damage.
     */
    @Test
    void aTierWithoutARadiusPicksExactlyOneTarget() {
        UbDamage.Plan plan = UbDamage.plan(singleTarget(20.0, 1), ALIVE,
                List.of(SELF, mob(1, 2.0), mob(2, 3.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(1)), plan.targets(), "one target, and it is the near one");
        assertEquals(1, plan.hits().size(), "hits: 1 is one application");
        assertEquals(20.0F, plan.hits().get(0).amount(), 1e-6, "the application is worth the tier's damage");
        assertEquals(id(1), plan.hits().get(0).target());
    }

    /** "Nearest" is the rule, not "first in the list": a farther candidate listed first must lose. */
    @Test
    void theNearestCandidateWinsWhateverTheListOrder() {
        UbDamage.Plan plan = UbDamage.plan(singleTarget(20.0, 1), ALIVE,
                List.of(SELF, mob(1, 6.5), mob(2, 1.25), mob(3, 3.0)), AllyMode.ALL_PLAYERS);

        assertEquals(id(2), plan.targets().get(0), "1.25 blocks is nearest");
    }

    /** Nobody in reach: an empty result, no damage, no exception (the caster simply hit air). */
    @Test
    void nobodyInReachIsAnEmptyPlanRatherThanAnError() {
        UbDamage.Plan plan = UbDamage.plan(singleTarget(20.0, 1), ALIVE,
                List.of(SELF, mob(1, UbDamage.SINGLE_TARGET_RANGE + 0.5)), AllyMode.ALL_PLAYERS);

        assertTrue(plan.isEmpty(), "a target beyond the search range must not be picked");
        assertTrue(plan.hits().isEmpty());
    }

    // ------------------------------------------------------------------ radius

    /**
     * The radius form hits <b>every</b> target inside and none outside. The boundary itself is
     * included, which is the same inclusive comparison {@code AllySelector} documents.
     */
    @Test
    void aRadiusHitsEveryoneInsideAndNobodyOutside() {
        UbDamage.Plan plan = UbDamage.plan(area(20.0, 1, 4.0), ALIVE,
                List.of(SELF, mob(1, 0.5), mob(2, 3.99), mob(3, 4.0), mob(4, 4.5), mob(5, 9.0)),
                AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(1), id(2), id(3)), plan.targets(),
                "three inside (4.0 is the boundary and counts), two outside");
        assertEquals(3, plan.hits().size(), "one hit per target for hits: 1");
    }

    /** Radius 0 or a hostile radius reaches nobody — it must not be read as "unlimited". */
    @Test
    void aDegenerateRadiusReachesNobody() {
        assertTrue(UbDamage.plan(area(20.0, 1, 0.0), ALIVE,
                List.of(SELF, mob(1, 0.5)), AllyMode.ALL_PLAYERS).isEmpty(), "radius 0 hits nothing");
        assertTrue(UbDamage.plan(area(20.0, 1, -3.0), ALIVE,
                List.of(SELF, mob(1, 0.5)), AllyMode.ALL_PLAYERS).isEmpty(), "a negative radius cannot mean forever");
        assertTrue(UbDamage.plan(area(20.0, 1, Double.NaN), ALIVE,
                List.of(SELF, mob(1, 0.5)), AllyMode.ALL_PLAYERS).isEmpty(), "NaN compares false, so nothing is in range");
    }

    // ------------------------------------------------------------------ hits

    /**
     * The shipped enhanced tier: {@code hits: 4, damage: 20.0}. Four <b>separate</b> applications of
     * 20 — never one application of 80. PRC's multi-hit ultimates are a sequence, and collapsing them
     * would break every on-hit interaction (iframe windows, per-hit logs, per-hit charge).
     */
    @Test
    void hitsAppliesTheDamageThatManyTimes() {
        UbDamage.Plan plan = UbDamage.plan(area(20.0, 4, 4.0), ALIVE,
                List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS);

        assertEquals(4, plan.hits().size(), "four applications, not one");
        for (UbDamage.Hit hit : plan.hits()) {
            assertEquals(20.0F, hit.amount(), 1e-6, "each application is the tier's damage, not the total");
            assertEquals(id(1), hit.target());
        }
        assertEquals(80.0, plan.total(), 1e-6, "the four applications add up to the tier's hits x damage");
        assertEquals(List.of(0, 1, 2, 3), plan.hits().stream().map(UbDamage.Hit::ordinal).toList(),
                "the ordinals are the sequence, in order");
    }

    /** Two targets and three hits is six applications: every hit reaches every target in the radius. */
    @Test
    void everyHitReachesEveryTargetInTheRadius() {
        UbDamage.Plan plan = UbDamage.plan(area(10.0, 3, 4.0), ALIVE,
                List.of(SELF, mob(1, 1.0), mob(2, 2.0)), AllyMode.ALL_PLAYERS);

        assertEquals(6, plan.hits().size(), "3 hits x 2 targets");
        assertEquals(60.0, plan.total(), 1e-6);
    }

    // ------------------------------------------------------------------ refusals

    /** A dead or removed caster deals nothing: the ultimate belongs to a player who is still here. */
    @Test
    void aDeadCasterDealsNothing() {
        UbDamage.Plan plan = UbDamage.plan(singleTarget(20.0, 1), DEAD,
                List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS);

        assertTrue(plan.isEmpty(), "no caster, no ultimate");
        assertDoesNotThrow(() -> UbDamage.plan(singleTarget(20.0, 1), DEAD,
                List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS));
    }

    /** Every absent input answers "nothing" rather than throwing: this runs inside a cast. */
    @Test
    void missingInputsAnswerAnEmptyPlanInsteadOfThrowing() {
        assertTrue(UbDamage.plan(null, ALIVE, List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(20.0, 1), null, List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(20.0, 1), ALIVE, null, AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(20.0, 1), ALIVE, List.of(), AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(20.0, 1), ALIVE, List.of(SELF), AllyMode.ALL_PLAYERS).isEmpty(),
                "a candidate list holding only the caster hits nobody");
    }

    /** Hostile datapack numbers: zero/negative/NaN damage and zero/negative hits deal nothing. */
    @Test
    void hostileNumbersDealNothing() {
        List<UbDamage.Target> world = List.of(SELF, mob(1, 1.0));
        assertTrue(UbDamage.plan(singleTarget(0.0, 1), ALIVE, world, AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(-20.0, 1), ALIVE, world, AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(Double.NaN, 1), ALIVE, world, AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(20.0, 0), ALIVE, world, AllyMode.ALL_PLAYERS).isEmpty());
        assertTrue(UbDamage.plan(singleTarget(20.0, -2), ALIVE, world, AllyMode.ALL_PLAYERS).isEmpty());
    }

    /**
     * A spectator, and a creative-mode player in flight, are never victims.
     *
     * <p>Both would refuse the damage anyway ({@code Player.abilities.invulnerable}, and
     * {@code isSpectator()} which sets {@code noPhysics}), so the point is <b>not</b> to protect them:
     * it is that the plan and its log line must not claim a hit that cannot land. "4 hits × 20 on a
     * spectator" would read as a bug in every bug report, and a percentage of them would be reported
     * as one.
     */
    @Test
    void spectatorsAndFlyingCreativePlayersAreNeverHit() {
        UbDamage.Target spectator = new UbDamage.Target(id(1), true, false, Optional.empty(), 1.0, true);
        UbDamage.Target creative = new UbDamage.Target(id(2), true, false, Optional.empty(), 1.0, true);
        UbDamage.Target ordinary = new UbDamage.Target(id(3), true, false, Optional.empty(), 1.0, false);

        UbDamage.Plan plan = UbDamage.plan(area(20.0, 1, 8.0), ALIVE,
                List.of(SELF, spectator, creative, ordinary),
                AllyMode.SAME_TEAM_ONLY);   // so that the player half is not spared as allies

        assertEquals(List.of(id(3)), plan.targets(), "only the ordinary player is a victim");
    }

    /** A single-target cast must not have its nearest pick stolen by an untouchable candidate. */
    @Test
    void anUntouchableCandidateDoesNotBecomeTheNearestTarget() {
        UbDamage.Target spectator = new UbDamage.Target(id(1), true, false, Optional.empty(), 0.5, true);

        UbDamage.Plan plan = UbDamage.plan(singleTarget(20.0, 1), ALIVE,
                List.of(SELF, spectator, mob(2, 4.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(2)), plan.targets(), "the mob four blocks away is the real nearest victim");
    }

    // ------------------------------------------------------------------ who is never hit

    /** The caster is never a victim, whatever the candidate list says about them. */
    @Test
    void theCasterIsNeverHit() {
        UbDamage.Plan plan = UbDamage.plan(area(20.0, 2, 8.0), ALIVE,
                List.of(SELF, mob(1, 1.0)), AllyMode.ALL_PLAYERS);

        assertFalse(plan.targets().contains(CASTER), "a self-inflicted ultimate is a bug players would notice");
        assertEquals(List.of(id(1)), plan.targets());
    }

    /**
     * {@link AllyMode#SAME_TEAM_ONLY}: a team-mate is not a victim, a team-less player is, and a mob
     * is always a victim (a mob is never an ally).
     */
    @Test
    void underSameTeamOnlyAPartyMemberIsNotHit() {
        UbDamage.Plan plan = UbDamage.plan(area(20.0, 1, 8.0), ALIVE,
                List.of(SELF, otherPlayer(1, 1.0, "red"), otherPlayer(2, 1.0, null), mob(3, 1.0)),
                AllyMode.SAME_TEAM_ONLY);

        assertEquals(List.of(id(2), id(3)), plan.targets(),
                "the team-mate is spared; the team-less player and the mob are not");
    }

    /**
     * {@link AllyMode#ALL_PLAYERS} — the shipped default — makes <b>every other player</b> an ally, so
     * the ultimate does not damage players at all and still damages mobs. This is the one consequence
     * a reader must not be surprised by: it is what "reuse the existing ally rule" means for a co-op
     * mod, and PvP needs {@code SAME_TEAM_ONLY} (where a team-less opponent is not an ally).
     */
    @Test
    void underAllPlayersNoPlayerIsHitButMobsStillAre() {
        UbDamage.Plan plan = UbDamage.plan(area(20.0, 1, 8.0), ALIVE,
                List.of(SELF, otherPlayer(1, 1.0, null), mob(2, 1.0)), AllyMode.ALL_PLAYERS);

        assertEquals(List.of(id(2)), plan.targets(), "only the mob");
    }

    /** A null mode falls back to the permissive policy, exactly as {@code AllySelector} does. */
    @Test
    void aNullAllyModeFallsBackToThePermissivePolicy() {
        assertDoesNotThrow(() -> UbDamage.plan(area(20.0, 1, 8.0), ALIVE,
                List.of(SELF, mob(1, 1.0)), null));
        assertEquals(List.of(id(1)), UbDamage.plan(area(20.0, 1, 8.0), ALIVE,
                List.of(SELF, mob(1, 1.0)), null).targets());
    }

    // ------------------------------------------------------------------ the search range

    /** A tier without a radius still needs a reach; it is the documented constant, not "everything". */
    @Test
    void aSingleTargetTierSearchesTheDocumentedRange() {
        assertEquals(UbDamage.SINGLE_TARGET_RANGE, UbDamage.range(singleTarget(20.0, 1)), 1e-9);
        assertEquals(4.0, UbDamage.range(area(20.0, 1, 4.0)), 1e-9, "a declared radius is the reach");
    }

    // ------------------------------------------------------------------ the moment

    /**
     * The strike frame is <b>inside</b> the clip, and near its middle.
     *
     * <p>Both shipped cast clips swing the right arm from about -170° to +50° half way through their
     * 1.4 s length, so "half way in" is the moment the animation reads as having landed. What matters
     * for correctness is that it is strictly inside the clip: at {@code 0} the damage would land before
     * the figure has visibly moved, and at {@code >= animationTicks} the despawn would win and the
     * damage would be settled by the interrupted-clip fallback instead of by the swing — the same
     * numbers, but a log line that says the strike never happened.
     */
    @Test
    void theStrikeFrameIsInsideTheClipAndNearItsMiddle() {
        assertEquals(14, UbDamage.strikeTick(28), "the shipped 1.4 s clip strikes at 14 of 28 ticks");
        assertEquals(0, UbDamage.strikeTick(0), "an unknown length strikes at once — there is no clip to wait for");
        assertEquals(0, UbDamage.strikeTick(-5));
        for (int length = 1; length <= 200; length++) {
            int strike = UbDamage.strikeTick(length);
            assertTrue(strike < length,
                    "a " + length + "-tick clip must strike inside itself, got " + strike);
            if (length >= 2) {
                assertTrue(strike >= 1,
                        "a clip of " + length + " ticks must strike after its first frame, got " + strike);
            }
        }
    }

    /** The strike must come before the despawn, or the fallback would settle every cast. */
    @Test
    void theStrikeFramePrecedesTheDespawnForEveryShippedClip() {
        for (String clip : com.astrum.pcrd.impl.showcase.ShowcaseEntity.SHIPPED_CLIPS) {
            int length = com.astrum.pcrd.impl.showcase.ShowcaseManager.castAnimationTicks();
            assertTrue(UbDamage.strikeTick(length)
                            < length + com.astrum.pcrd.impl.showcase.ShowcaseLifetime.DESPAWN_GRACE_TICKS,
                    clip + ": the strike must land while the clip is still playing");
        }
    }

    // ------------------------------------------------------------------ what the plan ignores

    /**
     * {@code selfAtkBuff}, {@code buffTicks} and {@code teamChargeBonus} do not change the damage plan.
     *
     * <p>They are <b>deliberately not implemented</b> (see the task report): a ×1.3 attack multiplier
     * would need a registered {@code MobEffect} carrying a multiplicative
     * {@code ATTACK_DAMAGE} modifier — vanilla's {@code damage_boost} is additive, so reusing it would
     * silently mean "+3 attack" instead of "×1.3" — and {@code teamChargeBonus: 0.2} would mean either
     * 0.2 charge points (invisible next to a 100-point bar) or a new multiplier on every charge path,
     * which is the second multiplier chain this design forbids.
     *
     * <p>This test exists so the gap is <b>stated</b> rather than discovered: a future edit that smuggled
     * a buff into the plan, or one that quietly scaled the damage by it, fails here and has to argue
     * with the report.
     */
    @Test
    void theOptionalTierFieldsDoNotChangeTheDamagePlan() {
        UbTier plain = new UbTier("tempest_blade", 1, 20.0, OptionalDouble.empty(),
                OptionalDouble.empty(), 0, OptionalDouble.empty(), Optional.empty());
        UbTier withBuff = new UbTier("tempest_blade", 1, 20.0, OptionalDouble.empty(),
                OptionalDouble.of(1.3), 360, OptionalDouble.of(0.2), Optional.empty());

        List<UbDamage.Target> world = List.of(SELF, mob(1, 1.0));
        UbDamage.Plan plainPlan = UbDamage.plan(plain, ALIVE, world, AllyMode.ALL_PLAYERS);
        UbDamage.Plan buffedPlan = UbDamage.plan(withBuff, ALIVE, world, AllyMode.ALL_PLAYERS);

        assertEquals(plainPlan.targets(), buffedPlan.targets());
        assertEquals(plainPlan.hits(), buffedPlan.hits(),
                "the buff fields are not part of the damage plan — see the task report for why");
        assertEquals(20.0, buffedPlan.total(), 1e-6, "no buff is baked into the damage");
    }

    // ------------------------------------------------------------------ the recursion fuse

    /**
     * The UB's own damage must be in this mod's namespace, because that namespace <b>is</b> the
     * recursion fuse: {@code CombatEvents} skips hook evaluation for it. If the ultimate dealt damage
     * typed as something else, djeeta's own {@code splash} / {@code area_charge} hooks would fire off
     * the ultimate's damage, which is precisely the "a UB re-triggers itself" loop.
     *
     * <p>The other half — that such damage still earns charge — is asserted too, because gating charge
     * behind the fuse was a real bug once ({@code LandedHit.Plan}).
     */
    @Test
    void theUltimatesDamageIsFusedOutOfHookEvaluationButStillEarnsCharge() {
        assertTrue(com.astrum.pcrd.impl.combat.OwnDamage.isOurs(UbDamage.DAMAGE_TYPE),
                "ub damage must live in the astrum namespace, or the fuse does not recognise it");
        var plan = com.astrum.pcrd.impl.combat.LandedHit.plan(UbDamage.DAMAGE_TYPE);
        assertFalse(plan.runsHooks(), "the ultimate's own damage must not re-enter hook evaluation");
        assertTrue(plan.creditsCharge(), "but it is still damage dealt, so it still fills the bar");
    }
}
