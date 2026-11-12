package com.astrum.pcrd.impl.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Report this once" — the policy behind the one WARN a broken datapack hook gets.
 *
 * <p>A malformed hook is a property of the <b>loaded datapack</b>, not of a hit: it is wrong on every
 * hit for as long as the pack is loaded. Logging it per hit would bury the line that says what to fix
 * under thousands of copies of itself, and not logging it at all is the fail-open silence this fix
 * round exists to remove. So: once per key.
 */
class WarnOnceTest {

    @Test
    void theSameKeyIsReportedOnlyTheFirstTime() {
        WarnOnce once = new WarnOnce();
        assertTrue(once.first("astrum:djeeta#2"), "the first occurrence is worth a line");
        assertFalse(once.first("astrum:djeeta#2"), "the second is not");
        assertFalse(once.first("astrum:djeeta#2"));
    }

    @Test
    void differentKeysAreIndependent() {
        WarnOnce once = new WarnOnce();
        assertTrue(once.first("astrum:djeeta#2"));
        assertTrue(once.first("astrum:djeeta#3"), "another hook of the same character is another problem");
        assertTrue(once.first("astrum:other#2"), "another character is another problem");
        assertFalse(once.first("astrum:other#2"));
    }
}
