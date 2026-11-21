package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>one</b> place the two defence buffs become less damage.
 *
 * <h2>Why a damage-event listener and not an attribute</h2>
 * 1.21.265 has no "damage taken" attribute: armour and resistance are both applied inside
 * {@code LivingEntity.getDamageAfterMagicAbsorb}, reading fields and effects, not an attribute this mod can
 * modify. So a percentage damage reduction cannot be an attribute modifier and has to be applied where the
 * damage is decided. That is {@code LivingDamageEvent.Pre} — post-armour, pre-absorption, and
 * <b>writable</b> through {@code setNewDamage} (measured; see {@code CombatEvents}' table, which pins the
 * same numbers). {@code Post} is too late and is not writable.
 *
 * <h2>Why this is not {@code minecraft:resistance}</h2>
 * Resistance is 20% a level, so level V is 100% — immunity — and the brief's ceiling is 60%. It also cannot
 * be split: it reduces all damage except what {@code bypasses_resistance} lets through. Two buffs need two
 * reductions, so both are this mod's own and the split is made here, from {@link #isMagic}.
 *
 * <h2>The split, and its documented hole</h2>
 * A damage type is magical when it <b>is</b> {@code astrum:magic} or is <b>in</b> the tag
 * {@code astrum:is_magic}. Everything else is physical. Minecraft ships no {@code is_magic} tag (verified:
 * {@code is_fire}, {@code is_projectile}, {@code is_explosion}, {@code is_freezing}, {@code is_lightning},
 * {@code is_fall}, {@code is_drowning} exist; magic does not), so the marker is ours — which means
 * <b>another mod's magic damage is treated as physical</b> until it declares {@code astrum:magic} or adds
 * its types to {@code astrum:is_magic}. See {@link DefenceRules} for why guessing is not an option.
 *
 * <h2>Two reductions, applied as one multiplication of the remaining fraction</h2>
 * They are separate effects and can both be on one player, but a damage type is only ever one of the two,
 * so at most one applies. Written as a single {@code 1 - reduction} factor rather than two successive
 * multiplications, which would double-count if the classification ever changed.
 */
@ApiStatus.Internal
public final class DamageReduction {

    /** The tag that marks a damage type as magical, {@code astrum:is_magic}. */
    public static final TagKey<DamageType> MAGIC_TAG =
            TagKey.create(Registries.DAMAGE_TYPE, InternalIds.id(DefenceRules.MAGIC_TAG_PATH));

    /** The damage type this mod owns to mark magic damage, {@code astrum:magic}. */
    public static final ResourceLocation MAGIC_TYPE = InternalIds.id(DefenceRules.MAGIC_DAMAGE_PATH);

    private DamageReduction() {
    }

    /**
     * {@return whether {@code source} is magical, i.e. reduced by the magic buff and not the physical one}
     *
     * <p>False for a null source: an unattributable damage type is not magic, and treating "unknown" as
     * magical would silently exempt it from the physical buff instead of subjecting it to it.
     *
     * <p>{@code source.is(tag)} resolves the tag through the level's registry, so a pack adding its own
     * types to {@code astrum:is_magic} is honoured on the next reload with no code change.
     *
     * @param source the damage being applied
     */
    public static boolean isMagic(DamageSource source) {
        if (source == null) {
            return false;
        }
        try {
            if (source.is(MAGIC_TAG)) {
                return true;
            }
            // The tag alone would be enough IF every consumer's data shipped it, but a hook naming
            // `astrum:magic` directly must work even before a tag file is loaded, so the type is checked
            // by key as well. Both answers are the same question; checking both is what makes the marker
            // work on the first tick of a world.
            return source.typeHolder().unwrapKey()
                    .map(ResourceKey::location)
                    .filter(MAGIC_TYPE::equals)
                    .isPresent();
        } catch (RuntimeException e) {
            // A tag lookup before the registry is bound throws; a damage path must not.
            Astrum.LOGGER.warn("astrum: could not classify damage source as magic; treating it as "
                    + "physical for this hit", e);
            return false;
        }
    }

