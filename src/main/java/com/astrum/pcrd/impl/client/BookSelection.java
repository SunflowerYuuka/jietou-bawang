package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.network.RosterSyncPayload;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>What the book is looking at</b>, as a pure state machine: which character a click selects, and which
 * character's still fills the panel.
 *
 * <h2>Why this is not three lines inside the screen</h2>
 * The human's two requests about the panel's background are both rules rather than drawing:
 * 「点击头像后，显示完全的角色立绘当背景」 (a click on a cell shows that character) and 「默认使用角色1当背景」 (with
 * nothing selected, party slot 1). Written inline in {@code CharacterBookScreen}, they would be unreachable from a
 * test — the screen needs a client, a world and a player — and the only evidence that "clicking a different cell
 * changes the background" would be somebody's eyes.
 *
 * <p>Extracted here, they are arithmetic over ids: {@code BookSelectionTest} pins the toggle and the fallback, and
 * the probe can print the same transition the screen performs.
 *
 * <h2>The two rules</h2>
 * <ol>
 *   <li><b>Selection toggles.</b> Clicking the selected character deselects her (the growth column then says
 *       "pick a character"); clicking anyone else selects her. That is what the screen has always done, and it is
 *       kept — a cell that could not be deselected would leave the growth column stuck on one character.</li>
 *   <li><b>The background never goes blank while the book holds somebody.</b> A deselected book falls back to
 *       {@link TierAvatar#defaultSelection}. This is the deliberate asymmetry: the growth column has a real
 *       "nothing selected" state, and a panel has not — the human's complaint was a dark plate (「深色底替，换成角色
 *       立绘」), and letting a deselect bring it back through a different door would undo the feature.</li>
 * </ol>
 */
@ApiStatus.Internal
public final class BookSelection {

    private BookSelection() {
    }

    /**
     * {@return the selection after a left-click on {@code clicked}}
     *
     * <p>Clicking the current selection clears it; clicking anyone else selects her. A {@code null} click is a
     * bug in the caller and answers the current selection unchanged rather than throwing on the input path.
     *
     * @param current the current selection, {@code null} for none
     * @param clicked the character whose cell was clicked, {@code null} for a click that hit no cell
     */
    public static @Nullable ResourceLocation select(@Nullable ResourceLocation current,
            @Nullable ResourceLocation clicked) {
        if (clicked == null) {
            return current;
        }
        return clicked.equals(current) ? null : clicked;
    }

    /**
     * {@return the character whose still the panel should draw}
     *
     * <p>The selection, or the default when nothing is selected — see the class javadoc for why this does not
     * simply mirror {@link #select}.
     *
     * @param selected the current selection, {@code null} for none
     * @param party    the party, in slot order
     * @param roster   the roster, in roster order
     */
    public static Optional<ResourceLocation> background(@Nullable ResourceLocation selected,
            List<Optional<ResourceLocation>> party, List<RosterSyncPayload.Entry> roster) {
        if (selected != null) {
            return Optional.of(selected);
        }
        return TierAvatar.defaultSelection(party, roster);
    }

    /** {@return {@link #background} applied to the client's two caches} */
    public static Optional<ResourceLocation> background(@Nullable ResourceLocation selected) {
        return background(selected, ClientPartyCache.latest().slots(), ClientRosterCache.entries());
    }
}
