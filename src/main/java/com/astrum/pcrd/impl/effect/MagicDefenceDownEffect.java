package com.astrum.pcrd.impl.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「魔法防御力をダウン」: the holder takes <b>more magic damage</b> — the magic-side counterpart of
 * {@link DefenceDownEffect}.
 *
 * <h2>Why it is a marker and not an attribute modifier</h2>
 * The physical debuff really is an attribute: {@code ARMOR} is what physical damage is measured against, so a
 * negative modifier there is exactly "less physical defence". The magic side has no such attribute. Minecraft
 * has one {@code ARMOR}, and the mod's magic damage is filed under {@code astrum:magic}, which is not tagged
 * {@code minecraft:bypasses_armor} — so armour does reduce it, but by the <em>physical</em> curve, i.e. the
 * thing {@code defence_down} already lowers. Lowering it here too would make the two debuffs one debuff.
 *
 * <p>So this effect carries <b>no modifier at all</b>: it is a registered type whose amplifier is the tier,
 * and {@link DamageReduction} reads it on the damage path — the one place the magic/physical split already
 * lives. That is the same construction {@code phys_defence} / {@code magic_defence} use on the buff side
 * ({@link AstrumMarkerEffect}), which is what makes the debuff the exact mirror of the buff rather than a
 * third mechanism.
 *
 * <h2>No tick listener</h2>
 * There is nothing to do per tick: the penalty is read when damage lands, and validity is vanilla's clock.
 * Adding {@code applyEffectTick} would be a tick listener in disguise, which this project forbids — the same
 * argument {@link AstrumMarkerEffect} makes.
 */
@ApiStatus.Internal
public final class MagicDefenceDownEffect extends MobEffect {

    /** Particle tint: the violet family of the magic-defence buff, darkened to read as a debuff. */
    public static final int COLOUR = 0x6B3FA0;

    /** Registry path of the effect: {@code astrum:magic_defence_down}. */
    public static final String PATH = "magic_defence_down";

    /** The effect's registry id, for log lines and for the client-side sprite lookup. */
    public static final net.minecraft.resources.ResourceLocation ID =
            com.astrum.pcrd.impl.InternalIds.id(PATH);

    /** The translation key vanilla resolves for the effect's name. */
    public static final String NAME_KEY = "effect." + ID.toLanguageKey();

    /** Builds the effect. {@code HARMFUL}, so vanilla draws it in the HUD's red debuff column. */
    public MagicDefenceDownEffect() {
        super(MobEffectCategory.HARMFUL, COLOUR);
    }
}
