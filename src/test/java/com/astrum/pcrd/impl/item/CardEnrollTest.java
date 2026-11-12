package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Roster;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three answers a character card can get, as pure values: <b>new</b>, <b>already owned</b>, and
 * <b>the book cannot be read</b>.
 *
 * <h2>Why the "already owned" case is the one worth pinning</h2>
 * The card is consumed by the caller, not by {@link CardEnroll}, so the whole point of the contract
 * is that the return value tells the caller apart "the roster changed" from "nothing happened". A
 * second card of the same character that reset level 9 back to level 1 would destroy earned progress
 * while looking like a successful enrolment; a card that is consumed on a no-op destroys the item
 * for nothing. Both are the same defect — the roster not being returned unchanged — so it is tested
 * from both directions: value equality, and the entry itself keeping its numbers.
 */
class CardEnrollTest {

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");

    @Test
    void enrollingNewCharacterAddsLevelOneEntry() {
        var r = CardEnroll.enroll(new Roster(Map.of(), 1), DJEETA);
        assertEquals(Optional.of(new CharacterProgress(1, 0, 1)),
                Optional.ofNullable(r.entries().get(DJEETA)));
    }

    @Test
    void enrollingExistingCharacterDoesNotResetProgress() {
        var existing = new Roster(Map.of(DJEETA, new CharacterProgress(9, 50, 3)), 1);
        assertEquals(existing, CardEnroll.enroll(existing, DJEETA));
    }

    @Test
    void aNewEnrolmentLeavesEveryOtherCharacterAlone() {
        ResourceLocation other = InternalIds.id("other");
        var before = new Roster(Map.of(other, new CharacterProgress(4, 7, 2)), 1);
        var after = CardEnroll.enroll(before, DJEETA);
        assertEquals(new CharacterProgress(4, 7, 2), after.progressOf(other));
        assertEquals(2, after.entries().size());
        assertEquals(CardEnroll.INITIAL, after.progressOf(DJEETA));
    }

    @Test
    void alreadyEnrolledAnswersBeforeAndAfter() {
        var empty = new Roster(Map.of(), 1);
        assertFalse(CardEnroll.alreadyEnrolled(empty, DJEETA),
                "a character nobody owns is not owned");
        assertTrue(CardEnroll.alreadyEnrolled(CardEnroll.enroll(empty, DJEETA), DJEETA),
                "after enrolling, the same card must be recognised as a duplicate");
    }

    /**
     * A quarantined roster stands in for a save this build could not read: its {@code entries()} throw.
     * Enrolling anyway would write into a view nobody can read, and the caller would then consume the
     * card for nothing — so the refusal must be an exception, not a quiet success.
     */
    @Test
    void aQuarantinedBookIsRefusedInsteadOfWritten() {
        var quarantined = Roster.quarantined(new CompoundTag());
        assertThrows(IllegalStateException.class, () -> CardEnroll.enroll(quarantined, DJEETA));
        assertThrows(IllegalStateException.class, () -> CardEnroll.alreadyEnrolled(quarantined, DJEETA));
    }

    @Test
    void theInitialProgressIsLevelOneWithOneAffection() {
        assertEquals(1, CardEnroll.INITIAL.level(), "a freshly enrolled character starts at level 1");
        assertEquals(0, CardEnroll.INITIAL.exp());
        assertEquals(1, CardEnroll.INITIAL.affection(),
                "affection starts at 1, not 0: tier 1 is the baseline the spec describes");
    }
}
