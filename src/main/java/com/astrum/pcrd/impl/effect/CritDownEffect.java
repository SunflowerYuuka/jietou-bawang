package com.astrum.pcrd.impl.effect;

import java.util.function.IntToDoubleFunction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * One <b>negative</b> {@code astrum:crit_*} modifier carried by a real, self-expiring {@code MobEffect} — the two
 * victim-side crit debuffs of {@code EffectType.CRIT_CHANCE_DOWN} / {@code CRIT_DAMAGE_DOWN}.
 *
 * <h2>Why one class and two registrations</h2>
 * The two debuffs differ in exactly four arguments: which attribute, which operation, which amplifier curve and
 * which colour. Everything else — the "vanilla adds the modifier when the instance arrives and removes it when the
 * instance dies" contract, the fixed modifier id, the hidden-from-the-bar family — is identical. A second class would
 * be a copy of this file with two words changed, which is the duplication this project forbids.
 *
 * <h2>Why the removal on expiry is <em>real</em></h2>
 * This is the whole reason the clause is carried by an effect instead of by a float somewhere:
 * {@link MobEffect#addAttributeModifiers} is called by vanilla when an instance is added and
 * {@link MobEffect#removeAttributeModifiers} when it expires, is removed by {@code /effect clear}, or dies with the
 * entity. Both are keyed by the fixed id below, so the modifier <b>cannot outlive its effect</b>, this mod contains no
 * expiry code, and there is no tick listener anywhere in the feature. A hand-written "remember the expiry" float
 * would be precisely the thing that leaks when a player dies mid-debuff.
 *
 * <h2>Why a fixed modifier id makes a recast refresh rather than compound</h2>
 * Vanilla's add path removes the modifier under that id before adding it, so ten casts leave one −18%, not −180%.
 * That is the same guarantee {@code CritChanceUpEffect} and {@code CritDamageUpEffect} make on the caster's side, and
 * it is why the id is a constant rather than something unique per application.
 *
 * <h2>Why the curve is over the amplifier</h2>
 * A {@code MobEffect} declares its modifier <b>once, on the type</b>; the only per-instance knob is the byte-sized
 * amplifier. So the amount is NeoForge's {@code Int2DoubleFunction} overload answering the negative of
 * {@code CritDowns}' own per-step curve: −0.1 percentage point a step for the chance, −1% a step for the damage. The
 * negation lives in the two lambdas at the registration site ({@code PartyEffects}) rather than in the curve, so the
 * curve's positive "how much does a step take away" reading stays readable and one number is stated once.
 *
 * <p>{@code HARMFUL} so vanilla groups it with the debuffs, and hidden from vanilla's effect lists like every other
 * mechanic ({@code HiddenEffectClient}), because 「药水栏不是把每个通用效果都显示出来」.
 */
@ApiStatus.Internal
public final class CritDownEffect extends MobEffect {

    /** Particle tint of the crit-chance debuff: a dull violet, the colour of a roll that fails. */
    public static final int CHANCE_COLOUR = 0x6A4C93;

    /** Particle tint of the crit-damage debuff: a bruised brown-red, distinct from the buff's crimson. */
    public static final int DAMAGE_COLOUR = 0x7B2D26;

    /**
     * Builds one debuff.
     *
     * @param modifierId the <b>fixed</b> modifier id, which is what makes a recast refresh and an expiry remove
     * @param attribute  the attribute to lower
     * @param operation  how the (negative) amount composes — {@code ADD_VALUE} for a fraction of hits,
     *                   {@code ADD_MULTIPLIED_TOTAL} for a fraction of the crit multiplier
     * @param curve      amplifier to the <b>positive</b> magnitude the step takes away; this class negates it
     * @param colour     the particle tint
     */
    public CritDownEffect(ResourceLocation modifierId, Holder<Attribute> attribute,
            AttributeModifier.Operation operation, IntToDoubleFunction curve, int colour) {
        super(MobEffectCategory.HARMFUL, colour);
        // The 4-argument overload is vanilla's, and it wants fastutil's Int2DoubleFunction: the adapter is one line
        // and keeps the curve's own signature (a plain int-to-double step) free of a library type.
        addAttributeModifier(attribute, modifierId, operation,
                (it.unimi.dsi.fastutil.ints.Int2DoubleFunction) amplitude ->
                        -Math.max(0.0, curve.applyAsDouble(amplitude)));
    }
}
