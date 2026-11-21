package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.Locale;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>魔法攻击力</b>: this mod's own attack stat, and the one place it is read.
 *
 * <h2>The gap this closes</h2>
 * The build had exactly one attack stat, {@code Attributes.ATTACK_DAMAGE}, and {@code astrum:magic} was
 * only a <em>damage type</em> marker — {@link DamageReduction#isMagic} reads it to pick which of the two
 * defence buffs applies. So 凯露's 黑暗之蚀 「提升自身【15+15*技能等级】<b>魔法攻击力</b>」 had nowhere to land
 * except the physical stat: her EX raised the number a sword uses, and her own magic damage (filed
 * {@code astrum:magic} by every hook and both ultimate tiers) did not read it at all. That is the defect
 * this class exists to remove.
 *
 * <h2>Why a real {@link Attribute} and not a damage-event rewrite</h2>
 * The alternative considered first was to recompute magic damage in a listener —
 * {@code LivingIncomingDamageEvent} or {@code LivingDamageEvent.Pre} — by swapping
 * {@code ATTACK_DAMAGE}'s contribution for this statistic's. That was rejected for three reasons, and they
 * are worth recording because "recompute it in an event" is the obvious-looking answer:
 *
 * <ol>
 *   <li><b>There is no contribution to swap.</b> A {@code hurt(source, amount)} carries a finished
 *       {@code float}. Nothing on the path records "this hit used 7.0 of attack damage", so a listener
 *       cannot subtract one stat's share and add another's — it can only <em>guess</em> a ratio, and a guess
 *       that is wrong is indistinguishable from a working feature until somebody measures it. The values this
 *       build actually deals are absolute numbers derived in {@code docs/balance-derivation.md} precisely so
 *       that no such back-solve is needed.</li>
 *   <li><b>It would double-apply.</b> {@code LivingDamageEvent.Pre} already carries the two defence buffs
 *       ({@code DamageReduction.applyBoth}) and is also where armour has already been applied. A second
 *       writer of "how much damage" in the same event is how a hit ends up scaled twice, and the earlier
 *       tasks measured exactly that class of defect (report §4 of task 9).</li>
 *   <li><b>An attribute is what the game and the player already understand.</b> A registered attribute has a
 *       value, tooltips, {@code /attribute} support, and vanilla's own add/remove lifecycle for a modifier
 *       carried by a {@code MobEffect} — which is how every other stat this mod grants works
 *       ({@code attack_area}, {@code haste}, {@code shield}). Expiry is therefore vanilla's clock and needs
 *       no tick listener.</li>
 * </ol>
 *
 * <h2>So where is it read?</h2>
 * {@link #attackFor} — the <b>one</b> place "which attack stat does this hit use?" is answered. It reads
 * {@code magic_damage} for a hit whose damage type {@link DamageReduction#isMagic} calls magical, and
 * {@code ATTACK_DAMAGE} otherwise. The mod's own damage settlement path
 * ({@code impl.ub.UbDamageSettlement}) and the {@code extra_damage} executor both go through it, so a magical
 * carrier scales with 魔法攻击力 and a physical one with 攻击力 — one rule, one copy. It is <b>not</b> a
 * mulitplier on anything: the tier's authored number is what is dealt (see {@code UbDamage}'s javadoc for why
 * that is the correct reading of §5.4), and this stat is what a future additive carrier would read.
 *
 * <h2>The default is 0, and that is load-bearing</h2>
 * {@code magic_damage}'s default is <b>0</b>, not {@code ATTACK_DAMAGE}'s 2.0 and not any positive number. A
 * non-caster — a sword, a bow, a mob — therefore has no magic attack at all, and a magic-typed hit from such
 * a body is not silently given a stat it never had. Only a character's EX (or a datapack, or an operator
 * with {@code /attribute}) can put a number there. This is the property {@code MagicAttackTest}
 * pins first, because it is the one that makes "a physical character is unaffected" true rather than
 * merely likely.
 *
 * <h2>Registration, and why the attribute is added to every living entity</h2>
 * NeoForge 21.1.265 registers a custom attribute through {@link DeferredRegister} over
 * {@link Registries#ATTRIBUTE} (the same construction {@code PartyEffects} uses for its effects), and an
 * attribute only exists on an entity whose {@code AttributeSupplier} declares it. So
 * {@link EntityAttributeModificationEvent} adds it to every registered living type at its default of 0 —
 * without that hook the attribute would be registered but unreadable from any entity, which is the failure
 * mode that looks like "the stat is always zero" rather than like a missing registration.
 */
@ApiStatus.Internal
public final class MagicAttack {

    /** Registry path: {@code astrum:magic_damage}. */
    public static final String PATH = "magic_damage";

    /** The attribute's registry id, for log lines and for {@code /attribute}. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /**
     * The translation key vanilla resolves for the attribute's name, {@code attribute.astrum.magic_damage}.
     *
     * <p>Vanilla builds an {@link Attribute}'s description id from the string it is constructed with, and
     * the HUD, the tooltip and this constant all have to name the same key or a player reads a raw id.
     */
    public static final String NAME_KEY = "attribute." + ID.toLanguageKey();

    /** The default value: <b>0</b>, so a non-caster has no magic attack. See the class javadoc. */
    public static final double DEFAULT_VALUE = 0.0;

    /**
     * The magic attack a <b>+100%</b> buff is worth, in the attribute's own units.
     *
     * <h2>Why a reference point is needed at all, and why it is 2000</h2>
     * The shipped damage numbers in this build are <b>absolute</b> — derived in
     * {@code docs/balance-derivation.md} §14 from the bwiki formulas at the project's reference point
     * (level 208, attack 2000) and clamped to a 20.0 anchor. Nothing in {@code hurt(source, amount)} records
     * "this hit used 7.0 of attack damage", so a stat cannot be <em>substituted</em> into a finished number;
     * the only faithful way for a stat to matter is as a <b>ratio against that same reference</b>, which is
     * the number the derivation itself used. So {@link #magicScale} answers
     * {@code 1 + magic_damage / 2000}, which is exactly 1.0 at the unchanged state and 2.0 after a +2000
     * contribution — and a "+100% magic attack" buff is +2000 in the attribute.
     *
     * <p>This is the same 2000 every other derivation in this repository quotes, so there is one reference
     * point and not two. It is also why the buff's modifier is {@code ADD_VALUE} rather than
     * {@code ADD_MULTIPLIED_TOTAL}: multiplying a base of {@link #DEFAULT_VALUE} (0) by any fraction is still
     * 0, so a multiplier-mode buff on a default-zero attribute would be a buff that does nothing — the
     * failure mode this project treats as a defect. An additive contribution of
     * {@code fraction × 2000} is what "+20% of a 2000-attack caster" means arithmetically.
     */
    public static final double REFERENCE_ATTACK = 2000.0;

    /**
     * The damage type this mod ships as its magic marker — {@code astrum:magic}.
     *
     * <p>Delegated to {@link DamageReduction#MAGIC_TYPE} rather than declared again: that class owns the
     * magic/physical classification for the defence buffs, and the attacker's stat reads the same predicate.
     * A second literal here would be a second definition of "magic" in the class that consumes the first.
     */
    public static final ResourceLocation SHIPPED_MAGIC_TYPE = DamageReduction.MAGIC_TYPE;

    /**
     * The attribute's ceiling.
     *
     * <p>{@code RangedAttribute} clamps in {@code sanitizeValue}, so a hostile modifier (a datapack, a
     * broken save, an operator typo) becomes a capped number rather than a {@code NaN} on a health bar.
     *
     * <h2>Why 8192 and not 2048</h2>
     * It was {@code 2048}, {@code ATTACK_DAMAGE}'s own maximum in 21.1.265, on the argument that the two
     * attack stats should be interchangeable in range. Task 39 raised the magic rung from 20% to 26% a tier
     * (<b>520 points</b> a rung), which makes tier 5 worth <b>2600 points</b> — a number 2048 would have
     * <b>silently clamped</b>, i.e. the top rung of the magic buff would have been worth 2048 instead of
     * 2600 and nothing would have said so.
     *
     * <p>The ceiling must hold the largest <em>honest</em> total, and the parts add because they are all
     * {@code ADD_VALUE}:
     *
     * <pre>
     *   a tier-5 magic buff                        2 600 points
     * + 黑暗之蚀+ at the character level cap (100)   240 + 15×100 = 1 740
     * = the worst case a real player can reach     4 340
     *
     * + the reference-level (208) form of the same formula, for the derivation's own arithmetic: 3 360
     *   -> 5 960, which is why the ceiling is 8192 and not 4 608
     * </pre>
     *
     * <p>So the asymmetry with {@code ATTACK_DAMAGE}'s 2048 is deliberate and is a consequence of the magic
     * stat being written as points: the two stats no longer share a scale, which is exactly what the human's
     * 5-10% rule asked for. {@code MagicAttackTest} pins that the top rung fits.
     */
    public static final double MAX_VALUE = 8192.0;

    /** The attribute registrations, on the mod bus. */
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, Astrum.MOD_ID);

    /**
     * 魔法攻击力 itself.
     *
     * <p>{@code syncable}, so a client can show it: {@code Attribute} is not synced to clients unless it says
     * so, and an unsynced stat reads 0 on the client while being correct on the server — a discrepancy that
     * shows up as a wrong tooltip and nowhere else.
     */
    public static final DeferredHolder<Attribute, Attribute> MAGIC_DAMAGE =
            ATTRIBUTES.register(PATH, () -> new RangedAttribute(NAME_KEY, DEFAULT_VALUE, 0.0, MAX_VALUE)
                    .setSyncable(true));

    /**
     * This effect's own modifier id, for the tiered attack buff's magic half.
     *
     * <p>{@code AttackAreaEffect} puts one modifier on {@code ATTACK_DAMAGE} under its own id; this is the
     * second one it puts on {@code magic_damage}. Two ids rather than one, because vanilla keys modifiers by
     * id per attribute — sharing an id across two attributes would work, but it would also make the two
     * halves unremovable independently, which is the property {@code AttackAreaEffect} was written to
     * preserve.
     */
    public static final ResourceLocation BUFF_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(Astrum.MOD_ID, "attack_area_magic");

    private MagicAttack() {
    }

    /** Registers the attribute and adds it to every living entity type. Called from the mod constructor. */
    public static void register(IEventBus modBus) {
        ATTRIBUTES.register(modBus);
        modBus.addListener(MagicAttack::addToEveryLivingEntity);
    }

    /**
     * Gives every registered living entity type the attribute, at its default of 0.
     *
     * <p>An attribute that is registered but absent from an entity's {@code AttributeSupplier} cannot be read
     * or modified on that entity: {@code getAttribute} answers {@code null} and {@code getAttributeValue}
     * answers the supplier's value, which throws for an attribute the supplier does not know. So skipping
     * this hook does not produce "a stat that is always zero" — it produces a stat that cannot be given a
     * value at all.
     *
     * <p>{@code add(...)} with the default rather than a positive number: see {@link #DEFAULT_VALUE}.
     */
    private static void addToEveryLivingEntity(EntityAttributeModificationEvent event) {
        for (var type : event.getTypes()) {
            if (!event.has(type, MAGIC_DAMAGE)) {
                event.add(type, MAGIC_DAMAGE, DEFAULT_VALUE);
            }
        }
    }

    /**
     * {@return the entity's 魔法攻击力, or {@code 0} when it has no such attribute}
     *
     * <p>Total and never throwing: this is read on the damage path, where an entity with no attribute map
     * entry (an armour stand, a marker, a client-side copy) must answer "no magic attack" rather than take
     * the hit down with it. {@code getAttribute} answers {@code null} in exactly that case, which is why the
     * check is here rather than left to {@code getAttributeValue}.
     *
     * @param entity the body whose stat is wanted; {@code null} answers {@code 0}
     */
    public static double of(@Nullable LivingEntity entity) {
        if (entity == null || !MAGIC_DAMAGE.isBound()) {
            return 0.0;
        }
        AttributeInstance instance = entity.getAttribute(MAGIC_DAMAGE);
        if (instance == null) {
            return 0.0;
        }
        double value = instance.getValue();
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    /**
     * {@return the attack stat <b>this hit</b> uses}
     *
     * <h2>The one copy of the split, on the attacker's side</h2>
     * {@link DamageReduction#isMagic} already answers "is this damage magical?" for the <em>victim's</em>
     * defence buffs. The attacker's stat is the same question asked from the other end — 「魔法攻击力」 for a
     * magic hit, 「攻击力」 otherwise — so it is answered by the same predicate rather than by a second
     * classification that could disagree. A damage type that is neither {@code astrum:magic} nor in
     * {@code astrum:is_magic} is physical here for the same documented reason it is physical there: another
     * mod's magic damage is unmarked, and guessing from a type's name would silently pick the wrong stat.
     *
     * <p><b>Never negative and never {@code NaN}</b>: a hostile attribute write must not become negative
     * damage or a poisoned health bar.
     *
     * @param entity the attacker whose stat is wanted; {@code null} answers {@code 0}
     * @param magic  whether the damage being dealt is magical — {@link DamageReduction#isMagic}'s answer
     */
    public static double attackFor(@Nullable LivingEntity entity, boolean magic) {
        if (entity == null) {
            return 0.0;
        }
        if (magic) {
            return of(entity);
        }
        return physicalAttackOf(entity);
    }

    /**
     * {@return the entity's physical attack damage, or {@code 0} when it has no such attribute}
     *
     * <p>{@code Attributes.ATTACK_DAMAGE} is a vanilla attribute and exists on every living entity this mod
     * will ever see, but the guard is kept for the same reason {@link #of} has one: a caller on a damage path
     * must not be the thing that discovers an exotic entity with no attribute map.
     *
     * @param entity the body whose stat is wanted; {@code null} answers {@code 0}
     */
    public static double physicalAttackOf(@Nullable LivingEntity entity) {
        if (entity == null) {
            return 0.0;
        }
        AttributeInstance instance = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (instance == null) {
            return 0.0;
        }
        double value = instance.getValue();
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    /**
     * {@return the registry path this mod's magic stat registers under, for a log line}
     *
     * <p>{@code PATH}, lower-cased, as the registry sees it. Present so a log line and a test name the same
     * string rather than each spelling it.
     */
    public static String registryPath() {
        return PATH.toLowerCase(Locale.ROOT);
    }

    /**
     * Adds one {@code ADD_VALUE} point of magic attack to {@code entity}, or removes it when {@code amount}
     * is 0.
     *
     * <h2>Why this exists at all, when an effect covers the buffs</h2>
     * The tiered attack buff is a {@code MobEffect} and gets its modifier from vanilla's own lifecycle (see
     * {@link AttackAreaEffect}). This method is for the paths that are <b>not</b> effects — a probe, an
     * operator command, a future equipment stat — so that "write the magic stat" has one implementation with
     * one modifier id and one round-trip guard, instead of each caller inventing an id and a value.
     *
     * <p>Idempotent: the modifier id is fixed, so a second call replaces rather than compounds. An
     * {@code amount} of 0 removes it, which is what "this source no longer contributes" means.
     *
     * @param entity the body to write; {@code null} is a no-op
     * @param amount the flat value to add, in the attribute's own units
     * @return whether the entity's state changed — {@code false} for a body with no such attribute
     */
    public static boolean setBonus(@Nullable LivingEntity entity, double amount) {
        if (entity == null || !MAGIC_DAMAGE.isBound() || !Double.isFinite(amount)) {
            return false;
        }
        AttributeInstance instance = entity.getAttribute(MAGIC_DAMAGE);
        if (instance == null) {
            return false;
        }
        AttributeModifier existing = instance.getModifier(BUFF_MODIFIER_ID);
        if (!(amount > 0.0)) {
            if (existing == null) {
                return false;
            }
            instance.removeModifier(BUFF_MODIFIER_ID);
            return true;
        }
        if (existing != null && existing.amount() == amount
                && existing.operation() == AttributeModifier.Operation.ADD_VALUE) {
            return false;
        }
        instance.removeModifier(BUFF_MODIFIER_ID);
        instance.addPermanentModifier(new AttributeModifier(BUFF_MODIFIER_ID, amount,
                AttributeModifier.Operation.ADD_VALUE));
        return true;
    }

    /** {@return the registered attribute holder, or empty before the registry is up} */
    public static java.util.Optional<Holder<Attribute>> holder() {
        return MAGIC_DAMAGE.isBound() ? java.util.Optional.of(MAGIC_DAMAGE) : java.util.Optional.empty();
    }

    /**
     * {@return the factor a magic hit's authored number is multiplied by, given this caster's 魔法攻击力}
     *
     * <pre>
     *   factor = 1 + magic_damage / 2000
     * </pre>
     *
     * <h2>The one place a stat becomes a number on a hit</h2>
     * The shipped amounts are absolute (see {@link #REFERENCE_ATTACK}), so "the stat is used" can only
     * honestly mean "the hit is scaled by how far this caster is from the reference the amount was derived
     * at". At an unchanged state {@code magic_damage} is {@link #DEFAULT_VALUE} = 0 and this answers
     * <b>exactly 1.0</b> — bit-for-bit the same float, so a character who never touches the stat deals
     * precisely what she dealt before this feature existed. That identity is what makes the feature additive
     * rather than a rebalance, and {@code MagicAttackTest} pins it.
     *
     * <p><b>Never negative, never {@code NaN}, never zero</b>: a hostile write must not be able to turn a
     * hit into a heal or into nothing, so a non-positive or non-finite stat answers exactly 1.0 (no change)
     * rather than a fraction that would shrink the hit.
     *
     * <p>Pure over a number so the arithmetic is assertable without a world — the seam
     * {@code DamageReduction.apply} provides for the defence buffs, for the same reason.
     *
     * @param magic the caster's 魔法攻击力, in the attribute's own units
     */
    public static double scaleOf(double magic) {
        if (!(magic > 0.0) || !Double.isFinite(magic)) {
            return 1.0;
        }
        double scale = 1.0 + magic / REFERENCE_ATTACK;
        return Double.isFinite(scale) && scale > 0.0 ? scale : 1.0;
    }

    /**
     * {@return the factor for {@code entity}'s magic attack — {@link #scaleOf} over {@link #of}}
     *
     * @param entity the caster; {@code null} answers 1.0, i.e. "no magic stat, no change"
     */
    public static double magicScale(@Nullable LivingEntity entity) {
        return scaleOf(of(entity));
    }

    /**
     * {@return the factor to apply to one hit's authored amount before it is dealt}
     *
     * <h2>Why this is the caller's single question, and why it cannot double-apply</h2>
     * It answers <b>1.0 for anything that is not magical</b> — {@link DamageReduction#isMagic}'s answer, the
     * same predicate the victim's defence buffs already use, so the attacker's stat and the defender's
     * buffs can never disagree about what "magic" means. A physical hit therefore passes through untouched
     * by construction, not by a second check at each call site.
     *
     * <p>Each damage path calls this <b>once</b>, on the amount it is about to hand to {@code hurt()}:
     * {@code CombatEvents.flush} for the hook batch and {@code UbDamageSettlement} for an ultimate's hits.
     * Nothing downstream multiplies again — armour, the defence buffs and the {@code LivingDamageEvent}
     * stages all see the already-scaled number, which is the only ordering in which a bug in this stat
     * cannot be mistaken for a bug in the defence arithmetic.
     *
     * @param attacker the caster whose stat applies; {@code null} answers 1.0
     * @param source   the damage about to be dealt, whose type decides whether the stat applies at all
     */
    public static double scaleFor(@Nullable LivingEntity attacker, @Nullable DamageSource source) {
        if (attacker == null || source == null || !DamageReduction.isMagic(source)) {
            return 1.0;
        }
        return magicScale(attacker);
    }
}
