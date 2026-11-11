package com.astrum.pcrd.api.v1.event;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * Posted on {@code NeoForge.EVENT_BUS} before a character's UB gauge gains (or loses) charge.
 *
 * <p>Listeners may cancel the event to drop the change, or replace {@link #amount()} to rescale it.
 * Posted on the logical server only, because only the server owns the authoritative gauge.
 */
@ApiStatus.Experimental
public class UbChargeEvent extends Event implements ICancellableEvent {

    private final ResourceLocation characterId;
    private double amount;

    /**
     * @param characterId the character whose gauge changes, e.g. {@code astrum:djeeta}
     * @param amount      charge points to add; negative values drain the gauge
     */
    public UbChargeEvent(ResourceLocation characterId, double amount) {
        this.characterId = characterId;
        this.amount = amount;
    }

    /**
     * {@return the character whose gauge changes}
     */
    public ResourceLocation characterId() {
        return characterId;
    }

    /**
     * {@return the charge points that will be applied if the event is not cancelled}
     */
    public double amount() {
        return amount;
    }

    /**
     * Replaces the charge delta, e.g. to apply a difficulty or config multiplier.
     *
     * @param amount the new delta
     */
    public void setAmount(double amount) {
        this.amount = amount;
    }
}
