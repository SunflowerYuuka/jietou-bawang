package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>「提升我方全体 28% 暴击伤害，持续 18 秒」</b> as one timed, party-wide {@code astrum:crit_damage} modifier —
 * 千歌's 6★ UB, and the applier for {@link com.astrum.pcrd.api.v1.EffectType#CRIT_DAMAGE_UP}.
 *
 * <h2>Why this is a {@code MobEffect} and not a remembered modifier</h2>
 * The buff has to <b>end by itself</b> after the ultimate's own 18 seconds. The only two ways to arrange that are
 * to remember the expiry and undo the write when it passes — which needs something that runs on a clock, i.e. the
 * tick listener this project forbids — or to express the buff as a vanilla effect whose lifetime <em>is</em> the
 * modifier's lifetime. This is the second, and it is the same construction
 * {@link AttackBuffEffect} documents for the attack buff: vanilla's {@code addAttributeModifiers} /
 * {@code removeAttributeModifiers} add and remove the modifier under a <b>fixed</b> id over the instance's own
 * clock, so this class has no expiry code, no state, and nothing to sweep.
 *
 * <h2>Why {@code ADD_MULTIPLIED_TOTAL}, and what 28% does</h2>
 * 「提升 28% 暴击伤害」 is a fraction <b>of the damage a crit already deals</b>, which is exactly what
 * {@code ADD_MULTIPLIED_TOTAL} means: the modifier's value is a fraction of the attribute's computed total. At the
 * shipped default {@code astrum:crit_damage = 1.5}, the buff makes it {@code 1.5 × 1.28 = 1.92}, i.e. a crit goes
 * from 150% to 192% of the authored number. {@code ADD_VALUE} would add 0.28 to the <em>multiplier</em> and mean
 * 「+0.28 absolute」 — a different and larger buff at this base — and {@code ADD_MULTIPLIED_BASE} would ignore any
 * other crit-damage source the player carries.
 *
 * <h2>Refresh, never compound</h2>
 * The modifier id is <b>fixed</b> ({@link CritDamageUpEffect#MODIFIER_ID}), so a recast replaces rather than adds,
 * and vanilla merges a re-application of the same effect type by taking the longer duration and the stronger
 * amplifier. Ten casts leave {@code +28%}, not {@code +280%} — {@link AttackBuffRules} states the same two
 * mechanisms for the attack buff, and they are the same two here.
 *
 * <h2>No rule is written twice</h2>
 * The <b>receiver set</b> is {@link AttackBuffs#receivers} (the one {@code AllySelector} call that decides who is an
 * ally, with {@code includeSelf = true}), the <b>reach</b> is {@link AttackBuffs#radiusFor} (declared wins, empty
 * means the party default, a nonsense value warns and falls back), and the <b>clock</b> is
 * {@link AttackBuffRules#ticksFor}. Only two things are new and they are this class's own: the
 * percent ↔ amplifier mapping, and the effect's registry path.
 */
@ApiStatus.Internal
public final class CritDamageUps {

    /** Registry path of the effect: {@code astrum:crit_damage_up}. */
    public static final String PATH = "crit_damage_up";

    /** The effect's registry id, for log lines and for a client-side lookup. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** The translation key vanilla resolves for the effect's name, {@code effect.astrum.crit_damage_up}. */
    public static final String NAME_KEY = "effect." + ID.toLanguageKey();

    /**
     * One amplifier step, as a fraction of crit damage: {@code 0.01}, because the amplifier counts <b>percent</b>.
     *
     * <p>Vanilla gives an effect instance one integer knob, so the wiki's own percentage has to be encoded in it:
     * {@code amplifier 28 → 0.28}, and the registered effect declares its modifier as {@code amplifier × 0.01}
     * through NeoForge's level-curve overload. That is the whole encoding, and it is exact for any whole percent
     * — 28 is representable, so 千歌's clause lands on exactly the number the source states.
     */
    public static final double FRACTION_PER_AMPLIFIER = 0.01;

    private CritDamageUps() {
    }

    /** {@return the registered effect holder, or empty when the registry is not up yet} */
    public static Optional<Holder<MobEffect>> holder() {
        if (!PartyEffects.CRIT_DAMAGE_UP.isBound()) {
            return Optional.empty();
        }
        return Optional.of(PartyEffects.CRIT_DAMAGE_UP);
    }

    /**
     * {@return the amplifier that expresses {@code percent}, clamped into {@code [0, 255]}}
     *
     * <p>Never throws: this runs on an effect path, where a hostile number in a pack must become a logged no-op
     * rather than an exception. A {@code NaN}, an infinity or a non-positive percentage answers {@code 0}, and the
     * caller refuses such a hook before it reaches here.
     *
     * @param percent the increase in crit damage, in percent: {@code 28.0} = +28%
     */
    public static int amplifierFor(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0) {
            return 0;
        }
        return (int) Math.max(0.0, Math.min(AttackBuffRules.MAX_AMPLIFIER, Math.round(percent)));
    }

    /**
     * {@return the fraction an amplifier expresses — the inverse of {@link #amplifierFor}}
     *
     * <p>Read by {@link CritDamageUpEffect}'s modifier curve and by the tests, so "amplifier 28 means +28%" has one
     * implementation.
     *
     * @param amplifier the instance's amplifier
     */
    public static double fractionFor(int amplifier) {
        int clamped = Math.max(0, Math.min(AttackBuffRules.MAX_AMPLIFIER, amplifier));
        return clamped * FRACTION_PER_AMPLIFIER;
    }

    /**
     * {@return whether {@code percent} survives the round trip through the amplifier unchanged}
     *
     * <p>{@code true} for every whole percent, which every shipped clause is; a fractional percent is quantised and
     * the caller states that at WARN rather than silently handing the party a different number.
     *
     * @param percent the declared increase, in percent
     */
    public static boolean isRepresentable(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0) {
            return false;
        }
        return Math.abs(fractionFor(amplifierFor(percent)) - percent * FRACTION_PER_AMPLIFIER)
                < FRACTION_PER_AMPLIFIER / 2.0;
    }

    /**
     * Applies the buff to the caster and to every allied player around her.
     *
     * <p>Checked <b>once</b> before any receiver is touched — a malformed buff cannot be half-applied to the party
     * — and each receiver's application is wrapped, so one player's failure never costs the others. That is the
     * same discipline {@link AttackBuffs#apply} follows on the same cast path.
     *
     * @param caster      the player whose ultimate (or hook) asked for it
     * @param percent     the increase in crit damage, in percent: {@code 28.0} = +28%
     * @param seconds     how long it lasts
     * @param characterId the character whose ultimate asked for it, for the log line
     * @param radius      the ultimate's effect radius, empty for the party default
     * @return how many receivers hold the buff after the call — a refresh counts
     */
    public static int apply(LivingEntity caster, double percent, double seconds,
            ResourceLocation characterId, OptionalDouble radius) {
        if (caster == null) {
            return 0;
        }
        if (!Double.isFinite(percent) || !(percent > 0.0)) {
            Astrum.LOGGER.warn("astrum: crit_damage_up of {} wants {}%, which is not a buff (it must be above "
                    + "0); hook skipped. Check the character's effectParams.", characterId, percent);
            return 0;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            Astrum.LOGGER.warn("astrum: crit_damage_up of {} wants {} seconds, so there is nothing to apply; "
                    + "hook skipped.", characterId, seconds);
            return 0;
        }
        if (!isRepresentable(percent)) {
            Astrum.LOGGER.warn("astrum: crit_damage_up of {} wants {}%, which this effect can only express as "
                            + "{}% (amplifiers are whole percents, 0-{}); the nearest value is used.",
                    characterId, percent, percentForAmplifier(amplifierFor(percent)),
                    AttackBuffRules.MAX_AMPLIFIER);
        }
        Optional<Holder<MobEffect>> effect = holder();
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: crit_damage_up is not registered yet, so the buff of {} was not "
                    + "applied; this means the mob effect registry missed {}.", characterId, ID);
            return 0;
        }
        int amplifier = amplifierFor(percent);
        double reach = AttackBuffs.radiusFor(radius);
        int applied = 0;
        int considered;
        if (caster instanceof ServerPlayer player) {
            AllyCandidates.Pool pool = AllyCandidates.around(player);
            considered = pool.size();
            for (AllySelector.Candidate candidate : AttackBuffs.receivers(pool.candidates(),
                    player.getUUID(), AllyCandidates.configuredMode(), reach)) {
                ServerPlayer receiver = pool.players().get(candidate.id());
                if (receiver != null && applyTo(receiver, effect.get(), ticks, amplifier, characterId)) {
                    applied++;
                }
            }
        } else {
            // A non-player caster is not a party: the effect lands on the body that asked for it.
            considered = 1;
            if (applyTo(caster, effect.get(), ticks, amplifier, characterId)) {
                applied++;
            }
        }
        Astrum.LOGGER.debug("astrum: crit_damage_up of {} -> +{}% for {}s on {} of {} player(s) within {} "
                        + "blocks (amplifier {}, {} ticks; an existing instance is refreshed, never stacked)",
                characterId, percentForAmplifier(amplifier), seconds, applied, considered, reach, amplifier,
                ticks);
        return applied;
    }

    /** {@return {@code amplifier} as the percentage a player reads} */
    static double percentForAmplifier(int amplifier) {
        return fractionFor(amplifier) * 100.0;
    }

    /**
     * {@return whether this receiver holds the buff after the call}
     *
     * <p>Asks {@code hasEffect} rather than returning {@code addEffect}'s answer, so a <b>refresh</b> counts: vanilla
     * answers {@code false} for an instance of the same type and amplifier with an equal-or-longer duration, which
     * is exactly the recast working as designed.
     */
    private static boolean applyTo(LivingEntity receiver, Holder<MobEffect> effect, int ticks, int amplifier,
            ResourceLocation characterId) {
        try {
            receiver.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, false, true));
            boolean holds = receiver.hasEffect(effect);
            Astrum.LOGGER.debug("astrum: crit_damage_up on {} -> +{}% ({} ticks, amplifier {}) holds={}",
                    receiver.getName().getString(), percentForAmplifier(amplifier), ticks, amplifier, holds);
            return holds;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: crit_damage_up of {} threw on {}; the other receivers still run",
                    characterId, receiver.getName().getString(), e);
            return false;
        }
    }
}