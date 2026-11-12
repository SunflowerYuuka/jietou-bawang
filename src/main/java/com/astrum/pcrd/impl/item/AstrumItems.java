package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration for the mod's items — the 角色册 and 角色卡 in three themes each, and the 料理.
 *
 * <h2>Why the registration lives here and not inside the item classes</h2>
 * The item class is behaviour (what a right-click does) and this class is wiring (what exists and under
 * which id). Keeping them apart means the item has no static registration state of its own, so a unit
 * test can look at the enrol rules without touching a registry, and {@code Astrum} keeps one call per
 * subsystem instead of one per register.
 *
 * <h2>Six books and cards, one class each, three ids each</h2>
 * The theme comes from the <b>item id</b> ({@link BookTheme#ofItemId}); {@code BookTheme}'s javadoc gives
 * the whole reasoning and the cost. The ids follow the theme's {@link BookTheme#itemSuffix()}, so
 * {@code astrum:character_book} keeps the id it already has in players' worlds and the two new books are
 * {@code astrum:character_book_sakura} / {@code _forest}.
 *
 * <p>Every themed item is the same class: the behaviour does not vary by theme, only the id it was
 * registered under — which is what {@code BookTheme.ofItemId} reads back.
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
            ITEMS.registerItem(BookTheme.DEFAULT.cardPath(), CharacterCardItem::new,
                    new Item.Properties().stacksTo(16));

    /** The sakura card: same behaviour, pink icon and a theme line in the tooltip. */
    public static final DeferredItem<CharacterCardItem> CHARACTER_CARD_SAKURA =
            ITEMS.registerItem(BookTheme.SAKURA.cardPath(), CharacterCardItem::new,
                    new Item.Properties().stacksTo(16));

    /** The forest card: same behaviour, green icon and a theme line in the tooltip. */
    public static final DeferredItem<CharacterCardItem> CHARACTER_CARD_FOREST =
            ITEMS.registerItem(BookTheme.FOREST.cardPath(), CharacterCardItem::new,
                    new Item.Properties().stacksTo(16));

    /**
     * The 角色册 (character book): right-click to open the roster / party / growth screen.
     *
     * <p>Stacks to <b>one</b>, unlike the card: it is a personal possession rather than a ticket, so a
     * second copy would only be an inventory slot spent on the same screen. It is also not consumed, so
     * one book is all a player ever needs.
     */
    public static final DeferredItem<CharacterBookItem> CHARACTER_BOOK =
            ITEMS.registerItem(BookTheme.DEFAULT.bookPath(), CharacterBookItem::new,
                    new Item.Properties().stacksTo(1));

    /** The sakura book: same screen, pink panel art and divider. */
    public static final DeferredItem<CharacterBookItem> CHARACTER_BOOK_SAKURA =
            ITEMS.registerItem(BookTheme.SAKURA.bookPath(), CharacterBookItem::new,
                    new Item.Properties().stacksTo(1));

    /** The forest book: same screen, green panel art and divider. */
    public static final DeferredItem<CharacterBookItem> CHARACTER_BOOK_FOREST =
            ITEMS.registerItem(BookTheme.FOREST.bookPath(), CharacterBookItem::new,
                    new Item.Properties().stacksTo(1));

    /** Hunger points one rice bowl restores: 6, the same as a bowl of mushroom stew. */
    public static final int RICE_BOWL_NUTRITION = 6;

    /** Saturation modifier: 6 nutrition at 0.6 gives 7.2 saturation — filling, not a golden meal. */
    public static final float RICE_BOWL_SATURATION = 0.6F;

    /**
     * The 料理 a player feeds a character: a rice bowl (spec §6.1 送料理, now a dedicated dish rather
     * than a vanilla ingredient — see {@code Dishes}).
     *
     * <p><b>Edible, and always edible.</b> Nutrition 6 at saturation 0.6, so it is a real meal rather
     * than a token, and {@code alwaysEdible()} so it can also be eaten at full hunger. That last part is
     * not a convenience: the feeding click is a right-click on a <em>block</em> (see {@code DishClick}
     * for why it is not {@code Item.use}), and a dish that could only be eaten while hungry would leave a
     * player who has just eaten unable to eat the dish they craft — which reads as a broken food item
     * even though the feeding path works.
     *
     * <p>Nothing is left behind when it is eaten. Vanilla's mushroom stew returns a bowl, but that is
     * only because a bowl item exists; inventing one here would be a second item, a second model and a
     * second texture for no gameplay.
     */
    public static final DeferredItem<Item> RICE_BOWL =
            ITEMS.registerItem("rice_bowl", Item::new,
                    new Item.Properties().stacksTo(16).food(new FoodProperties.Builder()
                            .nutrition(RICE_BOWL_NUTRITION)
                            .saturationModifier(RICE_BOWL_SATURATION)
                            .alwaysEdible()
                            .build()));

    private AstrumItems() {
    }

    /**
     * {@return every themed item id to its mod-relative path, for {@code AstrumItemsTest}}
     *
     * <p>Not used by the game: it exists so the test can check that a model and a lang key exist for every
     * id this build registers, without reflecting over a registry that is not up in a unit test. Six
     * entries, derived from the enum, so a fourth theme is covered by the same test without editing it.
     */
    public static Map<String, String> themedItemIds() {
        // LinkedHashMap for a stable iteration order, then an immutable copy: the test prints these in
        // failure messages, and a HashMap would give a different order per JVM. Rebuilt on demand rather
        // than cached in a static: six strings are cheaper than a static container the guard has to warn
        // about.
        Map<String, String> ids = new LinkedHashMap<>();
        for (BookTheme theme : BookTheme.values()) {
            ids.put(theme.bookItemId().toString(), theme.bookPath());
            ids.put(theme.cardItemId().toString(), theme.cardPath());
        }
        return Map.copyOf(ids);
    }

    /**
     * Subscribes the item content to the mod bus.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        AstrumDataComponents.register(modBus);
        ITEMS.register(modBus);
        // The creative tab is item content that lists these items, and its population is derived from
        // ITEMS above, so it is wired here rather than from Astrum: one call per subsystem keeps the
        // entry point wiring-only (44 lines) instead of one call per register.
        AstrumCreativeTab.register(modBus);
    }
}
