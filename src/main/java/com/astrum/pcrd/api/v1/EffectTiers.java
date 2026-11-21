package com.astrum.pcrd.api.v1;

import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>one</b> table of tiers: how many rungs each tiered {@link EffectType} has, and how much one
 * rung is worth.
 *
 * <h2>Why tier is a shared concern and not five fields per effect</h2>
 * Ten effects × five tiers as {@code tier1..tier5} would be fifty numbers per effect, fifty places for a
 * rung to be missing, and no single answer to "what does tier 3 of a heal do". Instead <b>every</b>
 * tiered effect carries one {@code tier} integer ({@link TieredParams}) and this class answers what that
 * integer is worth. A tier is then an ordinal into a ladder, not a field: adding a rung is one number
 * here, and an author writes {@code "tier": 3} once whether the effect is a heal, a buff or a debuff.
 *
 * <h2>The shape</h2>
 * <pre>
 *   Rule(maxTier, stepPerTier)     value(tier) = stepPerTier × tier
 * </pre>
 *
 * <p>A {@code stepPerTier} is a fraction of something the effect names: of the receiver's max health
 * (the two heals), of its attack damage (the attack buff), of incoming damage (the two defence buffs),
 * of the victim's armour (the armour break), of its absorption hearts (the shield break), of its max
 * health again (percent damage), or a multiplier added to {@code 1.0} (charge rate). Tier 1 is therefore
 * always "one step", never zero: a tiered effect at its lowest rung must still <em>do</em> something, or
 * the skill that names it would silently do nothing.
 *
 * <h2>The percentages, and why the defence pair is not 20</h2>
 * <ul>
 *   <li>heal / attack / armour break / percent damage: <b>20%</b> a tier. The top rung is 100% of max
 *       health (a full heal), +100% attack (double damage) and −80% armour, all intended.</li>
 *   <li>the two defence buffs: <b>12%</b> a tier, topping at <b>60%</b>. Twenty percent a tier was
 *       considered and rejected: five rungs of it would reach 100% damage reduction, i.e. immunity, and
 *       vanilla's own {@code resistance} demonstrates the problem — it is 20% a level, so level V is
 *       exactly 100% and the holder cannot be hurt at all. Sixty percent is the ceiling that stays a
 *       buff rather than turning the party invulnerable.</li>
 *   <li>charge rate: <b>+25%</b> a tier, topping at {@code 2.25×}.</li>
 * </ul>
 *
 * <h2>Why the clamping lives here and not in each codec</h2>
 * A datapack is content: {@code "tier": 7} on a five-rung effect must not be able to switch the effect
 * off, apply a sixth rung, or crash the load. {@link #resolve} therefore answers the nearest legal tier
 * <b>and says whether it had to clamp</b>, so the executor can log it once — the write-once diagnostic
 * the rest of this project uses for bad data — while the arithmetic downstream only ever sees a legal
 * number. Nothing here throws.
 *
 * @see TieredParams the one {@code effectParams} shape that carries a tier
 */
@ApiStatus.Experimental
public final class EffectTiers {

    /** The lowest tier. One-based, because a datapack author counts rungs. */
    public static final int FIRST_TIER = 1;

    /**
     * One rung's worth, per effect type.
     *
     * @param maxTier     how many rungs this effect has
     * @param stepPerTier what one rung is worth, as the fraction the effect's javadoc names
     */
    @ApiStatus.Experimental
    public record Rule(int maxTier, double stepPerTier) {

        public Rule {
            if (maxTier < FIRST_TIER) {
                throw new IllegalArgumentException("an effect must have at least one tier: " + maxTier);
            }
        }

        /**
         * {@return the fraction {@code tier} rungs are worth}
         *
         * @param tier an already-clamped tier in {@code [1, maxTier]}
         */
        public double fractionAt(int tier) {
            return stepPerTier * tier;
        }
    }

    /**
     * The table. <b>Every</b> tiered effect appears exactly once; an effect missing here is not tiered
     * and its hooks carry no {@code tier} field at all.
     *
     * <p>The five-slot effects that shipped before this one ({@code extra_damage} and friends) are
     * deliberately absent: they take their magnitude from the datapack and their strength from the
     * multiplier chain, so a tier would be a second, competing scale.
     */
    private static final Map<EffectType, Rule> RULES = Map.ofEntries(
            // 「味方全体HP中回復」 / 「自分のHP中回復」 — 20/40/60/80/100% of the receiver's max health.
            Map.entry(EffectType.HEAL_AREA, new Rule(5, 0.20)),
            Map.entry(EffectType.HEAL_SELF, new Rule(5, 0.20)),
            // 「味方全体の物理攻撃力アップ」 — +20/40/60/80/100% attack damage.
            Map.entry(EffectType.BUFF_ATTACK_AREA, new Rule(5, 0.20)),
            // 「魔法攻击力アップ」 — the magic half of the pair, and deliberately NOT the same step as the
            // physical one: +26/52/78/104/130%.
            //
            // The human's rule: 「在原本物理加成的基础上，再加百分5-10%，比如原本物理是20%，魔法就是25%-30%」,
            // and he chose 26%. The reason the two rungs are not equivalent is arithmetic, not taste:
            // 物理攻击 is ATTACK_DAMAGE, whose base for a player is ~1.0-2.0, so a +20% modifier is a
            // meaningful absolute gain. 魔法攻击力 is astrum:magic_damage, whose base is 0 (MagicAttack's
            // DEFAULT_VALUE) and which is written as FLAT POINTS — fraction × 2000, because a
            // multiplicative modifier on a base-0 attribute is a no-op. So "20%" on each side is 20% of
            // two very different quantities, and the magic side needs the larger rung to feel comparable.
            //
            // 26% = 20% × 1.3, i.e. a third stronger per rung, and 2 percentage points above physical at
            // every tier — which is exactly his 5-10% band measured as the *increment* over physical
            // (2/20 = 10%). At tier 5 that is damage scale 2.60 against physical's 2.00. He accepted that
            // gap explicitly. AttackAreaRules owns both steps; docs/balance-derivation.md §16 shows the
            // arithmetic so a future author can retune one constant.
            Map.entry(EffectType.BUFF_MAGIC_ATTACK_AREA, new Rule(5, 0.26)),
            // 「物理防御力アップ」 / 「魔法防御力アップ」 — 12/24/36/48/60% damage reduction. See the
            // class javadoc for why this pair is 12 and not 20.
            Map.entry(EffectType.BUFF_PHYS_DEFENCE_AREA, new Rule(5, 0.12)),
            Map.entry(EffectType.BUFF_MAGIC_DEFENCE_AREA, new Rule(5, 0.12)),
            // 「物理・魔法防御力をダウン」 — 20/40/60/80% of the victim's armour removed.
            Map.entry(EffectType.DEFENCE_DOWN, new Rule(4, 0.20)),
            // 「魔法防御力をダウン」 — the magic half, and the human's explicit instruction: *"Tiered 4 @ 20%,
            // same ladder as DEFENCE_DOWN"*. The step is 20% of the damage the victim *takes* (a magic
            // damage increase) rather than 20% of an attribute, because there is no magic armour attribute
            // to remove — see MAGIC_DEFENCE_DOWN's javadoc. The two share a ladder on purpose: a pack that
            // knows one rung knows the other.
            Map.entry(EffectType.MAGIC_DEFENCE_DOWN, new Rule(4, 0.20)),
            // バリアブレイク — one rung, all of it. A ladder of "how much of the shield" would be four
            // numbers nobody asked for; the skill is "remove the barrier".
            Map.entry(EffectType.REMOVE_SHIELD, new Rule(1, 1.00)),
            // 「行動速度アップ」 — the step is 1 because this effect's magnitude is a vanilla amplifier,
            // not a fraction: tier 1..3 is amplifier 0..2. See {@code HasteRules}.
            Map.entry(EffectType.HASTE, new Rule(3, 1.00)),
            // 恐慌 — one rung. Duration, not magnitude, is what a panic scales, and the duration is the pack's
            // (`PanicRules.ticksFor`: 绫音 2.5/3 s, 胡桃 5.5 s, 凯露 8 s, 1.5 s when none is declared).
            Map.entry(EffectType.PANIC, new Rule(1, 1.00)),
            // 「最大HPの X% ダメージ」 — 10/20/30/40/50% of the victim's max health.
            Map.entry(EffectType.PERCENT_DAMAGE, new Rule(5, 0.10)),
            // 「TP上昇」 — +25/50/75/100/125% charge gain, i.e. a multiplier of 1.25 .. 2.25.
            Map.entry(EffectType.CHARGE_RATE_UP, new Rule(5, 0.25)),
            // 護盾 (absorption) — 20/40/60/80/100% of the receiver's max health, the same 20% step and the
            // same five rungs as the two heals: a shield and a heal are the same quantity of health, spent
            // before it rather than after it. Tier 5 is a full health bar of shield, intended.
            Map.entry(EffectType.SHIELD, new Rule(5, 0.20)),
            // The two typed channels: the same ladder, the same rung worth, because only *what the barrier
            // absorbs* differs. 由衣's 「45+45×技能等级」 = 9405 raw is tier 2 by the same conversion SHIELD uses,
            // so restricting a barrier costs it nothing; a restriction that also shrank the barrier would be
            // two changes where the source asks for one.
            Map.entry(EffectType.SHIELD_PHYS, new Rule(5, 0.20)),
            Map.entry(EffectType.SHIELD_MAGIC, new Rule(5, 0.20)),
            // 召唤精灵 — the step is 1 because the magnitude is the vanilla AMPLIFIER, not a fraction: tier
            // 1..5 is one..five spirits, and the amplifier (tier - 1) is what an instance carries. Read by
            // Spirits.countForTier; the particle cost is one particle per spirit per tick.
            Map.entry(EffectType.SUMMON_SPIRIT, new Rule(5, 1.00)),
            // 计数 — the step is 4 because one rung of a tally is worth four MARKS, not four percent: tier 1..5
            // caps the count at 4/8/12/16/20. The top rung's 20 is 雪菲's 6★ 「最多可获得20个」; the human's
            // 「最高…20层」 is this number. Counters.capForTier reads it, and Counters.tierForStatedCap maps a
            // bwiki cap that is not on the ladder to the smallest rung that does not truncate it (her base
            // ultimate's 「最大10个」 -> tier 3 = 12). A second table of caps would be the drift this class
            // exists to prevent, so there is none.
            Map.entry(EffectType.COUNTER, new Rule(5, 4.00)),
            // 「他充」 — 6/12/18/24/30 charge points to one receiver. The step is 6 because
            // `ubChargePerKill` is 20 and `ubChargePerDamage` is 1: one kill is worth about a tier-3 cast and
            // one sword hit about a sixth of a tier-1 cast, so a skill that spends its slot handing charge to
            // a teammate is worth a fifth to a third of a bar. Deriving it from those two numbers rather than
            // inventing a percentage keeps it in the same currency the gauge already uses.
            Map.entry(EffectType.TRANSFER_CHARGE, new Rule(5, 6.00)),
            // 「他奶」 — the instant heal's own ladder, because it IS the instant heal: 「他奶就是套用瞬间回血的那个效果」.
            // 20/40/60/80/100% of the receiver's max health, the same step and rung count as HEAL_SELF, so a
            // pack moving a heal from self to teammate does not have to relearn the numbers.
            Map.entry(EffectType.TRANSFER_HEAL, new Rule(5, 0.20)),
            // 「缓慢回血」 — the step is 1 because the magnitude is vanilla's AMPLIFIER, not a fraction:
            // regeneration I..V is amplifier 0..4. A percentage here would be a number regeneration does not
            // read; see OverTimeEffects for the mapping and for what the amplifiers actually heal.
            Map.entry(EffectType.REGEN_SELF, new Rule(5, 1.00)),
            Map.entry(EffectType.REGEN_AREA, new Rule(5, 1.00)),
            // 「缓慢恢复充能」 — 4/8/12/16/20 charge points a second, and again the step is 1 because the
            // magnitude IS the amplifier: the per-instance tick reads
            // OverTimeEffects.chargePointsPerSecond(amplifier). Not a percentage of anything, so a fraction
            // would be a second scale for one number.
            Map.entry(EffectType.CHARGE_REGEN_SELF, new Rule(5, 1.00)),
            Map.entry(EffectType.CHARGE_REGEN_AREA, new Rule(5, 1.00)),
            // 「位移」 — one BLOCK a rung, 1..5. The step is 1 because the magnitude is a distance in blocks,
            // not a fraction of anything: tier 3 is three blocks of travel toward the caster, which is what
            // bwiki's 「拉到身前 300」 comes to on the ÷100 scale the radius table uses (300 → 3, 100 → 1).
            //
            // Why the rung is the DISTANCE TRAVELLED and not the gap left to the caster: a melee hook fires on
            // a target already inside melee reach, so "end up 3 blocks away" would be a no-op or a push —
            // EffectType.PULL's javadoc has the full argument. The band still applies, so a high-level
            // character's pull reaches one or two rungs further, which is the same rule every tiered effect
            // follows; the ceiling of 5 keeps a level-95 pull at 5 blocks rather than 8.
            Map.entry(EffectType.PULL, new Rule(5, 1.00)),
            // 「击退」 — strength 0.4·N, i.e. 0.4 / 0.8 / 1.2 / 1.6 / 2.0. 0.4 is not a round number chosen
            // here: it is the strength `LivingEntity#hurt` itself passes to `knockback` at the end of every
            // damage sequence (verified in 21.1.265), so tier 1 means "exactly as hard as the hit already
            // pushed it" and a pack that wants more says how many times more. The step is 0.4 rather than a
            // fraction because the magnitude IS a vanilla strength, the same shape HASTE's amplifier uses.
            Map.entry(EffectType.KNOCKBACK, new Rule(5, 0.40)),
            // 「嘲讽」 — one rung. Duration, not magnitude, is what a taunt scales (10 s / 11.5 s in 望's two
            // tiers), and it travels in `seconds` like every other timed effect. A ladder of "how many mobs"
            // would be a second reach competing with the radius field that already says how far it calls.
            Map.entry(EffectType.TAUNT, new Rule(1, 1.00)),
            // 「物理无敌」 — one rung, because it is immunity and not a magnitude. A five-rung ladder of 20% a
            // rung would duplicate BUFF_PHYS_DEFENCE_AREA's reduction ladder, and the source's 「5档」 is
            // resistance V's rung count, not something this channel should copy. The duration travels in
            // `seconds`, exactly as TAUNT's does.
            Map.entry(EffectType.PHYS_IMMUNITY, new Rule(1, 1.00)),
            // 「控制免疫」 — one rung for the same reason: the effect is "panic cannot land", and a rung would
            // be a second scale for a boolean. Its lifetime is `seconds` (180 s in 矛依未's 6★ UB).
            Map.entry(EffectType.CONTROL_IMMUNITY, new Rule(1, 1.00)));

    private EffectTiers() {
    }

    /**
     * {@return the rule for {@code type}, or {@code null} when this effect is not tiered}
     *
     * @param type the effect type, may be {@code null}
     */
    public static Rule ruleOf(EffectType type) {
        return type == null ? null : RULES.get(type);
    }

    /** {@return whether this effect type has tiers at all} */
    public static boolean isTiered(EffectType type) {
        return ruleOf(type) != null;
    }

    /**
     * {@return how many rungs {@code type} has, or {@code 0} when it is not tiered}
     *
     * @param type the effect type, may be {@code null}
     */
    public static int maxTier(EffectType type) {
        Rule rule = ruleOf(type);
        return rule == null ? 0 : rule.maxTier();
    }

    /**
     * {@return the nearest legal tier for {@code type}, and whether the declared one had to move}
     *
     * <p>Three answers, and the reason each is what it is:
     * <ul>
     *   <li><b>in range</b> — used as written, {@code clamped} false;</li>
     *   <li><b>out of range</b> — pulled into {@code [1, maxTier]}, {@code clamped} true, so a
     *       {@code "tier": 7} on a five-rung effect still does its strongest thing instead of nothing,
     *       and the author is told;</li>
     *   <li><b>not tiered, or nonsense</b> — tier {@code 1} and {@code clamped} true, because an effect
     *       that is not tiered has exactly one rung and every hook must land somewhere.</li>
     * </ul>
     *
     * <p>Never throws: this is reached from a damage path and from a codec, where a hostile number in a
     * pack must become a logged no-op rather than an exception.
     *
     * @param type     the effect whose ladder applies
     * @param declared the tier as written, possibly {@code 0}, negative or absurd
     */
    public static Resolved resolve(EffectType type, int declared) {
        Rule rule = ruleOf(type);
        if (rule == null) {
            return new Resolved(FIRST_TIER, declared != FIRST_TIER);
        }
        int clamped = Math.max(FIRST_TIER, Math.min(rule.maxTier(), declared));
        return new Resolved(clamped, clamped != declared);
    }

    /**
     * A tier after clamping.
     *
     * @param tier    the legal tier, in {@code [1, maxTier]}
     * @param clamped whether the declared value differed, i.e. whether the author should be told
     */
    @ApiStatus.Experimental
    public record Resolved(int tier, boolean clamped) {
    }
}
