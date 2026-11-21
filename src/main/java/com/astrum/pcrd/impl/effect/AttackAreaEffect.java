package com.astrum.pcrd.impl.effect;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;

/**
 * 「味方全体の物理攻击力アップ」: +20% <b>physical</b> attack a tier, to 100% (double damage).
 *
 * <h2>Why a modifier on {@code ATTACK_DAMAGE} and not {@code minecraft:strength}</h2>
 * Vanilla's {@code strength} adds a <b>flat</b> {@code +3} damage a level ({@code ADD_VALUE}). That is a
 * different buff on a wooden sword than on a netherite one, and it cannot say "20%". What a generic attack
 * buff must mean is a fraction of <em>this</em> entity's own attack, which is
 * {@code ADD_MULTIPLIED_TOTAL} on {@code Attributes.ATTACK_DAMAGE} — the operation whose value is a
 * fraction of the fully-computed total, i.e. the thing the wiki's 「攻击力アップ」 describes. Tier 5 doubles
 * the damage, which is intended.
 *
 * <h2>Why this is physical-only, and where the magic half lives</h2>
 * Once {@link MagicAttack} split the game's single attack stat into 攻击力 and 魔法攻击力, "「攻击力アッ
 * プ」" became two questions, and the answer is <b>one effect type each</b> rather than one type that raises
 * both:
 *
 * <ul>
 *   <li><b>This effect keeps the physical stat</b>, which is what its three carriers actually say: 姬塔's
 *       龙卷的攻刃 「物理攻击力」, 可可萝's 精灵的启示 「物理攻击力」, 佩可莉姆's 6★
 *       「物理攻击力を大アップ」. Its javadoc has always called it 「物攻」; that is now literally true.</li>
 *   <li><b>「魔法攻击力アップ」 is {@link EffectType#BUFF_MAGIC_ATTACK_AREA}</b>, its own type and its own
 *       registered effect ({@link MagicAttackAreaEffect}). 凯露's 黑暗之蚀 is the shipped carrier. Putting
 *       her on this effect would raise the stat she never reads, which is the original defect turned
 *       inside out — and it is exactly what the verification for this task rules out ("凯露's EX raises
 *       {@code magic_damage} and not {@code ATTACK_DAMAGE}").</li>
 *   <li><b>The alternative — one type raising both stats — was considered and rejected.</b> It reads
 *       attractively ("a buff that says attack raises whichever stat you use"), and it would work, but it
 *       cannot express a <em>magic-only</em> buff at all: 凯露's EX would have to raise the physical stat
 *       too, so the fix for "her buff lands on the wrong stat" would be "her buff lands on both". Two types
 *       also cost less than one type doing two things, because {@link AttackAreaRules} is then one ladder
 *       read by two effects with no second rule anywhere.</li>
 * </ul>
 *
 * <p>The ladder is shared: {@link AttackAreaRules} is +20% a tier for both types, so
 * {@code EffectTiers} states the rung once and a pack that knows tier 4 of one knows the other.
 *
 * <h2>Why this is a separate effect from {@code astrum:attack_buff}</h2>
 * {@link AttackBuffEffect} is the shipped djeeta hook, whose magnitude arrives as a <em>ratio</em> in the
 * datapack ({@code multiplier: 1.5}). This one is the <b>tiered, generic</b> form: five rungs, each +20%,
 * chosen by a tier. They are two types rather than one because a player may legitimately have both — a
 * djeeta swing buff and a tiered party buff — and vanilla merges two instances of one <em>type</em>, which
 * would silently drop the weaker. Two types, two HUD entries, two modifiers under different fixed ids, and
 * both fractions add as {@code ADD_MULTIPLIED_TOTAL} permits: {@code ×(1 + 0.5 + 1.0)}.
 *
 * <p>Everything else is {@link AttackBuffEffect}'s construction, and for its reasons: a fixed modifier id
 * so a recast replaces rather than compounds, and vanilla's own add/remove over the instance's lifetime so
 * there is no expiry code and no tick listener.
 */
@ApiStatus.Internal
public final class AttackAreaEffect extends MobEffect {

    /** Particle tint: the same warm gold the self-buff uses — the same stat, the same colour. */
    public static final int COLOUR = 0xF0C24B;

    /** Registry path: {@code astrum:attack_area}. */
    public static final String PATH = "attack_area";

    /**
     * This effect's own modifier id.
     *
     * <p><b>Deliberately not</b> {@link AttackBuffEffect#MODIFIER_ID}: vanilla's
     * {@code addAttributeModifiers} removes a modifier <em>by id</em> before adding its replacement, so two
     * effects sharing an id would make the second one cancel the first — a party buff erasing the
     * character's own buff, which is exactly the kind of silent loss this project treats as a defect.
     */
    public static final ResourceLocation MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath("astrum", "attack_area");

    /**
     * Builds the effect: one {@code ADD_MULTIPLIED_TOTAL} modifier on attack damage, whose fraction is the
     * tier's, encoded in the amplifier by {@link AttackAreaRules}.
     */
    public AttackAreaEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOUR);
        addAttributeModifier(Attributes.ATTACK_DAMAGE, MODIFIER_ID,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                AttackAreaRules::fractionForAmplifier);
    }
}
