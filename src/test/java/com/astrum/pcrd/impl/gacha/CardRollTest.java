package com.astrum.pcrd.impl.gacha;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The lottery's probability model, asserted as numbers.</b>
 *
 * <h2>What the human asked for, and the reading this pins</h2>
 * 「所有角色平分100%」 + 「已经有的角色也能抽到」 + 「已有的角色权重要低很多」. The first and third cannot both be
 * literally true, so the model is: every character is always in the pool with the same base weight, and being
 * owned multiplies that weight down ({@link CardRoll#UNOWNED_WEIGHT} vs {@link CardRoll#OWNED_WEIGHT}).
 * Before anything is owned the pool is flat — which is the first sentence read exactly — and nothing is ever
 * removed from the pool, which is the second.
 *
 * <h2>How the numbers are asserted</h2>
 * Through {@link CardRoll#chanceOf}, which is the same weight table the draw uses: a test that rolled ten
 * thousand times and compared frequencies would be flaky for the rare cases (and would need a tolerance
 * argument about how flaky is acceptable). Asserting the probabilities directly is exact, and the sampling is
 * checked separately — one test walks the whole ticket space instead of sampling it, which proves the draw
 * agrees with the table for <em>every</em> possible ticket.
 */
class CardRollTest {

    private static final ResourceLocation DJEETA = id("djeeta");
    private static final ResourceLocation KOKKORO = id("kokkoro");
    private static final ResourceLocation PECORINE = id("pecorine");
    private static final ResourceLocation KYARU = id("kyaru");

    private static final List<ResourceLocation> FOUR = List.of(DJEETA, KOKKORO, PECORINE, KYARU);

    /**
     * <b>With nothing owned, all characters share 100% equally.</b>
     *
     * <p>This is 「所有角色平分100%」 taken literally, and it is the state a new player is in — so it is the case
     * the rule has to get right before any de-weighting applies.
     */
    @Test
    void anEmptyBookGivesEveryCharacterAnEqualShare() {
        for (int characters = 1; characters <= 4; characters++) {
            List<ResourceLocation> pool = FOUR.subList(0, characters);
            double each = 1.0 / characters;
            for (ResourceLocation character : pool) {
                assertEquals(each, CardRoll.chanceOf(character, pool, Set.of()), 1e-9,
                        "with " + characters + " character(s) and nothing owned, " + character
                                + " must have 1/" + characters);
            }
        }
        // Four characters, exactly 25% each, and the shares sum to 1 — the "100%" half of the sentence.
        assertEquals(1.0, FOUR.stream().mapToDouble(c -> CardRoll.chanceOf(c, FOUR, Set.of())).sum(),
                1e-9, "the shares must sum to 100%");
    }

    /**
     * <b>A character added later keeps the rule: N characters, 1/N each.</b>
     *
     * <p>The human's 「添加角色后规则不变」, as arithmetic. Nothing here is a pot of fixed size, so a fifth
     * character takes 20% and the other four fall to 20% each on their own.
     */
    @Test
    void addingACharacterOnlyRenormalises() {
        List<ResourceLocation> five = List.of(DJEETA, KOKKORO, PECORINE, KYARU, id("mahiru"));
        for (ResourceLocation character : five) {
            assertEquals(0.2, CardRoll.chanceOf(character, five, Set.of()), 1e-9,
                    character + " must be one fifth of an unowned five-character pool");
        }
    }

    /**
     * <b>Owning a character de-weights her but never removes her.</b>
     *
     * <p>The two halves of his instruction that sound contradictory, asserted together: the owned character is
     * still drawable (her chance is above zero — 「已经有的角色也能抽到」) and much less likely than the others
     * (「已有的角色权重要低很多」). The measured values for four characters with one owned are in
     * {@link CardRoll}'s javadoc: 4.7% against 31.8%.
     */
    @Test
    void anOwnedCharacterIsStillDrawableButMuchLessLikely() {
        Set<ResourceLocation> owned = Set.of(DJEETA);

        double ownedChance = CardRoll.chanceOf(DJEETA, FOUR, owned);
        double unownedChance = CardRoll.chanceOf(KOKKORO, FOUR, owned);

        assertTrue(ownedChance > 0.0,
                "an owned character must stay rollable (「已经有的角色也能抽到」): a zero weight would silently "
                        + "implement 'owned characters are not rollable'");
        assertTrue(ownedChance < unownedChance / 5.0,
                "owned " + ownedChance + " must be much lower than unowned " + unownedChance);

        // 15 : 100 : 100 : 100 — the exact distribution, so a change to either constant is a visible decision.
        assertEquals(CardRoll.OWNED_WEIGHT / (CardRoll.OWNED_WEIGHT + 3.0 * CardRoll.UNOWNED_WEIGHT),
                ownedChance, 1e-9, "one owned of four");
        assertEquals(CardRoll.UNOWNED_WEIGHT / (CardRoll.OWNED_WEIGHT + 3.0 * CardRoll.UNOWNED_WEIGHT),
                unownedChance, 1e-9, "each of the three unowned");
        assertEquals(1.0, FOUR.stream().mapToDouble(c -> CardRoll.chanceOf(c, FOUR, owned)).sum(), 1e-9,
                "the de-weighted shares must still sum to 100%");
    }

    /**
     * The three states the human's design has to keep live: nothing left to get, nearly done, and just started.
     *
     * <p>With three of four owned the remaining one is still the most likely single outcome but not a certainty
     * (61% / 39%) — so duplicates keep arriving and the token route stays reachable, while the book still
     * fills. A harsher ratio would make the last character a near-certainty; a flatter one would make the book
     * creep. Asserting the shape here is what would catch a careless retune.
     */
    @Test
    void theLastUnownedCharacterIsLikelyButNotCertain() {
        Set<ResourceLocation> owned = Set.of(DJEETA, KOKKORO, PECORINE);
        double last = CardRoll.chanceOf(KYARU, FOUR, owned);
        assertTrue(last > 0.5, "the only character left should be the most likely single outcome: " + last);
        assertTrue(last < 0.9, "…but not a near-certainty, or the tokens would never be reachable: " + last);

        double duplicate = 1.0 - last;
        assertTrue(duplicate > 0.3, "duplicates must still arrive with three of four owned: " + duplicate);
    }

    /**
     * <b>Every ticket in the space draws the character the table says it should.</b>
     *
     * <p>The sampling check, done exhaustively instead of statistically: the ticket is an integer in
     * {@code [0, total)}, so a fake {@link RandomSource} can walk all of them and the resulting histogram must
     * equal the weights exactly. That is a stronger statement than "the frequencies looked right after 10,000
     * draws", and it is deterministic.
     */
    @Test
    void everyTicketDrawsTheCharacterItsWeightSaysItDoes() {
        Set<ResourceLocation> owned = Set.of(DJEETA);
        // 15 (owned) + 100 + 100 + 100 (unowned) = 315
        long total = CardRoll.OWNED_WEIGHT + 3L * CardRoll.UNOWNED_WEIGHT;
        assertEquals(315, total, "sanity: the ticket space of a four-character pool with one owned");

        java.util.Map<ResourceLocation, Integer> histogram = new java.util.HashMap<>();
        for (int ticket = 0; ticket < total; ticket++) {
            Optional<ResourceLocation> drawn = CardRoll.roll(FOUR, owned, alwaysTicket(ticket));
            assertTrue(drawn.isPresent(), "every ticket in range must draw somebody");
            histogram.merge(drawn.get(), 1, Integer::sum);
        }

        assertEquals(CardRoll.OWNED_WEIGHT, histogram.get(DJEETA), "the owned character's ticket count");
        assertEquals(CardRoll.UNOWNED_WEIGHT, histogram.get(KOKKORO), "an unowned character's ticket count");
        assertEquals(CardRoll.UNOWNED_WEIGHT, histogram.get(PECORINE), "an unowned character's ticket count");
        assertEquals(CardRoll.UNOWNED_WEIGHT, histogram.get(KYARU), "an unowned character's ticket count");
        assertEquals(total, histogram.values().stream().mapToInt(Integer::intValue).sum(),
                "the histogram must account for every ticket exactly once");
    }

    /**
     * <b>A batch re-reads ownership between cards, so five cards are five independent rolls.</b>
     *
     * <p>This is the shift-craft requirement: 「一次合成多张，每张独立抽」. The failure it guards against is
     * rolling a whole stack against one snapshot, which would make the batch behave as if only the first card
     * had been drawn. The observable difference is in the ticket space: after the first card is added to the
     * owned set, the next draw's total grows by {@code UNOWNED_WEIGHT - OWNED_WEIGHT} per newly-owned character
     * — so a batch of five from a four-character pool cannot be five identical draws taken from one unchanged
     * distribution.
     */
    /**
     * <b>A batch re-reads ownership between cards, so five cards are five independent rolls.</b>
     *
     * <p>This is the shift-craft requirement: 「一次合成多张，每张独立抽」. The failure it guards against is rolling
     * a whole stack against one snapshot, which would make the batch behave as if only the first card had been
     * drawn.
     *
     * <h2>The discriminator, worked out rather than hoped for</h2>
     * Two characters, nothing owned, and a source scripted to answer ticket 199 twice:
     *
     * <ul>
     *   <li><b>Card 1</b> — the pool is {@code djeeta 100, kokkoro 100}, so 200 tickets; 199 lands on
     *       {@code kokkoro}, the second candidate.</li>
     *   <li><b>Card 2, ownership fed back</b> — {@code kokkoro} is now owned, so the pool is
     *       {@code djeeta 100, kokkoro 15}, 115 tickets, and {@code 199 % 115 = 84} lands on {@code djeeta}.</li>
     *   <li><b>Card 2, stale snapshot</b> — the pool would still be 200 and 199 would land on
     *       {@code kokkoro} again.</li>
     * </ul>
     *
     * So "the first two cards differ" is exactly the assertion that separates the two implementations, and it is
     * arithmetic rather than statistics.
     */
    @Test
    void aBatchRollsEachCardAgainstTheOwnershipItHasAtThatMoment() {
        List<ResourceLocation> pool = List.of(DJEETA, KOKKORO);
        List<ResourceLocation> batch = CardRoll.rollBatch(pool, Set.of(), 2, scriptedTickets(199, 199));

        assertEquals(2, batch.size(), "two cards asked for, two cards produced");
        assertEquals(KOKKORO, batch.get(0), "ticket 199 of a flat 200 is the second candidate");
        assertEquals(DJEETA, batch.get(1),
                "the second card must be rolled against the ownership the first card created: ticket 199 of "
                        + "the new 115-ticket space is 84, which is djeeta. Getting kokkoro twice means one "
                        + "snapshot was reused: " + batch);
        assertNotEquals(batch.get(0), batch.get(1), "the discriminating pair");
    }

    /**
     * A batch of more cards than the pool has characters still produces that many cards.
     *
     * <p>Duplicates are legal by design (「已经有的角色也能抽到」), so "more cards than characters" must produce
     * more cards rather than stopping.
     */
    @Test
    void aBatchLargerThanThePoolStillProducesEveryCard() {
        List<ResourceLocation> pool = List.of(DJEETA, KOKKORO);
        List<ResourceLocation> batch = CardRoll.rollBatch(pool, Set.of(), 7, scriptedTickets(0));
        assertEquals(7, batch.size(), "seven cards asked for, seven produced");
        for (ResourceLocation drawn : batch) {
            assertTrue(pool.contains(drawn), "every card must name a real character: " + drawn);
        }
    }

    /**
     * An empty pool answers empty rather than throwing — a broken datapack must not cost the ingredients.
     */
    @Test
    void anEmptyPoolAnswersEmpty() {
        assertTrue(CardRoll.roll(List.of(), Set.of(), alwaysTicket(0)).isEmpty());
        assertTrue(CardRoll.roll(null, Set.of(), alwaysTicket(0)).isEmpty());
        assertTrue(CardRoll.rollBatch(List.of(), Set.of(), 3, alwaysTicket(0)).isEmpty());
        assertEquals(0, CardRoll.rollBatch(List.of(), Set.of(), 0, alwaysTicket(0)).size());
        assertEquals(0.0, CardRoll.chanceOf(DJEETA, List.of(), Set.of()));
    }

    /**
     * A character not in the pool has no chance, and a duplicate in the pool does not get a double share.
     *
     * <p>The second is the one bug in a lottery nobody would notice by playing: `pool` is a public parameter,
     * and a caller that concatenated two sources would silently double that character's weight.
     */
    @Test
    void thePoolIsNormalisedAndDuplicatesDoNotDoubleAWeight() {
        assertEquals(0.0, CardRoll.chanceOf(id("nobody"), FOUR, Set.of()),
                "a character outside the pool cannot be drawn");

        List<ResourceLocation> withDuplicate = List.of(DJEETA, DJEETA, KOKKORO, PECORINE, KYARU);
        assertEquals(0.25, CardRoll.chanceOf(DJEETA, withDuplicate, Set.of()), 1e-9,
                "a repeated entry must not get a doubled share");
        assertEquals(1.0, withDuplicate.stream().distinct()
                .mapToDouble(c -> CardRoll.chanceOf(c, withDuplicate, Set.of())).sum(), 1e-9);
    }

    /** {@return the weight of an unowned character, which is the documented base} */
    @Test
    void theWeightsAreTheDocumentedConstants() {
        assertEquals(100, CardRoll.UNOWNED_WEIGHT, "「所有角色平分100%」 is the base weight");
        assertEquals(15, CardRoll.OWNED_WEIGHT, "「已有的角色权重要低很多」 — about a sixth");
        assertTrue(CardRoll.OWNED_WEIGHT > 0, "never zero: owned characters stay rollable");
        assertTrue(CardRoll.OWNED_WEIGHT < CardRoll.UNOWNED_WEIGHT, "owned must be the lower weight");
    }

    // -----------------------------------------------------------------------------------------
    // A RandomSource that answers a chosen ticket
    // -----------------------------------------------------------------------------------------

    /**
     * {@return a source whose every {@code nextInt(bound)} answers {@code ticket}}
     *
     * <p>Overrides only the two methods {@link CardRoll} uses. The rest throw: this fake exists to make the
     * draw deterministic for one assertion, and a method nobody expected to be called silently returning 0
     * would turn a wrong test into a broken one.
     *
     * @param ticket the answer
     */
    private static RandomSource alwaysTicket(int ticket) {
        return (RandomSource) java.lang.reflect.Proxy.newProxyInstance(
                RandomSource.class.getClassLoader(), new Class<?>[] {RandomSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "nextInt" -> args == null || args.length == 0 ? ticket : ticket % (int) args[0];
                    case "nextLong" -> (long) ticket;
                    default -> throw new UnsupportedOperationException(
                            "this fake answers nextInt only, not " + method.getName());
                });
    }

    /** {@return a source whose {@code nextInt(bound)} answers {@code bound - 1}, the last ticket} */
    private static RandomSource scriptedTickets(int... tickets) {
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        return (RandomSource) java.lang.reflect.Proxy.newProxyInstance(
                RandomSource.class.getClassLoader(), new Class<?>[] {RandomSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("nextInt")) {
                        throw new UnsupportedOperationException("this fake answers nextInt only");
                    }
                    int bound = args == null || args.length == 0 ? Integer.MAX_VALUE : (int) args[0];
                    // Past the script, keep answering the last value: a test that over-asks should still be
                    // deterministic rather than wrapping into an unrelated ticket.
                    int index = Math.min(next.getAndIncrement(), tickets.length - 1);
                    return tickets[index] % bound;
                });
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("astrum", path);
    }

    // -----------------------------------------------------------------------------------------
    // The two modes: the ordinary lottery, and 「优先未拥有」 for the hundred crystals
    // -----------------------------------------------------------------------------------------

    /**
     * <b>{@code GUARANTEED_PREFER_UNOWNED} never returns an owned character while one is unowned.</b>
     *
     * <p>The human's rule for the crystals: 「直接随机获取一个没有的角色」, and he called it a better roll than
     * crafting an ordinary card. So this is asserted <b>exhaustively over the whole ticket space</b> rather
     * than by sampling: for every possible ticket the draw could receive, the answer must be one of the
     * unowned characters. A weight of zero makes that true by construction, and this proves the construction
     * holds at the boundaries (ticket 0 and ticket total-1), which is where an off-by-one would show.
     *
     * <p>Two owned of four, so the unowned pool has two members and a bad implementation that leaked an owned
     * character would be caught rather than coincidentally right.
     */
    @Test
    void theGuaranteedModeNeverReturnsAnOwnedCharacterWhileOneIsUnowned() {
        Set<ResourceLocation> owned = Set.of(DJEETA, KOKKORO);
        List<ResourceLocation> unowned = List.of(PECORINE, KYARU);

        for (int ticket = 0; ticket < 2 * CardRoll.UNOWNED_WEIGHT; ticket++) {
            Optional<ResourceLocation> drawn = CardRoll.roll(CardRoll.Mode.GUARANTEED_PREFER_UNOWNED,
                    FOUR, owned, alwaysTicket(ticket));
            assertTrue(drawn.isPresent(), "a non-empty pool must always answer");
            assertTrue(unowned.contains(drawn.get()),
                    "ticket " + ticket + " drew " + drawn.get() + ", which the player already owns — the "
                            + "guaranteed mode exists to prevent exactly this");
        }

        // And the two unowned characters share it equally: uniform among them, not weighted by anything else.
        assertEquals(0.5, CardRoll.weightUnder(CardRoll.Mode.GUARANTEED_PREFER_UNOWNED, PECORINE, owned)
                / (double) (2 * CardRoll.UNOWNED_WEIGHT), 1e-12, "uniform among the unowned");
        assertEquals(0, CardRoll.weightUnder(CardRoll.Mode.GUARANTEED_PREFER_UNOWNED, DJEETA, owned),
                "an owned character carries no ticket at all in this mode");
    }

    /** With one character left to collect, that character is the certain answer. */
    @Test
    void withOneCharacterLeftTheGuaranteedModeReturnsThatOne() {
        Set<ResourceLocation> owned = Set.of(DJEETA, KOKKORO, PECORINE);
        for (int ticket = 0; ticket < CardRoll.UNOWNED_WEIGHT; ticket++) {
            assertEquals(Optional.of(KYARU), CardRoll.roll(CardRoll.Mode.GUARANTEED_PREFER_UNOWNED,
                    FOUR, owned, alwaysTicket(ticket)), "ticket " + ticket);
        }
        assertEquals(List.of(KYARU), CardRoll.unownedIn(FOUR, owned),
                "the eligible pool is exactly the one character left");
    }

    /**
     * <b>Owning everything falls back to the ordinary lottery rather than refusing.</b>
     *
     * <p>Rule B, and the reason it is a fallback: a completed collection must not leave the hundred crystals
     * unspendable. The fallback is <b>not</b> an empty answer, and it is not a special case at the call site —
     * it is inside {@link CardRoll#roll}, so the item cannot forget it.
     *
     * <p>Asserted by the distribution rather than by "something came back": the answer must follow the
     * ordinary weights (all four equal, 25% each, since all are owned), which is what distinguishes a real
     * fallback from, say, always answering the first character.
     */
    @Test
    void owningEverythingFallsBackToTheOrdinaryLottery() {
        Set<ResourceLocation> all = Set.of(DJEETA, KOKKORO, PECORINE, KYARU);
        assertEquals(List.of(), CardRoll.unownedIn(FOUR, all), "nothing is eligible in the strict sense");

        java.util.Set<ResourceLocation> seen = new java.util.HashSet<>();
        for (int ticket = 0; ticket < 4 * CardRoll.OWNED_WEIGHT; ticket++) {
            Optional<ResourceLocation> drawn = CardRoll.roll(CardRoll.Mode.GUARANTEED_PREFER_UNOWNED,
                    FOUR, all, alwaysTicket(ticket));
            assertTrue(drawn.isPresent(),
                    "owning everything must still produce a card (rule B), not an empty answer");
            seen.add(drawn.get());
        }
        assertEquals(new java.util.HashSet<>(FOUR), seen,
                "the fallback must reach every character, i.e. it really is the ordinary lottery and not "
                        + "a fixed answer");
    }

    /** The ordinary mode is unchanged: an owned character is still drawable, at its de-weighted odds. */
    @Test
    void theOrdinaryModeStillAllowsDuplicates() {
        Set<ResourceLocation> owned = Set.of(DJEETA);
        assertEquals(CardRoll.OWNED_WEIGHT, CardRoll.weightUnder(CardRoll.Mode.ORDINARY, DJEETA, owned));
        assertEquals(CardRoll.UNOWNED_WEIGHT,
                CardRoll.weightUnder(CardRoll.Mode.ORDINARY, KYARU, owned));
        assertEquals(CardRoll.OWNED_WEIGHT, CardRoll.weightOf(DJEETA, owned),
                "the no-mode overload must keep the old answer, or crafting changes behaviour silently");
    }

    /** An empty pool is the one case that answers empty, in both modes. */
    @Test
    void anEmptyPoolAnswersEmptyInBothModes() {
        assertEquals(Optional.empty(),
                CardRoll.roll(CardRoll.Mode.ORDINARY, List.of(), Set.of(), alwaysTicket(0)));
        assertEquals(Optional.empty(),
                CardRoll.roll(CardRoll.Mode.GUARANTEED_PREFER_UNOWNED, List.of(), Set.of(),
                        alwaysTicket(0)),
                "no characters at all must not be mistaken for 'owns everything' and fall back");
        assertEquals(List.of(), CardRoll.unownedIn(List.of(), Set.of()));
    }
}


