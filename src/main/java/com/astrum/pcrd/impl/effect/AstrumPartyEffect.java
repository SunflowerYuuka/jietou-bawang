package com.astrum.pcrd.impl.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import org.jetbrains.annotations.ApiStatus;

/**
 * One party slot's HUD effect: a marker, not a mechanic.
 *
 * <p>It does nothing to the player — no attribute modifier, no tick behaviour
 * ({@code shouldApplyEffectTickThisTick} is already {@code false} in {@link MobEffect}, and this class
 * does not enable it) — so the only thing it exists for is to appear as an entry in vanilla's effect
 * HUD and in the inventory's effect list. Its category is {@link MobEffectCategory#BENEFICIAL}, so
 * vanilla draws the blue "beneficial" background on the right-hand side of the HUD, where a player
 * expects buffs, and so the entries never share the harmful column with poison.
 *
 * <p>The icon and the name are the character's, resolved on the client from the party sync
 * ({@code PartyEffectClient} / {@code PartyBuffName}); the type itself only knows which slot it
 * stands for. That is why five instances of this class exist: one per slot, {@link #slot()} is the
 * identity, and two slots holding the same character still render two entries.
 */
@ApiStatus.Internal
public final class AstrumPartyEffect extends MobEffect {

    private final int slot;

    /**
     * @param slot 0-based party slot, {@code 0 <= slot < Party.SLOTS}
     * @throws IllegalArgumentException when the slot is outside the party
     */
    public AstrumPartyEffect(int slot) {
        super(MobEffectCategory.BENEFICIAL, colourFor(slot));
        this.slot = slot;
    }

    /** {@return the 0-based party slot this effect stands for} */
    public int slot() {
        return slot;
    }

    /**
     * {@return the slot's particle colour}
     *
     * <p>A separate method so the range check runs <em>before</em> {@code super(...)} indexes the
     * colour table: an out-of-range slot must fail with a sentence, not an array exception inside the
     * superclass call.
     */
    private static int colourFor(int slot) {
        if (slot < 0 || slot >= PartyEffectSlots.SLOT_COLORS.length) {
            throw new IllegalArgumentException("party slot out of range: " + slot);
        }
        return PartyEffectSlots.SLOT_COLORS[slot];
    }
}