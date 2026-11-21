package com.astrum.pcrd.impl.effect;

import java.util.OptionalDouble;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>暴击</b> as arithmetic: the roll, the defaults, the clamp, and the foreign-mod pool.
 *
 * <h2>What this can and cannot assert</h2>
 * There is no {@code Player} in a unit test, so nothing here reads the attributes off a live entity — the
 * attribute plumbing is the same {@code DeferredRegister} / {@code EntityAttributeModificationEvent} pair
 * {@code MagicAttackTest} covers, and it either works or the game fails to start. What <em>is</em> asserted
 * is every rule that decides <em>whether a hit crits and by how much</em>, because those are the rules that
 * were wrong in earlier tasks of this kind: a chance of 0 that still crits, a multiplier that compounds, a
 * roll that happens twice.
 *
 * <p>The roll is a pure function of a {@link RandomSource}, which is what makes "deterministic under test"
 * true rather than a claim: two sources seeded the same answer the same sequence, and a source is asked for
 * exactly one number per hit.
 */
class CritTest {

    // ------------------------------------------------------------------ the roll itself

    /**
     * The same seed answers the same sequence of crits, and two rolls off one source are one roll each.
     *
     * <p>This is the determinism the task asks for, and it is a property of {@link Crit#roll}: the answer is
     * a function of the {@code RandomSource} and nothing else — no clock, no entity, no registry.
     */
    @Test
    void theSameSeedAnswersTheSameSequenceOfCrits() {
        RandomSource first = RandomSource.create(424242L);
        RandomSource second = RandomSource.create(424242L);
        for (int i = 0; i < 200; i++) {
            assertEquals(Crit.roll(first, 0.35), Crit.roll(second, 0.35),
                    "roll " + i + " must not depend on anything but the source");
        }
    }

    /**
     * <b>A chance of 0 never crits</b> — over ten thousand rolls, because a one-in-a-million escape is still
     * an escape.
     *
     * <p>{@code nextDouble()} answers in {@code [0, 1)}, and the comparison is strict, so {@code x < 0} is
     * never true. That is why the answer does not depend on the draw at all, and it is the property a player
     * with no crit stat depends on: her hits deal exactly what they dealt before this feature existed.
     */
    @Test
    void aChanceOfZeroNeverCrits() {
        RandomSource random = RandomSource.create(7L);
        for (int i = 0; i < 10_000; i++) {
            assertFalse(Crit.roll(random, 0.0), "a 0 chance must never crit (roll " + i + ")");
        }
    }

