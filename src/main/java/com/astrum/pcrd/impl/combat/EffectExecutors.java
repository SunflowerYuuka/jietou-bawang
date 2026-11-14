package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.StackKernel;
import com.astrum.pcrd.impl.attach.StackState;
import com.astrum.pcrd.impl.effect.AttackBuffs;
import com.astrum.pcrd.impl.ub.UbCharge;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The nine effect executors of spec §5.2 — one arm per {@code EffectType}, dispatched from
 * {@link HookEvaluator}.
 *
 * <h2>The rule this file exists to keep</h2>
 * Spec §5.2: 递减作用于「量」（伤害、回血、充能速度、持续时间），**绝不作用于「质」**（是否触发、
 * 叠层上限、引爆这个行为本身）. Every arm below therefore touches the multiplier in exactly one way —
 * it is handed to {@link EffectQuantities} (or, for {@code detonate}, to
 * {@link StackKernel#detonateDamage}) together with a declared quantity. No arm decides
 * <em>whether</em> to run on the multiplier, compares it to a threshold, passes it to
 * {@link StackKernel#add} as a cap, or lets it suppress a detonation. Reading the switch and asking
 * "where could 0.1 change a mechanism here?" has exactly one answer in this file: nowhere.
 *
 * <h2>What each arm is allowed to touch</h2>
 * <ul>
 *   <li>{@code extra_damage} — the hit's batch, type {@code astrum:hook_damage} (§5.1 rule 3).</li>
 *   <li>{@code apply_effect} — a real {@code MobEffectInstance} on the target (§5.1 rule 1: debuffs
 *       are expressed as mob effects, never as rewritten damage).</li>
 *   <li>{@code lifesteal} — heals the <b>attacker</b> for a fraction of the health the hit removed.</li>
 *   <li>{@code gain_charge} — the attacker's own gauge, through {@link UbCharge}.</li>
 *   <li>{@code area_charge} — through {@link AreaChargeSink}, the guarded sink of spec §7.4.</li>
 *   <li>{@code splash} — other entities around the target, via {@link DamageToTarget}.</li>
 *   <li>{@code stack} — {@link StackRuntime}: per attacker, per stack kind, lazily expired.</li>
 *   <li>{@code detonate} — consumes the stack and deals {@code stacks × perStack × multiplier}.</li>
 *   <li>{@code attack_buff} — a real effect on the <b>caster</b>, expiring by vanilla's own clock
 *       (see {@link #attackBuff}; it is the one arm the multiplier chain does not touch).</li>
 * </ul>
 *
 * <h2>Failure policy</h2>
 * Everything here can be reached with datapack content that names things which do not exist (an
 * unknown mob effect, a {@code detonate} with no {@code stackId}) and from inside a damage sequence, so
 * every arm that cannot proceed says so at WARN and returns. None of them throws; none of them throws
 * away the rest of the wheel.
 */
@ApiStatus.Internal
public final class EffectExecutors {

    /** The one damage type attachment damage uses (spec §5.1 rule 3). */
    public static final ResourceLocation HOOK_DAMAGE = InternalIds.id("hook_damage");

    private EffectExecutors() {
    }

    /**
     * Runs one hook's effect.
     *
     * <p>Exhaustive on purpose (no {@code default}): a ninth {@link com.astrum.pcrd.api.v1.EffectType}
     * stops compiling here, which is the reminder to give it an executor.
     *
     * @param hook the effect to apply
     * @param ctx  the event it applies to, with this slot's multiplier
     * @return the stack id whose count this hook filled to its cap, or {@code null} for every other
     *         outcome — the one fact a following {@code stack_full} evaluation needs
     */
    public static @Nullable ResourceLocation run(EffectHook hook, HookContext ctx) {
        return switch (hook.type()) {
            case EXTRA_DAMAGE -> {
                extraDamage((EffectHook.ExtraDamage) hook, ctx);
                yield null;
            }
            case APPLY_EFFECT -> {
                applyEffect((EffectHook.ApplyEffect) hook, ctx);
                yield null;
            }
            case LIFESTEAL -> {
                lifesteal((EffectHook.Lifesteal) hook, ctx);
                yield null;
            }
            case GAIN_CHARGE -> {
                gainCharge((EffectHook.GainCharge) hook, ctx);
                yield null;
            }
            case AREA_CHARGE -> {
                areaCharge((EffectHook.AreaCharge) hook, ctx);
                yield null;
            }
            case SPLASH -> {
                splash((EffectHook.Splash) hook, ctx);
                yield null;
            }
            case STACK -> stack((EffectHook.Stack) hook, ctx);
            case DETONATE -> {
                detonate((EffectHook.Detonate) hook, ctx);
                yield null;
            }
            case ATTACK_BUFF -> {
                attackBuff((EffectHook.AttackBuff) hook, ctx);
                yield null;
            }
        };
    }

    // ------------------------------------------------------------------ damage

    private static void extraDamage(EffectHook.ExtraDamage hook, HookContext ctx) {
        float amount = EffectQuantities.scaled(hook.amount(), ctx.multiplier());
        if (amount <= 0.0F) {
            Astrum.LOGGER.debug("astrum: extra_damage of {} scaled to nothing on {}",
                    hook.amount(), name(ctx.target()));
            return;
        }
        ctx.batch().add(HOOK_DAMAGE, amount);
        Astrum.LOGGER.debug("astrum: extra_damage {} x {} = {} queued on {} (multiplier {})",
                hook.amount(), ctx.multiplier(), amount, name(ctx.target()), ctx.multiplier());
    }

    /**
     * {@code splash}: the same damage type against every other living entity inside the radius.
     *
     * <p>The primary target is excluded (it already took the main hit) and so is the attacker (a splash
     * that hits the caster is a bug every player would notice). The bounding box is a cube, so the
     * sphere is enforced with a squared-distance test rather than by the box.
     */
    private static void splash(EffectHook.Splash hook, HookContext ctx) {
        float damage = EffectQuantities.scaled(hook.damage(), ctx.multiplier());
        List<LivingEntity> nearby = around(ctx, hook.radius());
        if (damage <= 0.0F) {
            Astrum.LOGGER.debug("astrum: splash of {} scaled to nothing ({} candidates skipped)",
                    hook.damage(), nearby.size());
            return;
        }
        int hit = 0;
        for (LivingEntity other : nearby) {
            ctx.otherTargets().add(other, HOOK_DAMAGE, damage);
            hit++;
            Astrum.LOGGER.debug("astrum: splash {} -> {} within {} of {}", damage, name(other),
                    hook.radius(), name(ctx.target()));
        }
        Astrum.LOGGER.debug("astrum: splash {} x {} = {} on {} candidates around {}", hook.damage(),
                ctx.multiplier(), damage, hit, name(ctx.target()));
    }

    private static void detonate(EffectHook.Detonate hook, HookContext ctx) {
        Optional<ResourceLocation> stackId = hook.triggerParams().stackId();
        if (stackId.isEmpty()) {
            Astrum.LOGGER.warn("astrum: detonate on {} has no triggerParams.stackId, so there is no "
                    + "stack to consume; the hook is skipped. Name the stack it detonates.",
                    ctx.characterId());
            return;
        }
        // Consumed before the damage is computed, and consumed whatever the damage turns out to be:
        // "a detonation happened" is the mechanism, and §5.2 puts it beyond the multiplier's reach.
        int stacks = StackRuntime.consume(ctx.target(), ctx.attacker(), stackId.get());
        float damage = StackKernel.detonateDamage(stacks, hook.damagePerStack(), ctx.multiplier());
        Astrum.LOGGER.debug("astrum: detonate {} stacks x {} x multiplier {} = {} on {} (radius {})",
                stacks, hook.damagePerStack(), ctx.multiplier(), damage, name(ctx.target()), hook.radius());
        if (damage <= 0.0F) {
            return;
        }
        ctx.batch().add(HOOK_DAMAGE, damage);
        for (LivingEntity other : around(ctx, hook.radius())) {
            ctx.otherTargets().add(other, HOOK_DAMAGE, damage);
            Astrum.LOGGER.debug("astrum: detonate splash {} -> {}", damage, name(other));
        }
    }

    // ------------------------------------------------------------------ the attacker's own resources

    private static void lifesteal(EffectHook.Lifesteal hook, HookContext ctx) {
        double fraction = hook.fraction();
        float heal = EffectQuantities.scaled(fraction * ctx.dealt(), ctx.multiplier());
        if (heal <= 0.0F) {
            Astrum.LOGGER.debug("astrum: lifesteal of {} x {} dealt x multiplier {} = nothing on {}",
                    fraction, ctx.dealt(), ctx.multiplier(), name(ctx.attacker()));
            return;
        }
        ctx.attacker().heal(heal);
        Astrum.LOGGER.debug("astrum: lifesteal {} x {} dealt x multiplier {} = {} -> {} hp {}/{}",
                fraction, ctx.dealt(), ctx.multiplier(), heal, name(ctx.attacker()),
                ctx.attacker().getHealth(), ctx.attacker().getMaxHealth());
    }

    private static void gainCharge(EffectHook.GainCharge hook, HookContext ctx) {
        double amount = EffectQuantities.scaled(hook.amount(), ctx.multiplier());
        if (amount <= 0.0) {
            Astrum.LOGGER.debug("astrum: gain_charge of {} scaled to nothing for {} (multiplier {})",
                    hook.amount(), name(ctx.attacker()), ctx.multiplier());
            return;
        }
        UbCharge.apply(ctx.attacker(), ctx.characterId(), amount);
        Astrum.LOGGER.debug("astrum: gain_charge {} x {} = {} for {}", hook.amount(), ctx.multiplier(),
                amount, name(ctx.attacker()));
    }

    private static void areaCharge(EffectHook.AreaCharge hook, HookContext ctx) {
        double amount = EffectQuantities.scaled(hook.amount(), ctx.multiplier());
        if (amount <= 0.0) {
            Astrum.LOGGER.debug("astrum: area_charge of {} scaled to nothing for {} (multiplier {})",
                    hook.amount(), name(ctx.attacker()), ctx.multiplier());
            return;
        }
        Astrum.LOGGER.debug("astrum: area_charge request {} x {} = {} within {} of {} (includeSelf {})",
                hook.amount(), ctx.multiplier(), amount, hook.radius(), name(ctx.attacker()),
                hook.includeSelf());
        ctx.areaCharge().deliver(new AreaChargeSink.Request(ctx.attacker(), ctx.characterId(),
                hook.radius(), amount, hook.includeSelf()));
    }

    // ------------------------------------------------------------------ the target's own state

    /**
     * {@code apply_effect}: a real {@link MobEffectInstance}.
     *
     * <p>Two mechanism decisions live here. The <b>amplifier is not scaled</b>: it is an integer effect
     * level, and rounding it would silently turn "level II for half as long" into "level I". The
     * <b>duration is scaled but floored at one tick</b> (§5.2): a 1-tick debuff on a rank-5 slot
     * computes 0.1 ticks, and letting that round to 0 would mean the debuff is never applied at all —
     * the multiplier switching an effect off, which is precisely what the spec forbids.
     */
    private static void applyEffect(EffectHook.ApplyEffect hook, HookContext ctx) {
        Optional<Holder.Reference<MobEffect>> effect = BuiltInRegistries.MOB_EFFECT.getHolder(hook.effect());
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: apply_effect names unknown mob effect {} (character {}); hook "
                    + "skipped. Is the effect id correct for this loader?", hook.effect(),
                    ctx.characterId());
            return;
        }
        int ticks = EffectQuantities.durationTicks(hook.duration(), ctx.multiplier());
        if (ticks <= 0) {
            Astrum.LOGGER.debug("astrum: apply_effect {} declared 0 ticks, nothing to apply on {}",
                    hook.effect(), name(ctx.target()));
            return;
        }
        MobEffectInstance instance = new MobEffectInstance(effect.get(), ticks, hook.amplifier());
        boolean applied = ctx.target().addEffect(instance);
        Astrum.LOGGER.debug("astrum: apply_effect {} {}t amp {} x multiplier {} -> {}t applied={} on {}",
                hook.effect(), hook.duration(), hook.amplifier(), ctx.multiplier(), ticks, applied,
                name(ctx.target()));
    }

    /**
     * {@code attack_buff}: a real {@code MobEffectInstance} on the <b>caster</b>, so it expires by itself.
     *
     * <p>The one hook whose number is <b>not</b> scaled by the multiplier chain, deliberately: its
     * "amount" is a ratio ({@code 1.5} = +50%) over which {@link EffectQuantities#scaled} is meaningless
     * — scaling {@code 1.5} by a rank-5 {@code 0.1} would give {@code 1.05}, i.e. {@code +5%}, and an
     * affection modifier of {@code 1.5} would turn a 50% buff into a 100% one. §5.2's dimension is 量 of
     * the effect (damage, healing, charge, duration); a <em>derived attribute ratio</em> is not one of
     * them, and {@code amplifier} is excluded for the same reason.
     *
     * <p>The <b>duration</b> is a quantity and is still not scaled: the wiki states a fixed 18 seconds,
     * and a per-slot-different lifetime would make one ultimate behave differently by party position.
     */
    private static void attackBuff(EffectHook.AttackBuff hook, HookContext ctx) {
        boolean applied = AttackBuffs.apply(ctx.attacker(), hook.multiplier(), hook.seconds(),
                ctx.characterId());
        Astrum.LOGGER.debug("astrum: attack_buff x{} for {}s on {} applied={} (character {})",
                hook.multiplier(), hook.seconds(), name(ctx.attacker()), applied, ctx.characterId());
    }

    /**
     * {@code stack}: one stack per hit, capped by the hook.
     *
     * <p>No multiplier anywhere: §5.2 lists 叠层上限 among the things diminishing returns must not
     * touch, so this arm has no way to be handed one.
     *
     * @return the stack id when this hit took the count <b>to</b> the cap (not when it was already
     *         there), so {@code stack_full} fires once per fill rather than on every capped hit
     */
    private static @Nullable ResourceLocation stack(EffectHook.Stack hook, HookContext ctx) {
        StackState before = StackRuntime.read(ctx.target(), ctx.attacker(), hook.stackId());
        StackState next = StackRuntime.add(ctx.target(), ctx.attacker(), hook.stackId(), 1, hook.max());
        boolean reachedCap = reachesCap(before.stacks(), next.stacks(), hook.max());
        Astrum.LOGGER.debug("astrum: stack {} on {} for {} -> {}/{} (lastHitTick {}){}", hook.stackId(),
                name(ctx.target()), name(ctx.attacker()), next.stacks(), hook.max(), next.lastHitTick(),
                reachedCap ? " REACHED CAP" : "");
        return reachedCap ? hook.stackId() : null;
    }

    /**
     * {@return whether this hit took the count <b>to</b> the cap, i.e. whether it was a fill}
     *
     * <p>This predicate <em>is</em> the "once per fill, not once per hit at the cap" rule: the
     * {@code stack_full} trigger must fire on the transition, and a later hit that leaves an already
     * full stack full is not a transition. {@code before < max} is exactly that half; {@code max <= 0}
     * is not a cap at all and can never be filled.
     *
     * <p>Pure and package-private on purpose. {@link #stack} itself needs a live {@link HookContext}
     * and an entity attachment, so without this seam the rule would have no test outside a running
     * server — which is how it went unasserted in the first place.
     *
     * @param before stacks before the hit
     * @param after  stacks after the hit, already clamped to {@code max} by {@link StackKernel#add}
     * @param max    the hook's cap, never scaled (spec §5.2)
     */
    static boolean reachesCap(int before, int after, int max) {
        return max > 0 && before < max && after >= max;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@return the entities a radius effect reaches around the current target}
     *
     * <p>Excludes the target itself and the attacker, and enforces a sphere rather than
     * {@code AABB.inflate}'s cube. Expired stacks are irrelevant here, but dead entities are not: a
     * {@code hurt()} on a corpse is a wasted damage sequence.
     */
    private static List<LivingEntity> around(HookContext ctx, double radius) {
        LivingEntity target = ctx.target();
        if (!(radius > 0.0)) {
            return List.of();
        }
        double radiusSquared = radius * radius;
        return target.level()
                .getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(radius),
                        other -> other != target && other != ctx.attacker() && other.isAlive()
                                && !other.isSpectator()
                                && other.distanceToSqr(target) <= radiusSquared);
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
