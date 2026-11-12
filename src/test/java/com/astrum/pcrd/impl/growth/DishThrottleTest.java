package com.astrum.pcrd.impl.growth;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每物品每天上限 1 次 (spec §6.2) as a value with a clock.
 *
 * <h2>What "one point only" means, and why the day is an argument</h2>
 * The throttle hands out the allowance; the point of affection is added by the caller
 * ({@code DishFeed} → {@link AffectionKernel#clampAffection}). So "same item + same day → one point
 * only" is exactly "the second attempt on that day is refused", and this class is the whole of that
 * rule: no level, no player, no clock, which is why it can be tested at all.
 *
 * <p>Note what is <b>not</b> keyed: the character. A dish feeds <em>the party</em>, so four dishes
 * today is four affection for everyone — the allowance is per dish, not per character.
 */
class DishThrottleTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final ResourceLocation SUGAR = ResourceLocation.withDefaultNamespace("sugar");
    private static final ResourceLocation CAKE = ResourceLocation.withDefaultNamespace("cake");
    private static final long DAY = 42L;

    @Test
    void theSameDishOnTheSameDayIsAllowedOnce() {
        DishThrottle throttle = new DishThrottle();
        assertTrue(throttle.allow(PLAYER, SUGAR, DAY), "the first sugar of the day is the whole point");
        assertFalse(throttle.allow(PLAYER, SUGAR, DAY),
                "a second sugar on the same day must not raise affection again");
        assertFalse(throttle.allow(PLAYER, SUGAR, DAY), "and it stays refused for the rest of the day");
        assertEquals(1, throttle.size());
    }

    @Test
    void aNewDayAllowsItAgain() {
        DishThrottle throttle = new DishThrottle();
        assertTrue(throttle.allow(PLAYER, SUGAR, DAY));
        assertFalse(throttle.allow(PLAYER, SUGAR, DAY));
        assertTrue(throttle.allow(PLAYER, SUGAR, DAY + 1), "the allowance is per day, so tomorrow is free");
        assertFalse(throttle.allow(PLAYER, SUGAR, DAY + 1));
    }

    /** The stored day does not need an expiry entry rewritten: a different day IS the expiry. */
    @Test
    void theTableStaysBoundedAcrossManyDays() {
        DishThrottle throttle = new DishThrottle();
        for (long day = 0; day < 100; day++) {
            throttle.allow(PLAYER, SUGAR, day);
        }
        assertEquals(1, throttle.size(), "one (player, dish) pair however many days pass");
    }

    @Test
    void aDifferentDishIsAllowedOnTheSameDay() {
        DishThrottle throttle = new DishThrottle();
        assertTrue(throttle.allow(PLAYER, SUGAR, DAY));
        assertTrue(throttle.allow(PLAYER, CAKE, DAY), "the allowance is per dish, not per player per day");
        assertEquals(2, throttle.size());
    }

    @Test
    void andSoIsTheSameDishForADifferentPlayer() {
        DishThrottle throttle = new DishThrottle();
        assertTrue(throttle.allow(PLAYER, SUGAR, DAY));
        assertTrue(throttle.allow(OTHER_PLAYER, SUGAR, DAY));
        assertFalse(throttle.allow(PLAYER, SUGAR, DAY), "the refusals must stay per player");
    }

    /** The read-only half must not spend the allowance; it answers the question without changing it. */
    @Test
    void askingWithoutConsumingLeavesTheAllowanceIntact() {
        DishThrottle throttle = new DishThrottle();
        assertFalse(throttle.alreadyFed(PLAYER, SUGAR, DAY));
        assertTrue(throttle.allow(PLAYER, SUGAR, DAY));
        assertTrue(throttle.alreadyFed(PLAYER, SUGAR, DAY));
        assertTrue(throttle.alreadyFed(PLAYER, SUGAR, DAY), "asking twice is still just asking");
        assertEquals(1, throttle.size());
        assertFalse(throttle.allow(PLAYER, SUGAR, DAY));
    }

    /** {@code clear()} is the session boundary: a stopped server must not remember yesterday's sugar. */
    @Test
    void clearingForgetsEveryAllowance() {
        DishThrottle throttle = new DishThrottle();
        throttle.allow(PLAYER, SUGAR, DAY);
        throttle.allow(PLAYER, CAKE, DAY);
        throttle.clear();
        assertEquals(0, throttle.size());
        assertFalse(throttle.alreadyFed(PLAYER, SUGAR, DAY));
        assertTrue(throttle.allow(PLAYER, SUGAR, DAY));
    }
}
