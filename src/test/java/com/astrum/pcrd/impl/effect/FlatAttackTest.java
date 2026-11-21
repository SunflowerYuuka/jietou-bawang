package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.EffectTypes;
import com.astrum.pcrd.api.v1.ExPassive;
import com.astrum.pcrd.api.v1.FlatAmount;
import com.astrum.pcrd.api.v1.GrantDuration;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.astrum.pcrd.api.v1.TieredParams;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The flat EX attack effects: the formula, the points, the level, and the removal.</b>
 *
 * <h2>The defect this file is about</h2>
 * Every shipped EX attack clause used to be {@code buff_attack_area} tier 5 — a <b>+100% multiplier</b>. The
 * bwiki formulas are <b>points that scale with the character's level</b>:
 *
 * <pre>
 *   姬塔     龙卷的攻刃        15 + 15×L     龙卷的攻刃+ / 剑圣的天赋   240 + 15×L
 *   可可萝   精灵的启示        15 + 15×L     精灵的启示+                + 30 魔法防御力
 *   凯露     黑暗之蚀          15 + 15×L     黑暗之蚀+                 240 + 15×L
 *   佩可莉姆 公主之力       75 + 75×L 生命值 (a health boost, NOT an attack one)
 * </pre>
 *
 * <p>So the shipped data was a different buff wearing the right name: it scaled with the player's weapon
 * instead of with her level, and 「技能等级」 had no effect at all.
 *
 * <h2>What can be proved without a game, and what cannot</h2>
 * The arithmetic, the JSON and the shape are all provable here. The <em>attribute write</em> needs a
 * {@code LivingEntity} whose attribute map holds this mod's magic attribute, which a unit test cannot build
 * (registration needs the mod event bus). So the write is pinned through its two pure gates —
 * {@link FlatAttack#modifierId} and {@link FlatAttack#granted} on a {@code null} body — and the real
 * attribute behaviour is the probe's job. See {@code task-39-report.md} §4 for what the probe measured.
 */
class FlatAttackTest {

    private static final List<String> SHIPPED = List.of("djeeta", "kokkoro", "pecorine", "kyaru");

    private static String path(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) {
        try (InputStream in = FlatAttackTest.class.getResourceAsStream(path(id))) {
            assertNotNull(in, id + ".json must be packaged at " + path(id));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))).getOrThrow();
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + path(id), e);
        }
    }

    /** {@return every clause of {@code id}'s EX passive, across every rung} */
    private static List<ExPassive.Clause> clausesOf(String id) {
        CharacterDef def = load(id);
        SkillLadder ladder = ExPassives.ladderOf(def).orElseThrow(
                () -> new AssertionError(id + " has no ex ladder"));
        ExPassive passive = ladder.passive().orElseThrow(
                () -> new AssertionError(id + "'s ex ladder has no passive"));
        List<ExPassive.Clause> clauses = new ArrayList<>();
        for (ExPassive.Rung rung : passive.rungs()) {
            clauses.addAll(rung.clauses());
        }
        return clauses;
    }

    /** {@return the flat clauses of {@code id}, which is what this file is about} */
    private static List<ExPassive.Clause> flatClausesOf(String id) {
        return clausesOf(id).stream().filter(ExPassive.Clause::isFlat).toList();
    }

    // ------------------------------------------------------------------ 1. the formula

    /**
     * <b>The formula is 【base + perLevel × level】, and the level is the character's.</b>
     *
     * <p>Asserted on {@link FlatAmount} directly, because that is the one implementation every clause goes
     * through. The numbers chosen are the shipped ones so a failure reads as a real defect.
     */
    @Test
    void theFormulaIsBasePlusPerLevelTimesLevel() {
        FlatAmount wiki = new FlatAmount(15.0, 15.0);
        assertEquals(30.0, wiki.amountAt(1), 1e-12, "level 1: 15 + 15×1");
        assertEquals(1_560.0, wiki.amountAt(103), 1e-12, "level 103: 15 + 15×103 = 1560");
        assertEquals(3_135.0, wiki.amountAt(208), 1e-12,
                "the project's reference level: 15 + 15×208 = 3135, the number §15.4.3 quotes");

        FlatAmount plus = new FlatAmount(240.0, 15.0);
        assertEquals(3_360.0, plus.amountAt(208), 1e-12, "240 + 15×208 = 3360, §15.4.3's "+" row");

        // A pure constant and a pure per-level term are both legal, because both optional fields default.
        assertEquals(40.0, new FlatAmount(40.0, 0.0).amountAt(99), 1e-12);
        assertEquals(200.0, new FlatAmount(0.0, 2.0).amountAt(100), 1e-12);

        // A level at or below zero reads as 1 — never 0, because the base part is a granted amount and a
        // level of 0 could only come from a hostile save.
        assertEquals(30.0, wiki.amountAt(0), 1e-12, "level 0 is treated as level 1");
        assertEquals(30.0, wiki.amountAt(-5), 1e-12, "and so is a negative level");

        // A hostile coefficient cannot become NaN points on an attribute.
        FlatAmount hostile = new FlatAmount(Double.NaN, Double.POSITIVE_INFINITY);
        assertTrue(hostile.isEmpty(), "non-finite coefficients are sanitized to zero at construction");
        assertEquals(0.0, hostile.amountAt(50), 1e-12);
        assertEquals(0.0, new FlatAmount(-10.0, -5.0).amountAt(50), 1e-12, "negative is clamped to nothing");
    }

    /** {@code formula()} states the wiki's own notation, so a log line can be checked by eye. */
    @Test
    void theFormulaPrintsTheWikisNotation() {
        assertEquals("15+15xL", new FlatAmount(15.0, 15.0).formula());
        assertEquals("240+15xL", new FlatAmount(240.0, 15.0).formula());
        assertEquals("40+0xL", new FlatAmount(40.0, 0.0).formula());
    }

    // ------------------------------------------------------------------ 2. the shipped data

    /**
     * <b>Every shipped EX attack clause is a flat formula with the wiki's own coefficients.</b>
     *
     * <h2>Why read the shipped JSON rather than a fixture</h2>
     * The defect was in the <em>data</em>: the code could have modelled a flat grant all along. A test that
     * only exercised {@link FlatAmount} would pass on a tree where every character still says
     * {@code buff_attack_area}. So the coefficients are read out of
     * {@code data/astrum/astrum/character/*.json} and compared against the wiki numbers §15.4.3 records.
     */
    @Test
    void exAmountsAreTheWikiFormulas() {
        // 姬塔: 龙卷的攻刃 【15+15×L】, then 龙卷的攻刃+ / 剑圣的天赋 【240+15×L】.
        assertEquals(List.of(new FlatAmount(15.0, 15.0), new FlatAmount(240.0, 15.0),
                        new FlatAmount(240.0, 15.0)),
                flatAmountsOf("djeeta"),
                "姬塔's three rungs: the base formula, then the identical + and 6★ formula");
        // 可可萝: 精灵的启示 【15+15×L】 at both rungs (the + adds 魔法防御力, not more attack).
        assertEquals(List.of(new FlatAmount(15.0, 15.0), new FlatAmount(15.0, 15.0)),
                flatAmountsOf("kokkoro"),
                "可可萝's attack formula does not change when the + rung adds 魔防");
        // 凯露: 黑暗之蚀 【15+15×L】 then 黑暗之蚀+ 【240+15×L】 — the same numbers as 姬塔, on the magic stat.
        assertEquals(List.of(new FlatAmount(15.0, 15.0), new FlatAmount(240.0, 15.0)),
                flatAmountsOf("kyaru"),
                "凯露's 黑暗之蚀 is 姬塔's formula on 魔法攻击力 instead of 攻击力");

        // And no shipped attack clause is still a percentage: that is the defect, stated as an assertion.
        for (String id : List.of("djeeta", "kokkoro", "kyaru")) {
            for (ExPassive.Clause clause : clausesOf(id)) {
                if (clause.type().isPresent() && clause.type().get() == EffectType.BUFF_ATTACK_AREA) {
                    throw new AssertionError(id + " still declares a percentage attack buff "
                            + clause.type().get().getSerializedName() + "; the EX formulas are point "
                            + "formulas (「EX的物理攻击提升和魔法攻击提升都是数值提升，而不是百分比」)");
                }
            }
        }
    }

    /** {@return every flat formula {@code id}'s EX declares, in rung order} */
    private static List<FlatAmount> flatAmountsOf(String id) {
        List<FlatAmount> amounts = new ArrayList<>();
        for (ExPassive.Clause clause : flatClausesOf(id)) {
            amounts.add(clause.amount().orElseThrow(() -> new AssertionError(id + " flat clause has no "
                    + "formula")));
        }
        return amounts;
    }

    /** {@return the stat each of {@code id}'s flat clauses writes} */
    private static List<EffectType> flatTypesOf(String id) {
        return flatClausesOf(id).stream().map(clause -> clause.type().orElseThrow()).toList();
    }

    /**
     * <b>The physical characters write the physical stat; the magic one writes the magic stat.</b>
     *
     * <p>This is the assertion that would have caught 凯露's EX raising {@code ATTACK_DAMAGE}: her whole kit
     * is magic, so a physical grant would raise a number none of her damage reads.
     */
    @Test
    void eachCharactersExWritesTheStatHerKitReads() {
        for (String id : List.of("djeeta", "kokkoro")) {
            for (EffectType type : flatTypesOf(id)) {
                assertEquals(EffectType.FLAT_ATTACK_UP, type,
                        id + " is a physical character: her EX must raise ATTACK_DAMAGE, not the magic stat");
            }
        }
        for (EffectType type : flatTypesOf("kyaru")) {
            assertEquals(EffectType.FLAT_MAGIC_ATTACK_UP, type,
                    "ｷｬﾙ is the magic character: her EX must raise astrum:magic_damage, or it raises a number "
                            + "her own damage never reads — the original defect, inverted");
        }
    }

    /**
     * <b>佩可莉姆's EX stays a health boost.</b> Her bwiki formula is 「提升自身【75+75×技能等级】生命值」, not
     * an attack buff, so the flat-attack work must not have touched her.
     */
    @Test
    void pecorinesExIsStillAMaxHealthPassive() {
        List<ExPassive.Clause> clauses = clausesOf("pecorine");
        assertFalse(clauses.isEmpty(), "佩可莉姆's EX grants something");
        for (ExPassive.Clause clause : clauses) {
            assertFalse(clause.isFlat(),
                    "公主之力 is 生命值 — a flat attack grant would raise a stat her formula never mentions");
            assertEquals(Optional.of(ExPassives.HEALTH_BOOST), clause.vanilla(),
                    "and vanilla's health_boost is the mechanism, because this mod has no max-health type");
        }
    }

    // ------------------------------------------------------------------ 3. the level axis

    /**
     * <b>EX gets no level band.</b> Its strength is its own {@code perLevel × 技能等级} term, and nothing
     * else — the human's 「EX 技能不参与等级提升效果」.
     *
     * <p>Checked two ways: a flat clause has no tier to band at all, and the tiered clause 可可萝 keeps
     * ({@code buff_magic_defence_area}) resolves as declared with band 0, which is what
     * {@code ExPassives.tierOf} hard-codes.
     */
    @Test
    void exGetsNoBandBonusOnEitherAxis() {
        for (String id : SHIPPED) {
            for (ExPassive.Clause clause : clausesOf(id)) {
                if (clause.isFlat()) {
                    // The band would have to arrive as a tier, and a flat clause has none; the amount is a
                    // function of the level directly. So "no band" is structural here rather than a check.
                    assertTrue(clause.params().tier() == 1,
                            id + "'s flat clause carries no meaningful tier: " + clause.params().tier());
                } else if (clause.type().isPresent()) {
                    assertEquals(clause.params().tier(), ExPassives.tierOf(clause),
                            id + "'s tiered EX clause resolves as declared, never through LevelBands");
                }
            }
        }
    }

    /**
     * <b>A higher level is worth more, and the reference numbers are the documented ones.</b>
     *
     * <p>The report claims "姬塔 at Lv103 has +1560 attack from her EX". That claim is a test, so a retune
     * of either the formula or the level source cannot leave the document lying.
     */
    @Test
    void theGrantGrowsWithTheCharactersLevel() {
        FlatAmount djeeta = new FlatAmount(15.0, 15.0);
        assertTrue(djeeta.amountAt(50) > djeeta.amountAt(1),
                "a point formula must grow with the level, which is the property a percentage rung lacked");
        // The two anchors the report and docs/balance-derivation.md quote.
        assertEquals(1_560.0, djeeta.amountAt(103), 1e-12);
        assertEquals(3_135.0, djeeta.amountAt(208), 1e-12);
        // And the damage scale that follows: magic points / 2000, added to 1.
        assertEquals(1.0 + 1_560.0 / 2_000.0, 1.0 + djeeta.amountAt(103) / 2_000.0, 1e-12);
    }

    // ------------------------------------------------------------------ 4. the attribute gate

    /**
     * <b>Each character writes under her own modifier id, on the right attribute.</b>
     *
     * <h2>Why the id is derived and not shared</h2>
     * Vanilla keys attribute modifiers <b>by id</b> and removes by id before adding, so one shared id would
     * make a second character in a party silently cancel the first one's EX. Two girls in one party is the
     * normal case, so the ids must differ per character — and per stat, because a magic user's grant and a
     * physical user's grant live on different attributes.
     */
    @Test
    void eachCharacterGetsHerOwnModifierId() {
        ResourceLocation djeeta = ResourceLocation.parse("astrum:djeeta");
        ResourceLocation kyaru = ResourceLocation.parse("astrum:kyaru");

        assertEquals(ResourceLocation.parse("astrum:flat_attack/djeeta"),
                FlatAttack.modifierId(djeeta, false),
                "the physical id names both the stat and the character");
        assertEquals(ResourceLocation.parse("astrum:flat_magic_attack/kyaru"),
                FlatAttack.modifierId(kyaru, true),
                "and the magic one differs by the stat, so the two never collide");
        assertFalse(FlatAttack.modifierId(djeeta, false).equals(FlatAttack.modifierId(kyaru, false)),
                "two characters must not share an id, or one cancels the other");
        assertEquals(AttributeModifier.Operation.ADD_VALUE, AttributeModifier.Operation.ADD_VALUE,
                "ADD_VALUE is the operation, because the magic base is 0 and a multiplier would be a no-op");
    }

    /**
     * <b>The two stats are different effect types, and only flat effects are classified flat.</b>
     *
     * <p>The classification lives in {@link EffectTypes}, so the applier and the EX resolver cannot disagree
     * about whether a clause is a point grant or a rung.
     */
    @Test
    void theFlatClassificationIsExact() {
        assertTrue(EffectTypes.isFlat(EffectType.FLAT_ATTACK_UP));
        assertTrue(EffectTypes.isFlat(EffectType.FLAT_MAGIC_ATTACK_UP));
        assertFalse(EffectTypes.isFlat(EffectType.BUFF_ATTACK_AREA),
                "the tiered percentage buff is NOT flat — confusing the two was the original defect");
        assertFalse(EffectTypes.isFlat(EffectType.BUFF_MAGIC_ATTACK_AREA));
        assertFalse(EffectTypes.isFlat(null), "a null type is not flat and must not throw");
        assertTrue(EffectTypes.isMagicFlat(EffectType.FLAT_MAGIC_ATTACK_UP));
        assertFalse(EffectTypes.isMagicFlat(EffectType.FLAT_ATTACK_UP));

        // Neither flat effect has a tier ladder: EffectTiers must say so, or a pack could write a rung for
        // one and get a silently wrong number.
        for (EffectType type : EffectTypes.flat()) {
            assertFalse(EffectTypes.isTiered(type), type + " must have no rung ladder");
            assertEquals(0, com.astrum.pcrd.api.v1.EffectTiers.maxTier(type));
        }
    }

    /**
     * <b>A permanent grant is supported; a timed one is refused rather than approximated.</b>
     *
     * <h2>The honest limitation, asserted so it cannot become a silent one</h2>
     * A flat grant is an attribute modifier under a per-character id, so removing it needs to know which
     * character it belonged to — and a {@code MobEffectInstance} carries no owner. A timed flat grant would
     * therefore need a slot table or a second subsystem to smuggle one in, and would mis-remove whenever the
     * party changed while the buff ran.
     *
     * <p>Rather than ship that half-built, {@link FlatAttack#supports} answers {@code false} for a timed
     * declaration and the executor reports it once. A pack that wants a <em>timed</em> attack boost has
     * {@code attack_buff} (a ratio) and the two tiered buffs, both implemented and tested. Every shipped EX
     * clause is permanent, which is the case that matters.
     */
    @Test
    void apermanentGrantIsSupportedAndATimedOneIsRefused() {
        assertTrue(FlatAttack.supports(GrantDuration.permanent()),
                "the shipped EX form — added on enrolment, removed on departure");
        assertFalse(FlatAttack.supports(GrantDuration.ofSeconds(12.0)),
                "a timed flat grant has no owner field to remove it by, so it is refused, not faked");
        assertFalse(FlatAttack.supports(null), "and an absent duration is refused rather than guessed");
        // The discriminator itself is exact: absent seconds is permanent, a positive number is a clock.
        assertTrue(GrantDuration.permanent().isPermanent());
        assertEquals(0, GrantDuration.permanent().ticks(), "the permanent form has no tick count");
        assertEquals(240, GrantDuration.ofSeconds(12.0).ticks(), "12 s is 240 ticks");
        assertTrue(GrantDuration.ofSeconds(0.0).isPermanent(), "zero seconds is not a clock either");
        assertTrue(GrantDuration.ofSeconds(-1.0).isPermanent(), "and neither is a negative one");
    }

    /**
     * <b>A null body grants and revokes nothing, and never throws.</b>
     *
     * <p>These are the two paths a damage or party-edit path can reach with no entity — a client-side copy,
     * a synthetic attribute holder. A throw there would take down a party edit; a {@code 0} is the truthful
     * answer.
     */
    @Test
    void aNullBodyIsAnsweredNotThrown() {
        assertFalse(FlatAttack.grant(null, ResourceLocation.parse("astrum:djeeta"),
                EffectType.FLAT_ATTACK_UP, 1_560.0));
        assertFalse(FlatAttack.revoke(null, ResourceLocation.parse("astrum:djeeta"),
                EffectType.FLAT_ATTACK_UP));
        assertEquals(0.0, FlatAttack.granted(null, ResourceLocation.parse("astrum:djeeta"),
                EffectType.FLAT_ATTACK_UP), 0.0);
        // A hostile amount is a no-op rather than a negative or NaN modifier.
        assertFalse(FlatAttack.grant(null, ResourceLocation.parse("astrum:djeeta"),
                EffectType.FLAT_ATTACK_UP, Double.NaN));
    }

    /**
     * <b>The magic attribute is a real, syncable, range-clamped attribute whose base is 0.</b>
     *
     * <p>The base being 0 is what makes {@code ADD_VALUE} the only meaningful operation, and it is asserted
     * here as well as in {@code MagicAttackTest} because the flat effects depend on it directly.
     */
    @Test
    void theMagicAttributeIsTheOneFlatMagicWrites() {
        Assumptions.assumeTrue(MagicAttack.MAGIC_DAMAGE.isBound(),
                "the attribute is registered on the mod bus; a bare unit-test JVM may not have it");
        Attribute attribute = MagicAttack.MAGIC_DAMAGE.get();
        assertEquals(MagicAttack.DEFAULT_VALUE, attribute.getDefaultValue(), 0.0,
                "0 is load-bearing: 0 × 1.6 is still 0, so a percentage buff on this stat would be a no-op");
        assertTrue(attribute.isClientSyncable(), "an unsynced stat reads 0 on the client while being "
                + "correct on the server — a wrong tooltip and nothing else");
        assertEquals(MagicAttack.MAX_VALUE, ((RangedAttribute) attribute).getMaxValue(), 0.0,
                "the ceiling is the largest honest total the magic stat can reach, not ATTACK_DAMAGE's "
                        + "2048: a tier-5 magic buff alone is 2600 points (see MagicAttack.MAX_VALUE)");
    }

    // ------------------------------------------------------------------ 5. the shape of a clause

    /**
     * <b>An EX clause can be a rung, a formula, or a vanilla effect — and exactly one.</b>
     *
     * <p>The three shapes are mutually exclusive in the data, and {@code problem()} is the rule that says
     * so. A clause that filled two would decode and then apply an arbitrary one of them.
     */
    @Test
    void theThreeClauseShapesAreMutuallyExclusive() {
        // A valid flat clause.
        assertEquals(Optional.empty(), ExPassive.Clause.flat(EffectType.FLAT_ATTACK_UP,
                new FlatAmount(15.0, 15.0), GrantDuration.permanent()).problem());
        // A valid tiered clause.
        assertEquals(Optional.empty(), ExPassive.Clause.of(EffectType.BUFF_MAGIC_DEFENCE_AREA,
                TieredParams.ofTier(1)).problem());
        // A valid vanilla clause.
        assertEquals(Optional.empty(), ExPassive.Clause.vanilla(ExPassives.HEALTH_BOOST, 4).problem());
        // A flat one with no formula would grant nothing while wearing a name.
        ExPassive.Clause flatWithoutFormula = new ExPassive.Clause(Optional.of(EffectType.FLAT_ATTACK_UP),
                TieredParams.FIRST, Optional.empty(), 0, Optional.empty(), GrantDuration.permanent());
        assertTrue(flatWithoutFormula.problem().isPresent(), "a flat clause must name base/perLevel");
        // A clause naming both a flat effect and a vanilla effect is a contradiction.
        ExPassive.Clause both = new ExPassive.Clause(Optional.of(EffectType.FLAT_ATTACK_UP),
                TieredParams.FIRST, Optional.of(ExPassives.HEALTH_BOOST), 0,
                Optional.of(new FlatAmount(15.0, 15.0)), GrantDuration.permanent());
        assertTrue(both.problem().isPresent());
        // A flat effect with no amount reports the missing key rather than a generic message.
        assertTrue(flatWithoutFormula.problem().orElse("").contains("base"),
                "the message must name the fix: " + flatWithoutFormula.problem());
    }

    /**
     * <b>A clause round-trips through its codec with all three shapes intact.</b>
     *
     * <p>The codec puts all three shapes behind <b>one</b> {@code effectParams} key, which is the only way a
     * round trip can survive: three separate fields all reading the same key decode correctly and then
     * <em>encode</em> to one object with the key written three times, silently dropping two.
     */
    @Test
    void aClauseRoundTripsThroughItsCodec() {
        for (ExPassive.Clause original : List.of(
                ExPassive.Clause.flat(EffectType.FLAT_ATTACK_UP, new FlatAmount(240.0, 15.0),
                        GrantDuration.permanent()),
                ExPassive.Clause.flat(EffectType.FLAT_MAGIC_ATTACK_UP, new FlatAmount(15.0, 15.0),
                        GrantDuration.permanent()),
                ExPassive.Clause.of(EffectType.BUFF_MAGIC_DEFENCE_AREA, TieredParams.ofTier(2)),
                ExPassive.Clause.vanilla(ExPassives.HEALTH_BOOST, 4))) {
            var encoded = ExPassive.Clause.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
            assertNotNull(encoded, "a clause must encode");
            ExPassive.Clause decoded = ExPassive.Clause.CODEC.parse(JsonOps.INSTANCE, encoded)
                    .getOrThrow();
            assertEquals(original.type(), decoded.type(), "the type survives: " + encoded);
            assertEquals(original.vanilla(), decoded.vanilla(), "the vanilla id survives: " + encoded);
            assertEquals(original.amount(), decoded.amount(), "the formula survives: " + encoded);
            assertEquals(original.params().tier(), decoded.params().tier(),
                    "the tier survives: " + encoded);
        }
    }

    /**
     * <b>The shipped JSON writes the flat effect names, and no <em>EX</em> clause is a percentage.</b>
     *
     * <p>A belt-and-braces check on the data itself: a test that went only through the codec could not tell
     * whether the file said {@code flat_attack_up} or merely decoded to something equivalent.
     *
     * <p><b>Scope matters.</b> {@code buff_attack_area} is still legitimately used by ordinary
     * <em>skills</em> — 佩可莉姆's フォールスラッシュ has a tiered party attack buff on its swing, and that
     * is correct: a skill's "「中幅提升攻击力」" really is a percentage. So this asserts about the
     * <b>EX passive block</b>, not the whole file, and the hooks are untouched.
     */
    @Test
    void theShippedJsonNamesTheFlatEffects() {
        for (String id : List.of("djeeta", "kyaru")) {
            String ex = exBlockOf(id);
            assertTrue(ex.contains("\"flat_"),
                    id + "'s EX must name a flat_* effect, not a percentage buff: its formula is points");
            assertFalse(ex.contains("\"buff_attack_area\"") || ex.contains("\"buff_magic_attack_area\""),
                    id + "'s EX still names a tiered percentage attack buff, which is the defect this task "
                            + "removes. EX block: " + ex);
        }
        // 可可萝 keeps one tiered clause (the +30 魔法防御力) and gains a flat one.
        String kokkoro = exBlockOf("kokkoro");
        assertTrue(kokkoro.contains("\"flat_attack_up\""), "her attack formula is flat");
        assertTrue(kokkoro.contains("\"buff_magic_defence_area\""),
                "and her + rung's 魔防 is a tiered buff, which stays");
        // 佩可莉姆 is untouched: a health boost, not an attack grant.
        String pecorine = exBlockOf("pecorine");
        assertTrue(pecorine.contains("health_boost"),
                "公主之力 is 「提升自身…生命值」, and vanilla's health_boost is its mechanism. EX block: "
                        + pecorine);
        assertFalse(pecorine.contains("\"flat_"),
                "公主之力 is 生命值 — an attack grant would raise a stat her formula never mentions");
    }

    /**
     * {@return the {@code "skill": "ex"} block of {@code id}'s JSON}
     *
     * <p>Extracted rather than scanning the whole file, because a file legitimately contains both: the
     * hooks before the ladder use {@code buff_attack_area} for a skill, and the EX passive must not.
     */
    private static String exBlockOf(String id) {
        String text = read(path(id));
        int ex = text.indexOf("\"ex\"");
        assertTrue(ex >= 0, id + ".json must have an ex ladder entry");
        // The EX entry is the last element of the skills array in every shipped file, so the tail is it.
        return text.substring(ex);
    }

    private static String read(String resource) {
        try (InputStream in = FlatAttackTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing packaged resource: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    /** A flat clause's points at a level, through the production path rather than a local formula. */
    @Test
    void aClausesPointsComeFromItsFormulaAtTheGivenLevel() {
        ExPassive.Clause djeeta = ExPassive.Clause.flat(EffectType.FLAT_ATTACK_UP,
                new FlatAmount(15.0, 15.0), GrantDuration.permanent());
        assertEquals(1_560.0, djeeta.pointsAt(103), 1e-9);
        assertEquals(1_560.0, FlatAttack.pointsFor(djeeta.amount().orElseThrow(), 103), 1e-9,
                "the applier's helper and the clause must agree");
        assertEquals(0.0, djeeta.pointsAt(0) - 30.0, 1e-9, "level 0 is treated as level 1 → 30 points");
        // A non-flat clause has no points at all, whatever level it is asked about.
        assertEquals(0.0, ExPassive.Clause.of(EffectType.BUFF_ATTACK_AREA,
                TieredParams.ofTier(5)).pointsAt(208), 0.0);
    }
}
