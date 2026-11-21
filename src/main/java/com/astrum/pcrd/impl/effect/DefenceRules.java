package com.astrum.pcrd.impl.effect;

import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;

/**
 * The numbers and the <b>one</b> classification rule behind the two defence buffs.
 *
 * <h2>Why a percentage, and why this class owns it</h2>
 * {@code BUFF_PHYS_DEFENCE_AREA} and {@code BUFF_MAGIC_DEFENCE_AREA} reduce incoming damage by 12% a tier,
 * topping at 60%. Vanilla's {@code resistance} cannot express that and cannot be split: it is a flat 20% a
 * level — so level V is 100%, i.e. invulnerability — and it reduces <em>every</em> damage type except the
 * ones tagged {@code bypasses_resistance}. The brief wants two buffs and a 60% ceiling, so both the number
 * and the split are this mod's own, and they are decided here rather than in two effects that could drift.
 *
 * <p>One step constant, {@link #STEP}, and one table, {@code EffectTiers}. {@code EffectTiersTest} asserts
 * the two agree, so the ladder an author picks and the arithmetic the engine does are one number.
 *
 * <h2>The split: what counts as magic</h2>
 * Minecraft has <b>no</b> {@code is_magic} damage-type tag. Read from {@code DamageTypeTags} in 21.1.265,
 * the tags that exist are {@code is_fire}, {@code is_projectile}, {@code is_explosion}, {@code is_freeze},
 * {@code is_lightning}, {@code is_fall}, {@code is_drowning} — and there is nothing for magic. So the
 * marker has to be ours, and there are exactly two of them, both shipped by this mod:
 *
 * <ol>
 *   <li>the damage type {@code astrum:magic} itself ({@link #MAGIC_DAMAGE_TYPE}), which this mod's own
 *       magical carriers declare through a hook's {@code damageType} field; and</li>
 *   <li>the tag {@code astrum:is_magic} ({@link #MAGIC_TAG}), so a pack — or another mod, by data — can
 *       mark its <b>own</b> damage types as magical without this mod knowing them.</li>
 * </ol>
 *
 * <p><b>The limitation, stated plainly:</b> a damage type that is neither {@code astrum:magic} nor in
 * {@code astrum:is_magic} is treated as <b>physical</b>. Another mod's magic damage therefore lands in the
 * physical bucket — its caster gets the physical buff's reduction and not the magical one's — until that
 * mod (or a datapack) adds its types to the tag. There is no way to tell "this was meant to be magic" from
 * a damage type this mod does not own, and guessing from the type's name would silently mislabel somebody
 * else's fireball as magic. That is a documented boundary, not an oversight.
 */
@ApiStatus.Internal
public final class DefenceRules {

    /** One tier's reduction, as a fraction: 12% — see the class javadoc for why not 20%. */
    public static final double STEP = 0.12;

    /** The highest tier either buff has. Kept here so the effect's curve and the tier table agree. */
    public static final int MAX_TIER = 5;

    /** The fraction tier {@link #MAX_TIER} reduces damage by: 60%, the documented ceiling. */
    public static final double MAX_REDUCTION = STEP * MAX_TIER;

    /** Registry path of the physical buff: {@code astrum:phys_defence}. */
    public static final String PHYS_PATH = "phys_defence";

    /** Registry path of the magical buff: {@code astrum:magic_defence}. */
    public static final String MAGIC_PATH = "magic_defence";

    /**
     * Registry path of the <b>flat</b> physical-defence passive: {@code astrum:phys_defence_flat}.
     *
     * <p>A second type rather than a second amplifier meaning, because vanilla merges by type: sharing
     * {@code astrum:phys_defence} would make an EX passive and a timed buff one HUD row, and the later grant
     * would overwrite the earlier one's amplifier.
     */
    public static final String PHYS_FLAT_PATH = "phys_defence_flat";

    /**
     * The damage type this mod owns to mark magic damage: {@code astrum:magic}.
     *
     * <p>A real datapack entry ({@code data/astrum/damage_type/magic.json}), so a hook can name it in
     * {@code "damageType"} and there is something for the classification below to recognise.
     */
    public static final String MAGIC_DAMAGE_PATH = "magic";

    /** The tag a pack can add its own damage types to, to be treated as magical. */
    public static final String MAGIC_TAG_PATH = "is_magic";

    private DefenceRules() {
    }

    /**
     * {@return the fraction {@code tier} reduces damage by, in {@code [0, MAX_REDUCTION]}}
     *
     * <p>Never throws and never answers a value above {@link #MAX_REDUCTION}: this runs on a damage path
     * where a hostile amplifier must become a logged no-op, never immunity and never a negative reduction
     * (which would be a <em>vulnerability</em> wearing the buff's name).
     *
     * @param tier the clamped tier, 1-based
     */
    public static double reductionFor(int tier) {
        int clamped = Math.max(0, Math.min(MAX_TIER, tier));
        return STEP * clamped;
    }

