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
                EffectHook.AttackBuff {

    Trigger trigger();

    TriggerParams triggerParams();

    double chance();

    EffectType type();

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
    });

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
     * @param amount raw damage added
     */
    @ApiStatus.Experimental
    record ExtraDamage(Trigger trigger, TriggerParams triggerParams, double chance, double amount) implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.EXTRA_DAMAGE;
        }

        private record Params(double amount) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("amount").forGetter(Params::amount)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<ExtraDamage> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(ExtraDamage::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(ExtraDamage::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(ExtraDamage::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.amount()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new ExtraDamage(
                trigger, triggerParams, chance, params.amount())));
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
     */
    @ApiStatus.Experimental
    record Splash(Trigger trigger, TriggerParams triggerParams, double chance, double radius, double damage)
            implements EffectHook {
        @Override
        public EffectType type() {
            return EffectType.SPLASH;
        }

        private record Params(double radius, double damage) {
            private static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.DOUBLE.fieldOf("radius").forGetter(Params::radius),
                    Codec.DOUBLE.fieldOf("damage").forGetter(Params::damage)
            ).apply(instance, Params::new));
        }

        public static final MapCodec<Splash> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Trigger.CODEC.fieldOf("trigger").forGetter(Splash::trigger),
                TriggerParams.CODEC.optionalFieldOf("triggerParams", TriggerParams.NONE).forGetter(Splash::triggerParams),
                Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(Splash::chance),
                Params.CODEC.fieldOf("effectParams").forGetter(h -> new Params(h.radius(), h.damage()))
        ).apply(instance, (trigger, triggerParams, chance, params) -> new Splash(
                trigger, triggerParams, chance, params.radius(), params.damage())));
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
}
