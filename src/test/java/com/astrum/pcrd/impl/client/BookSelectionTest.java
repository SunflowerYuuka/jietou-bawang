package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.RosterSyncPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * <b>The click that changes the panel's background</b> — 「点击头像后，显示完全的角色立绘当背景」.
 *
 * <h2>What is asserted, and the one asymmetry that needs explaining</h2>
 * Selection <b>toggles</b> (clicking the selected character clears the growth column, which has always behaved
 * that way), but the <b>background</b> does not go blank: a deselected book falls back to the default. That
 * asymmetry is the human's rule, not an oversight — 「深色底替，换成角色立绘」 was a complaint about a dark plate, so
 * a deselect that brought it back would undo the feature through a side door. Both halves are pinned separately, so
 * a future "simplification" that made one mirror the other fails here.
 *
 * <h2>Why this is not tested through the screen</h2>
 * {@code CharacterBookScreen} needs a client, a world and a player, and a click cannot be delivered without them.
 * The rule is extracted into {@link BookSelection} for exactly this reason; {@code BookSelectionTest} proves the
 * transition, and the probe logs the same transition against the shipped data.
 */
class BookSelectionTest {

    private static final ResourceLocation DJEETA = id("djeeta");
    private static final ResourceLocation OTHER = id("other");
    private static final ResourceLocation SLOT_ONE = id("slot_one");
    private static final ResourceLocation ROSTER_HEAD = id("roster_head");

    // -----------------------------------------------------------------------------------------
    // Selection
    // -----------------------------------------------------------------------------------------

    /** Clicking a different character selects her: the headline behaviour. */
    @Test
    void clickingAnotherCharacterSelectsHer() {
        assertEquals(OTHER, BookSelection.select(DJEETA, OTHER),
                "a click on another cell moves the selection, which is what swaps the still");
        assertEquals(DJEETA, BookSelection.select(null, DJEETA), "the first click selects");
    }

    /** Clicking the selected character again clears the selection, exactly as the screen has always done. */
    @Test
    void clickingTheSelectedCharacterClearsTheSelection() {
        assertNull(BookSelection.select(DJEETA, DJEETA),
                "a second click deselects, so the growth column can be emptied");
    }

    /** A click that hit no cell leaves the selection alone, and a null click is not a crash. */
    @Test
    void aClickOffTheGridChangesNothing() {
        assertEquals(DJEETA, BookSelection.select(DJEETA, null));
        assertNull(BookSelection.select(null, null));
    }

    // -----------------------------------------------------------------------------------------
    // The background
    // -----------------------------------------------------------------------------------------

    /** A selection is the background: no fallback, no re-derivation. */
    @Test
    void theBackgroundIsTheSelectionWhenThereIsOne() {
        assertEquals(Optional.of(OTHER), BookSelection.background(OTHER, party(DJEETA), roster(DJEETA)),
                "the clicked character's still is what fills the panel, not slot 1's");
    }

    /** With nothing selected the default takes over, so the panel is never a dark plate while the book has somebody. */
    @Test
    void aDeselectedBookFallsBackToTheDefault() {
        assertEquals(Optional.of(SLOT_ONE), BookSelection.background(null, party(SLOT_ONE),
                        roster(ROSTER_HEAD)),
                "party slot 1 is the default (「默认使用角色1当背景」)");
        assertEquals(Optional.of(ROSTER_HEAD),
                BookSelection.background(null, emptyParty(), roster(ROSTER_HEAD)),
                "no party: the roster's head");
        assertEquals(Optional.empty(), BookSelection.background(null, party(), roster()),
                "an empty book has nobody, and the panel keeps its plain dark body");
    }

    /**
     * The two halves really are different: this is the assertion that stops the asymmetry being "simplified" away.
     *
     * <p>Deselecting answers {@code null} (the growth column is empty) while the background answers party slot 1
     * (the panel is still filled). If somebody made {@code background} mirror {@code select}, this test fails and
     * the failure message says why.
     */
    @Test
    void deselectingEmptiesTheGrowthViewButNotTheBackground() {
        ResourceLocation afterSecondClick = BookSelection.select(SLOT_ONE, SLOT_ONE);
        assertNull(afterSecondClick, "the growth column goes blank");

        Optional<ResourceLocation> background = BookSelection.background(afterSecondClick,
                party(SLOT_ONE), roster(ROSTER_HEAD));
        assertEquals(Optional.of(SLOT_ONE), background,
                "but the panel still shows a character: 「深色底替」 must not come back through a deselect");
        assertNotEquals(Optional.empty(), background);
    }

    // -----------------------------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------------------------

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("astrum", path);
    }

    /** {@return a five-slot party, filled in order from slot 1; the rest are empty} */
    private static List<Optional<ResourceLocation>> party(ResourceLocation... filled) {
        List<Optional<ResourceLocation>> slots = new ArrayList<>();
        for (ResourceLocation character : filled) {
            slots.add(Optional.ofNullable(character));
        }
        while (slots.size() < 5) {
            slots.add(Optional.empty());
        }
        return slots;
    }

    /**
     * {@return a party with all five slots empty}
     *
     * <p>A named method and not {@code party(null)}: {@code null} as a varargs argument binds to the whole
     * {@code ResourceLocation[]} parameter in javac's phase 2, so {@code party(null)} is a null <em>array</em>
     * rather than a one-element array holding null — the trap this test hit the first time it ran.
     */
    private static List<Optional<ResourceLocation>> emptyParty() {
        return party();
    }

    /** {@return roster rows in the order given, with placeholder numbers} */
    private static List<RosterSyncPayload.Entry> roster(ResourceLocation... characters) {
        List<RosterSyncPayload.Entry> entries = new ArrayList<>();
        for (ResourceLocation character : characters) {
            entries.add(new RosterSyncPayload.Entry(character, 1, 0, 100, 1, 1));
        }
        return entries;
    }
}
