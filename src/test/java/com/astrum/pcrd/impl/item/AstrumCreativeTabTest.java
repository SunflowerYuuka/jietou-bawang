package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.impl.InternalIds;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The creative tab's one rule: <b>which items, in which order</b>.
 *
 * <h2>Why the order is a test and not a comment</h2>
 * The tab holds three items today and the population path is derived from
 * {@code AstrumItems.ITEMS} — which is what makes a future item appear without an edit, and also what
 * makes the order fragile: it is decided by a comparator over registry ids, so adding a block item, a
 * fourth theme, or moving a field in {@code AstrumItems} reorders the tab silently. The player-visible
 * consequence is a tab whose first row is not the loop a player runs (card → book → dish), and nothing
 * anywhere fails. So the ids are pinned <b>as a list, in order</b>, and a change has to be made here on
 * purpose. The list was five until the two re-skinned books were deleted (「将2个皮肤版本的角色册道具删除，仅
 * 保留最开始的那个」); it is three now, and the deletion is visible here as three names instead of five.
 *
 * <h2>What is deliberately not asserted here</h2>
 * That the registry contains these ids, that {@code BuildCreativeModeTabContentsEvent} really fires, and
 * that the tab's icon renders — all three need a running game. They are proved by the client probe's log
 * instead ({@code task-16b-report.md} §5), because a unit test cannot bind a {@code DeferredHolder} without
 * a {@code RegisterEvent}.
 */
class AstrumCreativeTabTest {

    /** The tab is {@code astrum:astrum}: mod-relative path {@code astrum} in the {@code astrum} namespace. */
    private static final String TAB_ID = "astrum:astrum";

    /** Every item this build registers, in the order the tab must show them. */
    private static final List<String> EXPECTED_ORDER = List.of(
            // The card family, then the book, then the exchange loop and the dish.
            "astrum:character_card",
            "astrum:character_book",
            "astrum:memory_token",
            "astrum:rice_bowl");

    /**
     * The four items, in this exact order: the card, then the book, then the exchange loop, then the dish.
     *
     * <p>Each position is the reason the order exists: a card comes first because a card is the only way a
     * character gets into the book; the book follows because it is the screen used afterwards; the
     * <b>token</b> and the dish are last because both are spent on a character that is already in the book,
     * and the token is the earlier half of that loop (a duplicate card becomes a token, a hundred tokens
     * become another card) so it comes before the dish that feeds her.
     *
     * <p><b>The voucher is gone.</b> It used to sit between the card and the book — its path
     * {@code character_card_voucher} starts with {@code character_card}, so the prefix rule sorted it into
     * the card family by construction. The human removed the item (「100个，又不是放在合成台，是背包里右键直接
     * 使用的东西」 + 「直接随机获取一个没有的角色」): the crystal is clicked directly, so there is nothing left
     * to register and this list lost one entry.
     */
    @Test
    void theTabHoldsEveryItemInCardsThenBooksThenDishOrder() {
        List<DeferredHolder<Item, ? extends Item>> ordered =
                AstrumCreativeTab.orderedItems(AstrumItems.ITEMS.getEntries());

        List<String> ids = new ArrayList<>();
        for (DeferredHolder<Item, ? extends Item> entry : ordered) {
            ids.add(entry.getId().toString());
        }
        assertEquals(EXPECTED_ORDER, ids, "the tab's contents, in order");
    }

    /**
     * The order is stable under the register's own iteration order, and nothing is lost.
     *
     * <p>{@code DeferredRegister} keeps a {@code LinkedHashMap}, so today the register's order already
     * equals the tab's — which means {@code theTabHoldsEveryItemInCardsThenBooksThenDishOrder} would pass
     * even if the comparator did nothing. This reverses the input and re-sorts, so the assertion is about
     * the comparator: a sorter that merely echoed its input fails here.
     */
    @Test
    void theOrderComesFromTheComparatorNotFromTheRegistersIterationOrder() {
        List<DeferredHolder<Item, ? extends Item>> forward =
                new ArrayList<>(AstrumItems.ITEMS.getEntries());
        List<DeferredHolder<Item, ? extends Item>> reversed = new ArrayList<>(forward);
        Collections.reverse(reversed);

        assertEquals(ids(AstrumCreativeTab.orderedItems(forward)),
                ids(AstrumCreativeTab.orderedItems(reversed)),
                "the tab's order must not depend on the order the register happens to hold entries in");
        assertEquals(EXPECTED_ORDER.size(), forward.size(),
                "every registered item must be in the tab: a new item is not 'silently left out' precisely "
                        + "because the population path reads this register");
    }

