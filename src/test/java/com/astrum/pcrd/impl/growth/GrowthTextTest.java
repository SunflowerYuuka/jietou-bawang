package com.astrum.pcrd.impl.growth;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words the growth system speaks with: one translation key per outcome a player can see.
 *
 * <h2>Why this is a test and not just constants</h2>
 * {@link DishFeed.Outcome} has six values, and spec §5.5 forbids a silent failure: "the dish did
 * nothing" with no sentence is exactly that. So the invariants worth pinning are that every outcome a
 * player can encounter has a key, and that the key set holds no key whose constant was renamed away —
 * a stale key is a message nobody sends and a message with no key is a player reading
 * {@code astrum.growth.…} raw.
 *
 * <p>{@code NOT_A_DISH} is the one outcome without a key, and deliberately so: the right-click handler
 * filters on {@link Dishes#isDish} before it calls {@link DishFeed#feed}, so that branch is a belt to
 * the handler's braces and never reaches a player.
 *
 * <h2>Two families of keys, counted separately</h2>
 * The dish outcomes are one family (one sentence each, five of them). The <b>level-up line</b>
 * ({@link GrowthText#KEY_LEVEL_UP}) is the other: it is not a {@code DishFeed} outcome at all, it is
 * what a kill says when {@code ExperienceKernel.add} crosses a level, so counting it as an outcome
 * would make this test assert the wrong thing. Both families are pinned; the counts are pinned
 * individually so a key added to one cannot hide behind the other.
 *
 * <p>The two shipped language files now carry all six keys and {@code LangParityTest} reads this set,
 * which is what makes a missing translation fail a test instead of showing a raw key to a player.
 */
class GrowthTextTest {

    @Test
    void everyKeyLivesInTheGrowthNamespace() {
        for (String key : GrowthText.LANG_KEYS) {
            assertTrue(key.startsWith("astrum.growth."), "not in the growth namespace: " + key);
            assertTrue(key.length() > "astrum.growth.".length(), "a bare prefix is not a key: " + key);
            assertEquals(key.toLowerCase(java.util.Locale.ROOT), key,
                    "translation keys are lower case: " + key);
        }
    }

    @Test
    void theSetIsExactlyTheConstantsItHolds() {
        assertEquals(Set.of(
                        GrowthText.KEY_FED,
                        GrowthText.KEY_ALREADY_TODAY,
                        GrowthText.KEY_ALREADY_FULL,
                        GrowthText.KEY_NOBODY,
                        GrowthText.KEY_BOOK_UNREADABLE,
                        GrowthText.KEY_LEVEL_UP),
                GrowthText.LANG_KEYS,
                "a constant added without a LANG_KEYS entry would never reach the parity test");
    }

    /**
     * One key per player-visible dish outcome. If a later task adds an outcome to
     * {@link DishFeed.Outcome} and forgets its sentence, this count disagrees.
     */
    @Test
    void everyDishOutcomeAPlayerCanSeeHasAKey() {
        Set<String> dishKeys = Set.of(
                GrowthText.KEY_FED, GrowthText.KEY_ALREADY_TODAY, GrowthText.KEY_ALREADY_FULL,
                GrowthText.KEY_NOBODY, GrowthText.KEY_BOOK_UNREADABLE);
        assertEquals(DishFeed.Outcome.values().length - 1, dishKeys.size(),
                "every outcome but NOT_A_DISH (filtered by the handler) needs its own sentence");
        assertTrue(GrowthText.LANG_KEYS.containsAll(dishKeys),
                "the dish sentences must stay in the set the parity test reads");
    }

    /**
     * The level-up line is its own family: exactly one key, because a level-up is one event with one
     * sentence (the character's name and the new level are its only arguments).
     */
    @Test
    void theLevelUpLineHasExactlyOneKey() {
        assertTrue(GrowthText.LANG_KEYS.contains(GrowthText.KEY_LEVEL_UP));
        assertEquals(GrowthText.LANG_KEYS.size() - (DishFeed.Outcome.values().length - 1), 1,
                "the only growth message that is not a dish outcome is the level-up line");
    }
}
