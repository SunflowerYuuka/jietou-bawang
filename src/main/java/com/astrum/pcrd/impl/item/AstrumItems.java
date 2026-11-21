package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * Registration for the mod's items — one 角色卡, one 角色册 and the 料理.
 *
 * <h2>Why the registration lives here and not inside the item classes</h2>
 * The item class is behaviour (what a right-click does) and this class is wiring (what exists and under
 * which id). Keeping them apart means the item has no static registration state of its own, so a unit
 * test can look at the enrol rules without touching a registry, and {@code Astrum} keeps one call per
 * subsystem instead of one per register.
 *
 * <h2>One book and <em>one</em> card</h2>
 * The book was three items for a while — a plain one plus two re-skins — and the human deleted the re-skins:
 * 「将2个皮肤版本的角色册道具删除，仅保留最开始的那个」. So {@code astrum:character_book_sakura} and
 * {@code astrum:character_book_forest} (items, recipes, models, textures, lang keys) and the
 * {@code BookTheme} enum behind them are gone, and the surviving id is the one players already own.
 *
 * <p>The card never had variants. It is a ticket naming one character in its {@code astrum:character_id}
 * component, and the roster it writes into is the player's — so a per-book card would have been a second item
 * with the same behaviour and a different picture, and the human's rule is one card per character
 * (「一个角色卡右键使用后应该是放进三个角色册」).
 *
 * <h2>The two content paths, in one place</h2>
 * {@link #BOOK_PATH} and {@link #CARD_PATH} are the mod-relative ids of the two things a player can hold that
 * are not food. They live here rather than on the deleted enum because the creative tab's family ranking and
 * the tests both key off them, and "what ids does this mod register" is this class's own subject.
 */
@ApiStatus.Internal
public final class AstrumItems {

    /** Item registrations, on the mod bus. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Astrum.MOD_ID);

    /** The single 角色册's mod-relative item path: {@code astrum:character_book}. */
    public static final String BOOK_PATH = "character_book";

    /** The single 角色卡's mod-relative item path: {@code astrum:character_card}. */
    public static final String CARD_PATH = "character_card";

    /**
     * The 记忆结晶's mod-relative item path: {@code astrum:memory_token}.
     *
     * <p>The currency a <b>duplicate</b> card turns into, and the item a player right-clicks to spend a
     * hundred of them on a card of a character they do not have yet. The human's loop is: a card is a
     * lottery, a card of a character you already have is not worthless, and a hundred of them buy the
     * character you actually want (「重复的角色卡可以销毁，或者参与合成得到一个新物品」, 「64个换一个自选角色卡」).
     * The token is that whole sentence — there is no voucher item in between any more.
     */
    public static final String TOKEN_PATH = "memory_token";

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
            ITEMS.registerItem(CARD_PATH, CharacterCardItem::new,
                    new Item.Properties().stacksTo(16));

    /**
     * The 角色册 (character book): right-click to open the roster / party / growth screen.
     *
     * <p>Stacks to <b>one</b>, unlike the card: it is a personal possession rather than a ticket, so a
     * second copy would only be an inventory slot spent on the same screen. It is also not consumed, so
     * one book is all a player ever needs.
     */
    public static final DeferredItem<CharacterBookItem> CHARACTER_BOOK =
            ITEMS.registerItem(BOOK_PATH, CharacterBookItem::new,
                    new Item.Properties().stacksTo(1));

    /**
     * The 记忆结晶: what a duplicate character card turns into, a hundred of which buy a character card.
     *
     * <p>Stacks to <b>{@value com.astrum.pcrd.impl.gacha.TokenExchange#TOKENS_PER_VOUCHER}</b>, and that is
     * the number the item needs rather than a coincidence: the exchange is one right-click on <b>one
     * stack</b> (the human: 「一组，又不是放在合成台，是背包里右键直接使用的东西」), so a stack size below the
     * requirement would make the interaction impossible and one above it would leave a fragment the check
     * never uses. The previous revision stacked to 64 and summed the whole inventory, which cost a stack of
     * 50 + 50 the ability to ever fire from one slot.
     *
     * <p>Its texture is <b>drawn</b>, not sourced ({@code tools/gen_token_texture.py}): there is no official
     * "duplicate card shard" in the art dump, and resampling something that only looks like one would be a lie
     * about provenance. The generator's docstring records that decision.
     */
    public static final DeferredItem<MemoryTokenItem> MEMORY_TOKEN =
            ITEMS.registerItem(TOKEN_PATH, MemoryTokenItem::new,
                    new Item.Properties()
                            .stacksTo(com.astrum.pcrd.impl.gacha.TokenExchange.TOKENS_PER_VOUCHER));

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
     * {@return every item id this build registers that is not the dish}, for {@code AstrumItemsTest}
     *
     * <p>Not used by the game: it exists so a test can check that a model and a lang key exist for every id
     * this build registers, without reflecting over a registry that is not up in a unit test. Three entries —
     * the card, the book and the token — derived from the three path constants, so renaming one is a change
     * in one place.
     *
     * <p>The dish is excluded on purpose and checked separately: it is content, not a 角色册-family item, and
     * conflating the two is what would let a future dish inherit a book's model by accident.
     */
    public static Map<String, String> themedItemIds() {
        // LinkedHashMap for a stable iteration order, then an immutable copy: the test prints these in
        // failure messages, and a HashMap would give a different order per JVM. Rebuilt on demand rather
        // than cached in a static: four strings are cheaper than a static container the guard has to warn
        // about.
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put(InternalIds.id(CARD_PATH).toString(), CARD_PATH);
        ids.put(InternalIds.id(BOOK_PATH).toString(), BOOK_PATH);
        ids.put(InternalIds.id(TOKEN_PATH).toString(), TOKEN_PATH);
        return Map.copyOf(ids);
    }

    /**
     * {@return the one character card's mod-relative path}
     *
     * <p>A method rather than a bare constant, so a test can state "there is exactly one card id and it is
     * this" without writing the string a second time.
     */
    public static String cardPath() {
        return CARD_PATH;
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
