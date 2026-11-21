package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.StackKernel;
import com.astrum.pcrd.impl.attach.StackState;
import com.astrum.pcrd.impl.effect.AreaBuffs;
import com.astrum.pcrd.impl.effect.AttackBuffRules;
import com.astrum.pcrd.impl.effect.AttackBuffs;
import com.astrum.pcrd.impl.effect.Counters;
import com.astrum.pcrd.impl.effect.CritChanceUps;
import com.astrum.pcrd.impl.effect.LostHealth;
import com.astrum.pcrd.impl.effect.CritDowns;
import com.astrum.pcrd.impl.effect.CritDamageUps;
import com.astrum.pcrd.impl.effect.Heals;
import com.astrum.pcrd.impl.effect.OverTimeEffects;
import com.astrum.pcrd.impl.effect.PartyVanillaEffects;
import com.astrum.pcrd.impl.effect.PercentHeals;
import com.astrum.pcrd.impl.effect.TieredAmounts;
import com.astrum.pcrd.impl.effect.TieredEffects;
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
 * The effect executors of spec §5.2, dispatched from {@link HookEvaluator}: one arm per
 * {@code EffectType}, <b>except</b> the eleven tiered ones, which are one arm because they are one
 * mechanism (see {@link #tiered}).
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
 * <p><b>And the tiered effects do not even see it.</b> A tier is a <em>strength</em>, not a quantity, so
 * {@link TieredEffects} scales nothing but a debuff's duration — which is the one quantity §5.2 lists that
 * a tiered effect has. That is why the eleven-into-one arm passes the multiplier through rather than
 * consuming it.
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
 *   <li>{@code heal} — restores health on the caster and her party (§5.2's 回血, see {@link Heals}).</li>
 *   <li>{@code ally_effect} — a named vanilla effect on the caster and her party; the party-side
 *       mirror of {@code apply_effect} (see {@link PartyVanillaEffects}).</li>
 *   <li><b>the eleven tiered effects</b> ({@code defence_down} and the ten generic types) — <b>one</b> arm,
 *       {@link #tiered}, which reads the effect's own receiver set and hands the whole job to
 *       {@link TieredEffects}. That is the same call the ultimate's settlement makes, so a tier means one
 *       thing whether it arrives on a swing or on an ultimate. The exceptions it routes itself:
 *       {@code percent_damage} is damage and joins this hit's {@link DamageBatch}.</li>
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

    /**
     * "Nobody resolved a tier for this hook": the two-argument {@link #run(EffectHook, HookContext)} form's
     * answer, which makes each tiered arm use the hook's own declared tier.
     *
     * <p>{@code 0} rather than "any", because a tier is 1-based throughout the schema: no legal tier is 0, so
     * this value cannot be confused with one an author wrote.
     */
    public static final int UNRESOLVED_TIER = 0;

    private EffectExecutors() {
    }

    /**
     * Runs one hook's effect <b>as declared</b>, with no level band.
     *
     * <p>Kept for the callers that have no character in hand — a probe, a test, an ultimate whose caster has no
     * roster row. It is <b>not</b> the production route: a party slot's hook arrives through
     * {@link #run(EffectHook, HookContext, int)}, which is handed the tier the collapse resolved.
     *
     * @param hook the effect to apply
     * @param ctx  the event it applies to, with this slot's multiplier
     * @return the stack id whose count this hook filled to its cap, or {@code null} otherwise
     */
    public static @Nullable ResourceLocation run(EffectHook hook, HookContext ctx) {
        return run(hook, ctx, UNRESOLVED_TIER);
    }

    /**
     * Runs one hook's effect at a <b>tier already resolved</b> by the firing's collapse.
     *
     * <h2>What {@code tier} means, and who resolved it</h2>
     * {@link com.astrum.pcrd.impl.effect.EffectCollapse} looked at every hook of this firing, added the
     * character's level band, clamped each one against its effect's ladder, and took the <b>highest</b> rung
     * per (type, reach, clock). So by the time an arm here runs, "which rung" is decided — and it is decided
     * <b>once for the whole firing</b>, which is what makes 「不同技能都相同效果，取最高等级」 true instead of
     * eleven executors each maximising its own corner.
     *
     * <p>{@link #UNRESOLVED_TIER} is the "nobody told me" value for the two-argument form, and every tiered arm
     * falls back to the hook's own declared tier for it — so the two call paths cannot disagree about a number
     * that was never supplied.
     *
     * @param hook the effect to apply
     * @param ctx  the event it applies to, with this slot's multiplier
     * @param tier the resolved tier for a tiered hook; {@code 0} or {@link #UNRESOLVED_TIER} to use the hook's own
     * @return the stack id whose count this hook filled to its cap, or {@code null} for every other
     *         outcome — the one fact a following {@code stack_full} evaluation needs
     */
    public static @Nullable ResourceLocation run(EffectHook hook, HookContext ctx, int tier) {
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
            // The timed crit-damage buff: one more non-tiered hook whose magnitude is a ratio the datapack states,
            // so it runs beside attack_buff rather than through the tier arm. Its receiver set and its clock are
            // AttackBuffs'/AttackBuffRules' — the same party rule, not a second one.
            case CRIT_DAMAGE_UP -> {
                critDamageUp((EffectHook.CritDamageUp) hook, ctx);
                yield null;
            }
            // The crit-<b>chance</b> twin of the arm above, for the same reason: its magnitude is a percentage the
            // wiki states exactly, not a rung, so it runs beside the tiered arm rather than through it.
            case CRIT_CHANCE_UP -> {
                critChanceUp((EffectHook.CritChanceUp) hook, ctx);
                yield null;
            }
            // The two victim-side crit debuffs: a negative modifier on the body the swing landed on. Not tiered
            // (their magnitude is the wiki's own percentage, not a rung), and the receiver is the hit's target
            // rather than a party, so they carry their own arm — see CritDowns.
            case CRIT_CHANCE_DOWN -> {
                critDown((EffectHook.CritChanceDown) hook, ctx);
                yield null;
            }
            case CRIT_DAMAGE_DOWN -> {
                critDown((EffectHook.CritDamageDown) hook, ctx);
                yield null;
            }
            // The tally-scaled charge regen: neither tiered (its magnitude is base + perMark x her marks) nor a
            // one-tick grant (it pays every tick of its own clock), so it has its own arm and its own effect.
            case COUNTER_CHARGE_REGEN -> {
                counterChargeRegen((EffectHook.CounterChargeRegen) hook, ctx);
                yield null;
            }
            case HEAL -> {
                heal((EffectHook.Heal) hook, ctx);
                yield null;
            }
            case ALLY_EFFECT -> {
                allyEffect((EffectHook.AllyEffect) hook, ctx);
                yield null;
            }
            case DEFENCE_DOWN -> {
                tiered(hook, ctx, tier);
                yield null;
            }
            // ---------------------------------------------------------- the ten generic tiered effects
            //
            // One arm for all eleven, because they are one mechanism: a tier applied to a receiver set.
            // The set is the effect's own (EffectType.reachesParty / reachesVictim), the arithmetic is
            // EffectTiers', and the application is TieredEffects' — the same call the ultimate's settlement
            // makes, which is what makes a tier mean one thing on a swing and on an ultimate.
            case HEAL_AREA, HEAL_SELF, BUFF_ATTACK_AREA, BUFF_PHYS_DEFENCE_AREA, BUFF_MAGIC_DEFENCE_AREA,
                 REMOVE_SHIELD, HASTE, PANIC, PERCENT_DAMAGE, CHARGE_RATE_UP, SHIELD,
                 // The two typed barrier channels ride the shield's own arm: their tier block, ladder, clock
                 // and receiver set are the shared barrier's, and `tiered` only has to hand the type through so
                 // TieredEffects can pick the channel. A separate arm would be a second place for a rung.
                 SHIELD_PHYS, SHIELD_MAGIC,
                 MAGIC_DEFENCE_DOWN, BUFF_MAGIC_ATTACK_AREA, SUMMON_SPIRIT,
                 TRANSFER_CHARGE, TRANSFER_HEAL, REGEN_SELF, REGEN_AREA,
                 CHARGE_REGEN_SELF, CHARGE_REGEN_AREA,
                 // The three movement-control mechanics ride the same arm: they are tiered, their rung is
                 // their magnitude (a distance, a vanilla strength, a duration) and the tier arrives already
                 // resolved by EffectCollapse. PULL and KNOCKBACK are victim-scoped; TAUNT is party-scoped
                 // because the taunter carries it. `tiered` reads EffectType.reachesVictim() to send each to
                 // the matching half, so there is no second dispatch here.
                 // The two immunity channels ride it for the same reason: one rung each, and `tiered` sends a
                 // party-scoped type to TieredEffects.applyParty — the same arm a hook and an ultimate share.
                 PULL, KNOCKBACK, TAUNT, PHYS_IMMUNITY, CONTROL_IMMUNITY -> {
                tiered(hook, ctx, tier);
                yield null;
            }
            // The tally and the spend, each its own arm because neither is a rung.
            //
            // COUNTER is not tiered: its magnitude is base + perMark x <what she already holds>, and its cap
            // rung is DERIVED from the stated cap by Counters.tierForStatedCap. A tier in the data would be a
            // second way to say the same thing and a chance for the two to disagree.
            case COUNTER -> {
                counter((EffectHook.Counter) hook, ctx);
                yield null;
            }
            // CONSUME_COUNTER spends what COUNTER granted. Reading the tally, removing from it and turning the
            // remainder into damage is one step; splitting it across a tier resolution would put a rung
            // between the two numbers that must agree.
            case CONSUME_COUNTER -> {
                consumeCounter((EffectHook.ConsumeCounter) hook, ctx);
                yield null;
            }
            // The two flat EX effects. They are NOT tiered, so they do not go through the arm above: their
            // magnitude is a point formula at the character's level and their lifetime is an ownership
            // (the party slot) rather than a clock. A hook-form flat effect is a timed buff; the permanent
            // form is what ExPassives applies on enrolment, and it never arrives here.
            case FLAT_ATTACK_UP, FLAT_MAGIC_ATTACK_UP -> {
                flatAttack(hook, ctx);
                yield null;
            }
        };
    }

    // ------------------------------------------------------------------ damage

    /**
     * The two <b>flat</b> EX effects: a number of attribute points, computed from the character's level.
     *
     * <h2>Why this is not part of {@code tiered}</h2>
     * {@code tiered} resolves a <em>rung</em> and applies a {@code MobEffect}; these have no rung and no
     * effect. They write one attribute modifier under a per-character id
     * ({@link com.astrum.pcrd.impl.effect.FlatAttack}), whose owner is the character the grant belongs to —
     * which is why the character id travels with the grant and not with the effect.
     *
     * <h2>Why a hook-form flat grant is refused</h2>
     * An EX clause's flat grant is permanent and owned by the party slot. A <em>hook</em> fires during a
     * swing, and a timed flat grant would need a per-character owner inside a {@code MobEffectInstance}
     * that has no field for it — see {@code FlatAttack.supports}. So a hook-form flat effect is reported
     * once and skipped, rather than silently applied as a permanent buff by a skill that was supposed to
     * last eight seconds.
     */
    private static void flatAttack(EffectHook hook, HookContext ctx) {
        if (ctx.attacker() == null) {
            return;
        }
        // One line per (effect, character), like TieredEffects.report: a defect in loaded data must not
        // produce a line per swing.
        String problem;
        com.astrum.pcrd.api.v1.GrantDuration duration = hook.grantDuration().orElse(null);
        if (!com.astrum.pcrd.impl.effect.FlatAttack.supports(duration)) {
            problem = "is a flat grant with a declared duration, which this build cannot own (a timed flat "
                    + "grant would need a per-character owner inside the effect instance); use attack_buff "
                    + "or a tiered buff for a timed attack boost";
        } else {
            problem = "is a flat grant, and a flat grant belongs to an EX passive (applied on enrolment "
                    + "and removed on departure), not to a hook fired during a swing";
        }
        ResourceLocation character = characterIdOf(ctx);
        if (FLAT_REPORTED.first(hook.type() + "|" + character + "|" + problem)) {
            Astrum.LOGGER.warn("astrum: {} (character {}) {}; reported once.", hook.type(), character,
                    problem);
        }
    }

    /** One line per (effect, character, problem) for the flat-effect refusals; see {@link #flatAttack}. */
    private static final com.astrum.pcrd.impl.combat.WarnOnce FLAT_REPORTED =
            new com.astrum.pcrd.impl.combat.WarnOnce();

    /**
     * {@return the character whose hook is running, for a log line}
     *
     * <p>Never throws and never answers {@code null}: the once-per-defect reporter keys on it, and a
     * {@code null} in a key would make every character's warning collapse into one.
     */
    private static ResourceLocation characterIdOf(HookContext ctx) {
        ResourceLocation character = ctx.characterId();
        return character == null ? ResourceLocation.fromNamespaceAndPath("astrum", "unknown") : character;
    }
    private static void extraDamage(EffectHook.ExtraDamage hook, HookContext ctx) {
        // 「随损失的生命值提升」 — 咲恋's 专属 烈焰斩击+ carries 【0.4*损失的生命值】. Added to the declared amount
        // before the multiplier, so it scales like the damage it is part of rather than escaping the chain.
        float scaling = hook.lostHealthBonus().isEmpty() ? 0.0F
                : LostHealth.bonusUnits(hook.lostHealthBonus().getAsDouble(), ctx.attacker());
        float amount = EffectQuantities.scaled(hook.amount() + scaling, ctx.multiplier());
        if (amount <= 0.0F) {
            Astrum.LOGGER.debug("astrum: extra_damage of {} scaled to nothing on {}",
                    hook.amount(), name(ctx.target()));
            return;
        }
        ResourceLocation type = hook.damageType().orElse(HOOK_DAMAGE);
        ctx.batch().add(type, amount);
        Astrum.LOGGER.debug("astrum: extra_damage {} x {} = {} queued on {} as {} (multiplier {})",
                hook.amount(), ctx.multiplier(), amount, name(ctx.target()), type, ctx.multiplier());
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
        // 「以自身为中心」 vs the default: the body that was struck. 矛依未's SP2 asks for the caster as the
        // centre, and `around` used to hard-code the target, so the two shapes were not expressible.
        LivingEntity centre = hook.casterCentred() ? ctx.attacker() : ctx.target();
        List<LivingEntity> nearby = around(ctx, hook.radius(), centre);
        if (damage <= 0.0F) {
            Astrum.LOGGER.debug("astrum: splash of {} scaled to nothing ({} candidates skipped)",
                    hook.damage(), nearby.size());
            return;
        }
        ResourceLocation type = hook.damageType().orElse(HOOK_DAMAGE);
        int hit = 0;
        for (LivingEntity other : nearby) {
            ctx.otherTargets().add(other, type, damage);
            hit++;
            Astrum.LOGGER.debug("astrum: splash {} -> {} within {} of {} as {}", damage, name(other),
                    hook.radius(), name(centre), type);
        }
        Astrum.LOGGER.debug("astrum: splash {} x {} = {} as {} on {} candidates around {}", hook.damage(),
                ctx.multiplier(), damage, type, hit, name(ctx.target()));
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
     * {@code attack_buff}: a real {@code MobEffectInstance}, on the caster and on her party in radius,
     * so it expires by itself.
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
     *
     * <p>The receiver set is {@code AttackBuffs}' — the caster plus every ally inside the hook's
     * {@code radius} — so this arm only passes the radius through; it does not decide who is an ally.
     */
    private static void attackBuff(EffectHook.AttackBuff hook, HookContext ctx) {
        int receivers = AttackBuffs.apply(ctx.attacker(), hook.multiplier(), hook.seconds(),
                ctx.characterId(), hook.radius());
        Astrum.LOGGER.debug("astrum: attack_buff x{} for {}s within {} blocks of {} -> {} receiver(s) "
                        + "(character {})",
                hook.multiplier(), hook.seconds(), AttackBuffs.radiusFor(hook.radius()),
                name(ctx.attacker()), receivers, ctx.characterId());
    }

    /**
     * {@code crit_damage_up}: a real {@code MobEffectInstance} raising {@code astrum:crit_damage} for the caster and
     * her party in radius, expiring by vanilla's own clock.
     *
     * <p>Like {@code attack_buff}'s ratio, {@code percent} is <b>not</b> scaled by the multiplier chain: it is a
     * derived attribute fraction, not a 量 (damage, healing, charge, duration), and scaling {@code 28} by a rank-5
     * {@code 0.1} would silently turn 「28%」 into 「2.8%」. The duration is a quantity and is still not scaled, for
     * the reason {@code attack_buff} gives: the wiki states a fixed 18 seconds, and a per-slot-different lifetime
     * would make one ultimate behave differently by party position.
     *
     * <p>The receiver set is {@code AttackBuffs}' — the caster plus every ally inside the hook's {@code radius} — so
     * this arm only passes the radius through; it does not decide who is an ally.
     */
    private static void critDamageUp(EffectHook.CritDamageUp hook, HookContext ctx) {
        int receivers = CritDamageUps.apply(ctx.attacker(), hook.percent(), hook.seconds(),
                ctx.characterId(), hook.radius());
        Astrum.LOGGER.debug("astrum: crit_damage_up +{}% for {}s within {} blocks of {} -> {} receiver(s) "
                        + "(character {})",
                hook.percent(), hook.seconds(), AttackBuffs.radiusFor(hook.radius()), name(ctx.attacker()),
                receivers, ctx.characterId());
    }

    /**
     * {@code crit_chance_up}: 「提升范围内所有友方 N 物理暴击」 as a timed, party-wide chance buff.
     *
     * <p>The magnitude is a percentage the datapack states ({@code percent}), not a rung, so this arm passes it
     * straight to {@link CritChanceUps} — the same shape {@link #critDamageUp} has one stat over. Never throws:
     * it runs on the swing path, and the buff failing is a log line, not a lost hit.
     */
    private static void critChanceUp(EffectHook.CritChanceUp hook, HookContext ctx) {
        int receivers = CritChanceUps.apply(ctx.attacker(), hook.percent(), hook.seconds(),
                ctx.characterId(), hook.radius());
        Astrum.LOGGER.debug("astrum: crit_chance_up +{}% for {}s within {} blocks of {} -> {} receiver(s) "
                        + "(character {})",
                hook.percent(), hook.seconds(), AttackBuffs.radiusFor(hook.radius()), name(ctx.attacker()),
                receivers, ctx.characterId());
    }

    /**
     * The two {@code crit_*_down} hooks: a timed negative crit modifier on <b>the body this swing hit</b>.
     *
     * <p>One method for both, because the receiver, the clock and the refusal rules are the same and only the stat
     * differs — {@code CritDowns} owns both halves and the numbers. The victim is {@code ctx.target()}, which is
     * what {@code HookContext} already calls the entity that was struck; a hook of this family on a
     * {@code block}/{@code kill} trigger therefore debuffs the body that fact is about, which is the honest
     * reading of 「降低敌方」.
     */
    private static void critDown(EffectHook hook, HookContext ctx) {
        if (hook instanceof EffectHook.CritChanceDown down) {
            boolean applied = CritDowns.applyChance(ctx.target(), down.percent(), down.seconds(),
                    ctx.characterId());
            Astrum.LOGGER.debug("astrum: crit_chance_down -{}% for {}s on {} -> applied={} (character {})",
                    down.percent(), down.seconds(), name(ctx.target()), applied, ctx.characterId());
            return;
        }
        if (hook instanceof EffectHook.CritDamageDown down) {
            boolean applied = CritDowns.applyDamage(ctx.target(), down.percent(), down.seconds(),
                    ctx.characterId());
            Astrum.LOGGER.debug("astrum: crit_damage_down -{}% for {}s on {} -> applied={} (character {})",
                    down.percent(), down.seconds(), name(ctx.target()), applied, ctx.characterId());
        }
    }

    /**
     * {@code counter_charge_regen}: 「每秒恢复自身【10+「冰龙之印」】技能值」.
     *
     * <p>One effect instance with its own clock; the per-tick instalment is the effect's, so this arm only
     * applies it (through {@code OverTimeEffects} — the one over-time applier) and never pays anything itself.
     * A grant with no clock has nothing to run for, which is reported rather than applied as a zero-tick
     * instance.
     */
    private static void counterChargeRegen(EffectHook.CounterChargeRegen hook, HookContext ctx) {
        boolean applied = OverTimeEffects.applyCounterChargeRegen(ctx.attacker(), hook.base(), hook.perMark(),
                hook.seconds(), ctx.characterId());
        Astrum.LOGGER.debug("astrum: counter_charge_regen {} + {} x marks a second for {}s on {} -> applied={}",
                hook.base(), hook.perMark(), hook.seconds(), name(ctx.attacker()), applied);
    }

    /**
     * {@code heal}: restores health to the caster and her party in radius.
     *
     * <p>The amount <b>is</b> scaled (§5.2 lists 回血 among the quantities), unlike
     * {@code attack_buff}'s ratio. The receiver set is {@code Heals}' — the caster plus every ally
     * inside the hook's {@code radius} — so this arm only passes the radius through and never decides
     * who is an ally.
     */
    private static void heal(EffectHook.Heal hook, HookContext ctx) {
        float amount = EffectQuantities.scaled(hook.amount(), ctx.multiplier());
        if (amount <= 0.0F) {
            Astrum.LOGGER.debug("astrum: heal of {} scaled to nothing for {} (multiplier {})",
                    hook.amount(), name(ctx.attacker()), ctx.multiplier());
            return;
        }
        int healed = Heals.apply(ctx.attacker(), amount, ctx.characterId(), hook.radius());
        Astrum.LOGGER.debug("astrum: heal {} x {} = {} within {} blocks of {} -> {} receiver(s)",
                hook.amount(), ctx.multiplier(), amount,
                Heals.radiusFor(hook.radius()), name(ctx.attacker()), healed);
    }

    /**
     * {@code ally_effect}: a named vanilla effect on the caster and her party in radius.
     *
     * <p>The duration is a quantity and is scaled (floored at one tick, so the multiplier can never
     * switch the effect off); the amplifier is an integer level and is not — exactly the split
     * {@link #applyEffect} documents.
     */
    private static void allyEffect(EffectHook.AllyEffect hook, HookContext ctx) {
        int ticks = EffectQuantities.durationTicks(hook.duration(), ctx.multiplier());
        if (ticks <= 0) {
            Astrum.LOGGER.debug("astrum: ally_effect {} declared {} ticks, nothing to apply for {}",
                    hook.effect(), hook.duration(), ctx.characterId());
            return;
        }
        int applied = PartyVanillaEffects.apply(ctx.attacker(), hook.effect(), ticks, hook.amplifier(),
                ctx.characterId(), hook.radius());
        Astrum.LOGGER.debug("astrum: ally_effect {} {}t amp {} x multiplier {} -> {}t on {} "
                        + "receiver(s) within {} blocks", hook.effect(), hook.duration(),
                hook.amplifier(), ctx.multiplier(), ticks, applied,
                AttackBuffs.radiusFor(hook.radius()));
    }

    // ------------------------------------------------------------------ the ten generic tiered effects

    /**
     * Runs <b>any</b> tiered effect: the one arm the eleven generic types and {@code defence_down} share.
     *
     * <h2>Why this is one method and not eleven</h2>
     * Each of these effects used to have its own arm here, and each arm re-wrote the same six steps —
     * clamp the tier, pick the receiver set, turn the tier into an amplifier, look up the registered
     * effect, convert the duration, apply it. Eleven copies of a chain is eleven chances for one of them
     * to clamp differently, and the ultimate's settlement needed the same chain a twelfth time. So the
     * chain lives in {@link TieredEffects} and this method is a dispatcher: it decides nothing, it reads
     * the effect's own receiver set ({@link EffectType#reachesParty()} /
     * {@link EffectType#reachesVictim()}) and calls the matching half.
     *
     * <h2>The two types that do not go through it</h2>
     * <ul>
     *   <li>{@code PERCENT_DAMAGE} is <b>damage</b>, not a state, so it belongs to the damage path: its
     *       fraction is a share of the victim's bar and it joins this hit's {@link DamageBatch}. Routing
     *       it through "apply an effect" would mean inventing an effect application that deals damage.</li>
     *   <li>{@link EffectType#reachesParty()} being false is not enough on its own — a type in neither set
     *       is reported by {@code TieredEffects} rather than silently dropped.</li>
     * </ul>
     *
     * <h2>The multiplier</h2>
     * The <b>tier</b> is never scaled (§5.2's 质/量 split: a tier is the strength). The one quantity the
     * chain does scale is a debuff's duration, so the hook's composed multiplier is passed through for
     * that — the same parameter an ultimate passes {@code 1.0} for, because §5.4 exempts the ultimate.
     *
     * <h2>{@code resolvedTier}</h2>
     * The rung arrives <b>already resolved</b> by {@code EffectCollapse}: the character's level band was added
     * and the effect's ceiling applied, once for the whole firing, so two skills carrying the same effect
     * arrive here as one application at the higher rung. This method therefore does no tier arithmetic — it
     * substitutes the resolved rung into a copy of the hook's tier block and passes it on, which is the same
     * {@code TieredParams} the effect's own rules read.
     */
    private static void tiered(EffectHook hook, HookContext ctx, int resolvedTier) {
        Optional<TieredParams> declared = hook.tieredParams();
        if (declared.isEmpty()) {
            Astrum.LOGGER.warn("astrum: {} of {} carries no tier block, so it was skipped; this is a "
                    + "schema bug rather than pack data", hook.type(), ctx.characterId());
            return;
        }
        TieredParams params = withResolvedTier(declared.get(), resolvedTier);
        if (hook.type() == EffectType.PERCENT_DAMAGE) {
            percentDamage(hook, ctx, params);
            return;
        }
        if (hook.type().reachesVictim()) {
            // The attacker travels with the call: a pull and a knockback are relative to whoever caused
            // them, and the hook path knows exactly who that is (see TieredEffects' source parameter).
            TieredEffects.applyVictim(ctx.target(), ctx.attacker(), hook.type(), params, ctx.characterId(),
                    ctx.multiplier(), 0);
            return;
        }
        TieredEffects.applyParty(ctx.attacker(), hook.type(), params, ctx.characterId());
    }

    /**
     * {@return {@code own} with its tier replaced by {@code resolvedTier}, or {@code own} when none was given}
     *
     * <p>{@link #UNRESOLVED_TIER} and any non-positive value mean "nobody resolved this hook", and the hook's
     * own declaration is used — so the two-argument {@link #run(EffectHook, HookContext)} cannot disagree with
     * the production path about a number it was never handed, and a probe still exercises the declared tier.
     */
    static TieredParams withResolvedTier(TieredParams own, int resolvedTier) {
        if (resolvedTier <= UNRESOLVED_TIER) {
            return own;
        }
        return new TieredParams(resolvedTier, own.seconds(), own.radius());
    }

    /**
     * {@code percent_damage}: damage equal to a share of the <b>victim's</b> max health.
     *
     * <p>Tier N = {@code 10N}% of max health. It joins the same damage batch as {@code extra_damage}, so the
     * same end-of-tick flush and the same recursion fuse apply — and the damage type is the hook's, so a
     * magic caster can file it under {@code astrum:magic} and let the magic defence buff see it.
     *
     * <p>The percentage is not scaled by the multiplier chain: it is a share of the victim's bar, and scaling
     * it would let party position change what fraction of a boss's health one skill removes — the "递减作用于
     * 量" dimension belongs to the flat effects, which have a declared amount to scale.
     */
    private static void percentDamage(EffectHook hook, HookContext ctx, TieredParams params) {
        TieredAmounts.Amount amount = TieredAmounts.resolve(EffectType.PERCENT_DAMAGE, params);
        float maxHealth = ctx.target().getMaxHealth();
        float damage = PercentHeals.amountFor(maxHealth, amount.fraction());
        if (!(damage > 0.0F)) {
            Astrum.LOGGER.debug("astrum: percent_damage tier {} of {}'s max health ({}) computes nothing",
                    amount.tier(), name(ctx.target()), maxHealth);
            return;
        }
        ResourceLocation type = hook instanceof EffectHook.PercentDamage percent
                ? percent.damageType().orElse(HOOK_DAMAGE) : HOOK_DAMAGE;
        ctx.batch().add(type, damage);
        Astrum.LOGGER.debug("astrum: percent_damage tier {} = {}% of {}'s max health {} = {} queued as {}",
                amount.tier(), Math.round(amount.fraction() * 100.0), name(ctx.target()), maxHealth, damage,
                type);
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

    /**
     * {@code counter}: adds {@code base + perMark × held} marks to the caster, up to the rung's cap.
     *
     * <h2>The formula is why this is not a tiered effect</h2>
     * bwiki's grant is 「【5+计数器枚数】个」 — the number granted depends on the number held. That is a
     * <em>formula</em> over the entity's current state, evaluated here, and no rung could express it.
     *
     * <h2>The cap rung is derived, not declared</h2>
     * {@code hook.cap()} is the number bwiki states (10 / 20). {@link Counters#tierForStatedCap} turns it into
     * the smallest ladder rung that does not truncate it, and {@link Counters#addMarks} applies
     * {@link Counters#capForTier}'s answer — so the ladder in {@code EffectTiers} stays the only table of caps.
     *
     * <p>The duration is <b>not</b> scaled by the multiplier chain: 180 s is bwiki's fixed number for the
     * mark's lifetime, and a per-slot-different tally lifetime would mean the same character's marks expired
     * at different rates depending on which row of the wheel fired.
     */
    private static void counter(EffectHook.Counter hook, HookContext ctx) {
        if (ctx.attacker() == null) {
            return;
        }
        int ticks = AttackBuffRules.ticksFor(hook.seconds());
        int tier = Counters.tierForStatedCap(hook.cap());
        int held = Counters.countOf(ctx.attacker());
        int granted = Math.max(0, hook.base()) + Math.max(0, hook.perMark()) * held;
        int after = Counters.addMarks(ctx.attacker(), granted, tier, ticks);
        Astrum.LOGGER.debug("astrum: counter granted {} ({} + {} x held {}) at cap rung {} (cap {}) for {}t "
                        + "-> {}/{}", granted, hook.base(), hook.perMark(), held, tier,
                Counters.capForTier(tier), ticks, after, Counters.capForTier(tier));
    }

    /**
     * {@code consume_counter}: spends marks of 冰龙之印 and adds the bonus they are worth to this hit.
     *
     * <h2>The order matters, and it is the whole reason this is one arm</h2>
     * <pre>
     *   held   = Counters.countOf(caster)      // read BEFORE the removal -- the bonus scales with what she had
     *   spent  = Counters.consume(caster, n)   // remove first, so the next skill sees the smaller tally
     *   damage = spent &gt; 0 ? damagePerMark * held : 0
     * </pre>
     *
     * <p>Both halves are needed: a skill that found no marks is supposed to deal no bonus (the game's
     * 「移除自身1个」 on an empty tally does nothing), and a skill that found marks is worth every one of them,
     * not just the one it spent. Reading the count after the removal would be one mark short every time, and
     * nothing in the logs would say so — which is why the number is captured here rather than in the data.
     *
     * <p>The damage joins the same {@link DamageBatch} as {@code extra_damage}, so the end-of-tick flush, the
     * {@code EffectQuantities} floor and the {@code astrum:hook_damage} recursion fuse all apply unchanged.
     */
    private static void consumeCounter(EffectHook.ConsumeCounter hook, HookContext ctx) {
        if (ctx.attacker() == null) {
            return;
        }
        int held = Counters.countOf(ctx.attacker());
        int spent = Counters.consume(ctx.attacker(), hook.remove());
        double raw = spent > 0 ? hook.damagePerMark() * held : 0.0;
        float amount = EffectQuantities.scaled(raw, ctx.multiplier());
        Astrum.LOGGER.debug("astrum: consume_counter held {} spent {} -> bonus {} x multiplier {} = {} on {}",
                held, spent, raw, ctx.multiplier(), amount, name(ctx.target()));
        if (amount <= 0.0F) {
            return;
        }
        ResourceLocation type = hook.damageType().orElse(HOOK_DAMAGE);
        ctx.batch().add(type, amount);
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
        return around(ctx, radius, ctx.target());
    }

    /**
     * {@return every living body within {@code radius} of {@code centre}, excluding the centre, the caster and
     * spectators}
     *
     * <p>The centre is a parameter because 矛依未's SP2 is 「以自身为中心」 — a blast around <b>her</b>, not around
     * the body she struck. {@code casterCentred} on {@code EffectHook.Splash} chooses; every other caller passes
     * the target, which is what {@link #around(HookContext, double)} still does.
     */
    private static List<LivingEntity> around(HookContext ctx, double radius, LivingEntity centre) {
        if (!(radius > 0.0) || centre == null) {
            return List.of();
        }
        double radiusSquared = radius * radius;
        return centre.level()
                .getEntitiesOfClass(LivingEntity.class, centre.getBoundingBox().inflate(radius),
                        other -> other != centre && other != ctx.attacker() && other.isAlive()
                                && !other.isSpectator()
                                && other.distanceToSqr(centre) <= radiusSquared);
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
