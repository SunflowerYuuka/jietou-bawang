package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.effect.TieredAmounts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>Level grants effect tiers</b> — the four bands, the clamp, and the one place the two meet.
 *
 * <h2>What these tests guard</h2>
 * The bands are a rule with three readers (the hook executor, the ultimate's settlement, and anything that
 * later shows a resolved tier), and this project has shipped three bugs of the shape "one rule, two copies".
 * So the assertions here are deliberately split the same way the code is:
 *
 * <ol>
 *   <li>{@link LevelBands} answers the <b>bonus</b>, and every boundary is pinned (30/31, 60/61, 90/91, 100
 *       and above) — an off-by-one in a band is invisible in play until a player levels once and nothing
 *       happens.</li>
 *   <li>{@link TieredAmounts} performs the <b>composition</b> ({@code declared + bonus}, then the effect's own
 *       clamp) — asserted for a buff whose ladder has room, for one whose ladder does not, and for a band that
 *       takes a legal tier past the top.</li>
 *   <li>The two delegating doors ({@link GrowthFacts#bandBonus}, {@link AffectionKernel#bandBonus}) are the
 *       same function, so a caller cannot get a different answer by knocking on a different door.</li>
 * </ol>
 *
 * <p><b>What is not here:</b> the EX exemption. EX is not resolved through {@code TieredAmounts} at all — its
 * rung comes from {@code ExPassive.rungFor} and its tier is clamped without a band — so it is pinned in
 * {@code ExPassiveTest}, next to the code that makes the decision.
 */
class LevelBandsTest {

    // ------------------------------------------------------------------ the bands, at every boundary

    /**
     * <b>1–30 → +0, 31–60 → +1, 61–90 → +2, 91–100 → +3.</b>
     *
     * <p>Every boundary is asserted on <b>both</b> sides: {@code 30} and {@code 31}, {@code 60} and
     * {@code 61}, {@code 90} and {@code 91}. A test of only the first level of each band would pass on an
     * implementation that granted the band one level early.
     */
    @Test
    void theFourBandsStartAndEndExactlyWhereTheHumanSaid() {
        assertEquals(0, LevelBands.bonusAt(1), "level 1 is the whiteboard");
        assertEquals(0, LevelBands.bonusAt(30), "30 is the last level with no bonus");
        assertEquals(1, LevelBands.bonusAt(31), "31 is the first level with +1");
        assertEquals(1, LevelBands.bonusAt(60), "60 is the last level with +1");
        assertEquals(2, LevelBands.bonusAt(61), "61 is the first level with +2");
        assertEquals(2, LevelBands.bonusAt(90), "90 is the last level with +2");
        assertEquals(3, LevelBands.bonusAt(91), "91 is the first level with +3");
        assertEquals(3, LevelBands.bonusAt(100), "100 is the cap and the last level of the last band");
    }

    /** Above the cap the answer does not keep growing: there is no fourth band. */
    @Test
    void aLevelAboveTheCapIsTheTopBandAndNoMore() {
        assertEquals(LevelBands.BONUS_AT_TOP, LevelBands.bonusAt(101));
        assertEquals(LevelBands.BONUS_AT_TOP, LevelBands.bonusAt(400));
        assertEquals(LevelBands.BONUS_AT_TOP, LevelBands.bonusAt(Integer.MAX_VALUE));
    }

    /** Hostile levels read as the first band: a bonus can only add, so there is no "penalty band". */
    @Test
    void aHostileLevelIsTheFirstBand() {
        assertEquals(LevelBands.BONUS_AT_BOTTOM, LevelBands.bonusAt(0));
        assertEquals(LevelBands.BONUS_AT_BOTTOM, LevelBands.bonusAt(-7));
        assertEquals(LevelBands.BONUS_AT_BOTTOM, LevelBands.bonusAt(Integer.MIN_VALUE));
    }

    // ------------------------------------------------------------------ the composition: add, THEN clamp

    /**
     * <b>Add, then clamp.</b> The order is the whole rule: a level-95 character on a tier-1 buff reaches tier
     * 4, not tier 1 and not a refusal.
     */
    @Test
    void theBandIsAddedToTheDeclaredTier() {
        // BUFF_ATTACK_AREA has five rungs, so +3 from tier 1 lands inside the ladder.
        assertEquals(4, TieredAmounts.tierOf(EffectType.BUFF_ATTACK_AREA, TieredParams.ofTier(1),
                LevelBands.bonusAt(95)));
        assertEquals(1, TieredAmounts.tierOf(EffectType.BUFF_ATTACK_AREA, TieredParams.ofTier(1),
                LevelBands.bonusAt(25)), "level 25 grants nothing");
        assertEquals(2, TieredAmounts.tierOf(EffectType.BUFF_ATTACK_AREA, TieredParams.ofTier(1),
                LevelBands.bonusAt(45)));
        assertEquals(3, TieredAmounts.tierOf(EffectType.BUFF_ATTACK_AREA, TieredParams.ofTier(1),
                LevelBands.bonusAt(75)));
    }

    /**
     * <b>The clamp is the effect's, not the band's.</b> On a four-rung ladder a +3 band from tier 3 asks for
     * 6 and gets 4 — the top rung, not a sixth one and not a silent no-op.
     */
    @Test
    void aBandNeverInventsARungPastTheTopOfTheLadder() {
        assertEquals(4, EffectTiers.maxTier(EffectType.DEFENCE_DOWN), "the fixture assumes four rungs");
        assertEquals(4, TieredAmounts.tierOf(EffectType.DEFENCE_DOWN, TieredParams.ofTier(3),
                LevelBands.bonusAt(95)), "3 + 3 = 6 clamps to the ladder's top, 4");
        assertEquals(4, TieredAmounts.tierOf(EffectType.DEFENCE_DOWN, TieredParams.ofTier(4),
                LevelBands.bonusAt(95)), "4 + 3 = 7 clamps to 4 too");
    }

    /** A one-rung effect stays at one rung however high the level: the ladder has no room. */
    @Test
    void aOneRungEffectIgnoresTheBand() {
        assertEquals(1, TieredAmounts.tierOf(EffectType.PANIC, TieredParams.ofTier(1),
                LevelBands.bonusAt(100)));
        assertEquals(1, TieredAmounts.tierOf(EffectType.REMOVE_SHIELD, TieredParams.ofTier(1),
                LevelBands.bonusAt(100)));
    }

    /**
     * The <b>fraction</b> the effect is worth follows the clamped tier, not the ask.
     *
     * <p>That is the property that makes this visible in play: a level-95 character's {@code buff_attack_area}
     * declared at tier 1 must <em>heal/buff as much as tier 4</em>, i.e. 80%, not 20% and not 100%.
     */
    @Test
    void theAmountFollowsTheClampedTier() {
        TieredAmounts.Amount amount = TieredAmounts.resolve(EffectType.BUFF_ATTACK_AREA,
                TieredParams.ofTier(1), LevelBands.bonusAt(95));
        assertEquals(4, amount.tier());
        assertEquals(0.80, amount.fraction(), 1e-9, "tier 4 of a 20%-a-rung ladder is +80%");

        TieredAmounts.Amount capped = TieredAmounts.resolve(EffectType.DEFENCE_DOWN,
                TieredParams.ofTier(3), LevelBands.bonusAt(95));
        assertEquals(4, capped.tier());
        assertEquals(0.80, capped.fraction(), 1e-9, "four rungs of 20% is the ceiling, not 120%");
    }

    /** Zero band is the identity: the level-1 whiteboard must reproduce the old behaviour exactly. */
    @Test
    void aZeroBandChangesNothing() {
        for (EffectType type : EffectType.values()) {
            if (!EffectTiers.isTiered(type)) {
                continue;
            }
            assertEquals(TieredAmounts.tierOf(type, TieredParams.ofTier(1), 0),
                    TieredAmounts.tierOf(type, TieredParams.ofTier(1)), "level 1 is the old behaviour");
        }
    }

    // ------------------------------------------------------------------ one implementation, three doors

    /**
     * <b>The two front doors delegate to {@link LevelBands}.</b>
     *
     * <p>Asserted across every band boundary, so a copy of the table that drifted by one level in one of the
     * three places would fail here rather than in play.
     */
    @Test
    void bothGrowthDoorsAnswerTheSameTable() {
        for (int level = 1; level <= LevelBands.MAX_LEVEL; level++) {
            assertEquals(LevelBands.bonusAt(level), GrowthFacts.bandBonus(level),
                    "GrowthFacts must delegate at level " + level);
            assertEquals(LevelBands.bonusAt(level), AffectionKernel.bandBonus(level),
                    "AffectionKernel must delegate at level " + level);
        }
    }

    /** {@code tierFor} is the addition without the clamp — the one place a caller can see both halves. */
    @Test
    void tierForIsTheUnclampedAddition() {
        assertEquals(4, LevelBands.tierFor(1, 95));
        assertEquals(7, LevelBands.tierFor(4, 95), "no clamp here: 4 + 3 is 7, and the effect decides");
        assertEquals(1, LevelBands.tierFor(1, 30));
    }
}
