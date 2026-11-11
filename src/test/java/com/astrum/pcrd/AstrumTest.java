package com.astrum.pcrd;

import com.astrum.pcrd.impl.InternalIds;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AstrumTest {
    @Test
    void modIdIsAstrum() {
        assertEquals("astrum", Astrum.MOD_ID);
    }

    @Test
    void internalIdsUseAstrumNamespace() {
        assertEquals("astrum:djeeta", InternalIds.id("djeeta").toString());
    }
}
