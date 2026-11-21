package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Putting a <b>named vanilla effect</b> on the caster and her party: the {@code ALLY_EFFECT} executor,
 * and the mirror image of {@code EffectExecutors.applyEffect}.
 *
 * <h2>The gap this closes</h2>
 * {@code apply_effect} lands on the entity that was <b>struck</b>. That is exactly right for a debuff
 * and exactly wrong for every buff: ペコリーヌ's 「自分の魔法防御力を中アップ」, コッコロ's
 * 「味方全体の行動速度小アップ」 and ペコリーヌ's 「継続HP回復」 are all effects on the caster's own
 * side, and there was no way to say so. The options were to add one {@code EffectType} per vanilla
 * effect (a defence-buff type, a haste type, a regeneration type, …) or to add <b>one</b> that names
 * the effect by id. This is that one: the vanilla registry already holds the effects, their amplifiers
 * already mean level, and vanilla already expires them.
 *
 * <h2>Why it does not simply reuse {@code attack_buff}</h2>
 * That hook exists because 攻击力 needed an <em>attribute modifier</em> encoded in the amplifier
 * ({@code AttackBuffRules}: 1.5 → amplifier 50). Vanilla's own effects need no such encoding — their
 * amplifier already is their level — so reusing it would have meant inventing a second amplifier
 * mapping for effects that already have one. What is reused is the part that mattered: the
 * <b>receiver set</b>, which is {@code AllySelector}'s through {@link AllyCandidates}, and the radius
 * rule, which is {@code AttackBuffs.radiusFor}'s.
 *
 * <h2>The numbers</h2>
 * Duration is the quantity the multiplier chain acts on, and is floored at one tick so a rank-5 slot
 * cannot switch the effect <em>off</em> (§5.2). The amplifier is an integer level and is never scaled —
 * the same rule, and the same reason, as {@code apply_effect}.
 *
 * <h2>What it cannot do</h2>
 * 「恐慌」 (panic) has no vanilla equivalent, and pretending {@code minecraft:weakness} were panic would
 * put a wrong label on the HUD. It is therefore recorded as unimplemented in the character JSON's
 * {@code extra} and in {@code docs/balance-derivation.md} rather than silently approximated.
 */
@ApiStatus.Internal
public final class PartyVanillaEffects {

    private PartyVanillaEffects() {
    }

    /**
     * {@return who the effect reaches: the caster and every ally within {@code radius}}
     *
     * <p>The whole receiver rule in one call, so it is assertable without a world — the same seam and
     * the same kernel {@code AttackBuffs.receivers} and {@code Heals.receivers} use.
     *
     * @param candidates every player around the caster, as {@link AllyCandidates} prepares them
     * @param casterId   the caster's UUID
     * @param mode       the configured ally policy; {@code null} means {@code ALL_PLAYERS}
     * @param radius     how far the effect reaches, in blocks, inclusive
     */
    public static List<AllySelector.Candidate> receivers(List<AllySelector.Candidate> candidates,
            UUID casterId, com.astrum.pcrd.impl.combat.AllyMode mode, double radius) {
        return AllySelector.select(candidates, casterId, mode, radius, true);
    }

    /**
     * Applies {@code effect} to the caster and to every ally inside {@code radius}.
     *
     * <p>Never throws: it runs inside a damage sequence. Each receiver is wrapped individually, so one
     * player's failure does not cost the party's buff.
     *
     * @param caster      the player whose hook asked for it
     * @param effectId    the vanilla mob effect to apply
     * @param ticks       duration in ticks, already scaled by the multiplier chain
     * @param amplifier   effect level, never scaled
     * @param characterId the character whose hook asked, for the log line
     * @param radius      the hook's radius, empty for the party default
     * @return how many receivers hold the effect afterwards — a refresh counts
     */
    public static int apply(LivingEntity caster, ResourceLocation effectId, int ticks, int amplifier,
            ResourceLocation characterId, OptionalDouble radius) {
        if (caster == null || effectId == null) {
            return 0;
        }
        Optional<Holder.Reference<MobEffect>> effect = BuiltInRegistries.MOB_EFFECT.getHolder(effectId);
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: ally_effect of {} names unknown mob effect {}; hook skipped. "
                    + "Is the effect id correct for this loader?", characterId, effectId);
            return 0;
        }
        if (ticks <= 0) {
            Astrum.LOGGER.debug("astrum: ally_effect {} of {} declares 0 ticks, nothing to apply",
                    effectId, characterId);
            return 0;
        }
        double reach = AttackBuffs.radiusFor(radius);
        Holder<MobEffect> holder = effect.get();
        if (!(caster instanceof ServerPlayer player)) {
            Astrum.LOGGER.debug("astrum: ally_effect {} on non-player {} (no party to reach)",
                    effectId, name(caster));
            return applyTo(caster, holder, effectId, ticks, amplifier, characterId) ? 1 : 0;
        }
        AllyCandidates.Pool pool = AllyCandidates.around(player);
        int applied = 0;
        for (AllySelector.Candidate candidate : receivers(pool.candidates(), player.getUUID(),
                AllyCandidates.configuredMode(), reach)) {
            ServerPlayer receiver = pool.players().get(candidate.id());
            if (receiver != null && applyTo(receiver, holder, effectId, ticks, amplifier, characterId)) {
                applied++;
            }
        }
        Astrum.LOGGER.debug("astrum: ally_effect {} {}t amp {} reached {} of {} player(s) within {} "
                        + "blocks", effectId, ticks, amplifier, applied, pool.size(), reach);
        return applied;
    }

    /**
     * {@return whether {@code receiver} holds the effect after the call}
     *
     * <p>Asking {@code hasEffect} rather than returning {@code addEffect}'s answer is what makes a
     * <b>refresh</b> count: vanilla answers {@code false} when an instance of the same type and
     * amplifier with an equal-or-longer duration is already there — i.e. on exactly the recast that is
     * working as designed. Same reasoning as {@code AttackBuffs.applyTo}.
     */
    private static boolean applyTo(LivingEntity receiver, Holder<MobEffect> effect,
            ResourceLocation effectId, int ticks, int amplifier, ResourceLocation characterId) {
        try {
            if (!receiver.isAlive() || receiver.isRemoved()) {
                return false;
            }
            receiver.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, false, true));
            boolean holds = receiver.hasEffect(effect);
            Astrum.LOGGER.debug("astrum: ally_effect {} amp {} for {}t on {} holds={}", effectId,
                    amplifier, ticks, name(receiver), holds);
            return holds;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: ally_effect {} of {} threw on {}; the other receivers still run",
                    effectId, characterId, name(receiver), e);
            return false;
        }
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
