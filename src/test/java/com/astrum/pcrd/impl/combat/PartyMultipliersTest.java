package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyMultipliersTest {

    static final ResourceLocation A = InternalIds.id("a"), B = InternalIds.id("b"),
            C = InternalIds.id("c"), D = InternalIds.id("d"), E = InternalIds.id("e");

    static final Map<ResourceLocation, Dimension> DIMS = Map.of(
            A, Dimension.OUTPUT, B, Dimension.SURVIVAL, C, Dimension.OUTPUT,
            D, Dimension.CHARGE, E, Dimension.OUTPUT);

    static final List<Optional<ResourceLocation>> PARTY = List.of(
            Optional.of(A), Optional.of(B), Optional.of(C), Optional.of(D), Optional.of(E));

    /**
     * The slot table from spec §5.2, asserted slot by slot: the first of a dimension keeps 1.00,
     * the second takes {@code base}, the third {@code base²}, and a different dimension restarts at
     * 1.00 — rank is per dimension, not per party position.
     */
    @Test void matchesSpecExample() {
        var m = PartyMultipliers.all(PARTY, DIMS, 0.7, true);
        assertEquals(1.00, m[0], 1e-9);   // output 第 1 名
        assertEquals(1.00, m[1], 1e-9);   // survival 不同维度
        assertEquals(0.70, m[2], 1e-9);   // output 第 2 名
        assertEquals(1.00, m[3], 1e-9);   // charge 不同维度
        assertEquals(0.49, m[4], 1e-9);   // output 第 3 名
    }

    /**
     * An unused slot is not a character: it must not consume a rank, or the wheel position of the
     * characters behind it would silently matter more than which dimension they are.
     */
    @Test void emptySlotsAreSkippedNotCounted() {
        var party = List.of(Optional.<ResourceLocation>empty(), Optional.of(A), Optional.<ResourceLocation>empty(),
                Optional.<ResourceLocation>empty(), Optional.of(E));
        var m = PartyMultipliers.all(party, DIMS, 0.7, true);
        assertEquals(1.0, m[0], 1e-9);
        assertEquals(1.0, m[1], 1e-9);   // A is the first output, despite sitting in slot 1
        assertEquals(0.7, m[4], 1e-9);   // E is the second output
    }

    /**
     * Datapack content can be broken: the character registry may not know a party slot's id at all.
     * A missing dimension means "this character cannot be ranked", which is neutral — not a
     * multiplier of zero and not an exception thrown into a damage event.
     */
    @Test void unknownDimensionInDatapackFallsBackToFullMultiplier() {
        var m = PartyMultipliers.all(PARTY, Map.of(), 0.7, true);
        for (double v : m) assertEquals(1.0, v, 1e-9);

        // Unknown characters must not join a shared bucket either: if an unknown id were defaulted
        // to some dimension (say output), the three unknown slots above C would push C to rank 2
        // (0.49). They are skipped instead, so C is the first KNOWN output and E the second.
        var partial = PartyMultipliers.all(PARTY, Map.of(C, Dimension.OUTPUT, E, Dimension.OUTPUT), 0.7, true);
        assertEquals(1.0, partial[0], 1e-9, "A is unknown");
        assertEquals(1.0, partial[2], 1e-9, "C is the first KNOWN output, so rank 0");
        assertEquals(0.7, partial[4], 1e-9, "E is the second KNOWN output, so rank 1");
    }

    /** The operator switch: off is "every slot at full strength", not "every slot at the floor". */
    @Test void disabledReturnsNeutralForEverySlot() {
        for (double v : PartyMultipliers.all(PARTY, DIMS, 0.7, false)) assertEquals(1.0, v, 1e-9);
    }

    /** One slot at a time, for a caller that answers a single event: the same table, no buffer. */
    @Test void forSlotResolvesASingleSlot() {
        assertEquals(1.00, PartyMultipliers.forSlot(PARTY, DIMS, 0, 0.7, true), 1e-9);
        assertEquals(0.70, PartyMultipliers.forSlot(PARTY, DIMS, 2, 0.7, true), 1e-9);
        assertEquals(0.49, PartyMultipliers.forSlot(PARTY, DIMS, 4, 0.7, true), 1e-9);
        assertEquals(1.00, PartyMultipliers.forSlot(PARTY, DIMS, 4, 0.7, false), 1e-9);
    }

    /**
     * Data degrades, call sites do not. A slot index outside the wheel is a programming error and
     * must be loud, exactly as {@code Party.with} is: silently answering a number would hide the
     * bug behind a plausible multiplier for the rest of the session.
     */
    @Test void forSlotRejectsAnIndexOutsideTheWheel() {
        assertThrows(IllegalArgumentException.class, () -> PartyMultipliers.forSlot(PARTY, DIMS, -1, 0.7, true));
        assertThrows(IllegalArgumentException.class,
                () -> PartyMultipliers.forSlot(PARTY, DIMS, PartyMultipliers.SLOTS, 0.7, true));
    }

    /**
     * The buffer is the wheel: exactly five multipliers whatever the caller hands over, so a short
     * hand-edited list cannot shift the array and a too-long one cannot overflow it.
     */
    @Test void allAlwaysAnswersOneMultiplierPerWheelSlot() {
        assertEquals(Party.SLOTS, PartyMultipliers.SLOTS, "the kernel's slot count must track Party.SLOTS");
        assertEquals(PartyMultipliers.SLOTS, PartyMultipliers.all(List.of(), DIMS, 0.7, true).length);
        assertEquals(PartyMultipliers.SLOTS, PartyMultipliers.all(PARTY, DIMS, 0.7, true).length);
        assertEquals(PartyMultipliers.SLOTS, PartyMultipliers.all(List.of(Optional.of(A)), DIMS, 0.7, true).length);
        var tooLong = new ArrayList<Optional<ResourceLocation>>(PARTY);
        tooLong.add(Optional.of(InternalIds.id("f")));
        tooLong.add(Optional.of(InternalIds.id("g")));
        assertEquals(PartyMultipliers.SLOTS, PartyMultipliers.all(tooLong, DIMS, 0.7, true).length);
    }

    /**
     * The quarantine trap, seen from the combat side.
     *
     * <p>{@link Roster} is quarantine-aware: a save written by a build this one cannot parse keeps
     * its raw tag, and {@code entries()} / {@code progressOf()} throw while it does. Combat will be
     * the first subsystem to read a roster, so this pins the caller pattern — probe
     * {@link Roster#isQuarantined()} first and answer {@link PartyMultipliers#neutral()}, i.e. "no
     * characters this session".
     *
     * <p>A player with an unreadable book must still be able to fight at full strength, and an
     * exception raised here would escape into a damage event. So the degraded answer is a value,
     * never a throw.
     */
    @Test void aQuarantinedRosterDegradesToNeutralMultipliersWithoutThrowing() {
        // What Roster.SERIALIZER.read returns for an unknown dataVersion. Built directly so this
        // test does not emit the serializer's "unreadable roster" ERROR line.
        var roster = Roster.quarantined(new CompoundTag());
        assertTrue(roster.isQuarantined(), "isQuarantined() is the only safe probe");

        // Why the guard is mandatory: everything a caller would naturally read throws.
        assertThrows(IllegalStateException.class, roster::entries);
        assertThrows(IllegalStateException.class, () -> roster.progressOf(A));

        // The reference caller pattern, which is what combat must look like.
        double[] degraded = combatMultipliers(roster, PARTY, DIMS, 0.7, true);
        for (double v : degraded) assertEquals(1.0, v, 1e-9);

        // The same degradation expressed as the data an unreadable roster leaves behind: no
        // characters on the wheel at all.
        for (double v : combatMultipliers(roster, Party.empty().slots(), Map.of(), 0.7, true)) {
            assertEquals(1.0, v, 1e-9);
        }
        // And a quarantined roster does not break the equipped wheel either: the dimensions come
        // from datapack definitions, not from the book.
        for (double v : combatMultipliers(roster, PARTY, DIMS, 0.7, false)) assertEquals(1.0, v, 1e-9);

        // A readable roster still resolves the real table, so the guard is not just "always 1.0".
        var live = new Roster(Map.of(), Roster.CURRENT_DATA_VERSION);
        assertEquals(0.49, combatMultipliers(live, PARTY, DIMS, 0.7, true)[4], 1e-9);
    }

    /**
     * The caller shape the kernel forces: a quarantined (or absent) roster is neutral before any
     * dimension lookup happens, so no quarantined accessor can ever be reached.
     */
    private static double[] combatMultipliers(Roster roster, List<Optional<ResourceLocation>> party,
            Map<ResourceLocation, Dimension> dims, double base, boolean enabled) {
        if (roster == null || roster.isQuarantined()) {
            return PartyMultipliers.neutral();
        }
        return PartyMultipliers.all(party, dims, base, enabled);
    }
}
