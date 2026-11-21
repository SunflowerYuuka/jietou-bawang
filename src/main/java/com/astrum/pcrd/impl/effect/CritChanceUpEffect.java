package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「提升范围内所有友方 N 物理暴击」: a real, self-expiring <b>crit-chance</b> buff — the twin of
 * {@link CritDamageUpEffect}, one stat over.
 *
 * <h2>Why a vanilla {@link MobEffect}, in one sentence</h2>
 * Vanilla calls {@link MobEffect#addAttributeModifiers} when an instance is added and
 * {@link MobEffect#removeAttributeModifiers} when it expires or is removed, both keyed by the fixed modifier id
 * declared here — so the buff ends by itself and this mod contains no expiry code, nothing to sweep and no
 * state to leak. No tick listener is involved anywhere in this feature.
 *
 * <h2>Why {@code ADD_VALUE} on {@code astrum:crit_chance}</h2>
 * A crit chance is a <b>fraction of hits</b>, so 「+5%」 is five percentage points added to whatever the player
 * already has: {@code ADD_VALUE} with {@code 0.05}. {@code ADD_MULTIPLIED_TOTAL} would scale her own chance
 * instead (a +5% buff on 22% would be 23.1%, not 27%), which is a different and smaller buff than the source
 * states. And it <b>adds to</b> the per-character grant of {@code CritChances} rather than replacing it,
 * because {@code Crit.chanceOf} reads the attribute's <em>value</em>, i.e. base plus every modifier — adding is
 * {@code Crit}'s existing rule for every source of "you crit more often", and a buff that replaced her own
 * chance would be a second rule for one stat.
 *
 * <h2>The amount, and why it is a curve over the amplifier</h2>
 * A {@code MobEffect} declares its modifier once, on the type, and the only per-instance knob is the amplifier.
 * So the modifier is NeoForge's {@code Int2DoubleFunction} overload answering
 * {@link CritChanceUps#fractionFor}: <b>one amplifier step is 0.1%</b> ({@code 0.001}), which makes 千歌's 2.5%,
 * 静流's 5% and 铃莓's 10% exact integers (25 / 50 / 100) instead of quantising the 2.5 to 2 or 3.
 *
 * <h2>Why the modifier id is a constant</h2>
 * Vanilla's {@code addAttributeModifiers} removes the modifier under that id before adding it, so a
 * <b>fixed</b> id is what makes re-application <em>refresh</em> rather than <b>compound</b>: ten casts leave
 * +10%, not +100%. A per-cast id would leave one modifier behind per cast and the party's crit chance would
 * grow without bound.
 *
 * <p>Hidden from vanilla's effect lists like every other mechanic ({@code HiddenEffectClient}), because
 * 「药水栏不是把每个通用效果都显示出来」.
 */
@ApiStatus.Internal
public final class CritChanceUpEffect extends MobEffect {

    /** Particle tint: a pale gold, the colour of a favourable roll — distinct from the crit-damage buff's crimson. */
    public static final int COLOUR = 0xE9C46A;

    /**
     * The one attribute modifier this effect declares.
     *
     * <p>Fixed, for the refresh-not-compound reason in the class javadoc; it is also what
     * {@code removeAttributeModifiers} looks up, so changing it would orphan every live instance.
     */
    public static final ResourceLocation MODIFIER_ID = ResourceLocation.fromNamespaceAndPath("astrum",
            CritChanceUps.PATH);

    /**
     * Builds the effect: one {@code ADD_VALUE} modifier on {@code astrum:crit_chance}, whose amount is
     * {@code amplifier / 1000} — one tenth of a percentage point a step.
     *
     * <p>{@code BENEFICIAL} so vanilla groups it with the buffs, the same choice {@link CritDamageUpEffect} and
     * {@link AttackBuffEffect} make even though this family is filtered out of the bar.
     */
    public CritChanceUpEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Crit.CRIT_CHANCE, MODIFIER_ID, AttributeModifier.Operation.ADD_VALUE,
                CritChanceUps::fractionFor);
    }
}