package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import java.util.Objects;
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
 * {@link #growth} is the <em>uncomposed</em> half of the growth numbers — the character's level factor
 * and its affection definition/tier, from {@code impl.growth.GrowthMultiplier}. It is carried rather
 * than multiplied here because the affection hook modifier is per <b>hook index</b>, and the hook index
 * only exists inside {@code HookPlan.fire}'s loop. So the product {@code diminishing × level ×
 * affectionHook} is computed in exactly one place — {@link #forHook} delegating to
 * {@link MultiplierChain#effective} — and the only thing the pipeline passes around is
 * {@link #multiplier}, now the <b>effective</b> multiplier. There is no second multiplication anywhere
 * downstream: the executors read {@code ctx.multiplier()} and multiply by nothing else.
 *
 * @param attacker    the player whose wheel is being evaluated
 * @param target      the entity that was hit
 * @param source      the damage source that landed
 * @param dealt       health the hit actually removed, overkill clamped (see {@code LandedHit.healthLost})
 * @param multiplier  the <b>effective</b> multiplier for the hook being run: the party's diminishing
 *                    factor composed with this character's level and affection hook modifier (spec
 *                    §5.2, §6.1, §6.2); scales quantities only
 * @param growth      this slot's level/affection factors, uncomposed (the affection part is per hook)
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
     * <p>The multiplier handed in is the slot's <b>diminishing</b> factor and the growth is the
     * character's, so this context is still "not yet composed": a slot with no hooks would never need
     * the product, and every hook that does run needs its own index.
     *
     * @param slotMultiplier that slot's diminishing multiplier
     * @param slotGrowth     that character's level and affection, uncomposed
     * @param slotCharacter  the character equipped in that slot
     */
    public HookContext forSlot(double slotMultiplier, GrowthMultiplier slotGrowth,
            ResourceLocation slotCharacter) {
        return new HookContext(attacker, target, source, dealt, slotMultiplier, slotGrowth, slotCharacter,
                batch, otherTargets, areaCharge);
    }

    /**
     * {@return the same hit as one hook of this slot sees it, with the composed multiplier}
     *
     * <p><b>The single growth composition point of the whole mod.</b> Its only caller is
     * {@code HookEvaluator.evaluate}'s effect runner, which is the one place a hook's index exists.
     *
     * @param hookIndex the hook's position in the character's {@code effects[]}, i.e. the index a
     *                  {@code HookModifier} names
     */
    public HookContext forHook(int hookIndex) {
        return new HookContext(attacker, target, source, dealt,
                MultiplierChain.effective(multiplier, growth, hookIndex), growth, characterId, batch,
                otherTargets, areaCharge);
    }
}
