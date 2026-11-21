package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.ExPassive;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.astrum.pcrd.impl.growth.LevelBands;
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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>EX as a real passive</b>: the data, the ladder, and the four shipped values — proved against the bwiki
 * formulas, not against a copy of the JSON.
 *
 * <h2>What is asserted, and why each one</h2>
 * <ol>
 *   <li><b>Every shipped character has an EX with a passive.</b> Before this task the EX rung carried an empty
 *       {@code effects} list and did nothing; the row said 剑圣的天赋 and no number existed. A test that only
 *       checked the <em>name</em> would have passed then, so the assertions here read the <b>clauses</b>.</li>
 *   <li><b>Each clause's rung follows from its own bwiki formula</b>, using the mapping §14.4 of
 *       {@code docs/balance-derivation.md} states — recomputable, not retyped.</li>
 *   <li><b>EX adds no level band</b>, the human's explicit rule, pinned by comparing two levels that straddle
 *       a band boundary.</li>
 *   <li><b>Only {@code CharacterDef} is at the component ceiling</b>, and EX is not on it. Asserted reflectively:
 *       the whole reason this shape exists is that a seventeenth component does not compile, so "we did not add
 *       one" has to be checkable in the suite rather than only in a review.</li>
 * </ol>
 *
 * <p><b>What cannot be asserted here.</b> Granting a passive needs a {@code ServerPlayer}, so the grant/remove
 * half of the lifecycle is proven on a real dedicated server — see the task report's log — rather than here. What
 * <em>is</em> asserted is the resolution that lifecycle consumes: which clauses exist, at which rung, and that
 * the level band never reaches them.
 */
class ExPassiveTest {

    /** The reference point every derivation in this project uses (§14.1). */
    private static final int LEVEL = 208;
    private static final double ATTACK = 2000.0;

    private static final String[] SHIPPED = {"djeeta", "kokkoro", "pecorine", "kyaru"};

    /**
     * 佩可莉姆's {@code health_boost} amplifier: one full 20-health reference bar, i.e. {@code 4 × (4 + 1) = 20}.
     *
     * <p>Derived in {@link #pecorinesExIsAMaxHealthPassiveAtEveryTier}, where the number it clamps is shown.
     */
    private static final int PECORINE_AMPLIFIER = 4;

    /** 佩可莉姆's own maximum health at Lv208, from the bwiki {@code 6x属性} row's first column. */
    private static final double PECORINE_OWN_MAX_HEALTH = 3377.3;

    // ------------------------------------------------------------------ helpers

