package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.impl.attach.BattleStartState;
import com.astrum.pcrd.impl.effect.Counters;
import com.astrum.pcrd.impl.party.BookText;
import com.astrum.pcrd.impl.party.SkillRows;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「战斗开始」: the trigger, its boundary, and 雪菲's 特殊技能1 冰龙之印.</b>
 *
 * <h2>The three questions this file answers, in order</h2>
 * <ol>
 *   <li><b>What is a battle start?</b> The first blow of a fight — the first blow a player lands after
 *       {@link TriggerRuntime#BATTLE_START_WINDOW_TICKS} of not landing one. Not a login, not a respawn, not a
 *       dimension change: none of those is a fight, and login is the one that would refill a saved tally. The
 *       boundary is pure arithmetic ({@link BattleStartState}) with a world-free test.</li>
 *   <li><b>Once per what?</b> Once per fight, where a fight is that run of blows: the anchor is the last blow,
 *       so continuous combat opens exactly one fight however long it lasts, and a lull opens the next. The
 *       state is a runtime-only per-player attachment, so a death (which clears the marks) and a relog (which
 *       keeps them, bounded by the tally's cap) start fresh — see {@code Attachments.BATTLE_START}.</li>
 *   <li><b>No tick listener?</b> None: the fact is read on the hit that already runs the wheel, and the
 *       boundary lapses when the next blow looks at it, exactly like the combo chain.</li>
 * </ol>
 *
 * <h2>What is asserted here, and what is not</h2>
 * The gate, the fact, the firing, the shipped hook and the book row are all unit-testable because
 * {@code HookEvaluator}'s seam takes the facts and a lambda for the effect. <b>The actual mark stack is
 * not</b>: applying it needs a {@code ServerPlayer} and a bound effect registry. What this file can prove is
 * that the shipped hook is a {@code counter} whose receiver is the caster's own side
 * ({@link EffectType#reachesParty()}), that it fires on exactly the first blow, and that the cap it
 * resolves to is the documented one. The rest — four marks appearing on her effect HUD, and the tally
 * surviving a relog — only a human at the keyboard can confirm.
 */
class BattleStartTest {

    /**
     * The shipped window: 600 ticks = 30 s. Pinned as a number because it is the rule that decides how often a
     * stalling player may refill, and a silent change to it is a balance change.
     */
    private static final int WINDOW = TriggerRuntime.BATTLE_START_WINDOW_TICKS;

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = BattleStartTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static JsonObject lang(String code) throws IOException {
        String path = "/assets/astrum/lang/" + code + ".json";
        try (InputStream in = BattleStartTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing language file on the classpath: " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    /** {@return the one {@code sp1} hook of {@code def}, or {@code null} when she has none} */
    private static EffectHook sp1Hook(CharacterDef def) {
        for (EffectHook hook : def.effects()) {
            if (hook.triggerParams().skillTag().orElse("").equals("sp1")) {
                return hook;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 1. the trigger itself

    @Test
    void theTriggerIsBattleStartAndIsLabelledInBothLanguages() throws Exception {
        assertEquals("battle_start", Trigger.BATTLE_START.getSerializedName(),
                "the datapack spelling is the enum's serialized name");
        assertEquals("astrum.trigger.battle_start", BookText.triggerKey(Trigger.BATTLE_START),
                "the book derives the label key from the enum, so a rename must move both together");
        for (String code : List.of("en_us", "zh_cn")) {
            JsonObject file = lang(code);
            assertTrue(file.has("astrum.trigger.battle_start"),
                    code + ".json has no label for the new trigger — a hook row would read the raw key");
            assertFalse(file.get("astrum.trigger.battle_start").getAsString().isBlank(),
                    code + ": a blank label is not a translation");
        }
    }

    // ------------------------------------------------------------------ 2. the fact

    @Test
    void onlyTheBlowThatOpensAFightObservesBattleStart() {
        TriggerFacts opening = TriggerFacts.forHit(true, false, false, 1, false, true);
        assertTrue(opening.observes(Trigger.BATTLE_START, TriggerParams.NONE));
        assertTrue(opening.observes(Trigger.MELEE_HIT, TriggerParams.NONE),
                "the opening blow is still a melee hit: one event, two facts, one evaluation");

        TriggerFacts ordinary = TriggerFacts.forHit(true, false, false, 2, false);
        assertFalse(ordinary.observes(Trigger.BATTLE_START, TriggerParams.NONE),
                "the second blow of a fight must not open it again");
        assertFalse(TriggerFacts.forKill().observes(Trigger.BATTLE_START, TriggerParams.NONE),
                "a death is not a fight's first blow");
        assertFalse(TriggerFacts.forBlock().observes(Trigger.BATTLE_START, TriggerParams.NONE),
                "a blocked blow is not one she landed");
        assertFalse(TriggerFacts.forStackFull(com.astrum.pcrd.impl.InternalIds.id("poison"))
                        .observes(Trigger.BATTLE_START, TriggerParams.NONE),
                "a full stack is not a fight's first blow");
    }

    // ------------------------------------------------------------------ 3. the boundary

    @Test
    void theShippedWindowIsThirtySeconds() {
        assertEquals(600, WINDOW, "600 ticks = 30 s; it is a constant, so a change is deliberate");
        assertTrue(WINDOW > 40,
                "it must be far longer than the 2 s combo window: a fight is an engagement, a combo is a "
                        + "rhythm, and one number cannot mean both");
    }

    @Test
    void theFirstBlowBeginsAFightAndTheBlowsAfterItDoNot() {
        assertTrue(BattleStartState.EMPTY.beginsAt(100, WINDOW), "no blow on record: the first one opens a fight");
        BattleStartState afterFirst = BattleStartState.EMPTY.noteBlow(100);
        assertFalse(afterFirst.beginsAt(200, WINDOW), "one blow later is still the same fight");
        assertFalse(afterFirst.beginsAt(100 + WINDOW - 1, WINDOW),
                "a gap of 599 ticks has not ended the fight");
        assertTrue(afterFirst.beginsAt(100 + WINDOW, WINDOW),
                "exactly one window later is a new fight (the boundary ComboState uses)");
    }

    @Test
    void aFightThatKeepsLandingBlowsOpensExactlyOnce() {
        BattleStartState state = BattleStartState.EMPTY;
        int openings = 0;
        // 20 ticks (1 s) between blows for 20 000 ticks: a fight of nearly seventeen minutes.
        for (long now = 0; now < 20_000; now += 20) {
            if (state.beginsAt(now, WINDOW)) {
                openings++;
            }
            state = state.noteBlow(now);
        }
        assertEquals(1, openings,
                "a continuous fight must not be re-opened every window — the anchor is the last BLOW");
    }

    @Test
    void aLullAndThenABlowOpensTheNextFight() {
        BattleStartState afterLull = BattleStartState.EMPTY.noteBlow(100).noteBlow(9_000);
        assertTrue(BattleStartState.EMPTY.noteBlow(100).beginsAt(100 + WINDOW + 1, WINDOW));
        assertFalse(afterLull.beginsAt(9_100, WINDOW),
                "and the new fight anchors on the blow that opened it, not on the previous fight");
    }

    @Test
    void aTickFromAnotherTimelineOpensAFight() {
        assertTrue(new BattleStartState(5_000).beginsAt(100, WINDOW),
                "a state from the future is not a fight in progress");
    }

    /**
     * A non-positive window cannot span two blows, so every blow would open one.
     *
     * <p>That is the honest arithmetic, and it is <b>not</b> reachable from the data: the window is a compile-time
     * constant ({@value #WINDOW} after the automatic resolution of {@code BATTLE_START_WINDOW_TICKS}), not a
     * config option, so only an author editing it can produce it. Asserting it here means the branch is a stated
     * reading rather than a surprise.
     */
    @Test
    void aNonPositiveWindowOpensEveryBlow() {
        assertTrue(BattleStartState.EMPTY.beginsAt(100, 0));
        assertTrue(new BattleStartState(100).beginsAt(100, -5));
    }

    // ------------------------------------------------------------------ 4. the firing path

    /**
     * <b>Once per fight, not once per swing.</b>
     *
     * <p>Four blows are driven through the real gate and the real production seam
     * ({@link HookEvaluator#evaluateHooksTiered}): three in one fight, then one after a lull. 雪菲's
     * {@code battle_start} hook fires on the first and the fourth, and on neither of the middle two.
     */
    @Test
    void herSp1FiresOnTheOpeningBlowOfEachFightOnly() throws Exception {
        CharacterDef sheffy = load("sheffy");
        long[] blows = {100, 140, 180, 180 + WINDOW};
        BattleStartState state = BattleStartState.EMPTY;
        List<Integer> openings = new ArrayList<>();
        for (int i = 0; i < blows.length; i++) {
            boolean begins = state.beginsAt(blows[i], WINDOW);
            state = state.noteBlow(blows[i]);
            int fired = countBattleStartFirings(sheffy, TriggerFacts.forHit(
                    true, false, false, 1, false, begins));
            assertEquals(begins ? 1 : 0, fired, "blow #" + (i + 1) + " at tick " + blows[i]);
            if (begins) {
                openings.add(i);
            }
        }
        assertEquals(List.of(0, 3), openings, "two fights in four blows: the first and the one after the lull");
    }

    /** Runs {@code def}'s hooks the way production does and counts what the {@code battle_start} trigger fired. */
    private static int countBattleStartFirings(CharacterDef def, TriggerFacts facts) {
        HookPlan.Outcome outcome = HookEvaluator.evaluateHooksTiered(def, facts, chance -> true,
                (index, hook, tier) -> Optional.empty(), invalid -> {
                }, SkillRotation.START, 1, 1);
        int fired = 0;
        for (HookPlan.Fired one : outcome.fired()) {
            if (one.hook().trigger() == Trigger.BATTLE_START) {
                fired++;
            }
        }
        return fired;
    }

    /**
     * <b>The grant runs before the swing that reads it.</b>
     *
     * <p>This is why the hook is written first in {@code effects[]}: on the opening blow the list order is
     * {@code counter(sp1)} then {@code consume_counter(snow_boots)}, so 雪靴's 「+【…】×『冰龙之印』」 bonus is
     * computed from the four marks the same blow just granted rather than from the tally before it.
     */
    @Test
    void theGrantRunsBeforeTheSkillThatSpendsItOnTheSameBlow() throws Exception {
        CharacterDef sheffy = load("sheffy");
        HookPlan.Outcome outcome = HookEvaluator.evaluateHooksTiered(sheffy,
                TriggerFacts.forHit(true, false, false, 1, false, true), chance -> true,
                (index, hook, tier) -> Optional.empty(), invalid -> {
                }, SkillRotation.START, 1, 1);
        List<Trigger> fired = outcome.fired().stream().map(one -> one.hook().trigger()).toList();
        assertEquals(List.of(Trigger.BATTLE_START, Trigger.MELEE_HIT, Trigger.MELEE_HIT,
                        Trigger.MELEE_HIT),
                fired,
                "「战斗开始」 then 雪靴's three clauses (a spend, damage, a defence debuff)");
        assertEquals(EffectType.COUNTER, outcome.fired().get(0).hook().type(),
                "the first thing that happens on the opening blow is the mark grant");
    }

    // ------------------------------------------------------------------ 5. the shipped data

    @Test
    void herSp1IsACounterOfFourMarksOnBattleStart() throws Exception {
        CharacterDef sheffy = load("sheffy");
        EffectHook hook = sp1Hook(sheffy);
        assertNotNull(hook, "sheffy.json must carry a hook tagged sp1 or 冰龙之印 has no body");
        assertEquals(Trigger.BATTLE_START, hook.trigger());
        assertEquals(EffectType.COUNTER, hook.type(),
                "「获得4个【冰龙之印】」 is a counter grant, not a buff or a charge");
        assertTrue(hook instanceof EffectHook.Counter);
        EffectHook.Counter counter = (EffectHook.Counter) hook;
        assertEquals(4, counter.base(), "bwiki: 「特殊技能1 获得4个『冰龙之印』」");
        assertEquals(0, counter.perMark(),
                "a constant grant — 「获得4个」 is not the UB's 【5+计数器枚数】 formula");
        assertEquals(180.0, counter.seconds(), 1e-9, "the shipped tally lifetime, the same as her UB's grant");
    }

    /**
     * <b>The grant lands on 雪菲's owner, not on the mob.</b>
     *
     * <p>Two facts, both single-copy: {@link EffectType#COUNTER} is party-scoped (the caster's own side), and
     * the only executor arm for it applies through {@code Counters.addMarks(ctx.attacker())}. So the data half
     * is `COUNTER`, and the receiver half is the effect type's own declaration — the same rule
     * {@code TieredEffects} and {@code EffectExecutors} read.
     */
    @Test
    void theGrantLandsOnTheCasterSideNotTheTarget() {
        assertTrue(EffectType.COUNTER.reachesParty(),
                "a mark is hers: COUNTER is in EffectType's party-scoped set");
        assertFalse(EffectType.COUNTER.reachesVictim());
    }

    /**
     * <b>The cap is the documented tier-3 rung of 12, and it is the same fraction of a grant her UB uses.</b>
     *
     * <p>The data states 12 because that is the number her {@code _notes} records as this tally's ceiling
     * (bwiki's 「最大10个」 maps up to the ladder's 12). {@code Counters.tierForStatedCap} then maps it back to
     * rung 3, so the data is self-consistent: the stated cap and the applied cap agree, and the ladder stays
     * the only table of caps.
     */
    @Test
    void theStatedCapIsTheTierThreeRungOfTwelve() throws Exception {
        EffectHook.Counter counter = (EffectHook.Counter) sp1Hook(load("sheffy"));
        assertEquals(12, counter.cap(), "the number her json states — her tier-3 tally ceiling");
        assertEquals(3, Counters.tierForStatedCap(counter.cap()),
                "12 is the ladder's third rung, so the grant applies at tier 3 and not at the top rung");
        assertEquals(12, Counters.capForTier(Counters.tierForStatedCap(counter.cap())),
                "the stated cap and the applied cap are the same number");
    }

    /** 冰龙之印 is a ladder of its own, with the name bwiki gives it. */
    @Test
    void herSp1IsALadderNamedIceDragonMark() throws Exception {
        CharacterDef sheffy = load("sheffy");
        var ladder = com.astrum.pcrd.impl.growth.AffectionKernel.ladderFor(sheffy, "sp1").orElseThrow(
                () -> new AssertionError("sheffy.json must declare the sp1 ladder"));
        assertEquals("冰龙之印", ladder.baseName());
        assertEquals("冰龙之印", ladder.nameAt(1));
        assertEquals("冰龙之印", ladder.nameAt(3), "the skill does not rename at 6★");
    }

    /** The book draws one row per skill: her SP1 is row one, with its grant behind it. */
    @Test
    void herSp1RowAppearsInTheBook() throws Exception {
        SkillRows.Row row = SkillRows.of(load("sheffy"), 1).rows().stream()
                .filter(one -> one.skill().equals("sp1")).findFirst().orElseThrow(
                        () -> new AssertionError("the skills list must show 冰龙之印"));
        assertEquals("冰龙之印", row.name());
        assertEquals(1, row.hooks().size(), "one effect: the grant");
        assertEquals(Trigger.BATTLE_START, row.firstHook().orElseThrow().trigger(),
                "and the row names the trigger that fires it, so the player can see it is a battle-start skill");
    }

    // ------------------------------------------------------------------ 6. the one firing path

    /**
     * <b>One definition of "a fight begins", so exactly one caller.</b>
     *
     * <p>{@code TriggerRuntime.noteBattleStart} is read from the damage path that already runs the wheel — and
     * from nowhere else. A login, a respawn or a dimension change therefore cannot fire the trigger at all,
     * which is the design decision this assertion pins: a second caller would be a second answer to "did a fight
     * begin", and the one that reads a saved tally (login) is the one a player could farm.
     *
     * <p>Source-pinned rather than behavioural because the alternative — standing up a server and logging in
     * twice — is exactly the kind of test this project cannot run in a unit suite; the source tree is the
     * artefact that would change if somebody wired it to login.
     */
    @Test
    void theFightOpensInExactlyOnePlace() {
        java.nio.file.Path root = com.astrum.pcrd.impl.client.TestProjectRoot.resolve("src/main/java");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null && java.nio.file.Files.isDirectory(root),
                "main sources not reachable; this assertion would be vacuous rather than wrong");
        List<String> callers = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = java.nio.file.Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                if (source.contains("noteBattleStart(")) {
                    callers.add(file.getFileName().toString());
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("could not walk " + root, e);
        }
        callers.sort(String::compareTo);
        assertEquals(List.of("CombatEvents.java", "TriggerRuntime.java"), callers,
                "the boundary may be read only by the damage path (which runs the wheel) and declared only by "
                        + "TriggerRuntime; a third file is a second definition of when a fight begins");
    }
}