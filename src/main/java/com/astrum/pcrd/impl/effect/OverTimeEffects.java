package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The two over-time families: heal-over-time is vanilla's {@code regeneration}, charge-over-time is this
 * mod's {@code astrum:charge_regen}.</b>
 *
 * <h2>The four types, and why they are four</h2>
 * The human named them one by one — 「新增缓慢回血，缓慢恢复充能的，给予他人缓慢回血，给予他人缓慢恢复充能」 — so they are
 * four {@link EffectType} values. What is <b>not</b> four is the implementation: {@code REGEN_SELF} and
 * {@code REGEN_AREA} are one call with two receiver sets, and {@code CHARGE_REGEN_SELF} /
 * {@code CHARGE_REGEN_AREA} are the <b>same registered effect</b> applied to two receiver sets. Four names in the
 * data, two mechanisms in the code, because the only thing that differs between a self and an area form is the
 * radius — and this project has paid for saying one thing twice often enough.
 *
 * <h2>Heal over time: vanilla's own effect, and what "coexist" can honestly mean</h2>
 * 「使用类似生命恢复的药水效果，可以跟游戏内的其他原版效果共存」. So this applies a real
 * {@code minecraft:regeneration} instance rather than healing a little each tick of our own — the mechanic a
 * player already knows, with vanilla's amplifier curve, vanilla's expiry and vanilla's HUD marker.
 *
 * <p><b>The honest limitation, stated rather than papered over.</b> Vanilla keys a living entity's effects by
 * <b>type</b> ({@code LivingEntity.activeEffects} is a {@code Map<Holder<MobEffect>, MobEffectInstance>}), so our
 * instance and any other source's {@code regeneration} are <b>one entry</b>: {@code MobEffectInstance#update}
 * keeps the <b>higher amplifier</b> and the longer of the two durations. That gives us exactly three true
 * statements and one that would be false:
 *
 * <ul>
 *   <li>our regen never <b>removes</b> or shortens somebody else's stronger regeneration;</li>
 *   <li>somebody else's stronger regeneration never shortens ours;</li>
 *   <li>a potion and a skill regen <b>overlapping at the same amplifier</b> extend the duration to the longer of
 *       the two rather than adding two heal rates.</li>
 *   <li><b>But</b> a weaker rung landing on a player who already has a stronger regeneration adds
 *       <b>nothing</b> — it is swallowed. "Coexist" therefore does not mean "stack"; it means "never fight".
 *       Making them add would require our own effect type and a per-instance tick, which is the change the human
 *       deferred for the <em>instant</em> heals (see {@code task-49-report.md}); it was not asked for here.</li>
 * </ul>
 *
 * <h2>Charge over time: a mod effect, because vanilla has nothing to reuse</h2>
 * There is no vanilla "charge the ultimate gauge over time" — the gauge is this mod's own state. So
 * {@code CHARGE_REGEN_SELF} / {@code CHARGE_REGEN_AREA} apply {@code astrum:charge_regen}, whose
 * {@code applyEffectTick} pays one instalment through {@code ChargeKernel}. That is the sanctioned per-instance
 * hook: vanilla calls it for <b>one instance on one entity</b> out of that entity's own effect map, so there is no
 * tick listener and nothing is enumerated.
 *
 * <p><b>Merge, strongest wins</b> — the human's ruling 「选择合并，取最强」. One registered type with the tier in the
 * amplifier gives that for free: vanilla merges two instances of one type keeping the higher amplifier, so two
 * characters' 20%/s and 40%/s give 40%/s rather than 60%. That is also why the area form is the <em>same</em> type
 * as the self form: an area cast and a self cast landing on one player are one entry.
 *
 * <h2>Both walk the same receiver rule the heal does</h2>
 * {@code REGEN_AREA} and {@code CHARGE_REGEN_AREA} use {@link AllySelector} through {@code AreaBuffs}'s shape, so
 * 「味方全体」 means the same thing here as in every other party effect. The self forms apply to the caster alone
 * and never ask.
 */
@ApiStatus.Internal
public final class OverTimeEffects {

    /** Registry path of the charge-over-time effect: {@code astrum:charge_regen}. */
    public static final String CHARGE_REGEN_PATH = "charge_regen";