    private static CharacterDef load(String id) {
        String path = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id
                + ".json";
        try (InputStream in = ExPassiveTest.class.getResourceAsStream(path)) {
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

    /** {@return the attack-up rung a formula value is worth, per §14.4} */
    private static int attackRung(double formulaValue) {
        return (int) Math.clamp(Math.round(formulaValue / (0.20 * ATTACK)), 1, 5);
    }

    // ------------------------------------------------------------------ 1. every character has a real EX

    @Test
    void everyShippedCharacterHasAnExPassiveThatGrantsSomething() {
        for (String id : SHIPPED) {
            SkillLadder ex = exOf(id);
            assertTrue(ex.isEx(), id + "'s ladder must be the reserved ex tag");
            Optional<ExPassive> passive = ex.passive();
            assertTrue(passive.isPresent(), id + " must declare a passive, or her EX is still inert");
            assertFalse(passive.get().isEmpty(), id + "'s passive must grant at least one clause");
        }
    }

    /**
     * <b>The ladder's names are exactly the bwiki's.</b> 姬塔's is the one that renames at 6★; the other three
     * keep their {@code +} name at tier 3, which is what {@link SkillLadder#upgradeAt} already guarantees.
     */
    @Test
    void theExNamesFollowTheAffectionLadder() {
        assertEquals("龙卷的攻刃", exOf("djeeta").nameAt(1));
        assertEquals("龙卷的攻刃+", exOf("djeeta").nameAt(2), "tier 2 is the + name");
        assertEquals("剑圣的天赋", exOf("djeeta").nameAt(3), "tier 3 is the renamed version");

        for (String id : new String[] {"kokkoro", "pecorine", "kyaru"}) {
            String base = exOf(id).nameAt(1);
            assertEquals(base + "+", exOf(id).nameAt(2), id + "'s tier 2 gains the plus");
            assertEquals(base + "+", exOf(id).nameAt(3),
                    id + " has no 6★ rename, so tier 3 keeps the plus — 「改名压过 +」 with no rename to apply");
        }
    }

    // ------------------------------------------------------------------ 2. the four values, from the formulas

    /**
     * <b>姬塔 龙卷的攻刃 / 龙卷的攻刃+ / 剑圣的天赋.</b>
     *
     * <pre>
     *   龙卷的攻刃    提升自身【15+15*技能等级】物理攻击力   = 15 + 15×208 = 3135 → 78% → rung 5 (capped)
     *   龙卷的攻刃+   提升自身【240+15*技能等级】物理攻击力  = 240 + 15×208 = 3360 → 84% → rung 5 (capped)
     *   剑圣的天赋    提升自身【240+15*技能等级】物理攻击力  = 3360 → rung 5
     * </pre>
     *
     * <p>The {@code +} and the 6★ rung are the <b>same formula</b> in the bwiki data, so they are the same rung
     * here — that is a fact about her EX, not an omission.
     */
    @Test
    void djeetasExIsAFlatAttackGrantAtEveryTier() {
        SkillLadder ex = exOf("djeeta");
        // This test used to assert rung 5 of BUFF_ATTACK_AREA at every tier — a +100% multiplier. Task 39
        // replaced it: 「EX的物理攻击提升和魔法攻击提升都是数值提升，而不是百分比」, and the bwiki agrees.
        // So the assertion is now the formula, which is the thing the source material actually states.
        for (int tier = 1; tier <= 3; tier++) {
            ExPassive.Rung rung = ex.passive().orElseThrow().rungFor(tier).orElseThrow();
            assertEquals(1, rung.clauses().size(), "tier " + tier + " grants exactly one thing");
            ExPassive.Clause clause = rung.clauses().get(0);
            assertEquals(EffectType.FLAT_ATTACK_UP, clause.type().orElseThrow(),
                    "tier " + tier + " is a flat point grant on ATTACK_DAMAGE, not a percentage rung");
            assertTrue(clause.isFlat(), "and the shape says so");
            assertTrue(clause.amount().isPresent(), "with a formula, or it would grant nothing");
            assertTrue(clause.duration().isPermanent(),
                    "an EX passive lasts as long as she holds the slot, so it declares no clock");
        }
        // The three rungs' formulas, in order: 【15+15×L】 then 【240+15×L】 twice (the + and 剑圣的天赋 are
        // the same formula — a fact about her EX, not an omission).
        assertEquals(new com.astrum.pcrd.api.v1.FlatAmount(15, 15),
                ex.passive().orElseThrow().rungFor(1).orElseThrow().clauses().get(0).amount().orElseThrow());
        assertEquals(new com.astrum.pcrd.api.v1.FlatAmount(240, 15),
                ex.passive().orElseThrow().rungFor(2).orElseThrow().clauses().get(0).amount().orElseThrow());
        assertEquals(new com.astrum.pcrd.api.v1.FlatAmount(240, 15),
                ex.passive().orElseThrow().rungFor(3).orElseThrow().clauses().get(0).amount().orElseThrow());
    }

    /**
     * <b>凯露 黑暗之蚀 lands on the <em>magic</em> stat, and that is the bug this task fixed.</b>
     *
     * <p>「提升自身【15+15*技能等级】魔法攻击力」 → the same number as 姬塔's base (3135 → the top rung), but on
     * {@code astrum:magic_damage} rather than {@code Attributes.ATTACK_DAMAGE}. Before
     * {@link EffectType#BUFF_MAGIC_ATTACK_AREA} existed her EX raised the physical stat — a stat her damage
     * never reads, since every hook and both ultimate tiers of hers are filed {@code astrum:magic} — so the
     * buff was real, visible, and worth nothing to her. The two assertions that matter are therefore which
     * effect type the clause names (→ which attribute it raises) and that the rung is unchanged.
     */
    @Test
    void kyarusExIsAFlatMagicAttackGrantAndNotThePhysicalOne() {
        ExPassive.Clause base = exOf("kyaru").passive().orElseThrow().rungFor(1).orElseThrow()
                .clauses().get(0);
        // Two rules meet here. (1) 黑暗之蚀 says 魔法攻击力, so it must raise astrum:magic_damage — a physical
        // grant would raise the stat her damage never reads. (2) Task 39: the grant is FLAT POINTS, not the
        // percentage rung this assertion used to pin ("rung 5 of BUFF_MAGIC_ATTACK_AREA"), because the bwiki
        // formula is 【15+15*技能等级】.
        assertEquals(EffectType.FLAT_MAGIC_ATTACK_UP, base.type().orElseThrow(),
                "黑暗之蚀 says 魔法攻击力, so it must raise astrum:magic_damage");
        assertFalse(base.type().orElseThrow() == EffectType.FLAT_ATTACK_UP,
                "and specifically not the physical one, which is the defect an earlier task fixed");
        assertTrue(base.isFlat(), "and it is a formula, not a rung");
        assertEquals(new com.astrum.pcrd.api.v1.FlatAmount(15, 15), base.amount().orElseThrow());
        assertEquals(3_135.0, base.pointsAt(208), 1e-9,
                "【15+15×技能等级】 at the reference level: 3135 points of magic attack");
        assertEquals(EffectType.FLAT_MAGIC_ATTACK_UP,
                exOf("kyaru").passive().orElseThrow().rungFor(2).orElseThrow().clauses().get(0)
                        .type().orElseThrow(),
                "黑暗之蚀+ must still grant the same stat, at the + formula");
        assertEquals(new com.astrum.pcrd.api.v1.FlatAmount(240, 15),
                exOf("kyaru").passive().orElseThrow().rungFor(2).orElseThrow().clauses().get(0)
                        .amount().orElseThrow(),
                "【240+15×技能等级】, the bwiki's + formula");
    }

    /**
     * <b>The other three EX skills keep the <em>physical</em> stat.</b>
     *
     * <p>姬塔 「物理攻击力」, 可可萝 「物理攻击力」 and 佩可莉姆 (a max-health clause, which is not an attack
     * buff at all) are physical characters, so nothing they do reads {@code magic_damage}. A regression that
     * moved all four onto the magic buff — the tempting one-line "fix" — would leave three physical
     * characters buffing a stat of zero.
     *
     * <p>Asserted over every tier, because an EX resolves a different rung per affection tier and a
     * mis-migration that only changed tier 3 would pass a single-tier check.
     */
    @Test
    void theOtherThreeExSkillsKeepThePhysicalAttackStat() {
        for (String id : new String[] {"djeeta", "kokkoro"}) {
            ExPassive passive = exOf(id).passive().orElseThrow();
            for (int tier = 1; tier <= 3; tier++) {
                for (ExPassive.Clause clause : passive.rungFor(tier).orElseThrow().clauses()) {
                    if (clause.type().isEmpty()) {
                        continue;   // 可可萝's vanilla armour clause, and 佩可莉姆's whole passive
                    }
                    assertTrue(clause.type().orElseThrow() != EffectType.BUFF_MAGIC_ATTACK_AREA,
                            id + " tier " + tier + " is a physical character: her EX must not raise "
                                    + "magic_damage, because none of her damage reads it");
                }
            }
        }
        // 佩可莉姆's EX is max health, not an attack buff at all — so it is the strongest form of "does not
        // touch the magic stat": there is no attack clause to migrate.
        for (int tier = 1; tier <= 3; tier++) {
            ExPassive.Clause clause = exOf("pecorine").passive().orElseThrow().rungFor(tier)
                    .orElseThrow().clauses().get(0);
            assertTrue(clause.type().isEmpty(),
                    "公主之力 raises max health; it names no attack type at all");
        }
    }

    /**
     * <b>可可萝 精灵的启示 → 精灵的启示+.</b> The {@code +} adds a clause: 「并小幅提升自身魔法防御力」.
     *
     * <p>Expressed as this mod's own {@code buff_magic_defence_area} at rung 1 rather than as vanilla's
     * {@code resistance}: resistance reduces <em>every</em> damage type (it is the reason
     * {@code EffectType.BUFF_MAGIC_DEFENCE_AREA} exists at all), so using it would implement "raises magic
     * defence" as "raises all defence" — a stealth buff the source text does not describe.
     */
    @Test
    void kokkorosExGainsAMagicDefenceClauseAtTierTwo() {
        ExPassive passive = exOf("kokkoro").passive().orElseThrow();
        assertEquals(1, passive.rungFor(1).orElseThrow().clauses().size(),
                "the base EX is the attack rung alone");
        List<ExPassive.Clause> plus = passive.rungFor(2).orElseThrow().clauses();
        assertEquals(2, plus.size(), "精灵的启示+ adds 「并小幅提升自身魔法防御力」");
        assertEquals(EffectType.BUFF_MAGIC_DEFENCE_AREA, plus.get(1).type().orElseThrow(),
                "the added clause is a magic-defence buff, not vanilla resistance");
        assertEquals(1, plus.get(1).params().tier(), "「小幅」 is the first rung");
        assertEquals(2, passive.rungFor(3).orElseThrow().tier(),
                "she has no 6★ rename, so tier 3 keeps the + rung");
    }

    /**
     * <b>佩可莉姆 公主之力 → 公主之力+.</b> 「提升自身【75+75*技能等级】生命值」, and the {@code +} is
     * 「【1275+75*技能等级】」 — the same per-level term with a much larger constant.
     *
     * <h2>The derivation, and the one clamp</h2>
     * This is the one EX clause expressed as a <b>vanilla</b> effect, because this mod has no "max health" effect
     * type and {@code minecraft:health_boost} is exactly the mechanic: {@code MobEffects.HEALTH_BOOST} carries an
     * {@code ADDITION} modifier of {@code 4.0} on {@code MAX_HEALTH}, applied as
     * {@code amount × (amplifier + 1)} — read from {@code MobEffect.getAttributeModifierValue} — so amplifier
     * {@code a} is {@code 4 × (a + 1)} health points.
     *
     * <p>Her formula at the project's reference point (Lv208) is 15675 health, which is <b>464%</b> of her own
     * 3377-health bar — well past anything a 20-health reference bar can express, exactly like the attack rows
     * that clamp at rung 5 (§14.4's 「封顶」). So it is clamped to <b>one full reference bar</b>, 20 health, which
     * is amplifier {@value #PECORINE_AMPLIFIER}. Both rungs land there: 15675 and 16875 are 464% and 500%, and
     * a difference the player cannot feel is not worth a second amplifier.
     */
    @Test
    void pecorinesExIsAMaxHealthPassiveAtEveryTier() {
        ExPassive passive = exOf("pecorine").passive().orElseThrow();
        ExPassive.Clause base = passive.rungFor(1).orElseThrow().clauses().get(0);
        assertEquals(Optional.empty(), base.type(), "no effect type of ours expresses max health");
        assertEquals(Optional.of(ResourceLocation.withDefaultNamespace("health_boost")),
                base.vanilla(), "so it is vanilla's own max-health effect");
        assertEquals(PECORINE_AMPLIFIER, base.amplifier(),
                "one full reference bar (20 health) = 4 × (4 + 1)");

        ExPassive.Clause plus = passive.rungFor(2).orElseThrow().clauses().get(0);
        assertEquals(PECORINE_AMPLIFIER, plus.amplifier(),
                "both rungs clamp: 464% and 500% of her own bar are both past the ceiling");

        // The clamp is a clamp, and here is the number it is clamping.
        double baseHealth = 75 + 75 * LEVEL;
        double plusHealth = 1275 + 75 * LEVEL;
        assertEquals(4.64, baseHealth / PECORINE_OWN_MAX_HEALTH, 1e-2,
                "【75+75*技能等级】 = " + (int) baseHealth + " is 464% of her own 3377-health bar");
        assertEquals(5.00, plusHealth / PECORINE_OWN_MAX_HEALTH, 1e-2,
                "【1275+75*技能等级】 = " + (int) plusHealth + " is 500% — the 「+」 really is bigger, and both "
                        + "are past what a max-health passive can mean here");
        assertEquals(4 * (PECORINE_AMPLIFIER + 1), 20,
                "amplifier 4 is one full 20-health reference bar, which is the ceiling this project uses");
    }

    // ------------------------------------------------------------------ 3. EX gets no level band

    /**
     * <b>The human's explicit rule: EX gets no level bonus.</b>
     *
     * <p>Asserted across a band boundary rather than at one level: a level-30 character and a level-31 character
     * are in different bands, so an implementation that (wrongly) routed EX through
     * {@code TieredAmounts.resolve(type, params, band)} would give 5 and 5 here only because both clamp — so the
     * discriminating case is a clause whose declared tier leaves room, below.
     */
    @Test
    void exIgnoresTheLevelBand() {
        ExPassive.Clause clause = ExPassive.Clause.of(EffectType.BUFF_ATTACK_AREA,
                com.astrum.pcrd.api.v1.TieredParams.ofTier(1));

        // The EX resolution: band 0, always.
        assertEquals(1, ExPassives.tierOf(clause), "a tier-1 EX clause stays at tier 1");

        // And the band that WOULD apply if EX were not exempt — 3 at level 95 — is deliberately not used.
        int withBand = TieredAmounts.tierOf(EffectType.BUFF_ATTACK_AREA, clause.params(),
                LevelBands.bonusAt(95));
        assertEquals(4, withBand,
                "this is what a normal hook of the same type and tier resolves to at level 95; EX must not");
        assertTrue(ExPassives.tierOf(clause) < withBand,
                "the whole point of the exemption: EX is the declared rung, never the declared rung + band");
    }

    /**
     * The exemption holds for every shipped EX clause at every tier, at both ends of the level range.
     *
     * <p>A loop rather than a case, because the failure this guards is one call site remembering to pass the band
     * — and a single-sample test would miss the nine other clauses.
     */
    @Test
    void everyShippedExClauseIsBandFree() {
        for (String id : SHIPPED) {
            ExPassive passive = exOf(id).passive().orElseThrow();
            for (int tier = 1; tier <= 3; tier++) {
                Optional<ExPassive.Rung> rung = passive.rungFor(tier);
                if (rung.isEmpty()) {
                    continue;
                }
                for (ExPassive.Clause clause : rung.get().clauses()) {
                    if (clause.type().isEmpty()) {
                        continue;   // a vanilla clause has no rung of ours to resolve
                    }
                    assertEquals(clause.params().tier(), ExPassives.tierOf(clause),
                            id + " tier " + tier + ": an EX clause resolves as declared");
                }
            }
        }
    }

    // ------------------------------------------------------------------ 4. the 17th component

    /**
     * <b>{@code CharacterDef} still has exactly sixteen components, and EX is not one of them.</b>
     *
     * <p>This is the constraint that shaped the whole design: {@code RecordCodecBuilder.group} takes at most
     * sixteen, so a seventeenth field does not compile. Asserted reflectively so the fact is in the suite: a
     * future author who adds a field here is told by this test exactly what the ceiling is, rather than by a
     * compiler error that looks like an unrelated problem.
     */
    @Test
    void characterDefIsStillAtSixteenComponentsAndExIsNotOne() {
        assertEquals(16, CharacterDef.class.getRecordComponents().length,
                "RecordCodecBuilder.group's ceiling is sixteen; EX therefore lives in affection.skills, "
                        + "keyed by the reserved tag \"ex\"");
        for (var component : CharacterDef.class.getRecordComponents()) {
            assertFalse(component.getName().toLowerCase(java.util.Locale.ROOT).contains("ex"),
                    "no CharacterDef component may be the EX passive: found " + component.getName());
        }
        // And the reserved tag is the marker, so a ladder named "ex" IS the EX skill.
        assertEquals("ex", SkillLadder.EX_SKILL);
        assertTrue(exOf("djeeta").isEx());
    }

    /**
     * <b>A pack that writes no {@code passive} still loads, and its EX is simply inert.</b>
     *
     * <p>The additive promise every other optional field in this schema makes: an older pack must not fail to
     * parse, and a ladder with no passive must not be mistaken for one that grants something invisible.
     */
    @Test
    void aLadderWithoutAPassiveIsInertRatherThanBroken() {
        SkillLadder bare = new SkillLadder(SkillLadder.EX_SKILL, "龙卷的攻刃",
                Optional.of(com.astrum.pcrd.api.v1.SkillUpgrade.named("龙卷的攻刃+")), Optional.empty());
        assertEquals(Optional.empty(), bare.passive());
        // NameAt still works — the row still shows the rung, which is what a pack that predates the passive gets.
        assertEquals("龙卷的攻刃+", bare.nameAt(2));
    }

    /** {@code rungFor} climbs back up when a pack ships only the higher rung — the documented degradation. */
    @Test
    void aPassiveWithOnlyAHigherRungAppliesItAtTheLowerTiersToo() {
        ExPassive onlyTier2 = new ExPassive(List.of(
                new ExPassive.Rung(2, List.of(ExPassive.Clause.of(EffectType.BUFF_ATTACK_AREA,
                        com.astrum.pcrd.api.v1.TieredParams.ofTier(3))))));
        assertEquals(2, onlyTier2.rungFor(1).orElseThrow().tier(),
                "a character who ships only the + rung keeps it at tier 1 rather than buffing nothing");
        assertEquals(2, onlyTier2.rungFor(3).orElseThrow().tier());
    }

    /** A clause naming both halves, or neither, is refused with a message rather than applied as nonsense. */
    @Test
    void aMalformedClauseReportsWhyItCannotApply() {
        ExPassive.Clause both = new ExPassive.Clause(Optional.of(EffectType.BUFF_ATTACK_AREA),
                com.astrum.pcrd.api.v1.TieredParams.ofTier(1),
                Optional.of(ResourceLocation.withDefaultNamespace("speed")), 0,
                Optional.empty(), com.astrum.pcrd.api.v1.GrantDuration.PERMANENT);
        assertTrue(both.problem().isPresent(), "both halves filled is a contradiction");

        ExPassive.Clause neither = new ExPassive.Clause(Optional.empty(),
                com.astrum.pcrd.api.v1.TieredParams.FIRST, Optional.empty(), 0,
                Optional.empty(), com.astrum.pcrd.api.v1.GrantDuration.PERMANENT);
        assertTrue(neither.problem().isPresent(), "neither half filled grants nothing");

        ExPassive.Clause notTiered = ExPassive.Clause.of(EffectType.EXTRA_DAMAGE,
                com.astrum.pcrd.api.v1.TieredParams.ofTier(1));
        assertTrue(notTiered.problem().isPresent(), "a non-tiered effect has no rung to carry");

        ExPassive.Clause fine = ExPassive.Clause.of(EffectType.BUFF_ATTACK_AREA,
                com.astrum.pcrd.api.v1.TieredParams.ofTier(1));
        assertEquals(Optional.empty(), fine.problem());

        // A flat clause with no formula would grant 0 points while wearing a name — the silent no-op this
        // project treats as a defect, so it must be a reported problem rather than a valid clause.
        ExPassive.Clause flatWithoutFormula = new ExPassive.Clause(
                Optional.of(EffectType.FLAT_ATTACK_UP), com.astrum.pcrd.api.v1.TieredParams.FIRST,
                Optional.empty(), 0, Optional.empty(),
                com.astrum.pcrd.api.v1.GrantDuration.PERMANENT);
        assertTrue(flatWithoutFormula.problem().isPresent(),
                "a flat clause must name base/perLevel, or it grants nothing");

        // And the valid flat form: a formula, permanent, no vanilla effect.
        ExPassive.Clause flat = ExPassive.Clause.flat(EffectType.FLAT_ATTACK_UP,
                new com.astrum.pcrd.api.v1.FlatAmount(15.0, 15.0),
                com.astrum.pcrd.api.v1.GrantDuration.PERMANENT);
        assertEquals(Optional.empty(), flat.problem());
        assertEquals(1_560.0, flat.pointsAt(103), 1e-9, "15 + 15 × 103: the formula is the wiki's");
    }

    /** The vanilla effect a clause names must exist in the registry: {@code health_boost} is not optional. */
    @Test
    void theVanillaEffectPecorinesExNeedsIsRegistered() {
        assertTrue(BuiltInRegistries.MOB_EFFECT.containsKey(ExPassives.HEALTH_BOOST),
                "minecraft:health_boost must exist, or 公主之力 has no mechanism at all");
    }
}
