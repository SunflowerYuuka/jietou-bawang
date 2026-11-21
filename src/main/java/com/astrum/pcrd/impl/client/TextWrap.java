package com.astrum.pcrd.impl.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Wraps a line of text onto as many lines as it needs, by word for latin and per character for CJK.</b>
 *
 * <h2>The human's rule</h2>
 * 「文字行不够显示的情况下，现在是用...做省略，改成换行显示完全。」 — a line too wide for the box must continue on the
 * next line, not be cut with an ellipsis. The ellipsis was a real loss of information: 佩可莉姆's ultimate has
 * parameter rows whose label plus value can exceed the popup, and 「…」 hid the number itself.
 *
 * <h2>Why two break rules and not one</h2>
 * Latin text is written with spaces and a reader expects a line to end between words: breaking "damage" into
 * "dam" / "age" is legible but wrong-looking, and it is what a per-character rule would do. Chinese and Japanese
 * are written without spaces, so a per-character break is the <em>only</em> correct rule there — a word-splitting
 * rule would find no break at all and emit one enormous line. A mixed string must therefore break at a space when
 * one is available and per character when it is not, which is what this class does: the token stream interleaves
 * whole latin words with individual CJK characters, and a too-wide latin word falls back to a per-character split
 * rather than overflowing.
 *
 * <h2>Why the measurement is a function parameter</h2>
 * The real width of a string comes from {@code Minecraft.font}, which needs a client. Passing
 * {@code ToIntFunction<String>} keeps every decision in this class pure, so {@code TextWrapTest} can assert the
 * whole algorithm with a monospace measurer ({@code String::length}) and no game — the same seam
 * {@code SkillDetailLayout} uses for its geometry.
 *
 * <h2>What this does not do</h2>
 * It does not hyphenate, it does not re-flow a paragraph across pages, and it does not preserve a caller's own
 * newlines: the numbers panel's lines are one per parameter, so a line is a unit and a wrapped line is a
 * <em>display</em> continuation of that unit.
 */
@ApiStatus.Internal
public final class TextWrap {

    private TextWrap() {
    }

    /**
     * {@return {@code text} broken into lines no wider than {@code width}}
     *
     * <p>An empty or null text answers <b>one</b> empty line rather than none: the caller is drawing a row, and a
     * row that produced no lines would silently shift every following line up. A width of {@code 0} or less also
     * answers the text as a single line — a box with no room cannot be wrapped usefully, and one line the caller
     * can clip is a better answer than an infinite list of one character each.
     *
     * @param text     the text to wrap; {@code null} is treated as empty
     * @param width    the usable width in GUI pixels
     * @param widthOf  measures a string in the same unit as {@code width}
     */
    public static List<String> wrap(String text, int width, ToIntFunction<String> widthOf) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        if (width <= 0 || widthOf == null) {
            return List.of(text);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String token : tokens(text)) {
            if (token.isEmpty()) {
                continue;
            }
            String candidate = append(line, token);
            if (widthOf.applyAsInt(candidate) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            // The token does not fit on this line.
            String finished = line.toString();
            if (!finished.isBlank()) {
                lines.add(finished);
            }
            line.setLength(0);
            if (token.isBlank()) {
                // A space that would start a new line is dropped rather than indenting the continuation: the
                // break itself already separates the words, and a leading space is a visible false indent.
                continue;
            }
            if (widthOf.applyAsInt(token) <= width) {
                line.append(token);
                continue;
            }
            // A single latin word wider than the box: fall back to characters rather than overflow. This is the
            // case a per-character rule would have handled from the start, and it is why the fallback exists
            // instead of an assertion that a word always fits.
            String word = token;
            while (!word.isEmpty()) {
                int take = fitLength(word, width, widthOf);
                if (take <= 0) {
                    // A single character wider than the box (an oversized glyph at a tiny GUI scale): take it
                    // anyway, or this loop would never end.
                    take = 1;
                }
                String head = word.substring(0, take);
                word = word.substring(take);
                if (word.isEmpty()) {
                    line.append(head);
                } else {
                    lines.add(head);
                }
            }
        }
        String tail = line.toString();
        if (!tail.isBlank()) {
            lines.add(tail);
        }
        // Trailing spaces are trimmed from every line: a space left at the end of a break costs a pixel column at
        // the right edge and is invisible to a reader, because the break itself already separates the words.
        List<String> trimmed = new ArrayList<>(lines.size());
        for (String finished : lines) {
            trimmed.add(stripTrailingSpace(finished));
        }
        return trimmed.isEmpty() ? List.of("") : List.copyOf(trimmed);
    }

    /** {@return {@code text} without its trailing spaces} */
    private static String stripTrailingSpace(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * {@return {@code line} plus {@code token}, with a space that would be a trailing one dropped}
     *
     * <p>A candidate ending in a space measures <em>wider</em> than the same words without it, so keeping the
     * trailing space makes a line break one word earlier than necessary and leaves a blank pixel column at the
     * right edge. Dropping it is invisible to a reader — the break already separates the words — and it removes a
     * whole class of off-by-a-space wrapping.
     */
    private static String append(StringBuilder line, String token) {
        if (token.isBlank() && line.length() == 0) {
            return line.toString();
        }
        return line + token;
    }

    /**
     * {@return how many leading characters of {@code word} fit in {@code width}, at least 1}
     *
     * <p>Linear rather than binary: the strings here are a label plus a number (tens of characters), and the
     * binary search's extra branches would cost more than the scan.
     */
    private static int fitLength(String word, int width, ToIntFunction<String> widthOf) {
        for (int end = 1; end <= word.length(); end++) {
            if (widthOf.applyAsInt(word.substring(0, end)) > width) {
                return end - 1;
            }
        }
        return word.length();
    }

    /**
     * {@return the text as a stream of break units: whole latin words, single CJK characters, single spaces}
     *
     * <h2>The classification, and why it is not a locale</h2>
     * A character is treated as "breakable by itself" when it is CJK ideographic, kana, hangul, or a fullwidth
     * form — the scripts that are written without inter-word spaces. Everything else is grouped with its
     * neighbours up to the next space or CJK character, which is a word in the latin sense. This is a property of
     * the <b>text</b> rather than of the player's locale, which is what makes a mixed string (「攻击力 +20%」)
     * wrap sensibly for every player: the CJK part breaks per character, the latin part at its space.
     */
    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character)) {
                if (word.length() > 0) {
                    tokens.add(word.toString());
                    word.setLength(0);
                }
                // Emit the space as its own token so a break here loses nothing, and a line that ends in one is
                // trimmed by the caller's measurement rather than by a silent concatenation.
                tokens.add(String.valueOf(character));
            } else if (breaksByCharacter(character)) {
                if (word.length() > 0) {
                    tokens.add(word.toString());
                    word.setLength(0);
                }
                tokens.add(String.valueOf(character));
            } else {
                word.append(character);
            }
        }
        if (word.length() > 0) {
            tokens.add(word.toString());
        }
        return tokens;
    }

    /**
     * {@return whether {@code character} may be a break point on its own}
     *
     * <p>The ranges are the ones Unicode reserves for scripts written without spaces: CJK ideographs (and their
     * extensions), hiragana, katakana, hangul, the fullwidth/halfwidth forms, and CJK punctuation (「」、。…).
     * Deliberately a range test rather than {@code Character.UnicodeScript}, because the script of a punctuation
     * mark is {@code COMMON} and CJK punctuation would then be treated as a latin word.
     */
    private static boolean breaksByCharacter(char character) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(character);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
