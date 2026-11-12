package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.attach.Roster;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a character card does to a player's book, as a pure function.
 *
 * <h2>Why the card is not consumed here</h2>
 * {@link #enroll} takes a {@link Roster} and returns a {@link Roster}; it has no item, no player and
 * no level, so it cannot consume anything. That split is the contract: the caller compares what came
 * back with what went in and only then decides whether the card is spent. Putting "consume the stack"
 * inside the same method as "write the book" is how a card gets eaten on a duplicate — the roster comes
 * back unchanged, the code has already shrunk the stack, and there is nothing left to un-shrink.
 *
 * <h2>Quarantine is an exception, not a quiet success</h2>
 * A quarantined {@link Roster} is a save this build could not read; it is preserved verbatim on disk
 * and every accessor throws. {@code Roster.addOrIgnore} would technically succeed on it — it writes
 * into a view nobody can read — and the caller would consume the card for a character that was never
 * recorded. So both methods below refuse a quarantined book loudly. The item checks
 * {@link Roster#isQuarantined()} first and tells the player why; reaching this exception means a caller
 * forgot that check.
 */
@ApiStatus.Internal
public final class CardEnroll {

    /**
     * What a character card grants: level 1, no exp, affection 1.
     *
     * <p>Affection 1 rather than 0 because tier 1 is the baseline the spec describes, not an empty
     * state: a card that granted 0 would show a player at "no affection" while their character is
     * newly in the book.
     */
    public static final CharacterProgress INITIAL = new CharacterProgress(1, 0, 1);

    private CardEnroll() {
    }

    /**
     * {@return the roster with {@code characterId} enrolled, or {@code roster} itself when already owned}
     *
     * <p>Returning the <b>same instance</b> for a duplicate is deliberate rather than an implementation
     * detail of {@code addOrIgnore}: the caller's test for "did anything happen" is
     * {@code enrolled != before}, and an equal-but-different roster would make that comparison say
     * "consumed" for a card that did nothing.
     *
     * @param roster      the player's book, readable (see the class comment on quarantined books)
     * @param characterId the character the card grants
     * @throws IllegalStateException if the roster is quarantined
     */
    public static Roster enroll(Roster roster, ResourceLocation characterId) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(characterId, "characterId");
        requireReadable(roster);
        return roster.addOrIgnore(characterId, INITIAL);
    }

    /**
     * {@return whether the player already owns {@code characterId}}
     *
     * <p>Answered from the roster only — not from the character registry. "Owned" is a fact about the
     * player's book, and a character the running pack no longer defines is still owned (and must not
     * have a second card spent on it).
     *
     * @param roster      the player's book, readable
     * @param characterId the character the card grants
     * @throws IllegalStateException if the roster is quarantined
     */
    public static boolean alreadyEnrolled(Roster roster, ResourceLocation characterId) {
        Objects.requireNonNull(roster, "roster");
        Objects.requireNonNull(characterId, "characterId");
        requireReadable(roster);
        return roster.progressOf(characterId) != null;
    }

    /**
     * Fails before anything is written when the book cannot be read.
     *
     * <p>The message names the fix rather than the symptom, because the only caller that ever sees it
     * is a stack trace.
     */
    private static void requireReadable(Roster roster) {
        if (roster.isQuarantined()) {
            throw new IllegalStateException("astrum: the character book is quarantined (an unreadable "
                    + "save is being preserved verbatim on disk); refusing to enrol into a book that "
                    + "cannot be written. Check Roster.isQuarantined() before spending a card.");
        }
    }
}
