package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import java.util.Objects;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * One landed hit, as the eight effect executors see it.
 *
 * <h2>Why a context object instead of widening the parameter list</h2>
 * Four of the eight effects need the attacker ({@code lifesteal}, {@code gain_charge},
 * {@code area_charge}, and every stack key), two need the landed amount, two need a way to reach other
 * entities, and all of them need the multiplier and the batch. Adding those one at a time to
 * {@code dispatch(hook, multiplier, hit, batch)} would have produced a nine-parameter switch arm by the
 * seventh effect — the kind of signature where a caller passes {@code attacker} and {@code target} in
 * the wrong order and the compiler says nothing. One record, named fields, built once per hit.
 *
 * <h2>The per-slot half</h2>
 * The multiplier and the attributed character are per <b>wheel slot</b>, while the rest is per
 * <b>hit</b>, so {@link #forSlot} derives the slot's context from the hit's. That keeps "which
 * multiplier applies" next to "which character it belongs to", which is the pair Task 9's dedupe
 * already reasons about.
 *
 * <h2>Where growth enters, and the single composition point</h2>
 * {@link #growth} is this slot's character growth — the level factor and the affection <b>tier</b>, from
 * {@code impl.growth.GrowthMultiplier}. The tier is not a multiplier: it selects which skill set runs
 * ({@code AffectionKernel.hooksAt}) and which ultimate is cast, both of which happen <em>before</em> a hook
 * ever reaches this context. So the product {@code diminishing × level} is computed in exactly one place —
 * {@link #forCharacter} delegating to {@link MultiplierChain#effective} — and the only number the pipeline
 * passes around is {@link #multiplier}, now the <b>effective</b> multiplier. There is no second
 * multiplication anywhere downstream: the executors read {@code ctx.multiplier()} and multiply by nothing
 * else.
 *
 * @param attacker    the player whose wheel is being evaluated
 * @param target      the entity that was hit
 * @param source      the damage source that landed
 * @param dealt       health the hit actually removed, overkill clamped (see {@code LandedHit.healthLost})
 * @param multiplier  the <b>effective</b> multiplier for this character's hooks: the party's diminishing
 *                    factor composed with her level (spec §5.2, §6.1); scales quantities only
 * @param growth      this slot's level factor and affection tier; the tier selects the skill set, it does
 *                    not multiply
 * @param characterId the character owning the hook being evaluated (attribution, never a selector)
 * @param batch       the damage accumulator this hit will be flushed from
 * @param otherTargets where an effect that hits a different entity files its damage
 * @param areaCharge  the {@code area_charge} seam (guarded by spec §7.4)
 */
@ApiStatus.Internal
public record HookContext(ServerPlayer attacker, LivingEntity target, DamageSource source, float dealt,
                          double multiplier, GrowthMultiplier growth, ResourceLocation characterId,
                          DamageBatch batch, DamageToTarget otherTargets, AreaChargeSink areaCharge) {

    public HookContext {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(characterId, "characterId");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(otherTargets, "otherTargets");
        Objects.requireNonNull(areaCharge, "areaCharge");
        growth = growth == null ? GrowthMultiplier.NEUTRAL : growth;
    }

    /**
     * {@return the same hit, seen from one wheel slot}
     *
     * <p>The multiplier handed in is the slot's <b>diminishing</b> factor and the growth is the character's,
     * so this context is still "not yet composed".
     *
     * @param slotMultiplier that slot's diminishing multiplier
     * @param slotGrowth     that character's level factor and affection tier
     * @param slotCharacter  the character equipped in that slot
     */
    public HookContext forSlot(double slotMultiplier, GrowthMultiplier slotGrowth,
            ResourceLocation slotCharacter) {
        return new HookContext(attacker, target, source, dealt, slotMultiplier, slotGrowth, slotCharacter,
                batch, otherTargets, areaCharge);
    }

    /**
     * {@return the same hit as one of this slot's characters sees it, with the composed multiplier}
     *
     * <p><b>The single growth composition point of the whole mod.</b> Its only caller is
     * {@code HookEvaluator.evaluate}'s effect runner.
     *
     * <p>There is no hook index any more: the affection tier used to scale a hook per index
     * ({@code tier2HookModifiers}) and now replaces the whole skill instead, so a hook that reaches this
     * context is already the version its tier runs. See {@code MultiplierChain} for why the parameter was
     * removed rather than left unread.
     */
    public HookContext forCharacter() {
        return new HookContext(attacker, target, source, dealt,
                MultiplierChain.effective(multiplier, growth), growth, characterId, batch,
                otherTargets, areaCharge);
    }

    /**
     * {@return the same hit, as the <b>one hook</b> about to be executed sees it}
     *
     * <h2>What the hook adds, and what it does not</h2>
     * The hook contributes exactly one thing here: its own crit multiplier
     * ({@link EffectHook#critMultiplier()}), which the batch tags the amounts this hook authors with. The
     * timing rule is that "this skill's crits are 4×" must travel <b>with the amount</b>, because the
     * amounts of several skills are merged and flushed later, at the end of the tick, by
     * {@code CombatEvents.flush}; a per-context scalar would be the last hook's value by then. The
     * executor keeps calling {@code ctx.batch().add(type, amount)} with no extra parameter — see
     * {@code DamageBatch#tagged}.
     *
     * <p>Everything else is unchanged: the multiplier is already composed by {@link #forCharacter}, and a
     * hook with no per-skill crit value ({@code OptionalDouble.empty()}) gets {@code this} context back, so
     * the normal case allocates nothing.
     *
     * <p><b>Damage against <em>other</em> bodies carries the tag too.</b> The radius of {@code splash} and
     * {@code detonate} files its amounts through {@link DamageToTarget}, so the same per-hook value is attached
     * to that sink as a {@link DamageToTarget#tagged} view — otherwise a 4× skill's splash would crit at the
     * caster's global multiplier while its primary hit crit at 4×, which is the defect {@code agent-30}
     * reported. The executor still calls {@code ctx.otherTargets().add(other, type, damage)} with three
     * arguments and knows nothing about the channel.
     *
     * @param hook the hook being executed; {@code null} answers {@code this} — "no hook" cannot declare a
     *             per-skill multiplier
     */
    public HookContext forHook(EffectHook hook) {
        OptionalDouble crit = hook == null ? OptionalDouble.empty() : hook.critMultiplier();
        DamageBatch tagged = batch.tagged(crit);
        DamageToTarget taggedTargets = otherTargets.tagged(crit);
        if (tagged == batch && taggedTargets == otherTargets) {
            return this;
        }
        return new HookContext(attacker, target, source, dealt, multiplier, growth, characterId, tagged,
                taggedTargets, areaCharge);
    }
}
