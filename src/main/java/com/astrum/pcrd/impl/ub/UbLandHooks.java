package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.UbHooks;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.combat.AreaChargeSink;
import com.astrum.pcrd.impl.combat.CombatEvents;
import com.astrum.pcrd.impl.combat.DamageBatch;
import com.astrum.pcrd.impl.combat.EffectExecutors;
import com.astrum.pcrd.impl.combat.HookContext;
import com.astrum.pcrd.impl.combat.HookEvaluator;
import com.astrum.pcrd.impl.combat.HookPlan;
import com.astrum.pcrd.impl.combat.SkillRotation;
import com.astrum.pcrd.impl.combat.TriggerFacts;
import com.astrum.pcrd.impl.combat.WarnOnce;
import com.astrum.pcrd.impl.effect.States;
import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The ultimate's own trigger: fires one rung's {@code ub.landHooks} for every body its damage landed on.</b>
 *
 * <h2>The gap this closes</h2>
 * An {@link EffectHook} exists only as "a trigger plus an effect", and an ultimate had no trigger at all: it
 * fires on a key press and settles its own victims. So {@code apply_effect} — the one way this build puts a
 * vanilla debuff such as {@code minecraft:weakness} on a body — could not be attached to an ultimate, and
 * 未奏希's 6★ 「降低敌方全体物理攻击力 18 秒」 was recorded as unimplemented rather than silently dropped.
 * {@link Trigger#UB_LAND} is that missing trigger, and this class is its one firing path.
 *
 * <h2>What it reuses, and what it deliberately does not re-implement</h2>
 * The decision is {@link HookPlan#fireTiered} — the same loop, rotation grouping, chance roll, same-effect
 * collapse and invalid-hook reporting a swing uses. The execution is {@link EffectExecutors#run}, the same
 * dispatch every hook uses. The damage an effect queues is applied by {@link CombatEvents#applyBatchNow}, the
 * same per-entry body the end-of-tick flush runs, so {@code extra_damage} from a UB clause is scaled by the
 * magic stat and the crit roll exactly like a swing's. The state gate is {@link States#holds}, the same
 * predicate a {@code melee_hit} branch uses. Nothing about "what an effect does" is written twice.
 *
 * <h2>Why the multiplier is {@code 1.0}</h2>
 * Spec §5.4 exempts the ultimate from diminishing returns, and {@code UbDamageSettlement.NEUTRAL_MULTIPLIER}
 * states the same for the tier's own effects. A UB-land hook is still an ultimate's clause, so a
 * {@code defence_down} keeps its authored duration rather than shrinking with the party slot. The caster's
 * <b>level band</b> still raises a tiered clause's rung, exactly as it does on the ultimate's
 * {@code ub.effects}: {@code level} is passed to the collapse, which is where the band is added.
 *
 * <h2>When it fires, and why that gate is the settlement's</h2>
 * Once per body, <b>after</b> {@code hurt()} answered true — the same fact that gates the tier's victim
 * effects and the burst ledger. A target that was immune, invulnerable or out of reach therefore gets no
 * debuff, which is what 「受到【动作1】伤害的目标」 says.
 *
 * <h2>The one clause this path refuses, and why it is reported</h2>
 * {@code area_charge} is a <b>caster-wide</b> grant, not a per-body state, and its guards (ally policy, pair
 * cooldown, per-second cap) are one session-owned {@code GuardedAreaChargeSink} in {@code CombatEvents}. A
 * per-victim copy of it would charge the party once per body and would create a second budget. So the sink
 * here is a refusal: a {@code ub.landHooks} entry that names {@code area_charge} is logged once and not
 * applied — the ultimate's own charge grant belongs on {@code ub.effects} / {@code teamChargeBonus}. That is
 * a documented, visible refusal rather than a silent one.
 *
 * <h2>Failure policy</h2>
 * Never throws. This runs inside the settlement's victim loop, after the caster's gauge has been spent and
 * the victim's health has already changed; one malformed hook, one failing executor or one bad receiver costs
 * that clause, not the rest of the cast.
 */
@ApiStatus.Internal
public final class UbLandHooks {

    /** Problems already reported, keyed by the datapack defect rather than by the cast that revealed it. */
    private static final WarnOnce REPORTED = new WarnOnce();

    /** The {@code area_charge} refusal, once per character declaring it. */
    private static final AreaChargeSink NO_AREA_CHARGE = request -> {
        if (request == null) {
            return;
        }
        if (REPORTED.first("area_charge|" + request.characterId())) {
            Astrum.LOGGER.warn("astrum: character {} declares area_charge in ub.landHooks; a UB-land hook "
                    + "fires once per body, so a party-wide charge grant would pay N times. The clause was "
                    + "not applied — move it to ub.effects (ally scope) or the ultimate's teamChargeBonus. "
                    + "(reported once)", request.characterId());
        }
    };

    private UbLandHooks() {
    }

    /**
     * {@return the decision step alone, at the production path's own rules, with both game seams injected}
     *
     * <h2>Why this is a separate method</h2>
     * The firing decision needs no live entity ({@link HookPlan#fireTiered} takes the chance roll, the state
     * gate and the effect runner as values), so the one property this class owes — "a {@code ub_land} hook
     * runs on the ultimate's facts, and only the fired rung's list is considered" — is assertable in a plain
     * unit test, through the same {@code HookPlan} the settlement calls. The alternative (a test that
     * re-implements the list selection) would pass while production selected something else.
     *
     * @param hooks     the rung's {@code ub.landHooks}, already filtered by {@link #legal}
     * @param level     the caster's level, for the collapse's level band; {@code 0} for unknown
     * @param chance    the chance roll, once per hook that is about to run
     * @param stateHeld the state gate, re-asked on every firing
     * @param run       runs one hook at its resolved tier
     * @param onInvalid receives every hook that cannot work as written
     */
    static HookPlan.Outcome decide(List<EffectHook> hooks, int level, DoublePredicate chance,
            Predicate<ResourceLocation> stateHeld, HookPlan.TieredEffectRun run,
            Consumer<HookPlan.Invalid> onInvalid) {
        return HookPlan.fireTiered(hooks, TriggerFacts.forUbLand(), chance, run, onInvalid,
                SkillRotation.START, level, stateHeld);
    }

    /**
     * Fires the rung's hooks for one body the ultimate's damage landed on.
     *
     * <p>Called from {@code UbDamageSettlement}'s victim loop, only on the {@code hurt() == true} branch. A
     * {@code null} caster, victim or empty list is a no-op; an empty list is the common case (every shipped
     * character but 未奏希 / 玲莓 / 怜 declares no {@code ub.landHooks}).
     *
     * @param caster      the player whose ultimate fired
     * @param victim      the body that took the hit
     * @param source      the damage source the hit used
     * @param dealt       health the hit actually removed, for {@code lifesteal}-style effects
     * @param characterId the firing character, for attribution and the log line
     * @param level       the caster's level, for the collapse's band
     * @param tier        the rung that fired, for the log line
     * @param hooks       that rung's {@code ub.landHooks}
     */
    public static void fire(ServerPlayer caster, LivingEntity victim, DamageSource source, float dealt,
            ResourceLocation characterId, int level, UbTier tier, List<EffectHook> hooks) {
        if (caster == null || victim == null || hooks == null || hooks.isEmpty()) {
            return;
        }
        List<EffectHook> legal = legal(hooks, characterId);
        if (legal.isEmpty()) {
            return;
        }
        DamageBatch batch = new DamageBatch();
        HookContext ctx = new HookContext(caster, victim, source, dealt,
                UbDamageSettlement.NEUTRAL_MULTIPLIER, GrowthMultiplier.NEUTRAL, characterId, batch,
                (other, type, amount, crit) -> {
                    DamageBatch one = new DamageBatch();
                    // The hook's own crit multiplier travels to the body this amount lands on: a UB's land hook that
                    // authors damage is the same skill's damage, so it must crit at the same value. This sink applies
                    // immediately (applyBatchNow) rather than at the tick's flush, so the tag has to be carried here
                    // explicitly — see DamageToTarget.
                    one.tagged(crit).add(type, amount);
                    CombatEvents.applyBatchNow(caster, other, one);
                },
                NO_AREA_CHARGE);
        try {
            HookPlan.Outcome outcome = decide(legal, level,
                    chance -> HookEvaluator.roll(chance, caster),
                    state -> States.holds(caster, state),
                    (index, hook, resolved) -> Optional.ofNullable(
                            // forHook tags the batch and the other-bodies sink with THIS hook's per-skill crit
                            // multiplier (task 55's channel), so an extra_damage hook on a 6★ UB crits at
                            // the ultimate's own value rather than at the caster's global one.
                            EffectExecutors.run(hook, ctx.forHook(hook), resolved)),
                    invalid -> report(characterId, invalid));
            CombatEvents.applyBatchNow(caster, victim, batch);
            Astrum.LOGGER.debug("astrum: UB {} ({}) landed on {} -> {} of {} ub_land hook(s) fired",
                    characterId, tier == null ? "?" : tier.nameOr("?"), victim.getName().getString(),
                    outcome.fired().size(), legal.size());
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: the ub_land hooks of {} threw on {}; the cast's damage and its "
                    + "other victims are unaffected", characterId, victim.getName().getString(), e);
        }
    }

    /**
     * {@return the hooks that may fire here, with a wrong-trigger one reported once and dropped}
     *
     * <p>The trigger in this position is not a free choice: the settlement observes {@code ub_land} and
     * nothing else, so a {@code melee_hit} hook written under {@code ub.landHooks} would be a permanent no-op.
     * {@link UbHooks#problemOf} owns that rule (it is also what {@code UbHooks.mismatch()} answers), and this
     * method only applies it — one rule, one message.
     */
    static List<EffectHook> legal(List<EffectHook> hooks, ResourceLocation characterId) {
        List<EffectHook> wanted = new ArrayList<>(hooks.size());
        for (int index = 0; index < hooks.size(); index++) {
            EffectHook hook = hooks.get(index);
            Optional<String> problem = UbHooks.problemOf(hook);
            if (problem.isPresent()) {
                report(characterId, new HookPlan.Invalid(index, hook, problem.get()));
                continue;
            }
            wanted.add(hook);
        }
        return List.copyOf(wanted);
    }

    /** Logs one malformed UB hook once per (character, index, problem), not once per cast. */
    private static void report(ResourceLocation characterId, HookPlan.Invalid invalid) {
        String key = "ub_land|" + characterId + "|" + invalid.hookIndex() + "|" + invalid.problem();
        if (!REPORTED.first(key)) {
            return;
        }
        Astrum.LOGGER.warn("astrum: character {} ub.landHooks[{}] ({} on {}) was skipped: {}", characterId,
                invalid.hookIndex(), invalid.hook() == null ? "null" : invalid.hook().type(),
                invalid.hook() == null ? "null" : invalid.hook().trigger(), invalid.problem());
    }
}