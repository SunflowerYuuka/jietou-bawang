package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>An EX skill as a real passive</b>: what it grants while the character is enrolled in a party slot.
 *
 * <h2>Why this is nested in the ladder and not a field of {@link CharacterDef}</h2>
 * {@code CharacterDef} is at <b>sixteen</b> {@code RecordCodecBuilder.group} components, which is the
 * ceiling — a seventeenth component does not compile, and that is very likely why an earlier attempt at this
 * task died mid-run. So EX does not get a top-level field. It is one more entry in the structure that
 * already models per-skill data, {@code affection.skills}:
 *
 * <pre>
 *   "affection": { "skills": [
 *     { "skill": "ex", "baseName": "龙卷的攻刃",
 *       "tier2": { "name": "龙卷的攻刃+" },
 *       "tier3": { "name": "剑圣的天赋" },
 *       "passive": { "rungs": [
 *         { "tier": 1, "clauses": [ { "effect": "buff_attack_area", "effectParams": { "tier": 5, "radius": 0.0 } } ] },
 *         { "tier": 2, "clauses": [ ... ] } ] } } ] }
 * </pre>
 *
 * <p>{@code ex} is already the reserved skill tag no hook ever carries (see {@link SkillLadder}), so the tag
 * <em>is</em> the marker: a ladder named {@code ex} with a {@code passive} block describes a battle-start
 * stat buff, and one without it describes nothing. No new top-level field, no new registry, no new record
 * hung off the character.
 *
 * <h2>Why rungs rather than one clause list</h2>
 * The bwiki gives EX three rungs with different numbers, and the游戏's EX+ is genuinely stronger:
 *
 * <pre>
 *   姬塔   龙卷的攻刃   【15+15*技能等级】物攻      → 龙卷的攻刃+ 【240+15*技能等级】
 *   可可萝 精灵的启示   【15+15*技能等级】物攻      → + 【30 魔法防御力】
 *   佩可莉姆 公主之力   【75+75*技能等级】生命值    → 公主之力+   【1275+75*技能等级】
 *   凯露   黑暗之蚀     【15+15*技能等级】魔攻      → 黑暗之蚀+   【240+15*技能等级】
 * </pre>
 *
 * <p>So a rung supplies its own clause list and {@link #rungFor(int)} walks the ladder the same way
 * {@link SkillLadder#upgradeAt} does: the <b>highest rung at or below</b> the affection tier, falling back to
 * the lowest one that exists. That keeps "the name says 龙卷的攻刃+ and the passive is the + one" impossible
 * to disagree — both questions are answered from the same tier number.
 *
 * <h2>EX gets no level bonus</h2>
 * The human was explicit. Every other tiered effect adds the level band's bonus
 * ({@code impl.growth.LevelBands}) before clamping; an EX clause is resolved <b>as declared</b>, because the
 * strength of an EX comes from its own formula's {@code +perLevel × 技能等级} term and from nothing else.
 * {@code ExPassives.tierOf} states that, and a test pins it across a band boundary.
 *
 * @param rungs the rungs, in ascending tier order; the lowest one is the tier-1 whiteboard
 */
@ApiStatus.Experimental
public record ExPassive(List<ExPassive.Rung> rungs) {

    public static final Codec<ExPassive> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ExPassive.Rung.CODEC.listOf().fieldOf("rungs").forGetter(ExPassive::rungs)
    ).apply(instance, ExPassive::new));

    public ExPassive {
        rungs = rungs == null ? List.of() : List.copyOf(rungs);
    }

    /** {@return whether this passive grants nothing at all} */
    public boolean isEmpty() {
        for (Rung rung : rungs) {
            if (rung != null && rung.hasClauses()) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return the rung that applies at affection {@code tier}, or empty when this passive grants nothing}
     *
     * <p>The highest rung at or below {@code tier} wins; a passive whose only rung is tier 2 applies it at
     * tier 1 too, which is the same "climb back up" degradation {@link CharacterDef#face(int)} documents for a
     * character who ships only a 6★ picture. Never throws: a hostile tier in a save must not cost a party edit.
     *
     * @param tier the affection tier, 1–3
     */
    public Optional<Rung> rungFor(int tier) {
        Rung best = null;
        Rung lowest = null;
        for (Rung rung : rungs) {
            if (rung == null || !rung.hasClauses()) {
                continue;
            }
            if (lowest == null || rung.tier() < lowest.tier()) {
                lowest = rung;
            }
            if (rung.tier() <= tier && (best == null || rung.tier() > best.tier())) {
                best = rung;
            }
        }
        return best != null ? Optional.of(best) : Optional.ofNullable(lowest);
    }

    /**
     * One rung of the EX ladder: which affection tier it belongs to, and what it grants.
     *
     * @param tier    the affection tier this rung is reached at, 1-based
     * @param clauses what it grants; empty is legal and means "this rung renames only"
     */
    @ApiStatus.Experimental
    public record Rung(int tier, List<ExPassive.Clause> clauses) {

        public static final Codec<Rung> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("tier").forGetter(Rung::tier),
                ExPassive.Clause.CODEC.listOf().optionalFieldOf("clauses", List.of())
                        .forGetter(Rung::clauses)
        ).apply(instance, Rung::new));

        public Rung {
            clauses = clauses == null ? List.of() : List.copyOf(clauses);
        }

        /** {@return whether this rung grants anything} */
        public boolean hasClauses() {
            return !clauses.isEmpty();
        }
    }

    /**
     * One thing an EX rung grants, with <b>three</b> shapes behind two fields.
     *
     * <h2>Why these shapes, and what changed in task 39</h2>
     * An EX clause is one of three things in the source material, and the shapes follow the material rather
     * than the other way round:
     *
     * <ul>
     *   <li><b>A flat point formula</b> — {@code {"effect": "flat_attack_up", "effectParams": {"base": 15,
     *       "perLevel": 15}}} for 「提升自身【15+15×技能等级】物攻」. This is what three of the four shipped
     *       EX skills actually say. The value scales with the <b>character's level</b> (see
     *       {@link FlatAmount}), which is an axis no tier ladder has.</li>
     *   <li><b>A tiered percentage buff</b> — {@code {"effect": "buff_magic_defence_area", "effectParams":
     *       {"tier": 1}}} for 可可萝's 「+30 魔法防御力」. Still supported: a percentage buff is a real thing
     *       this mod models, and a pack may legitimately write one.</li>
     *   <li><b>A named vanilla effect</b> — {@code {"vanillaEffect": "minecraft:health_boost", "amplifier":
     *       4}} for 佩可莉姆's 「提升自身最大生命力」, which this mod has no effect type for and where vanilla
     *       already owns the mechanic.</li>
     * </ul>
     *
     * <p><b>What was wrong before.</b> All four EX attack clauses were written as {@code buff_attack_area}
     * tier 5, i.e. a +100% multiplier on whatever the player's weapon happened to be. The bwiki formulas are
     * points that scale with level, so that was a different buff wearing the same name — the concern the
     * previous report raised and this task fixes.
     *
     * <p>{@link #type()} together with {@link #vanilla()} is the either/or, and {@link #problem()} is the
     * rule that says so — the same construction {@link UbEffect#mismatch()} uses, and for the same reason:
     * the halves decode independently, so only a check over the pair can refuse a clause that means neither.
     *
     * @param type      the effect this clause applies, empty when it names a vanilla one
     * @param params    the tier block for a <b>tiered</b> {@link #type()}; ignored otherwise
     * @param vanilla   a vanilla effect id, empty when this clause uses {@link #type()}
     * @param amplifier the <b>vanilla</b> clause's effect level; never scaled, and never used by ours
     * @param amount    the point formula for a <b>flat</b> {@link #type()}; empty otherwise
     * @param duration  how long a <b>flat</b> grant lasts — permanent for EX, which is the shipped case
     */
    @ApiStatus.Experimental
    public record Clause(Optional<EffectType> type, TieredParams params,
                         Optional<ResourceLocation> vanilla, int amplifier,
                         Optional<FlatAmount> amount, GrantDuration duration) {

        public static final Codec<Clause> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                EffectType.CODEC.optionalFieldOf("effect").forGetter(Clause::type),
                // ONE `effectParams` object for all three shapes, whose fields are INLINED. Three separate
                // fields all reading "effectParams" would decode correctly but *encode* to one object with
                // the key written three times, silently dropping two on a round trip.
                Params.CODEC.optionalFieldOf("effectParams", Params.NONE).forGetter(Clause::paramsBundle),
                ResourceLocation.CODEC.optionalFieldOf("vanillaEffect").forGetter(Clause::vanilla),
                Codec.INT.optionalFieldOf("amplifier", 0).forGetter(Clause::amplifier)
        ).apply(instance, (type, bundle, vanilla, amplifier) -> new Clause(type, bundle.tiered(),
                vanilla, amplifier, bundle.flat(), bundle.duration())));

        /**
         * The three shapes of an {@code effectParams} object, decoded once.
         *
         * <p>A flat clause writes {@code {"base": 15, "perLevel": 15}}, a tiered one writes
         * {@code {"tier": 3, "seconds": 8.0}}, and either may add {@code "seconds"}. Every field here is
         * optional, so one codec reads them together and {@link Clause} keeps whichever one its
         * {@link EffectType} needs — which is why an author writes <b>one</b> {@code effectParams} block and
         * never has to know which of our shapes is going to read it.
         *
         * <h2>Why {@code seconds} is owned by {@link TieredParams} and not read again here</h2>
         * Both shapes can be timed, so a second reader for the same key would put "seconds" in one map codec
         * twice — which does not fail loudly, it just picks one. So the field is read once, by the record
         * that already documents it, and the lifetime is <b>derived</b> from it. One key, one reader.
         */
        private record Params(TieredParams tiered, Optional<FlatAmount> flat) {

            static final Params NONE = new Params(TieredParams.FIRST, Optional.empty());

            static final Codec<Params> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    // Field-less: `tier`, `seconds` and `radius` land directly in effectParams.
                    TieredParams.MAP_CODEC.forGetter(Params::tiered),
                    // base/perLevel as their own optional fields rather than a nested object, so a flat
                    // clause reads `{"base": 15, "perLevel": 15}`.
                    Codec.DOUBLE.optionalFieldOf("base").forGetter(p -> p.flat().map(FlatAmount::base)),
                    Codec.DOUBLE.optionalFieldOf("perLevel")
                            .forGetter(p -> p.flat().map(FlatAmount::perLevel))
            ).apply(instance, Params::new));

            private Params(TieredParams tiered, Optional<Double> base, Optional<Double> perLevel) {
                this(tiered,
                        base.isEmpty() && perLevel.isEmpty() ? Optional.empty()
                                : Optional.of(new FlatAmount(base.orElse(0.0), perLevel.orElse(0.0))));
            }

            /** {@return the lifetime the shared {@code seconds} field declares} */
            GrantDuration duration() {
                return tiered.seconds().isPresent()
                        ? GrantDuration.ofSeconds(tiered.seconds().getAsDouble())
                        : GrantDuration.PERMANENT;
            }
        }

        public Clause {
            type = type == null ? Optional.empty() : type;
            vanilla = vanilla == null ? Optional.empty() : vanilla;
            params = params == null ? TieredParams.FIRST : params;
            amount = amount == null ? Optional.empty() : amount;
            duration = duration == null ? GrantDuration.PERMANENT : duration;
        }

        /** {@return a clause over one of this mod's tiered effects} */
        public static Clause of(EffectType type, TieredParams params) {
            return new Clause(Optional.ofNullable(type), params, Optional.empty(), 0, Optional.empty(),
                    GrantDuration.PERMANENT);
        }

        /**
         * {@return a clause granting a flat point formula}
         *
         * @param type     {@link EffectType#FLAT_ATTACK_UP} or {@link EffectType#FLAT_MAGIC_ATTACK_UP}
         * @param amount   the formula
         * @param duration how long it lasts; {@link GrantDuration#permanent()} for an EX
         */
        public static Clause flat(EffectType type, FlatAmount amount, GrantDuration duration) {
            return new Clause(Optional.ofNullable(type), TieredParams.FIRST, Optional.empty(), 0,
                    Optional.ofNullable(amount), duration);
        }

        /** {@return a clause over one vanilla effect, at {@code amplifier}} */
        public static Clause vanilla(ResourceLocation effect, int amplifier) {
            return new Clause(Optional.empty(), TieredParams.FIRST, Optional.ofNullable(effect), amplifier,
                    Optional.empty(), GrantDuration.PERMANENT);
        }

        /**
         * {@return the three shapes bundled, for the codec's single {@code effectParams} field}
         *
         * <p>The lifetime is written into the shared {@code seconds} key rather than into a field of its
         * own, which is why the tiered params are rebuilt with the duration's seconds when they do not
         * already carry them. That keeps <b>one</b> reader of {@code seconds} (and therefore one answer to
         * "how long does this last") while still letting a caller construct a timed flat clause from either
         * side.
         */
        private Params paramsBundle() {
            TieredParams carrySeconds = duration.isPermanent() || params.seconds().isPresent()
                    ? params
                    : new TieredParams(params.tier(), OptionalDouble.of(duration.seconds().getAsDouble()),
                            params.radius());
            return new Params(carrySeconds, amount);
        }

        /**
         * {@return whether this clause grants a flat point amount rather than a tier}
         *
         * <p>The discriminator the applier switches on: flat clauses take an attribute write with a level,
         * tiered ones take a rung and a {@code MobEffect}. Two mechanisms, so the shape has to be decidable
         * from the data rather than guessed from the type alone.
         */
        public boolean isFlat() {
            return type.isPresent() && EffectTypes.isFlat(type.get());
        }

        /**
         * {@return the points this clause is worth at {@code level}, {@code 0} for a non-flat clause}
         *
         * @param level the character's level, from the roster
         */
        public double pointsAt(int level) {
            return isFlat() ? amount.orElse(FlatAmount.NONE).amountAt(level) : 0.0;
        }

        /**
         * {@return why this clause cannot be applied, or empty when exactly one shape is filled}
         *
         * <p>A message rather than a boolean because its two readers — a pack author reading a WARN and
         * a test's failure output — both need the fix, not the verdict. Never throws.
         */
        public Optional<String> problem() {
            if (type.isPresent() && vanilla.isPresent()) {
                return Optional.of("a clause names both effect " + type.get().getSerializedName()
                        + " and vanillaEffect " + vanilla.get() + "; write exactly one");
            }
            if (type.isEmpty() && vanilla.isEmpty()) {
                return Optional.of("a clause names neither effect nor vanillaEffect, so it grants nothing; "
                        + "name one, or drop the clause");
            }
            if (type.isPresent() && isFlat()) {
                // A flat effect with no formula is a grant of zero points wearing a name — the silent no-op
                // this project treats as a defect. Say which key is missing.
                if (amount.isEmpty()) {
                    return Optional.of(type.get().getSerializedName() + " needs an effectParams block with "
                            + "base and/or perLevel (the wiki's 【base+perLevel×技能等级】); without one it "
                            + "would grant 0 points");
                }
                return Optional.empty();
            }
            if (type.isPresent() && !EffectTiers.isTiered(type.get())) {
                return Optional.of(type.get().getSerializedName() + " is neither a tiered effect nor a flat "
                        + "one, so an EX clause cannot carry a rung or an amount for it; the party-scoped "
                        + "effects are " + EffectType.partyScoped() + " and the flat ones are "
                        + EffectTypes.flat());
            }
            return Optional.empty();
        }
    }
}
