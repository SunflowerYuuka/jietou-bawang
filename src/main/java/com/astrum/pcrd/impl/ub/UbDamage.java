package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.ArrayList;
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
                         double distance, boolean untouchable) {

        /** Normalises a {@code null} team to empty, so the ally kernel never sees a null. */
        public Target {
            teamName = teamName == null ? Optional.empty() : teamName;
        }
    }

    /**
     * One application of damage.
     *
     * @param target  who takes it
     * @param ordinal which hit of the sequence it is, from {@code 0} — kept because "four separate
     *                applications" is the design, not an implementation detail
     * @param amount  damage for this application
     */
    public record Hit(UUID target, int ordinal, float amount) {
    }

    /**
     * The whole plan for one cast.
     *
     * @param targets who is hit, in the order the candidates were given (one entry for a
     *                single-target tier)
     * @param hits    every application, in strike order: all targets of hit 0, then all of hit 1, …
     */
    public record Plan(List<UUID> targets, List<Hit> hits) {

        public Plan {
            targets = List.copyOf(targets);
            hits = List.copyOf(hits);
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
    public static final Plan NOTHING = new Plan(List.of(), List.of());

    /**
     * {@return the reach of {@code tier}, in blocks}
     *
     * <p>The declared radius when there is one, {@link #SINGLE_TARGET_RANGE} otherwise. The raw value
     * is returned even when it is unusable (0, negative, {@code NaN}) — deciding that such a tier
     * reaches <b>nobody</b> is {@link #plan}'s job, and hiding it here would turn a hostile radius
     * into a silent six-block one.
     *
     * @param tier the tier that fired
     */
    public static double range(UbTier tier) {
        if (tier == null) {
            return SINGLE_TARGET_RANGE;
        }
        return tier.radius().isPresent() ? tier.radius().getAsDouble() : SINGLE_TARGET_RANGE;
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
     * <p>Never throws and never returns {@code null}: a {@code null} tier, caster or candidate list, a
     * dead caster, a hostile number in the data, or a target list holding only the caster all answer
     * {@link #NOTHING}. This runs on the cast path, where the player's gauge has already been spent,
     * so an exception here would turn a completed cast into a reported failure.
     *
     * @param tier       the tier that fired ({@code ub.base()} or {@code ub.enhanced()})
     * @param caster     the player whose ultimate fired
     * @param candidates every entity the game-bound half considers, <b>including</b> the caster (the
     *                   ally policy needs the caster's own team to compare against)
     * @param mode       the configured ally policy; {@code null} means {@link AllyMode#ALL_PLAYERS}
     */
    public static Plan plan(UbTier tier, Caster caster, List<Target> candidates, AllyMode mode) {
        if (tier == null || caster == null || caster.id() == null || !caster.alive()
                || candidates == null) {
            return NOTHING;
        }
        float amount = (float) tier.damage();
        if (!(amount > 0.0F) || !Float.isFinite(amount)) {
            return NOTHING;   // 0, negative, NaN and infinity are all "this tier deals nothing"
        }
        if (tier.hits() <= 0) {
            return NOTHING;
        }
        double reach = range(tier);
        if (tier.radius().isPresent() && !usable(reach)) {
            return NOTHING;   // a radius of 0 / negative / NaN reaches nobody, never "everyone"
        }

        List<UUID> eligible = eligible(candidates, caster, mode, reach);
        if (eligible.isEmpty()) {
            return NOTHING;
        }
        List<UUID> targets = tier.radius().isPresent()
                ? eligible
                : List.of(nearest(candidates, caster, eligible));

        List<Hit> hits = new ArrayList<>(targets.size() * tier.hits());
        for (int ordinal = 0; ordinal < tier.hits(); ordinal++) {
            for (UUID target : targets) {
                hits.add(new Hit(target, ordinal, amount));
            }
        }
        return new Plan(targets, hits);
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

    /** {@return the nearest of {@code eligible}, by the distance the candidates carry} */
    private static UUID nearest(List<Target> candidates, Caster caster, List<UUID> eligible) {
        Map<UUID, Double> distances = new LinkedHashMap<>();
        for (Target candidate : candidates) {
            if (candidate != null && candidate.id() != null && !candidate.id().equals(caster.id())
                    && Double.isFinite(candidate.distance())) {
                distances.putIfAbsent(candidate.id(), candidate.distance());
            }
        }
        UUID best = eligible.get(0);
        double bestDistance = distances.getOrDefault(best, Double.MAX_VALUE);
        for (UUID id : eligible) {
            double distance = distances.getOrDefault(id, Double.MAX_VALUE);
            if (distance < bestDistance) {
                best = id;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** {@return whether a declared radius can reach anything at all} */
    private static boolean usable(double reach) {
        return reach > 0.0 && Double.isFinite(reach);
    }
}
