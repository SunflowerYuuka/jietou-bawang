package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「物理防御力アップ」: <b>flat armour points</b>, plus the percentage reduction {@link DefenceRules} already
 * applies on the damage path.
 *
 * <h2>Two layers, because the human asked for both</h2>
 * 「物理防御上升做成护甲值的提升」, and when asked which reading he wanted, 「护甲值 + 保留百分比」. So the
 * buff does two things at once and this class is the half vanilla can see:
 *
 * <ol>
 *   <li><b>{@code Attributes.ARMOR}</b>, via a real attribute modifier — the number on the inventory screen
 *       moves, the armour formula treats it exactly like a chestplate, and it scales with what the player
 *       is already wearing ({@code ADD_MULTIPLIED_TOTAL}, because 「百分比增加护甲值」). This is the layer
 *       that makes the buff feel like armour.</li>
 *   <li><b>the damage-reduction percentage</b>, unchanged: {@code DefenceRules.STEP} a tier, read by
 *       {@code DamageReduction} on {@code LivingDamageEvent.Pre}. Kept because armour points alone still
 *       meet vanilla's diminishing curve at the top end, and because a player in no armour at all would
 *       otherwise get nothing from a percentage-of-armour buff. The two layers cover each other's shape.</li>
 * </ol>
 *
 * <p>This is a deliberate <b>departure from {@link AstrumMarkerEffect}</b>, whose javadoc says the defence
 * buffs cannot be an attribute modifier "because there is no damage-taken attribute". That is still true —
 * there is no percentage attribute, and the percentage still lives in {@code DamageReduction}. What changed
 * is that the buff now <em>also</em> grants the attribute that does exist. So it stops being a pure marker
 * and becomes a marker with a modifier, which is why it is its own class instead of another instance of
 * {@code AstrumMarkerEffect}.
 *
 * <h2>The magic half stays a pure marker</h2>
 * {@code astrum:magic_defence} deliberately does <b>not</b> get an armour modifier: vanilla's armour formula
 * cannot tell physical from magical, so armour points would reduce a fireball as readily as a sword, and the
 * split this mod's two buffs exist to make would collapse. The percentage is the only way to express
 * "less magic damage specifically" — which is also how the mainstream magic mods do it (Iron's Spells'
 * {@code Spell Resistance}, the standalone {@code Magic Resistance} attribute).
 */
@ApiStatus.Internal
public final class PhysDefenceEffect extends MobEffect {

    /** Particle tint: the same steel blue the marker used. */
    public static final int COLOUR = 0x6FA8DC;

    /**
     * This effect's own modifier id.
     *
     * <p><b>Never</b> {@code astrum:magic_defence}, and never a shared "defence" id: vanilla's
     * {@code addAttributeModifiers} removes a modifier <em>by id</em> before adding its replacement, so a
     * shared id would let one buff cancel the other — the silent loss {@code AttackAreaEffect#MODIFIER_ID}
     * already warns about for the attack pair.
     */
    public static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", "phys_defence");

    /**
     * The flat form's modifier id — <b>different on purpose</b>.
     *
     * <p>Vanilla removes a modifier by id before adding its replacement, so a shared id would let the EX
     * passive and a timed buff cancel each other depending on which was applied last.
     */
    public static final ResourceLocation FLAT_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", "phys_defence_flat");

    /**
     * Builds the <b>percentage</b> form — the one a timed buff uses.
     *
     * <p>{@code ADD_MULTIPLIED_TOTAL}: 「百分比增加护甲值」 — a fraction of the armour the player already has.
     * {@code ADD_VALUE} would hand a naked player and a netherite player the same points.
     */
    public PhysDefenceEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Attributes.ARMOR, MODIFIER_ID,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                DefenceRules::armourFractionForAmplifier);
    }

    /**
     * Builds the <b>flat</b> form — the one an EX or SP clause uses.
     *
     * <p>「只有 ex 或者 sp 技能里面加物理防御的，才是增加固定的护甲值」: a permanent passive is a flat grant,
     * because a percentage of nothing is nothing — an EX that says 「物理防御力 2+2L」 has to mean armour
     * points on a naked player too, which is the whole reason it is a passive rather than a buff.
     *
     * <p>The two forms are <b>two registered types</b>, not one type with a flag. Vanilla keys a player's
     * effects by type, so one type would merge the passive and a timed buff into a single HUD row and the
     * second grant would silently overwrite the first's amplifier — the class of defect this project keeps
     * paying for.
     *
     * @param ignored kept for signature symmetry with the percentage form; the operation is what differs
     */
    private PhysDefenceEffect(boolean flat) {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Attributes.ARMOR, FLAT_MODIFIER_ID,
                AttributeModifier.Operation.ADD_VALUE,
                DefenceRules::armourPointsForAmplifier);
    }

    /** {@return the flat form's effect — a separate type, see the constructor's javadoc} */
    public static PhysDefenceEffect flat() {
        return new PhysDefenceEffect(true);
    }
}