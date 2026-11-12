package com.astrum.pcrd.impl.growth;

import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * The translation keys the growth system speaks with.
 *
 * <p>Collected here for the reason {@code AstrumCommand.LANG_KEYS} exists: {@code LangParityTest}
 * compares the two shipped language files against each other and against the keys the code names, so a
 * message that is added without a translation — or renamed and left behind — fails a test instead of
 * showing a raw {@code astrum.growth.*} string to a player.
 *
 * <p>Every outcome of {@link DishFeed.Outcome} is answered, including the ones the player should never
 * see in a healthy game: spec §5.5 forbids a silent failure, and "the dish did nothing" with no sentence
 * is exactly that.
 */
@ApiStatus.Internal
public final class GrowthText {

    /** A dish was eaten and the party gained affection. Args: dish name, how many characters. */
    public static final String KEY_FED = "astrum.growth.fed";

    /** The day's allowance for that dish is already spent. Args: dish name. */
    public static final String KEY_ALREADY_TODAY = "astrum.growth.already_today";

    /** Everyone in the party is already at the last tier. Args: dish name. */
    public static final String KEY_ALREADY_FULL = "astrum.growth.already_full";

    /** No owned, known character in the party. Args: dish name. */
    public static final String KEY_NOBODY = "astrum.growth.nobody";

    /** The character book could not be read, so nothing was written. */
    public static final String KEY_BOOK_UNREADABLE = "astrum.growth.book_unreadable";

    /** A character crossed a level from a kill. Args: character name, new level. */
    public static final String KEY_LEVEL_UP = "astrum.growth.level_up";

    /** Every key above, for the language-parity test. */
    public static final Set<String> LANG_KEYS = Set.of(
            KEY_FED, KEY_ALREADY_TODAY, KEY_ALREADY_FULL, KEY_NOBODY, KEY_BOOK_UNREADABLE,
            KEY_LEVEL_UP);

    private GrowthText() {
    }
}
