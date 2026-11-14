package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.ApiStatus;

/**
 * The mod's one creative-mode tab: {@code astrum:astrum}, holding every item this build registers.
 *
 * <h2>What is here, and what is deliberately not</h2>
 * Two facts and one rule: a {@link DeferredRegister} for the {@code creative_mode_tab} registry, a
 * builder that names the tab and its icon, and a comparator that decides the order the items appear in.
 * There is no per-item list in this file and no "add the new item here" step in the registration path —
 * see {@link #orderedItems} for why, and what a future item therefore has to do (nothing beyond
 * registering on {@link AstrumItems#ITEMS}).
 *
 * <h2>The id: {@code astrum:astrum}</h2>
 * One tab for the whole mod, so its path is the mod id, in this mod's namespace. Every Minecraft registry
 * key is namespaced, so the mod-relative id the brief asks for ({@code astrum}) is what a human writes and
 * the key the registry actually holds is {@code astrum:astrum} — exactly as {@code minecraft:building_blocks}
 * is the vanilla building-blocks tab. The human-facing name is not the id at all but the translation key
 * {@link #TITLE_KEY}, {@code itemGroup.astrum}.
 *
 * <p>No {@code withTabsBefore}/{@code withTabsAfter} edge is declared. NeoForge's
 * {@code CreativeModeTabRegistry#sortTabs} already gives an edge-less mod tab an edge from the last vanilla
 * tab, i.e. it lands right after the vanilla strip, which is the placement we want. Declaring an edge would
 * add nothing and would pin this tab to one named vanilla neighbour forever.
 *
 * <h2>The icon is an {@code ItemStack}, not the 16x16 texture — and that is not a shortcut</h2>
 * The repository owns a purpose-drawn 16x16 tab icon, {@code assets/astrum/textures/gui/tab_icon.png}
 * (drawn specifically because the 128x128 banner's lettering turns to noise at 16x16). It is <b>not</b>
 * used as the tab image, because the documented 21.1 API has no slot for one:
 *
 * <ul>
 *   <li>{@code CreativeModeTab.Builder#icon(Supplier<ItemStack>)} is the only icon setter, and
 *       {@code CreativeModeInventoryScreen#renderTabButton} draws the result with
 *       {@code graphics.renderItem(getIconItem(), x, y)} — an item render, with the item's own model and
 *       lighting, not a texture blit;</li>
 *   <li>{@code Builder#withTabsImage(ResourceLocation)} <em>looks</em> like the answer and is not: the field
 *       it sets is exposed by {@code CreativeModeTab#getTabsImage()}, and in 21.1.265 <b>nothing reads that
 *       getter</b> — {@code renderTabButton} blits vanilla's own
 *       {@code container/creative_inventory/tab_top_unselected_N} sprites directly. So it cannot carry an
 *       icon, and it is inert besides;</li>
 *   <li>{@code Builder#backgroundTexture} is the panel behind the item grid, again not the icon.</li>
 * </ul>
 *
 * So the tab's face is an {@code ItemStack}, as every vanilla tab's is, and the item chosen is the
 * <b>default 角色册</b>, {@code astrum:character_book} ({@link #iconEntry()}): the item that is already the
 * mod's identity in a player's inventory, that is craftable from ordinary ingredients
 * ({@code data/astrum/recipe/character_book.json}: book + 2 paper + string, so the tab's face is an item a
 * player can actually be holding), and that carries the same blue book the roster/party/growth screen is
 * built around. The banner was rejected for the reason the art was drawn twice: at 16x16 its lettering is
 * unreadable, which is exactly what {@code tab_icon.png} exists to avoid.
 *
 * <p><b>The 16x16 texture is therefore unused by this tab.</b> It stays in the repository because it is the
 * art the mod list and any future tab-shaped UI would want, and because deleting art the human just had
 * drawn is not this task's call. Recorded in the task report as a loose end, not forgotten.
 *
 * <h2>Population: automatic, from the item register</h2>
 * {@link #onBuildContents} fills the tab in {@link BuildCreativeModeTabContentsEvent} from
 * {@link AstrumItems#ITEMS}{@code .getEntries()} — the register itself, not a hand-written list. A new item
 * added to {@code AstrumItems} appears in this tab the moment it is registered, with no second edit; that
 * is the difference between "add every item" and "add every item that existed on the day this was
 * written". The event is used rather than {@code Builder#displayItems} because the event is where the
 * contents are assembled for <em>any</em> tab, so a later mod (or a datapack-driven integration) adding to
 * this tab composes with our fill by the same mechanism vanilla uses for its own tabs.
 *
 * <h2>The order is a rule, not registry order</h2>
 * Card, then books, then everything else (today: the dish). That is the order of the loop a player
 * actually runs — the card is the only way a character enters the book, the books are the screen used
 * afterwards, the dish is what is spent on the character once it is in the book — and it matches the book
 * screen's own sections. Inside the books the theme order is {@link BookTheme}'s declaration order (blue,
 * sakura, forest), which is the order the three themes shipped in.
 *
 * <p>Today's registration order happens to agree with that rule, which is precisely why the rule is
 * written down and tested rather than relied on: {@code AstrumItems} orders its fields by {@code BookTheme}'s
 * table, so a fourth theme, or a block item added between the cards and the books, would silently reorder
 * the tab. {@code AstrumCreativeTabTest} pins the five ids in this order, so a change of order is a
 * decision someone makes in the test, not an accident of where a field was typed.
 *
 * <p>There is one card. {@code _sakura} / {@code _forest} card items were deleted (see
 * {@code BookTheme}'s javadoc): a card names a character, not a theme, and any of the three books shows
 * every character the player has enrolled.
 */
@ApiStatus.Internal
public final class AstrumCreativeTab {

