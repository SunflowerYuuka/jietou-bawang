package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * One "when {@link Trigger} happens, do this {@link EffectType}" rule of a character.
 *
 * <p>Every hook shares {@code trigger}, {@code triggerParams} and {@code chance} at the top level of
 * the JSON object; the effect-specific numbers live in a nested {@code effectParams} object, and the
 * dispatch key {@code effect} sits beside them. {@link #CODEC} encodes and decodes exactly that
 * shape.
 */
@ApiStatus.Experimental
public sealed interface EffectHook
        permits EffectHook.ApplyEffect,
                EffectHook.ExtraDamage,
                EffectHook.Lifesteal,
                EffectHook.GainCharge,
                EffectHook.AreaCharge,
                EffectHook.Splash,
                EffectHook.Stack,
                EffectHook.Detonate,
                EffectHook.AttackBuff,
                EffectHook.Heal,
                EffectHook.AllyEffect,
                EffectHook.DefenceDown,
                EffectHook.HealArea,
                EffectHook.HealSelf,
                EffectHook.BuffAttackArea,
                EffectHook.BuffPhysDefenceArea,
                EffectHook.BuffMagicDefenceArea,
                EffectHook.RemoveShield,
                EffectHook.Haste,
                EffectHook.Panic,
                EffectHook.PercentDamage,
                EffectHook.ChargeRateUp,
                EffectHook.Shield,
                EffectHook.MagicDefenceDown,
                EffectHook.BuffMagicAttackArea,
                EffectHook.FlatAttackUp,
                EffectHook.FlatMagicAttackUp,
                EffectHook.SummonSpirit,
                EffectHook.Counter,
                EffectHook.ConsumeCounter,
                EffectHook.TransferCharge,
                EffectHook.TransferHeal,
                EffectHook.RegenSelf,
                EffectHook.RegenArea,
                EffectHook.ChargeRegenSelf,
                EffectHook.ChargeRegenArea,
                EffectHook.Pull,
                EffectHook.Knockback,
                EffectHook.Taunt,
                EffectHook.PhysImmunity,
                EffectHook.ControlImmunity,
                EffectHook.CritDamageUp,
                EffectHook.ShieldPhys,
                EffectHook.ShieldMagic,
                EffectHook.CritChanceUp,
                EffectHook.CounterChargeRegen,
                EffectHook.CritChanceDown,
                EffectHook.CritDamageDown {

    Trigger trigger();

    TriggerParams triggerParams();

    double chance();

    EffectType type();

    /**
     * {@return the tier block of a <b>tiered</b> effect, or empty for one that has none}
     *
     * <h2>Why this is on the interface and not eleven casts</h2>
     * Eleven records carry a {@link TieredParams} and they share no declared supertype, so a caller that
     * wants "the tier, whatever the effect" had to cast to each record in turn. That is the shape that
     * grows a new branch every time an effect is added, and the branch is where one of them silently reads
     * the wrong field. Declaring the accessor here means one dispatcher serves all eleven, and a test can
     * ask a shipped hook its tier by name rather than by class.
     *
     * <p>{@code Optional} rather than {@code null}: "not tiered" is an ordinary answer for the other
     * eleven values ({@code extra_damage} and friends take their magnitude from the datapack and their
     * strength from the multiplier chain, so a tier would be a second, competing scale —
     * {@link EffectTiers} says the same).
     */
    default Optional<TieredParams> tieredParams() {
        return Optional.empty();
    }

    /**
     * {@return how long this hook's grant lasts, for the effects that can be either permanent or timed}
     *
     * <h2>Why the interface owns this question</h2>
     * The two flat effects are the only ones that can be <b>permanent</b> — an EX passive lives until the
     * character leaves her party slot. Every other hook's effect expires on its own clock. So "is this
     * grant permanent, and if not how many ticks" is asked by exactly one place
     * ({@code EffectExecutors}) and answered by exactly one method, rather than by a cast to each flat
     * record inside a switch that would grow a branch per new effect.
     *
     * <p>Empty means "this hook's effect owns its own duration"; a present value means the grant is
     * carried by an attribute modifier that this mod adds and removes.
     */
    default Optional<GrantDuration> grantDuration() {
        return Optional.empty();
    }

    /**
     * {@return the flat point formula this hook grants, or empty when it grants none}
     *
     * <p>Read by the executor for {@link EffectType#FLAT_ATTACK_UP} /
     * {@link EffectType#FLAT_MAGIC_ATTACK_UP}, and by a test that wants a shipped hook's points at a
     * stated level without a world.
     */
    default Optional<FlatAmount> flatAmount() {
        return Optional.empty();
    }

    /**
     * {@return this hook's <b>own</b> crit multiplier, or empty when a crit uses the caster's global one}
     *
     * <h2>What this is for</h2>
     * {@code astrum:crit_damage} is a number the <b>player</b> carries, so it is one value for every skill
     * she owns. 「本技能出现暴击时，造成的伤害值从 2 倍提升至 4 倍」 (鏡華's 专属) is a statement about one
     * <b>skill</b>, and the global attribute cannot say it. This accessor is the per-skill channel: a hook
     * that authored damaging output may carry the multiplier its own hits crit at, and
     * {@code CombatEvents.flush} hands it to {@code Crit.factorFor} with the amount it queued.
     *
     * <p><b>Replace, not add.</b> A present value <b>replaces</b> the caster's global multiplier for this
     * hook's damage: {@code 4.0} means a crit deals four times the authored number whether the global is
     * 1.5 or 2.0 — not {@code 1.5 + 4} and not {@code 1.5 × 4}. Absent (the normal case, and every pack
     * written before task 55) means the global applies unchanged.
     *
     * <p><b>Why a default on the interface rather than a field on every record.</b> Only a hook that
     * <em>authors damage</em> has a hit whose crit it could describe. Declaring the question here means
     * {@code HookContext} can tag every hook's batch without a cast per record, and a future damaging hook
     * (a {@code splash}, a {@code detonate}) can answer it by adding one component — no new branch at the
     * settlement site.
     */
    default OptionalDouble critMultiplier() {
        return OptionalDouble.empty();
    }

    Codec<EffectHook> CODEC = EffectType.CODEC.dispatch("effect", EffectHook::type, type -> switch (type) {
        case APPLY_EFFECT -> ApplyEffect.MAP_CODEC;
        case EXTRA_DAMAGE -> ExtraDamage.MAP_CODEC;
        case LIFESTEAL -> Lifesteal.MAP_CODEC;
        case GAIN_CHARGE -> GainCharge.MAP_CODEC;
        case AREA_CHARGE -> AreaCharge.MAP_CODEC;
        case SPLASH -> Splash.MAP_CODEC;
        case STACK -> Stack.MAP_CODEC;
        case DETONATE -> Detonate.MAP_CODEC;
        case ATTACK_BUFF -> AttackBuff.MAP_CODEC;
        case HEAL -> Heal.MAP_CODEC;
        case ALLY_EFFECT -> AllyEffect.MAP_CODEC;
        case DEFENCE_DOWN -> DefenceDown.MAP_CODEC;
        case HEAL_AREA -> HealArea.MAP_CODEC;
        case HEAL_SELF -> HealSelf.MAP_CODEC;
        case BUFF_ATTACK_AREA -> BuffAttackArea.MAP_CODEC;
        case BUFF_PHYS_DEFENCE_AREA -> BuffPhysDefenceArea.MAP_CODEC;
        case BUFF_MAGIC_DEFENCE_AREA -> BuffMagicDefenceArea.MAP_CODEC;
        case REMOVE_SHIELD -> RemoveShield.MAP_CODEC;
        case HASTE -> Haste.MAP_CODEC;
        case PANIC -> Panic.MAP_CODEC;
        case PERCENT_DAMAGE -> PercentDamage.MAP_CODEC;
        case CHARGE_RATE_UP -> ChargeRateUp.MAP_CODEC;
        case SHIELD -> Shield.MAP_CODEC;
        case MAGIC_DEFENCE_DOWN -> MagicDefenceDown.MAP_CODEC;
        case BUFF_MAGIC_ATTACK_AREA -> BuffMagicAttackArea.MAP_CODEC;
        case FLAT_ATTACK_UP -> FlatAttackUp.MAP_CODEC;
        case FLAT_MAGIC_ATTACK_UP -> FlatMagicAttackUp.MAP_CODEC;
        case SUMMON_SPIRIT -> SummonSpirit.MAP_CODEC;
        case COUNTER -> Counter.MAP_CODEC;
        case CONSUME_COUNTER -> ConsumeCounter.MAP_CODEC;
        case TRANSFER_CHARGE -> TransferCharge.MAP_CODEC;
        case TRANSFER_HEAL -> TransferHeal.MAP_CODEC;
        case REGEN_SELF -> RegenSelf.MAP_CODEC;
        case REGEN_AREA -> RegenArea.MAP_CODEC;
        case CHARGE_REGEN_SELF -> ChargeRegenSelf.MAP_CODEC;
        case CHARGE_REGEN_AREA -> ChargeRegenArea.MAP_CODEC;
        case PHYS_IMMUNITY -> PhysImmunity.MAP_CODEC;
        case CONTROL_IMMUNITY -> ControlImmunity.MAP_CODEC;
        case CRIT_DAMAGE_UP -> CritDamageUp.MAP_CODEC;
        // The three movement-control mechanics: one arm each in the executor, and `TieredParams` for the
        // numbers — a distance in blocks, a vanilla knockback strength and a duration in seconds. See each
        // record below and the EffectType values for why each number travels in a rung.
        case PULL -> Pull.MAP_CODEC;
        case KNOCKBACK -> Knockback.MAP_CODEC;
        case TAUNT -> Taunt.MAP_CODEC;
        // The typed shield channels and the two non-tiered-sized party effects, one arm each: the dispatch key
        // is the effect type, so three channels are three types and a pack names the one it means.
        case SHIELD_PHYS -> ShieldPhys.MAP_CODEC;
        case SHIELD_MAGIC -> ShieldMagic.MAP_CODEC;
        case CRIT_CHANCE_UP -> CritChanceUp.MAP_CODEC;
        case COUNTER_CHARGE_REGEN -> CounterChargeRegen.MAP_CODEC;
        // The two victim-side crit debuffs: one arm each, for the same reason every pair above has two types
        // rather than one with a flag.
        case CRIT_CHANCE_DOWN -> CritChanceDown.MAP_CODEC;
        case CRIT_DAMAGE_DOWN -> CritDamageDown.MAP_CODEC;
    });

    /**
     * 「无效【45+45*技能等级】物理伤害的护盾」 — {@link EffectType#SHIELD_PHYS}: a barrier that absorbs
     * <b>physical</b> damage only, leaving magical damage to reach health untouched.
     *
     * <p>The physical twin of {@link Shield}, and the same {@link TieredParams} block (the rung is 20% of each
     * receiver's max health, the clock is {@code seconds}); only the channel differs. See
     * {@link EffectType#SHIELD_PHYS} for why the channel is its own effect type rather than a field here,
     * and {@code Shields.parkForTypedDamage} for where the restriction becomes real.
     *
     * @param params the tier, plus the duration and the reach
     */
    @ApiStatus.Experimental
    record ShieldPhys(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.SHIELD_PHYS;
        }

        /** {@return this hook's tier block} — the same shared dispatcher every tiered effect uses. */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<ShieldPhys> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(ShieldPhys::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(ShieldPhys::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ShieldPhys::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(ShieldPhys::params)
                ).apply(instance, ShieldPhys::new));
    }

    /**
     * {@link EffectType#SHIELD_MAGIC}: a barrier that absorbs <b>magical</b> damage only — the symmetric half of
     * {@link ShieldPhys}.
     *
     * <p>Same ladder, clock and reach; no shipped character restricts a barrier this way, and the type exists so
     * the two-channel split is complete rather than "physical only" with the other half unexpressible.
     *
     * @param params the tier, plus the duration and the reach
     */
    @ApiStatus.Experimental
    record ShieldMagic(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.SHIELD_MAGIC;
        }

        /** {@return this hook's tier block} */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<ShieldMagic> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
                instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(ShieldMagic::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(ShieldMagic::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ShieldMagic::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(ShieldMagic::params)
                ).apply(instance, ShieldMagic::new));
    }

    /**
     * 「提升范围半径200内所有友方 50 物理暴击」 — {@link EffectType#CRIT_CHANCE_UP}: a timed, party-wide
     * <b>crit-chance</b> buff.
     *
     * <p>The exact twin of {@link CritDamageUp}, one stat over, and it exists for the same reason: the wiki's
     * number (千歌 50, 静流 100, 铃莓 200 critic points) is not a rung, so a ladder would quantise a value the
     * source states exactly. {@code percent} carries the <em>converted</em> percentage (千歌 2.5, 静流 5,
     * 铃莓 10 at this build's 1 point = 0.05 percentage points), the clock and the reach travel as every party
     * effect's do, and {@code CritChanceUps} is the applier.
     *
     * @param percent the increase in crit chance, in percentage points ({@code 5.0} = +5%)
     * @param seconds how long it lasts
     * @param radius  the reach, empty for the party default, {@code 0.0} for the caster only
     */
    @ApiStatus.Experimental
    record CritChanceUp(Trigger trigger, TriggerParams triggerParams, double chance, double percent,
                        double seconds, OptionalDouble radius) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CRIT_CHANCE_UP;
        }

        private record Params(double percent, double seconds, OptionalDouble radius) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("percent").forGetter(Params::percent),
                    Codec.DOUBLE.fieldOf("seconds").forGetter(Params::seconds),
                    Codec.DOUBLE.optionalFieldOf("radius").xmap(
                                    value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                                    value -> value.isPresent() ? Optional.of(value.getAsDouble())
                                            : Optional.empty())
                            .forGetter(Params::radius)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<CritChanceUp> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(CritChanceUp::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(CritChanceUp::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(CritChanceUp::chance),
                        Params.CODEC.fieldOf("effectParams").forGetter(
                                h -> new Params(h.percent(), h.seconds(), h.radius()))
                ).apply(instance, (trigger, triggerParams, chance, params) -> new CritChanceUp(
                        trigger, triggerParams, chance, params.percent(), params.seconds(),
                        params.radius())));
    }

    /**
     * 「每秒恢复自身【10+「冰龙之印」】技能值」 — {@link EffectType#COUNTER_CHARGE_REGEN}: charge over time whose
     * instalment is a <b>formula over the caster's tally</b>.
     *
     * <h2>Why two numbers and not a rung</h2>
     * The wiki gives 「10 + 「冰龙之印」」: a fixed part and a part that follows a count that <b>changes during the
     * grant</b> (she spends marks with 技能1 while the regen is running). A rung is a fixed magnitude, so it
     * cannot express that; {@code base} and {@code perMark} are the two numbers the source actually has, and
     * they are the same pair {@link Counter} uses for 「追加【5+计数器枚数】个」.
     *
     * <p>The unit is <b>charge points a second</b> (the project's 1 point = 10 TP, §14.5): 雪菲's 10 TP a second
     * is {@code base 1.0}, and each 冰龙之印 adds 1 TP a second, which is {@code perMark 0.1}.
     *
     * @param base    points a second before any mark
     * @param perMark points a second for each mark the caster holds, read live every tick
     * @param seconds how long it lasts
     */
    @ApiStatus.Experimental
    record CounterChargeRegen(Trigger trigger, TriggerParams triggerParams, double chance, double base,
                              double perMark, double seconds) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.COUNTER_CHARGE_REGEN;
        }

        private record Params(double base, double perMark, double seconds) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.optionalFieldOf("base", 0.0).forGetter(Params::base),
                    Codec.DOUBLE.optionalFieldOf("perMark", 0.0).forGetter(Params::perMark),
                    Codec.DOUBLE.fieldOf("seconds").forGetter(Params::seconds)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<CounterChargeRegen> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(CounterChargeRegen::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(CounterChargeRegen::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(CounterChargeRegen::chance),
                        Params.CODEC.fieldOf("effectParams").forGetter(
                                h -> new Params(h.base(), h.perMark(), h.seconds()))
                ).apply(instance, (trigger, triggerParams, chance, params) -> new CounterChargeRegen(
                        trigger, triggerParams, chance, params.base(), params.perMark(), params.seconds())));
    }

    /**
     * Applies a mob effect to the struck target.
     *
     * @param effect    mob effect id, e.g. {@code minecraft:poison}
     * @param duration  duration in ticks
     * @param amplifier effect amplifier (0 = level I)
     */
    @ApiStatus.Experimental
    record ApplyEffect(Trigger trigger, TriggerParams triggerParams, double chance, ResourceLocation effect,
                       int duration, int amplifier) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.APPLY_EFFECT;
        }

        private record Params(ResourceLocation effect, int duration, int amplifier) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("effect").forGetter(Params::effect),
                    Codec.INT.fieldOf("duration").forGetter(Params::duration),
                    Codec.INT.fieldOf("amplifier").forGetter(Params::amplifier)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<ApplyEffect> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(ApplyEffect::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(ApplyEffect::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ApplyEffect::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.effect(), h.duration(), h.amplifier()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new ApplyEffect(
                trigger, triggerParams, chance, params.effect(), params.duration(), params.amplifier())));
    }

    /**
     * Appends flat damage to the same tick's damage batch.
     *
     * @param amount     raw damage added
     * @param damageType the damage type to file it under, empty for {@code astrum:hook_damage}.
     *                   A <b>magic</b> caster passes {@code astrum:magic}: that is the marker
     *                   {@link EffectType#BUFF_MAGIC_DEFENCE_AREA} reduces, and without it a magic
     *                   character's damage would be indistinguishable from a sword's (see that value's
     *                   javadoc for why Minecraft cannot answer this by itself)
     * @param critMultiplier this skill's own crit multiplier, replacing the caster's global
     *                   {@code astrum:crit_damage} for this hook's damage; empty for the global. See
     *                   {@link #critMultiplier()} for the replacement rule and for why the channel exists.
     *                   It is the <b>skill's</b> number, so every hook that authors one skill's damage
     *                   carries the same value — 鏡華's 专属 puts {@code 4.0} on its {@code extra_damage}
     */
    @ApiStatus.Experimental
    record ExtraDamage(Trigger trigger, TriggerParams triggerParams, double chance, double amount,
                       Optional<ResourceLocation> damageType, OptionalDouble critMultiplier,
                       OptionalDouble lostHealthBonus)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.EXTRA_DAMAGE;
        }

        /**
         * The pre-{@code critMultiplier} shape: every caller that predates task 55 keeps working and its
         * damage keeps critting at the caster's global multiplier.
         */
        public ExtraDamage(Trigger trigger, TriggerParams triggerParams, double chance, double amount,
                Optional<ResourceLocation> damageType) {
            this(trigger, triggerParams, chance, amount, damageType, OptionalDouble.empty(),
                    OptionalDouble.empty());
        }

        /** The pre-{@code lostHealthBonus} shape: a clause whose amount does not grow as she bleeds. */
        public ExtraDamage(Trigger trigger, TriggerParams triggerParams, double chance, double amount,
                Optional<ResourceLocation> damageType, OptionalDouble critMultiplier) {
            this(trigger, triggerParams, chance, amount, damageType, critMultiplier, OptionalDouble.empty());
        }

        /**
         * The pre-{@code damageType} shape, so every existing caller and pack keeps working and lands on
         * the physical default.
         */
        public ExtraDamage(Trigger trigger, TriggerParams triggerParams, double chance, double amount) {
            this(trigger, triggerParams, chance, amount, Optional.empty(), OptionalDouble.empty(),
                    OptionalDouble.empty());
        }

        private record Params(double amount, Optional<ResourceLocation> damageType,
                              OptionalDouble critMultiplier, OptionalDouble lostHealthBonus) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("amount").forGetter(Params::amount),
                    ResourceLocation.CODEC.optionalFieldOf("damageType").forGetter(Params::damageType),
                    // Additive and last: every pack written before task 55 omits it, keeps decoding, and keeps
                    // critting at the caster's global `astrum:crit_damage` — which is what those packs meant.
                    Codec.DOUBLE.optionalFieldOf("critMultiplier").xmap(
                                    value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                                    value -> value.isPresent() ? Optional.of(value.getAsDouble())
                                            : Optional.empty())
                            .forGetter(Params::critMultiplier),
                    // 「随损失的生命值提升」, in units over one full bar lost — see LostHealth. Additive and
                    // last: absence means the clause does not scale, which is every pack written before it.
                    Codec.DOUBLE.optionalFieldOf("lostHealthBonus").xmap(
                                    value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                                    value -> value.isPresent() ? Optional.of(value.getAsDouble())
                                            : Optional.empty())
                            .forGetter(Params::lostHealthBonus)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<ExtraDamage> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(ExtraDamage::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(ExtraDamage::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ExtraDamage::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(
                        h -> new Params(h.amount(), h.damageType(), h.critMultiplier(),
                                h.lostHealthBonus()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new ExtraDamage(
                trigger, triggerParams, chance, params.amount(), params.damageType(),
                params.critMultiplier(), params.lostHealthBonus())));
    }

    /**
     * Heals the attacker for a fraction of the damage dealt.
     *
     * @param fraction healed fraction, {@code 0.2} = 20% of the damage
     */
    @ApiStatus.Experimental
    record Lifesteal(Trigger trigger, TriggerParams triggerParams, double chance, double fraction) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.LIFESTEAL;
        }

        private record Params(double fraction) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("fraction").forGetter(Params::fraction)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<Lifesteal> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Lifesteal::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Lifesteal::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Lifesteal::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.fraction()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new Lifesteal(
                trigger, triggerParams, chance, params.fraction())));
    }

    /**
     * Charges the attacker's own UB gauge.
     *
     * @param amount charge points granted
     */
    @ApiStatus.Experimental
    record GainCharge(Trigger trigger, TriggerParams triggerParams, double chance, double amount) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.GAIN_CHARGE;
        }

        private record Params(double amount) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("amount").forGetter(Params::amount)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<GainCharge> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(GainCharge::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(GainCharge::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(GainCharge::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.amount()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new GainCharge(
                trigger, triggerParams, chance, params.amount())));
    }

    /**
     * Charges the UB gauge of allied players around the attacker.
     *
     * @param radius      search radius in blocks
     * @param amount      charge points granted to each ally
     * @param includeSelf whether the attacker is charged as well
     */
    @ApiStatus.Experimental
    record AreaCharge(Trigger trigger, TriggerParams triggerParams, double chance, double radius, double amount,
                      boolean includeSelf) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.AREA_CHARGE;
        }

        private record Params(double radius, double amount, boolean includeSelf) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("radius").forGetter(Params::radius),
                    Codec.DOUBLE.fieldOf("amount").forGetter(Params::amount),
                    Codec.BOOL.fieldOf("includeSelf").forGetter(Params::includeSelf)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<AreaCharge> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(AreaCharge::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(AreaCharge::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(AreaCharge::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.radius(), h.amount(), h.includeSelf()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new AreaCharge(
                trigger, triggerParams, chance, params.radius(), params.amount(), params.includeSelf())));
    }

    /**
     * Splashes damage onto every other entity around the struck target.
     *
     * @param radius splash radius in blocks
     * @param damage damage dealt to each splashed entity
     * @param damageType the damage type to file it under, empty for {@code astrum:hook_damage}; a magic
     *                   caster passes {@code astrum:magic}, for the reason
     *                   {@link ExtraDamage#damageType()} gives
     */
    @ApiStatus.Experimental
    record Splash(Trigger trigger, TriggerParams triggerParams, double chance, double radius, double damage,
                  Optional<ResourceLocation> damageType, boolean casterCentred) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.SPLASH;
        }

        /** The pre-{@code damageType} shape: physical, which is what every shipped pack means. */
        public Splash(Trigger trigger, TriggerParams triggerParams, double chance, double radius,
                double damage) {
            this(trigger, triggerParams, chance, radius, damage, Optional.empty(), false);
        }

        /** The pre-{@code casterCentred} shape: centred on the body that was struck. */
        public Splash(Trigger trigger, TriggerParams triggerParams, double chance, double radius,
                double damage, Optional<ResourceLocation> damageType) {
            this(trigger, triggerParams, chance, radius, damage, damageType, false);
        }

        private record Params(double radius, double damage, Optional<ResourceLocation> damageType,
                              boolean casterCentred) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("radius").forGetter(Params::radius),
                    Codec.DOUBLE.fieldOf("damage").forGetter(Params::damage),
                    ResourceLocation.CODEC.optionalFieldOf("damageType").forGetter(Params::damageType),
                    // Additive: a pack written before this field existed keeps loading and centres on the body
                    // it hit, which is what every shipped splash meant before this field existed.
                    Codec.BOOL.optionalFieldOf("casterCentred", false).forGetter(Params::casterCentred)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<Splash> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Splash::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Splash::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Splash::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(
                        h -> new Params(h.radius(), h.damage(), h.damageType(), h.casterCentred()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new Splash(
                trigger, triggerParams, chance, params.radius(), params.damage(), params.damageType(),
                params.casterCentred())));
    }

    /**
     * Adds one stack of {@code stackId} to the struck target.
     *
     * @param stackId stack kind, e.g. {@code astrum:poison}
     * @param max     stack cap
     */
    @ApiStatus.Experimental
    record Stack(Trigger trigger, TriggerParams triggerParams, double chance, ResourceLocation stackId, int max)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.STACK;
        }

        private record Params(ResourceLocation stackId, int max) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("stackId").forGetter(Params::stackId),
                    Codec.INT.fieldOf("max").forGetter(Params::max)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<Stack> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Stack::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Stack::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Stack::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.stackId(), h.max()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new Stack(
                trigger, triggerParams, chance, params.stackId(), params.max())));
    }

    /**
     * Consumes all stacks of the triggering stack and deals damage per stack.
     *
     * @param damagePerStack damage per consumed stack
     * @param radius         radius in blocks
     */
    @ApiStatus.Experimental
    record Detonate(Trigger trigger, TriggerParams triggerParams, double chance, double damagePerStack, double radius)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.DETONATE;
        }

        private record Params(double damagePerStack, double radius) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("damagePerStack").forGetter(Params::damagePerStack),
                    Codec.DOUBLE.fieldOf("radius").forGetter(Params::radius)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<Detonate> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Detonate::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Detonate::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Detonate::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.damagePerStack(), h.radius()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new Detonate(
                trigger, triggerParams, chance, params.damagePerStack(), params.radius())));
    }

    /**
     * Temporarily raises attack damage — the attacker's and, within {@code radius}, her party's
     * (「特大幅度提升自己的物理攻击」, and in the real game the same boost covers the area).
     *
     * <p>The one hook that changes a number the <em>player</em> carries rather than something the hit
     * does: it is applied as a real {@code MobEffectInstance}, so vanilla's own effect HUD shows it and
     * vanilla's own expiry takes it away again. See {@code AttackBuffs} for the receiver rule (the
     * caster plus every ally inside the radius, selected by {@code AllySelector}), for the stacking rule
     * and for why no tick listener is involved.
     *
     * @param multiplier attack multiplier while the buff lasts, {@code 1.5} = +50%; values at or below
     *                   {@code 1.0} are not a buff and are refused by the executor rather than applied
     *                   as a debuff
     * @param seconds    how long it lasts, in seconds (the datapack says seconds because the wiki does)
     * @param radius     how far around the caster the buff reaches, in blocks; empty means the
     *                   documented party default ({@code AttackBuffs.PARTY_RADIUS_DEFAULT}). An ultimate
     *                   passes its own tier radius here, so the buff reaches exactly as far as the
     *                   ultimate does
     */
    @ApiStatus.Experimental
    record AttackBuff(Trigger trigger, TriggerParams triggerParams, double chance, double multiplier,
                      double seconds, OptionalDouble radius) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.ATTACK_BUFF;
        }

        private record Params(double multiplier, double seconds, OptionalDouble radius) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("multiplier").forGetter(Params::multiplier),
                    Codec.DOUBLE.fieldOf("seconds").forGetter(Params::seconds),
                    Codec.DOUBLE.optionalFieldOf("radius").xmap(
                                    value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                                    value -> value.isPresent() ? Optional.of(value.getAsDouble())
                                            : Optional.empty())
                            .forGetter(Params::radius)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<AttackBuff> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(AttackBuff::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(AttackBuff::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(AttackBuff::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(
                        h -> new Params(h.multiplier(), h.seconds(), h.radius()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new AttackBuff(
                trigger, triggerParams, chance, params.multiplier(), params.seconds(), params.radius())));
    }

    /**
     * 「提升我方全体 28% 暴击伤害，持续 18 秒」: raises the party's <b>暴击伤害</b> for a while — 千歌's 6★ UB.
     *
     * <h2>Why this exists at all</h2>
     * The clause sat in {@code ub.enhanced.extra} for two tasks because nothing carried it: the buff family
     * raises the stats its own effects were written for, {@code apply_effect} only attaches vanilla potion
     * effects, and {@code astrum:crit_damage} is this mod's own attribute. The human's ruling was
     * 「新增效果类型，修复。」, so it is a type — {@link EffectType#CRIT_DAMAGE_UP} — rather than a sentence that
     * renders as 「尚未实现」.
     *
     * <h2>Why not task 55's per-skill channel</h2>
     * 「本技能暴击 4 倍」 <b>replaces</b> the caster's global multiplier for one skill's own hits, so it cannot
     * say 「我方全体 28%、持续 18 秒」: that is timed, party-wide, and a change to what <em>every</em> crit is worth
     * while it runs. The two mechanisms are deliberately different, and this record is the second one.
     *
     * <h2>The numbers, and how they travel</h2>
     * {@code percent} is the source's own percentage ({@code 28} = +28% of the receiver's crit damage), carried
     * as a free number rather than a rung because the wiki states it exactly. The value reaches the receiver as a
     * vanilla amplifier — {@code round(percent)} — and the registered effect's modifier is
     * {@code amplifier / 100.0} {@code ADD_MULTIPLIED_TOTAL} on {@code astrum:crit_damage}, so 28 → 1.5 × 1.28 =
     * 1.92 at the shipped default multiplier. {@code seconds} is a fixed lifetime, not a quantity the multiplier
     * chain scales: a per-slot-different lifetime would make one ultimate behave differently by party position
     * ({@link AttackBuff} gives the same reason).
     *
     * @param percent the increase in crit damage, in percent: {@code 28.0} = +28%
     * @param seconds how long the buff lasts
     * @param radius  how far around the caster it reaches, in blocks; {@code 0.0} is "caster only". Empty means
     *                the party default, as for {@link AttackBuff}
     */
    @ApiStatus.Experimental
    record CritDamageUp(Trigger trigger, TriggerParams triggerParams, double chance, double percent,
                        double seconds, OptionalDouble radius) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CRIT_DAMAGE_UP;
        }

        private record Params(double percent, double seconds, OptionalDouble radius) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("percent").forGetter(Params::percent),
                    Codec.DOUBLE.fieldOf("seconds").forGetter(Params::seconds),
                    Codec.DOUBLE.optionalFieldOf("radius").xmap(
                                    value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                                    value -> value.isPresent() ? Optional.of(value.getAsDouble())
                                            : Optional.empty())
                            .forGetter(Params::radius)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<CritDamageUp> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(CritDamageUp::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(CritDamageUp::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(CritDamageUp::chance),
                        Params.CODEC.fieldOf("effectParams").forGetter(
                                h -> new Params(h.percent(), h.seconds(), h.radius()))
                ).apply(instance, (trigger, triggerParams, chance, params) -> new CritDamageUp(
                        trigger, triggerParams, chance, params.percent(), params.seconds(),
                        params.radius())));
    }

    /**
     * Restores health to the caster and to her party inside {@code radius} — 「HP中回復」.
     *
     * <p>The second hook that changes a number the <em>player</em> carries rather than something the hit
     * does, and like {@link AttackBuff} it is a single mechanism rather than an approximation: the game's
     * heals are fixed amounts on a caster and her allies, not a fraction of damage dealt, which is what
     * {@code lifesteal} would have made them. See {@code Heals} for the receiver rule (the same
     * {@code AllySelector} kernel every other party effect uses), for the clamp at max health and for why
     * a dead receiver is skipped.
     *
     * <p>The amount <b>is</b> scaled by the multiplier chain — §5.2 lists 回血 among the quantities
     * diminishing returns act on — unlike {@link AttackBuff}'s ratio, which is not a quantity.
     *
     * @param amount how much health to restore before the multiplier, derived in
     *               {@code docs/balance-derivation.md}
     * @param radius how far around the caster the heal reaches, in blocks; {@code 0.0} is the
     *               documented "caster only", exactly as {@link AttackBuff#radius()} reads it. Empty
     *               means the same party default
     */
    @ApiStatus.Experimental
    record Heal(Trigger trigger, TriggerParams triggerParams, double chance, double amount,
                OptionalDouble radius) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.HEAL;
        }

        private record Params(double amount, OptionalDouble radius) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("amount").forGetter(Params::amount),
                    Codec.DOUBLE.optionalFieldOf("radius").xmap(
                                    value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                                    value -> value.isPresent() ? Optional.of(value.getAsDouble())
                                            : Optional.empty())
                            .forGetter(Params::radius)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<Heal> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Heal::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Heal::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Heal::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.amount(), h.radius()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new Heal(
                trigger, triggerParams, chance, params.amount(), params.radius())));
    }

    /**
     * Applies a <b>named vanilla mob effect</b> to the caster and her party inside {@code radius} —
     * the party-side counterpart of {@link ApplyEffect}.
     *
     * <h2>Why this is not {@link ApplyEffect}</h2>
     * {@code ApplyEffect} lands on {@code ctx.target()}, the entity that was <b>struck</b>: it is how a
     * debuff reaches the enemy, and it can reach nobody else. Every defensive or supportive vanilla
     * effect a character wants — 魔法防御力アップ, 行動速度アップ, 継続HP回復 — is a <b>buff on her own
     * side</b>, so it needs the caster-and-party receiver set. {@link #attack_buff} already owns that set
     * for the one attribute it was written for; this value generalises it to any vanilla effect id,
     * which is why ペコリーヌ's defence buff and コッコロ's haste can be data instead of three new
     * {@code EffectType}s.
     *
     * <h2>What that means for the numbers</h2>
     * The amplifier travels in the amplifier (an integer effect level, never scaled — the same rule
     * {@code EffectQuantities} documents for {@code apply_effect}), and the <b>duration</b> is the
     * quantity the multiplier chain acts on.
     *
     * @param effect    mob effect id, e.g. {@code minecraft:resistance}
     * @param duration  duration in ticks before the multiplier
     * @param amplifier effect amplifier (0 = level I); never scaled
     * @param radius    how far around the caster the effect reaches, in blocks; {@code 0.0} is
     *                  "caster only". Empty means the party default, as for {@link AttackBuff}
     */
    @ApiStatus.Experimental
    record AllyEffect(Trigger trigger, TriggerParams triggerParams, double chance, ResourceLocation effect,
                      int duration, int amplifier, OptionalDouble radius) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.ALLY_EFFECT;
        }

        private record Params(ResourceLocation effect, int duration, int amplifier, OptionalDouble radius) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    ResourceLocation.CODEC.fieldOf("effect").forGetter(Params::effect),
                    Codec.INT.fieldOf("duration").forGetter(Params::duration),
                    Codec.INT.fieldOf("amplifier").forGetter(Params::amplifier),
                    Codec.DOUBLE.optionalFieldOf("radius").xmap(
                                    value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                                    value -> value.isPresent() ? Optional.of(value.getAsDouble())
                                            : Optional.empty())
                            .forGetter(Params::radius)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<AllyEffect> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(AllyEffect::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(AllyEffect::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(AllyEffect::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(
                        h -> new Params(h.effect(), h.duration(), h.amplifier(), h.radius()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new AllyEffect(
                trigger, triggerParams, chance, params.effect(), params.duration(), params.amplifier(),
                params.radius())));
    }

    /**
     * Lowers the struck entity's armour — キャル's アーマーダウン 「単体の物理・魔法防御力を小ダウン」.
     *
     * <h2>Why this is its own type</h2>
     * See {@link EffectType#DEFENCE_DOWN}: vanilla has no "takes more damage" effect, and
     * {@code weakness} debuffs the wrong half of the stat sheet. This applies this mod's own
     * {@code astrum:defence_down} effect, which carries a negative {@code ARMOR} /
     * {@code ARMOR_TOUGHNESS} modifier and expires by vanilla's clock.
     *
     * <p>The receiver is {@code ctx.target()} — the enemy that was struck — so unlike
     * {@link AllyEffect} this lands on the victim, the same side of the fight as
     * {@link ApplyEffect}.
     *
     * <h2>Now tiered: 4 rungs, 20% of armour each</h2>
     * Tier N removes {@code 20N}% of the victim's armour (tier 4 = −80%). The rung is clamped against
     * {@link EffectTiers} and encoded into the effect's <em>amplifier</em> by {@code DefenceDownRules},
     * so a pack says {@code "tier": 2} where it used to have to say {@code "amplifier": 1}.
     *
     * @param params  the tier, plus the duration in {@link TieredParams#seconds()}
     */
    @ApiStatus.Experimental
    record DefenceDown(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.DEFENCE_DOWN;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<DefenceDown> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(DefenceDown::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(DefenceDown::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(DefenceDown::chance),
                // Nested under `effectParams`, like every other hook's parameters. The tiered block is one
                // *value* (tier + clock + reach), and this schema's rule is that a hook's parameters live in
                // `effectParams` rather than being flattened onto the hook: `trigger`, `triggerParams` and
                // `chance` are the hook's own keys, and everything an effect needs is one object below them.
                TieredParams.CODEC.fieldOf("effectParams").forGetter(DefenceDown::params)
        ).apply(instance, DefenceDown::new));
    }

    // ================================================================== the ten generic tiered effects
    //
    // One record each, and every one of them carries the SAME field — `TieredParams` — so the tier is a
    // shared concern rather than five fields per effect. The records differ in nothing but their type and
    // the hooks object they decode from; what a rung is worth is `EffectTiers`' table, and who is reached
    // is the executor's. That is deliberate: ten records with one field each is the shape that makes a
    // reviewer able to check all ten in one reading, and it is why adding an eleventh effect is one enum
    // value plus one record rather than a new subsystem.

    /**
     * 「味方全体HP中回復」: heals every ally inside a radius for a percentage of <b>their own</b> max health.
     *
     * <p>Tier N = {@code 20N}% of max health (20/40/60/80/100%), so tier 5 is a full heal — intended.
     * Instant, not regeneration: one {@code heal()} per receiver, and a receiver already at full health
     * is a legitimate zero.
     *
     * @param params the tier plus the radius; an absent radius is the documented party default and
     *               {@code 0.0} is the caster alone
     */
    @ApiStatus.Experimental
    record HealArea(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.HEAL_AREA;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<HealArea> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(HealArea::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(HealArea::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(HealArea::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(HealArea::params)
        ).apply(instance, HealArea::new));
    }

    /**
     * 「自分のHP中回復」: heals the caster for a percentage of her own max health — tier N = {@code 20N}%.
     *
     * <p>The self half of {@link HealArea}. No radius: the receiver set is the caster, so this does not go
     * through {@code AllySelector} at all — there is nobody to select.
     *
     * @param params the tier; {@code seconds} and {@code radius} are meaningless here and ignored
     */
    @ApiStatus.Experimental
    record HealSelf(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.HEAL_SELF;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<HealSelf> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(HealSelf::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(HealSelf::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(HealSelf::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(HealSelf::params)
        ).apply(instance, HealSelf::new));
    }

    /**
     * 「味方全体の物理攻撃力アップ」: raises the attack damage of the caster and every ally in a radius.
     *
     * <p>Tier N = {@code +20N}% attack damage, applied as an {@code ADD_MULTIPLIED_TOTAL} modifier on
     * {@code Attributes.ATTACK_DAMAGE} so it scales with each receiver's own weapon rather than adding a
     * flat number. Tier 5 is double damage — intended.
     *
     * @param params the tier plus the radius and duration
     */
    @ApiStatus.Experimental
    record BuffAttackArea(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.BUFF_ATTACK_AREA;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<BuffAttackArea> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(BuffAttackArea::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(BuffAttackArea::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(BuffAttackArea::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(BuffAttackArea::params)
        ).apply(instance, BuffAttackArea::new));
    }

    /**
     * 「物理防御力アップ」: reduces <b>non-magic</b> damage taken — tier N = {@code 12N}%, topping at 60%.
     *
     * <p>Not {@code minecraft:resistance}: that cannot be split into physical and magical, and the brief
     * asks for two buffs. See {@link EffectType#BUFF_PHYS_DEFENCE_AREA}.
     *
     * @param params the tier plus the radius and duration
     */
    @ApiStatus.Experimental
    record BuffPhysDefenceArea(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.BUFF_PHYS_DEFENCE_AREA;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<BuffPhysDefenceArea> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(BuffPhysDefenceArea::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(BuffPhysDefenceArea::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(BuffPhysDefenceArea::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(BuffPhysDefenceArea::params)
                ).apply(instance, BuffPhysDefenceArea::new));
    }

    /**
     * 「魔法防御力アップ」: reduces <b>magic</b> damage taken — tier N = {@code 12N}%, topping at 60%.
     *
     * <p>"Magic" means damage whose type is {@code astrum:magic} or is tagged {@code astrum:is_magic};
     * another mod's magic damage is not marked and lands in the physical bucket. See
     * {@link EffectType#BUFF_MAGIC_DEFENCE_AREA} for the full statement.
     *
     * @param params the tier plus the radius and duration
     */
    @ApiStatus.Experimental
    record BuffMagicDefenceArea(Trigger trigger, TriggerParams triggerParams, double chance,
                                TieredParams params) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.BUFF_MAGIC_DEFENCE_AREA;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<BuffMagicDefenceArea> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(BuffMagicDefenceArea::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(BuffMagicDefenceArea::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(BuffMagicDefenceArea::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(BuffMagicDefenceArea::params)
                ).apply(instance, BuffMagicDefenceArea::new));
    }

    /**
     * バリアブレイク: removes <b>all</b> of the struck entity's absorption hearts — one tier.
     *
     * <p>A {@code "tier": 2} clamps to 1 with a warning rather than inventing a rung, because a barrier
     * break is all-or-nothing: the ladder the brief first sketched was replaced by this.
     *
     * @param params the tier (always 1 after clamping)
     */
    @ApiStatus.Experimental
    record RemoveShield(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.REMOVE_SHIELD;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<RemoveShield> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(RemoveShield::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(RemoveShield::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(RemoveShield::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(RemoveShield::params)
        ).apply(instance, RemoveShield::new));
    }

    /**
     * 「行動速度アップ」: movement speed <b>and</b> attack speed, 3 tiers, one vanilla level each.
     *
     * <p>Tier N = amplifier {@code N - 1}. Not {@code minecraft:speed}, which moves and nothing else —
     * see {@link EffectType#HASTE}.
     *
     * <p>Reaches the caster and her party in radius, because 「味方全体」 is how the shipped characters
     * write it; {@link TieredParams#radius()} of {@code 0.0} makes it self-only.
     *
     * @param params the tier plus the radius and duration
     */
    @ApiStatus.Experimental
    record Haste(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.HASTE;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<Haste> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Haste::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Haste::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Haste::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(Haste::params)
        ).apply(instance, Haste::new));
    }

    /**
     * 「恐慌」: the struck entity <b>cannot act</b> for 1.5 s — one tier.
     *
     * <p>A {@code Mob} stops ticking its AI; a {@code ServerPlayer} has its movement input zeroed and its
     * attack and interaction paths cancelled. The duration is a constant
     * ({@code PanicRules.DURATION_TICKS}) because the brief fixed it at 1.5 s — a tier or a
     * {@code seconds} field would be a knob nobody asked for, and the one thing it would let an author do
     * is make panic permanent.
     *
     * @param params the tier (always 1 after clamping); any {@code seconds} is ignored in favour of the
     *               fixed duration
     */
    @ApiStatus.Experimental
    record Panic(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.PANIC;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<Panic> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Panic::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Panic::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Panic::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(Panic::params)
        ).apply(instance, Panic::new));
    }

    /**
     * 「最大HPの X% ダメージ」: damage equal to a percentage of the <b>victim's</b> max health.
     *
     * <p>Tier N = {@code 10N}% of max health (10/20/30/40/50%). Runs through the same damage batch and
     * the same recursion fuse as {@link ExtraDamage}.
     *
     * @param params the tier; {@code seconds} and {@code radius} are ignored
     * @param damageType the damage type to file it under, empty for {@code astrum:hook_damage}. A magic
     *                   caster passes {@code astrum:magic} so the two defence buffs can tell the halves
     *                   apart
     */
    @ApiStatus.Experimental
    record PercentDamage(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params,
                         Optional<ResourceLocation> damageType) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.PERCENT_DAMAGE;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        private record Params(TieredParams tiered, Optional<ResourceLocation> damageType) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    // MAP_CODEC, not CODEC: `forGetter` needs a MapCodec (a codec that reads fields off the
                    // object it is given), and CODEC is the same parameters as a standalone, field-wrapped
                    // value. Using MAP_CODEC here is what lets `tier`/`seconds`/`radius` sit directly in
                    // `effectParams` beside `damageType`, exactly as every other hook writes them.
                    TieredParams.MAP_CODEC.forGetter(Params::tiered),
                    ResourceLocation.CODEC.optionalFieldOf("damageType").forGetter(Params::damageType)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<PercentDamage> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(PercentDamage::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(PercentDamage::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(PercentDamage::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(
                        h -> new Params(h.params(), h.damageType()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new PercentDamage(
                trigger, triggerParams, chance, params.tiered(), params.damageType())));
    }

    /**
     * 「TP上昇」: multiplies UB charge gain for the caster and her party — 5 tiers, +25% each.
     *
     * <p>Tier N = multiplier {@code 1 + 0.25N}, i.e. 1.25× .. 2.25×. The multiplier is applied inside
     * {@code ChargeKernel}, so all four charge sources (damage dealt, damage taken, kill, passive trickle)
     * honour it without a per-source copy of the rule.
     *
     * @param params the tier plus the radius and duration of the buff
     */
    @ApiStatus.Experimental
    record ChargeRateUp(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CHARGE_RATE_UP;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves all eleven tiered effects.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<ChargeRateUp> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(ChargeRateUp::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(ChargeRateUp::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ChargeRateUp::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(ChargeRateUp::params)
        ).apply(instance, ChargeRateUp::new));
    }

    /**
     * 護盾: grants absorption that <b>adds to</b> vanilla's own instead of being cancelled by it — 5 tiers,
     * 20% of the receiver's max health each.
     *
     * <p>Party-scoped, because a shield is something that happens to <em>your own side</em>: the bwiki
     * formula is 「对自身展开可吸收…伤害的护盾」, and {@code radius: 0} keeps it self-only while an absent
     * radius lets a pack extend it to the party. See {@link EffectType#SHIELD} for why the shield cannot be
     * vanilla's {@code absorption}, and {@code ShieldPool} for the stacking arithmetic.
     *
     * @param params the tier, plus the duration (the bwiki's 8 seconds) and the reach
     */
    @ApiStatus.Experimental
    record Shield(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.SHIELD;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves every tiered effect.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<Shield> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Shield::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Shield::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Shield::chance),
                TieredParams.CODEC.fieldOf("effectParams").forGetter(Shield::params)
        ).apply(instance, Shield::new));
    }

    /**
     * 「魔法防御力をダウン」: the struck entity takes more <b>magic</b> damage — 4 tiers, 20% each, the same
     * ladder as {@link DefenceDown}.
     *
     * <p>Victim-scoped, like {@code defence_down}: it lands on the body that was struck. See
     * {@link EffectType#MAGIC_DEFENCE_DOWN} for why the effect cannot be a lower {@code Attributes.ARMOR}
     * (armour does not touch magic damage at all) and for the documented limit that only
     * {@code astrum:magic} / {@code astrum:is_magic} damage is recognised as magical.
     *
     * @param params the tier, plus the duration in {@link TieredParams#seconds()}
     */
    @ApiStatus.Experimental
    record MagicDefenceDown(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.MAGIC_DEFENCE_DOWN;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves every tiered effect.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<MagicDefenceDown> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(MagicDefenceDown::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(MagicDefenceDown::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(MagicDefenceDown::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(MagicDefenceDown::params)
                ).apply(instance, MagicDefenceDown::new));
    }

    /**
     * 「魔法攻击力アップ」: raises the <b>magic attack</b> of the caster and every ally in a radius.
     *
     * <p>Tier N = {@code +20N}%, applied as an {@code ADD_MULTIPLIED_TOTAL} modifier on
     * {@code astrum:magic_damage} — the stat a magic carrier reads ({@code impl.effect.MagicAttack}). The
     * mirror of {@link BuffAttackArea}, which owns the physical stat, and the carrier for 凯露's
     * 黑暗之蚀 「提升自身【15+15*技能等级】魔法攻击力」.
     *
     * <p>Party-scoped like every other 「味方全体」/「自分」 buff; her text says 自身, and {@code radius: 0}
     * is what makes that literal. See {@link EffectType#BUFF_MAGIC_ATTACK_AREA} for why this is a separate
     * type from {@link BuffAttackArea} rather than one buff raising both stats, and for the limitation that
     * raising the stat only changes what a carrier that <em>reads</em> it deals.
     *
     * @param params the tier plus the radius and duration
     */
    @ApiStatus.Experimental
    record BuffMagicAttackArea(Trigger trigger, TriggerParams triggerParams, double chance,
                               TieredParams params) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.BUFF_MAGIC_ATTACK_AREA;
        }

        /**
         * {@return this hook's tier block} — the one accessor the shared dispatcher reads, so
         * one code path serves every tiered effect.
         */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<BuffMagicAttackArea> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(BuffMagicAttackArea::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(BuffMagicAttackArea::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(BuffMagicAttackArea::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(BuffMagicAttackArea::params)
                ).apply(instance, BuffMagicAttackArea::new));
    }

    // ================================================================== the two flat EX effects
    //
    // These two are why this enum gained values at all in task 39. They are NOT tiered and they are NOT
    // MobEffects: their magnitude is a point formula evaluated at the character's level, and their
    // lifetime is "until something removes it" (an EX passive) or a number of seconds. See
    // EffectType#FLAT_ATTACK_UP for why a percentage could not express them and EffectTiers for the
    // separate 20/26 question about the two <percentage> attack buffs.

    /**
     * 「提升自身【base+perLevel×技能等级】物理攻击力」 — adds flat points to {@code ATTACK_DAMAGE}.
     *
     * <h2>What this replaced, and why that matters</h2>
     * Before it, 姬塔's 龙卷的攻刃, her 龙卷的攻刃+ / 剑圣的天赋, and 可可萝's 精灵的启示 were all written as
     * {@code buff_attack_area} tier 5 — a <b>+100% multiplier</b>. The bwiki formulas are points that scale
     * with level ({@code 15+15×L}, {@code 240+15×L}), so the shipped data was a different buff wearing the
     * right name: it scaled with the player's weapon instead of with her level, and 「技能等级」 played no
     * part at all.
     *
     * <h2>Points, and where the level comes from</h2>
     * {@link FlatAmount#amountAt(int)} multiplies by the <b>character's</b> level out of the roster —
     * {@code CharacterProgress.level()} — and the applier is what passes it in. Nothing here invents a
     * reference level, and {@code LevelBands} is not consulted: EX is exempt from the band ladder by the
     * human's own rule (§15.4.4).
     *
     * @param amount   the formula, {@code {"base": 15, "perLevel": 15}}
     * @param duration how long the grant lasts; the shipped EX clauses are {@link GrantDuration#PERMANENT}
     */
    @ApiStatus.Experimental
    record FlatAttackUp(Trigger trigger, TriggerParams triggerParams, double chance, FlatAmount amount,
                        GrantDuration duration) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.FLAT_ATTACK_UP;
        }

        @Override
        public Optional<GrantDuration> grantDuration() {
            return Optional.of(duration);
        }

        @Override
        public Optional<FlatAmount> flatAmount() {
            return Optional.of(amount);
        }

        private record Params(FlatAmount amount, GrantDuration duration) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    FlatAmount.CODEC.optionalFieldOf("effectParams", FlatAmount.NONE).forGetter(Params::amount),
                    GrantDuration.CODEC.optionalFieldOf("effectParams", GrantDuration.PERMANENT)
                            .forGetter(Params::duration)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<FlatAttackUp> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(FlatAttackUp::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                        .forGetter(FlatAttackUp::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(FlatAttackUp::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(
                        h -> new Params(h.amount(), h.duration()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new FlatAttackUp(
                trigger, triggerParams, chance, params.amount(), params.duration())));
    }

    /**
     * 「提升自身【base+perLevel×技能等级】魔法攻击力」 — adds flat points to {@code astrum:magic_damage}.
     *
     * <p>{@link FlatAttackUp}'s magic half, and the carrier for 凯露's 黑暗之蚀. Identical in every respect
     * except which attribute it writes, which is why the two records differ in one accessor and the
     * applier asks {@code EffectTypes.isMagicFlat} rather than switching on the class.
     *
     * @param amount   the formula
     * @param duration how long the grant lasts; permanent for the shipped EX
     */
    @ApiStatus.Experimental
    record FlatMagicAttackUp(Trigger trigger, TriggerParams triggerParams, double chance, FlatAmount amount,
                             GrantDuration duration) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.FLAT_MAGIC_ATTACK_UP;
        }

        @Override
        public Optional<GrantDuration> grantDuration() {
            return Optional.of(duration);
        }

        @Override
        public Optional<FlatAmount> flatAmount() {
            return Optional.of(amount);
        }

        private record Params(FlatAmount amount, GrantDuration duration) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    FlatAmount.CODEC.optionalFieldOf("effectParams", FlatAmount.NONE).forGetter(Params::amount),
                    GrantDuration.CODEC.optionalFieldOf("effectParams", GrantDuration.PERMANENT)
                            .forGetter(Params::duration)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<FlatMagicAttackUp> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(FlatMagicAttackUp::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(FlatMagicAttackUp::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(FlatMagicAttackUp::chance),
                        Params.CODEC.fieldOf("effectParams").forGetter(
                                h -> new Params(h.amount(), h.duration()))
                ).apply(instance, (trigger, triggerParams, chance, params) -> new FlatMagicAttackUp(
                        trigger, triggerParams, chance, params.amount(), params.duration())));
    }

    /**
     * 「召唤精灵」: a ring of orbiting particle spirits around the caster, for as long as the effect lasts.
     *
     * <h2>Why a status effect and not an entity</h2>
     * The human's instruction was to avoid a new entity model and to make the summon a <b>buff</b>, with the
     * level used as the count. See {@link EffectType#SUMMON_SPIRIT}: a {@code MobEffect} brings its own HUD
     * entry, its own expiry clock and {@code /effect give} for free, and the spirits themselves are particles
     * broadcast by {@code ServerLevel.sendParticles} so every player nearby sees them.
     *
     * <p>Tier N is <b>N spirits</b> (5 rungs, one each) and the amplifier is {@code N - 1}. The receiver set is
     * the caster alone — a summon orbits <em>her</em> — so {@code radius} is not read.
     *
     * @param params the tier plus the duration; {@code radius} is ignored
     */
    @ApiStatus.Experimental
    record SummonSpirit(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.SUMMON_SPIRIT;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<SummonSpirit> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(SummonSpirit::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(SummonSpirit::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(SummonSpirit::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(SummonSpirit::params)
                ).apply(instance, SummonSpirit::new));
    }

    /**
     * 「计数」: grants marks to the caster — 雪菲's 冰龙之印.
     *
     * <h2>The one grant that accumulates</h2>
     * Each firing adds marks and refreshes the duration, up to the cap. That is the opposite of
     * {@code SHIELD}'s rule and deliberately so; {@code Counters}' javadoc explains why a tally and a quantity
     * of health must behave differently, so nobody "fixes" this into a replacement.
     *
     * <h2>Why the grant is a formula and not a number</h2>
     * bwiki writes 雪菲's grants as <b>「【5+计数器枚数】个」</b> — the number of marks depends on how many she
     * already holds, so it cannot be a constant and it cannot be a tier. {@code base + perMark × held} is the
     * whole formula, and the same shape covers the constant grants (「追加4个」 is {@code perMark: 0}).
     *
     * <h2>Where the cap comes from</h2>
     * {@code cap} is the number bwiki <em>states</em> (「最大10个」 / 「最多可获得20个」), and it is
     * <b>not</b> the number applied. The applied cap is the rung's, read from {@link EffectTiers} through
     * {@code Counters.tierForStatedCap}, so the ladder keeps its one copy and a grant that would pass the
     * rung's cap clamps and says so once. The stated number stays in the data because it is the provenance of
     * the rung choice and a reader must be able to check it.
     *
     * @param base      marks granted before the per-mark part
     * @param perMark   marks granted per mark the caster already holds, {@code 0} for a constant grant
     * @param cap       the cap exactly as bwiki states it, in marks
     * @param seconds   how long the refreshed instance lasts
     */
    @ApiStatus.Experimental
    record Counter(Trigger trigger, TriggerParams triggerParams, double chance, int base, int perMark,
                   int cap, double seconds) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.COUNTER;
        }

        private record Params(int base, int perMark, int cap, double seconds) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.optionalFieldOf("base", 1).forGetter(Params::base),
                    Codec.INT.optionalFieldOf("perMark", 0).forGetter(Params::perMark),
                    Codec.INT.fieldOf("cap").forGetter(Params::cap),
                    Codec.DOUBLE.optionalFieldOf("seconds", 180.0).forGetter(Params::seconds)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<Counter> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Counter::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                        .forGetter(Counter::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Counter::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(
                        h -> new Params(h.base(), h.perMark(), h.cap(), h.seconds()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new Counter(
                trigger, triggerParams, chance, params.base(), params.perMark(), params.cap(),
                params.seconds())));
    }

    /**
     * Spends marks of {@link Counter} and turns them into damage — 雪菲's 技能1 「移除自身1个『冰龙之印』」 plus
     * its 「+【0.4×物理攻击力】×『冰龙之印』」 bonus.
     *
     * <h2>Why the two numbers are separate, and why one of them is a "before"</h2>
     * bwiki's clauses are two different statements and neither can be derived from the other:
     *
     * <pre>
     *   移除自身1个「冰龙之印」                      -> remove 1
     *   伤害 = 【0.4*物理攻击力+10+10*等级】+【0.4*物理攻击力】*「冰龙之印」   -> the bonus scales with the
     *                                                                          marks she HELD
     * </pre>
     *
     * <p>So {@link #remove()} says how many marks leave and {@link #damagePerMark()} says what each mark she
     * was <b>holding when the hook fired</b> is worth. Reading the count after the removal would make the
     * bonus one mark short every time, and reading it as "the marks removed" would make it worth one mark.
     * Both are wrong in a way nothing would report, so both numbers are named here.
     *
     * @param remove        how many marks to take away; {@code 1} in every shipped use
     * @param damagePerMark damage added per mark the caster held, before the removal
     * @param damageType    the damage type to file the bonus under, empty for {@code astrum:hook_damage}
     */
    @ApiStatus.Experimental
    record ConsumeCounter(Trigger trigger, TriggerParams triggerParams, double chance, int remove,
                          double damagePerMark, Optional<ResourceLocation> damageType) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CONSUME_COUNTER;
        }

        /** The pre-{@code damageType} shape: physical, which is what every shipped use means. */
        public ConsumeCounter(Trigger trigger, TriggerParams triggerParams, double chance, int remove,
                double damagePerMark) {
            this(trigger, triggerParams, chance, remove, damagePerMark, Optional.empty());
        }

        private record Params(int remove, double damagePerMark, Optional<ResourceLocation> damageType) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("remove").forGetter(Params::remove),
                    Codec.DOUBLE.fieldOf("damagePerMark").forGetter(Params::damagePerMark),
                    ResourceLocation.CODEC.optionalFieldOf("damageType").forGetter(Params::damageType)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<ConsumeCounter> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(ConsumeCounter::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(ConsumeCounter::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ConsumeCounter::chance),
                        Params.CODEC.fieldOf("effectParams").forGetter(
                                h -> new Params(h.remove(), h.damagePerMark(), h.damageType()))
                ).apply(instance, (trigger, triggerParams, chance, params) -> new ConsumeCounter(
                        trigger, triggerParams, chance, params.remove(), params.damagePerMark(),
                        params.damageType())));
    }

    // ================================================================== the two transfer effects
    //
    // 「他充」 and 「他奶」: the same two quantities the instant forms already grant, delivered to ONE receiver
    // chosen by {@code impl.effect.TransferTarget} — the nearest ally who has a UB gauge, else the caster. Each
    // record carries `TieredParams` like every other tiered effect, so a pack writes 「他充 20」 as
    // `"tier": 4` and the ladder stays in `EffectTiers`.
    //
    // The `radius` field is what "周围" means; it is the only field these two add to the tiered shape, because
    // the receiver rule needs to know how far to look.

    /**
     * 「他充」: grants UB charge to the nearest ally who has a gauge, else to the caster.
     *
     * <h2>Why the receiver is not {@code area_charge}</h2>
     * See {@link EffectType#TRANSFER_CHARGE}: that one charges everyone and takes {@code includeSelf}, this one
     * charges exactly one body. The rule lives in {@code TransferTarget} and is shared with
     * {@link TransferHeal} — the human asked for the two to agree, and one function is how they cannot disagree.
     *
     * @param params the tier (6 charge points a rung) plus the search radius and nothing else; a duration would be
     *               meaningless, because the charge is granted in the tick the skill fires
     */
    @ApiStatus.Experimental
    record TransferCharge(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.TRANSFER_CHARGE;
        }

        /** {@return this hook's tier block} — the one accessor the shared dispatcher reads. */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<TransferCharge> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(TransferCharge::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(TransferCharge::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(TransferCharge::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(TransferCharge::params)
                ).apply(instance, TransferCharge::new));
    }

    /**
     * 「他奶」: restores health to the same single receiver {@link TransferCharge} picks.
     *
     * <h2>The amount is the instant heal's own</h2>
     * 「他奶就是套用瞬间回血的那个效果」 — so this carries {@code TieredParams} and is worth 20% of the
     * <b>receiver's</b> max health a rung, five rungs, exactly as {@link HealSelf} and {@link HealArea} are. The
     * implementation is {@code PercentHeals}, one heal convention in this mod, no second one.
     *
     * @param params the tier plus the search radius; a duration would be meaningless, because the health is
     *               restored in the tick the skill fires
     */
    @ApiStatus.Experimental
    record TransferHeal(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.TRANSFER_HEAL;
        }

        /** {@return this hook's tier block} — the one accessor the shared dispatcher reads. */
        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<TransferHeal> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(TransferHeal::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(TransferHeal::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(TransferHeal::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(TransferHeal::params)
                ).apply(instance, TransferHeal::new));
    }

    // ================================================================== the four over-time effects
    //
    // 「缓慢回血」 and 「缓慢恢复充能」, each in a self and an area form. Four values in the data, two mechanisms in
    // the code: heal-over-time is vanilla's `regeneration`, charge-over-time is this mod's
    // `astrum:charge_regen`. Both carry `TieredParams`, so the rung, the duration and (for the area forms) the
    // reach are the same three fields every other tiered effect uses.

    /**
     * 「缓慢回血」 on the caster: vanilla {@code regeneration}, one rung per level.
     *
     * <p>See {@link EffectType#REGEN_SELF} for why this is vanilla's effect rather than a heal per tick, and for
     * the honest statement of what "coexist with other vanilla effects" means (one entry per type; the stronger
     * amplifier wins; a weaker rung on a stronger potion adds nothing).
     *
     * @param params the tier (amplifier), plus the duration; {@code radius} is ignored, because the receiver set
     *               is the caster alone
     */
    @ApiStatus.Experimental
    record RegenSelf(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.REGEN_SELF;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<RegenSelf> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(RegenSelf::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(RegenSelf::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(RegenSelf::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(RegenSelf::params)
                ).apply(instance, RegenSelf::new));
    }

    /**
     * 「缓慢回血」 on the party: the same vanilla {@code regeneration}, applied to every ally inside
     * {@code radius} — the {@link HealArea} receiver shape.
     *
     * @param params the tier, the duration and the reach
     */
    @ApiStatus.Experimental
    record RegenArea(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.REGEN_AREA;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<RegenArea> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(RegenArea::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(RegenArea::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(RegenArea::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(RegenArea::params)
                ).apply(instance, RegenArea::new));
    }

    /**
     * 「缓慢恢复充能」 on the caster: {@code astrum:charge_regen}, whose tick pays
     * {@code OverTimeEffects.chargePointsPerTick} through {@code ChargeKernel}.
     *
     * <p>One registered type for the self and area forms, so the human's 「取最强」 merge rule is vanilla's own.
     * See {@link EffectType#CHARGE_REGEN_SELF}.
     *
     * @param params the tier (amplifier), plus the duration; {@code radius} is ignored
     */
    @ApiStatus.Experimental
    record ChargeRegenSelf(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CHARGE_REGEN_SELF;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<ChargeRegenSelf> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(ChargeRegenSelf::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(ChargeRegenSelf::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ChargeRegenSelf::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(ChargeRegenSelf::params)
                ).apply(instance, ChargeRegenSelf::new));
    }

    /**
     * 「缓慢恢复充能」 on the party: the <b>same</b> {@code astrum:charge_regen} effect, applied to every ally in
     * {@code radius}.
     *
     * @param params the tier, the duration and the reach
     */
    @ApiStatus.Experimental
    record ChargeRegenArea(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CHARGE_REGEN_AREA;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<ChargeRegenArea> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(ChargeRegenArea::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(ChargeRegenArea::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ChargeRegenArea::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(ChargeRegenArea::params)
                ).apply(instance, ChargeRegenArea::new));
    }

    // ================================================================== the three movement-control hooks
    //
    // A displacement, a knockback and a taunt. All three carry `TieredParams` and nothing else, because all
    // three magnitudes already have a home there:
    //
    //   * PULL's rung is a DISTANCE in blocks (1..5), the shape SUMMON_SPIRIT and COUNTER already use where
    //     the rung is the quantity rather than a fraction;
    //   * KNOCKBACK's rung is a vanilla STRENGTH (0.4 x N), likewise;
    //   * TAUNT is one rung and takes its length from `seconds` and its reach from `radius`.
    //
    // So the datapack writes the same three keys it writes everywhere else, and neither a new codec nor a
    // new field on CharacterDef (which is at its 16-component limit) was needed.

    /**
     * 「位移」: moves the struck body toward the caster — {@link EffectType#PULL}.
     *
     * <p>Tier N is N blocks of travel. The destination is computed against the level's block collisions, so
     * the body stops short of a wall rather than being placed inside one; see {@code Pulls}.
     *
     * @param params the tier, which is the distance in blocks; {@code seconds} and {@code radius} are not read
     */
    @ApiStatus.Experimental
    record Pull(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.PULL;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<Pull> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(Pull::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(Pull::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Pull::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(Pull::params)
                ).apply(instance, Pull::new));
    }

    /**
     * 「击退」: vanilla's own knockback on the struck body, away from the caster — {@link EffectType#KNOCKBACK}.
     *
     * <p>Tier N is strength {@code 0.4 × N}; 0.4 is the number vanilla's damage sequence itself passes, so
     * tier 1 is "the push the hit already gave" and the ladder says how many times more.
     *
     * @param params the tier, which is the strength; {@code seconds} and {@code radius} are not read
     */
    @ApiStatus.Experimental
    record Knockback(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.KNOCKBACK;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<Knockback> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(Knockback::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(Knockback::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Knockback::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(Knockback::params)
                ).apply(instance, Knockback::new));
    }

    /**
     * 「嘲讽」: the caster draws nearby mobs' attention — {@link EffectType#TAUNT}.
     *
     * <h2>Why the receiver is the caster</h2>
     * 「嘲讽自身」: she taunts, the mobs react. An effect instance has no field for "who applied me", so a
     * taunt carried by the <em>mob</em> would need a side channel for the taunter's uuid; a taunt carried by
     * the caster needs none. It is also the only shape that can fire for 望's ultimate, whose tiers declare
     * {@code damage: 0.0} and therefore never reach the victim-effect gate. See {@link EffectType#TAUNT}.
     *
     * @param params the single rung, the duration in {@code seconds} and the reach in {@code radius}
     */
    @ApiStatus.Experimental
    record Taunt(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.TAUNT;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<Taunt> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(Taunt::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(Taunt::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Taunt::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(Taunt::params)
                ).apply(instance, Taunt::new));
    }

    // ================================================================== the two immunity hooks
    //
    // Both are one-rung, timed party states, so their hook face is exactly the timed-effect shape every party
    // buff already has: a trigger, a tier, a duration and a reach. The shipped data reaches them through an
    // ultimate's `ub.effects` (克莉丝提娜's two rungs, 矛依未's 6★), which is the same TieredParams the hook
    // form writes — a hook that grants one is therefore not a second codec or a second mechanic, it is the
    // same state granted from a swing instead of a cast. That is also why they are EffectType values at all:
    // the dispatch key must have a hook shape for every declared effect type.

    /**
     * 「物理无敌」: grants the caster (and her party inside {@code radius}) the physical-only immunity —
     * {@link EffectType#PHYS_IMMUNITY}.
     *
     * <p>One rung, because the state is immunity rather than a magnitude; {@code seconds} is how long it lasts
     * and {@code radius} is the receiver reach ({@code 0} = the caster alone, which is what both of
     * 克莉丝提娜's ultimate rungs declare). The physical/magic split is {@code DamageReduction}'s one
     * classifier, not a field here: a grant cannot choose which half of the damage it exempts.
     *
     * @param params the single rung, the duration and the reach; the tier is validated and clamped like any
     *               other, and a declared {@code "tier": 5} (resistance V's provenance) becomes the one rung
     */
    @ApiStatus.Experimental
    record PhysImmunity(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.PHYS_IMMUNITY;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<PhysImmunity> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(PhysImmunity::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(PhysImmunity::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(PhysImmunity::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(PhysImmunity::params)
                ).apply(instance, PhysImmunity::new));
    }

    /**
     * 「控制免疫」: grants the caster (and her party inside {@code radius}) immunity to the mod's hard control —
     * {@link EffectType#CONTROL_IMMUNITY}.
     *
     * <p>One rung for the same reason as {@link PhysImmunity}: the state is 「panic cannot land」, not a
     * magnitude. {@code seconds} 180 is 矛依未's 6★ duration; {@code radius} of {@code 0} is her own body.
     * Which effects count as control is {@code ControlImmunity.controllableTypes()}' single table, so this
     * hook does not repeat that classification.
     *
     * @param params the single rung, the duration and the reach
     */
    @ApiStatus.Experimental
    record ControlImmunity(Trigger trigger, TriggerParams triggerParams, double chance, TieredParams params)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CONTROL_IMMUNITY;
        }

        @Override
        public Optional<TieredParams> tieredParams() {
            return Optional.of(params);
        }

        public static final MapCodec<ControlImmunity> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(ControlImmunity::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(ControlImmunity::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ControlImmunity::chance),
                        TieredParams.CODEC.fieldOf("effectParams").forGetter(ControlImmunity::params)
                ).apply(instance, ControlImmunity::new));
    }

    // ================================================================== the two crit debuffs
    //
    // 「降低敌方全体【100+1.25*技能等级】物理暴击 18 秒」 and 「暴击时造成的伤害降低 25% 18 秒」: the first writers of
    // `astrum:crit_*` **on the victim**. Every earlier crit effect (CritChanceUp, CritDamageUp, CritChances)
    // writes the caster's or the party's copy, which is why 未奏希's clause had no channel at all.
    //
    // Both are VICTIM-scoped states with a clock and no reach: the receiver is the body the skill landed on, so a
    // `radius` would have no meaning (`TieredEffects.applyVictim` never reads one either). Both carry the
    // magnitude as a free percentage rather than a rung, for the reason CRIT_CHANCE_UP documents: the wiki states
    // a number (360 points -> 18 percentage points; 25%), and a ladder would quantise it.

    /**
     * 「降低敌方全体【100+1.25*技能等级】物理暴击 18 秒」 — {@link EffectType#CRIT_CHANCE_DOWN}: a timed
     * <b>negative</b> {@code astrum:crit_chance} modifier on the body that was hit.
     *
     * <p>The applier is {@code CritDowns}, one class for both halves, and the removal on expiry is vanilla's own
     * (the modifier lives and dies with the effect instance). See {@link EffectType#CRIT_CHANCE_DOWN} for the
     * masking limitation the "best single source" rule in {@code Crit.chanceOf} puts on any debuff of this stat.
     *
     * @param percent the decrease in crit chance, in <b>percentage points</b> ({@code 18.0} = −18%)
     * @param seconds how long it lasts
     */
    @ApiStatus.Experimental
    record CritChanceDown(Trigger trigger, TriggerParams triggerParams, double chance, double percent,
                          double seconds) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CRIT_CHANCE_DOWN;
        }

        private record Params(double percent, double seconds) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("percent").forGetter(Params::percent),
                    Codec.DOUBLE.fieldOf("seconds").forGetter(Params::seconds)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<CritChanceDown> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(CritChanceDown::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(CritChanceDown::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(CritChanceDown::chance),
                        Params.CODEC.fieldOf("effectParams").forGetter(
                                h -> new Params(h.percent(), h.seconds()))
                ).apply(instance, (trigger, triggerParams, chance, params) -> new CritChanceDown(
                        trigger, triggerParams, chance, params.percent(), params.seconds())));
    }

    /**
     * 「暴击时造成的伤害降低 25% 18 秒」 — {@link EffectType#CRIT_DAMAGE_DOWN}: a timed <b>negative</b>
     * {@code astrum:crit_damage} modifier on the body that was hit, so the crits it lands hit softer.
     *
     * <p>{@link CritChanceDown}'s second half, with the same clock and the same receiver rule.
     *
     * @param percent the decrease in crit damage, in percent of the crit multiplier ({@code 25.0} = −25%)
     * @param seconds how long it lasts
     */
    @ApiStatus.Experimental
    record CritDamageDown(Trigger trigger, TriggerParams triggerParams, double chance, double percent,
                          double seconds) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.CRIT_DAMAGE_DOWN;
        }

        private record Params(double percent, double seconds) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("percent").forGetter(Params::percent),
                    Codec.DOUBLE.fieldOf("seconds").forGetter(Params::seconds)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<CritDamageDown> MAP_CODEC =
                RecordCodecBuilder.mapCodec(instance -> instance.group(
                        Trigger.CODEC.fieldOf("trigger").forGetter(CritDamageDown::trigger),
                        TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE)
                                .forGetter(CritDamageDown::triggerParams),
                        Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(CritDamageDown::chance),
                        Params.CODEC.fieldOf("effectParams").forGetter(
                                h -> new Params(h.percent(), h.seconds()))
                ).apply(instance, (trigger, triggerParams, chance, params) -> new CritDamageDown(
                        trigger, triggerParams, chance, params.percent(), params.seconds())));
    }
}
