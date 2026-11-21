package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>one</b> place a tiered party buff becomes a real effect on the caster and her allies.
 *
 * <h2>One receiver rule, six buffs</h2>
 * The attack buff, the two defence buffs, haste and charge-rate all answer the same question — "who is on my
 * side?" — and this project has shipped three bugs from having two answers to it. So the set comes from
 * {@link AllyCandidates} over {@link AllySelector}, with {@code includeSelf = true}: 「自分」 is in the text
 * of every one of these. Five buffs, one rule, one call.
 *
 * <h2>Duration and strength</h2>
 * The <b>duration</b> comes from {@link TieredParams#seconds()} (or a default when the pack omits it) and is
 * <b>not</b> scaled by the multiplier chain: these are tiered effects, so their strength is the tier, and
 * scaling a tier-N buff's lifetime by the party's slot order would make one ultimate behave differently by
 * party position — the same rule {@code AttackBuffs} states for its own duration.
 *
 * <p>The <b>strength</b> is the tier, and it travels as the instance's amplifier — the one per-instance knob
 * vanilla offers. Each effect's own rules class owns the amplifier↔tier mapping
 * ({@code AttackAreaRules}, {@code DefenceRules}, {@code HasteRules}, {@code ChargeKernel}), so this class
 * never does that arithmetic; it is handed an amplifier and applies it.
 *
 * <h2>Refresh, never compound</h2>
 * Vanilla merges two instances of one type (taking the longer duration), and the attribute-carrying effects
 * declare their modifiers under fixed ids, so a recast replaces rather than accumulates. Nothing here has to
 * know that.
 *
 * <h2>No tick listener, nothing to clean up</h2>
 * The instance's duration is the whole lifetime; vanilla expires it. This class is a static function and
 * holds no state at all.
 */
@ApiStatus.Internal
public final class AreaBuffs {

    /**
     * The reach used when a buff declares no radius, in blocks.
     *
     * <p>Delegated to {@link AttackBuffs#PARTY_RADIUS_DEFAULT} rather than declared again: it is the same
     * promise ("a party buff covers a party that is fighting the same pack") and two constants for it would
     * drift.
     */
    public static final double DEFAULT_RADIUS = AttackBuffs.PARTY_RADIUS_DEFAULT;

    /**
     * The duration a buff gets when the pack names none, in seconds.
     *
     * <p>Eight seconds: long enough to matter across a fight's worth of swings, short enough that a buff
     * from a lucky hit is not permanent. The shipped {@code attack_buff} uses 12 s and 18 s, so this sits
     * inside the range the characters already use rather than inventing a new scale.
     */
    public static final double DEFAULT_SECONDS = 8.0;

    private AreaBuffs() {
    }

    /**
     * {@return who a tiered party buff reaches: the caster and every ally within {@code radius}}
     *
     * <p>The whole receiver rule in one call, so "the caster and two allies inside the radius gain it, one
     * outside gains nothing" is assertable without a world — the same seam {@code AttackBuffs.receivers} and
     * {@code Heals.receivers} provide, over the same kernel.
     *
     * @param candidates every player around the caster, as {@link AllyCandidates} prepares them
     * @param casterId   the caster's UUID
     * @param mode       the configured ally policy; {@code null} means {@code ALL_PLAYERS}
     * @param radius     how far the buff reaches, in blocks, inclusive
     */
    public static List<AllySelector.Candidate> receivers(List<AllySelector.Candidate> candidates,
            UUID casterId, com.astrum.pcrd.impl.combat.AllyMode mode, double radius) {
        return AllySelector.select(candidates, casterId, mode, radius, true);
    }

    /**
     * {@return the reach a declared radius means}
     *
     * <p>{@code AttackBuffs.radiusFor}'s three answers: absent → the party default, {@code 0} → the caster
     * alone (a legitimate 「自分」), nonsense → the default with a WARN. Delegated, not copied — a second
     * copy of this rule would drift the first time the default was tuned.
     *
     * @param declared the hook's radius, if it declared one
     */
    public static double radiusFor(OptionalDouble declared) {
        return AttackBuffs.radiusFor(declared);
    }

    /**
     * {@return the duration in ticks a {@link TieredParams} asks for, {@code DEFAULT_SECONDS} when absent}
     *
     * <p>A declared {@code 0} is honoured as zero (the author asked for nothing) and the caller skips; a
     * negative or {@code NaN} falls back to the default with a WARN, because "a buff that lasts nobody's
     * turn" is a broken feature with no symptom while an over-long buff is visible and correctable — the
     * same asymmetry {@code AttackBuffs.radiusFor} documents.
     *
     * @param params the hook's parameters
     */
    public static int ticksFor(TieredParams params) {
        OptionalDouble seconds = params.seconds();
        if (seconds.isEmpty()) {
            return AttackBuffRules.ticksFor(DEFAULT_SECONDS);
        }
        double declared = seconds.getAsDouble();
        if (declared == 0.0) {
            return 0;
        }
        if (!Double.isFinite(declared) || declared < 0.0) {
            Astrum.LOGGER.warn("astrum: a tiered buff declares {} seconds, which is not a duration; using "
                    + "the {} s default instead. Write 0 for 'no buff'.", declared, DEFAULT_SECONDS);
            return AttackBuffRules.ticksFor(DEFAULT_SECONDS);
        }
        return AttackBuffRules.ticksFor(declared);
    }

    /**
     * Applies {@code effect} to the caster and to every ally inside {@code radius}.
     *
     * <p>Never throws: it runs inside a damage sequence, where an exception would cost the rest of the
     * wheel. Each receiver is wrapped individually, so one player's failure does not cost the party's
     * buff.
     *
     * @param caster      the player whose hook asked for it
     * @param effect      the registered effect to apply
     * @param ticks       duration in ticks
     * @param amplifier   the effect level the tier maps to, never scaled
     * @param characterId the character whose hook asked, for the log line
     * @param radius      the hook's radius, empty for the party default
     * @return how many receivers hold the effect afterwards — a refresh counts
     */
    public static int apply(LivingEntity caster, Holder<MobEffect> effect, int ticks, int amplifier,
            ResourceLocation characterId, OptionalDouble radius) {
        if (caster == null || effect == null || ticks <= 0) {
            return 0;
        }
        double reach = radiusFor(radius);
        if (!(caster instanceof ServerPlayer player)) {
            // A non-player caster is not a party: the effect is on the entity that asked for it, and there
            // is no ally rule that could name a player for it. The same degradation AttackBuffs makes.
            return applyTo(caster, effect, ticks, amplifier, characterId) ? 1 : 0;
        }
        AllyCandidates.Pool pool = AllyCandidates.around(player);
        int applied = 0;
        for (AllySelector.Candidate candidate : receivers(pool.candidates(), player.getUUID(),
                AllyCandidates.configuredMode(), reach)) {
            ServerPlayer receiver = pool.players().get(candidate.id());
            if (receiver != null && applyTo(receiver, effect, ticks, amplifier, characterId)) {
                applied++;
            }
        }
        Astrum.LOGGER.debug("astrum: tiered buff {} amp {} for {}t reached {} of {} player(s) within {} "
                + "blocks", effect.value().getDescriptionId(), amplifier, ticks, applied, pool.size(), reach);
        return applied;
    }

    /**
     * {@return whether {@code receiver} holds the effect after the call}
     *
     * <p>Asking {@code hasEffect} rather than returning {@code addEffect}'s answer is what makes a
     * <b>refresh</b> count: vanilla answers {@code false} when an instance of the same type and amplifier
     * with an equal-or-longer duration is already there — i.e. on exactly the recast that is working as
     * designed.
     */
    private static boolean applyTo(LivingEntity receiver, Holder<MobEffect> effect, int ticks,
            int amplifier, ResourceLocation characterId) {
        try {
            if (!receiver.isAlive() || receiver.isRemoved()) {
                return false;
            }
            receiver.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, false, true));
            boolean holds = receiver.hasEffect(effect);
            Astrum.LOGGER.debug("astrum: tiered buff on {} -> amp {} for {}t holds={}",
                    receiver.getName().getString(), amplifier, ticks, holds);
            return holds;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: a tiered buff of {} threw on {}; the other receivers still run",
                    characterId, receiver.getName().getString(), e);
            return false;
        }
    }

    /**
     * {@return the effect holder for a registry path, empty when this build has no such effect}
     *
     * <p>Only used by the two effects whose holder is chosen by kind rather than by a named constant, so the
     * call site stays free of the {@code DeferredHolder} plumbing. {@link Optional#empty()} rather than a
     * throw: the caller is on a damage path.
     *
     * @param path the registry path, e.g. {@code phys_defence}
     */
    public static Optional<Holder<MobEffect>> holderFor(String path) {
        return switch (path) {
            case DefenceRules.PHYS_PATH -> PartyEffects.physDefenceHolder();
            case DefenceRules.MAGIC_PATH -> PartyEffects.magicDefenceHolder();
            case AttackAreaEffect.PATH -> PartyEffects.attackAreaHolder();
            case MagicAttackAreaEffect.PATH -> PartyEffects.magicAttackAreaHolder();
            case PanicRules.PATH -> PartyEffects.panicHolder();
            case "haste" -> PartyEffects.hasteHolder();
            default -> Optional.empty();
        };
    }

    /**
     * {@return whether the entity's {@code ATTACK_DAMAGE} attribute exists}
     *
     * <p>Only used for a debug line that says which attribute an attack buff landed on; a mob without the
     * attribute (an armour stand, a marker) is not an error, it is an entity with no attack to raise.
     *
     * @param entity the receiver
     */
    public static boolean canAttack(LivingEntity entity) {
        return entity.getAttribute(Attributes.ATTACK_DAMAGE) != null;
    }
}
