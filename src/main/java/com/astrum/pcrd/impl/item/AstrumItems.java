package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration for the mod's items — today exactly one, the character card.
 *
 * <h2>Why the registration lives here and not inside {@code CharacterCardItem}</h2>
 * The item class is behaviour (what a right-click does) and this class is wiring (what exists and under
 * which id). Keeping them apart means the item has no static registration state of its own, so a unit
 * test can look at the enrol rules without touching a registry, and {@code Astrum} keeps one call per
 * subsystem instead of one per register.
 *
 * <h2>Why the data components are registered from here too</h2>
 * A component type with no item carrying it does not exist yet, and an item whose component type is
 * not registered cannot be decoded from a recipe — the two are one unit of content. Registering them
 * in one call is also what keeps the order explicit: component types first, then the items whose
 * default patches and recipe results name them.
 */
@ApiStatus.Internal
public final class AstrumItems {

    /** Item registrations, on the mod bus. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Astrum.MOD_ID);

    /**
     * The character card: right-click to enrol the character named by its {@code astrum:character_id}
     * component.
     *
     * <p>Stackable to 16, because a player who has earned several characters should not need one
     * inventory slot per ticket.
     */
    public static final DeferredItem<CharacterCardItem> CHARACTER_CARD =
            ITEMS.registerItem("character_card", CharacterCardItem::new,
                    new Item.Properties().stacksTo(16));

    private AstrumItems() {
    }

    /**
     * Subscribes the item content to the mod bus.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        AstrumDataComponents.register(modBus);
        ITEMS.register(modBus);
    }
}
