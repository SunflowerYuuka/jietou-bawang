package com.astrum.pcrd.impl.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The rotation cursor itself: pure arithmetic, no game (spec §5.3).
 *
 * <h2>What this pins</h2>
 * The human's rule is that <b>each character's rotation is independent</b>, so the cursor is a value per
 * character whose period is her own count. Three properties follow, and each has its own test:
 *
 * <ol>
 *   <li><b>Two and three skills drift apart.</b> A character with 2 rotating hooks goes 1-2-1-2 and one
 *       with 3 goes 1-2-3-1; after the second swing both have fired their second skill, and on the third
 *       the two-skill character is back at 1 while the three-skill one is at 3. That is the example in
 *       the dispatch, asserted as a sequence rather than as an assertion about one call.</li>
 *   <li><b>Wrap-around</b> is {@code (index + 1) % count}, so the cursor never exceeds the count and
 *       {@link SkillRotation#peek} is always a legal index.</li>
 *   <li><b>A character with no rotating hooks</b> is a no-op: {@code peek} answers 0 and {@code advance}
 *       returns the same value, so no entry is created for her and nothing throws.</li>
 * </ol>
 */
class SkillRotationTest {

    /**
     * The human's example, played out swing by swing.
     *
     * <p>A has two small skills, B has three. The test drives both cursors through five swings with the
     * same code path production uses ({@link SkillRotation#peek} then {@link SkillRotation#advance}), so
     * the assertion is about the interaction of two independent periods rather than about one call.
     */
    @Test
    void aTwoSkillAndAThreeSkillCharacterRotateIndependently() {
        SkillRotation a = SkillRotation.START;
        SkillRotation b = SkillRotation.START;

        StringBuilder aSequence = new StringBuilder();
        StringBuilder bSequence = new StringBuilder();
        for (int swing = 0; swing < 7; swing++) {
            aSequence.append(a.peek(2) + 1);
            bSequence.append(b.peek(3) + 1);
            a = a.advance(2);
            b = b.advance(3);
        }

        assertEquals("1212121", aSequence.toString(),
                "a character with two skills goes 1-2-1-2-…");
        assertEquals("1231231", bSequence.toString(),
                "a character with three skills goes 1-2-3-1-…");

        // The three moments the dispatch names explicitly, spelled out so a failure says which one broke.
        assertEquals(1, skillAt(0, 2), "swing 1: A fires her first skill");
        assertEquals(1, skillAt(0, 3), "swing 1: B fires her first skill");
        assertEquals(2, skillAt(1, 2), "swing 2: A fires her second");
        assertEquals(2, skillAt(1, 3), "swing 2: B fires her second");
        assertEquals(1, skillAt(2, 2), "swing 3: A is back to her first");
        assertEquals(3, skillAt(2, 3), "swing 3: B is on her third");
    }

    /** {@return the 1-based skill number a character with {@code count} skills fires on {@code swing}} */
    private static int skillAt(int swing, int count) {
        SkillRotation rotation = SkillRotation.START;
        for (int i = 0; i < swing; i++) {
            rotation = rotation.advance(count);
        }
        return rotation.peek(count) + 1;
    }

    /** Wrap-around: the cursor returns to 0 after the last skill and never overshoots. */
    @Test
    void theCursorWrapsAtTheCharactersOwnCount() {
        SkillRotation rotation = SkillRotation.START;
        for (int i = 0; i < 5; i++) {
            assertEquals(i % 3, rotation.peek(3), "swing " + (i + 1) + " of a 3-skill rotation");
            rotation = rotation.advance(3);
        }
        assertEquals(5 % 3, rotation.peek(3),
                "after 5 advances of a 3-skill rotation the cursor is floorMod(5,3)=2");
    }

    /** A character with no rotating hooks costs nothing and cannot corrupt a cursor she needs later. */
    @Test
    void anEmptyRotatingListIsANoOp() {
        assertEquals(0, SkillRotation.START.peek(0));
        assertEquals(0, new SkillRotation(7).peek(0), "a stale cursor must not index anything");

        SkillRotation rotation = new SkillRotation(2);
        assertSame(rotation, rotation.advance(0), "nothing fired, so nothing advances");
        assertEquals(2, rotation.cursor(), "and the stale cursor is kept for when she has hooks again");
    }

    /** A negative count is not a rotation either, rather than an exception on the effect path. */
    @Test
    void aNegativeCountIsTreatedAsEmpty() {
        assertEquals(0, SkillRotation.START.peek(-1));
        assertSame(SkillRotation.START, SkillRotation.START.advance(-4));
    }

    /**
     * A cursor left over from a longer hook list still lands in range.
     *
     * <p>A datapack can be reloaded between two swings, so a character can lose a skill while her cursor
     * is at index 2. {@code peek} normalises with {@code floorMod}, which is what keeps the book from
     * throwing {@code IndexOutOfBounds} at the player.
     */
    @Test
    void aCursorPastTheNewCountIsNormalised() {
        assertEquals(1, new SkillRotation(3).peek(2),
                "floorMod(3,2)=1: a stale cursor still lands in range, on the second skill");
    }

    /** A negative cursor cannot be constructed: the record clamps it. */
    @Test
    void aNegativeCursorIsClampedToZero() {
        assertEquals(0, new SkillRotation(-5).cursor());
        assertEquals(0, new SkillRotation(-5).peek(4));
    }

    /** {@link SkillRotation#START} is the first skill of every character, whatever her count. */
    @Test
    void startIsTheFirstSkillOfAnyCount() {
        for (int count = 1; count <= 5; count++) {
            assertEquals(0, SkillRotation.START.peek(count), "count " + count);
        }
    }
}