    /** {@code astrum:charge_regen}, for attribution and for the sprite test. */
    public static final ResourceLocation ID = InternalIds.id(CHARGE_REGEN_PATH);

    /**
     * Charge points a second at <b>amplifier 0</b>: 2, so the five rungs are 2/4/6/8/10 a second.
     *
     * <h2>Why 2 and not 1</h2>
     * The gauge holds 100 and {@code ubChargePerSecond} pays <b>0.2</b> a second — that trickle is the
     * "兜底" floor, explicitly 每秒微量. A skill that costs a slot and a duration and pays the same trickle would
     * be indistinguishable from not having fired, so one rung is deliberately ten times the trickle. Ten a
     * second at the top rung is ten seconds to fill an empty bar; the ladder is one number here so it can be
     * retuned in one place.
     *
     * <h2>This was 4, and the human halved it after playing</h2>
     * 「所有 tp 充能效果都太好了，削弱一半。」 The first value made the top rung fill an empty bar in five
     * seconds, which he judged too strong against the rest of the kit. Halving the constant halves every rung
     * at once, which is why the ladder is expressed as one multiplier rather than five literals.
     */
    public static final int CHARGE_PER_SECOND_PER_TIER = 2;

    /**
     * Registry path of the tally-scaled charge effect: {@code astrum:charge_counter}.
     *
     * <p>A second effect type rather than a flag on {@code charge_regen}, because the two have opposite merge
     * rules: {@code charge_regen}'s magnitude is its amplifier (so vanilla's strongest-wins merge is the
     * human's 「取最强」), while this one's magnitude is a formula over a live tally — see
     * {@link CounterChargeRegenEffect} for why a rung cannot express it.
     */
    public static final String COUNTER_CHARGE_PATH = "charge_counter";

    /** {@code astrum:charge_counter}, for attribution and for the sprite test. */
    public static final ResourceLocation COUNTER_CHARGE_ID = InternalIds.id(COUNTER_CHARGE_PATH);

    /**
     * Amplifier steps per charge point a second: ten, so one step is 0.1 points (1 TP).
     *
     * <p>The unit both halves of {@link #counterAmplifierFor} are expressed in — one tenth of a point a second
     * — which is what lets the two data numbers share the amplifier's two nibbles exactly.
     */
    public static final int COUNTER_RATE_STEPS = 10;

    /** Particle tint for {@code astrum:charge_regen}: the gauge's own gold, so it reads as the bar filling. */
    public static final int CHARGE_REGEN_COLOUR = 0xC8A24A;

    /** Minecraft's fixed tick rate. The single place the {@code 20} appears in this file. */
    private static final int TICKS_PER_SECOND = 20;

    private OverTimeEffects() {
    }

    /**
     * {@return the vanilla amplifier a rung is worth}
     *
     * <p>Tier 1 is amplifier <b>0</b> for these two families, not 1: vanilla's regeneration and our charge-regen
     * both encode strength <em>as</em> the amplifier, so the rung <b>is</b> the level. That is the same reading
     * {@code AttackAreaRules} makes for its ladder and the opposite of {@code DefenceRules}'s "one rung already
     * one step", because here the amplifier is the quantity rather than a code for a fraction.
     *
     * @param tier a 1-based rung, already clamped by {@code TieredAmounts}
     */
    public static int amplifierForTier(int tier) {
        return Math.max(0, tier - 1);
    }

    /**
     * {@return how many charge points a second {@code amplifier} pays}
     *
     * <p>The one mapping from a stored amplifier to a rate, read by {@code ChargeRegenEffect}'s tick and by
     * nothing else. Amplifier 0 is <b>already</b> {@value #CHARGE_PER_SECOND_PER_TIER} a second, for the reason
     * the constant gives — a lowest rung that did nothing would put a HUD entry on the player saying something
     * untrue, which is the rule {@code ChargeKernel.rateForAmplifier} states for its own ladder.
     *
     * @param amplifier a clamped amplifier, {@code 0..4}
     */
    public static double chargePointsPerSecond(int amplifier) {
        int levels = Math.max(0, Math.min(4, amplifier)) + 1;
        return (double) levels * CHARGE_PER_SECOND_PER_TIER;
    }

