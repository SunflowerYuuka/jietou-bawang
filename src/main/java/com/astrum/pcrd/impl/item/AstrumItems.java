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
 * Registration for the mod's items — one 角色卡, three 角色册 and the 料理.
 *
 * <h2>Why the registration lives here and not inside the item classes</h2>
 * The item class is behaviour (what a right-click does) and this class is wiring (what exists and under
 * which id). Keeping them apart means the item has no static registration state of its own, so a unit
 * test can look at the enrol rules without touching a registry, and {@code Astrum} keeps one call per
 * subsystem instead of one per register.
 *
 * <h2>Three books and <em>one</em> card</h2>
 * A book's theme comes from its <b>item id</b> ({@link BookTheme#ofItemId}); {@code BookTheme}'s javadoc
 * gives the whole reasoning and the cost. The ids follow the theme's {@link BookTheme#itemSuffix()}, so
 * {@code astrum:character_book} keeps the id it already has in players' worlds and the two new books are
 * {@code astrum:character_book_sakura} / {@code _forest}.
 *
 * <p>The card has no theme and no variants. It is a ticket naming one character in its
 * {@code astrum:character_id} component, and the roster it writes into is the player's, shared by all
 * three books — so a sakura card would have been a second item with the same behaviour and a different
 * picture, and the human's rule is one card per character (「一个角色卡右键使用后应该是放进三个角色册」).
 * {@code CHARACTER_CARD_SAKURA} / {@code CHARACTER_CARD_FOREST} and their recipes, models, textures and
 * lang keys are gone.
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
     *
     * <p><b>One card for every character and every book.</b> There is no {@code _sakura} or {@code _forest}
     * variant: the card carries the character id, not a theme, and the roster it writes is the one all
     * three books read. Its texture is a blank ticket for the same reason — a face on the item would
     * claim the item <em>is</em> that character (see {@code tools/gen_character_card_texture.py}).
     */
    public static final DeferredItem<CharacterCardItem> CHARACTER_CARD =
            ITEMS.registerItem(BookTheme.CARD_PATH, CharacterCardItem::new,
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
     * {@return every themed book id to its mod-relative path, for {@code AstrumItemsTest}}
     *
     * <p>Not used by the game: it exists so the test can check that a model and a lang key exist for every
     * id this build registers, without reflecting over a registry that is not up in a unit test. Three
     * entries, derived from the enum, so a fourth theme is covered by the same test without editing it.
     *
     * <p>Books only. The card is not themed any more — see the class javadoc — so it is not in this map;
     * {@code AstrumItemsTest} asserts its model and name separately, which is the point at which "the card
     * is a single item" would break if somebody re-added a variant.
     */
    public static Map<String, String> themedItemIds() {
        // LinkedHashMap for a stable iteration order, then an immutable copy: the test prints these in
        // failure messages, and a HashMap would give a different order per JVM. Rebuilt on demand rather
        // than cached in a static: six strings are cheaper than a static container the guard has to warn
        // about.
        Map<String, String> ids = new LinkedHashMap<>();
        for (BookTheme theme : BookTheme.values()) {
            ids.put(theme.bookItemId().toString(), theme.bookPath());
        }
        return Map.copyOf(ids);
    }

    /**
     * {@return the one character card's mod-relative path}
     *
     * <p>A method rather than a bare constant, so a test can state "there is exactly one card id and it is
     * this" without writing the string a second time.
     */
    public static String cardPath() {
        return BookTheme.CARD_PATH;
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
