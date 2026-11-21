package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.AllySelector;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two effect values added for コッコロ / ペコリーヌ / ｷｬﾙ — {@code HEAL}, {@code ALLY_EFFECT} and
 * {@code DEFENCE_DOWN} — as arithmetic and as wire format.
 *
 * <h2>Why these are new values and not approximations</h2>
 * The task said each gap had to be either filled properly or approximated with the reason written down,
 * and never quietly dropped. {@code lifesteal} was rejected for a heal (it ties the heal to damage dealt,
 * so a healer who misses heals nobody), and {@code minecraft:weakness} was rejected for an armour break
 * (it lowers <b>attack</b>, not the damage the victim takes). {@code Heals} and {@code DefenceDownEffect}
 * carry the full argument; this file asserts the consequences.
 *
 * <h2>What cannot be asserted here</h2>
 * No {@code ServerPlayer} exists in a unit test, so the <b>application</b> of a heal to a live entity, and
 * the attribute-modifier arithmetic vanilla performs for the armour debuff, stay unasserted. What is
 * asserted is every rule that decides <em>who</em> and <em>how much</em> — the parts that were wrong in
 * the two defects this project has already shipped — through the same pure seams
 * {@code AttackBuffsTest} uses.
 */
class NewEffectTypesTest {

    private static final UUID CASTER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID NEAR = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID FAR = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static List<AllySelector.Candidate> party() {
        return List.of(
                new AllySelector.Candidate(CASTER, true, Optional.empty(), 0.0),
                new AllySelector.Candidate(NEAR, false, Optional.empty(), 3.0),
                new AllySelector.Candidate(FAR, false, Optional.empty(), 40.0));
    }

    private static List<UUID> ids(List<AllySelector.Candidate> candidates) {
        return candidates.stream().map(AllySelector.Candidate::id).toList();
    }

    // ------------------------------------------------------------------ the enum and the codec

