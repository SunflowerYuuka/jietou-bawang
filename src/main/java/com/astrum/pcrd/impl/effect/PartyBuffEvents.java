package com.astrum.pcrd.impl.effect;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * The two non-edit moments at which the party effects have to be restored: joining a world and
 * respawning.
 *
 * <h2>Why these two and no others</h2>
 * The three ways a player's effect set can disagree with the party attachment are: the party changed
 * (covered by {@code PartyService.edit} calling {@link PartyBuffs#apply}), the player joined a world
 * (saved party, unsaved effects), and the player died (death removes effects, the party survives).
 * Dimension change moves the same player entity and keeps its effects, so it needs no hook; a
 * disconnect removes the entity, and the next login is the hook.
 *
 * <p>Both listeners are plain event handlers — no {@code EntityTickEvent}, no entity walk, no polling.
 * The methods are static so the registration is one {@code NeoForge.EVENT_BUS.addListener} per event;
 * there is no per-player state in this class at all.
 */
@ApiStatus.Internal
public final class PartyBuffEvents {

    private PartyBuffEvents() {
    }

    /** Subscribes the two handlers on the game bus. Called once, from {@code Attachments.register}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(PartyBuffEvents::onLogin);
        NeoForge.EVENT_BUS.addListener(PartyBuffEvents::onRespawn);
    }

    /**
     * A player joined the world: the party attachment was just read from disk, the effects were not
     * saved with it, so reconcile now. Logged at debug volume by {@link PartyBuffs} itself (it logs
     * only actual changes).
     */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PartyBuffs.apply(player);
        }
    }

    /**
     * A player respawned: vanilla cleared every effect on death, and the party attachment was copied
     * onto the new player by {@code copyOnDeath}. Giving the effects back here is what keeps the HUD
     * bar correct after a death without touching the party.
     */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PartyBuffs.apply(player);
        }
    }
}