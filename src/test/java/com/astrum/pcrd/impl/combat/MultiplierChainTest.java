package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one multiplier chain: {@code diminishing × level} (spec §5.2, §6.1).
 *
 * <h2>What this file used to test, and why it is shorter now</h2>
 * It pinned a <b>three</b>-factor chain, the third being {@code affectionHook} — the affection tier-2
 * modifier that scaled a hook by an index. That factor is <b>gone</b>, and its removal is what the affection
 * rework consists of: a tier no longer multiplies a skill, it <b>replaces</b> it
 * ({@code AffectionKernel.hooksAt}). Two assertions went with it:
 *
 * <ul>
 *   <li>"the tier-2 modifier is the one factor allowed above 1" — there is no longer a per-hook factor to
 *       allow anything;</li>
 *   <li>"a broken affection factor falls back to the party multiplier" — there is no affection factor to be
 *       broken. Affection still reaches combat (through which hooks run and which ultimate is cast), but
 *       neither of those is arithmetic, so neither can be a hostile number.</li>
 * </ul>
 *
 * <p>Everything that remains is the same rule it always was, and it is worth keeping: a broken <em>growth</em>
 * factor degrades to the party multiplier instead of switching a character's hooks off. That is the difference
 * between "your save is odd, nothing happens" and "your character silently does nothing".
 */
class MultiplierChainTest {

    @Test
    void theTwoFactorsMultiply() {
        assertEquals(1.05, MultiplierChain.compose(0.7, 1.5), 1e-9);
        assertEquals(0.735, MultiplierChain.compose(0.7, 1.05), 1e-9,
                "a level-3 character on the second same-dimension slot");
    }

    /**
     * The level factor is the one factor allowed <b>above</b> 1: growth is a buff, and clamping it at 1.0
     * would make levelling buy nothing.
     */
    @Test
    void aLevelFactorIsNotClamped() {
        assertEquals(1.5, MultiplierChain.compose(1.0, 1.5), 1e-9);
        assertTrue(MultiplierChain.compose(1.0, 1.58) > 1.5);
    }

    /** A neutral growth changes nothing at all: this is the shipped level-1 tier-1 character. */
    @Test
    void aNeutralGrowthLeavesThePartyMultiplierAlone() {
        assertEquals(0.7, MultiplierChain.effective(0.7, GrowthMultiplier.NEUTRAL), 1e-9);
        assertEquals(1.0, MultiplierChain.effective(1.0, null), 1e-9);
    }

    /**
     * Hostile data must not switch a character off: a {@code NaN} or infinite factor is dropped and the
     * diminishing multiplier alone is used. The alternative — returning the product — would make every
     * hook of that character queue {@code NaN} damage, and {@code EffectQuantities.scaled} would answer
     * {@code 0} for all of them, i.e. a broken save would silently mute a character.
     */
    @Test
    void aBrokenGrowthFactorFallsBackToThePartyMultiplier() {
        assertEquals(0.7, MultiplierChain.compose(0.7, Double.NaN), 1e-9);
        assertEquals(0.7, MultiplierChain.compose(0.7, Double.POSITIVE_INFINITY), 1e-9);
        assertEquals(0.7, MultiplierChain.compose(0.7, -1.0), 1e-9,
                "a negative growth factor would invert every quantity, so it is refused");
        assertEquals(0.7, MultiplierChain.compose(0.7, 0.0), 1e-9,
                "0.0 is the one value that would switch the hooks off");
    }

    /** A broken <em>diminishing</em> factor is not rescued: that is the party table's number. */
    @Test
    void aBrokenDiminishingFactorAnswersZero() {
        assertEquals(0.0, MultiplierChain.compose(Double.NaN, 1.5), 1e-9);
        assertEquals(0.0, MultiplierChain.compose(-1.0, 1.5), 1e-9);
    }

    /**
     * An overflow is refused for the same reason a {@code NaN} is: {@code Infinity} would make the
     * executor's own filter answer {@code 0} and mute the hook.
     */
    @Test
    void anOverflowIsRefusedRatherThanPassedOn() {
        // A finite product is fine even at the top of the double range: this is not an overflow.
        assertEquals(0.5 * Double.MAX_VALUE, MultiplierChain.compose(0.5, Double.MAX_VALUE), 1e-9);
        // An actual overflow is Infinity, and that is refused: Infinity downstream would make the
        // executor's own filter answer 0 and mute the hook.
        assertEquals(0.5, MultiplierChain.compose(0.5, Double.MAX_VALUE * 4.0), 1e-9);
    }

    /**
     * <b>The signature has no hook index any more.</b>
     *
     * <p>Asserted reflectively because it is a <em>design</em> fact, not a numeric one: the parameter existed
     * only for the per-hook affection modifier, and a signature that keeps an unread parameter is exactly what
     * a later author "restores" a multiplier into. See {@code MultiplierChain}'s javadoc.
     */
    @Test
    void theChainTakesNoHookIndex() throws Exception {
        var effective = MultiplierChain.class.getMethod("effective", double.class, GrowthMultiplier.class);
        assertEquals(2, effective.getParameterCount(),
                "the chain must take (diminishing, growth) — a hook index has nothing left to select");
    }
}
