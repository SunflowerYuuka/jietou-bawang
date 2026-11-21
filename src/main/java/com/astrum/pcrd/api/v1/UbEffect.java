package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * One <b>generic, tiered effect</b> an ultimate applies — 「味方全体の物理攻撃力を特大アップ」,
 * 「自分の物理・魔法防御力を大アップ」, 「恐慌」.
 *
 * <h2>Why an ultimate needs its own effect list</h2>
 * An ultimate is not a hook: it has no {@code Trigger}, fires on a key press rather than on a landed
 * swing, and settles its own damage over a set of victims discovered at cast time. So an on-cast effect
 * cannot hang off {@code effects[]} (there is no trigger to hang it on) and cannot be a
 * {@link EffectHook} — that is precisely the "invent a fake trigger" move {@code UbDamage}'s javadoc
 * rejects.
 *
 * <p>Before the generic effect kernel, the schema therefore had one bespoke field per clause it could
 * express: {@code attackBuff}, {@code heal}, {@code allyEffect}, {@code victimEffect}. Each carried its
 * own numbers, its own amplitude encoding and its own executor, and each was a different answer to the
 * same question ("what does casting this ultimate do to people"). This record replaces all four with one
 * shape: a {@link Scope}, an {@link EffectType}, and the <b>same</b> {@link TieredParams} every tiered
 * hook uses.
 *
 * <h2>What that buys</h2>
 * <ul>
 *   <li><b>One ladder.</b> What tier 3 of a party attack buff is worth is {@link EffectTiers}' table,
 *       not a number in this file. A pack says {@code "tier": 3} exactly as it would on a hook.</li>
 *   <li><b>One executor.</b> {@code UbTierEffects} converts this record into the {@link EffectHook}
 *       record of the same type and hands it to {@code EffectExecutors}, so the arm that applies
 *       {@code buff_attack_area} for an ultimate is literally the arm that applies it for a swing. No
 *       second copy of "who is in the party", no second copy of a tier's arithmetic.</li>
 *   <li><b>Nothing invented.</b> A clause nobody has a mechanism for is simply not declared, rather
 *       than being {@code extra} free text that looks like it does something.</li>
 * </ul>
 *
 * <h2>The one thing this does not express</h2>
 * {@code teamChargeBonus} stays on {@link UbTier}: it is a plain charge grant (an {@code area_charge}
 * in hook terms), it is not tiered, and it is still unimplemented. Folding it in here would suggest a
 * rung ladder it does not have.
 *
 * @param scope      who receives it — see {@link Scope}; the two receivers sets a cast has
 * @param type       which generic effect; one of the <b>tiered</b> types, since {@link #params()} carries a
 *                   tier — or {@link EffectType#ALLY_EFFECT} together with {@link #vanillaEffect} for a
 *                   named effect (see that component)
 * @param params     the tier, plus the duration and reach the effect has
 * @param damageType the damage type, for the effects that deal damage ({@code percent_damage}); empty
 *                   means {@code astrum:hook_damage}. A caster whose ultimate is magical says
 *                   {@code astrum:magic}, which is the marker the magic defence buff reduces
 * @param counter    the grant shape for {@link EffectType#COUNTER}, which is <b>not tiered</b> and so
 *                   cannot say its magnitude in {@link #params()}; empty for every other effect
 * @param vanillaEffect the <b>named vanilla (or mod) mob effect</b> an {@link EffectType#ALLY_EFFECT} clause
 *                   grants to the caster and her party — 「风之刃」 is one, and before this field an ultimate had
 *                   no way to name an effect by id at all. Empty for every tiered effect. The duration is
 *                   {@link #params()}'s {@code seconds} (the same {@code TieredParams} clock every party
 *                   effect reads) and the level is {@link #amplifier}, exactly as {@code ally_effect}'s hook
 *                   form carries them. It is an optional field on <em>this</em> record rather than a new
 *                   effect type per state: the effect registry already holds the states, and 「which state」
 *                   is data.
 * @param amplifier  the level of {@link #vanillaEffect}, never scaled; ignored (and {@code 0}) for a tiered
 *                   effect
 * @param percent    the <b>non-tiered</b> magnitude for {@link EffectType#CRIT_DAMAGE_UP}: the increase in crit
 *                   damage, in percent ({@code 28.0} = +28%), so 千歌's 6★ clause keeps the number the wiki
 *                   states instead of being quantised into a rung. Empty for every other effect — the same
 *                   reasoning {@code counter} and {@code vanillaEffect} give one field up: a magnitude that is
 *                   not a rung needs its own component, and an invented tier of "28%" would be a second,
 *                   competing scale for a number the source gives exactly. The duration and reach still travel
 *                   in {@link #params()} ({@code seconds} / {@code radius}), which is every party effect's clock
 * @param chance how likely this one clause is to fire, {@code 1.0} for "every cast"
 *
 *                   <h2>Why an ultimate's effect needs a chance at all</h2>
 *                   铃莓's UB splits one debuff into two branches — 33% and 67%, each with its own magnitude
 *                   (bwiki: {@code 0.855+0.855L} and {@code 0.4275+0.4275L} of physical defence) — and both
 *                   branches clamped to the same rung of {@code defence_down}'s ladder, so the data wrote one
 *                   clause and recorded the other in {@code extra}. Without this field the two branches could
 *                   not be <em>said</em>: one of them would be a second, unconditional debuff, i.e. the
 *                   victim would lose both amounts at once. A hook has carried {@code chance} all along
 *                   ({@code EffectHook.chance()}); this is the same field for the ultimate's list, rolled once
 *                   per clause per victim at settlement, so the branches are mutually exclusive and the
 *                   source's probability is in the data rather than in a sentence.
 * @param launch the <b>height</b> a {@link EffectType#KNOCKBACK} clause lifts its victim to, in blocks; empty
 *               for every clause that declares none
 *
 *               <h2>Why the launch height is a field of its own</h2>
 *               绫音's ultimate is 「击退距离 2160 / 击飞高度 800」 — two numbers that move in two directions.
 *               The knockback rung is a vanilla <em>strength</em>, and vanilla caps the vertical component of
 *               that strength at {@code 0.4} whatever it is: at tier 5 the body still leaves the ground at the
 *               same speed and still peaks at the same ~0.75 blocks. So the height is not a stronger rung of
 *               anything — it is a separate quantity, and the tier cannot carry it. It travels here instead,
 *               in the one unit the field name states (blocks), and {@code Knockbacks} turns it into the
 *               initial upward velocity that reaches it.
 *               <p><b>Why the ultimate's record and not the hook's.</b> The clause is an ultimate's; a hook's
 *               {@code knockback} carries its rung and no shipped skill declares a height. A second field on
 *               {@code EffectHook.Knockback} would be a channel with no data behind it, and this project's rule
 *               is that a field exists because a clause needs it.
 */
@ApiStatus.Experimental
public record UbEffect(Scope scope, EffectType type, TieredParams params,
                       Optional<ResourceLocation> damageType, Optional<UbCounterGrant> counter,
                       Optional<ResourceLocation> vanillaEffect, int amplifier, OptionalDouble percent,
                       double chance, Optional<UbEffect.ChargeRate> chargeRate,
                       OptionalDouble launch) {

    public static final Codec<UbEffect> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Scope.CODEC.fieldOf("scope").forGetter(UbEffect::scope),
            // The same dispatch key a hook uses, so a pack author names an effect the same way in both
            // places — and so a value this build does not know fails to decode rather than being read as
            // some other effect.
            EffectType.CODEC.fieldOf("effect").forGetter(UbEffect::type),
            TieredParams.CODEC.fieldOf("effectParams").forGetter(UbEffect::params),
            ResourceLocation.CODEC.optionalFieldOf("damageType").forGetter(UbEffect::damageType),
            // 计数 is the one party effect whose magnitude is a FORMULA over the caster's current tally
            // (「追加【5+计数器枚数】个」) rather than a rung, so it carries its own block. Empty for every
            // other effect — see the record javadoc for why this could not be a rung.
            UbCounterGrant.CODEC.optionalFieldOf("counter").forGetter(UbEffect::counter),
            // The named-effect half of `ally_effect`, additive and last: a pack written before it existed
            // keeps decoding with no vanilla effect and behaves exactly as it did.
            ResourceLocation.CODEC.optionalFieldOf("vanillaEffect").forGetter(UbEffect::vanillaEffect),
            Codec.INT.optionalFieldOf("amplifier", 0).forGetter(UbEffect::amplifier),
            // The non-tiered magnitude, additive and last for the same reason the two fields above are: a pack
            // written before it existed keeps decoding with no percent and behaves exactly as it did.
            Codec.DOUBLE.optionalFieldOf("percent").xmap(
                            value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                            value -> value.isPresent() ? Optional.of(value.getAsDouble()) : Optional.empty())
                    .forGetter(UbEffect::percent),
            // Additive and last, for the same reason the two fields above are: a pack written before it existed
            // decodes with chance 1.0 and fires every cast, which is exactly what it did before.
            Codec.DOUBLE.optionalFieldOf("chance", 1.0).forGetter(UbEffect::chance),
            // The two-number block {@link EffectType#COUNTER_CHARGE_REGEN} needs, additive and last for the same
            // reason every field above it is. Its rate is a formula over a live tally rather than a rung, so the
            // tier cannot carry it — the same argument `counter` gives one field up.
            ChargeRate.CODEC.optionalFieldOf("chargeRate").forGetter(UbEffect::chargeRate),
            // 「击飞高度 800」 — the height a knockback lifts the victim to, additive and last for the same reason
            // every field above it is: a pack written before it existed decodes with no launch and behaves
            // exactly as it did (vanilla's own 0.4 pop and nothing more).
            Codec.DOUBLE.optionalFieldOf("launch").xmap(
                            value -> value.map(OptionalDouble::of).orElse(OptionalDouble.empty()),
                            value -> value.isPresent() ? Optional.of(value.getAsDouble()) : Optional.empty())
                    .forGetter(UbEffect::launch)
    ).apply(instance, UbEffect::new));

    public UbEffect {
        damageType = damageType == null ? Optional.empty() : damageType;
        counter = counter == null ? Optional.empty() : counter;
        vanillaEffect = vanillaEffect == null ? Optional.empty() : vanillaEffect;
        params = params == null ? TieredParams.FIRST : params;
        amplifier = Math.max(0, amplifier);
        percent = percent == null ? OptionalDouble.empty() : percent;
        // A hostile probability is *no* chance rather than an exception: this is decoded from a datapack, and a
        // clause that cannot be rolled is better skipped than rolled with a NaN (which a `random < chance` test
        // answers false for, i.e. the same "never" — but stated rather than implied).
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
            chance = 0.0;
        }
        chargeRate = chargeRate == null ? Optional.empty() : chargeRate;
        // A launch height that is not a positive distance is "no launch": the same one-directional reading the
        // chance field gives a hostile probability, and the reading that keeps a mistyped height from lifting
        // the victim downwards.
        launch = launch == null || !launch.isPresent() || !Double.isFinite(launch.getAsDouble())
                || launch.getAsDouble() <= 0.0 ? OptionalDouble.empty() : launch;
    }

    /** The four-field form a pack written without a counter grant uses. */
    public UbEffect(Scope scope, EffectType type, TieredParams params,
            Optional<ResourceLocation> damageType) {
        this(scope, type, params, damageType, Optional.empty(), Optional.empty(), 0, OptionalDouble.empty(),
                1.0, Optional.empty(), OptionalDouble.empty());
    }

    /** The three-field form a pack written without a damage type uses. */
    public UbEffect(Scope scope, EffectType type, TieredParams params) {
        this(scope, type, params, Optional.empty(), Optional.empty(), Optional.empty(), 0, OptionalDouble.empty(),
                1.0, Optional.empty(), OptionalDouble.empty());
    }

    /** {@return the five-field form a pack written without a counter, a named effect or a percent uses} */
    public UbEffect(Scope scope, EffectType type, TieredParams params,
            Optional<ResourceLocation> damageType, Optional<UbCounterGrant> counter,
            Optional<ResourceLocation> vanillaEffect, int amplifier, OptionalDouble percent) {
        this(scope, type, params, damageType, counter, vanillaEffect, amplifier, percent, 1.0, Optional.empty(),
                OptionalDouble.empty());
    }

    /**
     * {@return why this effect cannot be applied to its scope, or empty when the pair is legal}
     *
     * <h2>Why the check lives in the record</h2>
     * A scope and an effect are two halves of one promise, and a pack can get the pair wrong: a party buff
     * declared on the victims would apply 「味方全体の攻撃力アップ」 to the enemy, and a debuff declared on
     * the party would apply it to the caster's own side. Neither can be caught by the codec — both halves
     * are individually valid — and neither is visible in play until somebody notices the wrong side is
     * buffed. So the rule is a pure function of this record, which makes it assertable without a game and
     * lets both the loader's WARN and the resource test name the same problem.
     *
     * <p>The answer is a message rather than a boolean because its two readers — a pack author reading a
     * WARN and a test's failure output — both need the fix, not the verdict.
     *
     * <p>Total and never throwing: {@code null} on either half is a legal question with the answer "no",
     * because this is reached from a decode-and-log path where a throw would be worse than a wrong pair.
     */
    public Optional<String> mismatch() {
        if (scope == null || type == null) {
            return Optional.of("an ultimate effect needs both a scope and an effect");
        }
        // 「名指しの原版/本模组效果」: the one shape that is not a tiered effect. It names its effect by id and
        // its level by `amplifier`, so the pair it must agree with is (scope = ally, type = ally_effect) —
        // the same two halves `ally_effect`'s hook form has. Both mismatches are refused rather than guessed:
        // a named effect on the victims would buff the enemy, and a named effect declared as a tiered type
        // would be applied by the tier kernel with no id to apply.
        if (vanillaEffect.isPresent()) {
            if (type != EffectType.ALLY_EFFECT) {
                return Optional.of("a named effect (vanillaEffect) must be declared as effect "
                        + EffectType.ALLY_EFFECT.getSerializedName() + ", not "
                        + type.getSerializedName() + "; the id travels in vanillaEffect");
            }
            return scope == Scope.ALLY ? Optional.empty()
                    : Optional.of("a named effect reaches the caster and her party (「味方全体」/「自分」), so "
                            + "its scope must be \"ally\", not \"" + scope.getSerializedName() + "\"");
        }
        if (type == EffectType.ALLY_EFFECT) {
            return Optional.of("ally_effect needs a vanillaEffect naming the mob effect id it grants; "
                    + "without one there is nothing to apply (a tiered effect carries its rung in "
                    + "effectParams instead)");
        }
        // The non-tiered magnitude, checked as a pair like every other half-and-half field above: a percent on a
        // tiered effect would be a second, competing scale for a magnitude the ladder already owns, and a
        // crit-damage buff with no percent would be a buff that grants +0% while showing on the HUD.
        if (percent.isPresent() && !percentCarries(type)) {
            return Optional.of("percent is the magnitude of " + EffectType.CRIT_DAMAGE_UP.getSerializedName()
                    + ", " + EffectType.CRIT_CHANCE_UP.getSerializedName() + ", "
                    + EffectType.CRIT_CHANCE_DOWN.getSerializedName() + " and "
                    + EffectType.CRIT_DAMAGE_DOWN.getSerializedName()
                    + " alone; a " + type.getSerializedName()
                    + " states its magnitude in effectParams (a tier) or in its own block");
        }
        if (percentCarries(type) && percent.isEmpty()) {
            return Optional.of(type.getSerializedName() + " needs a percent (28.0 for 「28% 暴击伤害」, 2.5 for "
                    + "「2.5% 暴击率」, 18.0 for 「降低 18 个百分点的暴击率」); without one the effect would be "
                    + "granted at 0% and change nothing");
        }
        if (chargeRate.isPresent() && type != EffectType.COUNTER_CHARGE_REGEN) {
            return Optional.of("chargeRate is the magnitude of "
                    + EffectType.COUNTER_CHARGE_REGEN.getSerializedName()
                    + " alone; a " + type.getSerializedName()
                    + " states its magnitude in effectParams (a tier) or in its own block");
        }
        // 「击飞高度」 is the half of a knockback the rung cannot carry, so it is checked as a pair like every
        // other half-and-half field: a height on an effect that does not move the body would be a number nothing
        // reads, and a knockback whose height was silently dropped would be the gap this field closes reopened
        // in the data. Both are refused rather than guessed.
        if (launch.isPresent() && type != EffectType.KNOCKBACK) {
            return Optional.of("launch is the height a "
                    + EffectType.KNOCKBACK.getSerializedName() + " lifts its victim to, so it belongs to that "
                    + "effect alone, not to " + type.getSerializedName()
                    + "; every other victim state has no height to declare");
        }
        if (type == EffectType.COUNTER_CHARGE_REGEN && chargeRate.isEmpty()) {
            return Optional.of("counter_charge_regen needs a chargeRate block, e.g. "
                    + "{\"base\": 1.0, \"perMark\": 0.1}; without one it would pay nothing a second");
        }
        if (chance <= 0.0) {
            return Optional.of("chance " + chance + " means this clause never fires; declare the clause only "
                    + "when it can happen, or raise the chance above 0");
        }
        if (type.reachesParty()) {
            return scope == Scope.ALLY ? Optional.empty()
                    : Optional.of(type.getSerializedName() + " reaches the caster and her party "
                            + "(「味方全体」), so its scope must be \"ally\", not \""
                            + scope.getSerializedName() + "\"");
        }
        if (type.reachesVictim()) {
            return scope == Scope.VICTIM ? Optional.empty()
                    : Optional.of(type.getSerializedName() + " is a state on the body it lands on (「敵」), "
                            + "so its scope must be \"victim\", not \"" + scope.getSerializedName() + "\"");
        }
        return Optional.of(type.getSerializedName() + " cannot be applied as an ultimate effect: party "
                + "effects are " + names(EffectType.partyScoped()) + ", victim effects are "
                + names(EffectType.victimScoped()) + ". Damage belongs on the tier's own hits "
                + "(hits/damage/damageType), not on an effect list.");
    }

    /**
     * The two numbers {@link EffectType#COUNTER_CHARGE_REGEN}'s formula has, in the ultimate's list.
     *
     * <h2>Why a block and not a tier</h2>
     * 「每秒恢复自身【10+「冰龙之印」】技能值」 is a <b>base plus a per-mark step</b>, and the mark count moves
     * during the grant (技能1 spends one a swing). A rung is a fixed magnitude, so it cannot express the clause
     * — the same reason {@code counter} carries its own block. Both numbers are <b>charge points a second</b> at
     * the project's 1 point = 10 TP: 雪菲's 10 TP is {@code 1.0}, and one 冰龙之印 a second is {@code 0.1}.
     *
     * @param base    charge points a second before any mark
     * @param perMark charge points a second for each mark the holder carries, read live every tick
     */
    @ApiStatus.Experimental
    public record ChargeRate(double base, double perMark) {
        public static final Codec<ChargeRate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.optionalFieldOf("base", 0.0).forGetter(ChargeRate::base),
                Codec.DOUBLE.optionalFieldOf("perMark", 0.0).forGetter(ChargeRate::perMark)
        ).apply(instance, ChargeRate::new));

        public ChargeRate {
            if (!Double.isFinite(base) || base < 0.0) {
                base = 0.0;
            }
            if (!Double.isFinite(perMark) || perMark < 0.0) {
                perMark = 0.0;
            }
        }
    }

    /** {@return whether a percent is this type's magnitude rather than a tier} */
    private static boolean percentCarries(EffectType type) {
        return type == EffectType.CRIT_DAMAGE_UP || type == EffectType.CRIT_CHANCE_UP
                // The two victim-side crit debuffs read the same field in the same units (percentage points of
                // chance / percent of the crit multiplier): 未奏希's ultimate is 「降低敌方全体【…】物理暴击 …、
                // 暴击时造成的伤害降低 25%」, and a rung would quantise both numbers the wiki states exactly.
                || type == EffectType.CRIT_CHANCE_DOWN || type == EffectType.CRIT_DAMAGE_DOWN;
    }

    /** {@return the set's members, as sorted names, for the message above} */
    private static String names(Set<EffectType> types) {
        return types.stream().map(EffectType::getSerializedName).sorted().toList().toString();
    }

    /**
     * Who an ultimate's effect reaches.
     *
     * <h2>Two sets, and why they are not the same one</h2>
     * <ul>
     *   <li>{@link #ALLY} — the caster and her party, selected by {@code AllySelector} through
     *       {@code AreaBuffs} / {@code PercentHeals}, exactly as a tiered hook's receivers are. This is
     *       「味方全体」: コッコロ's Aurora, ペコリーヌ's defence buff, 姬塔's party attack boost.</li>
     *   <li>{@link #VICTIM} — every body the ultimate actually <b>hit</b>, i.e. the plan's victims that
     *       took real damage. This is 「敵全体に…防御力大ダウン + 恐慌」: ｷｬﾙ's 6★ アビスバースト. It is
     *       gated on {@code hurt()} answering true, so an ultimate that a target dodged or was immune to
     *       leaves no debuff behind.</li>
     * </ul>
     *
     * <p>They are separate values rather than one list with a per-effect rule because the difference is
     * not a property of the effect — {@code defence_down} is a victim effect and
     * {@code buff_attack_area} is an ally effect, but the schema should not have to know that — it is a
     * property of <b>which set of bodies the author meant</b>. Recording it in the data is what lets the
     * reader see the intent, and it is what makes a mismatch (a party buff on the victims) a reported
     * error instead of a silently half-applied skill.
     */
    @ApiStatus.Experimental
    public enum Scope implements StringRepresentable {
        /** The caster and her party, through the one ally rule. */
        ALLY,
        /** Every body the ultimate hit, after the hit really landed. */
        VICTIM;

        public static final Codec<Scope> CODEC = StringRepresentable.fromEnum(Scope::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
