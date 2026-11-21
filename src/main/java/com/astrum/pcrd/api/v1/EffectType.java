package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import java.util.Locale;
import java.util.Set;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * Kind of effect an {@link EffectHook} applies. Also the dispatch key of {@link EffectHook#CODEC}.
 *
 * <h2>Adding a value is additive</h2>
 * {@link #CODEC} is {@link StringRepresentable#fromEnum}, which reads an unknown name as
 * {@code DataResult.error}. A pack that names a value a given build does not know therefore fails to
 * decode that one hook rather than being misread as some other effect, and the other values keep their
 * meaning: the enum's order and names are the wire format, so a new value goes at the end and no
 * existing pack changes behaviour.
 *
 * <h2>Why {@code HEAL} and {@code ALLY_EFFECT} exist at all</h2>
 * Both were added for the three characters after 姬塔, and each closes a gap that could not be
 * expressed before — the alternative was to <em>silently drop</em> a skill's main function:
 *
 * <ul>
 *   <li>{@link #HEAL} — there was no heal at all. コッコロ's ultimate is 「自分のHPを中回復」 and
 *       ペコリーヌ's 技能1 is 「HP中回復」: the heal <b>is</b> what those skills do. Approximating it
 *       with {@code lifesteal} would have tied a fixed heal to damage dealt, which is a different
 *       mechanic (and a healer who misses heals nobody). See {@code Heals} for why it is a direct
 *       {@code heal()} and not a {@code minecraft:instant_health} instance.</li>
 *   <li>{@link #ALLY_EFFECT} — {@link #APPLY_EFFECT} applies a vanilla mob effect to the entity that
 *       was <b>struck</b>, so it can only ever be a debuff on the enemy. There was no way to put a
 *       vanilla effect on the <b>caster or her party</b>, which is what 「自分の魔法防御力中アップ」,
 *       「味方全体の行動速度小アップ」 and 「継続HP回復」 all are. {@code attack_buff} covers the one
 *       attribute it was written for; this value generalises it to any vanilla effect by id.</li>
 * </ul>
 *
 * <p>Neither adds a <em>number</em>: a heal's amount and an effect's amplifier are data, derived in
 * {@code docs/balance-derivation.md} like every other quantity here.
 */
@ApiStatus.Experimental
public enum EffectType implements StringRepresentable {
    APPLY_EFFECT,
    EXTRA_DAMAGE,
    LIFESTEAL,
    GAIN_CHARGE,
    AREA_CHARGE,
    SPLASH,
    STACK,
    DETONATE,
    /**
     * Raises the caster's own attack for a while (「特大幅度提升自己的物理攻击」).
     *
     * <p>Last, so the eight values shipped before it keep their ordinal positions.
     */
    ATTACK_BUFF,
    /**
     * Restores health to the caster, and to her party inside a radius (「味方全体HP中回復」).
     *
     * <p>Appended after {@link #ATTACK_BUFF} for the ordinal reason above.
     */
    HEAL,
    /**
     * Applies a <b>named vanilla mob effect</b> to the caster and her party inside a radius — the
     * mirror image of {@link #APPLY_EFFECT}, which applies one to the enemy.
     *
     * <p>Appended last for the ordinal reason above.
     */
    ALLY_EFFECT,
    /**
     * Lowers the <b>armour</b> of the entity that was struck (「物理・魔法防御力を小ダウン」).
     *
     * <h2>Why this could not be {@link #APPLY_EFFECT}</h2>
     * The brief asked whether {@code apply_effect} can carry a defence debuff. It cannot, and the
     * registry says so rather than a guess: vanilla's only defensive <em>debuff</em> is
     * {@code minecraft:weakness}, which lowers the victim's <b>attack</b> — the opposite half of the
     * stat sheet from "takes more damage". Using it would put a picture and a name on the HUD that say
     * something the hit does not do, which is exactly the silent functional loss the brief forbids.
     *
     * <p>So it is a real {@code MobEffect} of this mod's own ({@code astrum:defence_down}) carrying a
     * negative {@code ARMOR} and {@code ARMOR_TOUGHNESS} modifier — the same construction and the same
     * fixed-modifier-id rule as {@code attack_buff}, which is why it expires by itself and needs no tick
     * listener. See {@code DefenceDownEffect}.
     *
     * <p><b>Tiered (4 rungs, 20% each) since the generic-effect task:</b> tier N removes {@code 20N}% of
     * the victim's armour, so tier 4 is −80%. The rungs travel in the <em>amplifier</em> — see
     * {@code DefenceDownRules} — and the tier in {@link TieredParams}, so the datapack says a rung rather
     * than an amplifier number.
     */
    DEFENCE_DOWN,
    /**
     * Restores <b>a percentage of max health</b> to every ally inside a radius — 5 tiers, 20% each.
     *
     * <h2>Why this is not {@link #HEAL}</h2>
     * {@code heal} takes an absolute amount from the datapack and is scaled by the multiplier chain;
     * this takes a <em>rung</em> and heals 20/40/60/80/100% of each receiver's own max health. Those are
     * different mechanics, not two spellings of one: 100% of max health is a full heal on a tank and on a
     * healer alike, which a fixed number cannot be. The brief calls for the percentage form and names
     * five rungs, so it gets its own value rather than overloading {@code heal}'s {@code amount}.
     *
     * <p>Instant, not regeneration: one {@code heal()} per receiver. A full heal at tier 5 is intended.
     */
    HEAL_AREA,
    /**
     * Restores <b>a percentage of the caster's own max health</b> — 5 tiers, 20% each.
     *
     * <p>The self-only half of {@link #HEAL_AREA}, and the one コッコロ's base ultimate's 「自分のHPを
     * 中回復」 would use once she is wired onto this kernel. Same arithmetic, same rungs; the receiver
     * set is the caster alone, so it does not go through {@code AllySelector} at all.
     */
    HEAL_SELF,
    /**
     * Raises the <b>attack damage</b> of the caster and every ally inside a radius — 5 tiers, +20% each.
     *
     * <h2>Why {@code ATTACK_DAMAGE} and not {@code minecraft:strength}</h2>
     * The brief's table says "strength equivalent". Vanilla's {@code strength} adds a flat
     * <b>+3 damage per level</b> ({@code ADD_VALUE}), which is a different buff on a wooden sword than on
     * a netherite one, and cannot express "20%". What a generic attack buff has to mean is "a fraction of
     * <em>this</em> entity's attack", so it is an {@code ADD_MULTIPLIED_TOTAL} modifier on
     * {@code Attributes.ATTACK_DAMAGE} — the same construction {@code attack_buff} already uses, and the
     * reason that class's encoding exists. +100% at tier 5 (double damage) is intended.
     *
     * <p>Distinct from {@link #ATTACK_BUFF}, which is the shipped djeeta hook whose magnitude is a ratio
     * in the datapack. This one is tiered and generic; both put a modifier on the same attribute under
     * <b>different modifier ids</b>, so a character may have both.
     */
    BUFF_ATTACK_AREA,
    /**
     * Reduces <b>non-magic</b> damage taken by the caster and every ally inside a radius — 5 tiers,
     * 12% each, topping at 60%.
     *
     * <h2>Why 12% and not 20%</h2>
     * Five rungs of 20% would be 100%, i.e. immunity, which is exactly what vanilla's {@code resistance}
     * does (20% a level, level V = invulnerable). The brief's ceiling is 60%, so the step is 12.
     *
     * <h2>Why it is not {@code minecraft:resistance}</h2>
     * Resistance cannot be split into physical and magical: it reduces <em>all</em> damage except what the
     * {@code bypasses_resistance} tag lets through. The brief asks for two separate buffs, so they are two
     * effects of this mod's own and reduce damage in {@code DamageReduction}, where the split is ours to
     * make. See {@link #BUFF_MAGIC_DEFENCE_AREA} for the marker that decides which half applies.
     */
    BUFF_PHYS_DEFENCE_AREA,
    /**
     * Reduces <b>magic</b> damage taken by the caster and every ally inside a radius — 5 tiers, 12% each.
     *
     * <h2>What "magic" means here, stated plainly</h2>
     * Minecraft has <b>no</b> {@code is_magic} damage-type tag. Verified against
     * {@code DamageTypeTags} in 21.1.265: it has {@code is_fire}, {@code is_projectile},
     * {@code is_explosion}, {@code is_freezing}, {@code is_lightning}, {@code is_fall},
     * {@code is_drowning} — and nothing for magic. So the marker has to be something this mod controls:
     * the damage type {@code astrum:magic}, which this mod ships and which its own magic carriers
     * ({@code extra_damage} / {@code splash} / the UB, when they declare {@code damageType:
     * "astrum:magic"}) use.
     *
     * <p><b>The honest limitation:</b> another mod's magic damage is <em>not</em> marked, so it is
     * reduced by {@link #BUFF_PHYS_DEFENCE_AREA} and not by this one — i.e. treated as physical — until
     * that mod adopts {@code astrum:magic} or adds its damage types to the {@code astrum:is_magic} tag
     * this mod also ships. There is no way to detect "this was meant to be magic" from a damage type we
     * do not own, and guessing from the type's name would be a rule that silently mislabels somebody
     * else's fireball.
     */
    BUFF_MAGIC_DEFENCE_AREA,
    /**
     * Removes <b>all</b> of the struck entity's absorption hearts — 1 tier, 100%.
     *
     * <h2>Why one rung and not four</h2>
     * The brief first described a 20/40/60/80/100% ladder and then settled on "one tier, remove all": a
     * barrier break is the skill, and "remove 40% of the barrier" is not a skill anybody wrote. So
     * {@link EffectTiers} gives it {@code maxTier = 1}, and a {@code "tier": 2} on it clamps to 1 with a
     * warning rather than inventing a rung.
     *
     * <p>Absorption is a single float on the entity ({@code LivingEntity#getAbsorptionAmount}), not a
     * per-source pool, so "all of it" is one {@code setAbsorptionAmount(0)}. The vanilla
     * {@code absorption} effect is also removed, or its attribute modifier would immediately refill the
     * hearts it is meant to have lost.
     */
    REMOVE_SHIELD,
    /**
     * Raises <b>movement speed and attack speed</b> together — 3 tiers, one vanilla level each.
     *
     * <h2>Why this is not just {@code minecraft:speed}</h2>
     * Verified against 21.1.265: {@code MobEffects.SPEED} carries one modifier, on
     * {@code Attributes.MOVEMENT_SPEED}. It does <b>not</b> touch attack speed, which in this game is
     * {@code Attributes.ATTACK_SPEED}. A 「行动速度アップ」 written as {@code minecraft:speed} is therefore
     * half the effect its name promises, and the half it is missing is the one a player feels on a sword.
     * So {@code HASTE} is this mod's own effect with two modifiers — {@code MOVEMENT_SPEED} and
     * {@code ATTACK_SPEED}, both {@code ADD_MULTIPLIED_TOTAL} — and the tier is the amplifier.
     */
    HASTE,
    /**
     * 「恐慌」: the target <b>cannot act</b> for the duration the skill declares (1.5 s when it declares none) —
     * 1 tier.
     *
     * <h2>What "cannot act" can honestly mean on a server</h2>
     * A server cannot press or unpress a client's keys. What it can do is make the action have no effect:
     * a {@code Mob} stops ticking its AI, and a {@code ServerPlayer} has its movement input zeroed and its
     * attack and interaction paths cancelled. That is "cannot act", and the code says so rather than
     * claiming keystrokes were blocked — see {@code PanicControl} for the full statement of the limit,
     * including that a modified client can still send whatever packets it likes and the server-side
     * cancellations are what stop them.
     */
    PANIC,
    /**
     * Deals damage equal to <b>a percentage of the victim's max health</b> — 5 tiers, 10% each.
     *
     * <h2>Why a percentage and not a flat number</h2>
     * Against a 20-health player this is indistinguishable from {@link #EXTRA_DAMAGE}; against a 150-health
     * boss it is the difference between a scratch and a fifth of its bar. The point of the effect is that
     * it scales with the target, which a fixed amount by definition does not.
     *
     * <p>It runs through the same damage batch as {@code extra_damage} and the same recursion fuse
     * ({@code astrum:hook_damage} is in this mod's namespace, so it never re-enters hook evaluation).
     */
    PERCENT_DAMAGE,
    /**
     * Multiplies <b>UB charge gain</b> for the caster and every ally inside a radius — 5 tiers,
     * +25% each, topping at {@code 2.25×}.
     *
     * <h2>The one effect that touches an existing kernel</h2>
     * Charge is credited from four sources — damage dealt, damage taken, a kill and the passive trickle —
     * and all four already funnel through {@code ChargeKernel}. So the multiplier lives <b>there</b>
     * ({@code ChargeKernel.rateForAmplifier} and the four {@code add*} methods), and every source gets it
     * without a second copy of the rule. A per-source implementation would have been four chances for one
     * source to be missed, and the brief's own test — "all four honour it" — exists because that is the
     * likely defect.
     */
    CHARGE_RATE_UP,
    /**
     * Grants <b>absorption</b> — vanilla's golden-apple yellow hearts — that <b>stacks with</b> vanilla's
     * own absorption instead of being cancelled by it. 5 tiers, 20% of the receiver's max health each.
     *
     * <h2>Why this is not {@code minecraft:absorption}</h2>
     * The human's requirement is 「做成类似MC原版金苹果效果的那个生命吸收，但是两者可以叠加」 — like the golden
     * apple's, but <b>the two add up</b>. Vanilla's {@code absorption} cannot do that, and not by accident:
     * absorption is one {@code float} on the entity ({@code LivingEntity.absorptionAmount}), and everything
     * that grants it <b>writes that one float</b>. {@code AbsorptionMobEffect.onEffectStarted} does
     * {@code setAbsorptionAmount(max(current, 4 * (1 + amplifier)))} — a <em>max</em>, so a second grant
     * never adds. Its attribute modifier raises {@code MAX_ABSORPTION} by {@code 4} a level, and
     * {@code LivingEntity.onAttributeUpdated} <b>clamps the float down</b> whenever that ceiling falls
     * (i.e. when the apple expires) — which would silently delete a shield that lived only in the float.
     *
     * <p>So our pool is tracked <b>separately</b> (the {@code astrum:shield_pool} attachment) and the float
     * is kept as the <em>mirror</em> of {@code ours + vanilla's}. The mirror is why the player still sees
     * yellow hearts: the HUD reads the float, and the float is the sum. Our own {@code MAX_ABSORPTION}
     * modifier is what keeps vanilla's expiry from clamping our part away, because removing vanilla's
     * modifier leaves a ceiling of exactly our pool. See {@code ShieldPool} for the reconciliation
     * arithmetic and the two orders the requirement names (apple→shield and shield→apple).
     *
     * <p><b>Tiered (5 rungs, 20% of max health each)</b>, so tier 5 is a full health bar of shield. The
     * rung is measured against the <em>receiver's</em> max health, which is what makes one tier mean the
     * same thing on a 20-health player and a 3377-health boss — the same reasoning {@link #HEAL_AREA}
     * gives. {@code docs/balance-derivation.md} derives ペコリーヌ's 专武 shield (tier 4) from the bwiki
     * formula.
     */
    SHIELD,
    /**
     * The magic counterpart of {@link #DEFENCE_DOWN}: the struck entity takes <b>more magic damage</b>.
     * 4 tiers, 20% each — the same ladder as {@code DEFENCE_DOWN}, as the human specified.
     *
     * <h2>Why this does not touch {@code Attributes.ARMOR}</h2>
     * Because it cannot and must not. Minecraft has <b>one</b> {@code ARMOR} attribute, and it reduces
     * <em>physical</em> damage only: {@code LivingEntity.getDamageAfterArmorAbsorb} applies armour for
     * every damage type <em>except</em> the ones tagged {@code minecraft:bypasses_armor}, and magic damage
     * in this mod is filed under {@code astrum:magic}, which is <b>not</b> in that tag. So lowering
     * {@code ARMOR} would lower the victim's physical defence and change nothing at all about magic —
     * the exact "the skill's main function is silently not implemented" failure this project forbids.
     *
     * <p>What "less magic defence" <em>means</em> here is therefore the mirror of
     * {@link #BUFF_MAGIC_DEFENCE_AREA}: a percentage <b>increase</b> in the magic damage the victim takes,
     * read on the damage path by {@code DamageReduction} — the one place the magic/physical split already
     * lives, guarded by the same {@code astrum:magic} marker. One rule, two directions, no second
     * classification.
     *
     * <h2>The honest limitation, the same one the buff has</h2>
     * It only reduces the defence of damage this build can recognise as magic, i.e. {@code astrum:magic} or
     * something in the {@code astrum:is_magic} tag. Another mod's magic damage is unmarked and is treated
     * as physical, so this debuff does nothing to it. That is stated rather than guessed — see
     * {@link #BUFF_MAGIC_DEFENCE_AREA}.
     */
    MAGIC_DEFENCE_DOWN,
    /**
     * Raises the <b>magic attack</b> of the caster and every ally inside a radius — 5 tiers, +20% each.
     *
     * <h2>Why this is a second attack buff and not {@link #BUFF_ATTACK_AREA}</h2>
     * Once {@code astrum:magic_damage} exists as a stat of its own, "「攻击力アップ」" is two questions and a
     * pack asks one or the other. {@link #BUFF_ATTACK_AREA} raises {@code Attributes.ATTACK_DAMAGE} — the stat
     * a sword reads — and this raises {@code astrum:magic_damage}, the stat a magic carrier reads
     * ({@code impl.effect.MagicAttack#attackFor} is the one place that split is made).
     *
     * <p>The alternative — one type that raised <b>both</b> stats — was considered and rejected, because it
     * cannot express a <em>magic-only</em> buff: 凯露's 黑暗之蚀 「提升自身【15+15*技能等级】魔法攻击力」 would
     * have to raise her physical stat too, i.e. the defect this value exists to fix ("her buff lands on the
     * wrong stat") would become "her buff lands on both". So the answer is two types on <b>one ladder with
     * two steps</b>: {@code AttackAreaRules} is +20% a tier on the physical stat and <b>+26%</b> a tier on
     * the magic one, because the two bases are not comparable (attack damage starts around 1-2, magic attack
     * starts at 0 and is written as flat points). 姬塔 / 可可萝 / 佩可莉姆 do <b>not</b> use this type for
     * their EX — their text is flat points, which is {@link #FLAT_ATTACK_UP}. This type is for a pack that
     * writes a <em>percentage</em> magic-attack buff as a timed skill.
     *
     * <p><b>The honest limitation, stated once.</b> This raises the magic attack <em>stat</em>. It changes
     * what a magic carrier's damage is worth only insofar as that carrier reads the stat — see
     * {@code MagicAttack#attackFor} for exactly which paths do (the mod's own damage carriers) and which do
     * not (a vanilla sword swing, which reads {@code ATTACK_DAMAGE} no matter whose name is on the buff).
     */
    BUFF_MAGIC_ATTACK_AREA,

    /**
     * 「提升自身【N】物理攻击力」 — adds a <b>flat number of points</b> to {@code ATTACK_DAMAGE}.
     *
     * <h2>Why this is not {@link #BUFF_ATTACK_AREA}</h2>
     * Because the shipped EX formulas are not percentages. The bwiki gives them as points that scale with
     * the character's level:
     *
     * <pre>
     *   姬塔     龙卷的攻刃  【15+15×L】物攻      龙卷的攻刃+ / 剑圣的天赋 【240+15×L】物攻
     *   可可萝   精灵的启示  【15+15×L】物攻      精灵的启示+            + 30 魔法防御力
     * </pre>
     *
     * <p>{@code BUFF_ATTACK_AREA} is a <b>tiered percentage</b>: tier N is {@code +20N%} of whatever the
     * receiver's attack happens to be, applied as {@code ADD_MULTIPLIED_TOTAL}. Writing 「15+15×L 物攻」
     * as a percentage would be a different buff — it would scale with the player's weapon instead of with
     * her level, and "技能等级" would vanish from the mechanic entirely. That is the defect this value
     * removes: before it, all four EX attack clauses were written as {@code buff_attack_area} tier 5, i.e.
     * every one of them was silently a +100% multiplier.
     *
     * <p>The amount is <b>data</b>, not a ladder — see {@link EffectHook.FlatAttackUp} for the
     * {@code base} / {@code perLevel} shape and why the code does the {@code perLevel × level} multiply.
     *
     * <h2>Not a status, so not a {@code MobEffect}</h2>
     * Like {@link #HEAL_AREA} and {@link #PERCENT_DAMAGE}, this is an <b>instantaneous attribute write</b>
     * and is deliberately unregistered: there is nothing to see on the HUD and nothing for a clock to
     * expire. Registering a marker would put a flickering entry on the player's effect bar for a grant
     * that finished in the same tick. The lifetime of an EX grant is not a duration — it is "while she
     * holds the party slot", owned by {@code ExPassives}' add/remove lifecycle.
     */
    FLAT_ATTACK_UP,

    /**
     * 「提升自身【N】魔法攻击力」 — adds a <b>flat number of points</b> to {@code astrum:magic_damage}.
     *
     * <p>{@link #FLAT_ATTACK_UP}'s magic half, and the carrier for 凯露's 黑暗之蚀
     * 「提升自身【…】魔法攻击力」. Both its rungs (base and {@code +}) are point formulas, exactly as her
     * physical sisters' are.
     *
     * <p>Flat is not merely the faithful reading here — it is the <em>only</em> one that works. The magic
     * stat's base is <b>0</b> ({@code MagicAttack.DEFAULT_VALUE}), so a percentage modifier on it is a
     * no-op; {@code ADD_VALUE} is the operation with meaning. See
     * {@code MagicAttackAreaEffect}'s javadoc for the same argument one level up.
     */
    FLAT_MAGIC_ATTACK_UP,

    /**
     * 「召唤」 as a <b>particle spirit</b>: a ring of orbiting motes around the caster, carried by a real
     * status effect. 5 tiers, one spirit each.
     *
     * <h2>Why this is not an entity</h2>
     * The human's instruction: 「为了不制作新实体模型，生成粒子效果当做精灵？环绕在玩家周围，做成buff给玩家，如果有计数要求就用等级来计数。」
     * A summon expressed as a {@code MobEffect} costs no model, no renderer, no AI, no despawn logic and no
     * chunk ticket, and it inherits the HUD entry, the expiry clock and {@code /effect give} from vanilla. The
     * count travels in the <b>amplifier</b> ({@code tier - 1}), which is exactly the "use the level to count"
     * the human asked for.
     *
     * <p>The particle budget is one particle per spirit per tick — 1 at tier 1 and 5 at tier 5 — drawn by
     * {@code ServerLevel.sendParticles} so the whole party sees it. See {@code Spirits} and
     * {@code SpiritEffect}.
     */
    SUMMON_SPIRIT,

    /**
     * 「计数」: a self buff whose <b>amplifier is the stack count</b> — 雪菲's 冰龙之印.
     *
     * <h2>This is the one effect that accumulates on purpose</h2>
     * Re-applying it <b>adds one stack</b> and refreshes the duration, up to the rung's cap. It is
     * deliberately the opposite of {@link #SHIELD}, which was corrected to be <em>restored</em> rather than
     * accumulated («ten swings are worth one shield»): a shield is a quantity of health, so adding grants
     * would make a defensive skill stronger the more often it fires; a counter is a <b>tally</b>, so a grant
     * that replaced instead of added would make every mark-granting skill but the last one pointless.
     * <b>Do not "fix" this one</b> — {@code Counters}' javadoc says why at length.
     *
     * <p>The cap is the ladder: 5 rungs, <b>4 marks a rung</b>, so tier 5 is 20 — 雪菲's 6★
     * 「最多可获得20个」. {@code Counters.capForTier} reads it from {@link EffectTiers} rather than holding a
     * second table.
     */
    COUNTER,

    /**
     * Consumes marks of {@link #COUNTER} from the <b>caster</b> and turns them into damage on the hit being
     * settled — 雪菲's 「移除自身1个『冰龙之印』，对最近的敌人造成【…】+【0.4×物理攻击力】×『冰龙之印』」.
     *
     * <h2>Why a type of its own rather than a flag on {@code COUNTER}</h2>
     * {@code COUNTER} <em>grants</em>, this <em>spends</em>, and they fire on different triggers (a mark is
     * granted by a burst; it is spent by 技能1). One type doing both would need a sign in the data, and the
     * sign is exactly the field a reader would miss. The two numbers a spend needs are also different from a
     * grant's: how many to remove, and how much damage each mark she was <em>holding</em> is worth — see
     * {@link EffectHook.ConsumeCounter}.
     *
     * <p><b>Not tiered.</b> The magnitude is derived from the marks the caster actually holds, so a rung would
     * be a second, competing scale for the same quantity.
     */
    CONSUME_COUNTER,

    /**
     * 「他充」: grants charge to <b>one</b> ally — the nearest one who has a UB gauge — or to the caster when
     * there is none.
     *
     * <h2>Who receives it, and why that is not {@link #AREA_CHARGE}</h2>
     * The human: 「补充一个通用效果，他充，也就是给他人进行充能的技能，这个他充效果是在周围有可以充能拥有UB槽的友方单位时，优先给组
     * 最近的友方单位，没有的情况下再给自己。」
     *
     * <p>{@code AREA_CHARGE} charges <em>everyone</em> in a radius and takes an {@code includeSelf} flag; this
     * one charges <b>exactly one</b> body. Expressing it as an area charge with a tiny radius would be two
     * mistakes at once — a second copy of "who is an ally", and a silent change of behaviour the moment two
     * allies stand at the same distance. So the receiver rule is its own function
     * ({@code impl.effect.TransferTarget}) and <b>both</b> transfer effects call it; the membership test is
     * still {@code AllySelector}'s, and the grant is still {@code UbCharge}'s.
     *
     * <p>「拥有UB槽」 is not invented here: it is {@code Party} membership, the same fact
     * {@code UbService.tryCast} reads to decide whether a slot holds anybody. A player with no character on
     * their wheel has no gauge to fill.
     *
     * <p>Tiered like {@link #GAIN_CHARGE}'s amount, 5 rungs of 6 points, so a pack writes 「他充 20」 as
     * {@code "tier": 4}.
     */
    TRANSFER_CHARGE,

    /**
     * 「他奶」: restores health to the same single receiver {@link #TRANSFER_CHARGE} picks — nearest ally with a
     * UB gauge, else the caster.
     *
     * <h2>Why this is a type and not a flag on the heal</h2>
     * The human: 「他奶同他充，就是给他人进行恢复，也是在周围有拥有UB槽的友方单位时，优先给组最近的友方单位，没有的情况下再给自己。」
     * and then, decisively, 「他奶就是套用瞬间回血的那个效果」. So the <b>amount</b> is the instant heal's own
     * (20% of the receiver's max health a rung, five rungs — {@link #HEAL_SELF}'s convention exactly), and what
     * is new is only <em>who</em> it lands on. A flag on {@code HEAL_AREA} would have to choose between a radius
     * sweep and a single pick at runtime; two values keep the receiver rule a compile-time fact.
     *
     * <p>It goes through {@code PercentHeals}, the existing percentage-heal implementation — there is no second
     * heal convention in this mod.
     */
    TRANSFER_HEAL,

    /**
     * 「缓慢回血」 on the <b>caster</b>: vanilla's own {@code minecraft:regeneration}, five rungs.
     *
     * <h2>Why a real vanilla effect and not a heal per tick</h2>
     * The human: 「使用类似生命恢复的药水效果，可以跟游戏内的其他原版效果共存」. {@code regeneration} <b>is</b> the
     * game's heal-over-time, so using it is what makes the effect behave like the potion a player already knows.
     * Applying one {@code MobEffectInstance} also inherits the amplifier curve, the expiry clock and the HUD
     * marker from vanilla, and needs no tick of ours at all.
     *
     * <h2>Coexistence, stated honestly</h2>
     * Vanilla keys a living entity's effects by <b>type</b>, so our instance and any other {@code regeneration}
     * are one entry: {@code MobEffectInstance#update} keeps the <b>stronger amplifier</b> and the longer of the
     * two durations. That is what "can coexist with other vanilla effects" can truthfully mean here — it never
     * <em>cancels</em> a stronger potion, and a weaker one never shortens ours — but it does mean a lower rung
     * landing on a player who already has a stronger {@code regeneration} adds nothing. See
     * {@code OverTimeEffects} for the statement in code and {@code task-49-report.md} for why the instant heals
     * are a separate question.
     */
    REGEN_SELF,

    /**
     * 「缓慢回血」 on <b>everyone in the radius</b> — {@link #REGEN_SELF}'s area half, the
     * {@link #HEAL_AREA} shape.
     *
     * <p>Same vanilla effect, same rungs, same coexistence caveat: four characters' area regenerations on one
     * player merge into the strongest, which is vanilla's rule and is stated rather than papered over.
     */
    REGEN_AREA,

    /**
     * 「缓慢恢复充能」 on the <b>caster</b>: a real mod effect whose per-instance tick credits charge through
     * {@code ChargeKernel}.
     *
     * <h2>Why this needs an effect of its own where the heal did not</h2>
     * Vanilla has {@code regeneration} for 「持续回血」 and <b>nothing</b> for "charge the ultimate gauge over
     * time" — the gauge is this mod's own state. So this is a {@code MobEffect} whose
     * {@code applyEffectTick} pays one instalment, which is the sanctioned place for per-instance work:
     * vanilla calls it for <b>one instance on one entity</b> out of that entity's own effect map. No tick
     * listener, no entity walk, nothing enumerated.
     *
     * <h2>Merge, strongest wins — the human's ruling</h2>
     * 「两个不同角色的缓慢回充同时挂在玩家身上时：选择合并，取最强。」 That is exactly what one type with the tier
     * in the <b>amplifier</b> gives for free: vanilla merges two instances of one type, keeping the higher
     * amplifier. So two characters' charge-regen do <b>not</b> add — 20%/s and 40%/s give 40%/s. One type, not
     * one per slot or per character, precisely so that the merge is vanilla's rather than a rule of ours.
     */
    CHARGE_REGEN_SELF,

    /**
     * 「缓慢恢复充能」 on <b>everyone in the radius</b> — {@link #CHARGE_REGEN_SELF}'s area half.
     *
     * <p>The <b>same</b> registered effect type as the self form, not a twin: the area form simply applies it to
     * more receivers. That is deliberate and it is what makes the human's 「取最强」 rule hold across both
     * shapes — a self cast and an area cast landing on one player are one entry, strongest amplifier wins.
     */
    CHARGE_REGEN_AREA,

    /**
     * 「位移 / 吸引」: <b>moves the struck body</b> toward the caster by N blocks, with
     * {@code Entity#teleportTo}'s semantics rather than a velocity.
     *
     * <h2>The human's instruction, and why it excludes an impulse</h2>
     * 「位移参考使用末影珍珠的效果。」 An ender pearl does not <em>push</em> anybody: it computes a destination
     * and then <b>places the entity there</b>. So this type computes a point on the line from the victim to
     * the caster and calls {@code LivingEntity#teleportTo(double, double, double)} — which is exactly what an
     * ender pearl's landing does, and which {@code ServerPlayer} overrides to a real client teleport, so a
     * pulled player is moved the same way a {@code /tp} moves her.
     *
     * <h2>What the number means, and the reading the data supports</h2>
     * The rung is the <b>distance travelled</b>, one block a rung (1..5). bwiki's 「把最远的敌人拉到身前
     * 300」 / 「将目标向前吸引 100」 are distances on the same ÷100 scale the radius table uses (§0(a) of
     * {@code docs/derivations-batch-a.md}), so 300 is 3 blocks and 100 is 1.
     *
     * <p>The alternative reading — "the point the victim ends up at, measured from the caster" — was
     * considered and <b>rejected</b>, and the reason is mechanical: a melee hook fires on a target that is
     * already inside melee reach (≈3 blocks), so a "land 3 blocks from me" pull would move it <em>away</em> or
     * not at all, i.e. the skill's whole function would silently do nothing. "Travel N blocks closer" is the
     * only reading under which both the 6-block ultimate and the melee skill do something, and a target
     * 5 blocks away ends up 2 blocks in front of the caster, which is what 拉到身前 describes.
     *
     * <h2>Walls: the ender pearl's rule, minus the suffocation</h2>
     * The destination is marched from the victim toward the caster and the <b>last clear point</b> wins, so a
     * body is never teleported into a wall — an ender pearl <em>can</em> put you inside one and suffocate
     * you; a movement-control effect that did that would be a griefing tool. The honest limitation: the march
     * is a straight line, so a wall between the two bodies means the victim stops at it instead of being
     * pulled around a corner. See {@code Pulls}.
     *
     * <p><b>Not registered as a {@code MobEffect}</b>: it is a position write that completes in the tick it
     * is applied, like {@link #HEAL_AREA}. There is no state to carry and nothing for a clock to expire.
     */
    PULL,

    /**
     * 「击退」: applies <b>vanilla's own knockback</b> to the struck body, away from the caster.
     *
     * <h2>The human is right, and this is the call he means</h2>
     * 「击退原版不是有吗？原版里，怪被攻击不是会击退吗？直接调用就好了，如果你觉得击退距离不够就改数值。」
     * Verified in 21.1.265: {@code LivingEntity#hurt} ends its own damage sequence with
     * {@code this.knockback(0.4F, dx, dz)}, and {@code Player#attack} adds a second, smaller one on top. So
     * every hit already knocks back, and the mechanic this type adds is <b>more of the same call</b>:
     * {@code LivingEntity#knockback(double strength, double x, double z)}, whose strength the data chooses.
     * No custom impulse is written anywhere — see {@code Knockbacks}' one call site.
     *
     * <h2>The ladder: one rung is the strength vanilla itself uses</h2>
     * Tier N is strength {@code 0.4 × N} (0.4 / 0.8 / 1.2 / 1.6 / 2.0), because 0.4 is the number
     * {@code hurt}'s own call passes — so tier 1 is "exactly as hard as being hit already is", and a pack
     * that asks for more says how many times more. The felt distance is not linear in the strength:
     * {@code knockback} halves the body's existing horizontal delta before adding, and
     * {@code KNOCKBACK_RESISTANCE} multiplies the result down, so tier 2 is roughly twice a sword hit and
     * tier 5 is a launch. Which rung "feels right" is a thing only a person in the game can settle.
     *
     * <p>Unlike {@link #PULL} this one <em>does</em> use velocity, because the human's requirement is
     * vanilla's knockback, and vanilla's knockback is a velocity. That is the difference between the two
     * mechanics, not an inconsistency.
     */
    KNOCKBACK,

    /**
     * 「嘲讽」: for the duration, every <b>hostile mob near the caster</b> turns to her and takes her as its
     * AI target.
     *
     * <h2>Why the taunt lives on the taunter, not on the mob</h2>
     * 望's clause is 「嘲讽自身 10 秒」 — she taunts, and the enemies react. Two mechanical reasons make the
     * caster the right holder:
     * <ul>
     *   <li><b>An effect instance has no "who applied me".</b> {@code MobEffectInstance} carries a type, a
     *       duration and an amplifier, and nothing else. A taunt on the mob would have to store the taunter
     *       in a side channel that outlives the instance — a stale uuid waiting to point at the wrong
     *       player. A taunt on the <b>caster</b> needs no such field: the holder <em>is</em> the taunter.</li>
     *   <li><b>It is the only shape that fires for her ultimate.</b> 望's two UB tiers declare
     *       {@code damage: 0.0}, and {@code UbDamageSettlement} gates every {@code VICTIM} effect on
     *       {@code hurt()} answering true — a 0-damage tier never does, so a victim-scoped taunt would
     *       silently never run. A party-scoped one is settled <b>before</b> the damage, exactly like the
     *       buffs the same cast applies.</li>
     * </ul>
     *
     * <p>So {@link #reachesParty()} is true, and the receiver is the caster alone (the radius field says how
     * far the <em>taunt</em> reaches, not who receives it).
     *
     * <h2>What a player actually observes</h2>
     * Every hostile {@code Mob} within the reach that can attack her turns its head toward her and has
     * {@code setTarget(taunter)} re-asserted <b>every tick</b> of the effect's life, so its own AI walks to
     * her and swings at her. On expiry the mobs that were still locked on her lose that target and their own
     * target selectors run again. Nothing is forced on a <b>player</b> victim: a server cannot own a client's
     * camera, so a taunt on a player only shows the effect and does not turn her view — stated rather than
     * faked with teleport packets. See {@code Taunts}.
     */
    TAUNT,

    /**
     * 「物理无敌」: while it is on, damage this mod classifies as <b>physical</b> does <b>zero</b>, and magical
     * damage passes through untouched — 克莉丝提娜's 乱数圣域 / 极值圣域 「使自身免疫物理伤害【3+0.01×技能等级】秒」.
     *
     * <h2>Why {@code minecraft:resistance} cannot be this, and why the channel is separate</h2>
     * Resistance is 20% a level, so level V is <b>100% of every damage type</b> — full invulnerability, magic
     * included, which is the opposite of 「单独免疫物理不免疫魔法」. It also cannot be split: it reduces
     * everything except what {@code bypasses_resistance} lets through. So physical immunity is a channel of
     * this mod's own, read on the damage path by {@link DamageReduction} — the one place the physical/magic
     * split already lives ({@link DamageReduction#isMagic}, {@code astrum:magic} / the {@code astrum:is_magic}
     * tag). A second classifier would be a second answer to 「is this magic」, which is exactly how the two
     * halves would drift.
     *
     * <h2>One rung, not five, and why it is not a second defence ladder</h2>
     * The source says 「5档」 because the game's buff is resistance V; that five is <b>resistance's</b> ladder,
     * not a magnitude this channel should copy. Building a 20%-a-rung physical-only reduction here would
     * duplicate {@link #BUFF_PHYS_DEFENCE_AREA} (12% a rung, 60% ceiling) and would leave the one thing the
     * text actually says — 免疫, not 「少掉一点血」 — unexpressed. So the channel is binary: one rung, full
     * negation of the physical half. A pack writing {@code "tier": 5} on it clamps to the one rung, and the
     * character's json records the 5→1 provenance in its {@code _notes}.
     *
     * <h2>The honest limitation, the same one the defence pair has</h2>
     * Another mod's unmarked magic damage is classified as physical (there is no {@code is_magic} tag in
     * vanilla), so this immunity would negate it until that mod adds its types to {@code astrum:is_magic}.
     * That is the documented boundary of the one classifier, stated once here and honoured by the buff, the
     * debuff and this immunity alike.
     */
    PHYS_IMMUNITY,

    /**
     * 「控制免疫」: while it is on, the mod's <b>hard control</b> — {@link #PANIC} — cannot be applied, and an
     * existing panic is neutralised for the duration. 矛依未's 6★ 「使得自己免疫控制180秒」.
     *
     * <h2>What "control" covers here, decided rather than assumed</h2>
     * <b>Panic only</b>, because panic is the only effect in this mod that actually takes an action away: a
     * panicking body cannot attack, interact or move under its own power ({@code PanicRules} states exactly
     * what that can and cannot mean). Slowness and weakness change a <em>stat</em> — how fast, how hard — but
     * the holder still acts, so they are debuffs, not control. The classification is
     * {@code ControlImmunity.controllableTypes()}, one table; a future control effect is added there and every
     * reader (the application gate and the action gate) picks it up.
     *
     * <h2>Why this is not resistance five either</h2>
     * Resistance V is all-damage immunity — it would silently grant an effect the text does not describe
     * (「使用抗性五等于凭空多给一条效果」). Control immunity is instead read in exactly two places: the panic
     * application (so a panic never lands) and {@code PanicControl.isPanicking} (so an already-running panic
     * stops refusing actions). It touches no damage path at all, which is the property the two are separated
     * for.
     */
    CONTROL_IMMUNITY,

    /**
     * 「提升我方全体 28% 暴击伤害，持续 18 秒」: raises the <b>crit damage</b> of the caster and every ally inside a
     * radius, for a while — 千歌's 6★ UB, and the first effect of this mod that writes a <b>timed</b>
     * {@code astrum:crit_damage} modifier.
     *
     * <h2>Why this needed a new type, in the human's words</h2>
     * 「新增效果类型，修复。」 The clause sat in 千歌's {@code ub.enhanced.extra} because nothing carried it: the
     * buff family ({@code buff_attack_area}, the two defence buffs, {@code haste}) raises the stats those effects
     * were written for, {@code apply_effect} can only attach a vanilla potion effect, and
     * {@code astrum:crit_damage} is this mod's own attribute. So the sentence rendered as 「尚未实现」 beside a
     * skill that does nothing of the sort.
     *
     * <h2>The 55th-channel argument, and why it does not cover this</h2>
     * Task 55's per-skill crit multiplier <b>replaces</b> the caster's global {@code astrum:crit_damage} for one
     * skill's own hits; it cannot say 「我方全体、18 秒」, which is a <em>timed, party-wide, global</em> buff. This
     * type is the other mechanism, and the two are deliberately distinct: one changes how big one skill's crit
     * is, this one changes what every crit is worth while it runs.
     *
     * <h2>How it is carried</h2>
     * A real {@code MobEffect} of this mod's own ({@code astrum:crit_damage_up}) with one
     * {@code ADD_MULTIPLIED_TOTAL} modifier on {@link com.astrum.pcrd.impl.effect.Crit#CRIT_DAMAGE}, declared
     * under a fixed modifier id, and the magnitude encoded in the <b>amplifier</b> — the same construction, and
     * the same reasons, {@link #ATTACK_BUFF} documents. Vanilla adds and removes the modifier over the
     * instance's own lifetime, so this mod contains no expiry code and no tick listener.
     *
     * <p><b>Party-scoped</b> (「我方全体」), like the four area buffs: {@code radius: 0} keeps it self-only and an
     * absent radius uses the party default. It <b>persists on the caster</b>, because it is a state a player
     * carries, not an event.
     *
     * <p><b>Not tiered.</b> The magnitude is the wiki's own percentage ({@code 28}), carried as a free number in
     * {@code effectParams} exactly as {@link #ATTACK_BUFF}'s ratio is: a rung would quantise a number the source
     * states exactly, and an amplifier can hold it (0–255 in units of 1%).
     *
     * <p><b>Appended last, for the wire-format reason</b> every value after {@link #ATTACK_BUFF} states: the enum's
     * order and names are the JSON's vocabulary, so a new value goes at the end and no existing pack changes
     * meaning.
     */
    CRIT_DAMAGE_UP,

    /**
     * 「无效【45+45*技能等级】<b>物理</b>伤害的护盾」 — {@link #SHIELD}'s <b>physical-only</b> channel: 由衣's 6★
     * barrier, and the value the human's 「做一下2个单独的通道」 asked for.
     *
     * <h2>Why a type of its own, and not a flag on {@code SHIELD}</h2>
     * Two reasons, and the first is vanilla's: a living entity's effects are keyed by <b>type</b>
     * ({@code LivingEntity.activeEffects} is a {@code Map<Holder<MobEffect>, MobEffectInstance>}), so one type
     * with a parameter would <b>merge</b> 由衣's physical barrier into 望's shared one — one instance, one
     * duration, one tier, and one of the two shields silently gone. Three types are three instances, and the
     * human's 「覆盖只会覆盖自身」 then has an entry per channel to replace. The second is the data: the
     * <em>type</em> is what the pack writes, and 「物理伤害的护盾」 is a different effect from 「物理·魔法伤害的护盾」,
     * exactly as {@code BUFF_PHYS_DEFENCE_AREA} is different from {@code BUFF_MAGIC_DEFENCE_AREA}.
     *
     * <h2>What carries the split</h2>
     * The channel is a {@code ShieldKind} layer in the ledger ({@code ShieldState}), and the damage path is
     * where it becomes real: {@code LivingDamageEvent.Pre} takes the layers a hit cannot use out of the
     * absorption float ({@code Shields.parkForTypedDamage}), so a magic bolt finds a physical barrier's points
     * nowhere and reaches health. The classifier is <b>not</b> a second one — {@code DamageReduction.isMagic}
     * ({@code astrum:magic} / the {@code astrum:is_magic} tag) answers for the shields, the two defence buffs
     * and the physical immunity alike.
     *
     * <h2>Same ladder, same size</h2>
     * 5 tiers, 20% of each receiver's max health a rung — identical to {@link #SHIELD}; only <em>what it
     * absorbs</em> differs. The bwiki's 由衣 number (「45+45×技能等级」 = 9405 raw → tier 2 by the shield
     * conversion) therefore lands on the same rung it did before, and the restriction is what changes.
     *
     * <p><b>Appended last, for the wire-format reason</b> every value after {@link #ATTACK_BUFF} states.
     */
    SHIELD_PHYS,

    /**
     * The <b>magic-only</b> mirror of {@link #SHIELD_PHYS}: a barrier that absorbs magical damage and lets
     * physical damage through.
     *
     * <p>No shipped character restricts a barrier this way, and it is here deliberately: 「两个单独的通道」 is
     * a symmetric split, and a type vocabulary that could say "physical only" but not "magic only" would be a
     * half-built channel that the next datapack would have to work around. Same ladder (5 rungs, 20% of max
     * health), same carried clock, same parking rule — see {@link #SHIELD_PHYS}.
     *
     * <p><b>Appended last, for the wire-format reason</b> every value after {@link #ATTACK_BUFF} states.
     */
    SHIELD_MAGIC,

    /**
     * 「提升范围半径200内所有友方 50 物理暴击」 — a <b>timed, party-wide crit-chance</b> buff: 千歌's 专属,
     * 静流's 二专 and 铃莓's 二专 each name one.
     *
     * <h2>Why {@link #CRIT_DAMAGE_UP}'s twin, and not a rung ladder</h2>
     * The two clauses are the two halves of one stat sheet — 「暴击率」 and 「暴击伤害」 — and the source gives
     * each as a number, not as a rung: 千歌 50 points (2.5%), 静流 100 (5%), 铃莓 200 (10%) at this build's
     * 1 point = 0.05 percentage points conversion ({@code CritChances.CHANCE_PER_POINT}). A ladder would
     * quantise three numbers the wiki states exactly, and 2.5% is not even on a 5% ladder. So the magnitude
     * travels in a {@code percent} field on the hook and on the ultimate's {@code UbEffect}, exactly as
     * {@link #CRIT_DAMAGE_UP}'s does, and the applier is {@code CritChanceUps}.
     *
     * <h2>How it is carried</h2>
     * A real {@code MobEffect} of this mod's own ({@code astrum:crit_chance_up}) with one {@code ADD_VALUE}
     * modifier on {@code Crit.CRIT_CHANCE}, under a <b>fixed</b> modifier id so a recast refreshes rather than
     * compounds, with the amount in the amplifier (one unit = one percent, the same encoding
     * {@code CritDamageUps} uses). Vanilla adds and removes the modifier over the instance's own lifetime, so
     * this mod contains no expiry code and no tick listener.
     *
     * <p><b>It adds to the character's own chance.</b> {@code astrum:crit_chance} sums every modifier and
     * {@code Crit.chanceOf} clamps at {@code Crit.MAX_CHANCE}, which is {@code Crit}'s existing rule for every
     * source of "you crit more often" — the per-character grants of {@code CritChances} included. A buff that
     * <em>replaced</em> her own chance would be a second rule for the same stat.
     *
     * <p><b>Appended last, for the wire-format reason</b> every value after {@link #ATTACK_BUFF} states.
     */
    CRIT_CHANCE_UP,

    /**
     * 「每秒恢复自身【10+「冰龙之印」】技能值」 — charge-over-time whose instalment <b>scales with the caster's own
     * tally</b> (雪菲's 6★ UB).
     *
     * <h2>Why not {@link #CHARGE_REGEN_SELF}</h2>
     * That type's magnitude is a <em>rung</em>: the amplifier is the points a second, and vanilla merges two
     * instances by taking the stronger one (the human's 「取最强」 rule). This one is a <b>formula over a live
     * count</b> — {@code base + perMark × marks} — which a rung cannot express without re-deriving it every
     * time the tally moves, and whose value must follow the tally <em>during</em> the grant (she spends marks
     * with 技能1 while the regen runs). So the amount is read off {@code Counters} once a tick by the effect
     * itself, and the shape of the grant is data: two numbers, in the hook's own {@code effectParams} block,
     * the same shape {@code COUNTER} uses for 「追加【5+计数器枚数】个」.
     *
     * <h2>The unit</h2>
     * The data's numbers are <b>charge points a second</b>, at the project's fixed 1 point = 10 TP
     * ({@code docs/balance-derivation.md} §14.5): 雪菲's 「10+印」 TP a second is 1.0 + 0.1 a mark. The
     * conversion is the pack author's job for the same reason every other derived number is — the code does
     * not guess a TP rate.
     *
     * <p><b>One per-second mechanism, not two.</b> It pays through {@code ChargeKernel} like every other charge
     * source ({@code UbCharge.apply}), so 「TP上昇」 and the {@code UbChargeEvent} see it, and the per-tick work lives in
     * the effect's own {@code applyEffectTick} — one instance on one entity, not a tick listener.
     *
     * <p><b>Appended last, for the wire-format reason</b> every value after {@link #ATTACK_BUFF} states.
     */
    COUNTER_CHARGE_REGEN,

    /**
     * 「降低敌方全体【100+1.25*技能等级】物理暴击 18 秒」 — the first <b>victim-side crit</b> channel: a timed,
     * negative {@code astrum:crit_chance} modifier on the body the skill landed on.
     *
     * <h2>Why this needed a new type, in the human's words</h2>
     * 「本 mod 的 crit_chance / crit_damage 是玩家侧属性，没有给受害者写暴击率或暴击伤害的通道。」 Both stats are
     * attributes ({@code Crit.CHANCE_PATH} / {@code Crit.DAMAGE_PATH}) read off the entity that is about to swing,
     * and every writer so far — {@code CritChances}, {@code CritChanceUps}, {@code CritDamageUps} — writes the
     * <b>caster's</b> copy. 未奏希's clause is about the body she hit, so the missing half is a writer that puts a
     * modifier on the <em>victim</em>, and this is it. {@link #CRIT_DAMAGE_DOWN} is the other half.
     *
     * <h2>How the magnitude travels, and why it is not a rung</h2>
     * The wiki states a crit <b>score</b> ({@code 100+1.25×L} = 360 points at Lv208), and this build's conversion
     * ({@code CritChances.CHANCE_PER_POINT}, one point = 0.05 percentage points) turns that into <b>18 percentage
     * points</b>. A ladder would quantise a number the source gives exactly, so the magnitude travels as
     * {@code percent} in the hook's {@code effectParams} / the ultimate's effect, exactly as {@link #CRIT_CHANCE_UP}'s
     * does, and the amplifier carries it in <b>tenths of a point</b> ({@code CritDowns.STEPS_PER_PERCENT}).
     *
     * <h2>Why it is a real {@code MobEffect} and not a bookkeeping float</h2>
     * Vanilla adds a {@code MobEffect}'s attribute modifiers when an instance is added and removes them when it
     * expires or is removed — keyed by a fixed modifier id, so a recast refreshes rather than compounds. That is a
     * real removal on expiry with no tick listener and no state to leak, which is exactly what an attribute modifier
     * owes: it must never outlive the effect that declares it.
     *
     * <h2>The honest limitation, stated once</h2>
     * {@code Crit.chanceOf} takes the <b>best single source</b> over this mod's attribute and every installed
     * foreign crit attribute ({@code attributeslib}, {@code apothic_attributes}, {@code irons_spellbooks}). A debuff
     * lowers <em>our</em> attribute's value, so it lowers the winning number whenever ours is the winning one — and
     * a party that installs a stronger foreign crit attribute would mask it. That masking is a property of the one
     * "best source" rule {@code Crit} already documents; it is not worked around here by a second rule, and it does
     * not exist in the pack this build was tested against (none of the three is installed — see {@code Crit}'s
     * javadoc for the scan). {@code CritDowns} says the same thing at the applier.
     *
     * <p><b>Appended last, for the wire-format reason</b> every value after {@link #ATTACK_BUFF} states.
     */
    CRIT_CHANCE_DOWN,

    /**
     * 「暴击时造成的伤害降低 25% 18 秒」 — {@link #CRIT_CHANCE_DOWN}'s second half: a timed, negative
     * {@code astrum:crit_damage} modifier on the victim, so its <b>crits</b> hit softer.
     *
     * <h2>Why two types and not one with two numbers</h2>
     * The clause names two magnitudes that move independently (18 percentage points of chance, 25% of damage), and
     * a {@code MobEffect} carries one amplifier per instance. One type would have to fix a ratio between the two —
     * i.e. invent a number the source does not state — and, because vanilla keys effects by type, would also merge
     * the two halves into one HUD row whose expiry could not differ. Two types are two instances with two clocks,
     * exactly as {@link #CRIT_CHANCE_UP} and {@link #CRIT_DAMAGE_UP} already are on the caster's side.
     *
     * <p>The modifier is {@code ADD_MULTIPLIED_TOTAL} with a negative fraction ({@code -0.25} turns the shipped
     * {@code 1.5} into {@code 1.125}), the same operation {@link #CRIT_DAMAGE_UP} uses in the other direction, so a
     * debuff and a buff on one body compose instead of one replacing the other.
     *
     * <p><b>Appended last, for the wire-format reason</b> every value after {@link #ATTACK_BUFF} states.
     */
    CRIT_DAMAGE_DOWN;

    public static final Codec<EffectType> CODEC = StringRepresentable.fromEnum(EffectType::values);

    /**
     * The effects whose text names <b>the caster and her party</b> — 「味方全体」 and 「自分」.
     *
     * <h2>Why this is declared here and not in an executor</h2>
     * "Who does this effect reach?" is a property of what the effect <em>is</em>, on the same footing as
     * {@link EffectTiers}' ladder: an author reading the enum should be able to see that
     * {@code BUFF_ATTACK_AREA} is a party buff and {@code PANIC} is not. Two callers need the answer and
     * neither owns it — {@code TieredEffects} applies an effect to whichever set it names, and
     * {@link UbEffect#mismatch} refuses a datapack that pairs an effect with the other set. Writing the
     * table twice is how the two halves drift, so it is written once, here, where both can read it.
     *
     * <p>Nine members, and each is 味方全体 or 自分 in the source material: the two percentage heals, the
     * four area buffs (the two attack stats and the two defences), the haste, the charge-rate buff, and
     * {@link #SHIELD} (「对自身展开…护盾」 is a shield on her own side; a radius of {@code 0} keeps it
     * self-only). {@link #BUFF_MAGIC_ATTACK_AREA} joined with the magic-attack split: 凯露's 黑暗之蚀
     * 「提升自身…魔法攻击力」 is 「自身」, and a {@code radius} of {@code 0} is what makes that literal.
     *
     * <p>{@link #SUMMON_SPIRIT}, {@link #COUNTER} and {@link #CONSUME_COUNTER} are here for the same reason and
     * with the same limit: a summon orbits <em>her</em>, a mark is <em>hers</em>, and a spend reads <em>her</em>
     * tally — none of the three is something done to an enemy. A count is self-only by nature, so its
     * {@code radius} is {@code 0} in every shipped use.
     *
     * <p>{@link #TAUNT} is the newest member and the one whose side is least obvious: the <b>taunter</b>
     * carries it, not the taunted mob. See that value for why an effect instance cannot hold "who applied
     * me", and why 望's {@code damage: 0.0} ultimate can only reach a party-scoped effect.
     */
    private static final Set<EffectType> PARTY_SCOPED = Set.of(HEAL_AREA, HEAL_SELF, BUFF_ATTACK_AREA,
            BUFF_PHYS_DEFENCE_AREA, BUFF_MAGIC_DEFENCE_AREA, HASTE, CHARGE_RATE_UP, SHIELD,
            BUFF_MAGIC_ATTACK_AREA, SUMMON_SPIRIT, COUNTER, CONSUME_COUNTER, TRANSFER_CHARGE,
            TRANSFER_HEAL, REGEN_SELF, REGEN_AREA, CHARGE_REGEN_SELF, CHARGE_REGEN_AREA, TAUNT,
            // The two immunity channels: both are a state on the caster's own side (radius 0 keeps a self
            // grant self-only), and neither is a debuff on the enemy. They are party-scoped for the same
            // reason SHIELD is: 「对自身」 is a receiver set, and an ultimate's ally scope is how the data
            // says so.
            PHYS_IMMUNITY, CONTROL_IMMUNITY,
            // 「提升我方全体 28% 暴击伤害」 — a buff on the caster's own side, radius-0 for self-only. Party-scoped
            // for the same reason the four area buffs are: its receiver set is 「我方」, which is exactly what an
            // ultimate's ally scope and a hook's radius express.
            CRIT_DAMAGE_UP,
            // The three typed-shield channels: a barrier is 「对自身」/「味方全体」 exactly as SHIELD is, and the
            // two restricted channels are the same grant restricted to one half of the damage.
            SHIELD_PHYS, SHIELD_MAGIC,
            // 「提升范围内所有友方 50 物理暴击」: a party-wide buff on the caster's side, the crit-chance twin of
            // CRIT_DAMAGE_UP and party-scoped for the same reason.
            CRIT_CHANCE_UP,
            // 「每秒恢复自身【10+印记】技能值」: the gauge is the caster's own, and her tally is hers — the same
            // self-scoped shape CHARGE_REGEN_SELF has, with a magnitude the tally moves.
            COUNTER_CHARGE_REGEN);

    /**
     * The effects whose text names <b>a body the effect lands on</b> — 「敵」.
     *
     * <p>Six, and each is something done <b>to a body</b>: its armour, its magic defence, its ability to act,
     * its absorption, its position ({@link #PULL}), its velocity ({@link #KNOCKBACK}). {@link #PERCENT_DAMAGE}
     * is deliberately absent: it is <em>damage</em>, not a state, so it belongs to whoever owns the damage
     * pathway (a hook's batch, an ultimate's own hits) rather than to an effect application.
     */
    private static final Set<EffectType> VICTIM_SCOPED =
            Set.of(DEFENCE_DOWN, MAGIC_DEFENCE_DOWN, PANIC, REMOVE_SHIELD, PULL, KNOCKBACK,
                    // The two crit debuffs are states on the body the skill landed on (「敵」), not on the caster:
                    // the modifier is written to the victim's own `astrum:crit_*` attributes and expires with the
                    // instance that carried it. That is why they are here and not in PERSISTS_ON_CASTER.
                    CRIT_CHANCE_DOWN, CRIT_DAMAGE_DOWN);

    /**
     * The effects that leave a <b>timed state on the caster's own side</b> — the ones that put something on
     * the player that lasts.
     *
     * <h2>What this answers, and the human's rule behind it</h2>
     * 「瞬发技能就不要显示持续时间了，会在药水栏出现持续时间的，是技能提供的状态，不是技能。」
     * {@code MobEffect}'s bar is a list of <b>states the player is under</b>. So a skill that only deals
     * damage, restores charge, splashes, heals or detonates leaves <em>nothing</em> on the player, and the
     * skill-entry family ({@code impl.effect.SkillEntries}) must produce no entry for it. A skill that does
     * leave something — a party buff, a haste, a shield, a spirit, a mark, a named vanilla effect — gets
     * exactly one entry, lasting as long as the <b>last</b> of those states
     * (「等到全部效果结束了，这个图标再删除。」).
     *
     * <h2>Why this is declared here and not in the entry code</h2>
     * "Is this effect a state or an event?" is a property of what the effect <em>is</em>, exactly as
     * {@link #reachesParty()} and {@link #reachesVictim()} are, and it is read by the code that decides both
     * <b>whether</b> an entry exists and <b>how long</b> it lasts. One table, one reader, no second copy of
     * the classification.
     *
     * <h2>The three that are deliberately absent</h2>
     * <ul>
     *   <li>{@link #PANIC}, {@link #DEFENCE_DOWN}, {@link #MAGIC_DEFENCE_DOWN}, {@link #APPLY_EFFECT},
     *       {@link #STACK}, {@link #REMOVE_SHIELD}, {@link #PULL} and {@link #KNOCKBACK} put their state on a
     *       body the skill landed on, not on the caster — that body's own effect list is where they show, and
     *       a mob has no bar. Counting them here would put 「护甲削弱」 on the caster's bar for a debuff she is
     *       not under. {@code PULL} and {@code KNOCKBACK} are not even states: they are a position write and a
     *       velocity write that finish in the tick they are applied.</li>
     *   <li>{@link #HEAL_AREA} and {@link #HEAL_SELF} are instantaneous: one {@code heal()} per receiver, no
     *       instance, no clock — the reason vanilla's own instant effects have no HUD entry either.</li>
     * </ul>
     *
     * <p>{@link #FLAT_ATTACK_UP} / {@link #FLAT_MAGIC_ATTACK_UP} <b>are</b> in the set, because they have
     * two forms and only one of them is a state: a <em>timed</em> grant raises the stat for a number of
     * seconds, which the player carries exactly like a buff, while the <em>permanent</em> form is an EX
     * passive owned by the party slot's lifetime and finishes with no clock at all. So this table says the
     * family <b>can</b> leave a timed state and the entry rule ({@code SkillEntries}) drops the permanent
     * case by its own {@code ticks == 0} answer — one classification, one place, and no second reading of
     * {@link GrantDuration#isPermanent()} anywhere.
     */
    private static final Set<EffectType> PERSISTS_ON_CASTER = Set.of(ATTACK_BUFF, ALLY_EFFECT,
            BUFF_ATTACK_AREA, BUFF_MAGIC_ATTACK_AREA, BUFF_PHYS_DEFENCE_AREA, BUFF_MAGIC_DEFENCE_AREA,
            HASTE, CHARGE_RATE_UP, SHIELD, SUMMON_SPIRIT, COUNTER, FLAT_ATTACK_UP, FLAT_MAGIC_ATTACK_UP,
            REGEN_SELF, REGEN_AREA, CHARGE_REGEN_SELF, CHARGE_REGEN_AREA, TAUNT,
            PHYS_IMMUNITY, CONTROL_IMMUNITY,
            // The timed crit-damage buff is a state a player carries: it has a clock (18 s), it shows nothing in
            // vanilla's bar (a mechanic, hidden like the rest) and it is read by Crit.damageOf, which is what
            // makes it matter. 「药水栏是『我身上的状态』」 — this is one.
            CRIT_DAMAGE_UP,
            // A barrier is a state with a clock, whichever half it absorbs: all three channels persist on the
            // caster and get the skill entry that lasts as long as the last of them.
            SHIELD_PHYS, SHIELD_MAGIC,
            // The timed crit-chance buff is the same kind of state as the crit-damage one: a clock, a real
            // attribute modifier and a reader (Crit.chanceOf) that makes it matter.
            CRIT_CHANCE_UP,
            // 「每秒恢复自身…技能值」 is carried by a real effect instance whose clock is its lifetime, so the
            // row lasts as long as the gauge keeps filling.
            COUNTER_CHARGE_REGEN);

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@return whether this effect's receiver set is the caster and her party (「味方全体」/「自分」)} */
    public boolean reachesParty() {
        return PARTY_SCOPED.contains(this);
    }

    /** {@return whether this effect's receiver set is the bodies it lands on (「敵」)} */
    public boolean reachesVictim() {
        return VICTIM_SCOPED.contains(this);
    }

    /**
     * {@return whether this effect leaves a <b>timed state on the caster</b> — i.e. something the player
     * carries for a while}
     *
     * <p>The predicate behind 「药水栏是『我身上的状态』」: a skill with no such effect is instantaneous and gets
     * <b>no</b> bar entry at all, and a skill with one gets an entry that lasts as long as the last of them.
     * See {@link #PERSISTS_ON_CASTER} for the rule and for the three families deliberately left out.
     */
    public boolean persistsOnCaster() {
        return PERSISTS_ON_CASTER.contains(this);
    }

    /** {@return the party-scoped types, for a message or an assertion} */
    public static Set<EffectType> partyScoped() {
        return PARTY_SCOPED;
    }

    /** {@return the victim-scoped types, for a message or an assertion} */
    public static Set<EffectType> victimScoped() {
        return VICTIM_SCOPED;
    }

    /** {@return the types that outlive their own application on the caster, for a message or a test} */
    public static Set<EffectType> persistingOnCaster() {
        return PERSISTS_ON_CASTER;
    }
}
