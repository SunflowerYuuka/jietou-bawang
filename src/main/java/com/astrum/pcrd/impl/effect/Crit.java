package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>暴击</b>: two attributes of this mod's own — {@code astrum:crit_chance} and {@code astrum:crit_damage} —
 * and the one place a crit roll happens.
 *
 * <h2>Why this mod needs its own crit at all</h2>
 * Minecraft has <b>no</b> crit attribute. A vanilla attack has a crit <em>flag</em>: {@code Player.attack}
 * rolls {@code level.getRandom().nextFloat() < 0.1F} against a hard-coded 10%, adds the jump penalty, and
 * multiplies the hit by a hard-coded {@code 1.5F}. None of that is a stat, so nothing can raise it, nothing
 * can show it in a tooltip, and nothing can carry it on an effect. This build deals absolute damage numbers
 * derived in {@code docs/balance-derivation.md} §14, so "a character crits more often" had nowhere to live.
 *
 * <h2>Two attributes, in the attribute system's own units</h2>
 * <pre>
 *   astrum:crit_chance   fraction, default 0.0   0.25 = one hit in four crits
 *   astrum:crit_damage   multiplier, default 1.5  1.5 = a crit deals 150% of the authored number
 * </pre>
 *
 * <p><b>Why 1.5.</b> It is the game's own crit multiplier — {@code Player.attack}'s hard-coded
 * {@code f1 *= 1.5F}. Picking the number the rest of the game already means is the only choice that keeps a
 * player's reading of "critical hit" intact, and it means a pack that grants crit without touching the
 * damage stat gets exactly a vanilla crit's strength. The attribute is clamped to a floor of 1.0 so a
 * hostile write cannot turn a crit into a <em>weaker</em> hit.
 *
 * <h2>Where the roll happens, and where it deliberately does not</h2>
 * {@link #factorFor} is called at the mod's <b>own</b> damage settlement and nowhere else — the same two
 * places {@link MagicAttack#scaleFor} is called:
 *
 * <ul>
 *   <li>{@code CombatEvents.flush} — one call per queued damage entry, i.e. one roll per hit;</li>
 *   <li>{@code UbDamageSettlement} — one call per ultimate hit.</li>
 * </ul>
 *
 * <p>It is <b>not</b> a global damage-event hook ({@code LivingDamageEvent} / {@code CriticalHitEvent}), and
 * the reason is the same one {@code MagicAttack}'s javadoc gives for the attack stat: a listener on
 * {@code LivingDamageEvent.Pre} sees an amount that has already been built out of a weapon, a tier's authored
 * number and the multiplier chain, and it cannot tell which part of it a "×1.5" is supposed to apply to. Two
 * writers of "how big is this hit" in one event is how a hit ends up scaled twice, and the mod already has a
 * writer there ({@code DamageReduction}). So a roll happens once, on a number this mod authored, before
 * {@code hurt()} is handed it. Everything downstream — armour, the defence buffs, the vanilla crit flag —
 * sees an already-final number and multiplies nothing again.
 *
 * <h2>Deterministic: one draw, from the level's {@link RandomSource}</h2>
 * {@link #roll} performs <b>exactly one</b> {@code nextDouble()} per call and compares it to the chance. That
 * is what makes the feature assertable: with a seeded {@code RandomSource} the answer is a function of the
 * seed, and "chance 0 never crits" is true because {@code nextDouble()} is in {@code [0, 1)} and
 * {@code x < 0} is never true. It also means a hit cannot crit twice for being settled in two stages.
 *
 * <p>{@link #factorFor} skips the draw entirely when the chance is 0, which is the state of every entity that
 * was never given a crit stat. That is not an optimisation: it keeps this feature from consuming a number out
 * of the world's RNG stream for characters it has nothing to say about, so adding this class cannot shift the
 * outcome of anything else that rolls on {@code level.getRandom()}.
 *
 * <h2>Compatibility with other mods, stated honestly</h2>
 * The human's requirement is 「兼容其他mod的暴击属性」. {@link #FOREIGN} is the list of well-known crit
 * attribute ids this build <b>soft-looks-up</b> — {@code BuiltInRegistries.ATTRIBUTE.getHolder(id)} answers
 * empty when the mod is not installed, so there is no dependency, no class loading, and no crash. When one is
 * present its value is folded into the same pool:
 *
 * <ul>
 *   <li><b>chance</b> — <b>summed</b>. Two sources of "you crit more often" are two probabilities of the same
 *       event, so adding them is the arithmetic the numbers mean. The sum is clamped to 100%.</li>
 *   <li><b>damage</b> — <b>maximised</b>, not summed. Two crit multipliers do not add: a {@code ×1.5} and a
 *       {@code ×2.0} do not mean {@code ×2.5} to anybody, and summing them would let one foreign mod quietly
 *       make this mod's crit three times as strong. Taking the larger honours the foreign mod when it is
 *       stronger and never compounds.</li>
 * </ul>
 *
 * <p><b>What was actually found, and therefore what is actually provided.</b> Every one of the 347 jars in the
 * human's pack
 * ({@code D:\Minecraft\Snapshot 2.6.8\.minecraft\versions\Better MC [NEOFORGE] BMC5\mods}) was scanned for
 * {@code crit} in its entry names and for {@code crit_chance} / {@code critical_chance} in its
 * {@code en_us.json} and {@code zh_cn.json}. <b>Nothing matched.</b> The only crit-shaped things present are
 * {@code dummmmmmy}'s damage-number dummy ({@code CritRecord}, {@code CritCompat}, {@code CritMode} — it
 * <em>detects</em> vanilla crits to print a bigger number, it registers no attribute) and the Aether's slider
 * entity textures. There is no AttributesLib, no Apothic Attributes, no Iron's Spells, no RPG attribute mod of
 * any kind installed. <b>So in this pack the only crit that is honoured is {@code astrum:crit_*}</b>, and the
 * foreign path below is dormant code that has never been exercised at runtime. It is written because the
 * requirement asked for the capability and because a soft lookup is free; it is <b>not</b> a claim to be
 * compatible with a mod that could not be found, and it is <b>not</b> covered by a test that needs a real
 * foreign mod — {@code CritTest} pins the arithmetic with a stand-in attribute it registers itself.
 *
 * <h2>The per-skill channel: a multiplier that <b>replaces</b> the global one</h2>
 * 「本技能出现暴击时，造成的伤害值从 2 倍提升至 4 倍」 (鏡華) and 「暴击时将造成 3.5 倍伤害」 (璃乃) are statements
 * about <b>one skill</b>, and the global attribute cannot say them: {@code astrum:crit_damage} is a number the
 * <em>player</em> carries, shared by every skill she owns, so "this ultimate crits for 4×" had nowhere to live.
 * The human's ruling was 「单独做一个通道更好点」 — a channel of its own rather than a re-reading of the global —
 * and it is {@link #factorFor(LivingEntity, RandomSource, OptionalDouble)}'s third argument:
 *
 * <ul>
 *   <li>an ultimate's value is {@code UbTier.critMultiplier} — {@code 4.0} on 鏡華's 6★ tier, {@code 3.5} on
 *       璃乃's, read by {@code UbDamageSettlement};</li>
 *   <li>a small skill's value is {@code EffectHook.ExtraDamage.critMultiplier} — 鏡華's 专属 {@code 4.0},
 *       carried with the amount it authored through {@code DamageBatch} to {@code CombatEvents.flush};</li>
 *   <li><b>absence means the global still applies</b>, which is every other hit in the mod.</li>
 * </ul>
 *
 * <p><b>Replace, never add.</b> A present value answers <b>itself</b>, not {@code global + own}: 4× means a
 * crit of that skill deals four times the authored number whether the caster's global is 1.5 or 2.0. Adding
 * would make the sentence mean "5.5× at this pack's defaults", which is a number nobody wrote; multiplying
 * would make it 6×. The value is clamped exactly like the attribute ({@code [1, MAX_CRIT_DAMAGE]}), so a
 * broken pack cannot turn a crit into a weaker hit or a {@code NaN}.
 *
 * <p><b>One roll, unchanged.</b> The channel decides only <em>how big a crit is</em>; whether one happens is
 * still {@link #chanceOf} and {@link #roll}, so a skill with its own multiplier does not crit more or less
 * often, and a caster with no chance still draws nothing.
 */
@ApiStatus.Internal
public final class Crit {

    /** Registry path of the chance attribute: {@code astrum:crit_chance}. */
    public static final String CHANCE_PATH = "crit_chance";

    /** Registry path of the damage attribute: {@code astrum:crit_damage}. */
    public static final String DAMAGE_PATH = "crit_damage";

    /** {@code astrum:crit_chance}. */
    public static final ResourceLocation CHANCE_ID = InternalIds.id(CHANCE_PATH);

    /** {@code astrum:crit_damage}. */
    public static final ResourceLocation DAMAGE_ID = InternalIds.id(DAMAGE_PATH);

    /** {@code attribute.astrum.crit_chance} — the key vanilla resolves for the chance attribute's name. */
    public static final String CHANCE_NAME_KEY = "attribute." + CHANCE_ID.toLanguageKey();

    /** {@code attribute.astrum.crit_damage}. */
    public static final String DAMAGE_NAME_KEY = "attribute." + DAMAGE_ID.toLanguageKey();

    /** The default chance: <b>0</b>. Nobody crits until something grants it. */
    public static final double DEFAULT_CHANCE = 0.0;

    /**
     * The default crit multiplier: <b>1.5</b>, vanilla's own ({@code Player.attack}'s {@code f1 *= 1.5F}).
     *
     * <p>A crit is therefore exactly as strong as the game's crit until a pack says otherwise, which is the
     * only reading of 「暴击」 a player brings with them.
     */
    public static final double DEFAULT_CRIT_DAMAGE = 1.5;

    /**
     * The chance ceiling, {@code 1.0} = always.
     *
     * <p>Also the clamp for the summed chance pool: three sources of 40% do not crit 120% of the time, they
     * crit 100% of the time. {@code RangedAttribute} enforces it per attribute; {@link #chanceOf} enforces it
     * on the best single source.
     */
    public static final double MAX_CHANCE = 1.0;

    /** The damage ceiling. {@code 16} is generous and still finite, so a broken save cannot make a hit a NaN. */
    public static final double MAX_CRIT_DAMAGE = 16.0;

    /**
     * The foreign crit attribute ids this build folds into {@link #pool}.
     *
     * <p>Two namespaces, and the reason each is here rather than a longer speculative list: {@code attributeslib}
     * is the id <b>AttributesLib</b> registered its crit pair under on 1.20.x and {@code apothic_attributes} is the
     * same mod's id after it was renamed <b>Apothic Attributes</b> — it is the de-facto standard crit attribute in
     * the NeoForge RPG ecosystem. {@code irons_spellbooks} is listed because Iron's Spells 'n Spellbooks is the
     * other widely installed source of a crit stat on this loader.
     *
     * <p><b>None of the three is installed in the pack this build was tested against</b> — see the class javadoc
     * for the scan that established it. The list is therefore a soft probe and nothing more: a pack that installs
     * one of them gets its crit counted, and a pack that installs a different one adds a row here. Guessing at
     * more namespaces would be inventing compatibility rather than providing it.
     */
    private static final List<Foreign> FOREIGN = List.of(
            new Foreign("attributeslib", "attributeslib:crit_chance", "attributeslib:crit_damage"),
            new Foreign("apothic_attributes", "apothic_attributes:crit_chance",
                    "apothic_attributes:crit_damage"),
            new Foreign("irons_spellbooks", "irons_spellbooks:crit_chance",
                    "irons_spellbooks:crit_damage"));

    /** The attribute registrations, on the mod bus. */
    public static final DeferredRegister<Attribute> ATTRIBUTES =
            DeferredRegister.create(Registries.ATTRIBUTE, Astrum.MOD_ID);

    /** 暴击率 — fraction in {@code [0, 1]}, default {@link #DEFAULT_CHANCE}. */
    public static final DeferredHolder<Attribute, Attribute> CRIT_CHANCE =
            ATTRIBUTES.register(CHANCE_PATH,
                    () -> new RangedAttribute(CHANCE_NAME_KEY, DEFAULT_CHANCE, 0.0, MAX_CHANCE)
                            .setSyncable(true));

    /** 暴击伤害 — multiplier in {@code [1, 16]}, default {@link #DEFAULT_CRIT_DAMAGE}. */
    public static final DeferredHolder<Attribute, Attribute> CRIT_DAMAGE =
            ATTRIBUTES.register(DAMAGE_PATH,
                    () -> new RangedAttribute(DAMAGE_NAME_KEY, DEFAULT_CRIT_DAMAGE, 1.0, MAX_CRIT_DAMAGE)
                            .setSyncable(true));

    private Crit() {
    }

    /**
     * Registers both attributes and adds them to every living entity type.
     *
     * @param modBus the mod bus, from {@code PartyEffects.register} — this is wiring, not a second registry
     */
    public static void register(IEventBus modBus) {
        ATTRIBUTES.register(modBus);
        modBus.addListener(Crit::addToEveryLivingEntity);
    }

    /**
     * Gives every registered living type both attributes at their defaults.
     *
     * <p>Same reasoning as {@code MagicAttack.addToEveryLivingEntity}: an attribute that is registered but
     * absent from an entity's {@code AttributeSupplier} cannot be read or modified on that entity at all —
     * {@code getAttribute} answers {@code null}. Adding it at the <em>default</em> is what makes "an untouched
     * entity never crits" true rather than likely.
     */
    private static void addToEveryLivingEntity(EntityAttributeModificationEvent event) {
        for (var type : event.getTypes()) {
            if (!event.has(type, CRIT_CHANCE)) {
                event.add(type, CRIT_CHANCE, DEFAULT_CHANCE);
            }
            if (!event.has(type, CRIT_DAMAGE)) {
                event.add(type, CRIT_DAMAGE, DEFAULT_CRIT_DAMAGE);
            }
        }
    }

    /**
     * {@return this entity's crit chance, as a fraction in {@code [0, 1]}}
     *
     * <p>The pool is this mod's own stat plus every installed foreign one (see {@link #FOREIGN}), summed and
     * then clamped once at {@link #MAX_CHANCE}. Total and never throwing, for the reason {@code MagicAttack.of}
     * is: this is read on a damage path, where an exotic entity with no attribute map must answer "no crit"
     * rather than take the hit down with it.
     *
     * @param entity the body whose chance is wanted; {@code null} answers {@code 0}
     */
    public static double chanceOf(@Nullable LivingEntity entity) {
        if (entity == null) {
            return 0.0;
        }
        // <b>max</b>, not a sum — the human's ruling: 「B取最高」. Four characters in a party each declare a
        // base chance, and adding them meant a full party crit four times as often as a solo character, which
        // makes the stat a function of party size rather than of the character. Taking the best is also what
        // {@link #damageOf} below already does, so the two stats now follow one rule instead of two.
        double best = ownValue(entity, CRIT_CHANCE, DEFAULT_CHANCE);
        for (Foreign foreign : FOREIGN) {
            best = Math.max(best, foreignValue(entity, foreign.chanceId()));
        }
        return clampChance(best);
    }

    /**
     * {@return this entity's crit multiplier, at least {@code 1.0}}
     *
     * <p>{@code max} over the pool rather than a sum — see the class javadoc: two multipliers do not add.
     *
     * @param entity the body whose multiplier is wanted; {@code null} answers {@link #DEFAULT_CRIT_DAMAGE}
     */
    public static double damageOf(@Nullable LivingEntity entity) {
        if (entity == null) {
            return DEFAULT_CRIT_DAMAGE;
        }
        double best = ownValue(entity, CRIT_DAMAGE, DEFAULT_CRIT_DAMAGE);
        for (Foreign foreign : FOREIGN) {
            best = Math.max(best, foreignValue(entity, foreign.damageId()));
        }
        if (!Double.isFinite(best)) {
            return DEFAULT_CRIT_DAMAGE;
        }
        return Math.max(1.0, Math.min(MAX_CRIT_DAMAGE, best));
    }

    /**
     * {@return the factor a hit's authored amount is multiplied by — {@code 1.0} or a crit's multiplier}
     *
     * <p>The global form: no skill multiplier is supplied, so a crit uses the attacker's
     * {@code astrum:crit_damage} ({@link #damageOf}). Callers that know which skill authored the hit pass its
     * own value to {@link #factorFor(LivingEntity, RandomSource, OptionalDouble)} instead.
     *
     * <h2>One roll per hit</h2>
     * Called once, on the amount about to be handed to {@code hurt()}: {@code CombatEvents.flush} for a hook
     * batch entry and {@code UbDamageSettlement} for an ultimate's hit. Nothing downstream multiplies again.
     *
     * <p>A chance of 0 answers exactly {@code 1.0} <b>without drawing</b> from {@code random}, so an entity
     * that was never given a crit stat is byte-identically unaffected and the world's RNG stream is not
     * perturbed by a feature that has nothing to say about it.
     *
     * @param attacker the caster; {@code null} answers {@code 1.0}
     * @param random   the level's {@link RandomSource}; {@code null} answers {@code 1.0}
     */
    public static double factorFor(@Nullable LivingEntity attacker, @Nullable RandomSource random) {
        return factorFor(attacker, random, OptionalDouble.empty());
    }

    /**
     * {@return the factor a hit of <b>one skill</b> is multiplied by — {@code 1.0} or that skill's crit multiplier}
     *
     * <h2>The per-skill channel</h2>
     * Whether the hit crits is unchanged ({@link #chanceOf}, {@link #roll}); this method only decides the
     * multiplier a crit uses:
     *
     * <ul>
     *   <li>{@code skillMultiplier} <b>present and finite</b> — a crit deals {@code skillMultiplier ×} the
     *       authored amount, <b>replacing</b> the attacker's global {@code astrum:crit_damage}. 4× means 4×,
     *       not {@code 1.5 + 4} and not {@code 1.5 × 4}. Clamped to {@code [1, MAX_CRIT_DAMAGE]}, the same
     *       range the attribute is clamped to, so a broken pack can neither weaken a crit below the authored
     *       number nor produce a {@code NaN}.</li>
     *   <li>{@code skillMultiplier} <b>absent</b> (or non-finite, which only a hand-built value can be) — the
     *       attacker's global {@link #damageOf} applies, exactly as before this channel existed.</li>
     * </ul>
     *
     * <p><b>Where a value comes from.</b> {@code UbTier.critMultiplier} for an ultimate's own hits
     * (read by {@code UbDamageSettlement}) and {@code EffectHook.ExtraDamage.critMultiplier} for a small
     * skill's authored damage (carried through {@code DamageBatch} to {@code CombatEvents.flush}). Absence is
     * the normal case and means "use the global".
     *
     * @param attacker        the caster; {@code null} answers {@code 1.0} — no entity means no crit chance, so
     *                        there is nothing to roll for the skill's own value either
     * @param random          the level's {@link RandomSource}; {@code null} answers {@code 1.0}
     * @param skillMultiplier the skill's own crit multiplier, empty for the global
     */
    public static double factorFor(@Nullable LivingEntity attacker, @Nullable RandomSource random,
            OptionalDouble skillMultiplier) {
        if (attacker == null || random == null) {
            return 1.0;
        }
        double chance = chanceOf(attacker);
        if (!(chance > 0.0)) {
            return 1.0;
        }
        return roll(random, chance) ? multiplierFor(attacker, skillMultiplier) : 1.0;
    }

    /**
     * {@return the factor a hit that <b>cannot fail to crit</b> is multiplied by — the crit multiplier, no roll}
     *
     * <h2>「必定暴击」: the forced-crit channel</h2>
     * 克莉丝提娜's two ultimate rungs say 「第一次 Hit 必定暴击」 / 「必定命中与必定暴击」: the hit is a crit
     * <b>whatever the caster's {@code astrum:crit_chance} is</b>. {@link #factorFor} cannot express that — it
     * answers {@code 1.0} for a caster with no chance, by design and without drawing — so a forced crit needs a
     * second door, and this is it.
     *
     * <p><b>Why it is not a flag on the roll.</b> A forced crit is not "a roll that succeeded" and must not
     * consume a number from {@code level.getRandom()}: nothing is being decided. So this method draws nothing
     * and calls {@link #multiplierFor} directly, which is the same arithmetic a rolled crit goes through. That is
     * what keeps the summary honest — a hit settled here really is a crit, it is multiplied by the crit
     * multiplier the caster or the skill declares, and no downstream stage multiplies it again.
     *
     * <p>The per-skill replacement rule is unchanged and is honoured: a clause with its own
     * {@code critMultiplier} (「本技能暴击 4 倍」) crits at 4× when forced, exactly as it would when rolled.
     *
     * @param attacker        the caster; {@code null} answers the default multiplier — a hit with no caster
     *                        still crits, because "this one crits" is a property of the clause and not of a stat
     * @param skillMultiplier the skill's own crit multiplier, empty for the caster's global
     */
    public static double forcedFactorFor(@Nullable LivingEntity attacker, OptionalDouble skillMultiplier) {
        return multiplierFor(attacker, skillMultiplier);
    }

    /**
     * {@return the multiplier a critical hit of this skill uses, at least {@code 1.0}}
     *
     * <p>The replacement rule in one place, so the two damage paths cannot disagree about "4× means 4×":
     * a present finite skill value wins outright, and an absent (or non-finite) one falls back to
     * {@link #damageOf}. The clamp is the attribute's own range {@link #MAX_CRIT_DAMAGE}, not a second
     * scale — a 1000 in a pack reads as 16, which is at least a finite hit.
     *
     * @param attacker        the caster, for the global fallback
     * @param skillMultiplier the skill's own multiplier, empty for "no channel"
     */
    static double multiplierFor(@Nullable LivingEntity attacker, OptionalDouble skillMultiplier) {
        if (skillMultiplier == null || skillMultiplier.isEmpty()) {
            return damageOf(attacker);
        }
        double own = skillMultiplier.getAsDouble();
        if (!Double.isFinite(own)) {
            return damageOf(attacker);
        }
        return Math.max(1.0, Math.min(MAX_CRIT_DAMAGE, own));
    }

    /**
     * {@return whether this roll crits} — <b>exactly one</b> {@code nextDouble()} per call
     *
     * <p>Pure over the {@link RandomSource}, which is why determinism is assertable without a world. The
     * comparison is strict ({@code <}), so a chance of {@code 0} can never crit even if {@code nextDouble()}
     * answers {@code 0.0}, and a chance of {@code 1.0} always crits because {@code nextDouble()} is
     * {@code < 1.0}.
     *
     * @param random the level's random source; {@code null} answers {@code false} without drawing
     * @param chance the crit chance as a fraction; non-finite or non-positive answers {@code false}
     */
    public static boolean roll(@Nullable RandomSource random, double chance) {
        if (random == null) {
            return false;
        }
        double draw = random.nextDouble();
        return Double.isFinite(chance) && draw < chance;
    }

    /** {@return {@code raw} clamped into {@code [0, MAX_CHANCE]}, with a non-finite value answering 0} */
    static double clampChance(double raw) {
        if (!Double.isFinite(raw)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(MAX_CHANCE, raw));
    }

    /** {@return the value of this mod's own attribute, or {@code fallback} when it cannot be read} */
    private static double ownValue(LivingEntity entity, DeferredHolder<Attribute, Attribute> holder,
            double fallback) {
        if (!holder.isBound()) {
            return fallback;
        }
        AttributeInstance instance = entity.getAttribute(holder);
        if (instance == null) {
            return fallback;
        }
        double value = instance.getValue();
        return Double.isFinite(value) ? value : fallback;
    }

    /**
     * {@return the value of a foreign crit attribute, or {@code 0} when that mod is not installed}
     *
     * <p>{@code BuiltInRegistries.ATTRIBUTE.getHolder} is the whole compatibility mechanism: a missing id
     * answers empty, so a pack without the mod costs one map lookup per hit and nothing else. A present id whose
     * entity has no modifier still answers its default, which is why the value is taken raw rather than
     * defaulted here — the foreign mod's own default is the right baseline for its own stat.
     */
    private static double foreignValue(LivingEntity entity, ResourceLocation id) {
        Optional<Holder.Reference<Attribute>> attribute = BuiltInRegistries.ATTRIBUTE.getHolder(id);
        if (attribute.isEmpty()) {
            return 0.0;
        }
        AttributeInstance instance = entity.getAttribute(attribute.get());
        if (instance == null) {
            return 0.0;
        }
        double value = instance.getValue();
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    /** {@return whether any foreign crit attribute in {@link #FOREIGN} is installed right now} */
    public static boolean foreignPresent() {
        for (Foreign foreign : FOREIGN) {
            if (BuiltInRegistries.ATTRIBUTE.getHolder(foreign.chanceId()).isPresent()
                    || BuiltInRegistries.ATTRIBUTE.getHolder(foreign.damageId()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One foreign crit pair.
     *
     * @param namespace the mod's namespace, for a log line
     * @param chanceId  the chance attribute's full id
     * @param damageId  the damage attribute's full id
     */
    private record Foreign(String namespace, ResourceLocation chanceId, ResourceLocation damageId) {

        private Foreign(String namespace, String chanceId, String damageId) {
            this(namespace, ResourceLocation.parse(chanceId), ResourceLocation.parse(damageId));
        }
    }
}
