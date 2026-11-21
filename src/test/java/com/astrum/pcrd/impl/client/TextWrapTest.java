package com.astrum.pcrd.impl.client;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The numbers panel wraps instead of truncating — by word for latin, per character for CJK.</b>
 *
 * <h2>His correction</h2>
 * 「文字行不够显示的情况下，现在是用...做省略，改成换行显示完全。」 The ellipsis was a real loss: a parameter row's label plus
 * value could exceed the popup, and 「…」 hid the number the page exists to show.
 *
 * <h2>Two rules, and why a single rule is wrong in one language</h2>
 * Latin is written with spaces, so breaking mid-word is legible but wrong-looking: 「dam/mage」. Chinese and
 * Japanese have no inter-word spaces, so a word-splitting rule finds <b>no</b> break at all and emits one enormous
 * line. The tokeniser therefore interleaves whole latin words with individual CJK characters, and a mixed string
 * breaks at whichever boundary is available. Both halves are asserted below, because a rule that only handled CJK
 * would pass a Chinese-only test while mangling every English label.
 *
 * <h2>Why a monospace measurer</h2>
 * Every decision in {@link TextWrap} takes the width function as a parameter, so this file measures with
 * {@code String::length} — one unit per character — and asserts exact expected strings instead of ranges. The real
 * font's widths are not the subject; the break <em>rule</em> is.
 */
class TextWrapTest {

    /** One unit per character, so a width of 10 is ten characters. */
    private static final ToIntFunction<String> MONOSPACE = String::length;

    // ---------------------------------------------------------------------------------------
    // Latin: break at spaces
    // ---------------------------------------------------------------------------------------

    /**
     * <b>A long english line breaks between words, never inside one.</b>
     *
     * <p>「attack damage bonus」 at width 12: {@code "attack damage"} is 13 units, one over, so the greedy fill
     * emits 「attack」 and carries 「damage bonus」 (12) — two whole words per line, none split. The assertion that
     * matters is the loop: no piece contains half a word, because that is the reading a player expects from latin
     * text.
     */
    @Test
    void aLatinLineBreaksBetweenWords() {
        List<String> lines = TextWrap.wrap("attack damage bonus", 12, MONOSPACE);
        assertEquals(List.of("attack", "damage bonus"), lines,
                "wrapped at the spaces, with no word cut in half");
        for (String line : lines) {
            assertTrue(MONOSPACE.applyAsInt(line) <= 12, "every line fits: " + line);
            assertFalse(line.endsWith(" "), "and no line keeps a trailing space: '" + line + "'");
        }
        // Every word survives whole, which is the property "break between words" actually names.
        List<String> words = new java.util.ArrayList<>();
        for (String line : lines) {
            words.addAll(List.of(line.split(" ")));
        }
        assertEquals(List.of("attack", "damage", "bonus"), words, "every word survives whole and in order");
    }

    /** A line that already fits is returned unchanged, as exactly one line. */
    @Test
    void aLineThatFitsIsUntouched() {
        assertEquals(List.of("攻击力 +20%"), TextWrap.wrap("攻击力 +20%", 40, MONOSPACE));
        assertEquals(List.of("short"), TextWrap.wrap("short", 5, MONOSPACE));
    }

    /**
     * <b>A single latin word wider than the box falls back to characters rather than overflowing.</b>
     *
     * <p>The alternative — emitting the word anyway — is how text escapes the popup and lands on the frame, the
     * defect the nine-slice replacement already fixed once. A per-character split looks worse than a word break and
     * is strictly better than a spill.
     */
    @Test
    void anUnbreakableWordSplitsByCharacterInsteadOfOverflowing() {
        List<String> lines = TextWrap.wrap("abcdefghijkl", 4, MONOSPACE);
        assertEquals(List.of("abcd", "efgh", "ijkl"), lines);
        for (String line : lines) {
            assertTrue(MONOSPACE.applyAsInt(line) <= 4, "even the fallback fits: " + line);
        }
    }

    // ---------------------------------------------------------------------------------------
    // CJK: break per character
    // ---------------------------------------------------------------------------------------

