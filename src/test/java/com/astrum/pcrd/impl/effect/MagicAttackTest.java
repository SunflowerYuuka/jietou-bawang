package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.ExPassive;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>魔法攻击力: the stat, the split, and the four EX skills' landing sites.</b>
 *
 * <h2>What is asserted here, and what is not</h2>
 * The stat's arithmetic is pure ({@link MagicAttack#scaleOf}), so it is provable without a game: the default
 * is 0, an unchanged caster is exactly 1.0, the reference caster is exactly 2.0 after a +100% buff, and
 * hostile numbers answer 1.0 rather than shrinking a hit. The two effects' <em>declarations</em> are also
 * assertable — a {@code MobEffect}'s attribute templates are read through
 * {@code createModifiers}, which needs no level.
 *
 * <p>What <b>cannot</b> be asserted here is the wiring that connects them inside a running game: that the
 * attribute is present on a real {@code ServerPlayer}'s attribute map, that a {@code MobEffectInstance}
 * applies and expires the modifier, and that a {@code hurt()} call ends in the expected health delta. Those
 * need a server, and the task report quotes the probe that ran one. The boundary is stated rather than
 * papered over: this file proves the <em>values</em> the runtime consumes, not the runtime.
 */
class MagicAttackTest {

    /** The reference point every derivation in this project uses (§14.1 of balance-derivation.md). */
    private static final int LEVEL = 208;
    private static final double REFERENCE = 2000.0;

    private static final String[] SHIPPED = {"djeeta", "kokkoro", "pecorine", "kyaru"};

    // ------------------------------------------------------------------ helpers

