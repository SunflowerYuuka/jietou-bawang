package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「按角色数据配置」: each character's declared 暴击率, as data and as one attribute modifier.</b>
 *
 * <h2>What is asserted, and on which side of the game boundary</h2>
 * No {@code LivingEntity} exists in this project's unit tests, so the grant is asserted on an
 * {@link AttributeInstance} directly — which is the whole mechanism anyway: {@code astrum:crit_chance} is a
 * {@link RangedAttribute} whose value is base plus modifiers, and {@code Crit.chanceOf} reads that value. So
 * "her declared chance <b>is</b> what the attribute gains, and removing the modifier <b>returns it to 0</b>" is
 * provable here, without a world.
 *
 * <p>What is <b>not</b> provable here is the lifecycle hook: a {@code ServerPlayer} with a party attachment cannot
 * be built in a unit test, so "the grant appears on enrolment and disappears when she leaves" is asserted at the
 * pure seam ({@link CritChances#wantedFor}) and is listed in {@code task-58-report.md} as something only a human in
 * the game can confirm. The call itself is one line inside {@code ExPassives.apply}, the build's single membership
 * sweep — the place an EX passive already appears and disappears.
 *
 * <h2>The two rulings this file pins</h2>
 * <ol>
 *   <li>The <b>band table</b>: a character whose kit mentions crit is worth strictly more than one whose kit does
 *       not, and the numbers are {@code CritChances.HIGH_CHANCE} / {@code LOW_CHANCE}, not a per-character
 *       invention. {@link #everyCritWantedCharacterOutCritsEveryOtherOne} reads the shipped JSON, so a value that
 *       drifted out of its band fails.</li>
 *   <li>The <b>conversion</b>: PCR gives a crit <em>score</em>, not a rate, so a named score is converted by
 *       {@link CritChances#CHANCE_PER_POINT} rather than rounded to a band. 千歌's 50 → 2.5% is asserted from the
 *       shipped file.</li>
 * </ol>
 */
class CritChancesTest {

    private static final List<String> SHIPPED = List.of("ayane", "chika", "christina", "djeeta", "hiyori",
            "kokkoro", "kurumi", "kyaru", "kyoka", "mimi", "misogi", "muimi", "nozomi", "pecorine", "rei",
            "rino", "saren", "sheffy", "shizuru", "suzume", "tsumugi", "yui");

    /** The characters whose material names a crit score, and the value that becomes their chance. */
    private static final List<String> CRIT_WANTED = List.of("chika", "christina", "kyoka", "rino", "shizuru",
            "suzume");

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = CritChancesTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    /**
     * {@return a bare {@code astrum:crit_chance} instance — the attribute the grant writes, without an entity}
     *
     * <p>{@code RangedAttribute}'s four arguments are the ones {@code Crit.CRIT_CHANCE} registers with, so the
     * arithmetic the test asserts is the arithmetic the game's attribute performs: base {@code 0}, clamped to
     * {@code [0, 1]}. {@code Holder.direct} is a holder over a bare attribute — no registry, no datapack.
     */
    private static AttributeInstance instance() {
        Holder<Attribute> attribute = Holder.direct(
                new RangedAttribute("attribute.astrum.crit_chance", Crit.DEFAULT_CHANCE, 0.0, Crit.MAX_CHANCE));
        return new AttributeInstance(attribute, ignored -> {
        });
    }

    // ------------------------------------------------------------------ the band table

    /**
     * <b>The conversion is the source's own scale, stated once.</b>
     *
     * <p>PCR's 暴击 rate is 「crit × 0.05%」 with a level term this build does not carry, so one point is half a
     * thousandth: 50 → 2.5%, 100 → 5%, 200 → 10%. The band numbers are anchored on that scale — the high band is
     * exactly the conversion of a 100-point score — so the two halves of the data are one table rather than two
     * invented sets of numbers.
     */
    @Test
    void theConversionIsHalfAThousandthPerPoint() {
        assertEquals(0.0005, CritChances.CHANCE_PER_POINT, 1e-12);
        assertEquals(0.025, CritChances.chanceForScore(50), 1e-12, "千歌's 50 -> 2.5%");
        assertEquals(0.05, CritChances.chanceForScore(100), 1e-12, "静流's 100 -> 5%");
        assertEquals(0.0375, CritChances.chanceForScore(75), 1e-12, "璃乃's highest, 75 -> 3.75%");
        assertEquals(0.10, CritChances.chanceForScore(200), 1e-12, "铃莓's 200 -> 10%");
        // The high band is a 100-point score PLUS the gear share — no longer the bare conversion, because the
        // human authorised standing in for the equipment this mod does not ship (「原作还有装备要素，mod没有制作相关
        // 内容」 / 「可以」). Asserted through chanceInPlay so the relationship stays checkable.
        assertEquals(CritChances.chanceInPlay(100), CritChances.HIGH_CHANCE, 1e-12,
                "the high band is 100 points plus the gear share");
        assertEquals(CritChances.chanceInPlay(40), CritChances.LOW_CHANCE, 1e-12,
                "and the low band is 40 points plus the same share");
        assertEquals(0.0, CritChances.chanceForScore(0), 1e-12, "no score is no grant");
        assertEquals(0.0, CritChances.chanceForScore(-5), 1e-12, "a negative score is no grant");
        assertEquals(0.0, CritChances.chanceForScore(Double.NaN), 1e-12, "a hostile pack value is no grant");
        assertEquals(Crit.MAX_CHANCE, CritChances.chanceForScore(1_000_000), 1e-12,
                "a huge score clamps at the attribute's own ceiling rather than exceeding it");
    }

    /**
     * <b>The human's rule, read off the shipped data: 「有暴击需求的比没暴击需求的角色，基础暴击率高一些」.</b>
     *
     * <p>Every character whose kit names crit must be <b>strictly</b> above every character whose kit does not, and
     * every shipped character must declare something (the default is 0, so "declares none" would silently be the
     * pre-task-58 behaviour). This is the test that would fail if somebody hand-edited one JSON to a nicer-looking
     * number outside its band.
     */
    @Test
    void everyCritWantedCharacterOutCritsEveryOtherOne() throws Exception {
        double lowestCritWanted = Double.MAX_VALUE;
        double highestOther = 0.0;
        for (String id : SHIPPED) {
            CharacterDef def = load(id);
            double chance = CritChances.chanceOf(def);
            assertTrue(chance > 0.0, id + " must declare a crit chance; the attribute default is 0, so 0 here "
                    + "would mean the character was never given one");
            assertTrue(chance <= Crit.MAX_CHANCE, id + " must stay inside the chance ceiling");
            if (CRIT_WANTED.contains(id)) {
                lowestCritWanted = Math.min(lowestCritWanted, chance);
            } else {
                highestOther = Math.max(highestOther, chance);
            }
        }
        assertTrue(lowestCritWanted > highestOther,
                "a character who wants crit must out-crit every one who does not: lowest crit-wanted "
                        + lowestCritWanted + " must exceed highest other " + highestOther);
    }

    /** The four characters whose material names a score get the conversion, not the band. */
    @Test
    void aNamedScoreWinsOverTheBand() throws Exception {
        // Runtime values now include the gear share, so each is (score + 400) x 0.0005.
        assertEquals(0.225, CritChances.chanceOf(load("chika")), 1e-12, "50 + 400 = 450 points -> 22.5%");
        assertEquals(0.2375, CritChances.chanceOf(load("rino")), 1e-12, "75 + 400 = 475 -> 23.75%");
        assertEquals(0.25, CritChances.chanceOf(load("shizuru")), 1e-12, "100 + 400 = 500 -> 25%");
        assertEquals(0.30, CritChances.chanceOf(load("suzume")), 1e-12, "200 + 400 = 600 -> 30%");
        // ... and a character with no score gets the high band exactly, not a value of her own.
        assertEquals(CritChances.HIGH_CHANCE, CritChances.chanceOf(load("kyoka")), 1e-12);
        assertEquals(CritChances.HIGH_CHANCE, CritChances.chanceOf(load("christina")), 1e-12);
        // ... while a kit with no crit clause at all gets the low band.
        assertEquals(CritChances.LOW_CHANCE, CritChances.chanceOf(load("djeeta")), 1e-12);
        assertEquals(CritChances.LOW_CHANCE, CritChances.chanceOf(load("pecorine")), 1e-12);
    }

    /** An undeclared chance is <b>no</b> grant — the pre-existing behaviour, and not a "0% row". */
    @Test
    void anUndeclaredChanceIsZeroAndNotABand() {
        var bare = new com.astrum.pcrd.api.v1.AffectionDef(0, 0, List.of());
        CharacterDef def = new CharacterDef(1, "nobody", com.astrum.pcrd.api.v1.Dimension.RESOURCE,
                Optional.empty(), Optional.empty(), List.of(), null, null, bare, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertEquals(0.0, CritChances.chanceOf(def), 1e-12, "no declaration is no grant");
        assertEquals(0.0, CritChances.chanceOf(null), 1e-12, "no character is no grant");
        assertEquals(1.0, CritChances.chanceOf(new CharacterDef(1, "nobody", def.dimension(),
                Optional.empty(), Optional.empty(), List.of(), null, null,
                new com.astrum.pcrd.api.v1.AffectionDef(0, 0, List.of(), 1.0 + 1e-9), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty())),
                1e-12, "a chance above the ceiling is clamped to it, never above: 1.0 is the maximum");
    }

    // ------------------------------------------------------------------ the attribute

    /**
     * <b>Her declared chance becomes the modifier's amount, and removing it returns the attribute to 0.</b>
     *
     * <p>This is the verification the brief asks for in the only form a unit test can give it: the attribute's
     * <em>value</em> is what {@code Crit.chanceOf} reads, so a modifier of {@code 0.0375} under
     * {@code astrum:crit_chance/&lt;character&gt;} <b>is</b> a 3.75% crit chance for anybody wielding that
     * character, with no second path.
     */
    @Test
    void theDeclaredChanceIsTheModifierAmountAndRemovalUndoesIt() throws Exception {
        AttributeInstance instance = instance();
        assertEquals(0.0, instance.getValue(), 1e-12, "the shipped default: nobody crits until something grants");

        CharacterDef rino = load("rino");
        ResourceLocation id = ResourceLocation.parse("astrum:rino");
        double chance = CritChances.chanceOf(rino);
        assertTrue(CritChances.grantOn(instance, id, chance), "the first grant writes");
        assertEquals(chance, instance.getValue(), 1e-12, "the attribute's value IS her crit chance");
        assertEquals(chance, CritChances.grantedOn(instance, id), 1e-12);

        AttributeModifier modifier = instance.getModifier(CritChances.modifierId(id));
        assertNotNull(modifier, "the grant is a named modifier, so /attribute can show whose it is");
        assertEquals(AttributeModifier.Operation.ADD_VALUE, modifier.operation(),
                "a chance is a fraction added onto a base of 0, not a fraction of another fraction");
        assertEquals(chance, modifier.amount(), 1e-12);

        assertFalse(CritChances.grantOn(instance, id, chance), "a re-apply at the same amount is no write");
        assertTrue(CritChances.revokeFrom(instance, id), "leaving the party removes it");
        assertEquals(0.0, instance.getValue(), 1e-12, "and the attribute is back to the default");
        assertFalse(CritChances.revokeFrom(instance, id), "a second removal is a no-op, not an error");
    }

    /**
     * Two characters in one party: the attribute <b>sums</b> their modifiers, but {@code Crit.chanceOf} takes the
     * <b>best</b> — the human's ruling 「B取最高」.
     *
     * <p>Those are two different layers and the distinction is the point of this test: the attribute is a plain
     * accumulator (vanilla's own rule, and foreign crit attributes add to it too), while the rule that turns the
     * pool into a chance is this mod's and answers max. Adding here would make a full party crit four times as
     * often as a solo character, which makes the stat a function of party size rather than of the character.
     */
    @Test
    void twoCharactersInOnePartyTakeTheBestNotTheSum() throws Exception {
        AttributeInstance instance = instance();
        ResourceLocation chika = ResourceLocation.parse("astrum:chika");
        ResourceLocation shizuru = ResourceLocation.parse("astrum:shizuru");
        CritChances.grantOn(instance, chika, CritChances.chanceOf(load("chika")));
        CritChances.grantOn(instance, shizuru, CritChances.chanceOf(load("shizuru")));
        assertEquals(0.225 + 0.25, instance.getValue(), 1e-12,
                "the ATTRIBUTE still sums its modifiers — that is vanilla's rule, not ours to change");
        assertNotEquals(CritChances.modifierId(chika), CritChances.modifierId(shizuru),
                "one id per character, or the second girl would silently cancel the first one's grant");
        CritChances.revokeFrom(instance, chika);
        assertEquals(0.25, instance.getValue(), 1e-12, "removing one leaves the other intact");
    }

    /** A body with no crit attribute, and a null body, are answers rather than exceptions. */
    @Test
    void aBodyWithoutTheAttributeIsSkippedRatherThanThrown() {
        assertFalse(CritChances.grantOn(null, ResourceLocation.parse("astrum:rino"), 0.0375));
        assertFalse(CritChances.revokeFrom(null, ResourceLocation.parse("astrum:rino")));
        assertEquals(0.0, CritChances.grantedOn(null, ResourceLocation.parse("astrum:rino")), 1e-12);
        assertEquals(Optional.empty(), CritChances.instanceOf(null));
        assertEquals(0.0, CritChances.reconcile(null), "no player is no change");
    }

    /** A non-positive chance is no grant — never a negative modifier on the attribute. */
    @Test
    void aNonPositiveChanceWritesNothing() {
        AttributeInstance instance = instance();
        ResourceLocation id = ResourceLocation.parse("astrum:nobody");
        assertFalse(CritChances.grantOn(instance, id, 0.0));
        assertFalse(CritChances.grantOn(instance, id, -0.1));
        assertFalse(CritChances.grantOn(instance, id, Double.NaN));
        assertEquals(0.0, instance.getValue(), 1e-12);
    }

    /** The modifier id is derived from the character, in this mod's namespace, spelled the way the report says. */
    @Test
    void theModifierIdNamesTheCharacter() {
        assertEquals(ResourceLocation.parse("astrum:crit_chance/rino"),
                CritChances.modifierId(ResourceLocation.parse("astrum:rino")));
        assertEquals(ResourceLocation.parse("astrum:crit_chance/unknown"), CritChances.modifierId(null));
    }

    /**
     * {@code wantedFor} answers the party's chances and nobody else's — the pure half of the reconcile.
     *
     * <p>No datapack registry is up in a unit test, so this asserts the <b>safe direction</b>: no registry (or no
     * party) is "nothing to grant", never an exception and never a stale write. The resolution itself is
     * {@link CritChances#chanceOf}, pinned above from the shipped files.
     */
    @Test
    void aMissingRegistryOrPartyGrantsNothing() {
        var party = com.astrum.pcrd.impl.attach.Party.of(List.of(
                ResourceLocation.parse("astrum:chika"), ResourceLocation.parse("astrum:djeeta")));
        assertEquals(List.of(), CritChances.wantedFor(null, party));
        assertEquals(List.of(), CritChances.wantedFor(null, null));
        assertEquals(0.0, CritChances.reconcile(null));
    }
}