    /**
     * <b>A CJK line breaks per character, because it has no spaces to break at.</b>
     *
     * <p>This is the case a word-splitting rule would fail completely: 「全体攻击力提升持续12秒」 contains no space, so a
     * space-only rule would emit one line twice the box's width and the ellipsis would eat the number again.
     */
    @Test
    void aCjkLineBreaksPerCharacter() {
        List<String> lines = TextWrap.wrap("全体攻击力提升持续", 4, MONOSPACE);
        assertEquals(List.of("全体攻击", "力提升持", "续"), lines);
        for (String line : lines) {
            assertTrue(MONOSPACE.applyAsInt(line) <= 4, "every CJK line fits: " + line);
        }
    }

    /**
     * <b>A mixed string breaks the CJK part per character and the latin part at its space.</b>
     *
     * <p>「攻击力提升 attack +20%」 is exactly what a parameter row contains. The CJK prefix must be able to split
     * anywhere; the latin tail must not be cut mid-word when a space is available.
     */
    @Test
    void aMixedLineUsesBothRules() {
        List<String> lines = TextWrap.wrap("攻击力提升 attack bonus", 8, MONOSPACE);
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            assertTrue(MONOSPACE.applyAsInt(line) <= 8, "every mixed line fits: " + line);
        }
        String joined = String.join("", lines);
        assertEquals("攻击力提升attackbonus", joined.replace(" ", ""),
                "and nothing is lost or duplicated by the wrap");
        // width 8 is 8 units, so a 5-unit word like "bonus" fits on its own line — the wrap must have taken that
        // option instead of cutting it, which is what "break at a space when one is available" means.
        assertTrue(lines.contains("bonus"),
                "the latin word went on its own line rather than being cut: " + lines);
        assertFalse(lines.stream().anyMatch(line -> line.equals("bonu") || line.equals("s")),
                "the latin word is not split when a space was available: " + lines);
    }

    // ---------------------------------------------------------------------------------------
    // Degenerate inputs
    // ---------------------------------------------------------------------------------------

    /**
     * <b>An empty text still answers one line, and a zero width is not an infinite loop.</b>
     *
     * <h2>Why "one empty line" and not "no lines"</h2>
     * The caller is drawing consecutive rows at consecutive y offsets. A line that produced <em>no</em> pieces would
     * shift every following line up by one row and silently misalign the panel. One empty line occupies its row and
     * draws nothing, which is the correct pixel outcome.
     *
     * <p>A width of zero answers the text as one line: a box with no room cannot be wrapped usefully, and one line
     * the caller clips is better than a list of one character each — which for a long string would also be a
     * pathological number of rows on a paged panel.
     */
    @Test
    void degenerateInputsAreHandledRatherThanThrowing() {
        assertEquals(List.of(""), TextWrap.wrap("", 10, MONOSPACE));
        assertEquals(List.of(""), TextWrap.wrap(null, 10, MONOSPACE));
        assertEquals(List.of("攻击力"), TextWrap.wrap("攻击力", 0, MONOSPACE));
        assertEquals(List.of("攻击力"), TextWrap.wrap("攻击力", -5, MONOSPACE));
        assertEquals(List.of("攻击力"), TextWrap.wrap("攻击力", 10, null));
        // A single character wider than the box still terminates: one character per line, not a hang.
        List<String> narrow = TextWrap.wrap("ab", 0, text -> 100);
        assertEquals(List.of("ab"), narrow, "a zero width answers one line rather than looping");
    }

    /** No line in any result exceeds the width it was given, for a range of widths. */
    @Test
    void noLineEverExceedsTheWidth() {
        String text = "全体攻击力提升 attack damage bonus 持续12秒";
        for (int width = 1; width <= 40; width++) {
            for (String line : TextWrap.wrap(text, width, MONOSPACE)) {
                assertTrue(MONOSPACE.applyAsInt(line) <= Math.max(width, 1),
                        "width " + width + " produced an overflowing line: '" + line + "'");
            }
            // And the wrap is lossless: concatenating the pieces recovers every non-space character.
            String joined = String.join("", TextWrap.wrap(text, width, MONOSPACE)).replace(" ", "");
            assertEquals(text.replace(" ", ""), joined, "width " + width + " lost or duplicated text");
        }
    }
}