    /**
     * The id rule, on ids alone: the pure half used by {@code orderedItems} agrees with it.
     *
     * <p>Also the check that a namespace does not accidentally sort in: a lookalike {@code minecraft:}
     * item would be rank "other" and land after the books, which is right, but only {@code astrum:} ids are
     * ever passed — the register is namespaced — so this pins the family rule itself rather than a
     * hypothetical.
     */
    @Test
    void theIdRuleRanksCardsBeforeBooksBeforeEverythingElse() {
        List<ResourceLocation> ids = List.of(
                InternalIds.id("rice_bowl"),
                InternalIds.id("character_book_gold"),
                InternalIds.id("character_card"),
                InternalIds.id("character_book"));

        assertEquals(List.of(
                        "astrum:character_card",
                        "astrum:character_book",
                        "astrum:character_book_gold",
                        "astrum:rice_bowl"),
                AstrumCreativeTab.orderedIds(ids).stream().map(ResourceLocation::toString).toList(),
                "a future sibling book still lands in the book family, sorted by path");

        // An empty register (a build with no items) must produce an empty tab rather than throw: the
        // population path runs on every tab rebuild, including in a world opened before items exist.
        assertEquals(List.of(), AstrumCreativeTab.orderedIds(List.of()));
        assertEquals(List.of(), AstrumCreativeTab.orderedItems(List.of()));
    }

    /**
     * The icon is the default 角色册, and it is an item rather than the 16x16 texture.
     *
     * <p>The brief's constraint, pinned: NeoForge 21.1's only icon setter is
     * {@code CreativeModeTab.Builder#icon(Supplier<ItemStack>)}, so the tab's face is an item stack. The
     * banner and the purpose-drawn {@code gui/tab_icon.png} are both the wrong shape for it — the banner
     * because its lettering is unreadable at 16x16, the texture because no builder method takes an icon
     * texture. Asserting the identity here means the builder and the test cannot disagree about which
     * stack the tab advertises.
     */
    @Test
    void theIconIsTheDefaultBookAndItsTextureIsPackagedButNotTheIcon() {
        DeferredItem<CharacterBookItem> icon = AstrumCreativeTab.iconEntry();
        assertSame(AstrumItems.CHARACTER_BOOK, icon, "the tab's icon entry is the default book, not a copy");
        assertEquals("astrum:character_book", icon.getId().toString());

        // The two art files are both really on the classpath, so "we used the book and not the art" is a
        // statement about a choice among present options rather than about a missing file.
        assertNotNull(AstrumCreativeTabTest.class.getResourceAsStream("/assets/astrum/textures/gui/tab_icon.png"),
                "the 16x16 tab icon must stay in the repository (unused by this tab, see the class javadoc)");
        assertNotNull(AstrumCreativeTabTest.class.getResourceAsStream("/assets/astrum/textures/logo.png"),
                "the banner used as the Mods-list logoFile must exist in the texture tree");
    }

    /** The tab's registry key is {@code astrum:astrum} and its title key is {@code itemGroup.astrum}. */
    @Test
    void theTabIsNamespacedAndItsTitleKeyIsTranslatedInBothLanguages() {
        assertEquals(TAB_ID, AstrumCreativeTab.KEY.location().toString());
        assertEquals("astrum", AstrumCreativeTab.TAB_PATH);
        assertEquals("itemGroup.astrum", AstrumCreativeTab.TITLE_KEY);
        assertEquals(Set.of("itemGroup.astrum"), AstrumCreativeTab.langKeys());

        for (String language : List.of("en_us", "zh_cn")) {
            JsonObject lang = lang("/assets/astrum/lang/" + language + ".json");
            assertTrue(lang.has(AstrumCreativeTab.TITLE_KEY), language + " is missing the tab's title");
            assertTrue(!lang.get(AstrumCreativeTab.TITLE_KEY).getAsString().isBlank(),
                    language + " has a blank tab title");
        }
    }

    /**
     * The four ids are exactly the themed items plus the dish — no fifth item has crept in unasserted.
     *
     * <p>Cross-checked against {@link AstrumItems#themedItemIds()} rather than retyped: if a fourth theme
     * is added, that map grows and this fails with a message saying the tab's order needs a decision,
     * which is the whole point of pinning it.
     */
    @Test
    void thePinnedListIsStillTheRegistersWholeContent() {
        Set<String> themed = new LinkedHashSet<>(AstrumItems.themedItemIds().keySet());
        themed.add("astrum:rice_bowl");
        // `themedItemIds()` holds the card, the book and the token; only the dish lives under an id its own
        // map does not carry, so it is the one added by hand. The card used to be added by hand too, back
        // when that map held books only — the other ids moved in there instead of here, so an item can never
        // be forgotten by both.
        assertEquals(themed, new LinkedHashSet<>(EXPECTED_ORDER),
                "the tab's pinned order must list every item the mod registers, and nothing else — "
                        + "extend EXPECTED_ORDER deliberately when adding an item");
    }

    private static List<String> ids(List<DeferredHolder<Item, ? extends Item>> entries) {
        List<String> out = new ArrayList<>();
        for (DeferredHolder<Item, ? extends Item> entry : entries) {
            out.add(entry.getId().toString());
        }
        return out;
    }

    private static JsonObject lang(String resource) {
        try (InputStream in = AstrumCreativeTabTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing language file on the classpath: " + resource);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}