    /**
     * One tier's <b>armour percentage</b>: 20%, so the five rungs are +20/40/60/80/100% of the armour the
     * player already has.
     *
     * <h2>Why a percentage of armour and not a flat number of points</h2>
     * 「百分比增加护甲值」 — the human's words. A flat ladder (say 2/4/6/8/10 points) means the same thing to
     * a naked player and to one in netherite, which makes it either worthless or overpowered depending on
     * who casts it. A fraction of the total scales with the caster's gear, so one rung is worth the same
     * <em>proportion</em> everywhere — which is the property {@link #STEP} already has for the reduction
     * layer, and the two layers now read the same way.
     *
     * <p>100% at rung 5 doubles her armour. That is deliberately generous: it is a five-rung party buff with
     * a duration, and vanilla's own armour curve means doubling 20 points is worth much less than twice the
     * reduction of 0 points — the diminishing returns are the balance, not this number.
     */
    public static final double ARMOUR_STEP = 0.20;

    /**
     * {@return the armour fraction an amplifier grants}
     *
     * <p>Read by {@code PhysDefenceEffect}'s attribute modifier. Clamped like every other curve here: a
     * hostile amplifier becomes the ceiling, never a negative (which would be a <em>vulnerability</em>
     * wearing the buff's name) and never more than {@link #MAX_TIER} rungs.
     *
     * @param amplifier the effect's level, {@code 0}-based
     */
    public static double armourFractionForAmplifier(int amplifier) {
        int rung = Math.max(0, Math.min(MAX_TIER - 1, amplifier));
        return ARMOUR_STEP * (rung + 1);
    }

    /**
     * One tier's <b>flat armour points</b> for the EX/SP form: 4, so the rungs are 4/8/12/16/20.
     *
     * <h2>Why the passive form is flat and the buff form is a fraction</h2>
     * 「只有 ex 或者 sp 技能里面加物理防御的，才是增加固定的护甲值」 — an EX is a permanent passive the player
     * carries from enrolment, so it has to be worth something on a naked character; a fraction of zero armour
     * would be zero. The timed buff keeps the fraction, where scaling with gear is the point.
     *
     * <p>Four points a rung is roughly one armour piece a rung: leather is +1..3, iron +2..6, a full iron set
     * is 15. So a five-rung passive is worth about a full set on its own — large, but it occupies an EX slot
     * that could have been attack, which is the trade the human is choosing.
     */
    public static final double ARMOUR_POINTS_STEP = 4.0;

    /**
     * {@return the flat armour points an amplifier grants}
     *
     * <p>Read by {@code PhysDefenceEffect}'s flat form. Clamped like every other curve here.
     *
     * @param amplifier the effect's level, {@code 0}-based
     */
    public static double armourPointsForAmplifier(int amplifier) {
        int rung = Math.max(0, Math.min(MAX_TIER - 1, amplifier));
        return ARMOUR_POINTS_STEP * (rung + 1);
    }

    /**
     * {@return the amplifier a tier is expressed as}
     *
     * <p>The effect's level curve reads the amplifier, so the tier has to travel in it. {@code tier 1 →
     * amplifier 0} keeps the weakest buff a real buff — the same "one step at the bottom rung" rule
     * {@code DefenceDownRules} follows, and the opposite of {@code AttackBuffRules}, where amplifier 0 is
     * "+0%" — with the difference that here a +0% defence buff would still be harmless, and the tier is
     * 1-based because a datapack author counts rungs.
     *
     * @param tier the clamped tier, 1-based
     */
    public static int amplifierFor(int tier) {
        return Math.max(0, Math.min(MAX_TIER, tier) - 1);
    }

    /** {@return the tier an amplifier expresses — the inverse of {@link #amplifierFor}} */
    public static int tierFor(int amplifier) {
        return Math.max(1, Math.min(MAX_TIER, amplifier + 1));
    }

    /** {@return which of the two buffs a registry path names} */
    public static Kind kindOf(String path) {
        return MAGIC_PATH.equals(path == null ? null : path.toLowerCase(Locale.ROOT))
                ? Kind.MAGIC : Kind.PHYSICAL;
    }

    /** Which half of the incoming damage a buff reduces. */
    public enum Kind {
        /** Everything not marked magical. */
        PHYSICAL,
        /** Everything marked magical. */
        MAGIC;

        /** {@return the registry path of this kind's effect} */
        public String path() {
            return this == MAGIC ? MAGIC_PATH : PHYS_PATH;
        }

        /** {@return the translation key of this kind's effect name} */
        public String nameKey() {
            return "effect.astrum." + path();
        }
    }
}