    /** Mod-relative path of the tab; the registry key is therefore {@code astrum:astrum}. */
    public static final String TAB_PATH = "astrum";

    /** Translation key of the tab's title, in both {@code en_us} and {@code zh_cn}. */
    public static final String TITLE_KEY = "itemGroup.astrum";

    /** Creative-tab registrations, on the mod bus. */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Astrum.MOD_ID);

    /** The tab itself. Registered under {@link #TAB_PATH}, so {@code astrum:astrum}. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ASTRUM =
            TABS.register(TAB_PATH, AstrumCreativeTab::build);

    /**
     * The registry key of {@link #ASTRUM}, for comparing against
     * {@link BuildCreativeModeTabContentsEvent#getTabKey()}.
     *
     * <p>Compared by key rather than by identity: the listener must answer "is this our tab" before the
     * tab holder is necessarily bound, and a {@link ResourceKey} exists from class-load time while
     * {@code ASTRUM.get()} would throw outside a running registry.
     */
    public static final ResourceKey<CreativeModeTab> KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, InternalIds.id(TAB_PATH));

    /** The item whose stack is drawn as the tab's icon: the default 角色册. See the class javadoc. */
    private static final DeferredItem<CharacterBookItem> ICON_ENTRY = AstrumItems.CHARACTER_BOOK;

    /**
     * The family ranks: 0 card, 1 books, 2 everything else. Prefixes rather than exact ids so a fourth
     * theme lands in the right family without touching this class.
     */
    private static final int RANK_CARD = 0;
    private static final int RANK_BOOK = 1;
    private static final int RANK_OTHER = 2;

    /**
     * The one ordering rule: family, then theme, then path.
     *
     * <p>The path tie-break is not decoration — it makes the order total, so two items in the same family
     * and theme (which cannot happen today) still have a stable, reproducible position instead of
     * inheriting whatever a {@code HashSet} iteration produced.
     */
    private static final Comparator<ResourceLocation> ORDER = Comparator
            .comparingInt(AstrumCreativeTab::familyRank)
            .thenComparingInt(id -> BookTheme.ofItemId(id).ordinal())
            .thenComparing(ResourceLocation::getPath);

    private AstrumCreativeTab() {
    }

    /**
     * {@return the language keys this tab asks for}, for {@code LangParityTest}
     *
     * <p>A method, not a static field: one string, derived from the constant above, and a static container
     * field is one more thing {@code guard} warns about for no benefit.
     */
    public static Set<String> langKeys() {
        return Set.of(TITLE_KEY);
    }

    /**
     * {@return the item whose stack the tab draws as its icon}
     *
     * <p>Exposed so a unit test can assert <em>which</em> item the tab advertises without a registry (the
     * {@link DeferredItem} knows its own id before anything is bound), and so the builder and the test
     * cannot disagree about it.
     */
    public static DeferredItem<CharacterBookItem> iconEntry() {
        return ICON_ENTRY;
    }

    /**
     * Builds the tab: title, icon, and nothing else.
     *
     * <p>No {@code displayItems} generator on purpose — the contents come from
     * {@link #onBuildContents}. An empty generator plus the event is the same result as a generator that
     * fills the tab, and it keeps "what the tab is" (here) apart from "what is in it" (the item register),
     * which is the same split {@code AstrumItems} makes between behaviour and wiring.
     */
    private static CreativeModeTab build() {
        return CreativeModeTab.builder()
                .title(Component.translatable(TITLE_KEY))
                .icon(() -> new ItemStack(ICON_ENTRY.get()))
                .build();
    }

    /**
     * Fills this mod's tab with every item {@link AstrumItems} registers, in {@link #ORDER}.
     *
     * @param event the mod-bus event fired while a tab assembles its contents
     */
    static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (!KEY.equals(event.getTabKey())) {
            return;
        }
        for (DeferredHolder<Item, ? extends Item> entry : orderedItems(AstrumItems.ITEMS.getEntries())) {
            event.accept(new ItemStack(entry.value()));
        }
    }

    /**
     * {@return {@code entries} in the tab's order — every one of them, none added, none dropped}
     *
     * <p>The population path: the caller passes the item register's own entries, so this method is the
     * whole of "which items are in the tab". A new item therefore needs no edit anywhere in this file.
     *
     * @param entries the items to order, typically {@code AstrumItems.ITEMS.getEntries()}
     */
    public static List<DeferredHolder<Item, ? extends Item>> orderedItems(
            Collection<DeferredHolder<Item, ? extends Item>> entries) {
        return entries.stream()
                .sorted(Comparator.comparing((DeferredHolder<Item, ? extends Item> e) -> e.getId(), ORDER))
                .toList();
    }

    /**
     * {@return {@code ids} in the same order {@link #orderedItems} puts the items they name}
     *
     * <p>The pure half of the rule, and what the unit test exercises: ids in, ids out, no registry, no
     * {@code ItemStack}. Kept next to {@code orderedItems} (which applies it to entries) so the tab and the
     * test cannot end up with two different orders.
     *
     * @param ids the item ids to order
     */
    public static List<ResourceLocation> orderedIds(Collection<ResourceLocation> ids) {
        return ids.stream().sorted(ORDER).toList();
    }

    /**
     * {@return this tab and the event listener that fills it}
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        TABS.register(modBus);
        modBus.addListener(AstrumCreativeTab::onBuildContents);
    }

    /** {@return 0 for a character card, 1 for a character book, 2 for anything else} */
    private static int familyRank(ResourceLocation id) {
        String path = id.getPath();
        if (path.startsWith(BookTheme.CARD_PATH)) {
            return RANK_CARD;
        }
        if (path.startsWith(BookTheme.BOOK_PATH)) {
            return RANK_BOOK;
        }
        return RANK_OTHER;
    }
}
