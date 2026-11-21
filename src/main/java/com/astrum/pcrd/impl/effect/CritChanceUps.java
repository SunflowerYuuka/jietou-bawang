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
 * <b>「提升我方全体 N% 暴击率，持续 M 秒」</b> as one timed, party-wide {@code astrum:crit_chance} modifier —
 * the applier for {@link com.astrum.pcrd.api.v1.EffectType#CRIT_CHANCE_UP}.
 *
 * <h2>Why the crit-damage buff's twin, and not a rung ladder</h2>
 * 千歌's 专属, 静流's 二专 and 铃莓's 二专 each name a crit <em>score</em> — 50, 100 and 200 points — and the
 * build's conversion ({@link CritChances#CHANCE_PER_POINT}, one point = 0.05 percentage points) turns them into
 * 2.5%, 5% and 10%. A rung ladder cannot state 2.5% at any sensible step, so the magnitude travels as the
 * <b>percentage itself</b> ({@code percent}), exactly as {@code CritDamageUps} carries 28 for the crit-damage
 * buff. That also means the book shows the converted number the wiki's arithmetic produces rather than "tier 1".
 *
 * <h2>No rule is written twice</h2>
 * The <b>receiver set</b> is {@link AttackBuffs#receivers} (the one {@code AllySelector} call that decides who is
 * an ally, with {@code includeSelf = true}), the <b>reach</b> is {@link AttackBuffs#radiusFor}, and the
 * <b>clock</b> is {@link AttackBuffRules#ticksFor}. Only three things are this class's own: the
 * percent ↔ amplifier mapping, the effect's registry path, and the applier. The stat's arithmetic
 * (base + modifiers, clamped at {@code Crit.MAX_CHANCE}) stays {@code Crit}'s, which is why nothing here touches
 * the attribute directly.
 *
 * <h2>Why the amplifier is in tenths of a percent</h2>
 * One amplifier step is 0.1% — {@link #STEPS_PER_PERCENT} steps to a whole percent. That is what makes 千歌's
 * 2.5% exact; a whole-percent step would force her clause to 2 or 3 and silently move a number the source states.
 * {@link #isRepresentable} reports any fraction that still does not fit (nothing shipped does).
 */
@ApiStatus.Internal
public final class CritChanceUps {

    /** Registry path of the effect: {@code astrum:crit_chance_up}. */
    public static final String PATH = "crit_chance_up";

    /** The effect's registry id, for log lines and for a client-side lookup. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** The translation key vanilla resolves for the effect's name, {@code effect.astrum.crit_chance_up}. */
    public static final String NAME_KEY = "effect." + ID.toLanguageKey();

    /** How many amplifier steps make one percentage point: ten, i.e. a step is 0.1%. */
    public static final double STEPS_PER_PERCENT = 10.0;

    /** The fraction of crit chance one amplifier step is worth. */
    public static final double FRACTION_PER_STEP = 0.001;

    private CritChanceUps() {
    }

    /** {@return the registered effect holder, or empty when the registry is not up yet} */
    public static Optional<Holder<MobEffect>> holder() {
        if (!PartyEffects.CRIT_CHANCE_UP.isBound()) {
            return Optional.empty();
        }
        return Optional.of(PartyEffects.CRIT_CHANCE_UP);
    }

    /**
     * {@return the amplifier that expresses {@code percent}, clamped into {@code [0, 255]}}
     *
     * <p>Never throws: this runs on an effect path, where a hostile number in a pack must become a logged no-op
     * rather than an exception. A {@code NaN}, an infinity or a non-positive percentage answers {@code 0}, and
     * the caller refuses such a hook before it reaches here.
     *
     * @param percent the increase in crit chance, in percentage points: {@code 5.0} = +5%
     */
    public static int amplifierFor(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0) {
            return 0;
        }
        return (int) Math.max(0.0, Math.min(AttackBuffRules.MAX_AMPLIFIER,
                Math.round(percent * STEPS_PER_PERCENT)));
    }

    /**
     * {@return the fraction an amplifier expresses — the inverse of {@link #amplifierFor}}
     *
     * <p>Read by {@link CritChanceUpEffect}'s modifier curve and by the tests, so "amplifier 25 means +2.5%" has
     * one implementation.
     *
     * @param amplifier the instance's amplifier
     */
    public static double fractionFor(int amplifier) {
        int clamped = Math.max(0, Math.min(AttackBuffRules.MAX_AMPLIFIER, amplifier));
        return clamped * FRACTION_PER_STEP;
    }

    /** {@return {@code amplifier} as the percentage a player reads} */
    public static double percentForAmplifier(int amplifier) {
        return fractionFor(amplifier) * 100.0;
    }

    /**
     * {@return whether {@code percent} survives the round trip through the amplifier unchanged}
     *
     * <p>{@code true} for any percentage quantised at one tenth of a point, which is every shipped clause (2.5,
     * 5, 10). A coarser value is quantised and the caller states that at WARN rather than silently handing the
     * party a different number.
     *
     * @param percent the declared increase, in percentage points
     */
    public static boolean isRepresentable(double percent) {
        if (!Double.isFinite(percent) || percent <= 0.0) {
            return false;
        }
        // Exact, not "close enough": 0.04 rounds to no grant at all, which would silently hand the party
        // nothing for a clause somebody wrote. Only values that are whole tenths of a point survive.
        return Math.abs(percentForAmplifier(amplifierFor(percent)) - percent) < 1.0e-6;
    }

    /**
     * Applies the buff to the caster and to every allied player around her.
     *
     * <p>Checked <b>once</b> before any receiver is touched — a malformed buff cannot be half-applied to the party
     * — and each receiver's application is wrapped, so one player's failure never costs the others. That is the
     * same discipline {@link CritDamageUps#apply} follows on the same cast path.
     *
     * @param caster      the player whose hook asked for it
     * @param percent     the increase in crit chance, in percentage points: {@code 5.0} = +5%
     * @param seconds     how long it lasts
     * @param characterId the character whose skill asked for it, for the log line
     * @param radius      the reach, empty for the party default
     * @return how many receivers hold the buff after the call — a refresh counts
     */
    public static int apply(LivingEntity caster, double percent, double seconds,
            ResourceLocation characterId, OptionalDouble radius) {
        if (caster == null) {
            return 0;
        }
        if (!Double.isFinite(percent) || !(percent > 0.0)) {
            Astrum.LOGGER.warn("astrum: crit_chance_up of {} wants {}%, which is not a buff (it must be above "
                    + "0); hook skipped. Check the character's effectParams.", characterId, percent);
            return 0;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            Astrum.LOGGER.warn("astrum: crit_chance_up of {} wants {} seconds, so there is nothing to apply; "
                    + "hook skipped.", characterId, seconds);
            return 0;
        }
        if (!isRepresentable(percent)) {
            Astrum.LOGGER.warn("astrum: crit_chance_up of {} wants {}%, which this effect can only express as "
                            + "{}% (amplifiers are tenths of a percent, 0-{}); the nearest value is used.",
                    characterId, percent, percentForAmplifier(amplifierFor(percent)),
                    AttackBuffRules.MAX_AMPLIFIER);
        }
        Optional<Holder<MobEffect>> effect = holder();
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: crit_chance_up is not registered yet, so the buff of {} was not "
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
        Astrum.LOGGER.debug("astrum: crit_chance_up of {} -> +{}% for {}s on {} of {} player(s) within {} "
                        + "blocks (amplifier {}, {} ticks; an existing instance is refreshed, never stacked)",
                characterId, percentForAmplifier(amplifier), seconds, applied, considered, reach, amplifier,
                ticks);
        return applied;
    }

    /**
     * {@return whether this receiver holds the buff after the call}
     *
     * <p>Asks {@code hasEffect} rather than returning {@code addEffect}'s answer, so a <b>refresh</b> counts:
     * vanilla answers {@code false} for an instance of the same type and amplifier with an equal-or-longer
     * duration.
     */
    private static boolean applyTo(LivingEntity receiver, Holder<MobEffect> effect, int ticks, int amplifier,
            ResourceLocation characterId) {
        try {
            receiver.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, false, true));
            boolean holds = receiver.hasEffect(effect);
            Astrum.LOGGER.debug("astrum: crit_chance_up on {} -> +{}% ({} ticks, amplifier {}) holds={}",
                    receiver.getName().getString(), percentForAmplifier(amplifier), ticks, amplifier, holds);
            return holds;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: crit_chance_up of {} threw on {}; the other receivers still run",
                    characterId, receiver.getName().getString(), e);
            return false;
        }
    }
}