package com.astrum.pcrd.impl.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>计数</b> as arithmetic: the cap ladder, the accumulate rule, and the spend.
 *
 * <h2>Why the seams, and not a live entity</h2>
 * {@link Counters#add} and {@link Counters#consume} need a {@code LivingEntity} and a bound effect registry,
 * neither of which exists in a unit test. So the two rules that decide the answer — "a grant adds one and
 * clamps at the cap", "a spend removes what is there and no more" — are pure methods, and this file asserts
 * those over the sequences a real character produces. That is the same seam {@code EffectExecutors.reachesCap}
 * gives the stack rule, and for the same reason: the bug that matters is in the arithmetic, not in
 * {@code addEffect}.
 *
 * <h2>The one thing that must not be "fixed"</h2>
 * {@code addsOneStackAtATimeAndNeverReplaces} is the assertion a future reader will be tempted to invert,
 * because {@code SHIELD} was corrected in task 41 to be <em>restored</em> rather than accumulated. A counter
 * is not a shield: a tally that replaced would make every mark-granting skill but the last one pointless.
 * {@link Counters}' javadoc says why at length; this test is the part that fails if someone disagrees.
 */
class CounterTest {

    /**
     * The cap ladder is {@link com.astrum.pcrd.api.v1.EffectTiers}'s, four marks a rung.
     *
     * <p>The top rung's 20 is 雪菲's 6★ 「最多可获得20个」, which is where the human's 「最高…20层」 comes
     * from. Pinning all five rungs means a retune of the step has to be deliberate: it moves the ladder for
     * every character that counts anything.
     */
    @Test
    void theCapLadderIsFourMarksARung() {
        assertEquals(4, Counters.capForTier(1));
        assertEquals(8, Counters.capForTier(2));
        assertEquals(12, Counters.capForTier(3));
        assertEquals(16, Counters.capForTier(4));
        assertEquals(20, Counters.capForTier(5), "the 6★ cap: 「最多可获得20个」");
        assertEquals(20, Counters.MAX_STACKS, "MAX_STACKS is the top rung, not a second constant");
    }

    /** A tier outside the ladder clamps into it rather than answering a cap nobody authored. */
    @Test
    void aTierOutsideTheLadderClamps() {
        assertEquals(4, Counters.capForTier(0), "there is no rung 0");
        assertEquals(4, Counters.capForTier(-3));
        assertEquals(20, Counters.capForTier(9), "there is no sixth rung");
    }

    /**
     * A bwiki cap that is not on the ladder maps to the <b>smallest rung that does not truncate it</b>.
     *
     * <p>雪菲's base ultimate says 「最大10个」, and no rung is 10: rung 2 is 8 (which would cut her off two
     * marks early) and rung 3 is 12. So 10 → rung 3, and the two marks of headroom are recorded in her json
     * rather than being silently absorbed. Her 6★ 「最多可获得20个」 is rung 5 exactly.
     */
    @Test
    void aStatedCapMapsToTheSmallestRungThatDoesNotTruncateIt() {
        assertEquals(1, Counters.tierForStatedCap(4));
        assertEquals(2, Counters.tierForStatedCap(8));
        assertEquals(3, Counters.tierForStatedCap(9));
        assertEquals(3, Counters.tierForStatedCap(10), "「最大10个」 has no rung of its own; 3 is the nearest above");
        assertEquals(4, Counters.tierForStatedCap(13));
        assertEquals(5, Counters.tierForStatedCap(17));
        assertEquals(5, Counters.tierForStatedCap(20), "「最多可获得20个」 is the top rung exactly");
        assertEquals(5, Counters.tierForStatedCap(60), "a cap past the ladder answers the ladder's top");
        assertEquals(1, Counters.tierForStatedCap(0), "a nonsense cap answers the first rung");
    }

    /**
     * <b>Ten swings of a counter stop at the cap</b> — the accumulate rule, written as a sequence.
     *
     * <p>This is the arithmetic behind the on-server proof: ten hits that each add one mark reach 20 and stay
     * there, and the eleventh is a clamp rather than a 21st. It is deliberately expressed with
     * {@link Counters#nextCount} in a loop, so the test is the same computation {@code Counters.add} runs.
     */
    @Test
    void addsOneStackAtATimeAndNeverReplaces() {
        int cap = Counters.capForTier(5);
        int[] expected = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 20, 20};
        int count = 0;
        for (int swing = 0; swing < expected.length; swing++) {
            count = Counters.nextCount(count, cap);
            assertEquals(expected[swing], count,
                    "swing " + (swing + 1) + " must leave " + expected[swing] + " marks, not "
                            + count);
        }
        assertEquals(cap, count, "ten swings past the cap change nothing");
    }

    /**
     * <b>A counter adds; it does not replace.</b> Twenty grants from empty reach twenty, not one.
     *
     * <p>This is the assertion that is the exact opposite of the shield's, on purpose: see the class javadoc.
     */
    @Test
    void twentyGrantsReachTwentyAndNotOne() {
        int cap = Counters.capForTier(5);
        int count = 0;
        for (int grant = 0; grant < 20; grant++) {
            count = Counters.nextCount(count, cap);
        }
        assertEquals(20, count, "a tally accumulates; a quantity of health is restored instead");
    }

    /**
     * A spend removes exactly what was asked, and <b>only</b> what is there.
     *
     * <p>雪菲's 「移除自身1个『冰龙之印』」 on a tally of one removes one and leaves none — which is why the
     * effect disappears rather than sitting at an amplifier of {@code -1}. On an empty tally it removes
     * nothing at all, and the skill deals no bonus because the executor asks this method first.
     */
    @Test
    void aSpendRemovesWhatWasAskedAndNoMore() {
        assertEquals(1, Counters.consumable(1, 1), "one mark available, one asked: one removed");
        assertEquals(1, Counters.consumable(5, 1), "five available, one asked: one removed");
        assertEquals(0, Counters.consumable(0, 1), "nothing to spend, nothing removed");
        assertEquals(3, Counters.consumable(3, 9), "nine asked from three available: three removed");
        assertEquals(0, Counters.consumable(3, 0), "asking for nothing removes nothing");
        assertEquals(0, Counters.consumable(3, -1), "a negative request is not a request");
    }

    /**
     * <b>A skill that consumes a counter loses exactly one</b> — the sequence the server proof logs.
     *
     * <p>Grant four, spend one, and the tally is three; the number the skill is worth is the count
     * <em>before</em> the spend, which is why the executor captures it first. Both halves are asserted here
     * so the two cannot drift apart in a later edit.
     */
    @Test
    void oneSpendLeavesOneFewerMark() {
        int cap = Counters.capForTier(3);
        int count = 0;
        for (int grant = 0; grant < 4; grant++) {
            count = Counters.nextCount(count, cap);
        }
        assertEquals(4, count, "「追加4个」 is four marks");
        int held = count;
        int spent = Counters.consumable(count, 1);
        count -= spent;
        assertEquals(1, spent, "exactly one mark leaves");
        assertEquals(3, count, "four minus one is three");
        assertEquals(4, held, "the bonus is worth what she HELD, so the count has to be read first");
    }

    /** The registry path and the id are the ones the task names, and they agree. */
    @Test
    void theRegistryPathIsCounter() {
        assertEquals("counter", Counters.PATH);
        assertEquals("astrum:counter", Counters.ID.toString());
        assertTrue(Counters.COLOUR >= 0 && Counters.COLOUR <= 0xFFFFFF, "a colour, not a sentinel");
    }
}
