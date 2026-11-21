package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>one</b> place a percentage heal becomes restored health: 「味方全体HP中回復」 and 「自分のHP中回復」.
 *
 * <h2>Why a percentage and not an amount</h2>
 * The generic effect kernel's heals are <b>20% of the receiver's own max health a tier</b>, to 100% at tier
 * 5. That is a different mechanic from {@code HEAL} (which takes an absolute amount from the datapack and is
 * scaled by the multiplier chain): 100% of max health is a full heal on a 20-health healer and a full heal on
 * a 40-health tank, which no fixed number can be. The brief names the percentage form and the five rungs, so
 * this class owns that arithmetic and {@link EffectTiers} owns the ladder.
 *
 * <h2>What "instant, not regeneration" means concretely</h2>
 * One {@link LivingEntity#heal(float)} per receiver, in the same tick. No {@code MobEffectInstance} of
 * {@code regeneration}, no per-tick top-up, no HUD entry: a heal is a moment, and an entry that appears and
 * vanishes in one tick is the flicker {@code Heals} rejects {@code instant_health} for.
 *
 * <h2>The three refusals, each a log line</h2>
 * <ul>
 *   <li>A <b>dead or removed</b> receiver is skipped: healing a corpse would raise a health value nobody can
 *       see, and {@code AllyCandidates} already excludes spectators.</li>
 *   <li>A receiver <b>already at full health</b> is a legitimate zero and is counted as "not healed" rather
 *       than as a failure — the log then reads "reached 3 of 4" truthfully.</li>
 *   <li>A <b>max health of zero or less</b> (an entity with a broken attribute) heals nothing rather than
 *       dividing by it.</li>
 * </ul>
 *
 * <h2>The receiver rule is {@code AllySelector}'s, not a copy</h2>
 * 「味方全体」 asks the same question the attack buff and {@code area_charge} ask. So the set comes from
 * {@link AllyCandidates} over the same {@link AllySelector} kernel with {@code includeSelf = true}, and the
 * self-only variant does not call it at all — it has one receiver by definition.
 */
@ApiStatus.Internal
public final class PercentHeals {

    private PercentHeals() {
    }

    /**
     * {@return {@code fraction} of {@code maxHealth}}, never more than the entity can hold
     *
     * <p>Pure, so every rung is assertable without a world. A full heal at fraction {@code 1.0} is exactly
     * the entity's max health — no over-heal to waste, and no floating-point shortfall either, because
     * {@code max × 1.0 == max} exactly for any finite float.
     *
     * <p>{@code NaN}, an infinite fraction and a non-positive max health all answer {@code 0.0F}: this runs
     * on a damage path, where "heal for NaN" would set the entity's health to {@code NaN} and every later
     * comparison against it would be false — the same silent-loss failure {@code ChargeKernel.clamp}
     * prevents for the gauge.
     *
     * @param maxHealth the receiver's max health
     * @param fraction  the share to restore, {@code 0.20} = 20%
     */
    public static float amountFor(float maxHealth, double fraction) {
        if (!(maxHealth > 0.0F) || !Float.isFinite(maxHealth)) {
            return 0.0F;
        }
        if (!(fraction > 0.0) || !Double.isFinite(fraction)) {
            return 0.0F;
        }
        double amount = maxHealth * Math.min(fraction, 1.0);
        return (float) Math.min(amount, maxHealth);
    }

    /**
     * {@return who a party heal reaches: the caster and every ally within {@code radius}}
     *
     * <p>The whole receiver rule in one call, over the kernel every other party effect uses.
     *
     * @param candidates every player around the caster, as {@link AllyCandidates} prepares them
     * @param casterId   the caster's UUID
     * @param mode       the configured ally policy; {@code null} means {@code ALL_PLAYERS}
     * @param radius     how far the heal reaches, in blocks, inclusive
     */
    public static List<AllySelector.Candidate> receivers(List<AllySelector.Candidate> candidates,
            UUID casterId, com.astrum.pcrd.impl.combat.AllyMode mode, double radius) {
        return AllySelector.select(candidates, casterId, mode, radius, true);
    }

    /**
     * Restores {@code fraction} of each receiver's max health to the caster and every ally in radius.
     *
     * <p>Never throws: it runs inside a damage sequence. Each receiver is wrapped individually, so one
     * player whose health handling fails loses their own heal and the party keeps theirs.
     *
     * @param caster      the player whose hook asked
     * @param fraction    the share of max health to restore, already resolved from the tier
     * @param characterId the character whose hook asked, for the log line
     * @param radius      the hook's radius, empty for the party default
     * @return how many receivers actually gained health
     */
    public static int applyArea(LivingEntity caster, double fraction, ResourceLocation characterId,
            OptionalDouble radius) {
        if (caster == null) {
            return 0;
        }
        double reach = AreaBuffs.radiusFor(radius);
        if (!(caster instanceof ServerPlayer player)) {
            Astrum.LOGGER.debug("astrum: percent heal on non-player {} (no party to reach)",
                    name(caster));
            return healOne(caster, fraction, characterId) ? 1 : 0;
        }
        AllyCandidates.Pool pool = AllyCandidates.around(player);
        int healed = 0;
        for (AllySelector.Candidate candidate : receivers(pool.candidates(), player.getUUID(),
                AllyCandidates.configuredMode(), reach)) {
            ServerPlayer receiver = pool.players().get(candidate.id());
            if (receiver != null && healOne(receiver, fraction, characterId)) {
                healed++;
            }
        }
        Astrum.LOGGER.debug("astrum: percent heal {}% of max health reached {} of {} player(s) within {} "
                + "blocks", Math.round(fraction * 100.0), healed, pool.size(), reach);
        return healed;
    }

    /**
     * Restores {@code fraction} of the caster's own max health.
     *
     * <p>The {@code HEAL_SELF} arm: one receiver, no ally policy, no radius. Kept as its own entry point
     * rather than {@code applyArea(…, radius = 0)} so the "self only" path cannot accidentally consult the
     * party — the bug this shape makes impossible.
     *
     * @param caster      the player whose hook asked
     * @param fraction    the share of max health to restore
     * @param characterId attribution for the log line
     * @return whether the caster actually gained health
     */
    public static int applySelf(LivingEntity caster, double fraction, ResourceLocation characterId) {
        if (caster == null) {
            return 0;
        }
        return healOne(caster, fraction, characterId) ? 1 : 0;
    }

    /**
     * Restores {@code fraction} of <b>{@code receiver}'s own</b> max health — one named body, no policy at all.
     *
     * <h2>Why this exists next to {@link #applySelf}</h2>
     * {@code applySelf} and this method do the same arithmetic on one body; they differ in <em>who chose the
     * body</em>. {@code applySelf} is "the caster, by definition"; this one is "this receiver, whoever decided" —
     * which is what {@code TRANSFER_HEAL} needs, because its receiver comes from
     * {@code TransferTarget.of(...)} and may be an ally or the caster. Putting that decision here would drag the
     * ally policy into the heal kernel, and the kernel is the one place that must never decide who is an ally.
     *
     * <p>The percentage is taken against <b>{@code receiver}'s</b> max health, not the caster's: a heal handed to a
     * tank has to be worth a tank's share, which is the whole reason {@link EffectType#HEAL_AREA} is a percentage
     * rather than a number.
     *
     * @param receiver    the body to heal; {@code null} answers {@code 0}
     * @param fraction    the share of its max health to restore
     * @param characterId attribution for the log line
     * @return whether the receiver actually gained health
     */
    public static int applyTo(LivingEntity receiver, double fraction, ResourceLocation characterId) {
        if (receiver == null) {
            return 0;
        }
        return healOne(receiver, fraction, characterId) ? 1 : 0;
    }

    /**
     * {@return whether {@code receiver} actually gained health}
     *
     * <p>Asking for the health <b>before and after</b> rather than returning "no exception" is what makes
     * the answer mean something: a receiver already at full health is a legitimate zero, and reporting it as
     * healed would make the log of a working party heal read as if it had done damage.
     */
    private static boolean healOne(LivingEntity receiver, double fraction, ResourceLocation characterId) {
        try {
            if (!receiver.isAlive() || receiver.isRemoved()) {
                Astrum.LOGGER.debug("astrum: percent heal of {} skipped {} (not alive)", characterId,
                        name(receiver));
                return false;
            }
            float amount = amountFor(receiver.getMaxHealth(), fraction);
            if (!(amount > 0.0F)) {
                Astrum.LOGGER.debug("astrum: percent heal of {} computes nothing for {} (max health {} × "
                                + "{}%)", characterId, name(receiver), receiver.getMaxHealth(),
                        Math.round(fraction * 100.0));
                return false;
            }
            float before = receiver.getHealth();
            receiver.heal(amount);
            float gained = receiver.getHealth() - before;
            Astrum.LOGGER.debug("astrum: percent heal {} hp ({}) on {} -> +{} hp (hp {}/{})", amount,
                    Math.round(fraction * 100.0) + "%", name(receiver), gained, receiver.getHealth(),
                    receiver.getMaxHealth());
            return gained > 0.0F;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: percent heal of {} threw on {}; the other receivers still run",
                    characterId, name(receiver), e);
            return false;
        }
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