    /**
     * {@return the reduction {@code victim} currently has against {@code source}, as a fraction}
     *
     * <p>Reads the <b>matching</b> buff only: the magical one for magical damage, the physical one
     * otherwise. Never throws and never answers a value outside {@code [0, MAX_REDUCTION]} — a
     * {@code NaN} here would make the damage {@code NaN} and kill the victim's health silently.
     *
     * @param victim the entity the damage is about to land on
     * @param source the damage being applied
     */
    public static double reductionFor(LivingEntity victim, DamageSource source) {
        if (victim == null) {
            return 0.0;
        }
        boolean magic = isMagic(source);
        Optional<Holder<MobEffect>> holder = magic
                ? PartyEffects.magicDefenceHolder()
                : PartyEffects.physDefenceHolder();
        if (holder.isEmpty()) {
            return 0.0;
        }
        MobEffectInstance instance = victim.getEffect(holder.get());
        if (instance == null) {
            return 0.0;
        }
        return DefenceRules.reductionFor(DefenceRules.tierFor(instance.getAmplifier()));
    }

    /**
     * {@return the <b>increase</b> in magic damage {@code victim} takes, as a fraction}
     *
     * <h2>The mirror of the magic buff, and the reason it is not an armour modifier</h2>
     * 「魔法防御力をダウン」 is the debuff half of 〔魔法防御力アップ〕, so it is read at the same place and by
     * the same classification. It is deliberately <b>not</b> a negative {@code Attributes.ARMOR} modifier
     * (which is what {@link DefenceDownEffect} legitimately is): armour is one attribute and the mod's magic
     * damage is not tagged {@code minecraft:bypasses_armor}, so armour <em>does</em> reduce it — by the
     * physical curve. Lowering it here would implement the physical debuff twice and leave 「魔法防御力ダウン」
     * unimplemented. See {@link com.astrum.pcrd.api.v1.EffectType#MAGIC_DEFENCE_DOWN}.
     *
     * <p><b>Magic only.</b> A physical hit is not increased, for the same reason the magic buff does not
     * reduce it: the two sides of the stat sheet are the split this class exists to make. An unmarked damage
     * type is treated as physical, so this debuff does nothing to another mod's magic — the limitation
     * {@link DefenceRules} documents for the buff, stated once and honoured in both directions.
     *
     * @param victim the entity the damage is about to land on
     * @param source the damage being applied
     */
    public static double magicPenaltyFor(LivingEntity victim, DamageSource source) {
        if (victim == null || !isMagic(source)) {
            return 0.0;
        }
        Optional<Holder<MobEffect>> holder = PartyEffects.magicDefenceDownHolder();
        if (holder.isEmpty()) {
            return 0.0;
        }
        MobEffectInstance instance = victim.getEffect(holder.get());
        if (instance == null) {
            return 0.0;
        }
        return MagicDefenceDownRules.penaltyForAmplifier(instance.getAmplifier());
    }

    /**
     * {@return {@code damage} after the victim's matching defence buff <b>and</b> the magic-defence debuff}
     *
     * <p>The whole of the buff/debuff arithmetic for one hit, in one call, so the listener cannot apply one
     * half and forget the other. The two are a single multiplication of the remaining fraction rather than
     * two successive ones — the same reason {@link #apply} is written that way.
     *
     * @param damage   the post-armour damage
     * @param reduction the fraction the buff removes, in {@code [0, 1)}
     * @param magicPenalty the fraction the debuff adds, in {@code [0, MAX_PENALTY]}
     */
    public static float applyBoth(float damage, double reduction, double magicPenalty) {
        float afterBuff = apply(damage, reduction);
        if (!(afterBuff > 0.0F) || Float.isNaN(afterBuff)) {
            return afterBuff;
        }
        if (!(magicPenalty > 0.0) || !Double.isFinite(magicPenalty)) {
            return afterBuff;
        }
        double clamped = Math.min(magicPenalty, MagicDefenceDownRules.MAX_PENALTY);
        return (float) (afterBuff * (1.0 + clamped));
    }

    /**
     * {@return {@code damage} after the victim's matching defence buff}
     *
     * <p>Pure and public so the arithmetic is assertable without a server: "tier 5 halves-and-then-some of
     * 10.0 is 4.0" is a number, and it is the number the buff's text promises.
     *
     * @param damage the post-armour damage
     * @param reduction the fraction to remove, in {@code [0, 1)}
     */
    public static float apply(float damage, double reduction) {
        if (!(damage > 0.0F) || Float.isNaN(damage)) {
            return damage;
        }
        if (!(reduction > 0.0) || !Double.isFinite(reduction)) {
            return damage;
        }
        double clamped = Math.min(reduction, DefenceRules.MAX_REDUCTION);
        return (float) (damage * (1.0 - clamped));
    }
}
