package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * What just happened, as the yes/no questions the eight {@link Trigger} values ask (spec §4.2, §5.3).
 *
 * <h2>Why every trigger has an answer, and why they share one switch</h2>
 * {@code api.v1} publishes eight triggers, so a data author may write any of them. An arm that answers
 * {@code false} unconditionally is not "not implemented yet" from the author's side — it is silence
 * with no diagnostic, and the only shipped character demonstrated it: two of djeeta's three hooks sat
 * on {@code combo_n: 3} and never fired. The {@link #observes} switch below is therefore exhaustive
 * (no {@code default}), which means a ninth trigger **stops compiling** instead of quietly joining the
 * silent set.
 *
 * <h2>The three families, and why they are not one event</h2>
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
 * <p>{@code STACK_FULL} compares the stack id named by the hook's {@code triggerParams} with the stack
 * that just reached its cap; a hook that names no stack accepts any stack reaching its cap.
 */
@ApiStatus.Internal
public record TriggerFacts(boolean meleeHit, boolean projectileHit, boolean crit, boolean kill,
                           boolean block, boolean lowHealth, int comboHits,
                           Optional<ResourceLocation> stackReachedMax) {

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

    /** {@return the facts of one landed hit} */
    public static TriggerFacts forHit(boolean meleeHit, boolean projectileHit, boolean crit, int comboHits,
            boolean lowHealth) {
        return new TriggerFacts(meleeHit, projectileHit, crit, false, false, lowHealth, comboHits,
                Optional.empty());
    }

    /** {@return the facts of a kill, where only {@link Trigger#KILL} applies} */
    public static TriggerFacts forKill() {
        return new TriggerFacts(false, false, false, true, false, false, 0, Optional.empty());
    }

    /** {@return the facts of a shield block, where only {@link Trigger#BLOCK} applies} */
    public static TriggerFacts forBlock() {
        return new TriggerFacts(false, false, false, false, true, false, 0, Optional.empty());
    }

    /** {@return the facts of a stack reaching its cap, where only {@link Trigger#STACK_FULL} applies} */
    public static TriggerFacts forStackFull(ResourceLocation stackId) {
        return new TriggerFacts(false, false, false, false, false, false, 0, Optional.of(stackId));
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
            case COMBO_N -> comboHits > 0 && comboHits % comboLengthOf(params) == 0;
            case STACK_FULL -> stackReachedMax.isPresent()
                    && (params.stackId().isEmpty() || params.stackId().get().equals(stackReachedMax.get()));
        };
    }

    /** {@return the chain length a {@code combo_n} hook asks for; a missing or silly value means 1} */
    private static int comboLengthOf(TriggerParams params) {
        return Math.max(1, params.hits());
    }
}
