package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>One damage clause of an ultimate</b> — an amount, how many times it lands, and <em>who it lands on</em>.
 *
 * <h2>Why a list of these, and not more fields on {@link UbTier}</h2>
 * An ultimate's text is a list of clauses, and they do not share a receiver: 日和莉 「对最近的敌人造成 10.0」 and
 * 「对范围半径400内全部敌人造成 4.5」 are two numbers on two kinds of target, and 美美 「对第二近的一名敌人」
 * plus 「以前方第2名角色为中心、对范围275内」 is two numbers on two <em>different centres</em>. One
 * {@code damage} field and one optional {@code radius} can spell exactly one of those clauses; the other five
 * shipped ones were parked in {@link UbTier#extra()}, which the book renders as 「尚未实现」.
 *
 * <p>So a segment is the unit of "one clause": its own amount, its own hit count, its own {@link UbTargetRule},
 * its own reach, its own damage type, its own crit multiplier, its own per-target scaling — and its own
 * effects, for the clauses whose clause is <em>about a body</em> rather than about a number: 紬希's 「将最远的
 * 敌人拉近 300」 is one {@code pull} on the {@link UbTargetRule#FARTHEST} body and 「击退最近的敌人 300」 is one
 * {@code knockback} on the {@link UbTargetRule#NEAREST} body, neither of which deals the damage.
 *
 * <h2>Backward compatibility is structural, not asserted-and-hoped</h2>
 * {@link UbTier} keeps {@code hits}, {@code damage} and {@code radius} and turns them into <b>one implicit
 * segment</b> ({@code UbTier.damageSegments()}) when {@code segments} is absent: {@link #NEAREST} with no
 * radius, {@link #ALL_IN_RADIUS} with one — exactly the two rules the old planner implemented, on the same
 * numbers, through the same downstream code. So "a pack that predates this field plays unchanged" is not a
 * promise the planner keeps per branch: there is only one branch, and the old shape is converted into it.
 *
 * <h2>What a segment with no {@code damage} means</h2>
 * An <b>effect clause</b>: it selects its bodies by its own rule and applies its own {@link #effects()} to them,
 * and deals no damage. That is the honest shape for 「将最远的敌人拉近 300」 — the source states a distance, not a
 * damage number, and inventing one would put a number in the data that the wiki never gave.
 *
 * @param damage          damage per application, empty for an effect-only clause (see the class javadoc). A
 *                        segment <b>without</b> a damage of its own is a real clause, not an error
 * @param hits            how many times this clause's amount lands on each of its targets; {@code 1} when the
 *                        pack says nothing, and {@code <= 0} makes the segment deal nothing (the same
 *                        "hostile number means no damage" reading {@code UbDamage.plan} applies to a tier's)
 * @param target          who this clause lands on — see {@link UbTargetRule}. Required: a clause with no
 *                        receiver rule is a number nobody can place, and defaulting it to "the nearest" would
 *                        make a typo apply a blast to one body instead of failing to load
 * @param radius          this clause's reach, in blocks. Required by the two area rules
 *                        ({@link UbTargetRule#needsRadius()}), optional on the rank rules — where it narrows
 *                        the pool the rank is taken from
 * @param effects         the effects this clause applies to the bodies <b>its own rule selected</b>, in
 *                        declaration order. They are victim clauses by construction (the receivers are enemies
 *                        that this clause picked), and the settlement applies them only where the clause's
 *                        damage really landed — or, for an effect clause, on the selected bodies that are still
 *                        alive. A tier-wide {@link UbTier#effects()} list applies to every body the tier hits,
 *                        which is unchanged: this list is for clauses that mean <em>these</em> bodies
 * @param damageType      the damage type this clause's hits are filed under; empty means the tier's own
 *                        ({@link UbTier#damageType()}), which in turn means {@code astrum:ub_damage}. A clause
 *                        that says 「魔法伤害」 next to a physical one needs its own — 日和莉's blast and her
 *                        nearest-target strike are both physical, but a future pack's may not be
 * @param critMultiplier  this clause's own crit multiplier, replacing the tier's for its hits; empty means the
 *                        tier's applies. Same replacement rule, same reasoning as {@link UbTier#critMultiplier()}
 * @param perExtraTarget  <b>how much this clause's amount grows for each additional body it hits</b>, beyond
 *                        the first — 紬希 6★'s 「伤害随目标数量提升」 (bwiki: {@code +0.5×物理攻击力×目标数量}).
 *                        It lives on the <em>segment</em> and not on the tier because it scales one clause's
 *                        amount by the size of that clause's own receiver set: 日和莉's nearest-target strike
 *                        and her blast are two numbers with two receiver sets, and a tier-wide multiplier would
 *                        have to pick one of them to scale. A number that has no receiver set of its own (a
 *                        single-target clause) is left alone by the arithmetic: it is added
 *                        {@code targets - 1} times, which is zero. {@code 0.0} — the default — means "this
 *                        clause does not scale", which is every clause before this field existed
 * @param forcedCrit      「必定暴击」: every application of this clause is a critical hit <b>whatever the
 *                        caster's {@code astrum:crit_chance} is</b>. Read by {@code UbDamageSettlement}, which
 *                        asks {@code Crit.forcedFactorFor} instead of {@code Crit.factorFor} — the multiplier a
 *                        rolled crit uses, so the number really is a crit's number. Default {@code false}, which
 *                        is every clause that rolls for its crits, i.e. every clause before this field existed
 * @param armourPierce    「无视 50 物理防御力」: the share of the <b>target's armour</b> this clause's hits
 *                        ignore, {@code 0.5} for the shipped clause. Empty — the default — means the target's
 *                        armour applies in full. It is a property of the <em>strike</em> and not a debuff on the
 *                        body (that is {@link EffectType#DEFENCE_DOWN}, which has a clock and a visible state),
 *                        and it is arithmetic on the amount rather than a modifier: {@code ArmourPierce} inverts
 *                        vanilla's non-linear armour curve so the hit lands what it would have landed against a
 *                        body with {@code (1 - share)} of its armour. Stated plainly: this is <b>not</b> "half the
 *                        armoured damage removed twice" and not a constant percentage of extra damage — how much
 *                        it is worth depends on the target's armour value
 */
@ApiStatus.Experimental
public record UbSegment(OptionalDouble damage, int hits, UbTargetRule target, OptionalDouble radius,
                        List<UbEffect> effects, Optional<ResourceLocation> damageType,
                        OptionalDouble critMultiplier, double perExtraTarget, boolean forcedCrit,
                        OptionalDouble armourPierce, OptionalDouble lostHealthBonus) {

    public static final Codec<UbSegment> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            // Optional, because an effect clause states a distance or a debuff and no damage: see the class
            // javadoc. A pack that writes `damage` gets a damage clause; one that writes only `effects` gets
            // a clause that moves people.
            Codec.DOUBLE.optionalFieldOf("damage")
                    .xmap(UbSegment::toOptionalDouble, UbSegment::toOptional).forGetter(UbSegment::damage),
            Codec.INT.optionalFieldOf("hits", 1).forGetter(UbSegment::hits),
            UbTargetRule.CODEC.fieldOf("target").forGetter(UbSegment::target),
            Codec.DOUBLE.optionalFieldOf("radius")
                    .xmap(UbSegment::toOptionalDouble, UbSegment::toOptional).forGetter(UbSegment::radius),
            UbEffect.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(UbSegment::effects),
            ResourceLocation.CODEC.optionalFieldOf("damageType").forGetter(UbSegment::damageType),
            Codec.DOUBLE.optionalFieldOf("critMultiplier")
                    .xmap(UbSegment::toOptionalDouble, UbSegment::toOptional)
                    .forGetter(UbSegment::critMultiplier),
            Codec.DOUBLE.optionalFieldOf("perExtraTarget", 0.0).forGetter(UbSegment::perExtraTarget),
            // Additive and last, for the reason every field of this record is: a pack written before this existed
            // keeps decoding and its clause keeps rolling for its crits, which is what it meant.
            Codec.BOOL.optionalFieldOf("forcedCrit", false).forGetter(UbSegment::forcedCrit),
            Codec.DOUBLE.optionalFieldOf("armourPierce")
                    .xmap(UbSegment::toOptionalDouble, UbSegment::toOptional)
                    .forGetter(UbSegment::armourPierce),
            // 「随损失的生命值提升」: units added over one full bar of lost health. Additive and last for the
            // reason every field above is — a pack written before it keeps decoding, and a clause that does not
            // scale answers 0 for every body, which is exactly the full-health value the old notes shipped.
            Codec.DOUBLE.optionalFieldOf("lostHealthBonus")
                    .xmap(UbSegment::toOptionalDouble, UbSegment::toOptional)
                    .forGetter(UbSegment::lostHealthBonus)
    ).apply(instance, UbSegment::new));

    public UbSegment {
        damage = damage == null ? OptionalDouble.empty() : damage;
        radius = radius == null ? OptionalDouble.empty() : radius;
        effects = effects == null ? List.of() : List.copyOf(effects);
        damageType = damageType == null ? Optional.empty() : damageType;
        critMultiplier = critMultiplier == null ? OptionalDouble.empty() : critMultiplier;
        if (target == null) {
            // A clause nobody can place. Defaulting to NEAREST would hide the defect behind a plausible-looking
            // single-target hit; the codec refuses it first (target is a required field), and this keeps a
            // hand-built value from throwing a NullPointerException inside a cast.
            target = UbTargetRule.NEAREST;
        }
        if (!Double.isFinite(perExtraTarget)) {
            perExtraTarget = 0.0;
        }
        // The share of the target's armour this clause ignores, clamped once here for the same reason every other
        // authored number on this record is: a hostile value must degrade to "no pierce" rather than travel into a
        // damage sequence. {@code ArmourPierce.sanitise} is the one reading of the number; this only drops the
        // values that mean "nothing to do" so an ordinary clause carries an empty optional.
        armourPierce = armourPierce == null ? OptionalDouble.empty() : armourPierce;
        double share = armourPierce.isPresent() ? armourPierce.getAsDouble() : 0.0;
        armourPierce = share > 0.0 && Double.isFinite(share) ? OptionalDouble.of(Math.min(1.0, share))
                : OptionalDouble.empty();
        // Same rule for the scaling term: a hostile or meaningless value degrades to "does not scale" rather
        // than travelling into a damage sequence. {@code LostHealth.bonusUnits} is the one reading of the number.
        lostHealthBonus = lostHealthBonus == null ? OptionalDouble.empty() : lostHealthBonus;
        double scaling = lostHealthBonus.isPresent() ? lostHealthBonus.getAsDouble() : 0.0;
        lostHealthBonus = scaling > 0.0 && Double.isFinite(scaling) ? OptionalDouble.of(scaling)
                : OptionalDouble.empty();
    }

    /**
     * The pre-{@code lostHealthBonus} canonical shape: a clause whose amount does not grow as the caster bleeds.
     *
     * <p>Kept so every existing construction site — the settlement, the plan tests, the codec round-trip — reads
     * unchanged, and so "does not scale" is expressed by <b>omission</b> rather than by a trailing empty optional
     * sprinkled through unrelated code.
     */
    public UbSegment(OptionalDouble damage, int hits, UbTargetRule target, OptionalDouble radius,
            List<UbEffect> effects, Optional<ResourceLocation> damageType, OptionalDouble critMultiplier,
            double perExtraTarget, boolean forcedCrit, OptionalDouble armourPierce) {
        this(damage, hits, target, radius, effects, damageType, critMultiplier, perExtraTarget, forcedCrit,
                armourPierce, OptionalDouble.empty());
    }

    /** The plain damage clause: an amount, its hit count, its rule, and the rule's reach when it has one. */
    public UbSegment(double damage, int hits, UbTargetRule target, OptionalDouble radius) {
        this(OptionalDouble.of(damage), hits, target, radius, List.of(), Optional.empty(),
                OptionalDouble.empty(), 0.0, false, OptionalDouble.empty(), OptionalDouble.empty());
    }

    /** A damage clause that also applies effects to the bodies its own rule selected. */
    public UbSegment(double damage, int hits, UbTargetRule target, OptionalDouble radius,
            List<UbEffect> effects) {
        this(OptionalDouble.of(damage), hits, target, radius, effects, Optional.empty(),
                OptionalDouble.empty(), 0.0, false, OptionalDouble.empty(), OptionalDouble.empty());
    }

    /** An <b>effect clause</b>: selects its bodies, applies {@code effects}, deals no damage of its own. */
    public UbSegment(UbTargetRule target, OptionalDouble radius, List<UbEffect> effects) {
        this(OptionalDouble.empty(), 1, target, radius, effects, Optional.empty(),
                OptionalDouble.empty(), 0.0, false, OptionalDouble.empty(), OptionalDouble.empty());
    }

    /**
     * A damage clause that also states one of the two <b>hit rules</b> — 「必定暴击」 and 「无视 50 物理防御力」.
     *
     * <p>A convenience constructor rather than making a test or a caller spell both {@code OptionalDouble.empty()}
     * tails: the meaning of "absent" is then stated once, here.
     *
     * @param damage       damage per application
     * @param hits         how many times it lands per target
     * @param target       who it lands on
     * @param forcedCrit   whether every application crits regardless of the caster's chance
     * @param armourPierce the share of the target's armour it ignores, empty for none
     */
    public UbSegment(double damage, int hits, UbTargetRule target, boolean forcedCrit,
            OptionalDouble armourPierce) {
        this(OptionalDouble.of(damage), hits, target, OptionalDouble.empty(), List.of(), Optional.empty(),
                OptionalDouble.empty(), 0.0, forcedCrit, armourPierce, OptionalDouble.empty());
    }

    /**
     * {@return whether this clause deals any damage at all}
     *
     * <p>The one test the planner and the book share, so "0 damage", "a hostile number" and "no amount
     * declared" cannot disagree between them: {@code hits <= 0}, a missing amount, and a non-positive or
     * non-finite amount all answer {@code false}, which is the same reading {@code UbDamage.plan} applies to a
     * tier's damage since the first version of it.
     */
    public boolean dealsDamage() {
        if (hits <= 0 || damage.isEmpty()) {
            return false;
        }
        double amount = damage.getAsDouble();
        return amount > 0.0 && Double.isFinite(amount);
    }

    /** {@return whether this clause carries effects of its own} */
    public boolean carriesEffects() {
        return !effects.isEmpty();
    }

    /**
     * {@return this clause's amount for a cast that reached {@code targets} bodies, before crit and magic}
     *
     * <p>The one place the target-count scaling is applied: {@code amount + perExtraTarget × (targets - 1)}.
     * {@link #perExtraTarget()} says why it is expressed per <em>extra</em> target rather than per target, and
     * why it is here rather than on the tier.
     *
     * @param targets how many bodies this clause's own rule selected; {@code <= 1} means no scaling
     */
    public double amountFor(int targets) {
        double amount = damage.orElse(0.0);
        int extra = targets - 1;
        return extra > 0 ? amount + perExtraTarget * extra : amount;
    }

    private static OptionalDouble toOptionalDouble(Optional<Double> value) {
        return value.isPresent() ? OptionalDouble.of(value.get()) : OptionalDouble.empty();
    }

    private static Optional<Double> toOptional(OptionalDouble value) {
        return value.isPresent() ? Optional.of(value.getAsDouble()) : Optional.empty();
    }
}