    private static CharacterDef load(String id) {
        String path = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id
                + ".json";
        try (InputStream in = MagicAttackTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(id + ".json is not on the test classpath: " + path);
            }
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
                    .getOrThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SkillLadder exOf(String id) {
        CharacterDef def = load(id);
        return ExPassives.ladderOf(def).orElseThrow(() -> new AssertionError(id + " has no ex ladder"));
    }

    /** {@return every EX clause of {@code id}, across every rung, flattened} */
    private static List<ExPassive.Clause> clausesOf(String id) {
        List<ExPassive.Clause> clauses = new ArrayList<>();
        ExPassive passive = exOf(id).passive().orElseThrow();
        for (int tier = 1; tier <= 3; tier++) {
            Optional<ExPassive.Rung> rung = passive.rungFor(tier);
            rung.ifPresent(value -> clauses.addAll(value.clauses()));
        }
        return clauses;
    }

    // ------------------------------------------------------------------ 1. the stat exists and is 0

    /**
     * <b>The attribute is registered as a real {@link Attribute}, at {@code astrum:magic_damage}.</b>
     *
     * <p>Asserted on the declared object rather than through the registry: a unit-test JVM has no
     * {@code RegisterEvent}, so {@code MagicAttack.MAGIC_DAMAGE.isBound()} is false here and the value
     * cannot be fetched. What <em>is</em> checkable is the shape the registration will produce — a
     * {@link RangedAttribute} with the documented default and ceiling — plus the id and the lang key, which
     * are the two things a rename would break silently.
     */
    @Test
    void theAttributeIsRangedAndNamedForTheStat() {
        assertEquals("magic_damage", MagicAttack.PATH);
        assertEquals("astrum:magic_damage", MagicAttack.ID.toString(),
                "the id a datapack, a command and a save all name");
        assertEquals("attribute.astrum.magic_damage", MagicAttack.NAME_KEY,
                "vanilla builds the description id from the string the Attribute is constructed with, so "
                        + "this constant and the constructor must be the same key");
        assertEquals("attribute.astrum.magic_damage"
                        .substring("attribute.".length()), MagicAttack.ID.toLanguageKey(),
                "and the key must be the id's language key, not a third spelling");
        // Constructed the way the registration lambda constructs it, so the numbers are the shipped ones.
        Attribute attribute = new RangedAttribute(MagicAttack.NAME_KEY, MagicAttack.DEFAULT_VALUE, 0.0,
                MagicAttack.MAX_VALUE);
        assertTrue(attribute instanceof RangedAttribute, "a ranged attribute clamps hostile values");
        assertEquals(MagicAttack.DEFAULT_VALUE, attribute.getDefaultValue(),
                "the default the constructor is handed is the documented 0");
        assertEquals(MagicAttack.NAME_KEY, attribute.getDescriptionId(),
                "vanilla resolves the name through the description id, which is the string it was "
                        + "constructed with");
        // isClientSyncable() is a mutable flag set by setSyncable(true) in the registration lambda, so it is
        // false on a locally constructed instance. Asserting it here would be asserting the test's own
        // construction, not the shipped one; the probe on a real server is what confirms the sync flag.
    }

    /**
     * <b>The default is 0, and that is what makes a non-caster unaffected.</b>
     *
     * <p>The two claims this pins are the same claim: the constant is 0, and a scale computed from 0 is
     * exactly {@code 1.0} — not 0.99, not "close to one". A default of {@code ATTACK_DAMAGE}'s 2.0 would
     * give every mob and every sword a magic attack it never had.
     */
    @Test
    void theDefaultIsZeroAndAnUntouchedCasterIsExactlyUnchanged() {
        assertEquals(0.0, MagicAttack.DEFAULT_VALUE,
                "a non-caster has no magic attack; the default must be 0, not ATTACK_DAMAGE's 2.0");
        assertEquals(1.0, MagicAttack.scaleOf(MagicAttack.DEFAULT_VALUE),
                "0 magic attack is EXACTLY a factor of 1.0 — the identity that makes this feature additive");
        assertEquals(1.0, MagicAttack.scaleOf(0.0), "and it is the same number written the other way");
        assertEquals(1.0, MagicAttack.scaleOf(-50.0),
                "a hostile negative must not shrink a hit — the floor is 1.0, not 'less than one'");
        assertEquals(1.0, MagicAttack.scaleOf(Double.NaN), "NaN must not poison a health bar");
        assertEquals(1.0, MagicAttack.scaleOf(Double.POSITIVE_INFINITY),
                "and infinity must not one-shot everything");
        assertEquals(1.0, MagicAttack.magicScale(null), "no entity is 'no magic stat', not an exception");
    }

    /**
     * <b>A body with a magic stat deals more, and the factor is exactly the reference arithmetic.</b>
     *
     * <pre>
     *   factor = 1 + magic_damage / 2000
     *   +400  -> 1.2        +1000 -> 1.5        +2000 -> 2.0 (a +100% buff, the tier-5 ceiling)
     * </pre>
     *
     * <p>2000 is not a new number: it is the reference attack every amount in
     * {@code docs/balance-derivation.md} §14 was derived against, so a stat relative to it is on the same
     * scale as the amounts it multiplies.
     */
    @Test
    void aMagicStatScalesTheHitAgainstTheSameReferenceTheAmountsUse() {
        assertEquals(2000.0, MagicAttack.REFERENCE_ATTACK,
                "the derivation's own reference attack; a second reference point would be a second scale");
        assertEquals(1.2, MagicAttack.scaleOf(400.0), 1e-12);
        assertEquals(1.5, MagicAttack.scaleOf(1000.0), 1e-12);
        assertEquals(2.0, MagicAttack.scaleOf(2000.0), 1e-12, "a +100% buff is exactly double damage");

        // Worked on the number 凯露's 闪电球 actually deals at the reference point (§14.2: 7380 -> 6.7).
        float authored = 6.7F;
        assertEquals(6.7F, (float) (authored * MagicAttack.scaleOf(0.0)), 1e-6F,
                "with no magic stat her hit is unchanged, to the float");
        assertEquals(13.4F, (float) (authored * MagicAttack.scaleOf(2000.0)), 1e-5F,
                "and a fully buffed caster doubles it");
    }

    // ------------------------------------------------------------------ 2. the two buffs write two attributes

    /**
     * <b>The magic buff's rung is additive points, and it is the physical buff's fraction of the reference.</b>
     *
     * <p>This is the one place the two halves cannot be symmetric, and the reason is arithmetic: the magic
     * attribute's base is 0, so an {@code ADD_MULTIPLIED_TOTAL} modifier there is {@code 0 × 1.6 = 0} — a
     * registered effect that changes no number. So the rung is applied as {@code fraction × 2000} points of
     * {@code ADD_VALUE}, which is what makes tier 5 double a reference caster.
     *
     * <p>Both facts are asserted together because either alone is misleading: "it is additive" says nothing
     * about whether the ladder still means 20% a rung, and "the ladder is shared" says nothing about whether
     * the operation suits the attribute.
     */
    @Test
    void theMagicBuffAddsReferencePointsAtTwentySixPercentARung() {
        for (int tier = 1; tier <= 5; tier++) {
            int amplifier = AttackAreaRules.amplifierForTier(tier);
            // 26% a rung, not 20%: the human's 「在原本物理加成的基础上，再加百分5-10%」 applied to the stat
            // whose base is 0 and which is therefore written as flat points. He chose 26%.
            assertEquals(0.26 * tier * MagicAttack.REFERENCE_ATTACK,
                    MagicAttackAreaEffect.pointsForAmplifier(amplifier), 1e-9,
                    "tier " + tier + " is " + (26 * tier) + "% of the reference, as points");
            assertEquals(1.0 + 0.26 * tier,
                    MagicAttack.scaleOf(MagicAttackAreaEffect.pointsForAmplifier(amplifier)), 1e-12,
                    "tier " + tier + " must multiply a reference caster's magic damage by "
                            + (1.0 + 0.26 * tier));
        }
        // Tier 5: 130% of the reference = 2600 points = damage scale 2.30, against the physical buff's 2.00.
        // (An earlier note in the brief said 2.60; that would need a 32% rung. 1 + 0.26×5 = 2.30, and the
        // 5-10% band is expressed as the *increment* over physical: 2 percentage points on 20 = 10%.)
        assertEquals(2.30, MagicAttack.scaleOf(MagicAttackAreaEffect.pointsForAmplifier(
                        AttackAreaRules.amplifierForTier(5))), 1e-12,
                "tier 5 is 2.30× a reference caster's magic damage, where the physical buff reaches 2.00");
        assertEquals(2.00, 1.0 + AttackAreaRules.fractionForTier(5), 1e-12,
                "and the physical buff's own tier 5 is still exactly 2.00");
        // A negative amplifier is a buff that adds nothing, never a debuff: AttackAreaRules clamps to tier 1.
        assertEquals(0.26 * MagicAttack.REFERENCE_ATTACK,
                MagicAttackAreaEffect.pointsForAmplifier(-5), 1e-9,
                "a hostile amplifier must not become a negative contribution on a beneficial effect");
    }

    /**
     * <b>The two attack buffs declare modifiers on two different attributes, under two different ids.</b>
     *
     * <p>Read through {@code createModifiers}, which is how vanilla enumerates an effect's templates —
     * no level, no player, so this runs in a unit-test JVM. It is the assertion that would catch the
     * tempting one-line "fix" of pointing {@code BUFF_MAGIC_ATTACK_AREA} at {@code ATTACK_DAMAGE}: the two
     * types would still be distinct and still carry a ladder, and only the attribute would be wrong.
     *
     * <p><b>What this cannot see.</b> The magic template is only present when the attribute's
     * {@code DeferredHolder} is bound, and it is not bound in a unit-test JVM (no {@code RegisterEvent}). So
     * this asserts the <b>physical</b> half unconditionally, and asserts the magic half's <em>rule</em>
     * through {@link MagicAttackAreaEffect#pointsForAmplifier} above. The report says so; the probe on a
     * real server is what confirms the bound case.
     */
    @Test
    void thePhysicalBuffWritesOnlyThePhysicalAttribute() {
        List<ResourceLocation> ids = new ArrayList<>();
        List<String> attributes = new ArrayList<>();
        new AttackAreaEffect().createModifiers(3, (attribute, modifier) -> {
            attributes.add(attribute.unwrapKey().map(key -> key.location().toString()).orElse("(unbound)"));
            ids.add(modifier.id());
        });
        assertEquals(List.of("minecraft:generic.attack_damage"), attributes,
                "BUFF_ATTACK_AREA raises the stat a sword reads, and only that one: 姬塔 / 可可萝 / "
                        + "佩可莉姆 are physical, and a second attribute here would raise a stat they never "
                        + "read. (The id keeps vanilla's historical 'generic.' prefix.)");
        assertEquals(List.of(AttackAreaEffect.MODIFIER_ID), ids,
                "one fixed id, so a recast replaces rather than compounds");
        assertEquals(0.80, ids.isEmpty() ? -1.0 : modifierAmount(new AttackAreaEffect(), 3), 1e-12,
                "amplifier 3 is tier 4, +80%");
        assertFalse(attributes.contains(MagicAttack.ID.toString()),
                "and NOT astrum:magic_damage — that is MagicAttackAreaEffect's job");
    }

    /**
     * <b>The two buffs' modifier ids differ, or alternating recasts would cancel each other.</b>
     *
     * <p>{@code addAttributeModifiers} removes a modifier <em>by id</em> before adding its replacement, so
     * two effects sharing an id on one attribute make the second cancel the first. They are on different
     * attributes today, which is why a shared id would work by accident — and would break the moment either
     * half was pointed at the other attribute. Pinning the ids makes that accident impossible.
     */
    @Test
    void theTwoBuffsUseDistinctModifierIds() {
        assertFalse(AttackAreaEffect.MODIFIER_ID.equals(MagicAttackAreaEffect.MODIFIER_ID),
                "two effects on one attribute must not share a modifier id; here they are on two attributes "
                        + "and must still be distinguishable, because vanilla removes by id");
        assertEquals(MagicAttack.BUFF_MODIFIER_ID, MagicAttackAreaEffect.MODIFIER_ID,
                "the magic buff writes under the id MagicAttack.setBonus also uses, so the operator path "
                        + "and the buff path replace each other instead of compounding");
        assertFalse(MagicAttack.BUFF_MODIFIER_ID.equals(AttackAreaEffect.MODIFIER_ID),
                "and that id is not the physical one");
    }

    /** {@return the amount of the first modifier the effect declares at {@code amplifier}} */
    private static double modifierAmount(net.minecraft.world.effect.MobEffect effect, int amplifier) {
        double[] found = {Double.NaN};
        effect.createModifiers(amplifier, (attribute, modifier) -> found[0] = modifier.amount());
        return found[0];
    }

    // ------------------------------------------------------------------ 3. the four EX skills

    /**
     * <b>凯露's EX raises {@code magic_damage}; the other three keep {@code ATTACK_DAMAGE}.</b>
     *
     * <p>The four bwiki rows, re-read from the source and matched to the stat each one names:
     *
     * <pre>
     *   姬塔    龙卷的攻刃    「提升自身【15+15*技能等级】物理攻击力」   -> BUFF_ATTACK_AREA       (physical)
     *   可可萝  精灵的启示    「提升自身【15+15*技能等级】物理攻击力」   -> BUFF_ATTACK_AREA       (physical)
     *   佩可莉姆 公主之力      「提升自身【75+75*技能等级】生命值」       -> health_boost           (max health)
     *   凯露    黑暗之蚀      「提升自身【15+15*技能等级】魔法攻击力」   -> BUFF_MAGIC_ATTACK_AREA (magic)
     * </pre>
     *
     * <p>The check is the <b>effect type each clause names</b>, because that is what decides which attribute
     * the passive writes — {@code ExPassives.pathOf} maps the type to a registered effect, and the effect
     * carries the modifier. Two claims are therefore each pinned: 凯露's clause is the magic type, and no
     * physical character's is.
     */
    @Test
    void theFourExSkillsLandOnTheStatTheirWordNames() {
        // 凯露: the one magic character, and the one whose EX was landing on the wrong stat. Task 39 also
        // made it FLAT (「EX…都是数值提升，而不是百分比」), so the type is FLAT_MAGIC_ATTACK_UP rather than the
        // percentage BUFF_MAGIC_ATTACK_AREA this assertion used to pin — but the stat it lands on, and the
        // reason, are unchanged.
        for (ExPassive.Clause clause : clausesOf("kyaru")) {
            assertEquals(EffectType.FLAT_MAGIC_ATTACK_UP, clause.type().orElseThrow(),
                    "黑暗之蚀 says 魔法攻击力, so every rung must name the magic stat; the physical one "
                            + "would raise a stat her astrum:magic damage never reads");
            assertTrue(clause.amount().isPresent(), "and it is a point formula, as the bwiki gives it");
        }

        // The two physical characters: 姬塔 and 可可萝 name the physical attack stat, 佩可莉姆 names no
        // attack type at all (hers is max health), and none of them may reach for the magic stat.
        for (String id : new String[] {"djeeta", "kokkoro"}) {
            boolean sawAttack = false;
            for (ExPassive.Clause clause : clausesOf(id)) {
                if (clause.type().isEmpty()) {
                    continue;   // 可可萝's vanilla/magdef clauses
                }
                assertFalse(clause.type().orElseThrow() == EffectType.FLAT_MAGIC_ATTACK_UP,
                        id + " is a physical character; her EX must not raise magic_damage");
                if (clause.type().orElseThrow() == EffectType.FLAT_ATTACK_UP) {
                    sawAttack = true;
                }
            }
            assertTrue(sawAttack, id + "'s EX must raise the physical attack stat");
        }
        for (ExPassive.Clause clause : clausesOf("pecorine")) {
            assertTrue(clause.type().isEmpty(),
                    "公主之力 raises max health through vanilla's own effect, not an attack stat of ours");
            assertTrue(clause.vanilla().isPresent());
        }
    }

    /**
     * <b>凯露's damage is all filed {@code astrum:magic} — which is what makes the stat matter.</b>
     *
     * <p>Raising a stat is worthless if nothing reads it. Every carrier she has declares the magic damage
     * type: the 闪电球 hook, its {@code +} splash, and both ultimate tiers. {@code DamageReduction.isMagic}
     * answers true for that type, which is what routes a hit through
     * {@link MagicAttack#scaleFor} — the same predicate the victim's magic defence buff uses, so attacker and
     * defender cannot disagree about what "magic" is.
     *
     * <p>The other three's damage is deliberately <b>not</b> asserted to be physical by type: they declare
     * no {@code damageType} at all, which means {@code astrum:hook_damage}, which
     * {@code DamageReduction.isMagic} answers false for. That is the physical bucket, and it is the same fact
     * stated negatively.
     */
    @Test
    void kyarusDamageIsAllMagicalSoTheStatItRaisesIsTheOneItReads() {
        CharacterDef kyaru = load("kyaru");
        ResourceLocation magic = MagicAttack.SHIPPED_MAGIC_TYPE;
        assertEquals("astrum:magic", magic.toString());

        EffectType type = kyaru.effects().get(0).type();
        assertEquals(EffectType.EXTRA_DAMAGE, type);
        assertEquals(Optional.of(magic),
                ((com.astrum.pcrd.api.v1.EffectHook.ExtraDamage) kyaru.effects().get(0)).damageType(),
                "闪电球 is 「魔法伤害」, so it must be filed astrum:magic");
        assertEquals(Optional.of(magic), kyaru.ub().base().damageType(),
                "格林炸裂 is 「敌方全体造成魔法伤害」");
        assertEquals(Optional.of(magic), kyaru.ub().enhanced().damageType(),
                "深渊炸裂 is the same element");

        // Her 专属 splash is the third magical carrier and is easy to forget: it is on the tier-2 ladder.
        boolean sawSplash = false;
        for (var hook : com.astrum.pcrd.impl.growth.AffectionKernel.hooksAt(kyaru, 2)) {
            if (hook instanceof com.astrum.pcrd.api.v1.EffectHook.Splash splash) {
                assertEquals(Optional.of(magic), splash.damageType(),
                        "闪电球+'s 范围魔法伤害 must be magical too, or part of her output would read the "
                                + "physical stat and her EX would be half a buff again");
                sawSplash = true;
            }
        }
        assertTrue(sawSplash, "her tier-2 splash must exist; otherwise this test proved nothing about it");

        // The physical three declare no damage type, i.e. astrum:hook_damage, i.e. not magic.
        for (String id : new String[] {"djeeta", "kokkoro", "pecorine"}) {
            CharacterDef def = load(id);
            for (var hook : def.effects()) {
                if (hook instanceof com.astrum.pcrd.api.v1.EffectHook.ExtraDamage extra) {
                    assertTrue(extra.damageType().isEmpty(),
                            id + "'s damage declares no type, so it is physical — the opposite of 凯露's");
                }
            }
            assertTrue(def.ub().base().damageType().isEmpty(), id + "'s ultimate is physical");
            assertTrue(def.ub().enhanced().damageType().isEmpty(), id + "'s 6★ ultimate is physical");
        }
    }

    /**
     * <b>EX gets no level band, for the new clause exactly as for the old one.</b>
     *
     * <p>The human's rule is a property of EX, not of one effect type, so adding a type must not create a
     * second resolution path. {@code ExPassives.tierOf} hard-codes band 0 and that is what this checks
     * against the band a normal hook of the same type would get at level 95.
     */
    @Test
    void theMagicExClauseIsStillBandFree() {
        ExPassive.Clause clause = ExPassive.Clause.of(EffectType.BUFF_MAGIC_ATTACK_AREA,
                com.astrum.pcrd.api.v1.TieredParams.ofTier(1));
        assertEquals(1, ExPassives.tierOf(clause), "an EX clause resolves as declared");
        int withBand = TieredAmounts.tierOf(EffectType.BUFF_MAGIC_ATTACK_AREA, clause.params(),
                com.astrum.pcrd.impl.growth.LevelBands.bonusAt(95));
        assertEquals(4, withBand,
                "this is what a hook of the same type resolves to at level 95; EX must not");
        for (ExPassive.Clause shipped : clausesOf("kyaru")) {
            assertEquals(shipped.params().tier(), ExPassives.tierOf(shipped),
                    "every shipped magic EX clause is band-free");
        }
    }

    // ------------------------------------------------------------------ 4. the tier table

    /**
     * <b>The two attack buffs' ladders are now DIFFERENT, and the magic one is stronger.</b>
     *
     * <h2>This assertion is inverted from what it used to say</h2>
     * The previous task asserted the two rows were the same {@code Rule} <em>instance</em>, on the reading
     * that "tier 3 must mean one thing across the stat sheet". Task 39's human instruction reversed that
     * reading, and the reason is arithmetic rather than taste:
     *
     * <blockquote>「物理攻击提升和魔法攻击提升的两个buff分开，因为物理攻击通常情况下等同于攻击伤害，而魔法攻击的数值是0
     * 开始的，所以魔法攻击提升buff的数值会比物理攻击提升更高，大约5%-10%」</blockquote>
     *
     * <p>Physical attack is {@code ATTACK_DAMAGE}, whose base for a player is ~1-2, so {@code +20%} is a
     * meaningful absolute gain. Magic attack is {@code astrum:magic_damage}, whose base is <b>0</b> and which
     * this mod writes as <b>flat points</b> ({@code fraction × 2000}) precisely because a multiplicative
     * modifier on a zero base is a no-op. So "20% of each" are 20% of two incomparable quantities, and he
     * wants the magic rung 5-10% stronger. He then chose <b>26%</b> over 25% and 30%.
     *
     * <p>The ladder is still <b>one</b> mechanism — one amplifier mapping, five rungs, one
     * {@code AttackAreaRules} — only the step constant differs. A shared step would have made the pair
     * indistinguishable, which is what his instruction explicitly rules out.
     */
    @Test
    void bothAttackLaddersAreNowDifferentAndMagicIsStronger() {
        com.astrum.pcrd.api.v1.EffectTiers.Rule physical =
                com.astrum.pcrd.api.v1.EffectTiers.ruleOf(EffectType.BUFF_ATTACK_AREA);
        com.astrum.pcrd.api.v1.EffectTiers.Rule magic =
                com.astrum.pcrd.api.v1.EffectTiers.ruleOf(EffectType.BUFF_MAGIC_ATTACK_AREA);

        assertNotEquals(physical, magic,
                "the previous rule was 'one Rule instance for both stats'; the human's 5-10% band replaces it");
        assertEquals(0.20, physical.stepPerTier(), 1e-12, "「原本物理是20%」");
        assertEquals(0.26, magic.stepPerTier(), 1e-12, "he chose 26% from the 25-30% band");
        assertTrue(magic.stepPerTier() > physical.stepPerTier(),
                "the magic rung must be the larger one — that is the whole instruction");
        assertEquals(5, com.astrum.pcrd.api.v1.EffectTiers.maxTier(EffectType.BUFF_MAGIC_ATTACK_AREA),
                "still five rungs, so the ladder's shape is unchanged");

        // At every tier the magic buff is worth more, and never the same number — the requirement stated
        // as "keep the pair distinguishable at every tier".
        for (int tier = 1; tier <= 5; tier++) {
            double p = AttackAreaRules.fractionForTier(tier);
            double m = AttackAreaRules.magicFractionForTier(tier);
            assertEquals(0.20 * tier, p, 1e-12, "physical tier " + tier);
            assertEquals(0.26 * tier, m, 1e-12, "magical tier " + tier);
            assertTrue(m > p, "tier " + tier + " must favour the magic stat");
            // In magic_damage points: 0.26 × 2000 = 520 a rung, so tier N is 520N and the damage scale is
            // 1 + 0.26N. Stated in points because that is what the attribute holds.
            assertEquals(520.0 * tier, MagicAttackAreaEffect.pointsForAmplifier(tier - 1), 1e-9,
                    "magic tier " + tier + " must be " + (520 * tier) + " points");
            assertEquals(1.0 + 0.26 * tier,
                    MagicAttack.scaleOf(MagicAttackAreaEffect.pointsForAmplifier(tier - 1)), 1e-12,
                    "and that many points is a damage scale of 1 + 0.26N");
        }

        assertTrue(EffectType.BUFF_MAGIC_ATTACK_AREA.reachesParty(),
                "魔法攻击力アップ is 「自身」/「味方全体」 — a party-scoped buff, so a radius 0 is what makes "
                        + "凯露's 自身 literal");
        assertFalse(EffectType.BUFF_MAGIC_ATTACK_AREA.reachesVictim(),
                "it is not a state on the enemy");
    }

    /**
     * <b>The reference attack this file uses is the one the bwiki derivation uses.</b>
     *
     * <p>A guard against the cheapest way to break this feature invisibly: change
     * {@code MagicAttack.REFERENCE_ATTACK} to some rounder number and every shipped amount would then be on a
     * different scale from the stat that multiplies it. The two other places that quote 2000 are
     * {@code docs/balance-derivation.md} §14.1 and the resource tests.
     */
    @Test
    void theReferenceAttackIsTheProjectsOwnReferencePoint() {
        assertEquals(REFERENCE, MagicAttack.REFERENCE_ATTACK, 0.0,
                "§14.1 of docs/balance-derivation.md: level 208 and attack 2000 are the reference point every "
                        + "shipped amount was derived at");
        // The clamp and the reference agree in range: the TOP rung of the magic buff must be expressible.
        // This is a real constraint, not decoration — 26% a rung means tier 5 is 2600 points, and the old
        // 2048 ceiling would have silently clamped it to 2048 (worth 2.024x instead of 2.30x) with nothing
        // said. MagicAttack.MAX_VALUE was raised to 4096 for exactly this.
        assertTrue(MagicAttackAreaEffect.pointsForAmplifier(AttackAreaRules.amplifierForTier(5))
                        <= MagicAttack.MAX_VALUE,
                "the tier-5 magic buff (" + MagicAttackAreaEffect.pointsForAmplifier(4)
                        + " points) must fit inside the attribute's own ceiling ("
                        + MagicAttack.MAX_VALUE + "), or the top rung is silently worth less than stated");
        // And a level-100 character's flat EX on top of it still fits, which is the real worst case.
        assertTrue(3_360.0 + MagicAttackAreaEffect.pointsForAmplifier(4) <= MagicAttack.MAX_VALUE,
                "a + formula EX (3360 at the reference) stacked on a tier-5 buff (2600) must also fit");
        assertEquals(LEVEL, 208, "the bwiki reference level, named so the derivation above is checkable");
    }
}
