package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.GrantDuration;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.combat.EffectQuantities;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>What one fired skill leaves in vanilla's effect bar: one entry, lasting as long as its last state — or
 * nothing at all.</b>
 *
 * <h2>The two rules this class is, and the human's sentences behind them</h2>
 * <ol>
 *   <li>「在药水栏上显示的图标也只有一个技能图标。」 — <b>one</b> entry per firing, not one per effect. A skill that
 *       applies haste <em>and</em> a party attack buff is still one skill, and the bar is a list of skills, not
 *       of effects.</li>
 *   <li>「一个技能里的多个效果，等到全部效果结束了，这个图标再删除。」 — the entry lasts until the
 *       <b>last</b> of those effects has ended, i.e. the maximum of their durations. Not the sum, not the first
 *       one's.</li>
 *   <li>「瞬发技能就不要显示持续时间了，会在药水栏出现持续时间的，是技能提供的状态，不是技能。」 — the bar lists <b>states
 *       the player is under</b>. A skill whose every effect is instantaneous (damage, a charge grant, a splash,
 *       a heal, a mark spend) leaves the player in no state at all, so it produces <b>no entry</b> — not a
 *       short one. The zero case is "nothing", never "a minimum".</li>
 * </ol>
 *
 * <h2>Which effects count as a state, and why that is not decided here</h2>
 * {@link EffectType#persistsOnCaster()} is the classification, and it lives on the enum because it is a
 * property of what an effect <em>is</em> — the same footing as {@link EffectType#reachesParty()}. This class
 * is the <b>arithmetic</b> over that classification: ask each fired hook whether its type persists, convert
 * the ones that do into ticks, keep the largest.
 *
 * <h2>The durations are delegated, never restated</h2>
 * Every tick count below comes from the class that already owns that effect's clock — {@link AreaBuffs#ticksFor}
 * for the six timed area buffs, {@link Shields#ticksFor} for a barrier, {@link Spirits#ticksFor} for a summon,
 * {@link AttackBuffRules#ticksFor} for the two that declare bare seconds, {@link GrantDuration#ticks} for the
 * flat grants, and {@link EffectQuantities#durationTicks} for the one duration the multiplier scales. A default
 * written here as well would be a second copy of {@code DEFAULT_SECONDS}, and the entry would outlive or
 * undershoot the buff it describes the first time either was tuned.
 *
 * <h2>Everything here is a pure function of a hook list</h2>
 * No registry, no entity, no client, no state: {@code SkillEntriesTest} covers every branch without a game,
 * which is what lets the "all-instant skills produce no entry" rule be asserted as a fact rather than observed
 * by watching a HUD.
 */
@ApiStatus.Internal
public final class SkillEntries {

    /**
     * One entry to apply: <b>which</b> skill it belongs to, and how many ticks it lasts.
     *
     * @param skill the {@code triggerParams.skill} tag of the effect that ends last — the skill whose name and
     *              picture the entry wears; empty string for a hook the pack left untagged
     * @param ticks the longest of the fired effects' durations, always {@code > 0}
     */
    public record Entry(String skill, int ticks) {
        public Entry {
            skill = skill == null ? "" : skill;
            if (ticks <= 0) {
                throw new IllegalArgumentException("a skill entry must last at least one tick, got " + ticks);
            }
        }
    }

    private SkillEntries() {
    }

    /**
     * {@return the entry {@code fired} leaves behind, or empty when the skill was purely instantaneous}
     *
     * <p>The production form: a slot's composed multiplier is passed through, because it is what the
     * {@code ally_effect} arm scales its duration by. Without it the entry would last the authored number of
     * ticks while the vanilla effect it reports lasted the diminished one.
     *
     * @param fired      the hooks that actually ran for this skill, in firing order
     * @param multiplier this slot's composed multiplier, for the one duration it scales
     */
    public static Optional<Entry> of(@Nullable List<EffectHook> fired, double multiplier) {
        if (fired == null || fired.isEmpty()) {
            return Optional.empty();
        }
        String winner = "";
        int longest = 0;
        for (EffectHook hook : fired) {
            if (hook == null || !hook.type().persistsOnCaster()) {
                continue;
            }
            int ticks = ticksOf(hook, multiplier);
            // Strictly greater: on a tie the FIRST effect that reaches that duration keeps the entry, so the
            // skill's own firing order decides, not the order a map happened to iterate in.
            if (ticks > longest) {
                longest = ticks;
                winner = hook.triggerParams().skillTag().orElse("");
            }
        }
        return longest > 0 ? Optional.of(new Entry(winner, longest)) : Optional.empty();
    }

    /** {@return {@link #of(List, double)} at an undiminished multiplier — the seam a test drives} */
    public static Optional<Entry> of(@Nullable List<EffectHook> fired) {
        return of(fired, 1.0);
    }

    /**
     * {@return how many ticks this hook leaves the player in a state, or {@code 0} for an effect that leaves
     * none}
     *
     * <p>{@code 0} is the honest answer for three different situations and they need no distinguishing here:
     * the effect is instantaneous, it is applied to a body the skill landed on rather than to the caster, or it
     * is the <b>permanent</b> form of a flat grant (an EX passive, which is owned by the party slot and has no
     * clock). All three mean "no entry", which is exactly the rule.
     *
     * @param hook       the effect to measure; {@code null} answers {@code 0}
     * @param multiplier this slot's composed multiplier, for {@code ally_effect}
     */
    public static int ticksOf(@Nullable EffectHook hook, double multiplier) {
        if (hook == null || !hook.type().persistsOnCaster()) {
            return 0;
        }
        return switch (hook) {
            case EffectHook.AttackBuff h -> AttackBuffRules.ticksFor(h.seconds());
            // The crit-damage buff is the same shape: a real effect instance on the caster's side whose clock is
            // the hook's own seconds, so the skill entry lasts exactly as long as the buff it reports.
            case EffectHook.CritDamageUp h -> AttackBuffRules.ticksFor(h.seconds());
            // The one duration the multiplier scales — delegated so the number matches what was applied.
            case EffectHook.AllyEffect h -> EffectQuantities.durationTicks(h.duration(), multiplier);
            case EffectHook.FlatAttackUp h -> flatTicks(h.grantDuration());
            case EffectHook.FlatMagicAttackUp h -> flatTicks(h.grantDuration());
            case EffectHook.Counter h -> AttackBuffRules.ticksFor(h.seconds());
            case EffectHook.Shield h -> Shields.ticksFor(h.params());
            // The two typed channels carry the same TieredParams clock as the shared barrier: the row lasts as
            // long as the barrier it reports, whichever half it absorbs.
            case EffectHook.ShieldPhys h -> Shields.ticksFor(h.params());
            case EffectHook.ShieldMagic h -> Shields.ticksFor(h.params());
            // The timed crit-chance buff is the crit-damage buff one stat over: a real effect instance on the
            // caster's side whose clock is the hook's own seconds.
            case EffectHook.CritChanceUp h -> AttackBuffRules.ticksFor(h.seconds());
            // 「每秒恢复自身…技能值」 leaves a state with a clock, exactly like the other over-time families.
            case EffectHook.CounterChargeRegen h -> AttackBuffRules.ticksFor(h.seconds());
            case EffectHook.SummonSpirit h -> Spirits.ticksFor(h.params());
            // The six timed buffs whose clock is TieredParams: AreaBuffs owns that reading for all of them,
            // including its "absent means the documented default" and its zero-means-nothing rule.
            case EffectHook.BuffAttackArea h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.BuffMagicAttackArea h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.BuffPhysDefenceArea h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.BuffMagicDefenceArea h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.Haste h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.ChargeRateUp h -> AreaBuffs.ticksFor(h.params());
            // The four over-time types leave a state with the same clock every other timed buff has, so the
            // skill entry lasts as long as the regeneration / charge-regen it reports. Nothing special here:
            // they are timed states, which is precisely what the bar lists.
            case EffectHook.RegenSelf h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.RegenArea h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.ChargeRegenSelf h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.ChargeRegenArea h -> AreaBuffs.ticksFor(h.params());
            // The two transfers are instantaneous grants to a single body: the charge is credited and the health
            // restored in the tick the skill fires, so neither leaves a state on the caster — the same answer
            // GAIN_CHARGE and HEAL get, and for the same reason.
            case EffectHook.TransferCharge h -> 0;
            case EffectHook.TransferHeal h -> 0;
            // task 56: the taunt is a timed state the <b>caster</b> carries, so the skill entry lasts as long
            // as she is calling the mobs. A pull and a knockback are writes that land on the victim and finish
            // in the tick they happen, so neither leaves anything on her.
            case EffectHook.Taunt h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.Pull h -> 0;
            case EffectHook.Knockback h -> 0;
            // task 57: both immunity channels leave a timed state on the caster, so the row lasts as long as
            // the immunity does — the same answer the other timed buffs give.
            case EffectHook.PhysImmunity h -> AreaBuffs.ticksFor(h.params());
            case EffectHook.ControlImmunity h -> AreaBuffs.ticksFor(h.params());
            // Everything else is instantaneous or lands on the victim. Listed rather than defaulted so adding a
            // hook to the sealed interface fails compilation here until somebody decides which it is — the same
            // reason SkillDetailModel's number panel is a switch.
            case EffectHook.ExtraDamage h -> 0;
            case EffectHook.PercentDamage h -> 0;
            case EffectHook.Lifesteal h -> 0;
            case EffectHook.GainCharge h -> 0;
            case EffectHook.AreaCharge h -> 0;
            case EffectHook.Splash h -> 0;
            case EffectHook.Stack h -> 0;
            case EffectHook.Detonate h -> 0;
            case EffectHook.Heal h -> 0;
            case EffectHook.HealArea h -> 0;
            case EffectHook.HealSelf h -> 0;
            case EffectHook.ApplyEffect h -> 0;
            case EffectHook.DefenceDown h -> 0;
            case EffectHook.MagicDefenceDown h -> 0;
            case EffectHook.Panic h -> 0;
            case EffectHook.RemoveShield h -> 0;
            case EffectHook.ConsumeCounter h -> 0;
            // The two victim-side crit debuffs put their state on the body the skill landed on, not on the
            // caster — a mob has no bar, and the caster is not under her own debuff. `0` here is the same
            // answer PANIC, DEFENCE_DOWN and MAGIC_DEFENCE_DOWN get, and for the same reason.
            case EffectHook.CritChanceDown h -> 0;
            case EffectHook.CritDamageDown h -> 0;
        };
    }

    /** {@return a flat grant's ticks, or {@code 0} for the permanent form} */
    private static int flatTicks(Optional<GrantDuration> duration) {
        // GrantDuration.ticks() answers 0 for the permanent form by design — see its javadoc — which is the
        // "no clock" answer that makes an EX passive produce no entry.
        return duration == null ? 0 : duration.map(GrantDuration::ticks).orElse(0);
    }

    /**
     * {@return how many ticks a list of <b>ultimate</b> effects leaves on the caster, or {@code 0} for none}
     *
     * <h2>Why the ultimate is measured through the same switch</h2>
     * An ultimate declares {@code UbEffect}s — a scope, an {@link EffectType} and the same {@link TieredParams}
     * a hook carries — so the question "how long does this cast leave me in a state" is the same question, and
     * the answer must be the same arithmetic. Only the {@code ALLY}-scoped ones count: a {@code VICTIM} clause
     * puts its state on the bodies the cast hit, which is a different effect list entirely.
     *
     * @param types   the types of the cast's party-scoped effects, in declaration order
     * @param params  their parameters, index-aligned with {@code types}
     */
    public static int ticksOfUltimate(List<EffectType> types, List<TieredParams> params) {
        if (types == null || params == null) {
            return 0;
        }
        int longest = 0;
        for (int i = 0; i < Math.min(types.size(), params.size()); i++) {
            EffectType type = types.get(i);
            if (type == null || !type.persistsOnCaster()) {
                continue;
            }
            longest = Math.max(longest, ticksForTiered(type, params.get(i)));
        }
        return longest;
    }

    /**
     * {@return the duration a party-scoped tiered type leaves, or {@code 0} when it leaves none}
     *
     * <p>The tiered half of {@link #ticksOf}, for the ultimate path where there is no {@link EffectHook} record
     * to switch on. Four types do not go through {@link AreaBuffs}: a barrier and a summon each own their own
     * default, and the two flat grants belong to the party slot rather than to a clock.
     */
    public static int ticksForTiered(EffectType type, @Nullable TieredParams params) {
        if (type == null) {
            return 0;
        }
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        return switch (type) {
            case SHIELD, SHIELD_PHYS, SHIELD_MAGIC -> Shields.ticksFor(tiered);
            case SUMMON_SPIRIT -> Spirits.ticksFor(tiered);
            // The taunt is a timed state on the <b>caster</b>, so it is the skill entry's clock like any party
            // buff: its duration is the ordinary `seconds` of a tiered effect.
            case TAUNT -> AreaBuffs.ticksFor(tiered);
            case ATTACK_BUFF, ALLY_EFFECT, COUNTER, FLAT_ATTACK_UP, FLAT_MAGIC_ATTACK_UP,
                 TRANSFER_CHARGE, TRANSFER_HEAL -> 0;
            // The three non-tiered party effects whose magnitude is a free number: their clock is the
            // ultimate's own `seconds`, exactly as it is on the hook path. CRIT_DAMAGE_UP was missing from this
            // list and answered 0, which was wrong for the same reason it would have been wrong for the two
            // beside it — the buff lasts 18 s whatever the row says.
            case CRIT_DAMAGE_UP, CRIT_CHANCE_UP, COUNTER_CHARGE_REGEN -> AreaBuffs.ticksFor(tiered);
            case BUFF_ATTACK_AREA, BUFF_MAGIC_ATTACK_AREA, BUFF_PHYS_DEFENCE_AREA, BUFF_MAGIC_DEFENCE_AREA,
                 HASTE, CHARGE_RATE_UP, REGEN_SELF, REGEN_AREA,
                 CHARGE_REGEN_SELF, CHARGE_REGEN_AREA -> AreaBuffs.ticksFor(tiered);
            default -> 0;
        };
    }
}
