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
 * Putting a heal on its receivers: the one place a {@code HEAL} hook becomes restored health.
 *
 * <h2>Why a new effect type instead of {@code lifesteal}</h2>
 * {@code lifesteal} heals a fraction of the damage the hit just dealt, which is a fundamentally
 * different mechanic: it ties the heal to connecting with an enemy, it is zero when the swing misses,
 * and it can never heal anybody but the attacker. コッコロ's ultimate is 「自分のHPを中回復」 and
 * ペコリーヌ's 技能1 is 「HP中回復」 — fixed heals on a caster and her party. Approximating them with
 * {@code lifesteal} would have replaced the skills' main function with something that behaves nothing
 * like it, which the brief forbids.
 *
 * <h2>Why {@code heal()} and not {@code minecraft:instant_health}</h2>
 * {@code MobEffectInstance} of {@code instant_health} is the other candidate and it is worse for three
 * measured reasons:
 *
 * <ol>
 *   <li><b>It heals a fixed 4 hp per level regardless of the amount we want.</b> The vanilla effect's
 *       own {@code applyEffectTick} restores {@code 4 << amplifier}, so a "92 damage worth" heal would
 *       have to be expressed as an amplifier of 4-5, i.e. 20 hp steps. The datapack could no longer
 *       say a number.</li>
 *   <li><b>It would be scaled by nothing.</b> §5.2 lists 回血 among the quantities diminishing returns
 *       act on, and the amplifier is the one field that must never be scaled (see
 *       {@code EffectQuantities}). An instant-health instance would therefore put the heal's whole
 *       magnitude in the one field the multiplier chain is forbidden to touch.</li>
 *   <li><b>It would show on the HUD.</b> A heal is instantaneous; an effect entry that appears and
 *       vanishes in the same tick is a flicker, and vanilla's own instant effects are hidden for
 *       exactly that reason. {@link LivingEntity#heal(float)} does the one thing a heal is.</li>
 * </ol>
 *
 * <p>So the amount arrives already scaled by the multiplier chain and lands as one {@code heal()} call
 * per receiver. Nothing is remembered, nothing expires, and there is no tick listener.
 *
 * <h2>The receiver rule is {@code AllySelector}'s, not a fifth copy</h2>
 * A heal on 「味方全体」 has exactly the same question to answer as the attack buff and
 * {@code area_charge}: <em>who is an ally?</em> This project has shipped three bugs from having two
 * answers to that question, so the set comes from {@link AllyCandidates} over the same
 * {@link AllySelector} kernel, with {@code includeSelf = true} — 「自分」 is in the text of every one of
 * these heals.
 *
 * <h2>Clamping and the dead</h2>
 * {@code heal()} clamps at max health itself, so an over-heal is simply wasted rather than stored.
 * A dead or removed receiver is skipped rather than healed: {@code heal()} on a corpse would raise a
 * health value nobody can see, and {@link AllyCandidates} already excludes spectators.
 */
@ApiStatus.Internal
public final class Heals {

    private Heals() {
    }

    /**
     * {@return who the heal reaches: the caster and every ally within {@code radius}}
     *
     * <p>The <b>whole</b> receiver rule, in one call, so "a caster plus two allies inside the radius all
     * are healed, one outside is not" is assertable without a world — the same seam
     * {@code AttackBuffs.receivers} provides, for the same reason.
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
     * {@return the reach a declared radius means}
     *
     * <p>The same three answers {@code AttackBuffs.radiusFor} gives, and for the same reasons: an
     * absent radius is the party default (a heal written for 味方全体 must not silently become
     * self-only), {@code 0} is a legitimate 「自分」, and a nonsense radius falls back to the default
     * with a WARN rather than healing nobody.
     *
     * <p>Delegated rather than copied: the two hooks read a radius identically, and a second copy of
     * this rule would drift the first time somebody tuned the default.
     *
     * @param declared the hook's radius, if it declared one
     */
    public static double radiusFor(OptionalDouble declared) {
        return AttackBuffs.radiusFor(declared);
    }

    /**
     * Restores {@code amount} health to the caster and to every ally inside {@code radius}.
     *
     * <p>Never throws: it runs inside a damage sequence, where an exception would cost the rest of the
     * wheel. Each receiver is wrapped individually, so one player whose health handling fails loses
     * their own heal and the party keeps theirs — the same rule the attack buff follows.
     *
     * @param caster      the player whose hook asked for the heal
     * @param amount      health to restore, already scaled by the multiplier chain
     * @param characterId the character whose hook asked, for the log line
     * @param radius      the hook's radius, empty for the party default
     * @return how many receivers actually gained health
     */
    public static int apply(LivingEntity caster, float amount, ResourceLocation characterId,
            OptionalDouble radius) {
        if (caster == null) {
            return 0;
        }
        if (!(amount > 0.0F) || !Float.isFinite(amount)) {
            Astrum.LOGGER.debug("astrum: heal of {} for {} is not a positive amount, so nothing is "
                    + "restored", characterId, name(caster));
            return 0;
        }
        double reach = radiusFor(radius);
        if (!(caster instanceof ServerPlayer player)) {
            // A non-player caster has no party: the heal is on the entity that asked for it, the same
            // degradation AttackBuffs documents.
            Astrum.LOGGER.debug("astrum: heal {} on non-player {} (no party to reach)", amount,
                    name(caster));
            return healOne(caster, amount, characterId) ? 1 : 0;
        }
        AllyCandidates.Pool pool = AllyCandidates.around(player);
        int healed = 0;
        for (AllySelector.Candidate candidate : receivers(pool.candidates(), player.getUUID(),
                AllyCandidates.configuredMode(), reach)) {
            ServerPlayer receiver = pool.players().get(candidate.id());
            if (receiver != null && healOne(receiver, amount, characterId)) {
                healed++;
            }
        }
        Astrum.LOGGER.debug("astrum: heal {} of {} reached {} of {} player(s) within {} blocks",
                amount, characterId, healed, pool.size(), reach);
        return healed;
    }

    /**
     * {@return whether {@code receiver} actually gained health}
     *
     * <p>Asking for the health <b>before and after</b> rather than returning "no exception" is what
     * makes the answer mean something: a receiver already at full health is a legitimate zero, and
     * reporting it as healed would make the log of a working party heal read as if it had done damage.
     *
     * <p>Wrapped per receiver: one player's failure must not cost the party.
     */
    private static boolean healOne(LivingEntity receiver, float amount, ResourceLocation characterId) {
        try {
            if (!receiver.isAlive() || receiver.isRemoved()) {
                Astrum.LOGGER.debug("astrum: heal of {} skipped {} (not alive)", characterId,
                        name(receiver));
                return false;
            }
            float before = receiver.getHealth();
            receiver.heal(amount);
            float gained = receiver.getHealth() - before;
            Astrum.LOGGER.debug("astrum: heal {} on {} -> +{} hp (hp {}/{})", amount, name(receiver),
                    gained, receiver.getHealth(), receiver.getMaxHealth());
            return gained > 0.0F;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: heal of {} threw on {}; the other receivers still run",
                    characterId, name(receiver), e);
            return false;
        }
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
