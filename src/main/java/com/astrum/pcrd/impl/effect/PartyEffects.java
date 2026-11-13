package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.attach.Party;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration of the five party-slot effects ({@code astrum:party_1} ... {@code astrum:party_5}).
 *
 * <h2>Registration, not mechanics</h2>
 * The {@code MobEffect}s are inert markers; the server-side lifecycle lives in
 * {@link PartyBuffs} and the client-side icon/name in {@code impl.client.PartyEffectClient}. This
 * class is only the wiring, so the class that knows the ids ({@link PartyEffectSlots}) stays free of
 * the registry and is testable without a game.
 *
 * <h2>Why five separate registrations</h2>
 * One effect type for all slots would give one merged HUD entry — see the long note in
 * {@link PartyEffectSlots}. Five entries in the registry are the whole fix, and they must be five
 * <em>types</em>: the entity's effect map is keyed by {@code Holder<MobEffect>}, so two instances only
 * stay apart when their holders differ, which is true exactly when their registry ids differ.
 */
@ApiStatus.Internal
public final class PartyEffects {

    /** The effect registrations, on the mod bus. */
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, Astrum.MOD_ID);

    /** Slot 1's effect. Named individually rather than held in a list so no static container exists. */
    public static final DeferredHolder<MobEffect, MobEffect> PARTY_1 = effect(0);
    public static final DeferredHolder<MobEffect, MobEffect> PARTY_2 = effect(1);
    public static final DeferredHolder<MobEffect, MobEffect> PARTY_3 = effect(2);
    public static final DeferredHolder<MobEffect, MobEffect> PARTY_4 = effect(3);
    public static final DeferredHolder<MobEffect, MobEffect> PARTY_5 = effect(4);

    private PartyEffects() {
    }

    private static DeferredHolder<MobEffect, MobEffect> effect(int slot) {
        return MOB_EFFECTS.register(PartyEffectSlots.idFor(slot).getPath(),
                () -> new AstrumPartyEffect(slot));
    }

    /**
     * {@return every slot's holder, in slot order}
     *
     * <p>A method, not a field: it is a fresh immutable list each call, so there is no static mutable
     * container to leak a stale registry handle between worlds.
     */
    public static List<DeferredHolder<MobEffect, MobEffect>> holders() {
        return List.of(PARTY_1, PARTY_2, PARTY_3, PARTY_4, PARTY_5);
    }

    /**
     * {@return the holder for {@code slot}}
     *
     * @param slot 0-based party slot
     * @throws IllegalArgumentException when the slot is outside {@link Party#SLOTS}
     */
    public static DeferredHolder<MobEffect, MobEffect> holderFor(int slot) {
        if (slot < 0 || slot >= Party.SLOTS) {
            throw new IllegalArgumentException("party slot out of range: " + slot);
        }
        return holders().get(slot);
    }

    /**
     * {@return the registered effect for {@code slot}}
     *
     * @param slot 0-based party slot
     */
    public static MobEffect typeFor(int slot) {
        return holderFor(slot).get();
    }

    /**
     * {@return the slot of a registered effect, empty for an effect this mod did not register}
     *
     * <p>Identity, not id parsing: the five objects are constructed once by the registry, so a
     * reference comparison is exact and needs no registry lookup (which is what lets it be called on
     * the client for a hovered effect without touching {@code BuiltInRegistries}).
     */
    public static OptionalInt slotOf(MobEffect effect) {
        List<DeferredHolder<MobEffect, MobEffect>> holders = holders();
        for (int slot = 0; slot < holders.size(); slot++) {
            if (holders.get(slot).value() == effect) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /** Registers the five effects on the mod bus. Called from {@code Attachments.register}. */
    public static void register(IEventBus modBus) {
        MOB_EFFECTS.register(modBus);
    }
}