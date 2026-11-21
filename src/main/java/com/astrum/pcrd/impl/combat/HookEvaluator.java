package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.impl.effect.SkillEntryBuffs;
import com.astrum.pcrd.impl.growth.ExperienceKernel;
import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turns one character's hook list into effects for one trigger event (spec §5.1/§5.2/§5.3/§6.4).
 *
 * <h2>Why this is its own class</h2>
 * It is the "what does a hook do" half of the pipeline; {@link CombatEvents} is the "when did something
 * happen" half. Keeping them apart means the event listener stays about event choice, ordering, the
 * recursion fuse and the facts of what just happened, while this class is about the eight effects.
 *
 * <h2>What lives here now, and what moved out</h2>
 * The <b>decision</b> — which of a hook list runs, for these facts, in what order, and whether a
 * {@code stack_full} pass follows — is {@link HookPlan#fire}: a pure step over the hook list, the
 * facts, a chance roll and an effect runner. This class keeps the two things that need the game: the
 * chance roll from the attacker's {@link net.minecraft.util.RandomSource}, and the diagnostics that
 * name a character and a hook list index.
 *
 * <p>That split is not cosmetic. The loop used to be untestable, so the per-trigger dedupe that dropped
 * djeeta's {@code area_charge} on every third hit shipped with a green suite. See {@link HookPlan} for
 * the three properties the extracted step now owes (all effects of one trigger run; {@code stack_full}
 * does not recurse; {@code stack_full} fires once per fill).
 *
 * <h2>The one re-entrant step: {@code stack_full}</h2>
 * A {@code stack} hook can drive a count to its cap, and "the stack is full" is a trigger of its own.
 * The stack arm reports that fact back, and the same character is re-evaluated with
 * {@link TriggerFacts#forStackFull} — same context, same slot multiplier, so a slot-5 character's
 * {@code stack_full} hook is still scaled as a slot-5 character. That pass runs with
 * {@code allowStackFull = false}, so a {@code stack_full} hook that itself fills a stack cannot loop.
 */
@ApiStatus.Internal
public final class HookEvaluator {

    /**
     * Hooks already reported as broken, keyed {@code <character id>#<hook index>}.
     *
     * <p>The key comes from the loaded datapack, not from a hit or a player, so this set is bounded by
     * the pack's content (see {@link WarnOnce}). Without it, a single malformed hook would write a WARN
     * into the server log on every hit.
     */
    private static final WarnOnce REPORTED = new WarnOnce();

    private HookEvaluator() {
    }

    /**
     * Runs {@code def}'s hooks for one event, applying whatever they produce through {@code ctx}.
     *
     * <p>The second lambda is where growth is composed into the one multiplier chain: {@link
     * HookContext#forCharacter} multiplies the slot's diminishing factor by the character's level. The
     * character's affection tier — carried in the same {@code ctx.growth()} — has already done its work
     * <b>before</b> this method: {@link #hooksOf} resolved which hook list the tier runs, so the hooks walked
     * here are the tier's versions. Nothing downstream multiplies again — see {@link MultiplierChain}.
     *
     * @param def  the character definition owning the hooks
     * @param ctx  the event, carrying this slot's diminishing multiplier and the character's growth
     * @param facts what just happened, as the nine triggers see it
     */
    public static void evaluate(CharacterDef def, HookContext ctx, TriggerFacts facts) {
        List<EffectHook> hooks = hooksOf(def, ctx);
        SkillRotation before = SkillRotationStore.get(ctx.attacker().getUUID(), ctx.characterId());
        // The level travels with the hook list: the collapse (「不同技能都相同效果，取最高等级」) has to
        // resolve every tiered hook's band-composed tier before it can say which single tier each effect
        // runs at, and the band is a property of the character, not of the hook. Reading it off ctx.growth()
        // rather than off the roster keeps this per-character (two players can own the same character at
        // different levels, and CharacterDef is shared registry data).
        int level = levelOf(ctx.growth());
        HookPlan.Outcome outcome = HookPlan.fireTiered(hooks, facts,
                chance -> roll(chance, ctx.attacker()),
                // `forCharacter` composes growth (once) and `forHook` tags the amounts this hook authors with
                // its own crit multiplier, so the flush can use 「本技能 4 倍」 instead of the player's global.
                (index, hook, tier) -> Optional.ofNullable(
                        EffectExecutors.run(hook, ctx.forCharacter().forHook(hook), tier)),
                invalid -> warnInvalid(invalid, ctx),
                before, level,
                // 「持有某状态才生效的分支」: the one game-bound answer the pure decision step cannot compute.
                // States.holds asks the caster's own effect map, and the gate is re-asked on every firing, so
                // an expired state stops its branch with nothing to clear.
                state -> com.astrum.pcrd.impl.effect.States.holds(ctx.attacker(), state));
        if (outcome.rotation() != before) {
            // The one write-back: only this method knows whether her skill actually fired, which is the
            // human's condition for advancing the cursor.
            SkillRotationStore.put(ctx.attacker().getUUID(), ctx.characterId(), outcome.rotation());
        }
        // 在药水栏上显示的图标也只有一个技能图标: the effect-bar row is derived from the SAME outcome the rotation
        // just used — the hooks that actually ran — so the bar cannot report a skill the cursor did not fire,
        // and a skill with no persisting effect writes no row at all (see SkillEntries).
        noteSkillEntry(ctx, outcome);
    }

    /**
     * Shows what this firing left running, as one row in vanilla's effect bar.
     *
     * <h2>Why here, and not inside an executor</h2>
     * The rule is 「one entry per <b>skill</b>」, and a skill is several hooks: an executor sees one hook and
     * cannot know whether a longer-lived effect from the same skill is about to run. So the decision needs the
     * finished {@link HookPlan.Outcome}, which exists only here — this is the one place that already answers
     * 「which skill fired」 for the rotation, and a second answer elsewhere is how the two would drift.
     *
     * <h2>Swallowed on failure, like everything else on this path</h2>
     * The hit has already landed and its effects have already been applied; an effect-bar row is a report about
     * that, so it must never cost it. {@code SkillEntryBuffs} guards itself as well; this catch is for the
     * list-building either side of it.
     */
    private static void noteSkillEntry(HookContext ctx, HookPlan.Outcome outcome) {
        try {
            List<EffectHook> fired = new java.util.ArrayList<>(outcome.fired().size());
            for (HookPlan.Fired one : outcome.fired()) {
                fired.add(one.hook());
            }
            // No party slot, no tier and no row index: the type is the (character, skill) identity, so the
            // applier only needs the character and the hooks that ran — see SkillEntryBuffs.
            SkillEntryBuffs.apply(ctx.attacker(), ctx.characterId(), fired,
                    ctx.forCharacter().multiplier());
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: could not report the skill entry of {}; the hit is unaffected",
                    ctx.characterId(), e);
        }
    }

    /**
     * {@return the level a growth value was built from, or {@code 0} when it cannot say}
     *
     * <p>The band is derived from the <b>level factor</b> rather than carried a second time: the factor is
     * {@code 1 + 0.02 × (level − 1)}, so {@code level = round((factor − 1) / 0.02) + 1}. Recovering it here
     * keeps {@code GrowthMultiplier} from holding three numbers that could disagree, and the round-trip is
     * exact for every integer level because the factor is computed from an integer in the first place.
     *
     * <p>{@code 0} is the honest answer for {@code NEUTRAL} — a quarantined roster, an unowned slot — and it
     * answers the first band, which is what "this build cannot say what level that is" must mean for a bonus
     * that can only add.
     */
    static int levelOf(GrowthMultiplier growth) {
        if (growth == null) {
            return 0;
        }
        double factor = growth.level();
        if (!(factor > 1.0) || !Double.isFinite(factor)) {
            return 0;
        }
        return (int) Math.round((factor - 1.0) / ExperienceKernel.LEVEL_GROWTH_PER_LEVEL) + 1;
    }

    /**
     * {@return the hook list this character runs for this event}
     *
     * <h2>Why the resolution happens here and not in the data loader</h2>
     * The tier is a <b>runtime</b> fact — it comes from the player's stored affection, not from the pack — so
     * a character's hook set cannot be baked at load time. It also must not be baked once per player: two
     * players can own the same character at different tiers, and {@code CharacterDef} is shared registry
     * data. So the resolution is per evaluation, and it is <b>one call</b> into
     * {@code AffectionKernel.hooksAt}, which owns the rule.
     *
     * <p>The cost is one list build per character per hit. That is deliberately accepted rather than cached:
     * a cache keyed by (character, tier) would be a second place that knows the ladder, and the failure mode
     * — a stale cache after an affection change — is a player whose ultimate upgraded but whose skills did
     * not.
     *
     * <h2>The second loop, and why it does not fork the pipeline</h2>
     * 「两套独立的循环」: while 矛依未 is in the mode her ultimate started, these are her <em>second</em> set of
     * small skills. The choice between the two sets is {@link SecondRotationRuntime#hooksFor}'s, because it is
     * the only one that knows about the deadline; everything downstream — {@code HookPlan}, the collapse, the
     * executors, the rotation cursor — receives a plain hook list and cannot tell the two loops apart, which is
     * exactly what keeps each of those rules single.
     */
    static List<EffectHook> hooksOf(CharacterDef def, HookContext ctx) {
        if (def == null) {
            return List.of();
        }
        return SecondRotationRuntime.hooksFor(def, ctx);
    }

    /**
     * The adapter's hook-list-to-effects path: {@link #evaluate} with its two game-bound seams — the
     * chance roll and the effect runner — passed in, and the character's rotation at its start.
     *
     * <p>This exists so a test can walk <b>the route production takes</b> from a {@link CharacterDef}
     * to a chain of effects without a {@link HookContext}, which needs a server. That distinction is
     * the point: a test calling {@link HookPlan#fire} directly would still pass if a per-trigger dedupe
     * were re-added here, between the character and the loop — the same defect, one layer out. What
     * stays uncovered is only the three lambdas built in {@link #evaluate} (roll, dispatch, diagnostic),
     * each of which needs a live entity.
     *
     * @param def       the character whose hooks run
     * @param facts     what just happened
     * @param chance    the chance roll, once per hook that is about to run
     * @param effects   runs one hook
     * @param onInvalid receives every hook that cannot work as written
     */
    @ApiStatus.Internal
    static void evaluateHooks(CharacterDef def, TriggerFacts facts, DoublePredicate chance,
            HookPlan.EffectRun effects, Consumer<HookPlan.Invalid> onInvalid) {
        evaluateHooks(def, facts, chance, effects, onInvalid, SkillRotation.START, 1);
    }

    /**
     * {@return what ran, and the character's rotation after this event} — the seam a test drives to walk
     * several swings of one character without a world, at affection tier 1.
     *
     * @param def      the character whose hooks run
     * @param facts    what just happened
     * @param chance   the chance roll, once per hook that is about to run
     * @param effects  runs one hook
     * @param onInvalid receives every hook that cannot work as written
     * @param rotation her cursor before this event
     */
    @ApiStatus.Internal
    static HookPlan.Outcome evaluateHooks(CharacterDef def, TriggerFacts facts, DoublePredicate chance,
            HookPlan.EffectRun effects, Consumer<HookPlan.Invalid> onInvalid, SkillRotation rotation) {
        return evaluateHooks(def, facts, chance, effects, onInvalid, rotation, 1);
    }

    /**
     * {@return what ran, and the character's rotation after this event} — the same seam <b>at an explicit
     * affection tier</b>.
     *
     * <h2>Why the tier is a parameter</h2>
     * The hook list a character runs depends on her tier ({@code AffectionKernel.hooksAt}), and that is the
     * whole point of the affection rework: tier 2 and tier 3 fire <b>different skills</b>, not the same skills
     * with different numbers. A seam that could only run tier 1 would leave the one behaviour this task
     * exists for untestable without a server, so the tier is passed the same way production passes it (by way
     * of a {@code GrowthMultiplier}) and resolved through the same call.
     *
     * @param def      the character whose hooks run
     * @param facts    what just happened
     * @param chance   the chance roll, once per hook that is about to run
     * @param effects  runs one hook
     * @param onInvalid receives every hook that cannot work as written
     * @param rotation her cursor before this event
     * @param tier     her affection tier, 1–3
     * @param second   whether her second loop is running — the same value {@link #hooksFor} reads off the
     *                 store's deadline, passed explicitly so a test can drive both loops through this seam
     */
    @ApiStatus.Internal
    static HookPlan.Outcome evaluateHooks(CharacterDef def, TriggerFacts facts, DoublePredicate chance,
            HookPlan.EffectRun effects, Consumer<HookPlan.Invalid> onInvalid, SkillRotation rotation,
            int tier, boolean second) {
        GrowthMultiplier growth = new GrowthMultiplier(1.0, tier);
        return HookPlan.fire(SecondRotationRuntime.loopFor(def, growth.tier(), second), facts, chance,
                effects, onInvalid, rotation);
    }

    /**
     * {@return what ran, in her <b>first</b> loop} — the three-argument-plus-tier form every pre-existing
     * caller uses.
     *
     * <p>Delegates to the five-argument {@code second}-aware overload with {@code false}, so the "first loop"
     * path an old caller asserts is the same code the new one takes and not a second route.
     */
    @ApiStatus.Internal
    static HookPlan.Outcome evaluateHooks(CharacterDef def, TriggerFacts facts, DoublePredicate chance,
            HookPlan.EffectRun effects, Consumer<HookPlan.Invalid> onInvalid, SkillRotation rotation,
            int tier) {
        return evaluateHooks(def, facts, chance, effects, onInvalid, rotation, tier, false);
    }

    /**
     * {@return what ran, at explicit affection tier <b>and level</b>} — <b>the production path</b>, which
     * collapses duplicate effects and resolves the level band.
     *
     * <h2>Why this exists next to the untiered seam</h2>
     * 「不同技能都相同效果，取最高等级」 and the level bands are both properties of <em>the whole firing</em>, so
     * a test that drove {@link #evaluateHooks(CharacterDef, TriggerFacts, DoublePredicate,
     * HookPlan.EffectRun, Consumer, SkillRotation, int)} would assert a route production no longer takes —
     * the exact defect {@code HookPlanTest}'s header warns about ("a test calling HookPlan.fire directly would
     * still pass if a per-trigger dedupe were re-added here, between the character and the loop").
     *
     * <p>The two numbers are the two halves of the ladder the human asked for: {@code tier} is the
     * <b>affection</b> rung (which skill set runs) and {@code level} is the <b>level band</b> (how many rungs
     * above the declared tier each effect lands). They are independent and both are needed, which is why they
     * are separate parameters rather than one "growth" blob a caller could pair up wrongly.
     *
     * @param def       the character whose hooks run
     * @param facts     what just happened
     * @param chance    the chance roll, once per hook that is about to run
     * @param effects   runs one hook at one resolved tier
     * @param onInvalid receives every hook that cannot work as written
     * @param rotation  her cursor before this event
     * @param tier      her affection tier, 1–3
     * @param level     her level, for the band; {@code 0} means "unknown", i.e. the first band
     */
    @ApiStatus.Internal
    static HookPlan.Outcome evaluateHooksTiered(CharacterDef def, TriggerFacts facts,
            DoublePredicate chance, HookPlan.TieredEffectRun effects,
            Consumer<HookPlan.Invalid> onInvalid, SkillRotation rotation, int tier, int level) {
        return evaluateHooksTiered(def, facts, chance, effects, onInvalid, rotation, tier, level, false);
    }

    /**
     * {@return what ran, at explicit tier and level, in whichever loop {@code second} names} — the production
     * route with the second loop selectable.
     *
     * <p>Same delegation shape as the untiered pair above: the old signature calls this with {@code false}, so
     * a test that walks 「first loop」 through this method is walking the code production runs.
     *
     * @param second whether her second loop is live
     */
    @ApiStatus.Internal
    static HookPlan.Outcome evaluateHooksTiered(CharacterDef def, TriggerFacts facts,
            DoublePredicate chance, HookPlan.TieredEffectRun effects,
            Consumer<HookPlan.Invalid> onInvalid, SkillRotation rotation, int tier, int level,
            boolean second) {
        GrowthMultiplier growth = new GrowthMultiplier(1.0, tier);
        return HookPlan.fireTiered(SecondRotationRuntime.loopFor(def, growth.tier(), second), facts, chance,
                effects, onInvalid, rotation, level);
    }

    /**
     * Reports a hook that cannot work as written, naming the character, the hook index and the
     * parameter — <b>once</b> per (character, hook), because the defect belongs to the loaded datapack
     * and not to the hit that revealed it.
     */
    private static void warnInvalid(HookPlan.Invalid invalid, HookContext ctx) {
        if (!REPORTED.first(ctx.characterId() + "#" + invalid.hookIndex())) {
            return;
        }
        Astrum.LOGGER.warn("astrum: character {} hook #{} ({} on {}) was skipped: {}", ctx.characterId(),
                invalid.hookIndex(), invalid.hook().type(), invalid.hook().trigger(), invalid.problem());
    }

    /**
     * {@return the chance roll for one hook}
     *
     * <p>Public because a UB's land hooks draw the same roll: a second copy would be a second answer to "did
     * this fire", and a UB clause with a lower chance than the hook beside it would be a defect nobody could
     * see.
     */
    public static boolean roll(double chance, ServerPlayer attacker) {
        if (chance >= 1.0) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        return attacker.getRandom().nextFloat() < chance;
    }
}
