package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * One tier of a character's UB (ultimate burst).
 *
 * <h2>Both tiers of one character usually share {@code hits}</h2>
 * The 6★ ultimate is a <em>strengthening</em> of the 5★ one, not a replacement: 星河一天 keeps 疾风剑雨's
 * four hits and adds 「特大幅度提升自己的物理攻击 18 秒」. So the shipped character declares
 * {@code hits: 4} on <b>both</b> tiers, and what the enhanced tier adds is damage and effects — never a
 * different hit count. The codec does not enforce that (a pack may legitimately ship two unrelated
 * tiers), so {@code DjeetaResourceTest} pins it for this character.
 *
 * <h2>The effects are the generic system's, not four bespoke fields</h2>
 * This record used to carry {@code attackBuff}, {@code heal}, {@code allyEffect} and {@code victimEffect}:
 * four shapes, four sets of numbers, four executors, and four different answers to "what does casting
 * this ultimate do to people". Each of them is now one entry in {@link #effects} — a {@link UbEffect},
 * which names a generic {@link EffectType} and the same {@link TieredParams} a hook uses. So a party
 * attack buff is {@code buff_attack_area} with a tier on both a swing and an ultimate, and the rung means
 * the same thing in both places because {@code EffectTiers} owns it.
 *
 * <p>The removed fields were dropped rather than kept as deprecated aliases, deliberately: two ways to
 * write one clause is how one of them silently stops being applied. A pack written against the old shape
 * fails to decode and is reported by {@code CharacterRegistry}'s lenient codec (one WARN naming the
 * file), which is the honest failure for a schema change of this size.
 *
 * @param animation       animation name played by the showcase
 * @param hits            number of damage ticks
 * @param damage          damage per hit
 * @param radius          area radius, empty for a single-target UB. It is <b>not</b> the reach of the
 *                        effects: each {@link UbEffect} carries its own {@code radius} in
 *                        {@link TieredParams}, because 「自分」 (0), the party default (absent) and the
 *                        ultimate's own radius are three different answers and only the data knows which
 *                        a given clause means
 * @param damageType      the damage type this tier's hits are filed under, empty for
 *                        {@code astrum:ub_damage}. A <b>magical</b> ultimate says {@code astrum:magic}:
 *                        that is the marker {@link EffectType#BUFF_MAGIC_DEFENCE_AREA} reduces, and
 *                        without it a mage's ultimate would be indistinguishable from a sword's — see
 *                        that value's javadoc for why Minecraft cannot answer this itself
 * @param effects         the generic effects this tier applies, in the order it applies them
 * @param teamChargeBonus charge granted to allies, empty for none. Deliberately still a field of its own:
 *                        it is an untiered charge grant, and folding it into {@link #effects} would
 *                        suggest a rung ladder it does not have
 * @param extra           free-form tag for a clause this build still cannot express, e.g.
 *                        {@code barrier_break_plus_max_hp_proportional}. Kept so a gap stays <b>visible in
 *                        the data</b> rather than being silently dropped — see
 *                        {@code docs/balance-derivation.md}'s "not implemented" table
 * @param forcedCrit      「必定暴击」 for a tier written in the <b>shorthand</b>: every hit of {@code hits} crits
 *                        regardless of the caster's {@code astrum:crit_chance}. It travels onto the implicit clause
 *                        ({@link #damageSegments()}) exactly as {@code damageType} and {@code critMultiplier} do, so
 *                        the settlement reads one shape. A tier that lists {@code segments} states it per clause and
 *                        this field is not read — the clauses are the truth, as they are for every other number.
 *                        Default {@code false}: 「第一次 Hit 必定暴击」 is what 克莉丝提娜's text says, and every
 *                        other shipped ultimate rolls for its crits
 * @param armourPierce    「无视 50 物理防御力」 for a shorthand tier: the share of the target's armour its hits
 *                        ignore, {@code 0.5} for the shipped clause; empty for none. Same rule and same
 *                        travel-onto-the-implicit-clause reasoning as {@code forcedCrit}, and the same arithmetic
 *                        as a clause's ({@code ArmourPierce}: vanilla's non-linear armour curve inverted, so the
 *                        hit lands what it would have landed against a body with {@code (1 - share)} of its armour)
 * @param segments        the tier's damage clauses, one per clause of its text — see {@link UbSegment}. Empty
 *                        means "this tier's damage is the shorthand above": {@code hits × damage} on the
 *                        nearest target, or on everything inside {@code radius}, exactly as before this field
 *                        existed. <b>Non-empty means the shorthand is not read at all</b>, and the tier's
 *                        damage is the segments' — which is why {@code hits} and {@code damage} are optional
 *                        fields now: a tier that lists its clauses has no single number to repeat at the top,
 *                        and repeating one there would be a second copy of a fact that lives in a segment
 *                        <p><b>Why the list is the general form and the triple is the shorthand.</b> Six
 *                        shipped clauses are two numbers on two kinds of receiver (日和莉), on the second or
 *                        the farthest body (美美, 紬希), around another body (美美 6★), or scaled by how many
 *                        were hit (紬希 6★). One {@code damage} and one {@code radius} can spell exactly one
 *                        of those, so the six were recorded as unimplemented. A list of
 *                        {@link UbSegment}s spells all of them, and the old triple is <b>defined</b> as one
 *                        implicit segment ({@link #damageSegments()}) rather than kept as a second planning
 *                        branch — that is what makes "the 22 shipped characters play unchanged" structural
 *                        instead of hopeful: there is no old code path left to keep in step
 * @param critMultiplier  this ultimate's <b>own</b> crit multiplier, replacing the caster's global
 *                        {@code astrum:crit_damage} for the hits of <b>this tier</b>; empty means the
 *                        global applies. 「本技能出现暴击时，造成的伤害值从 2 倍提升至 4 倍」 is
 *                        {@code 4.0} here — a <b>replacement</b>, not a bonus added to the global: 4×
 *                        means a crit of this ultimate deals {@code 4.0 ×} the authored hit, whether the
 *                        caster's global is 1.5 or 2.0. The number is per <b>tier</b> for the same reason
 *                        {@code name} is: 鏡華 ships the clause on her 6★ ultimate only, and her 5★
 *                        ultimate must keep critting at the global rate.
 *                        <p><b>Why it is an optional last component and not a required one.</b> A pack
 *                        written before this field existed keeps decoding unchanged and files its
 *                        ultimate's crits under the global — exactly the degradation {@code damageType}'s
 *                        comment gives, and the reason every additive field of this record (damageType,
 *                        teamChargeBonus, extra, name) sits after the shape that existed before it.
 *                        <p><b>Why it lives here rather than on {@code CharacterDef}.</b> That record is
 *                        at sixteen {@code RecordCodecBuilder.group} components, which is the ceiling, and
 *                        a multiplier of one skill is a property of that skill's tier anyway. A second
 *                        copy in a per-character field would be the duplication this project forbids.
 */
@ApiStatus.Experimental
public record UbTier(String animation, int hits, double damage, OptionalDouble radius,
                     Optional<ResourceLocation> damageType, List<UbEffect> effects,
                     OptionalDouble teamChargeBonus, Optional<String> extra, Optional<String> name,
                     OptionalDouble critMultiplier, List<UbSegment> segments, boolean forcedCrit,
                     OptionalDouble armourPierce) {

    public static final Codec<UbTier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("animation").forGetter(UbTier::animation),
            // Optional <b>only</b> because a tier that lists its clauses in `segments` has no single hit count
            // and no single amount: its numbers live in the clauses, and requiring a top-level copy would make
            // a pack state one number twice (and a reader guess which of the two wins). A tier that lists no
            // segments writes them exactly as before, and the defaults are the meanings that shape always had:
            // one hit, and "no damage number", which is what `"damage": 0.0` already said for the shipped
            // heal-only ultimates (千歌, 可可萝, 望, 优衣) — so nothing that used to decode stops decoding.
            Codec.INT.optionalFieldOf("hits", 1).forGetter(UbTier::hits),
            Codec.DOUBLE.optionalFieldOf("damage", 0.0).forGetter(UbTier::damage),
            Codec.DOUBLE.optionalFieldOf("radius").xmap(UbTier::toOptionalDouble, UbTier::toOptional)
                    .forGetter(UbTier::radius),
            // Additive: a pack written before this field existed keeps loading and files its hits under
            // `astrum:ub_damage`, which is what every shipped character meant before it.
            ResourceLocation.CODEC.optionalFieldOf("damageType").forGetter(UbTier::damageType),
            UbEffect.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(UbTier::effects),
            // Also additive, and the last field for that reason: a pre-{@code effects} pack keeps decoding
            // (with an empty list) and simply applies nothing — the same degradation the extra fields made
            // when they were introduced.
            Codec.DOUBLE.optionalFieldOf("teamChargeBonus").xmap(UbTier::toOptionalDouble, UbTier::toOptional)
                    .forGetter(UbTier::teamChargeBonus),
            Codec.STRING.optionalFieldOf("extra").forGetter(UbTier::extra),
            // The ultimate's own name, e.g. 疾风剑雨 / 星河漫天. Additive like the two before it: a pack that
            // names none keeps loading and the roster simply shows no ultimate line. It belongs here rather
            // than in a SkillLadder because the two UB rungs already exist — a third copy of "which ultimate
            // is this" is exactly the duplication this project forbids.
            Codec.STRING.optionalFieldOf("name").forGetter(UbTier::name),
            // The per-skill crit multiplier, additive and last for the same reason: every pack written before
            // task 55 omits it, keeps decoding, and keeps critting at the caster's global `astrum:crit_damage`
            // — which is exactly what those packs meant before this field existed.
            Codec.DOUBLE.optionalFieldOf("critMultiplier")
                    .xmap(UbTier::toOptionalDouble, UbTier::toOptional)
                    .forGetter(UbTier::critMultiplier),
            // The last component, for the reason every additive field of this record is: a pack written before
            // it keeps decoding (with an empty list) and keeps the shorthand above, which is exactly what it
            // meant. A tier that does list clauses says so once, here.
            UbSegment.CODEC.listOf().optionalFieldOf("segments", List.of()).forGetter(UbTier::segments),
            // The two per-hit rules of the shorthand, additive and last for the reason every field above is: a
            // pack written before them keeps decoding and its tier keeps rolling for its crits with the target's
            // armour in full, which is what it meant. A tier that lists clauses states them on the clause.
            Codec.BOOL.optionalFieldOf("forcedCrit", false).forGetter(UbTier::forcedCrit),
            Codec.DOUBLE.optionalFieldOf("armourPierce")
                    .xmap(UbTier::toOptionalDouble, UbTier::toOptional)
                    .forGetter(UbTier::armourPierce)
    ).apply(instance, UbTier::new));

    public UbTier {
        damageType = damageType == null ? Optional.empty() : damageType;
        effects = effects == null ? List.of() : List.copyOf(effects);
        extra = extra == null ? Optional.empty() : extra;
        name = name == null ? Optional.empty() : name;
        critMultiplier = critMultiplier == null ? OptionalDouble.empty() : critMultiplier;
        segments = segments == null ? List.of() : List.copyOf(segments);
        // Same clamp the clause applies to the same two numbers, so a shorthand tier and a clause cannot disagree
        // about what "half the armour" is — see UbSegment's own normalisation and ArmourPierce.sanitise.
        armourPierce = armourPierce == null ? OptionalDouble.empty() : armourPierce;
        double share = armourPierce.isPresent() ? armourPierce.getAsDouble() : 0.0;
        armourPierce = share > 0.0 && Double.isFinite(share) ? OptionalDouble.of(Math.min(1.0, share))
                : OptionalDouble.empty();
    }

    /**
     * {@return the tier's damage clauses — its {@code segments} when it lists any, otherwise <b>one implicit
     * segment</b> built from {@code hits × damage} and {@code radius}}
     *
     * <h2>Why the shorthand becomes a segment instead of staying a second branch</h2>
     * A planner with two branches is a planner where one of them stops being updated. So "no segments" is
     * translated once, here, into the clause it has always meant: {@code hits × damage} on the
     * {@link UbTargetRule#NEAREST} body, or on every body inside {@code radius}
     * ({@link UbTargetRule#ALL_IN_RADIUS}). The tier's own {@code damageType} and {@code critMultiplier} travel
     * onto it, so the settlement reads <em>only</em> segments and never has to ask "but what if the tier said
     * it instead".
     *
     * <p>The implicit segment carries no effects: a tier's {@link #effects()} have always applied to every body
     * the ultimate hit, which is not a property of one clause. They still do — see
     * {@code UbDamageSettlement} — so an effect clause (紬希's pull on the farthest body) is <b>additional</b>
     * to them, not a replacement.
     */
    public List<UbSegment> damageSegments() {
        if (!segments.isEmpty()) {
            return segments;
        }
        return List.of(new UbSegment(OptionalDouble.of(damage), hits,
                radius.isPresent() ? UbTargetRule.ALL_IN_RADIUS : UbTargetRule.NEAREST, radius,
                List.of(), damageType, critMultiplier, 0.0, forcedCrit, armourPierce,
                OptionalDouble.empty()));
    }

    /** {@return this tier's name, or empty when the pack names none} */
    public String nameOr(String fallback) {
        return name.filter(value -> !value.isBlank()).orElse(fallback);
    }

    /**
     * The pre-{@code critMultiplier} shape: every field the record had before task 55, no per-skill
     * crit multiplier.
     *
     * <p>A convenience constructor rather than making every existing caller pass the optional tail: the
     * meaning of "absent" is then stated once, here, instead of at a dozen call sites and a dozen tests.
     */
    public UbTier(String animation, int hits, double damage, OptionalDouble radius,
            Optional<ResourceLocation> damageType, List<UbEffect> effects,
            OptionalDouble teamChargeBonus, Optional<String> extra, Optional<String> name) {
        this(animation, hits, damage, radius, damageType, effects, teamChargeBonus, extra, name,
                OptionalDouble.empty(), List.of(), false, OptionalDouble.empty());
    }

    /**
     * The common shape: a tier with effects, the ultimate's own damage type and no free-form tag.
     *
     * <p>A convenience constructor rather than making each caller pass {@link Optional#empty()} twice: the
     * meaning of the optional tail is then stated once, here, instead of at a dozen call sites.
     */
    public UbTier(String animation, int hits, double damage, OptionalDouble radius,
            List<UbEffect> effects, OptionalDouble teamChargeBonus, Optional<String> extra) {
        this(animation, hits, damage, radius, Optional.empty(), effects, teamChargeBonus, extra,
                Optional.empty(), OptionalDouble.empty(), List.of(), false, OptionalDouble.empty());
    }

    /** The smallest useful shape: a tier that applies no effects at all. */
    public UbTier(String animation, int hits, double damage, OptionalDouble radius,
            List<UbEffect> effects) {
        this(animation, hits, damage, radius, Optional.empty(), effects, OptionalDouble.empty(),
                Optional.empty(), Optional.empty(), OptionalDouble.empty(), List.of(), false,
                OptionalDouble.empty());
    }

    /**
     * The <b>segments</b> shape: a tier whose damage is spelled as its clauses — one per clause of its text.
     *
     * <p>This is the shape a pack uses when its ultimate is not "one number on one kind of receiver": two
     * amounts on two kinds of target (日和莉), a blast centred on another body (美美 6★), or a number that grows
     * with how many it hit (紬希 6★). {@code hits} and {@code damage} are {@code 0}/{@code 0.0} because the
     * segments hold them — see {@link #damageSegments()}, which is why the shorthand is not read at all.
     */
    public UbTier(String animation, List<UbSegment> segments, List<UbEffect> effects) {
        this(animation, 0, 0.0, OptionalDouble.empty(), Optional.empty(), effects, OptionalDouble.empty(),
                Optional.empty(), Optional.empty(), OptionalDouble.empty(), segments, false,
                OptionalDouble.empty());
    }

    /**
     * The full segments shape: clauses, tier-wide effects, the tier's damage type, its crit multiplier and its
     * name — every number a {@link UbSegment} does not carry itself.
     */
    public UbTier(String animation, List<UbSegment> segments, List<UbEffect> effects,
            Optional<ResourceLocation> damageType, Optional<String> name) {
        this(animation, 0, 0.0, OptionalDouble.empty(), damageType, effects, OptionalDouble.empty(),
                Optional.empty(), name, OptionalDouble.empty(), segments, false, OptionalDouble.empty());
    }

    /**
     * {@return this tier with its effects checked, as a {@link DataResult}}
     *
     * <p>Not wired into {@link #CODEC}, on purpose: a decode error drops the whole character
     * ({@code CharacterRegistry}'s lenient codec turns it into one WARN and a skipped entry), and a
     * mistyped scope should cost one effect, not a character's entire data. So {@code UbDamageSettlement}
     * asks this per cast and logs a mismatch once, the same policy {@code TieredAmounts} applies to an
     * out-of-range tier.
     *
     * <p>Public and game-free so the check is assertable without a server, and so a resource test can run
     * it over every shipped JSON.
     */
    public DataResult<UbTier> validateEffects() {
        for (int index = 0; index < effects.size(); index++) {
            UbEffect effect = effects.get(index);
            Optional<String> problem = effect == null
                    ? Optional.of("is null") : effect.mismatch();
            if (problem.isPresent()) {
                String where = "effects[" + index + "] ";
                String why = problem.get();
                return DataResult.error(() -> where + why);
            }
        }
        // The clauses' own effects are checked by the same walk and the same rule: an effect clause selects
        // enemies (紬希's pull on the farthest body), so a party buff declared there would buff the enemy it
        // was aimed at. One check, two lists — the alternative is a second copy of `UbEffect.mismatch`
        // reached only by packs that use segments.
        for (int segment = 0; segment < segments.size(); segment++) {
            UbSegment clause = segments.get(segment);
            if (clause == null) {
                int where = segment;
                return DataResult.error(() -> "segments[" + where + "] is null");
            }
            for (int index = 0; index < clause.effects().size(); index++) {
                UbEffect effect = clause.effects().get(index);
                Optional<String> problem = effect == null
                        ? Optional.of("is null") : effect.mismatch();
                if (problem.isPresent()) {
                    String where = "segments[" + segment + "].effects[" + index + "] ";
                    String why = problem.get();
                    return DataResult.error(() -> where + why);
                }
            }
        }
        return DataResult.success(this);
    }

    private static OptionalDouble toOptionalDouble(Optional<Double> value) {
        return value.isPresent() ? OptionalDouble.of(value.get()) : OptionalDouble.empty();
    }

    private static Optional<Double> toOptional(OptionalDouble value) {
        return value.isPresent() ? Optional.of(value.getAsDouble()) : Optional.empty();
    }
}
