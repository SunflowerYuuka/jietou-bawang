package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.UbSegment;
import com.astrum.pcrd.api.v1.UbTargetRule;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The ultimate's damage, as a plan: <b>who</b> it hits, in <b>which order</b>, for <b>how much</b>.
 *
 * <h2>The gap this closes</h2>
 * {@link UbTier} has always carried {@code hits}, {@code damage} and {@code radius}, and the shipped
 * {@code djeeta.json} fills all three in. No code read them: pressing the cast key played an animation
 * and spent the bar, and the world did not change. "The ultimate does 20 damage" was true of no path.
 *
 * <h2>Why the ultimate settles its own damage</h2>
 * The effect pipeline ({@code HookEvaluator} / {@code EffectExecutors}) is driven by <b>hooks with
 * triggers</b>. An ultimate is not a hook: it has no trigger, it fires on a player's key press, and it
 * is not one of five simultaneous wheel slots. Putting its damage there would mean inventing a fake
 * trigger, so this class is where the numbers become damage.
 *
 * <h2>No second multiplier chain (deliberate)</h2>
 * {@code MultiplierChain.effective} is the single composition point for <b>hook</b> multipliers:
 * {@code diminishing(slot) × level(character) × affectionHook(character, hookIndex)}. This class calls
 * neither it nor any copy of it, on purpose:
 *
 * <ol>
 *   <li>Spec §5.4: 递减 <b>不适用</b> to the ultimate — 一次只能放一个，天然互斥, so the per-slot
 *       diminishing factor has no meaning here. Composing it would apply a penalty the spec removes.</li>
 *   <li>{@code effective} needs a <b>hook index</b> for the affection term, and the ultimate has no
 *       hook index. Supplying one (0, say) would silently inherit
 *       {@code tier2HookModifiers[hookIndex=0]} — djeeta's own data lists {@code hookIndex: 1}, so a
 *       guess here would be wrong, invisible, and would scale an ultimate that the tier list has no
 *       entry for.</li>
 *   <li>Growth still reaches the ultimate the way §6.2 says it does: by <b>swapping the tier</b>
 *       ({@code UbService.isEnhanced} → {@code ub.enhanced}), not by multiplying the base tier's
 *       damage. The datapack owns the two absolute numbers.</li>
 * </ol>
 *
 * <p>So the tier's {@code damage} is applied verbatim. The one number that is not verbatim is the
 * <b>count</b>: {@code hits} applications, because PRC's multi-hit ultimates are a sequence.
 *
 * <h2>Each clause picks its own receivers — and none of them re-decides who may be hit</h2>
 * An ultimate's text is a list of clauses, and they do not share a receiver: 日和莉 strikes the nearest body
 * for 10.0 <em>and</em> everything inside four blocks for 4.5, 美美 strikes the second-nearest for 15.0, and
 * 紬希 pulls the <b>farthest</b> body while knocking the <b>nearest</b> one away. So the plan is built clause by
 * clause out of {@link UbSegment}s, and each clause's
 * {@link com.astrum.pcrd.api.v1.UbTargetRule} decides only <em>which of the eligible bodies</em> it lands on.
 *
 * <p>The eligibility rule stays a single copy — {@link #eligible}, unchanged since this class was written: not
 * the caster, not an untouchable body, not out of reach, and not a player the ally policy calls an ally. A rank
 * rule ranks that list by distance, an area rule filters it by a radius, and the one rule centred on another
 * body ({@link com.astrum.pcrd.api.v1.UbTargetRule#AROUND_SECOND_NEAREST}) uses the positions the candidates
 * carry. No clause has an opinion about who may be hit, which is the property that keeps 绝不打自己 and
 * 不伤队友 true for every clause a pack can write.
 *
 * <p>A tier that declares no clauses reaches this class as <b>one</b> implicit clause
 * ({@code UbTier.damageSegments()}): the nearest body, or everything inside the radius, for
 * {@code hits × damage}. That is the whole of the backward-compatibility story — one plan path, not two.
 *
 * <h2>The ally rule is the existing one, not a fourth copy</h2>
 * "Who may a player's effect reach?" already has an answer: {@link AllySelector}, written for
 * {@code area_charge} (spec §7.4) precisely because {@code isAlliedTo()} answers {@code false} when
 * either side has no scoreboard team — which would make every team-less LAN partner a valid target.
 * This project has shipped three bugs from having two answers to one question, so the player half of
 * the candidate list is fed through that class rather than re-derived here.
 *
 * <p>Its input space is <b>players</b>: it compares team names and has no notion of a mob. Handing it
 * a mob as if it were a player would make {@link AllyMode#ALL_PLAYERS} — "every other player is an
 * ally" — classify every mob as an ally, and the ultimate would damage nothing at all. So
 * {@link Target#player()} says which half a candidate belongs to, and only the player half is
 * filtered by the ally policy. A mob is never an ally.
 *
 * <h2>What this class does not do</h2>
 * No {@code Level}, no {@code Entity}, no {@code DamageSource}, no config read. It turns a tier plus a
 * prepared candidate list into a value, so every rule above is one unit test away
 * ({@code UbDamageTest}) and the game-bound half is {@link UbDamageSettlement}'s.
 */
@ApiStatus.Internal
public final class UbDamage {

    /**
     * The damage type the ultimate uses — in this mod's namespace, which <b>is</b> the recursion fuse.
     *
     * <p>{@code OwnDamage.isOurs} answers by namespace, and {@code LandedHit.plan} turns that into
     * "credits charge, does not run hooks". So the ultimate's own damage fills the bar (spec §5.4
     * counts 造成伤害 with no carve-out) and never re-enters hook evaluation — which is exactly why
     * djeeta's {@code splash} / {@code area_charge} cannot be triggered by her own ultimate.
     *
     * <p>Its own type rather than {@code hook_damage} for one reason: the death message. A player
     * killed by an ultimate should read "cut down by the ultimate", not "cut down by attachment
     * damage". The type is declared in {@code data/astrum/damage_type/ub_damage.json} and tagged
     * {@code minecraft:bypasses_cooldown} — <b>without that tag a multi-hit ultimate deals one hit's
     * worth</b>, because the 2nd..Nth {@code hurt()} calls are swallowed by the victim's
     * post-hit invulnerability window (measured: 4 × 20 dealt 20).
     */
    public static final ResourceLocation DAMAGE_TYPE = InternalIds.id("ub_damage");

    /**
     * How far a tier <em>without</em> a radius reaches, in blocks.
     *
     * <p>A single-target ultimate still needs a reach, or "the nearest target" would mean "anything
     * anywhere in the level" — a mob 200 blocks away would be hit through terrain. Six blocks is a
     * little beyond a player's melee reach (~3) and inside the shipped {@code area_charge} radius (8),
     * so an ultimate cast in a fight lands on the thing in front of you. It is a constant rather than
     * a config key because spec §8.2's tables have no key for it and {@code ConfigDefaultsTest} pins
     * the file's exact key set.
     */
    public static final double SINGLE_TARGET_RANGE = 6.0;

    /**
     * Where in the clip the strike lands, as a fraction of its length.
     *
     * <p>Both shipped cast clips strike at about half their length — {@code tempest_blade} swings
     * from -170° at 0.3 s to +50° at 0.75 s of a 1.4 s clip, {@code ub_cast} the same at 0.7 s — so
     * "half way in" is where the animation reads as having landed, for both tiers and for the generic
     * fallback. A per-tier strike frame would be new datapack machinery ({@code UbTier} has no such
     * field) and the spec's schema is closed, so the fraction is the honest approximation.
     */
    public static final double STRIKE_FRACTION = 0.5;

    private UbDamage() {
    }

    /**
     * The caster, as the plan needs them.
     *
     * @param id    the caster's UUID
     * @param alive whether they are still alive and present; {@code false} deals nothing
     */
    public record Caster(UUID id, boolean alive) {
    }

    /**
     * One candidate victim, as the plan sees it — gathered by the game-bound half.
     *
     * @param id        the entity's UUID
     * @param player    whether it is a player (the only kind the ally policy governs)
     * @param self      whether this candidate is the caster
     * @param teamName  the player's scoreboard team, empty when they have none
     * @param distance  blocks from the caster; {@code 0.0} for the caster
     * @param untouchable whether the game would refuse the damage outright — a spectator, or a
     *                    creative-mode player in flight ({@code abilities.invulnerable}). Such a
     *                    candidate is dropped before the target selection, so a plan never claims a
     *                    hit that cannot land and the log line never reports one
     */
    public record Target(UUID id, boolean player, boolean self, Optional<String> teamName,
                         double distance, boolean untouchable, Point position) {

        /** Normalises a {@code null} team to empty, so the ally kernel never sees a null. */
        public Target {
            teamName = teamName == null ? Optional.empty() : teamName;
            position = position == null ? new Point(distance, 0.0, 0.0) : position;
        }

        /**
         * A candidate whose position does not matter, laid on the caster's +X axis at {@code distance}.
         *
         * <p>{@link Point} is read by <b>one</b> rule — a clause centred on another body
         * ({@link com.astrum.pcrd.api.v1.UbTargetRule#AROUND_SECOND_NEAREST}) — because a distance from the
         * caster cannot say how far one candidate is from another. A plan whose clauses are all
         * caster-relative never looks at it, so a caller (or a test) that only knows the distances may leave
         * it out; the synthetic point keeps every body on one ray, which is the layout a distance-only
         * candidate list describes. A clause that <em>does</em> need geometry must say where its bodies are.
         */
        public Target(UUID id, boolean player, boolean self, Optional<String> teamName,
                      double distance, boolean untouchable) {
            this(id, player, self, teamName, distance, untouchable, new Point(distance, 0.0, 0.0));
        }
    }

    /**
     * Where a candidate is, in blocks — the world coordinates the game-bound half read off the entity.
     *
     * <p>This class stays game-free (no {@code Vec3}, no {@code Level}, no {@code Entity}) because its whole
     * rule set has to be assertable without a server; a bare triple of doubles is the smallest thing that
     * lets one clause ask "how far is that body from <em>this</em> body". Distances between two of these are
     * Euclidean, the same measure {@code distanceToSqr} gives.
     */
    public record Point(double x, double y, double z) {

        /** {@return the distance from this point to {@code other}; {@code NaN} when either is not finite} */
        public double distanceTo(Point other) {
            if (other == null) {
                return Double.NaN;
            }
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * One application of damage.
     *
     * @param target  who takes it
     * @param ordinal which hit of <em>its own clause's</em> sequence it is, from {@code 0} — kept because "four
     *                separate applications" is the design, not an implementation detail
     * @param amount  damage for this application, target-count scaling already applied
     * @param segment which of {@link Plan#segments()} produced it, as an index. The settlement needs it for the
     *                two facts that are per clause rather than per tier: the damage type the application is
     *                filed under, and the crit multiplier it uses. Before segments existed there was one clause
     *                and both came off the tier; now the tier's values travel <b>on</b> the clause
     *                ({@code UbTier.damageSegments()}), so there is still exactly one place to read them
     */
    public record Hit(UUID target, int ordinal, float amount, int segment) {
    }

    /**
     * One clause of the plan: the segment that fired, and <b>the bodies its own rule selected</b>.
     *
     * <p>Carried separately from the hits because a clause need not deal damage at all: 「将最远的敌人拉近
     * 300」 selects a body and moves it, and contributing no {@link Hit} is the honest representation of a
     * clause whose source states a distance rather than a number. The settlement applies such a clause's
     * effects to {@link #targets()} directly, after the damage of the clauses that do deal it.
     *
     * @param segment the clause, as the pack wrote it
     * @param targets who it selected, in the order the candidates were given
     */
    public record Strike(UbSegment segment, List<UUID> targets) {

        public Strike {
            targets = List.copyOf(targets);
        }

        /** {@return whether this clause contributes no damage — an effect clause, see the record javadoc} */
        public boolean effectOnly() {
            return !segment.dealsDamage();
        }
    }

    /**
     * The whole plan for one cast.
     *
     * @param targets  every body the cast touches, in the order the candidates were given and without
     *                 duplicates — one entry for a single-target tier, one per distinct victim for a tier with
     *                 clauses
     * @param hits     every application, in clause order and then strike order: all targets of hit 0, then all
     *                 of hit 1, … of the first clause, then the next clause's
     * @param segments one entry per clause that selected anybody, in the tier's declaration order — including
     *                 the clauses that deal no damage, which is what lets the settlement move the bodies an
     *                 effect clause chose
     */
    public record Plan(List<UUID> targets, List<Hit> hits, List<Strike> segments) {

        public Plan {
            targets = List.copyOf(targets);
            hits = List.copyOf(hits);
            segments = List.copyOf(segments);
        }

        /** The empty plan's shape, for {@link #NOTHING}: two lists and no clause. */
        public Plan(List<UUID> targets, List<Hit> hits) {
            this(targets, hits, List.of());
        }

        /** {@return whether this cast hits nothing at all} */
        public boolean isEmpty() {
            return hits.isEmpty();
        }

        /** {@return the sum of every application, for the log line and for tests} */
        public double total() {
            double total = 0.0;
            for (Hit hit : hits) {
                total += hit.amount();
            }
            return total;
        }
    }

    /** The plan of a cast that hits nothing: one value, so callers compare instead of re-deriving. */
    public static final Plan NOTHING = new Plan(List.of(), List.of(), List.of());

    /**
     * {@return how far {@code tier} needs the world searched, in blocks — the furthest reach any of its
     * clauses has}
     *
     * <h2>Why the <em>widest</em> clause answers for the whole tier</h2>
     * The game-bound half inflates one candidate box by this number before the plan exists, so it has to cover
     * the clause that reaches furthest — 日和莉 casts on the nearest body for 10.0 <em>and</em> on everything
     * inside 4 blocks for 4.5, and a box built from the shorter clause would quietly drop the longer one's
     * victims. Each clause is then filtered by its <b>own</b> reach inside {@link #plan} — this number never
     * decides who a clause hits, only who is worth looking at.
     *
     * <p>A clause with no radius reaches {@link #SINGLE_TARGET_RANGE} (six blocks, the documented single-target
     * reach), and a clause whose radius is another body's
     * ({@link com.astrum.pcrd.api.v1.UbTargetRule#centredOnAnotherBody()}) can reach that body <em>plus</em> its
     * radius, since the anchor itself is up to a single-target reach away.
     *
     * <p>Unusable reaches (0, negative, {@code NaN}) contribute nothing rather than poisoning the maximum: one
     * hostile radius in one clause must not make the other clauses reach nowhere. A tier whose <em>every</em>
     * clause is unusable therefore answers {@code 0.0}, and {@link #plan} then selects nobody — the same
     * "a degenerate radius reaches nobody, never everyone" rule the single-clause planner had.
     *
     * @param tier the tier that fired
     */
    public static double range(UbTier tier) {
        if (tier == null) {
            return SINGLE_TARGET_RANGE;
        }
        double reach = 0.0;
        for (UbSegment segment : tier.damageSegments()) {
            double own = reachOf(segment);
            if (Double.isFinite(own) && own > reach) {
                reach = own;
            }
        }
        return reach;
    }

    /**
     * {@return how far from the caster one clause can reach, in blocks}
     *
     * <p>Its radius when it declares one, {@link #SINGLE_TARGET_RANGE} when it does not — plus, for a clause
     * centred on another body, that body's own reach: the anchor can be a single-target reach away, so the
     * clause's outermost victim can be {@code SINGLE_TARGET_RANGE + radius} from the caster.
     */
    private static double reachOf(UbSegment segment) {
        if (segment == null) {
            return 0.0;
        }
        if (segment.radius().isEmpty()) {
            return SINGLE_TARGET_RANGE;
        }
        double radius = segment.radius().getAsDouble();
        boolean centred = segment.target() != null && segment.target().centredOnAnotherBody();
        return centred ? SINGLE_TARGET_RANGE + radius : radius;
    }

    /**
     * {@return how far from the caster one clause takes its <b>victims</b> from}
     *
     * <p>The same number as {@link #reachOf} except for the clause centred on another body, whose radius
     * measures from that body and not from the caster: its victims are chosen within a single-target reach of
     * her, and then within its radius of the anchor. The distinction is one line here instead of a comment at
     * two call sites, and it is why a two-number clause (mimi 6★: 6 blocks around her, then 2.75 around the
     * second body) does not have to pretend the second radius is measured from somewhere it is not.
     */
    private static double victimReachOf(UbSegment segment) {
        if (segment == null) {
            return 0.0;
        }
        boolean centred = segment.target() != null && segment.target().centredOnAnotherBody();
        if (centred) {
            return SINGLE_TARGET_RANGE;
        }
        return segment.radius().isPresent() ? segment.radius().getAsDouble() : SINGLE_TARGET_RANGE;
    }

    /**
     * {@return the tick, from the entity's spawn, at which the animation reads as having landed}
     *
     * <p>Always strictly inside the clip: a strike at {@code 0} would deal its damage before the figure
     * has visibly moved, and one at {@code >= animationTicks} would be settled by the interrupted-clip
     * fallback rather than by the swing. A one-tick clip therefore strikes on its only frame.
     *
     * @param animationTicks the clip's length in ticks; {@code <= 0} means "unknown — strike at once"
     */
    public static int strikeTick(int animationTicks) {
        if (animationTicks <= 0) {
            return 0;
        }
        int strike = (int) Math.round(animationTicks * STRIKE_FRACTION);
        return Math.max(0, Math.min(strike, animationTicks - 1));
    }

    /**
     * {@return the damage plan for one cast}
     *
     * <h2>One loop over the tier's clauses, and no second copy of "who may I hit"</h2>
     * {@link #eligible} answers "who may this player's ultimate damage" <b>once</b> — the caster is out, the
     * untouchable are out, the out-of-reach are out, and a player the ally policy calls an ally is out. Each
     * clause then only <em>chooses</em> among those bodies ({@link #select}), which is the whole point of
     * {@link com.astrum.pcrd.api.v1.UbTargetRule}: five receivers, one eligibility rule. A second "who may I
     * hit" written inside the clause loop is the bug this design exists to prevent.
     *
     * <p>A tier that lists no clauses reaches here as <b>one</b> clause ({@code UbTier.damageSegments()}): the
     * nearest body, or everything inside the radius, for {@code hits × damage}. Same numbers, same order, same
     * plan — the shipped characters cannot tell this method was rewritten.
     *
     * <p>Never throws and never returns {@code null}: a {@code null} tier, caster or candidate list, a dead
     * caster, a hostile number in the data, or a target list holding only the caster all answer
     * {@link #NOTHING}. This runs on the cast path, where the player's gauge has already been spent, so an
     * exception here would turn a completed cast into a reported failure.
     *
     * @param tier       the tier that fired ({@code ub.base()} or {@code ub.enhanced()})
     * @param caster     the player whose ultimate fired
     * @param candidates every entity the game-bound half considers, <b>including</b> the caster (the ally
     *                   policy needs the caster's own team to compare against)
     * @param mode       the configured ally policy; {@code null} means {@link AllyMode#ALL_PLAYERS}
     */
    public static Plan plan(UbTier tier, Caster caster, List<Target> candidates, AllyMode mode) {
        if (tier == null || caster == null || caster.id() == null || !caster.alive()
                || candidates == null) {
            return NOTHING;
        }
        List<UbSegment> clauses = tier.damageSegments();
        if (clauses.isEmpty()) {
            return NOTHING;
        }
        List<UUID> eligible = eligible(candidates, caster, mode, range(tier));
        if (eligible.isEmpty()) {
            return NOTHING;
        }
        Map<UUID, Target> byId = byId(candidates, caster);
        List<Strike> strikes = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        List<UUID> targets = new ArrayList<>();
        for (UbSegment clause : clauses) {
            if (clause == null) {
                continue;
            }
            // A clause that neither deals damage nor carries effects is nothing at all. That is what keeps the
            // implicit clause of a damage-less tier (千歌's heal-only ultimate) selecting nobody, which is
            // exactly what this planner did when `damage: 0.0` meant "no plan".
            boolean damage = clause.dealsDamage();
            if (!damage && !clause.carriesEffects()) {
                continue;
            }
            List<UUID> selected = select(clause, byId, eligible);
            if (selected.isEmpty()) {
                continue;
            }
            strikes.add(new Strike(clause, selected));
            int index = strikes.size() - 1;
            for (UUID id : selected) {
                if (!targets.contains(id)) {
                    targets.add(id);   // 一个人被两条子句打中，只算一个受害目标
                }
            }
            if (!damage) {
                continue;   // 「将最远的敌人拉近」: moves a body, deals no damage of its own
            }
            // The target-count scaling, applied once per clause and to its own receiver count: see
            // UbSegment#amountFor and perExtraTarget for why it is the clause's and not the tier's.
            float amount = (float) clause.amountFor(selected.size());
            if (!(amount > 0.0F) || !Float.isFinite(amount)) {
                continue;
            }
            for (int ordinal = 0; ordinal < clause.hits(); ordinal++) {
                for (UUID target : selected) {
                    hits.add(new Hit(target, ordinal, amount, index));
                }
            }
        }
        if (strikes.isEmpty()) {
            return NOTHING;
        }
        return new Plan(targets, hits, strikes);
    }

    /**
     * {@return the candidate bodies one clause lands on, from the already-eligible list}
     *
     * <p>The clause's own reach narrows the pool ({@link #victimReachOf}), and then its
     * {@link com.astrum.pcrd.api.v1.UbTargetRule} chooses — nothing else is decided here. The pool is sorted by
     * distance first, because a rank rule means "n-th <em>by distance</em>", not "n-th in whatever order the
     * level enumerated its entities"; the sort is stable so two bodies at the same distance keep the
     * candidate order the game gave.
     *
     * @param clause   the clause firing
     * @param byId     the candidates by id, the caster excluded
     * @param eligible who the ultimate may damage — the one eligibility rule, already applied
     */
    private static List<UUID> select(UbSegment clause, Map<UUID, Target> byId, List<UUID> eligible) {
        UbTargetRule rule = clause.target() == null ? UbTargetRule.NEAREST : clause.target();
        double reach = victimReachOf(clause);
        List<UUID> pool = new ArrayList<>();
        for (UUID id : eligible) {
            Target candidate = byId.get(id);
            if (candidate != null && candidate.distance() <= reach) {
                pool.add(id);
            }
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        pool.sort(Comparator.comparingDouble(id -> byId.get(id).distance()));
        return switch (rule) {
            case NEAREST -> List.of(pool.get(0));
            case SECOND_NEAREST -> pool.size() < 2 ? List.of() : List.of(pool.get(1));
            case FARTHEST -> List.of(pool.get(pool.size() - 1));
            // The pool was already filtered by this clause's radius, so "everything in the radius" is the pool.
            case ALL_IN_RADIUS -> List.copyOf(pool);
            case AROUND_SECOND_NEAREST -> around(clause, byId, eligible, pool);
        };
    }

    /**
     * {@return every eligible body inside {@code clause}'s radius <b>of the second-nearest body</b>}
     *
     * <p>美美 6★'s 「以前方第2名角色为中心、对范围275内的敌人」: the centre is a body, not the caster, which is why
     * a candidate carries a position at all. The anchor is the second body in the ranked pool and is inside its
     * own blast by construction (its distance from itself is 0), so it never needs re-adding.
     *
     * <p>{@code NaN} comes out of {@link Target#position()} only for a caller that never said where its bodies
     * are; the comparison is then false for every candidate, so such a clause selects nobody rather than
     * everybody — the same reading every other unusable number in this class gets.
     */
    private static List<UUID> around(UbSegment clause, Map<UUID, Target> byId, List<UUID> eligible,
            List<UUID> ranked) {
        double radius = clause.radius().orElse(Double.NaN);
        if (!usable(radius) || ranked.size() < 2) {
            return List.of();
        }
        Target anchor = byId.get(ranked.get(1));
        if (anchor == null) {
            return List.of();
        }
        List<UUID> blast = new ArrayList<>();
        for (UUID id : eligible) {
            Target candidate = byId.get(id);
            if (candidate == null) {
                continue;
            }
            if (anchor.position().distanceTo(candidate.position()) <= radius) {
                blast.add(id);
            }
        }
        return List.copyOf(blast);
    }

    /**
     * {@return the candidates by id, the caster excluded and the first occurrence of a repeated id winning}
     *
     * <p>The caster's own candidate stays in the list {@link #eligible} reads (the ally policy needs their team
     * name) but has no place in a ranking: "the nearest body" is never the caster.
     */
    private static Map<UUID, Target> byId(List<Target> candidates, Caster caster) {
        Map<UUID, Target> byId = new LinkedHashMap<>();
        for (Target candidate : candidates) {
            if (candidate == null || candidate.id() == null || candidate.id().equals(caster.id())) {
                continue;
            }
            if (Double.isFinite(candidate.distance())) {
                byId.putIfAbsent(candidate.id(), candidate);
            }
        }
        return byId;
    }

    /**
     * {@return the candidates this ultimate may damage, in the order given}
     *
     * <p>Three rules, in order: the caster is never a victim; an <b>untouchable</b> candidate
     * (spectator, creative in flight) is out — the game would refuse the damage, so the plan must not
     * count it; a candidate out of reach is out (the boundary is inclusive, the same comparison
     * {@link AllySelector} documents); and a <b>player</b> is out when the ally policy says they are
     * an ally. A mob never reaches the last rule.
     */
    private static List<UUID> eligible(List<Target> candidates, Caster caster, AllyMode mode,
            double reach) {
        Set<UUID> allies = allies(candidates, caster, mode, reach);
        List<UUID> eligible = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Target candidate : candidates) {
            if (candidate == null || candidate.id() == null) {
                continue;
            }
            if (candidate.id().equals(caster.id())) {
                continue;   // 绝不打自己
            }
            if (candidate.untouchable()) {
                continue;   // the game refuses the damage, so the plan must not claim it
            }
            if (!(candidate.distance() <= reach)) {
                continue;   // also drops NaN distances and a NaN reach
            }
            if (candidate.player() && allies.contains(candidate.id())) {
                continue;   // 不伤队友：the policy's answer, not a second rule written here
            }
            if (seen.add(candidate.id())) {
                eligible.add(candidate.id());
            }
        }
        return List.copyOf(eligible);
    }

    /**
     * {@return the candidate players the ally policy calls allies, by the shared kernel}
     *
     * <p>The caster is passed <b>into</b> the kernel with {@code includeSelf = false} even though
     * {@link #eligible} drops them anyway: {@code AllySelector} reads the caster's team name off the
     * caster's own candidate, so removing it beforehand would make {@link AllyMode#SAME_TEAM_ONLY}
     * spare nobody (a team-less caster would then reach everyone, which is the §7.4 trap in reverse).
     */
    private static Set<UUID> allies(List<Target> candidates, Caster caster, AllyMode mode,
            double reach) {
        List<AllySelector.Candidate> players = new ArrayList<>();
        for (Target candidate : candidates) {
            if (candidate == null || !candidate.player() || candidate.id() == null) {
                continue;
            }
            if (!(candidate.distance() <= reach)) {
                continue;
            }
            boolean self = candidate.self() || candidate.id().equals(caster.id());
            players.add(new AllySelector.Candidate(candidate.id(), self, candidate.teamName(),
                    candidate.distance()));
        }
        Set<UUID> allies = new HashSet<>();
        for (AllySelector.Candidate selected
                : AllySelector.select(players, caster.id(), mode, reach, false)) {
            allies.add(selected.id());
        }
        return allies;
    }

    /** {@return whether a declared radius can reach anything at all} */
    private static boolean usable(double reach) {
        return reach > 0.0 && Double.isFinite(reach);
    }
}
