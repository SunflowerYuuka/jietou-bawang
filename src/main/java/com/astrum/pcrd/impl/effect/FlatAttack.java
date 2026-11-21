package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.FlatAmount;
import com.astrum.pcrd.api.v1.GrantDuration;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「提升自身【base+perLevel×技能等级】物攻/魔攻」 as one attribute write</b> — where a flat EX grant
 * becomes a number on the player, and where it is taken away again.
 *
 * <h2>Why a modifier and not a {@code MobEffect}</h2>
 * Two reasons, and the second is the decisive one:
 *
 * <ol>
 *   <li><b>A {@code MobEffect}'s modifier can only be a function of its amplifier</b>
 *       ({@code MobEffect.addAttributeModifier(holder, id, operation, Int2DoubleFunction)}), and an
 *       amplifier is a byte. 姬塔's 剑圣的天赋 is {@code 240 + 15 × L} = {@code 3 360} points at Lv208, so
 *       encoding points in an amplifier would need a granularity of ~14 points per step — i.e. a rounding
 *       error larger than most whole buffs, to express a number we already know exactly.</li>
 *   <li><b>The lifetime is not a duration.</b> An EX passive is 「战斗开始时提升自身属性」 and lasts exactly
 *       as long as the character holds her party slot. That is an <em>ownership</em>, and the codebase
 *       already has exactly one place that owns it — {@code ExPassives.apply}, driven by the one party-edit
 *       point. A {@code MobEffectInstance} would need a clock or an expiry listener to express a fact that
 *       is already known at enrolment.</li>
 * </ol>
 *
 * <h2>One modifier id per (character, stat), and why it is derived</h2>
 * Vanilla keys attribute modifiers <b>by id</b>, and {@code addAttributeModifiers} removes by id before
 * adding. A single shared id would therefore make a second character in the party silently cancel the
 * first one's EX — two girls in one party is the normal case, not an edge case. So the id is derived from
 * the character:
 *
 * <pre>
 *   astrum:flat_attack/&lt;character path&gt;                       ATTACK_DAMAGE, ADD_VALUE
 *   astrum:flat_magic_attack/&lt;character path&gt;                 astrum:magic_damage, ADD_VALUE
 * </pre>
 *
 * <p>That makes the grant idempotent per character (re-applying replaces), independently removable
 * (leaving the party removes only hers), and self-describing in {@code /attribute}.
 *
 * <h2>{@code ADD_VALUE}, because the base is zero</h2>
 * The magic stat's base is <b>0</b> ({@code MagicAttack.DEFAULT_VALUE}), and a multiplicative modifier on a
 * zero base is a no-op. Flat points are also what the bwiki formulas <em>are</em>, so the operation is
 * {@code ADD_VALUE} for both stats — the same operation, because the quantity is the same kind of thing.
 *
 * <h2>Never throws, never produces a hostile attribute value</h2>
 * Reached from a party edit and from login. A non-finite or non-positive point count is a no-op rather
 * than a write, and a body with no such attribute (an armour stand, a client-side copy) is skipped instead
 * of throwing — the same discipline {@code MagicAttack} and {@code AreaBuffs} keep.
 */
@ApiStatus.Internal
public final class FlatAttack {

    /**
     * The modifier id's namespace-prefix for the physical stat, before the character path is appended.
     *
     * <p>Spelled once so {@link #modifierId} and {@code FlatAttackTest}'s id assertions agree, and so the
     * two stats' ids differ by one substring rather than by two independent literals.
     */
    public static final String PHYSICAL_PREFIX = "flat_attack/";

    /** The same for the magic stat. */
    public static final String MAGIC_PREFIX = "flat_magic_attack/";

    private FlatAttack() {
    }

    /**
     * {@return the attribute modifier id a character's flat grant on {@code magic} writes under}
     *
     * @param characterId the character the grant belongs to
     * @param magic       whether the grant is the magic stat's
     */
    public static ResourceLocation modifierId(ResourceLocation characterId, boolean magic) {
        String path = (magic ? MAGIC_PREFIX : PHYSICAL_PREFIX)
                + (characterId == null ? "unknown" : characterId.getPath().toLowerCase(Locale.ROOT));
        return ResourceLocation.fromNamespaceAndPath(Astrum.MOD_ID, path);
    }

    /**
     * Writes {@code points} of {@code type}'s stat onto {@code entity}, replacing that character's previous
     * grant.
     *
     * @param entity      the body to buff; {@code null} is a no-op
     * @param characterId whose grant this is, for the modifier id
     * @param type        {@link EffectType#FLAT_ATTACK_UP} or {@link EffectType#FLAT_MAGIC_ATTACK_UP}
     * @param points      the flat amount, in the attribute's own units
     * @return whether the entity's attribute changed
     */
    public static boolean grant(@Nullable LivingEntity entity, ResourceLocation characterId,
            EffectType type, double points) {
        AttributeInstance instance = instanceOf(entity, type);
        if (instance == null || !Double.isFinite(points) || !(points > 0.0)) {
            return false;
        }
        ResourceLocation id = modifierId(characterId, isMagic(type));
        AttributeModifier existing = instance.getModifier(id);
        if (existing != null && existing.amount() == points
                && existing.operation() == AttributeModifier.Operation.ADD_VALUE) {
            return false;   // already exactly right: no write, so nothing on the client re-syncs
        }
        instance.removeModifier(id);
        instance.addTransientModifier(new AttributeModifier(id, points,
                AttributeModifier.Operation.ADD_VALUE));
        Astrum.LOGGER.debug("astrum: {} of {} -> +{} points under {} (now {})", type, characterId, points,
                id, instance.getValue());
        return true;
    }

