package com.astrum.pcrd.impl.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BalanceTest {
    @Test void firstSameDimensionGetsFullMultiplier() { assertEquals(1.0, Balance.diminishingMultiplier(0, 0.7, true)); }
    @Test void successiveRanksFollowBasePowers() {
        assertEquals(0.7, Balance.diminishingMultiplier(1, 0.7, true), 1e-9);
        assertEquals(0.49, Balance.diminishingMultiplier(2, 0.7, true), 1e-9);
    }
    @Test void disabledMeansOne() { assertEquals(1.0, Balance.diminishingMultiplier(3, 0.7, false)); }
    @Test void floorIsOneTenth() { assertEquals(0.1, Balance.diminishingMultiplier(40, 0.7, true), 1e-9); }
    @Test void hostileConfigValuesAreClamped() {
        assertEquals(0.1, Balance.clampBase(0.0));     // 填 0 不至于效果全废
        assertEquals(1.0, Balance.clampBase(2.0));     // 填 2.0 不至于爆炸
        assertEquals(0.1, Balance.clampBase(-3.0));
    }

    /**
     * NaN is the one hostile value {@code Math.clamp} cannot handle: every comparison with NaN is
     * false, so {@code Math.clamp(NaN, 0.1, 1.0)} returns NaN and the "clamped into [0.1, 1.0]"
     * promise fails silently — the multiplier would poison every downstream product instead of
     * defaulting. A TOML file can contain it ({@code diminishingBase = nan}). The kernel must fall
     * back to a playable value.
     */
    @Test void notANumberFallsBackToFloorNotNan() {
        assertEquals(0.1, Balance.clampBase(Double.NaN));
        assertEquals(0.1, Balance.diminishingMultiplier(2, Double.NaN, true), 1e-9);
    }
}