    /**
     * The new values are <b>appended</b>, so every ordinal that shipped before keeps its position.
     *
     * <p>{@code EffectType}'s names are the wire format and a pack that names an unknown value fails to
     * decode rather than being misread — but the ordinals are what a serialized enum-free path would
     * break on, and {@code SkillIcons}' map and the lang keys are keyed by it.
     *
     * <p>The count moved 12 → 22 with the generic effect kernel, 22 → 24 with the affection rework's two
     * new values ({@code SHIELD}, {@code MAGIC_DEFENCE_DOWN}), 24 → 25 with task 38's magic attack buff
     * ({@code BUFF_MAGIC_ATTACK_AREA}), and 42 → 46 with task 60's four (the two typed channels, the timed
     * crit-chance buff and the tally-scaled charge regen). Those are the <em>only</em> numbers allowed to
     * change here; every ordinal is pinned so an insertion in the middle — which would silently re-key a
     * serialized enum — fails rather than passing.
     */
    @Test
    void theNewValuesAreAppendedAfterTheShippedNine() {
        assertEquals(0, EffectType.APPLY_EFFECT.ordinal(), "the shipped order must not move");
        assertEquals(8, EffectType.ATTACK_BUFF.ordinal());
        assertEquals(9, EffectType.HEAL.ordinal());
        assertEquals(10, EffectType.ALLY_EFFECT.ordinal());
        assertEquals(11, EffectType.DEFENCE_DOWN.ordinal());
        // The ten tiered values, appended in the order the brief's table lists them.
        assertEquals(12, EffectType.HEAL_AREA.ordinal());
        assertEquals(13, EffectType.HEAL_SELF.ordinal());
        assertEquals(14, EffectType.BUFF_ATTACK_AREA.ordinal());
        assertEquals(15, EffectType.BUFF_PHYS_DEFENCE_AREA.ordinal());
        assertEquals(16, EffectType.BUFF_MAGIC_DEFENCE_AREA.ordinal());
        assertEquals(17, EffectType.REMOVE_SHIELD.ordinal());
        assertEquals(18, EffectType.HASTE.ordinal());
        assertEquals(19, EffectType.PANIC.ordinal());
        assertEquals(20, EffectType.PERCENT_DAMAGE.ordinal());
        assertEquals(21, EffectType.CHARGE_RATE_UP.ordinal());
        // 護盾 and 「魔法防御力をダウン」, appended last for the same ordinal reason.
        assertEquals(22, EffectType.SHIELD.ordinal());
        assertEquals(23, EffectType.MAGIC_DEFENCE_DOWN.ordinal());
        // task 38: the magic attack buff, appended last for the same ordinal reason. It is the only value
        // this task adds, and adding it at the END is what keeps every ordinal above stable.
        assertEquals(24, EffectType.BUFF_MAGIC_ATTACK_AREA.ordinal());
        // task 39: the two flat EX effects, appended last for the same ordinal reason. Appending is the
        // whole point — every ordinal above stays put, so a pack written against the previous build reads
        // identically.
        assertEquals(25, EffectType.FLAT_ATTACK_UP.ordinal());
        assertEquals(26, EffectType.FLAT_MAGIC_ATTACK_UP.ordinal());
        // task 42: the summon, the tally and the spend, appended last for the same ordinal reason.
        assertEquals(27, EffectType.SUMMON_SPIRIT.ordinal());
        assertEquals(28, EffectType.COUNTER.ordinal());
        assertEquals(29, EffectType.CONSUME_COUNTER.ordinal());
        // task 49: the two transfer effects and the four over-time ones, appended last for the same ordinal
        // reason every task above appended last — a pack written against the previous build reads identically.
        assertEquals(30, EffectType.TRANSFER_CHARGE.ordinal());
        assertEquals(31, EffectType.TRANSFER_HEAL.ordinal());
        assertEquals(32, EffectType.REGEN_SELF.ordinal());
        assertEquals(33, EffectType.REGEN_AREA.ordinal());
        assertEquals(34, EffectType.CHARGE_REGEN_SELF.ordinal());
        assertEquals(35, EffectType.CHARGE_REGEN_AREA.ordinal());
        // task 56: the displacement, the knockback and the taunt, appended last for the same ordinal reason.
        assertEquals(36, EffectType.PULL.ordinal());
        assertEquals(37, EffectType.KNOCKBACK.ordinal());
        assertEquals(38, EffectType.TAUNT.ordinal());
        // task 55 appended its two constants after task 56's three, for the same "never reorder" reason.
        assertEquals(39, EffectType.PHYS_IMMUNITY.ordinal());
        assertEquals(40, EffectType.CONTROL_IMMUNITY.ordinal());
        // task 58: the timed crit-damage buff, appended last for the same ordinal reason — a pack written against
        // the previous build reads identically, and 千歌's 6★ UB clause finally has an effect type of its own.
        assertEquals(41, EffectType.CRIT_DAMAGE_UP.ordinal());
        // task 60: the two typed shield channels, the timed crit-chance buff, and the tally-scaled charge
        // regen — appended last, in that order, for the reason every task above appended last.
        assertEquals(42, EffectType.SHIELD_PHYS.ordinal());
        assertEquals(43, EffectType.SHIELD_MAGIC.ordinal());
        assertEquals(44, EffectType.CRIT_CHANCE_UP.ordinal());
        assertEquals(45, EffectType.COUNTER_CHARGE_REGEN.ordinal());
        // task 62: the two crit debuffs, appended last for the same ordinal reason.
        assertEquals(46, EffectType.CRIT_CHANCE_DOWN.ordinal());
        assertEquals(47, EffectType.CRIT_DAMAGE_DOWN.ordinal());
        assertEquals(48, EffectType.values().length);
    }

    /** Each new value round-trips through the real hook codec, under the name the JSON uses. */
    @Test
    void eachNewHookRoundTripsThroughTheRealCodec() {
        String heal = """
                {"trigger":"melee_hit","effect":"heal",
                 "effectParams":{"amount":6.9,"radius":0.0}}""";
        EffectHook decodedHeal = EffectHook.CODEC
                .parse(JsonOps.INSTANCE, JsonParser.parseString(heal)).getOrThrow();
        EffectHook.Heal asHeal = assertInstanceOf(EffectHook.Heal.class, decodedHeal);
        assertEquals(EffectType.HEAL, asHeal.type());
        assertEquals(6.9, asHeal.amount(), 1e-9);
        assertEquals(OptionalDouble.of(0.0), asHeal.radius(),
                "radius 0 is 「自分」, written explicitly, not an absent party default");

        String ally = """
                {"trigger":"melee_hit","effect":"ally_effect","triggerParams":{"skill":"speed_up"},
                 "effectParams":{"effect":"minecraft:speed","duration":240,"amplifier":0,"radius":8.0}}""";
        EffectHook.AllyEffect asAlly = assertInstanceOf(EffectHook.AllyEffect.class,
                EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(ally)).getOrThrow());
        assertEquals(EffectType.ALLY_EFFECT, asAlly.type());
        assertEquals("minecraft:speed", asAlly.effect().toString());
        assertEquals(240, asAlly.duration());
        assertEquals(0, asAlly.amplifier());
        assertEquals(OptionalDouble.of(8.0), asAlly.radius());
        assertEquals(Optional.of("speed_up"), asAlly.triggerParams().skillTag());