    /**
     * Takes a character's flat grant back off {@code entity}.
     *
     * <p>Removal is by id, so it is exact and total: it cannot leave a fraction behind, and it does not
     * touch another character's grant on the same attribute.
     *
     * @param entity      the body to clean; {@code null} is a no-op
     * @param characterId whose grant to remove
     * @param type        the stat's flat effect type
     * @return whether something was removed
     */
    public static boolean revoke(@Nullable LivingEntity entity, ResourceLocation characterId,
            EffectType type) {
        AttributeInstance instance = instanceOf(entity, type);
        if (instance == null) {
            return false;
        }
        ResourceLocation id = modifierId(characterId, isMagic(type));
        if (instance.getModifier(id) == null) {
            return false;
        }
        instance.removeModifier(id);
        Astrum.LOGGER.info("astrum: flat {} of {} removed (now {})", type, characterId,
                instance.getValue());
        return true;
    }

    /**
     * {@return the points a grant currently contributes, {@code 0} when it contributes none}
     *
     * <p>The read side, so a log line and a test can state "her EX is worth exactly N" without recomputing
     * the formula. Reads the modifier's own {@code amount} rather than the attribute's total, so an
     * unrelated base value cannot be mistaken for the grant.
     *
     * @param entity      the body, possibly {@code null}
     * @param characterId whose grant to read
     * @param type        the stat's flat effect type
     */
    public static double granted(@Nullable LivingEntity entity, ResourceLocation characterId,
            EffectType type) {
        AttributeInstance instance = instanceOf(entity, type);
        if (instance == null) {
            return 0.0;
        }
        AttributeModifier modifier = instance.getModifier(modifierId(characterId, isMagic(type)));
        return modifier == null ? 0.0 : modifier.amount();
    }

    /**
     * {@return the points {@code amount} is worth at {@code level}, for a caller holding a formula}
     *
     * <p>A one-line delegate so the "level at least 1" and "never negative" rules live in
     * {@link FlatAmount} and not in every caller.
     *
     * @param amount the formula
     * @param level  the character's level
     */
    public static double pointsFor(FlatAmount amount, int level) {
        return amount == null ? 0.0 : amount.amountAt(level);
    }

    /**
     * {@return whether a flat effect can be granted at all, i.e. whether its duration is supported}
     *
     * <h2>Why a timed flat grant is refused rather than approximated</h2>
     * A flat grant is carried by an attribute modifier under a per-character id, so removing it needs to
     * know <b>which character</b> it belonged to. A {@code MobEffectInstance} does not carry that: vanilla
     * gives an instance an effect type and an amplifier, nothing else. So a timed flat grant would have to
     * smuggle an owner into an amplifier — a slot table, a second subsystem, and a source of wrong-owner
     * removals whenever the party changes while the buff is running.
     *
     * <p>Rather than ship that half-built, a timed flat grant is <b>refused with one WARN</b> (this method
     * answering {@code false} is what the caller checks), and a pack that wants a timed attack buff has two
     * mechanisms that are implemented and tested: {@code attack_buff} (a ratio) and
     * {@code buff_attack_area} / {@code buff_magic_attack_area} (a tier). The permanent form — which is
     * every shipped EX clause — is fully supported. See {@code task-39-report.md} for the deviation.
     *
     * @param duration the declared lifetime
     */
    public static boolean supports(@Nullable GrantDuration duration) {
        return duration != null && duration.isPermanent();
    }

    /** {@return whether {@code type} is the magic stat's flat effect} */
    private static boolean isMagic(EffectType type) {
        return com.astrum.pcrd.api.v1.EffectTypes.isMagicFlat(type);
    }

    /** {@return the attribute instance {@code type} writes, or {@code null} when it cannot be written} */
    private static @Nullable AttributeInstance instanceOf(@Nullable LivingEntity entity, EffectType type) {
        if (entity == null) {
            return null;
        }
        if (isMagic(type)) {
            // The magic stat is registered by this mod, so it may legitimately be absent (a body whose
            // supplier predates the attribute). MagicAttack.of already answers 0 for that case; here we
            // need the instance itself, so the holder is resolved bound-safely.
            return MagicAttack.holder().map(entity::getAttribute).orElse(null);
        }
        return entity.getAttribute(Attributes.ATTACK_DAMAGE);
    }
}
