package com.astrum.pcrd.impl.party;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every translation key the 角色册 (character book) asks for, in one common class.
 *
 * <h2>Why the keys are not constants inside the screen</h2>
 * Two reasons, both practical:
 *
 * <ul>
 *   <li>{@code LangParityTest} must be able to enumerate them, and it is a plain unit test: reading a
 *       key set off a {@code Screen} subclass would initialise {@code net.minecraft.client} classes in
 *       a JVM that has no client. This class has no Minecraft imports at all, so the test can hold the
 *       set the way it already holds {@code AstrumCommand.LANG_KEYS}.</li>
 *   <li>The refusal keys are <b>derived</b> from {@link PartyRules.Reason} through
 *       {@link PartyRules#reasonKey}, exactly as the cast path derives its keys from
 *       {@code CastGate.Reason}. A new reason therefore cannot reach a player as a raw
 *       {@code astrum.party.reason.SOMETHING} string: it fails the parity test first.</li>
 * </ul>
 *
 * <p>Item-side keys live here too ({@code item.astrum.character_book}, its tooltip), because the item
 * and the screen are one feature: the tooltip is the only place a player learns the book exists.
 */
public final class BookText {

    /** The item's own name, {@code item.astrum.character_book}. */
    public static final String KEY_ITEM = "item.astrum.character_book";

    /**
     * {@return the item-name key of the {@code theme}'s book}
     *
     * <p>Derived from the same suffix the item id uses ({@link com.astrum.pcrd.impl.item.BookTheme}),
     * so the name key, the item id, the model file and the texture name are one string with four uses.
     * The plain book keeps the literal {@link #KEY_ITEM} it has always had.
     *
     * @param theme the book's theme
     */
    public static String itemKey(com.astrum.pcrd.impl.item.BookTheme theme) {
        return "item.astrum." + theme.bookPath();
    }

    /**
     * {@return every book item name}, one per theme, for {@code LangParityTest}
     *
     * <p>Derived by walking the enum rather than listed: a fourth theme gets its name key checked by the
     * parity test without anybody remembering to add it here, which is the whole failure mode this method
     * exists to prevent.
     */
    public static Set<String> itemKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (com.astrum.pcrd.impl.item.BookTheme theme : com.astrum.pcrd.impl.item.BookTheme.values()) {
            keys.add(itemKey(theme));
        }
        return Set.copyOf(keys);
    }

    /** The item's tooltip: what a right-click does. */
    public static final String KEY_TOOLTIP = "astrum.book.tooltip";

    /** The screen's title. */
    public static final String KEY_TITLE = "astrum.book.title";

    /** Shown in place of the roster, party and growth views when the save cannot be read. */
    public static final String KEY_QUARANTINED = "astrum.book.quarantined";

    /** Section heading: the owned-character grid. */
    public static final String KEY_SECTION_ROSTER = "astrum.book.section.roster";

    /** Section heading: the five party slots. */
    public static final String KEY_SECTION_PARTY = "astrum.book.section.party";

    /** Section heading: the selected character's progress. */
    public static final String KEY_SECTION_GROWTH = "astrum.book.section.growth";

    /** Shown when the roster is readable but empty. */
    public static final String KEY_ROSTER_EMPTY = "astrum.book.roster.empty";

    /** One line telling the player how to move characters between the grid and the slots. */
    public static final String KEY_PARTY_HINT = "astrum.book.party.hint";

    /** Shown in the growth view when no character is selected. */
    public static final String KEY_GROWTH_NONE = "astrum.book.growth.none";

    /** Growth view line: the level. */
    public static final String KEY_GROWTH_LEVEL = "astrum.book.growth.level";

    /** Growth view line: the experience bar's numbers. */
    public static final String KEY_GROWTH_EXP = "astrum.book.growth.exp";

    /** Growth view line: affection and the tier it currently is. */
    public static final String KEY_GROWTH_AFFECTION = "astrum.book.growth.affection";

    /** Growth view line: the threshold of the next affection tier. */
    public static final String KEY_GROWTH_NEXT = "astrum.book.growth.next";

    /** Growth view line: already at the last affection tier. */
    public static final String KEY_GROWTH_MAX = "astrum.book.growth.max";

    /** Growth view entry point when a dish handler is installed. */
    public static final String KEY_FEED_AVAILABLE = "astrum.book.feed.available";

    /** Growth view entry point when no dish handler is installed yet: the route, as text. */
    public static final String KEY_FEED_ROUTE = "astrum.book.feed.route";

    /** A grid cell whose character the loaded pack no longer defines. */
    public static final String KEY_UNKNOWN_CHARACTER = "astrum.book.unknown_character";

    /** The last refused edit, shown at the bottom of the screen. */
    public static final String KEY_REFUSED = "astrum.book.refused";

    /**
     * Every key this feature asks for, for {@code LangParityTest}.
     *
     * <p>The five {@code astrum.party.reason.*} keys are added by walking the enum, so the set cannot
     * fall out of step with the reasons the server can actually return.
     */
    public static final Set<String> LANG_KEYS = keys();

    private BookText() {
    }

    private static Set<String> keys() {
        // A List, not Set.of: a duplicated constant would make Set.of throw inside a static
        // initialiser, i.e. take the whole screen down at class-load time. A duplicate key is a bug,
        // but it must not be a crash in a player's game.
        Set<String> keys = new LinkedHashSet<>(List.of(KEY_ITEM, KEY_TOOLTIP, KEY_TITLE, KEY_QUARANTINED,
                KEY_SECTION_ROSTER, KEY_SECTION_PARTY, KEY_SECTION_GROWTH, KEY_ROSTER_EMPTY, KEY_PARTY_HINT, KEY_GROWTH_NONE, KEY_GROWTH_LEVEL,
                KEY_GROWTH_EXP, KEY_GROWTH_AFFECTION, KEY_GROWTH_NEXT, KEY_GROWTH_MAX,
                KEY_FEED_AVAILABLE, KEY_FEED_ROUTE, KEY_UNKNOWN_CHARACTER, KEY_REFUSED));
        keys.addAll(itemKeys());
        for (PartyRules.Reason reason : PartyRules.Reason.values()) {
            keys.add(PartyRules.reasonKey(reason));
        }
        return Set.copyOf(keys);
    }
}