    /** A chance of 1 always crits: {@code nextDouble()} is strictly below 1. */
    @Test
    void aChanceOfOneAlwaysCrits() {
        RandomSource random = RandomSource.create(11L);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(Crit.roll(random, 1.0), "a 1.0 chance must always crit (roll " + i + ")");
        }
    }

    /**
     * One {@code roll} costs the source <b>exactly one</b> number.
     *
     * <h2>Why this is the assertion that matters</h2>
     * "One roll per hit" is what keeps a hit from critting twice for being settled in two stages, and it is
     * also what keeps this feature from perturbing the world's RNG stream. Both are asserted the same way: a
     * source that has been asked for {@code n} doubles answers the same as a fresh source seeded the same
     * after {@code n} draws. Here the control is a source asked for one draw and then a roll — if the roll
     * took two, the third answer would differ.
     */
    @Test
    void oneRollConsumesExactlyOneNumber() {
        RandomSource withRoll = RandomSource.create(99L);
        RandomSource control = RandomSource.create(99L);
        Crit.roll(withRoll, 0.5);          // the roll under test
        control.nextDouble();              // the same one number, taken by hand
        for (int i = 0; i < 20; i++) {
            assertEquals(control.nextDouble(), withRoll.nextDouble(),
                    "a roll must consume exactly one double, or the streams desynchronise at " + i);
        }
    }

    /** A {@code null} source or a nonsense chance answers {@code false} rather than throwing. */
    @Test
    void nothingCritsWithoutASourceOrWithNonsenseChances() {
        assertFalse(Crit.roll(null, 0.5), "no source, no crit");
        RandomSource random = RandomSource.create(1L);
        assertFalse(Crit.roll(random, Double.NaN), "NaN is not a chance");
        assertFalse(Crit.roll(random, -0.5), "a negative chance is not a chance");
    }

    // ------------------------------------------------------------------ the numbers

    /**
     * The shipped defaults: chance <b>0</b>, multiplier <b>1.5</b>.
     *
     * <p>1.5 is not a taste call — it is {@code Player.attack}'s own hard-coded {@code f1 *= 1.5F}, so a crit
     * granted by this mod is worth exactly what the game's crit is worth until a pack says otherwise. The
     * chance default of 0 is what makes the whole feature additive: an untouched entity deals identically to
     * what it dealt before this class existed.
     */
    @Test
    void theDefaultsAreNoChanceAndVanillasOwnMultiplier() {
        assertEquals(0.0, Crit.DEFAULT_CHANCE, "nobody crits until something grants it");
        assertEquals(1.5, Crit.DEFAULT_CRIT_DAMAGE, "vanilla's Player.attack multiplies a crit by 1.5");
        assertEquals(1.0, Crit.MAX_CHANCE, "the ceiling is 'always'");
        assertTrue(Crit.MAX_CRIT_DAMAGE >= 2.0 && Double.isFinite(Crit.MAX_CRIT_DAMAGE),
                "the damage ceiling must hold a real crit and stay finite: " + Crit.MAX_CRIT_DAMAGE);
    }

    /**
     * The summed chance pool clamps at 1.0, and never goes negative or {@code NaN}.
     *
     * <p>Three sources of 40% do not crit 120% of the time. This is the arithmetic {@code Crit.chanceOf}
     * performs after it has added this mod's attribute to every foreign one.
     */
    @Test
    void theChancePoolClampsAtAlways() {
        assertEquals(0.4, Crit.clampChance(0.4), 1e-9);
        assertEquals(1.0, Crit.clampChance(1.4), "three 40% sources crit 100% of the time, not 120%");
        assertEquals(0.0, Crit.clampChance(-0.2), "a negative pool is no chance at all");
        assertEquals(0.0, Crit.clampChance(Double.NaN), "NaN is no chance at all");
        assertEquals(0.0, Crit.clampChance(Double.POSITIVE_INFINITY), "an infinite chance is a broken save");
    }

    /**
     * The registry ids are the ones the task names, and the name keys are derived rather than typed.
     *
     * <p>{@code attribute.astrum.crit_chance} is what vanilla looks up for the tooltip; a class that spelled
     * it by hand could disagree with the id it registered, and the player would read a raw key.
     */
    @Test
    void theIdsAndTheirTranslationKeysAgree() {
        assertEquals("astrum:crit_chance", Crit.CHANCE_ID.toString());
        assertEquals("astrum:crit_damage", Crit.DAMAGE_ID.toString());
        assertEquals("attribute.astrum.crit_chance", Crit.CHANCE_NAME_KEY);
        assertEquals("attribute.astrum.crit_damage", Crit.DAMAGE_NAME_KEY);
    }

    // ------------------------------------------------------------------ the per-skill channel

    /**
     * <b>A skill's own multiplier replaces the global one; it does not add to it or multiply it.</b>
     *
     * <p>This is the human's ruling 「单独做一个通道更好点」 as arithmetic. 「从 2 倍提升至 4 倍」 means the
     * skill's crit is <b>4×</b> — at this build's default global of 1.5 that is {@code 4.0}, never
     * {@code 1.5 + 4 = 5.5} and never {@code 1.5 × 4 = 6.0}. A skill that declares nothing keeps the global,
     * which is the state of every hit before task 55 and of every shipped character but two.
     */
    @Test
    void aSkillsOwnMultiplierReplacesTheGlobalOne() {
        assertEquals(Crit.DEFAULT_CRIT_DAMAGE, Crit.multiplierFor(null, OptionalDouble.empty()), 1e-9,
                "no per-skill value means the caster's global multiplier");
        assertEquals(4.0, Crit.multiplierFor(null, OptionalDouble.of(4.0)), 1e-9,
                "4× means 4×; it must not become 1.5+4 or 1.5*4");
        assertEquals(3.5, Crit.multiplierFor(null, OptionalDouble.of(3.5)), 1e-9,
                "璃乃's 3.5× is a replacement too");
    }

    /**
     * <b>The channel's value is clamped exactly like the attribute.</b>
     *
     * <p>A pack is data and data can be wrong. The floor is 1.0 — a crit may never become a <em>weaker</em>
     * hit than the number the skill authored — and the ceiling is the attribute's own {@code MAX_CRIT_DAMAGE},
     * so a typo like {@code 1000} reads as a finite 16 rather than an infinity. A {@code NaN} is not a
     * multiplier at all: the caster's global applies instead of a hit that multiplies to nothing.
     */
    @Test
    void aSkillMultiplierIsClampedAndABrokenOneFallsBack() {
        assertEquals(1.0, Crit.multiplierFor(null, OptionalDouble.of(0.25)), 1e-9,
                "a crit below the authored number is not a crit");
        assertEquals(Crit.MAX_CRIT_DAMAGE, Crit.multiplierFor(null, OptionalDouble.of(1_000.0)), 1e-9,
                "the ceiling is the attribute's own");
        assertEquals(Crit.DEFAULT_CRIT_DAMAGE, Crit.multiplierFor(null, OptionalDouble.of(Double.NaN)), 1e-9,
                "broken data falls back to the global rather than poisoning the hit");
    }

    /**
     * <b>The channel changes how big a crit is, never whether one happens.</b>
     *
     * <p>Without an entity there is no crit chance to read, so the honest answer is {@code 1.0} even for a
     * skill that declares 4×: the skill's multiplier is consulted only after a roll. A {@code null} source is
     * the same answer, and neither consumes a number from it.
     */
    @Test
    void aSkillMultiplierCannotGrantACritWithoutAChanceOrASource() {
        RandomSource random = RandomSource.create(2024L);
        assertEquals(1.0, Crit.factorFor(null, random, OptionalDouble.of(4.0)),
                "no caster, no crit chance — the skill's value is not a chance");
        assertEquals(1.0, Crit.factorFor(null, null, OptionalDouble.of(4.0)), "no source, no crit");
        assertEquals(1.0, Crit.factorFor(null, random),
                "the global form delegates to the same rule and answers the same thing");
    }

    // ------------------------------------------------------------------ other mods

    /**
     * <b>Nothing is claimed about a mod that is not installed.</b>
     *
     * <p>The compatibility list is a soft probe — {@code BuiltInRegistries.ATTRIBUTE.getHolder(id)} answers
     * empty for a missing id — so in the pack this build was tested against, where <b>no</b> crit attribute
     * exists in any of the 347 jars (see {@code Crit}'s javadoc for the scan), {@link Crit#foreignPresent()}
     * must answer {@code false}. If it ever answers {@code true} here, either a mod got installed or the probe
     * grew a bug, and both are worth knowing.
     *
     * <p>This test is deliberately <em>not</em> "the compatibility works": that would need a real foreign mod,
     * and inventing a fixture that registers one under a fake namespace would prove only that the test can
     * call {@code getHolder}.
     */
    @Test
    void noForeignCritAttributeIsPresentInThisBuild() {
        assertFalse(Crit.foreignPresent(),
                "the shipped pack has no crit attribute of any other mod; if this fails, a mod was added and "
                        + "the report's compatibility section needs re-checking");
    }
}
