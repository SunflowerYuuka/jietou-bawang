package com.astrum.pcrd.api.v1.event;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.ApiStatus;

/**
 * Posted on {@code NeoForge.EVENT_BUS} after a UB cast has been accepted and paid for.
 *
 * <p>Posted on the logical server only: the gauge, the cooldown and the damage are all server state.
 * Unlike {@link UbChargeEvent} this one is not cancellable — by the time it fires the charge has been
 * spent and the cooldown set, so cancelling would leave the player paid-up but without an ultimate.
 * A listener that wants to veto a cast must do it before {@code UbService.tryCast} accepts it.
 *
 * @param characterId the character whose ultimate fired
 * @param slot        the wheel slot it came from, {@code [0, 5)}
 * @param enhanced    whether the tier-3 (enhanced) form fired
 */
@ApiStatus.Experimental
public class UbActivatedEvent extends Event {

    private final ResourceLocation characterId;
    private final int slot;
    private final boolean enhanced;

    /**
     * @param characterId the character whose ultimate fired
     * @param slot        the wheel slot it came from
     * @param enhanced    whether the enhanced form fired
     */
    public UbActivatedEvent(ResourceLocation characterId, int slot, boolean enhanced) {
        this.characterId = characterId;
        this.slot = slot;
        this.enhanced = enhanced;
    }

    /** {@return the character whose ultimate fired} */
    public ResourceLocation characterId() {
        return characterId;
    }

    /** {@return the wheel slot it came from} */
    public int slot() {
        return slot;
    }

    /** {@return whether the enhanced (tier-3) form fired} */
    public boolean enhanced() {
        return enhanced;
    }
}
