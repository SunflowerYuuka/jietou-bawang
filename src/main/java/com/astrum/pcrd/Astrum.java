package com.astrum.pcrd;

import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.client.ClientConfigScreen;
import com.astrum.pcrd.impl.client.ClientEvents;
import com.astrum.pcrd.impl.combat.CombatEvents;
import com.astrum.pcrd.impl.command.AstrumCommand;
import com.astrum.pcrd.impl.config.ClientConfig;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.growth.GrowthEvents;
import com.astrum.pcrd.impl.item.AstrumItems;
import com.astrum.pcrd.impl.network.AstrumNetwork;
import com.astrum.pcrd.impl.registry.CharacterRegistry;
import com.astrum.pcrd.impl.showcase.ShowcaseTypes;
import com.astrum.pcrd.impl.ub.UbParticles;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the Astrum mod. Wiring only: every feature lives in {@code impl} (plan R1). */@Mod(Astrum.MOD_ID)
public final class Astrum {
    public static final String MOD_ID = "astrum";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public Astrum(IEventBus modBus, ModContainer container) {
        LOGGER.info("astrum loaded");
        ServerConfig.register(container);
        ClientConfig.register(container);
        CharacterRegistry.register(modBus);
        Attachments.register(modBus);
        AstrumItems.register(modBus);
        AstrumNetwork.register(modBus);
        ShowcaseTypes.register(modBus);
        UbParticles.register(modBus);
        CombatEvents.register();
        GrowthEvents.register();
        AstrumCommand.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {   // client classes stay off a dedicated server (§7)
            ClientEvents.register(modBus);
            ClientConfigScreen.register(container);
        }
    }
}
