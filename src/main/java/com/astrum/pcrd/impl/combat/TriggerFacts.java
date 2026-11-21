package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * What just happened, as the yes/no questions the ten {@link Trigger} values ask (spec §4.2, §5.3).
 *
 * <h2>Why every trigger has an answer, and why they share one switch</h2>
 * {@code api.v1} publishes ten triggers, so a data author may write any of them. An arm that answers
 * {@code false} unconditionally is not "not implemented yet" from the author's side — it is silence
 * with no diagnostic, and the only shipped character demonstrated it: two of djeeta's three hooks sat
 * on {@code combo_n: 3} and never fired. The {@link #observes} switch below is therefore exhaustive
 * (no {@code default}), which means an eleventh trigger **stops compiling** instead of quietly joining
 * the silent set.
 *
 * <h2>The four families, and why they are not one event</h2>
 * <ul>
 *   <li><b>Hit-shaped</b> ({@link Trigger#MELEE_HIT}, {@link Trigger#PROJECTILE_HIT},
 *       {@link Trigger#CRIT}, {@link Trigger#COMBO_N}, {@link Trigger#LOW_HEALTH}) — answered on
 *       {@code LivingDamageEvent.Post}, i.e. only for hits that actually reached health, carrying the
 *       health the hit really removed. {@code CRIT} rides along as a <em>fact captured before the hit</em>
 *       ({@code CriticalHitEvent} fires before {@code hurt()}), so a crit hook still sees the real
 *       damage instead of a predicted one.</li>
 *   <li><b>{@link Trigger#KILL}</b> — answered on {@code LivingDeathEvent}, because a death is a
 *       different event from a hit: the killing blow already fired its own melee hook, and replaying it
 *       as a kill would double the payoff (measured rule, see {@code TriggerFactsTest}).</li>
 *   <li><b>{@link Trigger#BLOCK}</b> — answered on {@code LivingShieldBlockEvent}, where the blocker
 *       is the actor and the incoming damage source is what the hit was.</li>
 *   <li><b>{@link Trigger#STACK_FULL}</b> — answered at the moment a {@code stack} hook drives a count
 *       to its cap, which is the only moment that fact exists.</li>
 *   <li><b>{@link Trigger#BATTLE_START}</b> — answered on the <b>one</b> landed hit that opens a fight
 *       ({@link TriggerRuntime#noteBattleStart}), not on every hit of it: the boundary is the player's own
 *       first blow after a lull, and the hit that carries the fact is also that fight's first
 *       {@link Trigger#MELEE_HIT} / {@link Trigger#PROJECTILE_HIT} event. Firing it from a login or a
 *       respawn would be a second definition of "a fight began" and an easy refill, so no such path exists.</li>
 *   <li><b>{@link Trigger#UB_LAND}</b> — answered by the ultimate's own settlement ({@code UbLandHooks}),
 *       once per body its damage really landed on. It is not reachable from a swing: the hook lists live in
 *       {@code ub.landHooks}, and {@code UbHooks.mismatch()} refuses a hook there with any other trigger.</li>
 * </ul>
 *
 * <h2>The two counters, and their conventions</h2>
 * {@code COMBO_N} pays out on <b>every Nth consecutive hit</b> of a rolling chain (n=3 → the 3rd, 6th,
 * 9th … hit), not on every hit from the Nth onward: the latter turns a combo bonus into a permanent
 * one and defeats the purpose of a dimishing design. The chain is per attacker, counts hits on any
 * target, and survives as long as the gap to the previous hit is under {@code comboWindowTicks}; getting
 * hit does not break it (a deliberate, documented choice — the simpler of the two conventions the
 * brief allowed).
 *
 * <p>A {@code hits} of zero or less is <b>not a period</b>. It cannot be clamped to 1 either: 1 is a
 * valid period that pays out on every hit, which is the permanent-payout failure again. So
 * {@code combo_n} with a non-positive {@code hits} never observes, and {@link HookPlan} reports the
 * hook so the author can find it (spec §5.3's "不得 fail-open", and the {@code comboWindowTicks} note
 * in §8.2). Note that {@code TriggerParams.CODEC} defaults {@code hits} to {@code 0}, so this is the
 * state a data author lands in by <em>omitting</em> {@code triggerParams}, not by writing nonsense.
 *
 * <p>{@code STACK_FULL} compares the stack id named by the hook's {@code triggerParams} with the stack
 * that just reached its cap; a hook that names no stack accepts any stack reaching its cap.
 */
@ApiStatus.Internal
public record TriggerFacts(boolean meleeHit, boolean projectileHit, boolean crit, boolean kill,
                           boolean block, boolean lowHealth, int comboHits, boolean battleStart,
                           boolean ubLand, Optional<ResourceLocation> stackReachedMax) {

    /**
     * At or below this fraction of the attacker's maximum health, the attacker counts as low.
     *
     * <p>A constant rather than a config option on purpose: spec §8.2 declares no knob for it, and the
     * two stack/trigger windows the spec now does declare are the ones an operator actually tunes. The
     * value is "half" because that is the only threshold a player can read off their own hearts
     * without a HUD element.
     */
    public static final double LOW_HEALTH_FRACTION = 0.5;

    public TriggerFacts {
        stackReachedMax = stackReachedMax == null ? Optional.empty() : stackReachedMax;
        comboHits = Math.max(0, comboHits);
    }

    /**
     * {@return the facts of one landed hit that does <b>not</b> open a fight}
     *
     * <p>The five-argument form every pre-{@code battle_start} caller uses: an ordinary swing in a fight
     * that is already running. Production passes the gate's answer through the six-argument overload
     * instead, so "this hit is an ordinary one" here can never be mistaken for "this hit opened a fight".
     */
    public static TriggerFacts forHit(boolean meleeHit, boolean projectileHit, boolean crit, int comboHits,
            boolean lowHealth) {
        return forHit(meleeHit, projectileHit, crit, comboHits, lowHealth, false);
    }

    /**
     * {@return the facts of one landed hit, which may also be the blow that opened a fight}
     *
     * @param battleStart whether this blow is the first of a fight, from {@link TriggerRuntime#noteBattleStart}
     */
    public static TriggerFacts forHit(boolean meleeHit, boolean projectileHit, boolean crit, int comboHits,
            boolean lowHealth, boolean battleStart) {
        return new TriggerFacts(meleeHit, projectileHit, crit, false, false, lowHealth, comboHits,
                battleStart, false, Optional.empty());
    }

    /** {@return the facts of a kill, where only {@link Trigger#KILL} applies} */
    public static TriggerFacts forKill() {
        return new TriggerFacts(false, false, false, true, false, false, 0, false, false,
                Optional.empty());
    }

    /** {@return the facts of a shield block, where only {@link Trigger#BLOCK} applies} */
    public static TriggerFacts forBlock() {
        return new TriggerFacts(false, false, false, false, true, false, 0, false, false,
                Optional.empty());
    }

    /** {@return the facts of a stack reaching its cap, where only {@link Trigger#STACK_FULL} applies} */
    public static TriggerFacts forStackFull(ResourceLocation stackId) {
        return new TriggerFacts(false, false, false, false, false, false, 0, false, false,
                Optional.of(stackId));
    }

    /**
     * {@return the facts of one landed ultimate body, where only {@link Trigger#UB_LAND} applies}
     *
     * <p>The ultimate's settlement calls this once per body a hit really landed on, after {@code hurt()}
     * answered true. Nothing else is true here on purpose: the swing-shaped facts describe a <em>swing</em>,
     * and copying them onto an ultimate would let a {@code melee_hit} hook in {@code ub.landHooks} fire as if
     * the cast had swung a sword — which is why {@code UbHooks.mismatch()} refuses that declaration outright.
     */
    public static TriggerFacts forUbLand() {
        return new TriggerFacts(false, false, false, false, false, false, 0, false, true,
                Optional.empty());
    }

    /**
     * {@return {@code true} when the attacker is at or below {@link #LOW_HEALTH_FRACTION} of max health}
     *
     * <p>A maximum of zero answers {@code false}: an entity with no health pool cannot be "at half".
     *
     * @param health    current health
     * @param maxHealth maximum health
     */
    public static boolean isLowHealth(float health, float maxHealth) {
        if (!(maxHealth > 0.0F) || !(health > 0.0F)) {
            return false;
        }
        return health <= maxHealth * LOW_HEALTH_FRACTION;
    }

    /**
     * {@return whether this event is the one {@code trigger} waits for}
     *
     * <p>Exhaustive on purpose: no {@code default}, so adding a trigger to {@code api.v1} is a compile
     * error here instead of a new silent no-op.
     *
     * @param trigger the hook's trigger
     * @param params  the hook's trigger parameters ({@code hits}, {@code stackId})
     */
    public boolean observes(Trigger trigger, TriggerParams params) {
        return switch (trigger) {
            case MELEE_HIT -> meleeHit;
            case PROJECTILE_HIT -> projectileHit;
            case CRIT -> crit;
            case KILL -> kill;
            case BLOCK -> block;
            case LOW_HEALTH -> lowHealth;
            case COMBO_N -> params.hits() >= 1 && comboHits > 0 && comboHits % params.hits() == 0;
            case BATTLE_START -> battleStart;
            case UB_LAND -> ubLand;
            case STACK_FULL -> stackReachedMax.isPresent()
                    && (params.stackId().isEmpty() || params.stackId().get().equals(stackReachedMax.get()));
        };
    }
}