    /**
     * {@return the per-tick instalment {@code amplifier} pays}
     *
     * <p>Divided here and nowhere else, exactly like {@code ChargeKernel.naturalRegen}: a caller that divided
     * again would multiply the rate by 400. The result is fractional on purpose (0.2 a tick at tier 1) and is
     * carried by {@code ChargeRegenEffect} rather than truncated per tick, or a tier-1 regen would pay nothing.
     *
     * @param amplifier a clamped amplifier, {@code 0..4}
     */
    public static double chargePointsPerTick(int amplifier) {
        return chargePointsPerSecond(amplifier) / TICKS_PER_SECOND;
    }

    /**
     * Applies heal-over-time to {@code receiver} — vanilla's {@code regeneration}, for {@code ticks}.
     *
     * <h2>Why the amplifier is not scaled by anything</h2>
     * It is an integer effect level, and rounding it would turn "regeneration II for half as long" into
     * "regeneration I": the same rule {@code EffectQuantities} documents for {@code apply_effect}. The
     * <b>duration</b> is the quantity the multiplier chain scales, and it arrives already scaled.
     *
     * <p>Wrapped per receiver: one player's bad health handling must not cost the party's buff, the same policy
     * {@code AreaBuffs} and {@code Heals} follow.
     *
     * @param receiver  the body to heal over time; {@code null} is ignored
     * @param tier      the clamped rung
     * @param ticks     how long it lasts, already scaled and floored at one tick
     * @param characterId the character that asked, for the log line
     * @return whether the instance was accepted
     */
    public static boolean applyRegen(LivingEntity receiver, int tier, int ticks,
            ResourceLocation characterId) {
        if (receiver == null || ticks <= 0) {
            return false;
        }
        int amplifier = amplifierForTier(tier);
        try {
            // ambient = false, visible = true, showIcon = true: a status a player should see and read, exactly
            // like the potion it is modelled on.
            boolean applied = receiver.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks,
                    amplifier, false, true, true));
            Astrum.LOGGER.debug("astrum: {} on {} -> regeneration {} for {}t applied={}",
                    characterId, receiver.getName().getString(), amplifier + 1, ticks, applied);
            return applied;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: regeneration of {} threw on {}; the other receivers still run",
                    characterId, receiver.getName().getString(), e);
            return false;
        }
    }

    /**
     * Applies charge-over-time to {@code receiver}, whose tick pays through {@code ChargeKernel}.
     *
     * <p>The same merge rule as the heal, one paragraph up: this is one effect type, so two casts on one player
     * become one instance with the stronger amplifier. Nothing here decides that — vanilla's own
     * {@code MobEffectInstance#update} does, which is why there is no per-slot type.
     *
     * @param receiver  the body to charge over time; {@code null} is ignored
     * @param tier      the clamped rung
     * @param ticks     how long it lasts, already scaled and floored at one tick
     * @param characterId the character that asked, for the log line
     * @return whether the instance was accepted
     */
    public static boolean applyChargeRegen(LivingEntity receiver, int tier, int ticks,
            ResourceLocation characterId) {
        if (receiver == null || ticks <= 0) {
            return false;
        }
        Optional<Holder<MobEffect>> holder = PartyEffects.chargeRegenHolder();
        if (holder.isEmpty()) {
            Astrum.LOGGER.warn("astrum: charge_regen is not registered yet, so the {} of {} was not applied",
                    EffectType.CHARGE_REGEN_SELF, characterId);
            return false;
        }
        int amplifier = amplifierForTier(tier);
        try {
            boolean applied = receiver.addEffect(new MobEffectInstance(holder.get(), ticks, amplifier,
                    false, true, true));
            Astrum.LOGGER.debug("astrum: {} on {} -> charge_regen amp {} ({} points/s) for {}t applied={}",
                    characterId, receiver.getName().getString(), amplifier,
                    chargePointsPerSecond(amplifier), ticks, applied);
            return applied;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: charge_regen of {} threw on {}; the other receivers still run",
                    characterId, receiver.getName().getString(), e);
            return false;
        }
    }

    /**
     * {@return the amplifier that carries a tally-scaled regen's two rates}
     *
     * <h2>The encoding, and why it exists</h2>
     * A {@code MobEffectInstance} has exactly one per-instance number, the byte-sized amplifier, and this
     * effect's rate has two data parts ({@code base} and {@code perMark}). Both are small — 雪菲's 1.0 and 0.1
     * points a second — so the amplifier's two nibbles carry them in <b>tenths of a point a second</b>:
     *
     * <pre>
     *   amplifier = round(base × 10) | (round(perMark × 10) &lt;&lt; 4)
     *   雪菲: 1.0 and 0.1  ->  10 | (1 &lt;&lt; 4) = 26
     * </pre>
     *
     * <p>The range is therefore 0..1.5 points a second per half (15 steps of a nibble), and a clause outside it
     * is refused with a WARN rather than silently clamped — a rate that quietly changes is the defect this
     * project treats as the worst of the three.
     *
     * @param base    charge points a second before any mark
     * @param perMark charge points a second for each mark the holder carries
     */
    public static int counterAmplifierFor(double base, double perMark) {
        return stepsFor(base) | (stepsFor(perMark) << 4);
    }

    /** {@return {@code rate} in tenths of a point a second, {@code -1} when it cannot be expressed} */
    static int stepsFor(double rate) {
        if (!Double.isFinite(rate) || rate < 0.0) {
            return -1;
        }
        long steps = Math.round(rate * COUNTER_RATE_STEPS);
        return steps > 15 ? -1 : (int) steps;
    }

    /** {@return the fixed part of a tally-scaled regen, in charge points a second} */
    public static double counterBaseOf(int amplifier) {
        return (amplifier & 0xF) / (double) COUNTER_RATE_STEPS;
    }

    /** {@return the per-mark part of a tally-scaled regen, in charge points a second} */
    public static double counterPerMarkOf(int amplifier) {
        return ((amplifier >> 4) & 0xF) / (double) COUNTER_RATE_STEPS;
    }

    /**
     * {@return the rate a tally-scaled regen pays at {@code marks}, in charge points a second}
     *
     * <p>The one reading of the formula, used by the effect's tick and by the tests: {@code base +
     * perMark × marks}. Nothing clamps the marks — {@code Counters.countOf} already answers a value in
     * {@code [0, its cap]}.
     *
     * @param amplifier the instance's encoded rates
     * @param marks     how many marks the holder has right now
     */
    public static double counterChargePointsPerSecond(int amplifier, int marks) {
        int count = Math.max(0, marks);
        return counterBaseOf(amplifier) + counterPerMarkOf(amplifier) * count;
    }

    /**
     * {@return the per-tick instalment of a tally-scaled regen}
     *
     * <p>Divided here and nowhere else, exactly like {@link #chargePointsPerTick}: a caller that divided again
     * would multiply the rate by 400. The result is fractional on purpose and is carried by {@code UbCharge},
     * which takes a {@code double}.
     *
     * @param amplifier the instance's encoded rates
     * @param marks     how many marks the holder has right now
     */
    public static double counterChargePointsPerTick(int amplifier, int marks) {
        return counterChargePointsPerSecond(amplifier, marks) / TICKS_PER_SECOND;
    }

    /**
     * Applies the tally-scaled charge regen to {@code receiver} for {@code seconds}.
     *
     * <p>Never throws and reports a refused clause rather than applying a zero-tick or zero-rate instance: a
     * HUD entry that pays nothing is a state the player can see and cannot feel.
     *
     * @param receiver    the body to charge over time; {@code null} is ignored
     * @param base        charge points a second before any mark
     * @param perMark     charge points a second for each mark the holder carries
     * @param seconds     how long it lasts
     * @param characterId the character that asked, for the log line
     * @return whether the instance was accepted
     */
    public static boolean applyCounterChargeRegen(LivingEntity receiver, double base, double perMark,
            double seconds, ResourceLocation characterId) {
        if (receiver == null) {
            return false;
        }
        int amplifier = counterAmplifierFor(base, perMark);
        if (amplifier < 0) {
            Astrum.LOGGER.warn("astrum: counter_charge_regen of {} wants {} + {} a mark a second, which this "
                    + "effect can only express up to {} + {}; the clause was skipped rather than silently "
                    + "changed. Check the character's effectParams.", characterId, base, perMark,
                    15.0 / COUNTER_RATE_STEPS, 15.0 / COUNTER_RATE_STEPS);
            return false;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            Astrum.LOGGER.warn("astrum: counter_charge_regen of {} wants {} seconds, so there is nothing to "
                    + "apply; hook skipped.", characterId, seconds);
            return false;
        }
        Optional<Holder<MobEffect>> holder = PartyEffects.counterChargeRegenHolder();
        if (holder.isEmpty()) {
            Astrum.LOGGER.warn("astrum: charge_counter is not registered yet, so the {} of {} was not applied",
                    EffectType.COUNTER_CHARGE_REGEN, characterId);
            return false;
        }
        try {
            boolean applied = receiver.addEffect(new MobEffectInstance(holder.get(), ticks, amplifier,
                    false, true, true));
            Astrum.LOGGER.debug("astrum: {} on {} -> charge_counter amp {} ({} + {} a mark a second) for {}t "
                            + "applied={}", characterId, receiver.getName().getString(), amplifier, base, perMark,
                    ticks, applied);
            return applied;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: charge_counter of {} threw on {}; the other receivers still run",
                    characterId, receiver.getName().getString(), e);
            return false;
        }
    }

    /** {@return the duration a rung's regen lasts when the hook declares none, in seconds} */
    public static double defaultSeconds() {
        return AreaBuffs.DEFAULT_SECONDS;
    }

    /**
     * Applies an over-time effect to the caster <b>and every ally inside {@code radius}</b> — the area forms.
     *
     * <h2>Why the receiver set is borrowed rather than written</h2>
     * 「味方全体」 has one meaning in this mod, and it is {@code Heals.receivers}' reading of
     * {@code AllySelector} plus {@code AttackBuffs.radiusFor}: the caster included, the boundary included, the
     * dead and the spectators already dropped by {@code AllyCandidates}. A second walk over
     * {@code level.players()} here would be a fifth copy of "who is an ally" — the exact defect this project has
     * shipped three times. So the receivers come from that one call.
     *
     * @param caster      the player whose hook fired
     * @param tier        the clamped rung
     * @param ticks       how long it lasts, already scaled and floored at one tick
     * @param characterId the character that asked, for the log line
     * @param radius      the hook's radius, empty for the party default
     * @param regen       {@code true} for heal-over-time, {@code false} for charge-over-time
     * @return how many receivers accepted the instance
     */
    public static int applyToParty(LivingEntity caster, int tier, int ticks, ResourceLocation characterId,
            java.util.OptionalDouble radius, boolean regen) {
        if (caster == null || ticks <= 0) {
            return 0;
        }
        if (!(caster instanceof net.minecraft.server.level.ServerPlayer player)) {
            // A non-player caster has no party: the buff lands on the body that asked, the same degradation
            // Heals and AttackBuffs document for their own non-player branch.
            return (regen ? applyRegen(caster, tier, ticks, characterId)
                    : applyChargeRegen(caster, tier, ticks, characterId)) ? 1 : 0;
        }
        int reached = 0;
        // One pool, measured once: `around` walks the level's player list and measures five distances, so calling
        // it per receiver would redo that work N times and could even see a different world mid-loop.
        AllyCandidates.Pool pool = AllyCandidates.around(player);
        for (AllySelector.Candidate candidate : Heals.receivers(pool.candidates(), player.getUUID(),
                AllyCandidates.configuredMode(), radiusFor(radius))) {
            ServerPlayer receiver = pool.players().get(candidate.id());
            if (receiver == null) {
                continue;
            }
            boolean applied = regen
                    ? applyRegen(receiver, tier, ticks, characterId)
                    : applyChargeRegen(receiver, tier, ticks, characterId);
            if (applied) {
                reached++;
            }
        }
        return reached;
    }

    /**
     * {@return the reach an area over-time effect covers}
     *
     * <p>Delegated to {@code AttackBuffs.radiusFor}, which is where every party radius in this kernel is read:
     * an absent radius is the party default, {@code 0} is a legitimate 「自分」, and nonsense falls back with a
     * warning. A second reading of "how far is 味方全体" would drift the first time the default was tuned.
     *
     * @param declared the hook's radius, if it declared one
     */
    public static double radiusFor(java.util.OptionalDouble declared) {
        return AttackBuffs.radiusFor(declared);
    }

    /** {@return the tier block a hook declared, or {@link TieredParams#FIRST}} */
    public static TieredParams params(TieredParams declared) {
        return declared == null ? TieredParams.FIRST : declared;
    }
}
