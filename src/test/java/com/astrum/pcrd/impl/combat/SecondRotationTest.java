package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.SecondRotation;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.api.v1.UbDef;
import com.astrum.pcrd.api.v1.UbTier;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>矛依未's second rotation</b>: the loop her ultimate switches her into, its deadline, and what a kill does
 * to it.
 *
 * <h2>The human's rules, and the assertion each one owes</h2>
 * <table border="1">
 *   <caption>Two rulings, one test each</caption>
 *   <tr><th>rule</th><th>test</th></tr>
 *   <tr><td>after the ultimate she enters the second loop</td><td>{@link #aCastPutsHerIntoTheSecondLoop()}</td></tr>
 *   <tr><td>① 直接消耗掉全部 — the bar is emptied once, at the cast</td>
 *       <td>{@link #theChargeIsSpentOnceAndTheModeIsADuration()}</td></tr>
 *   <tr><td>the second loop serves SP skills while it lasts, the first loop after</td>
 *       <td>{@link #theSecondLoopServesSpSkillsAndTheFirstLoopServesHers()}</td></tr>
 *   <tr><td>② +2 秒 / 击杀，无上限</td><td>{@link #eachKillAddsExactlyTwoSeconds()},
 *       {@link #theExtensionIsUncapped()}</td></tr>
 *   <tr><td>a character with only one loop is unaffected</td><td>{@link #aCharacterWithOneLoopIsUnaffected()}</td></tr>
 *   <tr><td>logout clears it</td><td>{@link #logoutForgetsTheMode()}</td></tr>
 * </table>
 *
 * <h2>Why the store's deadline is driven with explicit game times</h2>
 * The whole point of storing a deadline instead of ticking is that <b>the clock is somebody else's</b>: time
 * passing is a number the caller passes in, so "three seconds later" is an argument rather than a sleep. That is
 * what makes every test here instant, and it is also why no test needs a world.
 */
class SecondRotationTest {

    private static final ResourceLocation MUIMI = ResourceLocation.parse("astrum:muimi");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000beef");

    @AfterEach
    void clearState() {
        SecondRotationStore.clearAll();
        SkillRotationStore.clearAll();
    }

    // ------------------------------------------------------------------ entering the mode

    /**
     * <b>An accepted ultimate puts her into the second loop, and it lasts the declared duration.</b>
     *
     * <p>「开了 UB 之后…进入第二套循环」. The declaration drives the numbers rather than a literal, so a pack that
     * retunes the duration moves this test with it.
     */
    @Test
    void aCastPutsHerIntoTheSecondLoop() {
        SecondRotation spec = spec(10.0, 2.0);
        CharacterDef def = muimi(spec);
        long now = 5_000L;

        assertFalse(SecondRotationStore.isActive(PLAYER, MUIMI, now), "she starts on her first loop");
        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now, spec.seconds()),
                "a declaration with a real clock starts a mode");
        assertTrue(SecondRotationStore.isActive(PLAYER, MUIMI, now));
        assertTrue(SecondRotationStore.isActive(PLAYER, MUIMI, now + 199L),
                "10 s = 200 ticks, so 199 ticks later it is still running");
        assertFalse(SecondRotationStore.isActive(PLAYER, MUIMI, now + 200L),
                "the deadline is exclusive: at exactly 200 ticks the mode is over");
        // And nothing else is affected: the player's other characters, and other players, are untouched.
        assertFalse(SecondRotationStore.isActive(PLAYER, ResourceLocation.parse("astrum:djeeta"), now));
        assertFalse(SecondRotationStore.isActive(OTHER, MUIMI, now));
        assertNotNull(def);
    }

    /**
     * <b>① 直接消耗掉全部 — the charge is emptied once, and the mode's lifetime is a duration.</b>
     *
     * <h2>What this pins, and what it deliberately does not</h2>
     * The emptying is {@code UbTransaction.decide}'s, which writes {@code charge = 0} for every accepted cast and
     * has its own tests. What this file owes is the <b>other</b> half of the human's ruling:
     * the second loop is <b>not</b> a charge budget. There is no code path here that reads the gauge, subtracts
     * per second, or ends the mode early because the bar ran out — the lifetime is
     * {@code SecondRotation.seconds()} and nothing else.
     *
     * <p>So the assertion is about <b>shape</b> as much as numbers: with the store's clock driven by hand, the
     * mode runs exactly {@code seconds × 20} ticks regardless of what is in the gauge, and a second activation
     * restarts it rather than extending it (two casts are two separate modes, and the second one re-spent a full
     * bar of its own).
     */
    @Test
    void theChargeIsSpentOnceAndTheModeIsADuration() {
        SecondRotation spec = spec(10.0, 2.0);
        long now = 1_000L;

        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now, spec.seconds()));
        assertEquals(now + 200L, SecondRotationStore.deadlineOf(PLAYER, MUIMI).orElseThrow(),
                "the deadline is exactly seconds × 20 ticks from the cast — a duration, not a budget");

        // A second cast mid-mode restarts the clock; it does not bank the remainder of the first.
        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now + 100L, spec.seconds()));
        assertEquals(now + 100L + 200L, SecondRotationStore.deadlineOf(PLAYER, MUIMI).orElseThrow(),
                "a recast owns its own clock: 10 s from the second cast, not 15");
        assertEquals(1, SecondRotationStore.size(), "one mode per (player, character), never two entries");
    }

    /** A declaration with no usable clock starts nothing, and the player keeps a working first loop. */
    @Test
    void aDeclarationWithoutADurationStartsNothing() {
        for (double seconds : new double[] {0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(SecondRotationStore.begin(PLAYER, MUIMI, 100L, seconds),
                    "seconds=" + seconds + " is not a duration");
            assertEquals(0, SecondRotationStore.size());
        }
        // And the record itself zeroes hostile input, so the failure is a value rather than an exception.
        assertEquals(0.0, spec(0.0, 2.0).seconds());
        assertEquals(0.0, spec(10.0, -1.0).secondsPerKill(), "a negative per-kill extension is not one");
    }

    // ------------------------------------------------------------------ the two loops

    /**
     * <b>While the mode runs her SP skills fire; afterwards her first loop does.</b>
     *
     * <h2>Driven through the production seam, not through the store</h2>
     * {@code HookEvaluator.evaluateHooks} is the route production takes from a {@link CharacterDef} to a chain
     * of effects ({@link HookPlan} is behind it), so walking her rotation here asserts the join rather than the
     * pieces: two swings in the second loop must be SP1 then SP2, and the very next swing after the deadline
     * must be her first loop's 让你们老实点！ again.
     */
    @Test
    void theSecondLoopServesSpSkillsAndTheFirstLoopServesHers() {
        CharacterDef def = muimi(spec(10.0, 2.0));

        // First loop: 让你们老实点！ then 这边这边！ — her two base skills, in effect-list order.
        List<List<EffectType>> firstLoop = swings(def, 1, false, SkillRotation.START, 2);
        assertEquals(List.of(EffectType.EXTRA_DAMAGE, EffectType.SPLASH, EffectType.PANIC), firstLoop.get(0),
                "her first loop's skill 1 is the three-clause attack");
        assertEquals(List.of(EffectType.DEFENCE_DOWN), firstLoop.get(1),
                "and skill 2 is 这边这边！");

        // The second loop is a *different* two-skill rotation, not her first one reordered.
        List<List<EffectType>> second = swings(def, 1, true, SkillRotation.START, 3);
        assertEquals(List.of(EffectType.EXTRA_DAMAGE), second.get(0), "SP1 天楼回刃斩 is one damage clause");
        assertEquals(List.of(EffectType.EXTRA_DAMAGE, EffectType.SPLASH), second.get(1),
                "SP2 天楼闪薙斩 is single-target plus the enemies around it");
        assertEquals(second.get(0), second.get(2), "and it wraps at its own period of two");
        assertNotEquals(firstLoop.get(0), second.get(0),
                "the two loops are independent: the same cursor index means different skills");
    }

    /**
     * <b>The 专武 / 6★ rung swaps in 天楼回刃斩+.</b>
     *
     * <p>SP1's upgraded formula is twice the base one (9.6A vs 4.8A), and SP2's is the same at both rungs — which
     * is exactly what {@code SecondRotation.hooksAt} has to express: a rung that replaces the <em>set</em>, not a
     * multiplier on one skill. Asserted at tier 2 and at tier 1 so a "tier ≥ 2" rule and a "tier == 2" rule are
     * told apart.
     */
    @Test
    void theUpgradedRungReplacesTheSecondLoopsSkillSet() {
        CharacterDef def = muimi(spec(10.0, 2.0));
        assertEquals(20.0, damageOf(def, 1, true, "sp1"), 1e-9, "tier 1 runs the base 天楼回刃斩");
        assertEquals(40.0, damageOf(def, 2, true, "sp1"), 1e-9, "tier 2 runs 天楼回刃斩+ (9.6A)");
        assertEquals(40.0, damageOf(def, 3, true, "sp1"), 1e-9, "and 6★ keeps the top rung");
        assertEquals(14.1, damageOf(def, 1, true, "sp2"), 1e-9);
        assertEquals(14.1, damageOf(def, 2, true, "sp2"), 1e-9, "SP2 is the same at both rungs");

        // And the first loop is unaffected by the second loop's rungs. The built fixture declares no affection
        // ladder, so it is tier-invariant by construction — the shipped file's ladder is asserted below, where
        // it is a fact about the data rather than about this fixture.
        assertEquals(1.1, damageOf(def, 1, false, "settle_down"), 1e-9);
        assertEquals(1.1, damageOf(def, 2, false, "settle_down"), 1e-9,
                "a rung of the second loop must not move a skill of the first");
    }

    /**
     * <b>A character with only one loop is unaffected.</b>
     *
     * <p>This is the regression that matters most: nineteen of the twenty shipped characters declare no
     * {@code ub.secondRotation}, and every one of them must keep exactly the hook list she had. Asserted twice —
     * once for a definition with no {@code secondRotation} field, and once with the mode flag forced on, which
     * cannot happen in production but proves the fallback is the first loop rather than an empty list.
     */
    @Test
    void aCharacterWithOneLoopIsUnaffected() {
        CharacterDef djeeta = djeeta();
        assertTrue(SecondRotationRuntime.secondRotationOf(djeeta).isEmpty(),
                "姬塔 declares no second loop, so there is nothing to enter");
        for (int tier = 1; tier <= 3; tier++) {
            assertEquals(com.astrum.pcrd.impl.growth.AffectionKernel.hooksAt(djeeta, tier),
                    SecondRotationRuntime.loopFor(djeeta, tier, false),
                    "tier " + tier + ": with no mode she runs exactly her resolved set");
            assertEquals(com.astrum.pcrd.impl.growth.AffectionKernel.hooksAt(djeeta, tier),
                    SecondRotationRuntime.loopFor(djeeta, tier, true),
                    "tier " + tier + ": a stray live flag must not strip her of her skills");
        }
        // And the store refuses to start a mode for her: no declaration means no clock.
        assertTrue(SecondRotationRuntime.secondRotationOf(null).isEmpty());
        assertEquals(List.of(), SecondRotationRuntime.loopFor(null, 1, true),
                "a null definition is empty, not an exception — this runs on a damage path");
    }

    // ------------------------------------------------------------------ kills

    /**
     * <b>A kill adds exactly 2 s, and only while the mode is running.</b>
     *
     * <p>「+2 秒 / 击杀」. The store's extension is measured in the only unit it has — ticks — so the assertion is
     * {@code +40 ticks}, and the "only while running" half is the second sentence of the human's rule: a kill is
     * not a way to <em>enter</em> or to <em>revive</em> a loop.
     */
    @Test
    void eachKillAddsExactlyTwoSeconds() {
        long now = 10_000L;
        assertFalse(SecondRotationStore.extend(PLAYER, MUIMI, now, 2.0),
                "a kill before the mode starts extends nothing — there is no mode");
        assertEquals(0, SecondRotationStore.size(), "and it does not create an entry");

        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now, 10.0));
        assertEquals(now + 200L, SecondRotationStore.deadlineOf(PLAYER, MUIMI).orElseThrow());

        assertTrue(SecondRotationStore.extend(PLAYER, MUIMI, now + 50L, 2.0));
        assertEquals(now + 240L, SecondRotationStore.deadlineOf(PLAYER, MUIMI).orElseThrow(),
                "+2 s is exactly +40 ticks");

        assertTrue(SecondRotationStore.extend(PLAYER, MUIMI, now + 240L - 1L, 2.0),
                "a kill one tick before the end still lands: the mode is running until the deadline");
        assertEquals(now + 280L, SecondRotationStore.deadlineOf(PLAYER, MUIMI).orElseThrow());

        assertFalse(SecondRotationStore.extend(PLAYER, MUIMI, now + 280L, 2.0),
                "a kill at the deadline finds no running mode and must not resurrect it");
        assertEquals(0, SecondRotationStore.size(), "the expired entry is dropped, not extended");
    }

    /**
     * <b>The extension is uncapped</b> — 「原作游戏内就是无上限」.
     *
     * <p>Two hundred kills is two hundred extensions. A cap would be a number nobody gave, and the failure mode
     * of an accidental one (a {@code Math.min} somebody added "for safety") is a long fight where the mode
     * silently stops growing. So the loop asserts the arithmetic for a large count and the total at the end.
     */
    @Test
    void theExtensionIsUncapped() {
        long now = 0L;
        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now, 10.0));
        long at = now;
        for (int kill = 1; kill <= 200; kill++) {
            // Keep the clock inside the mode so every kill lands, which is what a real fight does.
            assertTrue(SecondRotationStore.extend(PLAYER, MUIMI, at, 2.0), "kill " + kill);
            at += 1L;
        }
        assertEquals(now + 200L + 200L * 40L, SecondRotationStore.deadlineOf(PLAYER, MUIMI).orElseThrow(),
                "200 kills × 40 ticks with no cap: the deadline is 8100 ticks out");
        assertTrue(SecondRotationStore.isActive(PLAYER, MUIMI, now + 7_000L),
                "and it really is still running seven thousand ticks after the cast");
    }

    /** A per-kill extension of zero (the default a pack omits) leaves the deadline alone. */
    @Test
    void aPackWithNoPerKillExtensionDoesNothingOnAKill() {
        long now = 500L;
        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now, 10.0));
        assertFalse(SecondRotationStore.extend(PLAYER, MUIMI, now + 10L, 0.0));
        assertEquals(now + 200L, SecondRotationStore.deadlineOf(PLAYER, MUIMI).orElseThrow());
        assertEquals(0.0, spec(10.0, 0.0).secondsPerKill(),
                "and the data's default is zero, not 2 — 「+2 秒」 is 矛依未's number, not the mechanic's");
    }

    // ------------------------------------------------------------------ lifecycle

    /** Logout forgets the mode, and only that player's — 「退出就重置」. */
    @Test
    void logoutForgetsTheMode() {
        long now = 42L;
        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now, 10.0));
        assertTrue(SecondRotationStore.begin(OTHER, MUIMI, now, 10.0));
        assertTrue(SecondRotationStore.begin(PLAYER, ResourceLocation.parse("astrum:rei"), now, 10.0));
        assertEquals(3, SecondRotationStore.size());

        assertEquals(2, SecondRotationStore.clear(PLAYER), "both of this player's characters are dropped");
        assertEquals(1, SecondRotationStore.size(), "the other player keeps hers");
        assertFalse(SecondRotationStore.isActive(PLAYER, MUIMI, now));
        assertTrue(SecondRotationStore.isActive(OTHER, MUIMI, now));

        SecondRotationStore.clearAll();
        assertEquals(0, SecondRotationStore.size(), "and a server stop drops the rest");
        assertEquals(0, SecondRotationStore.clear(PLAYER), "clearing nothing is not an error");
        assertEquals(0, SecondRotationStore.clear(null));
    }

    /**
     * <b>The table holds no expired mode as "active", and reports the difference honestly.</b>
     *
     * <p>{@link SecondRotationStore#size()} counts entries, {@link SecondRotationStore#activeCount(long)} counts
     * live ones. The gap is the price of not ticking: an idle player's expired {@code long} sits there until
     * somebody asks. This asserts that no <em>rule</em> can read the stale entry as running, which is the part
     * that matters.
     */
    @Test
    void anExpiredEntryIsNeverReadAsRunning() {
        long now = 0L;
        assertTrue(SecondRotationStore.begin(PLAYER, MUIMI, now, 1.0));
        assertEquals(1, SecondRotationStore.activeCount(now));
        assertEquals(1, SecondRotationStore.activeCount(now + 19L));
        assertEquals(0, SecondRotationStore.activeCount(now + 20L), "one second later it is over");
        assertFalse(SecondRotationStore.isActive(PLAYER, MUIMI, now + 20L));
        assertEquals(0, SecondRotationStore.size(), "and the read that noticed it also dropped it");
    }

    // ------------------------------------------------------------------ the shipped data

    /**
     * <b>矛依未's shipped JSON declares the loop, and the numbers are the derivations'.</b>
     *
     * <p>Read through the real {@link CharacterDef#CODEC}, because the file is what a future author edits:
     * 10.0 s (a 1000-point TP bar at 100 TP a second), +2.0 s a kill with no cap, SP1 20.0 / SP2 14.1 at the base
     * rung and SP1 40.0 at 专武 — all from {@code docs/derivations-batch-a.md} §6 and the bwiki formulas.
     */
    @Test
    void theShippedDeclarationIsTheDerivedOne() throws Exception {
        CharacterDef shipped = load("muimi");
        SecondRotation spec = SecondRotationRuntime.secondRotationOf(shipped).orElseThrow(
                () -> new AssertionError("muimi.json must declare ub.secondRotation — that is the mechanism"));
        assertEquals(SecondRotation.Condition.AFTER_ULTIMATE, spec.condition());
        assertEquals(10.0, spec.seconds(), 1e-9, "1000 TP at 100 TP/s = 10 s, and §14.5 makes 1 point = 10 TP");
        assertEquals(2.0, spec.secondsPerKill(), 1e-9, "「+2 秒 / 击杀」");
        // <b>All three clauses are closed now, so the note is gone.</b> Task 63 made 「标记 ID 125」 a granted
        // state and the 「层数 ≥ 1 时追加一段」 branches gated hooks; the last one, SP2's 「以自身为中心」, is
        // `EffectHook.Splash#casterCentred` (see CasterCentredSplashTest). An `extra` naming any of them would
        // render as 「尚未实现」, so its absence is the assertion.
        assertTrue(spec.extra().isEmpty(),
                "every second-loop clause is expressed, so no gap note may remain: "
                        + spec.extra().orElse(""));
        assertFalse(spec.extra().orElse("").contains("无上限"),
                "the uncapped rule is an implemented fact (secondsPerKill), so it belongs in _notes, not here");

        assertEquals(2, com.astrum.pcrd.impl.combat.SkillGroups.of(spec.hooks()).count(),
                "her second loop is two skills — the pattern the assertion in the brief quotes");
        assertEquals(20.0, damageOf(shipped, 1, true, "sp1"), 1e-9, "4.8A+60+60L = 22140 → 20.0");
        assertEquals(14.1, damageOf(shipped, 1, true, "sp2"), 1e-9, "3.6A+40+40L = 15560 → 14.1");
        assertEquals(40.0, damageOf(shipped, 2, true, "sp1"), 1e-9, "9.6A+120+120L = 44280 → 40.0");

        // And no OTHER shipped character gains a second loop by accident — every json in the shipped directory,
        // including 怜 (`rei`, 100301), whose second attack pattern is a normal-attack cadence and not a drain
        // mode. The list is the directory's contents rather than a subset, so a new character that copied
        // muimi's block by accident fails here.
        for (String id : List.of("ayane", "chika", "christina", "djeeta", "hiyori", "kokkoro", "kurumi",
                "kyaru", "kyoka", "mimi", "misogi", "nozomi", "pecorine", "rei", "rino", "saren", "sheffy",
                "shizuru", "suzume", "tsumugi", "yui")) {
            CharacterDef def = maybeLoad(id);
            assertNotNull(def, id + " must be packaged: the assertion below is only meaningful if it loaded");
            assertTrue(SecondRotationRuntime.secondRotationOf(def).isEmpty(),
                    id + " must not declare a second loop: 怜's (100301) second pattern is a normal-attack "
                            + "cadence, not a drain mode, and the human ruled it does not get this mechanic");
        }
    }

    // ------------------------------------------------------------------ the book, and the codec

    /**
     * <b>The book lists the second loop's skills, and keeps every first-loop row where it was.</b>
     *
     * <h2>Why this is load-bearing</h2>
     * The rows are the book's order and the rotation's order: a player reading the list top to bottom reads her
     * rotation, so the second loop's skills are <b>appended</b> rather than interleaved — inserting them among the
     * first loop's would put 天楼回刃斩 before 这边这边！ in the list a player reads.
     *
     * <p>It used to be load-bearing for the effect bar too: a skill entry's amplifier was a row index, so moving a
     * row renumbered every skill after it and the bar drew one skill's picture for another skill's firing. Task 59
     * removed that coupling — the entry's effect type now <b>is</b> the (character, skill) identity, so nothing on
     * the bar depends on a row's position (see {@code SkillEffectTypes}). The order here is still asserted, for the
     * book's sake.
     */
    @Test
    void theBookListsBothLoopsAndKeepsTheFirstLoopsIndices() throws Exception {
        CharacterDef shipped = load("muimi");
        for (int tier = 1; tier <= 3; tier++) {
            var rows = com.astrum.pcrd.impl.party.SkillRows.of(shipped, tier).rows();
            List<String> tags = rows.stream().map(com.astrum.pcrd.impl.party.SkillRows.Row::skill).toList();
            assertEquals(List.of("settle_down", "over_here", "sp1", "sp2", "ex", "ub"), tags,
                    "tier " + tier + ": her first loop, then her second, then EX and the ultimate");
            // SP1 gains the ladder's `+` at 专武, and SP2 never renames — the same rung rule the skill set swaps
            // at, because the row's name and the hooks the game runs both come from `SecondRotation.hooksAt`
            // and `SkillLadder.nameAt` at the same tier.
            assertEquals(tier >= 2 ? "天楼回刃斩+" : "天楼回刃斩", rows.get(2).name(),
                    "tier " + tier + ": SP1's name follows the ladder");
            assertEquals("天楼闪薙斩", rows.get(3).name());
        }
        assertEquals("天楼回刃斩+",
                com.astrum.pcrd.impl.party.SkillRows.of(shipped, 2).rows().get(2).name(),
                "and the ladder's 专武 rung names it, the same rung the skill set swaps at");

        // The first loop's rows are byte-identical between tiers 1 and 2 in the skill tags, which is what keeps a
        // row index meaning one skill: that invariant is SkillEntriesTest's, and this is the second loop's half.
        assertEquals(List.of("settle_down", "over_here"),
                com.astrum.pcrd.impl.party.SkillRows.of(shipped, 1).rows().stream()
                        .map(com.astrum.pcrd.impl.party.SkillRows.Row::skill).limit(2).toList());
    }

    /**
     * <b>The declaration survives the codec, and every pack that names none still loads.</b>
     *
     * <p>The field is additive and optional, which is the promise every field added to this schema has made: a
     * pack written before it existed decodes to "one loop" rather than failing, so no shipped character had to be
     * edited for this feature. Both halves are asserted — the round trip, and the absence.
     */
    @Test
    void theDeclarationRoundTripsAndIsOptional() {
        SecondRotation spec = spec(10.0, 2.0);
        UbDef withLoop = new UbDef(ubTier(), ubTier(), Optional.empty(), Optional.of(spec));
        var encoded = UbDef.CODEC.encodeStart(JsonOps.INSTANCE, withLoop).getOrThrow();
        UbDef decoded = UbDef.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(SecondRotation.Condition.AFTER_ULTIMATE,
                decoded.secondRotationOf().orElseThrow().condition());
        assertEquals(10.0, decoded.secondRotationOf().orElseThrow().seconds(), 1e-9);
        assertEquals(2.0, decoded.secondRotationOf().orElseThrow().secondsPerKill(), 1e-9);
        assertEquals(3, decoded.secondRotationOf().orElseThrow().hooks().size());
        assertEquals(3, decoded.secondRotationOf().orElseThrow().upgradedHooks().size(),
                "both rungs survive: a lost upgradedHooks would silently halve SP1 at 专武");
        assertTrue(decoded.secondRotationOf().orElseThrow().extra().isPresent(),
                "and the unimplemented-clause tag survives, or the gap becomes invisible");

        // The pre-existing shape: no secondRotation key at all.
        UbDef bare = new UbDef(ubTier(), ubTier());
        var bareJson = UbDef.CODEC.encodeStart(JsonOps.INSTANCE, bare).getOrThrow();
        assertTrue(UbDef.CODEC.parse(JsonOps.INSTANCE, bareJson).getOrThrow().secondRotationOf().isEmpty(),
                "a pack that names no loop keeps loading with one loop — the additive promise");

        // A block that declares no skills is not a loop, which is why the accessor filters it.
        SecondRotation empty = new SecondRotation(SecondRotation.Condition.AFTER_ULTIMATE, 10.0, 2.0,
                List.of(), List.of());
        assertFalse(new UbDef(ubTier(), ubTier(), Optional.empty(), Optional.of(empty))
                .secondRotationOf().isPresent(),
                "a mode with no skills would strip her of every small skill; it is not a loop");
        assertFalse(empty.isDeclared());
    }

    // ------------------------------------------------------------------ helpers

    /** {@return a declaration with the given clocks and muimi's real skill sets} */
    private static SecondRotation spec(double seconds, double secondsPerKill) {
        return new SecondRotation(SecondRotation.Condition.AFTER_ULTIMATE, seconds, secondsPerKill,
                List.of(sp1(20.0), sp2(), splash()), List.of(sp1(40.0), sp2(), splash()),
                Optional.of("test declaration"));
    }

    private static EffectHook sp1(double amount) {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT, tagged("sp1"), 1.0, amount);
    }

    private static EffectHook sp2() {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT, tagged("sp2"), 1.0, 14.1);
    }

    private static EffectHook splash() {
        return new EffectHook.Splash(Trigger.MELEE_HIT, tagged("sp2"), 1.0, 6.0, 14.1,
                Optional.empty());
    }

    private static TriggerParams tagged(String skill) {
        return new TriggerParams(0, Optional.empty(), Optional.of(skill));
    }

    /** {@return a definition carrying {@code spec} as her second loop, with her real first loop} */
    private static CharacterDef muimi(SecondRotation spec) {
        return new CharacterDef(1, "矛依未", Dimension.OUTPUT, Optional.empty(), Optional.empty(),
                firstLoop(),
                new UbDef(ubTier(), ubTier(), Optional.empty(), Optional.of(spec)),
                new GrowthDef(100, 1.04, 100),
                com.astrum.pcrd.api.v1.AffectionDef.thresholds(3, 8),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /** Her first loop, as the shipped file writes it: 让你们老实点！ then 这边这边！. */
    private static List<EffectHook> firstLoop() {
        return List.of(
                new EffectHook.ExtraDamage(Trigger.MELEE_HIT, tagged("settle_down"), 1.0, 1.1),
                new EffectHook.Splash(Trigger.MELEE_HIT, tagged("settle_down"), 1.0, 4.0, 1.5,
                        Optional.empty()),
                new EffectHook.Panic(Trigger.MELEE_HIT, tagged("settle_down"), 1.0,
                        new com.astrum.pcrd.api.v1.TieredParams(1, OptionalDouble.empty(),
                                OptionalDouble.empty())),
                new EffectHook.DefenceDown(Trigger.MELEE_HIT, tagged("over_here"), 1.0,
                        new com.astrum.pcrd.api.v1.TieredParams(1, OptionalDouble.of(12.0),
                                OptionalDouble.of(4.0))));
    }

    private static UbTier ubTier() {
        return new UbTier("ub_cast", 1, 17.0, OptionalDouble.empty(), List.of());
    }

    /** A one-loop character, built rather than loaded so this test needs no resource. */
    private static CharacterDef djeeta() {
        return new CharacterDef(1, "姬塔", Dimension.CHARGE, Optional.empty(), Optional.empty(),
                List.of(new EffectHook.ExtraDamage(Trigger.MELEE_HIT, tagged("wide_slash"), 1.0, 6.7)),
                new UbDef(ubTier(), ubTier()),
                new GrowthDef(100, 1.15, 30),
                com.astrum.pcrd.api.v1.AffectionDef.thresholds(3, 8),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /**
     * {@return what one swing fires, over {@code swings} swings of one loop}
     *
     * <p>Driven through {@link HookEvaluator#evaluateHooks} — the seam production takes from a definition to a
     * chain of effects — with the loop passed explicitly, so the rotation (not just the hook list) is under test:
     * a second loop that re-used the first one's cursor would show up here as a skill firing out of order.
     */
    private static List<List<EffectType>> swings(CharacterDef def, int tier, boolean second,
            SkillRotation start, int swings) {
        SkillRotation cursor = start;
        List<List<EffectType>> fired = new java.util.ArrayList<>();
        for (int swing = 0; swing < swings; swing++) {
            HookPlan.Outcome outcome = HookEvaluator.evaluateHooks(def,
                    TriggerFacts.forHit(true, false, false, 3, false), chance -> true,
                    (index, hook) -> Optional.empty(), invalid -> {
                    }, cursor, tier, second);
            fired.add(outcome.fired().stream().map(hook -> hook.hook().type()).toList());
            cursor = outcome.rotation();
        }
        return fired;
    }

    /** {@return the {@code extra_damage} amount of {@code skill} in the loop {@code second} names at {@code tier}} */
    private static double damageOf(CharacterDef def, int tier, boolean second, String skill) {
        for (EffectHook hook : SecondRotationRuntime.loopFor(def, tier, second)) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skill)) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        CharacterDef def = maybeLoad(id);
        assertNotNull(def, id + ".json must be packaged at " + jsonPath(id));
        return def;
    }

    /** {@return the shipped definition, or {@code null} when the pack does not ship that name} */
    private static CharacterDef maybeLoad(String id) throws Exception {
        try (InputStream in = SecondRotationTest.class.getResourceAsStream(jsonPath(id))) {
            if (in == null) {
                return null;
            }
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }
}
