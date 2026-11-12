package com.astrum.pcrd.impl.growth;

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
 * <h2>What is deferred to the wiring task</h2>
 * The two shipped language files do not carry the {@code astrum.growth.*} rows yet, and
 * {@code LangParityTest} does not read {@link GrowthText#LANG_KEYS} yet — both are part of printing
 * these messages, which is wiring. Until then this test pins the names, so the task that adds the
 * translations and the parity entry has one list to satisfy.
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
        assertEquals(java.util.Set.of(
                        GrowthText.KEY_FED,
                        GrowthText.KEY_ALREADY_TODAY,
                        GrowthText.KEY_ALREADY_FULL,
                        GrowthText.KEY_NOBODY,
                        GrowthText.KEY_BOOK_UNREADABLE),
                GrowthText.LANG_KEYS,
                "a constant added without a LANG_KEYS entry would never reach the parity test");
    }

    /**
     * One key per player-visible outcome. If a later task adds an outcome to {@link DishFeed.Outcome}
     * and forgets its sentence, this count disagrees.
     */
    @Test
    void everyOutcomeAPlayerCanSeeHasAKey() {
        assertEquals(DishFeed.Outcome.values().length - 1, GrowthText.LANG_KEYS.size(),
                "every outcome but NOT_A_DISH (filtered by the handler) needs its own sentence");
        assertTrue(GrowthText.LANG_KEYS.contains(GrowthText.KEY_FED));
        assertTrue(GrowthText.LANG_KEYS.contains(GrowthText.KEY_ALREADY_TODAY));
        assertTrue(GrowthText.LANG_KEYS.contains(GrowthText.KEY_ALREADY_FULL));
        assertTrue(GrowthText.LANG_KEYS.contains(GrowthText.KEY_NOBODY));
        assertTrue(GrowthText.LANG_KEYS.contains(GrowthText.KEY_BOOK_UNREADABLE));
    }
}
