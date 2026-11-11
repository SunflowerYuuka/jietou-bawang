package com.astrum.pcrd;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the Astrum mod.
 */
@Mod(Astrum.MOD_ID)
public final class Astrum {
    public static final String MOD_ID = "astrum";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public Astrum(IEventBus modBus, ModContainer container) {
        LOGGER.info("astrum loaded");
    }
}
