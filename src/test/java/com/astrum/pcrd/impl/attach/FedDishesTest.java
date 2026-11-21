package com.astrum.pcrd.impl.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * <b>「每游戏日一次」，and what a restart must not give back.</b>
 *
 * <h2>The defect</h2>
 * The dish allowance lived in a table owned by the server-session listener and was cleared on
 * {@code ServerStoppingEvent}. So the rule was really "once per game day <b>or</b> once per server run,
 * whichever comes first": feed the pantry, quit to the title screen, feed it again — same world day, no
 * boundary crossed. A single-player session that ends is the ordinary case, which made the allowance mostly
 * decorative.
 *
 * <p>The state is player data now ({@link FedDishes}, an attachment), so writing it down and reading it back
 * <b>is</b> the fix. These tests are that round trip, plus the two properties the old design got right and this
 * one must not lose: the map is bounded by the number of dishes (not by elapsed days), and a stored day that is
 * not today re-arms the allowance.
 */
class FedDishesTest {

    private static final ResourceLocation CAKE = ResourceLocation.parse("astrum:cake");
    private static final ResourceLocation PUDDING = ResourceLocation.parse("astrum:pudding");

    /** The whole fix in one assertion: the allowance survives being written and read back. */
    @Test
    void theAllowedDaySurvivesASaveAndLoad() {
        FedDishes before = FedDishes.empty();
        assertTrue(before.allow(CAKE, 42L), "the first feed of the day is allowed");

        FedDishes after = roundTrip(before);
        assertFalse(after.allow(CAKE, 42L),
                "after a save/load on the SAME day the allowance is spent — this is the restart case that used "
                        + "to hand it back");
        assertTrue(after.alreadyFed(CAKE, 42L), "and the record is readable without consuming anything");
    }

    /** A new day re-arms it, which is the whole of 「每游戏日一次」. */
    @Test
    void theNextDayReArmsTheAllowance() {
        FedDishes dishes = FedDishes.empty();
        assertTrue(dishes.allow(CAKE, 42L));
        assertFalse(dishes.allow(CAKE, 42L), "once per day");
        assertTrue(dishes.allow(CAKE, 43L), "and again tomorrow");
        assertEquals(1, dishes.size(), "one dish, one row — the map does not grow with time");
    }

    /** Two dishes are two independent allowances; four dishes are four, the reason the rule names the dish. */
    @Test
    void oneDishPerDayIsPerDishAndNotPerPlayer() {
        FedDishes dishes = FedDishes.empty();
        assertTrue(dishes.allow(CAKE, 7L));
        assertTrue(dishes.allow(PUDDING, 7L), "a second dish is its own allowance");
        assertFalse(dishes.allow(CAKE, 7L));
        assertEquals(2, dishes.size());
    }

    /**
     * <b>Bounded by the number of dishes, not by elapsed days.</b>
     *
     * <p>The old design objected to persisting because "a save must not grow a row per dish per day". That
     * objection was right, and storing only the <b>last</b> day per dish is what answers it: eleven years of
     * days over two dishes is still two rows.
     */
    @Test
    void theRecordDoesNotGrowWithTime() {
        FedDishes dishes = FedDishes.empty();
        for (long day = 1; day <= 4_000; day++) {
            dishes.allow(CAKE, day);
            dishes.allow(PUDDING, day);
        }
        assertEquals(2, dishes.size(), "two dishes, two rows, however long the world runs");
        assertEquals(2, roundTrip(dishes).size(), "and nothing accumulates through the codec either");
    }

    /** An empty record is what a player who has never fed anything has, and it encodes as nothing at all. */
    @Test
    void anEmptyRecordIsValidAndEncodesToNoEntries() {
        FedDishes empty = roundTrip(FedDishes.empty());
        assertEquals(0, empty.size());
        assertFalse(empty.alreadyFed(CAKE, 1L));
        assertTrue(empty.allow(CAKE, 1L));
    }

    /** Round trip through the attachment codec, which is exactly what the save does. */
    private static FedDishes roundTrip(FedDishes dishes) {
        var encoded = FedDishes.CODEC.encodeStart(JsonOps.INSTANCE, dishes).getOrThrow();
        return FedDishes.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
    }
}
