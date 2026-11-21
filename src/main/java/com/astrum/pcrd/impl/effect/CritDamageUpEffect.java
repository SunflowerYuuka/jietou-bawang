package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「提升我方全体 28% 暴击伤害，持续 18 秒」: a real, self-expiring <b>crit-damage</b> buff.
 *
 * <h2>Why a vanilla {@link MobEffect}, in one sentence</h2>
 * Vanilla calls {@link MobEffect#addAttributeModifiers} when an instance is added and
 * {@link MobEffect#removeAttributeModifiers} when it expires or is removed, both keyed by the modifier id declared
 * here — so the buff ends by itself and this mod contains no expiry code, nothing to sweep, and no state to leak.
 * There is no tick listener anywhere in this feature. {@code AttackBuffEffect} is the same shape for the attack
 * stat, and both are the alternative the project chose over remembering an expiry.
 *
 * <h2>Why {@code ADD_MULTIPLIED_TOTAL} on {@code astrum:crit_damage}</h2>
 * 「28% 暴击伤害」 is a fraction of the damage a crit already deals, so the modifier must be a fraction of the
 * attribute's computed total: {@code ADD_MULTIPLIED_TOTAL} with {@code 0.28} turns the shipped {@code 1.5} into
 * {@code 1.92}. {@code ADD_VALUE} would add {@code 0.28} <em>absolute</em> multiplier points (a different, larger
 * buff at this base) and {@code ADD_MULTIPLIED_BASE} would ignore any other crit-damage source the player carries.
 *
 * <h2>The amount, and why it is a curve over the amplifier</h2>
 * A {@code MobEffect} declares its modifier <b>once, on the type</b>, not per instance, and the only per-instance
 * knob vanilla offers is the byte-sized amplifier. So the registered modifier is NeoForge's
 * {@code Int2DoubleFunction} overload, answering {@code CritDamageUps.fractionFor(amplifier)} — {@code amplifier ×
 * 0.01}, i.e. a linear step of one percent rather than the {@code amount × (amplifier + 1)} a plain template would
 * produce. {@code 28} therefore means exactly {@code +28%}, which is the number the wiki states.
 *
 * <h2>Why the modifier id is a constant</h2>
 * Vanilla's {@code addAttributeModifiers} does {@code removeModifier(id)} and then adds a permanent modifier under
 * that same id, so a <b>fixed</b> id is what makes re-application <em>replace</em> rather than <b>compound</b>. A
 * per-cast id would leave one modifier behind per cast, and the party's crit damage would grow without bound —
 * {@link AttackBuffRules}' stacking section states the same rule for the attack buff.
 *
 * <p>Distinct from every other effect type by its id, so 千歌's buff and any future crit-damage source are separate
 * HUD/attribute entries rather than one cancelling the other. It is hidden from vanilla's effect lists like every
 * other mechanic ({@code HiddenEffectClient}), because 「药水栏不是把每个通用效果都显示出来」.
 */
@ApiStatus.Internal
public final class CritDamageUpEffect extends MobEffect {

    /**
     * Particle tint: a deep crimson, the colour the game's own crit numbers use — this buff is about crits, and the
     * hue is the only place a player could ever recognise it at a glance.
     */
    public static final int COLOUR = 0xC0392B;

    /**
     * The one attribute modifier this effect declares.
     *
     * <p>A fixed id, for the replace-not-accumulate reason in the class javadoc; it is also what
     * {@code removeAttributeModifiers} looks up, so changing it would orphan every live instance.
     */
    public static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath("astrum",
            "crit_damage_up");

    /**
     * Builds the effect: one {@code ADD_MULTIPLIED_TOTAL} modifier on {@code astrum:crit_damage}, whose fraction is
     * {@code amplifier / 100}.
     *
     * <p>{@code BENEFICIAL} so vanilla draws it in the blue buff column rather than among the debuffs — the same
     * choice {@code AttackBuffEffect} makes, and it matters even though this family is filtered out of the bar: the
     * category also decides how {@code /effect} and any client-side display group it.
     */
    public CritDamageUpEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Crit.CRIT_DAMAGE, MODIFIER_ID, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                CritDamageUps::fractionFor);
    }
}