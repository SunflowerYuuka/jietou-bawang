package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * The data component types this mod adds to items — today exactly one.
 *
 * <h2>Why the character id is a component and not a subclass per character</h2>
 * "Which character does this card grant?" is per-<em>stack</em> data, not per-<em>item</em> behaviour.
 * A subclass per character would need a new item, a new model and a new registry entry for every unit
 * in the pack; a component lets one item, one recipe shape and one model serve the whole roster, and it
 * is the mechanism 1.21 recipes already use to hand out a specific stack
 * ({@code "components": {"astrum:character_id": "astrum:djeeta"}}).
 *
 * <h2>Both codecs are mandatory, for different reasons</h2>
 * <ul>
 *   <li>{@code persistent} — without it the component is dropped on save and a card in a chest comes
 *       back as a card that grants nothing. It also makes the component usable inside a recipe's
 *       {@code result}, because {@code DataComponentPatch.CODEC} only accepts persistent types.</li>
 *   <li>{@code networkSynchronized} — <b>not</b> optional, and the failure is not a missing icon: the
 *       recipe carrying this component is sent to every joining client
 *       ({@code ItemStack.STREAM_CODEC} → {@code DataComponentPatch.STREAM_CODEC}), which calls
 *       {@code DataComponentType.streamCodec()} and throws when the type has none. A component that is
 *       persistent but not synchronized therefore crashes the connection of every player who joins a
 *       world that has the recipe.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class AstrumDataComponents {

    /**
     * Item data component types, registered on the mod bus.
     *
     * <p>{@code DeferredRegister.createDataComponents} rather than
     * {@code DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MOD_ID)}: the {@code Items},
     * {@code Blocks} and {@code DataComponents} subclasses exist so the concrete type is kept (the
     * generic {@code DeferredRegister<DataComponentType<?>>} would not expose
     * {@code registerComponentType}).
     */
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Astrum.MOD_ID);

    /**
     * The character a card grants, e.g. {@code astrum:djeeta}.
     *
     * <p>An absent component is a card that grants nothing: {@link CharacterCardItem} refuses it with a
     * message instead of guessing, so a hand-written or datapack copy of the item cannot enrol a
     * character nobody named.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ResourceLocation>> CHARACTER_ID =
            DATA_COMPONENTS.registerComponentType("character_id", builder -> builder
                    .persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC));

    private AstrumDataComponents() {
    }

    /**
     * Subscribes these registrations to the mod event bus.
     *
     * <p>{@code Registries.DATA_COMPONENT_TYPE} is a vanilla registry, but the {@code DeferredRegister}
     * still has to be attached to the mod bus — that is where the register event it listens for is
     * fired.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        DATA_COMPONENTS.register(modBus);
    }
}
