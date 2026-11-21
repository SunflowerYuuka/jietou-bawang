package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;import com.astrum.pcrd.impl.combat.EffectQuantities;
import com.astrum.pcrd.impl.combat.WarnOnce;
import com.astrum.pcrd.impl.ub.ChargeKernel;
import com.astrum.pcrd.impl.ub.UbCharge;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The <b>one</b> place a {@link TieredParams} becomes an effect somebody is carrying.
 *
 * <h2>Why this exists</h2>
 * There are two ways a character makes a tiered effect happen, and they are not two mechanics: a
 * <b>hook</b> (a landed swing's effect, reached from {@code EffectExecutors}) and an <b>ultimate</b> (an
 * on-cast or on-hit effect, reached from {@code UbDamageSettlement}). Both need the same five steps —
 * clamp the tier ({@code TieredAmounts}), pick the receiver set ({@code AreaBuffs} /
 * {@code PercentHeals} / {@code AllySelector}), turn the tier into an amplifier
 * ({@code AttackAreaRules} / {@code DefenceRules} / {@code HasteRules} / {@code ChargeKernel} /
 * {@code DefenceDownRules}), look the registered effect up ({@code PartyEffects}) and apply it — and
 * writing that chain twice is precisely how one of the two paths ends up applying a different rung, a
 * different duration or a different receiver set than the other. This project has shipped three bugs
 * from having two answers to one question.
 *
 * <p>So this class is that chain, once. {@code EffectExecutors} delegates its arms here, and
 * {@code UbDamageSettlement} calls it for the effects an ultimate declares. No number, receiver rule or
 * amplifier mapping is decided here: every one is read from the class that already owns it, which is what
 * makes "a tier means the same thing on a swing and on an ultimate" a property of the code rather than a
 * promise.
 *
 * <h2>Two receiver sets, and why they are two methods</h2>
 * <ul>
 *   <li>{@link #applyParty} — the caster <b>and her party</b>, 「味方全体」/「自分」. The set is
 *       {@code AreaBuffs} / {@code PercentHeals}'s, over {@code AllySelector} and the configured ally
 *       policy, so this method never decides who is an ally.</li>
 *   <li>{@link #applyVictim} — <b>one body the effect landed on</b>, 「敵」. Called once per victim by
 *       whoever owns the loop, which for an ultimate is the settlement (only for the bodies a hit really
 *       landed on) and for a hook is {@code ctx.target()}.</li>
 * </ul>
 *
 * <p>Which of the ten types belongs to which set is {@link EffectType#reachesParty()} /
 * {@link EffectType#reachesVictim()} — declared on the enum, because it is a property of what the effect
 * <em>is</em>, and read here so there is one copy. Two methods rather than one with a flag because the
 * two take different arguments (a caster vs a victim): a caller that mixed them up would be applying
 * 「味方全体の攻撃力アップ」 to the enemy, and the signatures make that impossible to type.
 *
 * <h2>The multiplier chain, and where it does — and does not — reach</h2>
 * A tiered effect's <b>strength is its tier</b>, so nothing here scales an amplifier or a fraction:
 * scaling a tier-2 party buff by a rank-5 slot's {@code 0.1} would make her ultimate weaker for having
 * been cast from the fifth slot, which nothing in the spec or the fiction says. The one quantity that
 * <em>is</em> scaled on this path is a debuff's <b>duration</b> (§5.2 lists 持续时间 among the diminishing
 * quantities), which is why {@link #applyVictim} takes a {@code magnitudeMultiplier}: a hook passes its
 * own composed slot multiplier, and an ultimate passes {@code 1.0} because §5.4 excludes the ultimate
 * from diminishing returns entirely (one cast at a time, mutually exclusive). Stating it as a parameter
 * keeps the scaling rule in one place while honouring both callers' contracts.
 *
 * <h2>Failure policy</h2>
 * Never throws. This is reached from a damage sequence and from a cast path where the gauge has already
 * been spent, so an unknown effect id, an unbound registry or one receiver's bad health handling must
 * become a warning — once per datapack defect — and never a lost cast.
 */
@ApiStatus.Internal
public final class TieredEffects {

    /**
     * The duration a tiered debuff gets when its hook names none, in seconds.
     *
     * <p>The same eight-second scale {@code AreaBuffs.DEFAULT_SECONDS} uses for the buffs, so a pack that
     * writes {@code "tier": 2} and nothing else gets a debuff of the same order as its buffs rather than a
     * zero-tick one that silently does nothing.
     */
    public static final double FALLBACK_SECONDS = AreaBuffs.DEFAULT_SECONDS;

    /** Problems already reported, keyed by what was wrong rather than by who noticed. */
    private static final WarnOnce REPORTED = new WarnOnce();

    private TieredEffects() {
    }

    /**
     * Applies a party-scope tiered effect to the caster <b>and her party</b>, or to the caster alone.
     *
     * <p>Which of the two is the effect's own business, not this method's: {@code HEAL_SELF} has exactly
     * one receiver by definition, and every other party type goes through {@link AreaBuffs} /
     * {@link PercentHeals}, whose radius reading ({@code 0} = 「自分」, absent = the documented party
     * default) is {@code AttackBuffs.radiusFor}'s.
     *
     * @param caster      the player whose hook or ultimate asked
     * @param type        a type for which {@link EffectType#reachesParty()} is true
     * @param params      the tier plus the duration and reach
     * @param characterId the character that asked, for the log line and for the once-per-defect report
     * @return how many receivers gained health or hold the buff afterwards; a refresh counts
     */
    public static int applyParty(LivingEntity caster, EffectType type, TieredParams params,
            ResourceLocation characterId) {
        return applyParty(caster, type, params, characterId, 0);
    }

    /**
     * Applies a party-scope tiered effect with the caster's <b>level band</b> added to the declared tier.
     *
     * <h2>Why the band is a parameter and not read here</h2>
     * Two callers reach this method — a hook (which has the character's level in its {@code HookContext}) and an
     * ultimate's settlement (which has the caster's roster row) — and neither is this class's business to
     * re-derive. So the band arrives as a number, resolved by the door that knows the character, and this method
     * only hands it to {@link TieredAmounts#resolve(EffectType, TieredParams, int)} — the one place the addition
     * and the clamp happen.
     *
     * <p><b>The EX path does not come through here.</b> An EX passive resolves its own rung from
     * {@code ExPassive.rungFor} and applies it with band {@code 0}, because the human's rule is that EX gets no
     * level bonus. See {@code ExPassives.tierOf}.
     *
     * @param caster      the player whose hook or ultimate asked
     * @param type        a type for which {@link EffectType#reachesParty()} is true
     * @param params      the tier plus the duration and reach
     * @param characterId the character that asked, for the log line and for the once-per-defect report
     * @param bandBonus   how many rungs the caster's level adds, from {@code LevelBands.bonusAt}
     * @return how many receivers gained health or hold the buff afterwards; a refresh counts
     */
    public static int applyParty(LivingEntity caster, EffectType type, TieredParams params,
            ResourceLocation characterId, int bandBonus) {
        if (caster == null || type == null) {
            return 0;
        }
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        TieredAmounts.Amount amount = TieredAmounts.resolve(type, tiered, bandBonus);
        try {
            return switch (type) {
                case HEAL_AREA -> {
                    int healed = PercentHeals.applyArea(caster, amount.fraction(), characterId,
                            tiered.radius());
                    Astrum.LOGGER.debug("astrum: heal_area tier {} ({}% of max health) within {} blocks of "
                                    + "{} -> {} receiver(s) gained health", amount.tier(),
                            Math.round(amount.fraction() * 100.0), AreaBuffs.radiusFor(tiered.radius()),
                            name(caster), healed);
                    yield healed;
                }
                case HEAL_SELF -> {
                    int healed = PercentHeals.applySelf(caster, amount.fraction(), characterId);
                    Astrum.LOGGER.debug("astrum: heal_self tier {} ({}% of {}'s max health) -> gained={}",
                            amount.tier(), Math.round(amount.fraction() * 100.0), name(caster), healed > 0);
                    yield healed;
                }
                // The amplifier comes from each effect's own rules class, and so does the fraction the
                // log line names — this method performs no tier arithmetic at all.
                case BUFF_ATTACK_AREA -> applyTimed(caster, PartyEffects.attackAreaHolder(),
                        AttackAreaRules.amplifierForTier(amount.tier()), tiered, type,
                        AttackAreaRules.fractionForTier(amount.tier()), characterId);
                // 「魔法攻击力アップ」: the same ladder and the same amplifier mapping, on the magic stat's
                // own effect — but the magic STEP, 26% a rung, not the physical 20%. The two stats have
                // different bases (ATTACK_DAMAGE ~1-2, magic_damage 0 and written as flat points), so the
                // human's rule is that the magic rung is worth 5-10% more; see AttackAreaRules.MAGIC_STEP.
                case BUFF_MAGIC_ATTACK_AREA -> applyTimed(caster, PartyEffects.magicAttackAreaHolder(),
                        AttackAreaRules.amplifierForTier(amount.tier()), tiered, type,
                        AttackAreaRules.magicFractionForTier(amount.tier()), characterId);
                case BUFF_PHYS_DEFENCE_AREA -> applyTimed(caster, PartyEffects.physDefenceHolder(),
                        DefenceRules.amplifierFor(amount.tier()), tiered, type,
                        DefenceRules.reductionFor(amount.tier()), characterId);
                case BUFF_MAGIC_DEFENCE_AREA -> applyTimed(caster, PartyEffects.magicDefenceHolder(),
                        DefenceRules.amplifierFor(amount.tier()), tiered, type,
                        DefenceRules.reductionFor(amount.tier()), characterId);
                case HASTE -> applyTimed(caster, PartyEffects.hasteHolder(),
                        HasteRules.amplifierForTier(amount.tier()), tiered, type,
                        HasteRules.fractionForTier(amount.tier()), characterId);
                // 召唤精灵: the amplifier IS the count (tier 1..5 -> one..five spirits), so the only number
                // that travels is the rung and the only receiver is the caster — a summon orbits *her*, and
                // AreaBuffs with a radius of 0 would still be the wrong set for a ring nobody else can own.
                case SUMMON_SPIRIT -> {
                    int received = Spirits.apply(caster, amount.tier(), tiered, characterId);
                    Astrum.LOGGER.debug("astrum: summon_spirit tier {} -> {} spirit(s) for {}s on {}", 
                            amount.tier(), Spirits.countForTier(amount.tier()),
                            tiered.seconds().orElse(AreaBuffs.DEFAULT_SECONDS), name(caster));
                    yield received;
                }
                // Tier N is multiplier 1 + 0.25N, encoded as amplifier N-1 by the kernel's own inverse, so
                // the amplifier arithmetic stays in ChargeKernel rather than being copied here.
                case CHARGE_RATE_UP -> {
                    int amplifier =
                            ChargeKernel.amplifierForRate(ChargeKernel.NEUTRAL_RATE + amount.fraction());
                    yield applyTimed(caster, PartyEffects.chargeRateUpHolder(), amplifier, tiered, type,
                            ChargeKernel.rateForAmplifier(amplifier) - ChargeKernel.NEUTRAL_RATE,
                            characterId);
                }
                // 護盾: tier N is 20N% of each receiver's own max health in absorption, granted by Shields
                // so that it stacks with vanilla's golden-apple absorption instead of replacing it. The
                // per-receiver health is why this cannot go through applyTimed (which takes one amount).
                //
                // The three arms are ONE mechanism, one line apart from each other on purpose: the only thing a
                // channel changes is which half of the damage the layer absorbs (ShieldKind), and the ladder,
                // the clock, the receiver set and the mirroring are the shared barrier's. Splitting them into
                // three code paths is how a restriction would silently come with a different rung.
                case SHIELD, SHIELD_PHYS, SHIELD_MAGIC -> {
                    int reached = Shields.applyFraction(caster, Shields.kindOf(type), amount.fraction(), tiered,
                            characterId);
                    Astrum.LOGGER.debug("astrum: {} tier {} ({}% of each receiver's max health) for {}s "
                                    + "within {} blocks of {} -> {} receiver(s)", type, amount.tier(),
                            Math.round(amount.fraction() * 100.0),
                            tiered.seconds().orElse(Shields.DEFAULT_SECONDS),
                            AreaBuffs.radiusFor(tiered.radius()), name(caster), reached);
                    yield reached;
                }
                // ---------------------------------------------------------------- 嘲讽
                //
                // Party-scoped, but ONE receiver: the taunter is the caster, and `radius` is not read here
                // because the reach belongs to the sweep (`Taunts.RADIUS`), not to the receiver set. The
                // duration is the ordinary `seconds` of any timed tiered effect.
                case TAUNT -> {
                    int called = Taunts.apply(caster, amount.tier(), tiered, characterId);
                    Astrum.LOGGER.debug("astrum: taunt tier {} for {}s on {} -> {} mob(s) called",
                            amount.tier(), tiered.seconds().orElse(AreaBuffs.DEFAULT_SECONDS),
                            name(caster), called);
                    yield called;
                }
                // ---------------------------------------------------------------- the two transfers
                //
                // ONE receiver each, chosen by TransferTarget: the nearest ally who has a UB gauge, else the
                // caster. Both arms call that one function, which is the human's explicit instruction
                // (「他奶同他充」), so the two can never disagree about who they land on.
                //
                // TRANSFER_HEAL's amount is the instant heal's own fraction (20% a rung, the HEAL_SELF
                // convention) applied to THAT ONE receiver — 「他奶就是套用瞬间回血的那个效果」. It goes through
                // PercentHeals, the one percentage-heal implementation, with a singleton receiver list so the
                // percentage is taken against the receiver's own max health rather than the caster's.
                case TRANSFER_CHARGE -> {
                    LivingEntity receiver = TransferTarget.of(caster, TransferTarget.radiusFor(tiered.radius()));
                    if (receiver == null) {
                        yield 0;
                    }
                    // The charge ladder is EffectTiers' (6 points a rung), read through the same Amount every
                    // other arm reads — no second scale for "how much is a rung" here.
                    double points = amount.fraction();
                    boolean credited = receiver instanceof net.minecraft.server.level.ServerPlayer player
                            && points > 0.0 && UbCharge.apply(player, characterId, points);
                    Astrum.LOGGER.debug("astrum: transfer_charge tier {} ({} points) from {} -> {} "
                                    + "(ally with a gauge? {} ) credited={}", amount.tier(), points,
                            name(caster), name(receiver), receiver != caster, credited);
                    yield credited ? 1 : 0;
                }
                case TRANSFER_HEAL -> {
                    LivingEntity receiver = TransferTarget.of(caster, TransferTarget.radiusFor(tiered.radius()));
                    if (receiver == null) {
                        yield 0;
                    }
                    int healed = PercentHeals.applyTo(receiver, amount.fraction(), characterId);
                    Astrum.LOGGER.debug("astrum: transfer_heal tier {} ({}% of the receiver's max health) "
                                    + "from {} -> {} (ally with a gauge? {}) -> healed={}", amount.tier(),
                            Math.round(amount.fraction() * 100.0), name(caster), name(receiver),
                            receiver != caster, healed > 0);
                    yield healed;
                }
                // ---------------------------------------------------------------- the four over-time effects
                //
                // Heal over time is vanilla's `regeneration` (OverTimeEffects.applyRegen) and charge over time is
                // `astrum:charge_regen` (OverTimeEffects.applyChargeRegen). The self forms have one receiver each
                // and never ask who is nearby; the area forms use the party receiver set the rest of the kernel
                // uses, so 「味方全体」 means the same thing here.
                case REGEN_SELF, CHARGE_REGEN_SELF -> {
                    int ticks = AreaBuffs.ticksFor(tiered);
                    boolean regen = type == EffectType.REGEN_SELF;
                    if (ticks <= 0) {
                        Astrum.LOGGER.debug("astrum: {} declared 0 seconds, nothing to apply for {}", type,
                                characterId);
                        yield 0;
                    }
                    boolean applied = regen
                            ? OverTimeEffects.applyRegen(caster, amount.tier(), ticks, characterId)
                            : OverTimeEffects.applyChargeRegen(caster, amount.tier(), ticks, characterId);
                    Astrum.LOGGER.debug("astrum: {} tier {} for {}t on {} -> applied={}", type, amount.tier(),
                            ticks, name(caster), applied);
                    yield applied ? 1 : 0;
                }
                case REGEN_AREA, CHARGE_REGEN_AREA -> {
                    int ticks = AreaBuffs.ticksFor(tiered);
                    if (ticks <= 0) {
                        Astrum.LOGGER.debug("astrum: {} declared 0 seconds, nothing to apply for {}", type,
                                characterId);
                        yield 0;
                    }
                    boolean regen = type == EffectType.REGEN_AREA;
                    // The same receiver set every other 味方全体 effect uses, including the caster: a duration
                    // buff on 「味方全体」 reaches her too, exactly as HealArea does.
                    int reached = OverTimeEffects.applyToParty(caster, amount.tier(), ticks, characterId,
                            tiered.radius(), regen);
                    Astrum.LOGGER.debug("astrum: {} tier {} for {}t within {} blocks of {} -> {} receiver(s)",
                            type, amount.tier(), ticks, AreaBuffs.radiusFor(tiered.radius()), name(caster),
                            reached);
                    yield reached;
                }
                // ---------------------------------------------------------------- the two immunity channels
                //
                // Both are binary states whose substance is read elsewhere (DamageReduction for the physical
                // half, ControlImmunity's two gates for the control half), so the only thing this table does is
                // put the marker on the receivers with the declared clock. Amplifier 0: the rung is the state
                // itself, not a magnitude.
                case PHYS_IMMUNITY -> applyTimed(caster, PartyEffects.physImmunityHolder(), 0, tiered, type,
                        1.0, characterId);
                case CONTROL_IMMUNITY -> applyTimed(caster, PartyEffects.controlImmunityHolder(), 0, tiered,
                        type, 1.0, characterId);
                default -> {
                    report(type, characterId, "reaches a body it lands on, not the party, so nothing was "
                            + "applied");
                    yield 0;
                }
            };
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: applying {} of {} threw; the rest of the skill still runs", type,
                    characterId, e);
            return 0;
        }
    }

    /**
     * Applies a victim-scope tiered effect to <b>one body</b>.
     *
     * <p>Called once per victim by the owner of the loop — an ultimate calls it per landed hit, a hook
     * calls it once for {@code ctx.target()}. It never decides <em>whether</em> the victim should be
     * affected: "the hit landed" is the caller's gate, because the caller is the one that saw
     * {@code hurt()} answer.
     *
     * @param victim              the body to affect
     * @param type                a type for which {@link EffectType#reachesVictim()} is true
     * @param params              the tier plus the duration
     * @param characterId         the character that asked, for the log line
     * @param magnitudeMultiplier the hook's composed diminishing factor, which scales a debuff's
     *                            <b>duration</b> and nothing else; an ultimate passes {@code 1.0}
     * @return whether the victim's state changed (a debuff was added, or absorption was removed)
     */
    public static boolean applyVictim(LivingEntity victim, EffectType type, TieredParams params,
            ResourceLocation characterId, double magnitudeMultiplier) {
        return applyVictim(victim, type, params, characterId, magnitudeMultiplier, 0);
    }

    /**
     * Applies a victim-scope tiered effect with the caster's level band added to the declared tier.
     *
     * <p>The same split as {@link #applyParty(LivingEntity, EffectType, TieredParams, ResourceLocation, int)}: the
     * band arrives resolved, and the composition happens in {@code TieredAmounts}. A debuff's <b>duration</b> is
     * still the only quantity the diminishing chain scales; the band raises its <em>rung</em>, which for the two
     * defence debuffs is the armour removed and for panic is nothing (one rung).
     *
     * @param victim              the body to affect
     * @param type                a type for which {@link EffectType#reachesVictim()} is true
     * @param params              the tier plus the duration
     * @param characterId         the character that asked, for the log line
     * @param magnitudeMultiplier the hook's composed diminishing factor for the duration
     * @param bandBonus           how many rungs the caster's level adds
     * @return whether the victim's state changed
     */
    public static boolean applyVictim(LivingEntity victim, EffectType type, TieredParams params,
            ResourceLocation characterId, double magnitudeMultiplier, int bandBonus) {
        return applyVictim(victim, sourceOf(victim), type, params, characterId, magnitudeMultiplier,
                bandBonus);
    }

    /**
     * Applies a victim-scope tiered effect <b>naming who caused it</b>.
     *
     * <h2>Why two of the arms need a source</h2>
     * A pull and a knockback are <em>relative</em> to somebody: they move the victim toward or away from the
     * caster, so the type cannot be applied from the victim alone. Every other victim-scoped type is a state
     * on the body itself and ignores the parameter.
     *
     * <h2>Why the source is a parameter and not looked up here</h2>
     * The hook path has the attacker in hand ({@code HookContext.attacker()}) and should say so rather than
     * re-deriving it. The ultimate path calls the six-argument form, which resolves the source from the damage
     * the settlement has <em>just</em> dealt — see {@link #sourceOf}. That keeps one rule ("where does the
     * caster come from") with one implementation, and keeps this class free of any assumption about how the
     * effect was reached.
     *
     * @param victim              the body to affect
     * @param source              who caused it, for the relative effects; {@code null} is "not known", which
     *                            makes a pull or a knockback report and skip rather than guess a direction
     * @param type                a type for which {@link EffectType#reachesVictim()} is true
     * @param params              the tier plus the duration
     * @param characterId         the character that asked, for the log line
     * @param magnitudeMultiplier the hook's composed diminishing factor for the duration
     * @param bandBonus           how many rungs the caster's level adds
     * @return whether the victim's state changed
     */
    public static boolean applyVictim(LivingEntity victim, @Nullable LivingEntity source, EffectType type,
            TieredParams params, ResourceLocation characterId, double magnitudeMultiplier, int bandBonus) {
        return applyVictim(victim, source, type, params, characterId, magnitudeMultiplier, bandBonus,
                OptionalDouble.empty());
    }

    /**
     * Applies a victim-scope tiered effect naming who caused it, and <b>how high it lifts the body</b>.
     *
     * <h2>Why the launch height is a parameter and not part of the tier</h2>
     * {@link EffectType#KNOCKBACK}'s rung is a vanilla <em>strength</em>, and vanilla caps the vertical
     * component of that strength at {@code 0.4} regardless: a stronger push does not lift a body higher. So a
     * declared 「击飞高度」 is a second quantity that no rung can hold, and it arrives here as one — the same
     * shape {@code UbEffect#launch} carries it in, and the same reason {@code source} is a parameter: this
     * class applies what it is told and does not go looking for a number.
     *
     * <p>Empty means "no launch of its own", which is every caller but an ultimate that declares one, so the
     * seven-argument form above stays exactly the behaviour it had.
     *
     * @param victim              the body to affect
     * @param source              who caused it, for the relative effects
     * @param type                a type for which {@link EffectType#reachesVictim()} is true
     * @param params              the tier plus the duration
     * @param characterId         the character that asked, for the log line
     * @param magnitudeMultiplier the hook's composed diminishing factor for the duration
     * @param bandBonus           how many rungs the caster's level adds
     * @param launch              how high a knockback lifts the body, in blocks; empty for no launch
     * @return whether the victim's state changed
     */
    public static boolean applyVictim(LivingEntity victim, @Nullable LivingEntity source, EffectType type,
            TieredParams params, ResourceLocation characterId, double magnitudeMultiplier, int bandBonus,
            OptionalDouble launch) {
        if (victim == null || type == null) {
            return false;
        }
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        TieredAmounts.Amount amount = TieredAmounts.resolve(type, tiered, bandBonus);
        double multiplier = Double.isFinite(magnitudeMultiplier) && magnitudeMultiplier > 0.0
                ? magnitudeMultiplier : 1.0;
        try {
            return switch (type) {
                case DEFENCE_DOWN -> {
                    int declared = tiered.seconds().isPresent()
                            ? AttackBuffRules.ticksFor(tiered.seconds().getAsDouble())
                            : AttackBuffRules.ticksFor(FALLBACK_SECONDS);
                    int ticks = EffectQuantities.durationTicks(declared, multiplier);
                    Optional<Holder<MobEffect>> holder = PartyEffects.defenceDownHolder();
                    if (ticks <= 0 || holder.isEmpty()) {
                        report(type, characterId, holder.isEmpty()
                                ? "is not registered yet, so the debuff was not applied"
                                : "declared no duration, so there was nothing to apply");
                        yield false;
                    }
                    int amplifier = DefenceDownRules.amplifierForTier(amount.tier());
                    boolean applied = victim.addEffect(new MobEffectInstance(holder.get(), ticks, amplifier,
                            false, true, true));
                    Astrum.LOGGER.debug("astrum: defence_down tier {} (amp {}, {}% armour) x multiplier {} "
                                    + "-> {}t applied={} on {}", amount.tier(), amplifier,
                            Math.round(DefenceDownRules.penaltyForTier(amount.tier()) * 100.0), multiplier,
                            ticks, applied, name(victim));
                    yield applied;
                }
                case PANIC -> {
                    // 「控制免疫」: the application gate. An immune body never receives the instance — and an
                    // existing panic is not refreshed either — so the immunity is real rather than a second
                    // resistance-like number. The classification is ControlImmunity's one table.
                    if (!ControlImmunity.permits(victim, EffectType.PANIC)) {
                        Astrum.LOGGER.debug("astrum: panic of {} was blocked by control immunity on {}",
                                characterId, name(victim));
                        yield false;
                    }
                    Optional<Holder<MobEffect>> holder = PartyEffects.panicHolder();
                    if (holder.isEmpty()) {
                        report(type, characterId, "is not registered yet, so the panic was not applied");
                        yield false;
                    }
                    // The duration is the pack's: 绫音 2.5/3 s, 胡桃 5.5 s, 凯露 8 s, and 1.5 s when none is
                    // declared. PanicRules owns the three-way reading and the ceil at 30 s; this only reports a
                    // declaration that had to be cut, once per clause rather than once per victim.
                    double declared = tiered.seconds().orElse(0.0);
                    if (PanicRules.isClamped(declared)) {
                        report(type, characterId, "declares " + declared + " seconds, which is longer than the "
                                + PanicRules.MAX_SECONDS + "-second cap a panic may last; the cap is used");
                    }
                    int ticks = PanicRules.ticksFor(declared);
                    boolean applied = victim.addEffect(new MobEffectInstance(holder.get(), ticks, 0,
                            false, true, true));
                    Astrum.LOGGER.debug("astrum: panic on {} for {}t ({}) applied={} — cannot act, not "
                            + "'keys locked'; see PanicRules", name(victim), ticks, ticks / 20.0, applied);
                    yield applied;
                }
                case REMOVE_SHIELD -> {
                    float before = victim.getAbsorptionAmount();
                    float after = ShieldBreak.strip(victim, amount.fraction());
                    Astrum.LOGGER.debug("astrum: remove_shield tier {} ({}%) on {} -> absorption {} -> {}",
                            amount.tier(), Math.round(amount.fraction() * 100.0), name(victim), before,
                            after);
                    yield before != after;
                }
                // 「魔法防御力をダウン」: the mirror of defence_down, and the mirror of
                // BUFF_MAGIC_DEFENCE_AREA's read — the tier travels in the amplifier and DamageReduction
                // turns it into "this body takes N% more magic damage". No attribute is touched: see the
                // EffectType value for why armour cannot express this.
                case MAGIC_DEFENCE_DOWN -> {
                    int declared = tiered.seconds().isPresent()
                            ? AttackBuffRules.ticksFor(tiered.seconds().getAsDouble())
                            : AttackBuffRules.ticksFor(FALLBACK_SECONDS);
                    int ticks = EffectQuantities.durationTicks(declared, multiplier);
                    Optional<Holder<MobEffect>> holder = PartyEffects.magicDefenceDownHolder();
                    if (ticks <= 0 || holder.isEmpty()) {
                        report(type, characterId, holder.isEmpty()
                                ? "is not registered yet, so the debuff was not applied"
                                : "declared no duration, so there was nothing to apply");
                        yield false;
                    }
                    int amplifier = MagicDefenceDownRules.amplifierForTier(amount.tier());
                    boolean applied = victim.addEffect(new MobEffectInstance(holder.get(), ticks, amplifier,
                            false, true, true));
                    Astrum.LOGGER.debug("astrum: magic_defence_down tier {} (amp {}, +{}% magic damage) x "
                                    + "multiplier {} -> {}t applied={} on {}", amount.tier(), amplifier,
                            Math.round(MagicDefenceDownRules.penaltyForTier(amount.tier()) * 100.0),
                            multiplier, ticks, applied, name(victim));
                    yield applied;
                }
                // ---------------------------------------------------------------- the two movement writes
                //
                // Neither is scaled by the diminishing multiplier, and the reason is the one every tiered arm
                // shares: a rung is a STRENGTH, not a quantity. A radius is not scaled either, and these two
                // are "how far" and "how hard" — the same kind of number. The tier's fraction IS the distance
                // (one block a rung) or the vanilla strength (0.4 a rung), read from EffectTiers' one table.
                //
                // A missing source is reported rather than guessed: a pull toward "somewhere" and a knockback
                // away from "somewhere" are not mechanics, they are bugs.
                case PULL -> {
                    if (source == null) {
                        report(type, characterId, "has no caster to pull toward, so the victim did not move");
                        yield false;
                    }
                    yield Pulls.apply(victim, source, amount.fraction());
                }
                case KNOCKBACK -> {
                    if (source == null) {
                        report(type, characterId, "has no caster to be pushed away from, so the victim did "
                                + "not move");
                        yield false;
                    }
                    // The rung is the horizontal strength (vanilla's own), and `launch` — when the clause
                    // declares one — is the height vanilla's cap cannot express. Both are handed to the one
                    // class that owns the knockback arithmetic; neither is derived here.
                    double height = launch == null || launch.isEmpty() ? 0.0 : launch.getAsDouble();
                    if (height > 0.0) {
                        Astrum.LOGGER.debug("astrum: knockback of {} at tier {} also launches {} by {} blocks",
                                characterId, amount.tier(), name(victim), height);
                    }
                    yield Knockbacks.apply(victim, source, amount.fraction(), height);
                }
                default -> {
                    report(type, characterId, "reaches the caster and her party, not a victim, so nothing "
                            + "was applied");
                    yield false;
                }
            };
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: applying {} of {} to a victim threw; the other victims still run",
                    type, characterId, e);
            return false;
        }
    }

    /**
     * The six timed party buffs, one body: holder check, duration, amplifier and the receiver call.
     *
     * <p>Every number arrives as an argument, so this performs no tier arithmetic. It exists to keep
     * "look up the holder, warn once if the registry is down, convert the duration, call
     * {@code AreaBuffs}" from being written five more times.
     */
    private static int applyTimed(LivingEntity caster, Optional<Holder<MobEffect>> effect, int amplifier,
            TieredParams params, EffectType type, double amount, ResourceLocation characterId) {
        if (effect.isEmpty()) {
            report(type, characterId, "is not registered yet, so the buff was not applied");
            return 0;
        }
        int ticks = AreaBuffs.ticksFor(params);
        if (ticks <= 0) {
            Astrum.LOGGER.debug("astrum: {} declared 0 seconds, nothing to apply for {}", type,
                    characterId);
            return 0;
        }
        int reached = AreaBuffs.apply(caster, effect.get(), ticks, amplifier, characterId, params.radius());
        Astrum.LOGGER.debug("astrum: {} amp {} (+{}%) for {}t within {} blocks of {} -> {} receiver(s)",
                type, amplifier, Math.round(amount * 100.0), ticks, AreaBuffs.radiusFor(params.radius()),
                name(caster), reached);
        return reached;
    }

    /** Logs one datapack-shaped problem once per (effect, character, message). */
    private static void report(EffectType type, ResourceLocation characterId, String problem) {
        String key = type + "|" + characterId + "|" + problem;
        if (REPORTED.first(key)) {
            Astrum.LOGGER.warn("astrum: {} (character {}) {}; reported once.", type, characterId, problem);
        }
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }

    /**
     * {@return who caused the state {@code victim} is in, for the relative effects}
     *
     * <h2>Why an ultimate has to be read off the damage it dealt</h2>
     * A hook's caller has the attacker in hand and passes it explicitly. An ultimate's settlement does not:
     * the six-argument {@code applyVictim} it calls predates the movement effects, and its own file
     * ({@code impl/ub}) is not this task's to change. So the caster is read from the two facts
     * {@code LivingEntity#hurt} writes on the victim <b>in the same call the settlement gates the effect on</b>:
     *
     * <ol>
     *   <li>{@code getLastHurtByMob()} — set by {@code hurt} when the damage source's entity is a living one
     *       and the type is not tagged {@code NO_ANGER};</li>
     *   <li>{@code getLastDamageSource().getEntity()} — set unconditionally for a hit that was not fully
     *       absorbed, which is the same condition the settlement used to decide to apply the effect.</li>
     * </ol>
     *
     * <p>Both are the <b>most recent</b> hit by construction, and "the most recent hit" is exactly the hit whose
     * effects are being applied. {@code null} is a real answer — a body hurt by a falling block has no living
     * attacker — and the two relative effects report and skip rather than guessing a direction.
     *
     * @param victim the body the effect is being applied to
     */
    private static @Nullable LivingEntity sourceOf(LivingEntity victim) {
        LivingEntity byMob = victim.getLastHurtByMob();
        if (byMob != null) {
            return byMob;
        }
        net.minecraft.world.damagesource.DamageSource last = victim.getLastDamageSource();
        return last != null && last.getEntity() instanceof LivingEntity living ? living : null;
    }
}