        String debuff = """
                {"trigger":"melee_hit","effect":"defence_down",
                 "effectParams":{"tier":3,"seconds":12.0}}""";
        EffectHook.DefenceDown asDebuff = assertInstanceOf(EffectHook.DefenceDown.class,
                EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(debuff)).getOrThrow());
        assertEquals(EffectType.DEFENCE_DOWN, asDebuff.type());
        // DefenceDown is tiered since the generic-effect kernel: the author writes a rung, not an amplifier
        // and a duration in ticks. Tier 3 of 4 is -60% armour; see the tier-table test below.
        assertEquals(3, asDebuff.params().tier(), "the rung as written survives the codec");
        assertEquals(OptionalDouble.of(12.0), asDebuff.params().seconds());
    }

    /**
     * A hook that omits {@code radius} keeps loading — the additive promise every optional field here
     * makes, and the reason a pack written before these values keeps working at all.
     */
    @Test
    void anOmittedRadiusStillDecodes() {
        String json = """
                {"trigger":"melee_hit","effect":"heal","effectParams":{"amount":3.4}}""";
        EffectHook.Heal heal = assertInstanceOf(EffectHook.Heal.class,
                EffectHook.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow());
        assertTrue(heal.radius().isEmpty(), "absent means the party default, answered by Heals.radiusFor");
    }

    // ------------------------------------------------------------------ the heal's receivers

    /**
     * A {@code radius: 0} heal reaches exactly the caster; a party-radius one reaches every ally inside it
     * and nobody outside — the same rule, the same kernel, as the attack buff's.
     */
    @Test
    void aZeroRadiusHealIsSelfOnlyAndAPartyRadiusReachesTheParty() {
        assertEquals(List.of(CASTER), ids(Heals.receivers(party(), CASTER,
                        AllyMode.ALL_PLAYERS, 0.0)),
                "radius 0 = 「自分」: コッコロ's base オーロラ heals only her");
        assertEquals(List.of(CASTER, NEAR), ids(Heals.receivers(party(), CASTER,
                        AllyMode.ALL_PLAYERS, 8.0)),
                "「味方全体」 = the caster and the ally inside 8 blocks, not the one at 40");
    }

    /**
     * The radius reading is {@code AttackBuffs.radiusFor}'s — <b>not a second copy</b>.
     *
     * <p>This is asserted as behaviour rather than by reading the source: the three answers (absent →
     * default, zero → self, nonsense → default) must come out identical for both hooks, because if they
     * ever diverged a heal and a buff written the same way would reach different people.
     */
    @Test
    void theHealReadsARadiusExactlyAsTheAttackBuffDoes() {
        for (OptionalDouble declared : List.of(OptionalDouble.empty(), OptionalDouble.of(0.0),
                OptionalDouble.of(8.0), OptionalDouble.of(12.5), OptionalDouble.of(-1.0),
                OptionalDouble.of(Double.NaN))) {
            assertEquals(AttackBuffs.radiusFor(declared), Heals.radiusFor(declared),
                    "the two hooks must read a radius identically; differed for " + declared);
        }
    }

    /** A null or degenerate input answers an empty set rather than throwing, on a damage path. */
    @Test
    void theHealDegradesSafely() {
        assertEquals(List.of(), Heals.receivers(null, CASTER, AllyMode.ALL_PLAYERS, 8.0));
        assertEquals(List.of(), Heals.receivers(party(), null, AllyMode.ALL_PLAYERS, 8.0));
        assertEquals(List.of(), Heals.receivers(party(), CASTER, AllyMode.ALL_PLAYERS, Double.NaN));
        assertEquals(0, Heals.apply(null, 5.0F, ResourceLocation.parse("astrum:kokkoro"),
                OptionalDouble.empty()), "a null caster heals nobody");
    }

    /**
     * A non-positive amount restores nothing and says so — it must not heal and must not damage.
     *
     * <p>This is the one arithmetic rule {@code Heals} owns beyond the receiver set, and it matters
     * because the amount arrives from {@code EffectQuantities.scaled}, which can legitimately produce
     * zero.
     */
    @Test
    void aNonPositiveHealAmountRestoresNothing() {
        ResourceLocation kokkoro = ResourceLocation.parse("astrum:kokkoro");
        assertEquals(0, Heals.apply(null, 0.0F, kokkoro, OptionalDouble.empty()));
        assertEquals(0, Heals.apply(null, -3.0F, kokkoro, OptionalDouble.empty()));
        assertEquals(0, Heals.apply(null, Float.NaN, kokkoro, OptionalDouble.empty()));
    }

    // ------------------------------------------------------------------ the ally effect's receivers

    /** The same receiver set for {@code ally_effect}: キャル's/コッコロ's party buffs reach the same people. */
    @Test
    void theAllyEffectUsesTheSamePartyKernel() {
        assertEquals(List.of(CASTER, NEAR), ids(PartyVanillaEffects.receivers(party(), CASTER,
                        AllyMode.ALL_PLAYERS, 8.0)),
                "「味方全体」 means the caster and every ally inside the radius");
        assertEquals(List.of(), PartyVanillaEffects.receivers(null, CASTER, AllyMode.ALL_PLAYERS, 8.0));
        assertEquals(0, PartyVanillaEffects.apply(null, ResourceLocation.parse("minecraft:speed"), 100, 0,
                ResourceLocation.parse("astrum:kokkoro"), OptionalDouble.empty()));
    }

    /** An unknown effect id is a logged no-op, never an exception and never a silent wrong effect. */
    @Test
    void anUnknownAllyEffectIdIsRefusedRatherThanApplied() {
        assertEquals(0, PartyVanillaEffects.apply(null, ResourceLocation.parse("astrum:not_a_real_effect"),
                        100, 0, ResourceLocation.parse("astrum:kokkoro"), OptionalDouble.empty()),
                "an unknown effect can reach no receiver");
        assertEquals(0, PartyVanillaEffects.apply(null, null, 100, 0,
                ResourceLocation.parse("astrum:kokkoro"), OptionalDouble.empty()));
    }

    // ------------------------------------------------------------------ the armour debuff's numbers

    /**
     * The armour debuff's ladder: <b>tier N removes 20N% of armour</b>, and tier 1 is already a real penalty.
     *
     * <h2>Why this replaced the old {@code stepFor(amplifier)} assertions</h2>
     * The previous revision encoded the penalty over a <em>vanilla amplifier</em> with no top: {@code
     * penalty = (amplifier + 1) × 0.15}, so an author writing {@code "amplifier": 20} got a 315% armour
     * removal nobody designed. The generic-effect task replaced that with a 4-rung ladder the brief fixes
     * (20/40/60/80%), so the assertions move from "amplifier N is (N+1) steps" to "tier N is N steps, and
     * there is no tier 5". Both halves of that change are asserted here.
     *
     * <p>The property that survived unchanged is the important one: <b>amplifier 0 is still a real
     * penalty</b>, not "+0%". A defence-down hook that computed to nothing would be a skill whose main
     * function silently does nothing — the opposite of {@code AttackBuffRules}, where amplifier 0 is
     * deliberately no-op.
     */
    @Test
    void theArmourPenaltyIsTwentyPercentARung() {
        assertEquals(DefenceDownRules.STEP, DefenceDownRules.penaltyForAmplifier(0), 1e-12,
                "amplifier 0 must still remove armour, unlike AttackBuffRules where 0 is +0%");
        assertEquals(2 * DefenceDownRules.STEP, DefenceDownRules.penaltyForAmplifier(1), 1e-12);
        assertEquals(3 * DefenceDownRules.STEP, DefenceDownRules.penaltyForAmplifier(2), 1e-12);
        assertNotEquals(0.0, DefenceDownRules.penaltyForAmplifier(0), "a zero penalty would be the silent drop");

        // The four rungs, by tier, exactly the numbers the brief fixed.
        assertEquals(0.20, DefenceDownRules.penaltyForTier(1), 1e-12);
        assertEquals(0.40, DefenceDownRules.penaltyForTier(2), 1e-12);
        assertEquals(0.60, DefenceDownRules.penaltyForTier(3), 1e-12);
        assertEquals(0.80, DefenceDownRules.penaltyForTier(4), 1e-12, "tier 4 = 80%, the documented ceiling");
        assertEquals(DefenceDownRules.MAX_PENALTY, DefenceDownRules.penaltyForTier(4), 1e-12);
    }

    /**
     * A hostile tier clamps instead of inverting, and the ceiling is reachable but never passed.
     *
     * <p>80% and not 100% is deliberate: removing the armour attribute entirely would drive a weak mob's
     * armour <em>negative</em>, and an armour break should not be a one-shot. A {@code "tier": 9} therefore
     * lands on 80% with one warning rather than inventing a fifth, deeper rung.
     */
    @Test
    void hostileTiersClampInsteadOfInverting() {
        assertEquals(DefenceDownRules.STEP, DefenceDownRules.penaltyForAmplifier(-5), 1e-12,
                "a negative amplifier must not invert the sign and buff the victim's armour");
        double ceiling = DefenceDownRules.penaltyForTier(DefenceDownRules.MAX_TIER);
        assertEquals(ceiling, DefenceDownRules.penaltyForTier(10_000), 1e-12, "the ceiling clamps, it does not throw");
        assertEquals(ceiling, DefenceDownRules.penaltyForAmplifier(255), 1e-12);
        assertTrue(DefenceDownRules.isRepresentable(1));
        assertTrue(DefenceDownRules.isRepresentable(4));
        assertFalse(DefenceDownRules.isRepresentable(0), "tiers are 1-based");
        assertFalse(DefenceDownRules.isRepresentable(DefenceDownRules.MAX_TIER + 1));
    }

    /** The fraction is a real ratio, positive, and below 1 at the top rung — an armour penalty, not a removal. */
    @Test
    void oneStepIsASmallPositiveFraction() {
        assertTrue(DefenceDownRules.STEP > 0.0 && DefenceDownRules.STEP < 1.0,
                "one step must remove some armour and not all of it: " + DefenceDownRules.STEP);
        assertTrue(DefenceDownRules.penaltyForTier(DefenceDownRules.MAX_TIER) < 1.0,
                "even tier 4 must not compute to -100% armour, which would remove the attribute");
    }

    /** The amplifier encoding is one function with one inverse, so tier and amplifier cannot drift. */
    @Test
    void theTierAndAmplifierEncodingsAreInverses() {
        for (int tier = 1; tier <= DefenceDownRules.MAX_TIER; tier++) {
            assertEquals(tier - 1, DefenceDownRules.amplifierForTier(tier),
                    "vanilla's amplifier is 0-based and an author's tier is 1-based");
            assertEquals(tier, DefenceDownRules.tierForAmplifier(tier - 1));
        }
        assertEquals(0, DefenceDownRules.amplifierForTier(0), "tier 0 clamps to amplifier 0, it does not go negative");
        assertEquals(DefenceDownRules.MAX_TIER, DefenceDownRules.tierForAmplifier(255));
    }

    /** The two modifier ids differ, or vanilla would overwrite one half of the penalty with the other. */
    @Test
    void theArmourAndToughnessModifiersHaveDistinctIds() {
        assertNotEquals(DefenceDownEffect.ARMOUR_MODIFIER_ID, DefenceDownEffect.TOUGHNESS_MODIFIER_ID,
                "the two modifiers must have different ids, or one replaces the other");
        assertEquals("astrum", DefenceDownEffect.ARMOUR_MODIFIER_ID.getNamespace());
    }

    // ------------------------------------------------------------------ additive by construction

    // The two tests that stood here — "the new types are not silently self-buffs" and "every new type has
    // its own icon" — both asked questions of SkillIcons, which no longer exists: a generic effect carries no
    // picture any more ("通用效果现在不需要图标，只作为代码引用"). The self-buff question is now answered by
    // the effect system itself (ATTACK_BUFF is the only self-buff type in EffectType), and the icon question
    // has no subject at all. They are removed rather than rewritten, because a test whose premise is gone
    // would only be able to assert something weaker in the same name.

    /** A hook of each new type still names its trigger and its skill tag, so the rotation can group it. */
    @Test
    void theNewHooksCarryTheirSkillTag() {
        TriggerParams tagged = new TriggerParams(0, Optional.empty(), Optional.of("speed_up"));
        assertEquals(Optional.of("speed_up"),
                new EffectHook.AllyEffect(Trigger.MELEE_HIT, tagged, 1.0,
                        ResourceLocation.parse("minecraft:speed"), 240, 0, OptionalDouble.of(8.0))
                        .triggerParams().skillTag());
        assertEquals(Optional.of("lunch_time"),
                new EffectHook.Heal(Trigger.MELEE_HIT,
                        new TriggerParams(0, Optional.empty(), Optional.of("lunch_time")), 1.0, 6.9,
                        OptionalDouble.of(0.0)).triggerParams().skillTag());
    }
}
