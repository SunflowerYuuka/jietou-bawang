package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.combat.HookPlan;
import com.astrum.pcrd.impl.combat.SecondRotationRuntime;
import com.astrum.pcrd.impl.combat.SkillRotation;
import com.astrum.pcrd.impl.combat.TriggerFacts;
import com.astrum.pcrd.impl.effect.Knockbacks;
import com.astrum.pcrd.impl.effect.PartyEffects;
import com.astrum.pcrd.impl.effect.States;
import com.astrum.pcrd.impl.party.BookText;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Task 63: the last five recorded gaps.</b>
 *
 * <p>Five {@code extra} sentences were on the books. Four named machinery that now exists and are therefore
 * <em>closed</em> — 绫音's 击飞高度 (a launch channel), 胡桃's 継続HP回復 (a real over-time heal), 矛依未's
 * 标记 125 (a one-layer state plus the branch that reads it) — and one was never a gap at all: 由衣's shield
 * duration is a <b>design value</b> the source does not state, which belongs in {@code _notes} rather than in a
 * field the page renders as 「尚未实现」.
 *
 * <h2>What each assertion is worth</h2>
 * <ul>
 *   <li><b>the data</b> — the clause is in the JSON, with the numbers this task derived;</li>
 *   <li><b>the behaviour</b> — the mechanism it now runs through, driven at the seam that needs no world:
 *       {@code Knockbacks}' integrator, vanilla's regeneration instalment count, and {@link HookPlan}'s state
 *       gate.</li>
 * </ul>
 *
 * <p><b>What a JVM cannot prove</b>, and the report says so: that a one-block launch <em>looks</em> like a launch
 * in a real client, and that a 18.5-second regeneration feels like 「每秒恢复」 rather than like a potion. Both
 * need a person in the game.
 */
class Task63ResourceTest {

    private static final double TICKS_PER_SECOND = 20.0;

    // ------------------------------------------------------------------ 1–2. 绫音's launch height

    /**
     * <b>「击飞高度 800」 is a number in her data now, at the pack's own battlefield scale.</b>
     *
     * <p>800 battlefield units × the 13000 → 16 blocks ratio the file already uses for 「击退距离 2160」 is
     * <b>0.985</b> blocks, written as {@code 1.0}. Both tiers carry it, and both {@code extra} sentences are gone —
     * a closed gap that kept its sentence would render as 「尚未实现」 beside a skill that does it.
     */
    @Test
    void ayanesLaunchHeightIsAFieldOnBothTiers() throws Exception {
        CharacterDef def = load("ayane");
        for (UbTier tier : List.of(def.ub().base(), def.ub().enhanced())) {
            UbEffect kick = tier.effects().stream()
                    .filter(effect -> effect.type() == EffectType.KNOCKBACK).findFirst()
                    .orElseThrow(() -> new AssertionError("both tiers keep the knockback: " + tier.effects()));
            assertEquals(OptionalDouble.of(2.0), kick.launch(),
                    "「击飞高度」 is 800 × 16 / 13000 = 0.985 blocks from the source, but the human raised it: 「击飞飞2格起步」 — so the data declares 2.0 and the apex really is two blocks. The source number is kept in this comment because it is why the field exists at all.");
            assertTrue(kick.mismatch().isEmpty(),
                    "a launch on a knockback is a legal pair, or the clause would be dropped at settlement: "
                            + kick.mismatch().orElse(""));
            assertTrue(tier.extra().isEmpty(), "the gap sentence is deleted: " + tier.extra().orElse(""));
        }
        // And the page states it: a field the detail panel did not draw would be a number a player cannot read —
        // the same complaint the whole book exists to answer.
        var page = com.astrum.pcrd.impl.client.SkillDetailModel.of(def, 1, 30).entry(0);
        var kickRow = page.rows().stream().filter(row -> row.type() == EffectType.KNOCKBACK).findFirst()
                .orElseThrow(() -> new AssertionError("the knockback must be a row: " + page.rows()));
        assertEquals("2", kickRow.numbers().stream()
                        .filter(number -> number.labelKey().equals(BookText.LABEL_HEIGHT))
                        .map(com.astrum.pcrd.impl.client.SkillDetailModel.Number::value)
                        .findFirst().orElse(null),
                "the 「击飞高度」 row must be on her ultimate's page");

        // The height is a real height: the velocity that reaches it is above vanilla's own 0.4 cap, which is the
        // whole reason the rung cannot carry it.
        assertTrue(Knockbacks.launchVelocityFor(1.0) > Knockbacks.VANILLA_STRENGTH,
                "a one-block launch must out-pull vanilla's capped pop");
        assertEquals(1.0, Knockbacks.apexOf(Knockbacks.launchVelocityFor(1.0)), 1e-6,
                "and it must reach exactly the declared height");
    }

    /**
     * <b>A launch on anything but a knockback is refused rather than ignored.</b>
     *
     * <p>The pairing rule lives in {@code UbEffect.mismatch}, which is what the loader's WARN and this assertion
     * read: a height on a panic would be a number nothing reads, and a silent ignore is how a datapack keeps a
     * number that does nothing.
     */
    @Test
    void aLaunchHeightBelongsToAKnockbackAlone() {
        UbEffect stray = new UbEffect(UbEffect.Scope.VICTIM, EffectType.PANIC, TieredParams.ofTier(1),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, OptionalDouble.empty(), 1.0,
                Optional.empty(), OptionalDouble.of(2.0));
        assertTrue(stray.mismatch().isPresent(), "launch on a panic must be a reported error");
        assertTrue(stray.mismatch().orElseThrow().contains("knockback"),
                "and the message must name the effect it belongs to: " + stray.mismatch().orElseThrow());

        UbEffect legal = new UbEffect(UbEffect.Scope.VICTIM, EffectType.KNOCKBACK, TieredParams.ofTier(2),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, OptionalDouble.empty(), 1.0,
                Optional.empty(), OptionalDouble.of(2.0));
        assertTrue(legal.mismatch().isEmpty(), "on a knockback it is legal");
        // A hostile height is "no launch", the same one-directional reading the chance field gives a hostile
        // probability: nothing may lift a victim downwards.
        UbEffect negative = new UbEffect(UbEffect.Scope.VICTIM, EffectType.KNOCKBACK, TieredParams.ofTier(2),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, OptionalDouble.empty(), 1.0,
                Optional.empty(), OptionalDouble.of(-3.0));
        assertEquals(OptionalDouble.empty(), negative.launch(), "a negative height is not a launch");
    }

    // ------------------------------------------------------------------ 3. 胡桃's over-time heal

    /**
     * <b>「每秒恢复…持续18.5秒」 is a real regeneration instance now, not one big instant heal.</b>
     *
     * <p>Her clause was written as a one-shot {@code heal_area} because {@code UbEffect} could not carry a named
     * effect's duration or level. It can: {@code vanillaEffect}, {@code amplifier} and
     * {@code effectParams.seconds} are all on the record, so the clause is {@code minecraft:regeneration} (the
     * mechanic 「継続HP回復」 names, §12) at amplifier I for the source's own 18.5 seconds — and the sentence that
     * explained the approximation is deleted rather than reworded.
     *
     * <p><b>The residual approximation, stated.</b> Vanilla's regeneration has <em>levels</em> and no per-second
     * amount: amplifier 0 is one point every 50 ticks — 0.4 a second, where the source's 247.2 raw a second is
     * 0.223 on this pack's scale. The rate is therefore the nearest rung and about 1.8× the derived one, while the
     * <b>duration is exact</b>. That is in her {@code _notes}; it is not a mechanism this build lacks.
     */
    @Test
    void kurumisHealIsOverTimeAndTotalsRepeatedInstalments() throws Exception {
        CharacterDef def = load("kurumi");
        UbTier enhanced = def.ub().enhanced();
        assertFalse(enhanced.effects().stream().anyMatch(effect -> effect.type() == EffectType.HEAL_AREA),
                "the one-shot approximation must be gone: " + enhanced.effects());
        UbEffect regen = enhanced.effects().stream()
                .filter(effect -> effect.type() == EffectType.ALLY_EFFECT).findFirst()
                .orElseThrow(() -> new AssertionError("the over-time heal is an ally_effect: "
                        + enhanced.effects()));
        assertEquals(Optional.of(ResourceLocation.parse("minecraft:regeneration")), regen.vanillaEffect(),
                "「継続HP回復」 is vanilla regeneration, the same clause ペコリーヌ's 技能2 has");
        assertEquals(0, regen.amplifier(), "amplifier 0 = level I, the lowest rung of vanilla's ladder");
        assertEquals(OptionalDouble.of(10.3), regen.params().seconds(),
                "10.3 s, <b>not</b> the source's 18.5 s. The human asked for it weaker (「削弱」): vanilla regeneration has a <b>rate floor</b> of 0.4 HP/s at amplifier 0, which over the source's 18.5 s heals 7.4 HP against the derived 4.13 — 1.8× too strong — and there is no slower rung to pick. Duration is therefore the only lever: 4.13 / 0.4 = 10.3 s makes the <b>total</b> match the derivation, at the cost of a shorter clock than the source states.");
        assertEquals(OptionalDouble.of(4.0), regen.params().radius(), "半径225 → 4 blocks, unchanged");
        assertTrue(regen.mismatch().isEmpty(), "a named effect on the party is a legal pair: "
                + regen.mismatch().orElse(""));
        assertTrue(enhanced.extra().isEmpty(), "the approximation sentence is deleted: "
                + enhanced.extra().orElse(""));

        // The behaviour: the instance pays one point per instalment, and there are many instalments — the property
        // "no one-shot heal could have". `shouldApplyEffectTickThisTick` is `duration % (50 >> amplifier) == 0`, so
        // the count over the whole instance is ticks / period.
        int ticks = (int) Math.round(regen.params().seconds().orElseThrow() * TICKS_PER_SECOND);
        assertEquals(206, ticks, "10.3 s at 20 ticks a second");
        int period = 50 >> regen.amplifier();
        assertEquals(50, period, "vanilla's amplifier-0 period");
        int instalments = ticks / period;
        assertTrue(instalments > 1, "a heal over time pays several times, got " + instalments);
        assertEquals(4, instalments, "206 ticks at one point every 50 is four instalments");
        // The total now MATCHES the derivation instead of overshooting it. At the source's 18.5 s the same
        // amplifier-0 rate paid seven instalments against a derived 4.13 — 1.8x too strong, and vanilla has no
        // slower rung. The human asked for it weaker (「削弱」), so the clock carries the correction.
        double derivedTotal = 20.0 * 4573.0 / 22140.0;
        assertTrue(Math.abs(instalments - derivedTotal) / derivedTotal < 0.25,
                "the shipped total must match the derivation (" + derivedTotal + ") within a quarter, got "
                        + instalments + " — a fifth instalment would be 25% over");
    }

    // ------------------------------------------------------------------ 4. 矛依未's mark, and its branch

    /**
     * <b>「追加 1 个 ID 125，180 秒，最大 1 层」 is a granted one-layer state.</b>
     *
     * <p>A state is a {@code MobEffect} on the caster ({@code States}), so 「最大 1 层」 is not a cap anybody
     * enforces: vanilla keys effects by type, so a second grant refreshes the same instance. Amplifier 0 is
     * therefore the whole of "one layer", and 180 seconds is the source's own clock.
     */
    @Test
    void muimisMarkIsAGrantedOneLayerState() throws Exception {
        CharacterDef def = load("muimi");
        UbEffect mark = def.ub().enhanced().effects().stream()
                .filter(effect -> effect.vanillaEffect().equals(Optional.of(States.MARKER_125))).findFirst()
                .orElseThrow(() -> new AssertionError("her 6★UB must grant 标记 125: "
                        + def.ub().enhanced().effects()));
        assertEquals(EffectType.ALLY_EFFECT, mark.type(), "a named state is an ally_effect");
        assertEquals(UbEffect.Scope.ALLY, mark.scope());
        assertEquals(0, mark.amplifier(), "one layer, and a second grant only refreshes it");
        assertEquals(OptionalDouble.of(180.0), mark.params().seconds(), "「180 秒」");
        assertEquals(OptionalDouble.of(0.0), mark.params().radius(), "radius 0 — the state is hers alone");
        assertTrue(mark.mismatch().isEmpty(), mark.mismatch().orElse(""));

        // It is registered as a mechanic, which is what makes `/effect give` work and what the sprite test reads:
        // a state on the bar with no sprite is the purple checkerboard.
        List<String> mechanics = new ArrayList<>();
        for (var holder : PartyEffects.mechanicHolders()) {
            mechanics.add(holder.getKey().location().getPath());
        }
        assertTrue(mechanics.contains(States.MARKER_125_PATH),
                "astrum:marker_125 must be a registered mechanic, got " + mechanics);

        // And the extras that described it as unimplemented are gone.
        assertTrue(def.ub().enhanced().extra().isEmpty(), "the 标记 sentence is closed: "
                + def.ub().enhanced().extra().orElse(""));
        var spec = SecondRotationRuntime.secondRotationOf(def).orElseThrow();
        // ... and the whole note is gone, not merely narrowed: the last open clause (SP2's 「以自身为中心」)
        // became `EffectHook.Splash#casterCentred`. See CasterCentredSplashTest.
        assertTrue(spec.extra().isEmpty(),
                "the branch sentence is closed too, and so is the rest of the note: "
                        + spec.extra().orElse(""));
    }

    /**
     * <b>The branch fires while she holds the mark, and only then.</b>
     *
     * <p>Driven through {@link HookPlan}'s real state gate on her <em>shipped</em> second-loop hooks: with the state
     * held, SP1 and SP2 each fire their base hit <b>and</b> their 追加 segment; with it gone, only the base hits
     * run. No cleanup, no mutation — the gate is re-asked on every firing, which is exactly what makes an expiring
     * 180-second mark stop adding damage with nothing to reset.
     */
    @Test
    void muimisExtraSegmentsFireOnlyWhileTheMarkIsHeld() throws Exception {
        CharacterDef def = load("muimi");
        var spec = SecondRotationRuntime.secondRotationOf(def).orElseThrow();
        List<EffectHook> upgraded = spec.hooksAt(2);

        List<EffectHook> whileHeld = fire(upgraded, Set.of(States.MARKER_125));
        List<EffectHook> afterExpiry = fire(upgraded, Set.of());

        assertEquals(5, whileHeld.size(), "SP1 40.0 + its 16.3, SP2 14.1 + splash + its 14.1: " + whileHeld);
        assertEquals(3, afterExpiry.size(), "without the mark only the ungated hooks run: " + afterExpiry);
        assertEquals(16.3, gatedAmount(whileHeld, "sp1"), 1e-9,
                "SP1+'s 追加 segment is 3.9A+48.75+48.75L = 16.3");
        assertEquals(14.1, gatedAmount(whileHeld, "sp2"), 1e-9,
                "SP2's is a repeat of its own 14.1");
        assertTrue(Double.isNaN(gatedAmount(afterExpiry, "sp1")),
                "an expired mark must leave no extra segment behind");
        assertEquals(40.0, ungatedAmount(whileHeld, "sp1"), 1e-9,
                "and the base hit is the same number whether the mark is held or not");
        assertEquals(40.0, ungatedAmount(afterExpiry, "sp1"), 1e-9);
    }

    // ------------------------------------------------------------------ 5. 由衣's design value

    /**
     * <b>由衣's 8-second barrier is a documented design value, not an unimplemented gap.</b>
     *
     * <p>Her bwiki clause gives no duration, so the pack chose one — 8 seconds, the same number 佩可莉姆's barrier
     * uses. That is a decision, and a decision rendered as 「尚未实现」 is a lie, so the sentence moved to
     * {@code _notes} and {@code extra} is empty. The barrier itself is unaffected: same rung, same clock, still
     * physical-only.
     */
    @Test
    void yuisShieldDurationIsADesignValueNotAGap() throws Exception {
        CharacterDef def = load("yui");
        assertTrue(def.ub().enhanced().extra().isEmpty(),
                "a design value must not be rendered as an unimplemented clause: "
                        + def.ub().enhanced().extra().orElse(""));
        // `_notes` is authoring prose and not part of the 16-component codec, so it is read from the raw file —
        // which is also the only way to assert that a <em>sentence</em> moved rather than being deleted.
        String notes = rawField("yui", "_notes");
        assertTrue(notes.contains("8 秒") && notes.contains("设计值"),
                "the design value must still be recorded, in the notes where a reader looks for design "
                        + "decisions");
        UbEffect barrier = def.ub().enhanced().effects().stream()
                .filter(effect -> effect.type() == EffectType.SHIELD_PHYS).findFirst()
                .orElseThrow(() -> new AssertionError("her 6★ barrier is still the physical-only channel"));
        assertEquals(OptionalDouble.of(8.0), barrier.params().seconds(), "the design value is still 8 seconds");
        assertEquals(2, barrier.params().tier(), "and the rung the bwiki formula converts to");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@return every hook that ran over two swings, when the caster holds {@code held}}
     *
     * <p>Two swings, not one: her second loop is <b>two</b> rotating skills, and {@code HookPlan} runs one skill
     * per swing (that is the rotation). A single call would therefore only ever see SP1's pair, and a test that
     * asserted "the extra segment fires" against it would pass whether SP2's did or not.
     */
    private static List<EffectHook> fire(List<EffectHook> hooks, Set<ResourceLocation> held) {
        List<EffectHook> fired = new ArrayList<>();
        SkillRotation rotation = SkillRotation.START;
        for (int swing = 0; swing < 2; swing++) {
            HookPlan.Outcome outcome = HookPlan.fireTiered(hooks,
                    TriggerFacts.forHit(true, false, false, 1, false), chance -> true,
                    (index, hook, tier) -> {
                        fired.add(hook);
                        return Optional.empty();
                    }, invalid -> {
                    }, rotation, 30, held::contains);
            rotation = outcome.rotation();
        }
        return fired;
    }

    /** {@return the amount of {@code skill}'s state-gated {@code extra_damage}, or {@code NaN}} */
    private static double gatedAmount(List<EffectHook> hooks, String skill) {
        for (EffectHook hook : hooks) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skill)
                    && hook.triggerParams().requiresState().isPresent()) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    /** {@return the amount of {@code skill}'s ungated {@code extra_damage}, or {@code NaN}} */
    private static double ungatedAmount(List<EffectHook> hooks, String skill) {
        for (EffectHook hook : hooks) {
            if (hook instanceof EffectHook.ExtraDamage damage
                    && hook.triggerParams().skillTag().orElse("").equals(skill)
                    && hook.triggerParams().requiresState().isEmpty()) {
                return damage.amount();
            }
        }
        return Double.NaN;
    }

    /**
     * {@return one top-level string field of a character's raw JSON}
     *
     * <p>For the fields the codec deliberately does not carry: {@code _notes} is prose for the next author, not a
     * value the game reads. Reading it here is what lets the test see a sentence that moved.
     */
    private static String rawField(String id, String field) {
        String resource = "/data/astrum/astrum/character/" + id + ".json";
        try (InputStream in = Task63ResourceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "the character data must be on the test classpath: " + resource);
            JsonElement json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            JsonElement value = json.getAsJsonObject().get(field);
            assertNotNull(value, id + ".json must carry " + field);
            return value.getAsString();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    /** {@return the character's data, read through the real codec} */
    private static CharacterDef load(String id) {
        String resource = "/data/astrum/astrum/character/" + id + ".json";
        try (InputStream in = Task63ResourceTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "the character data must be on the test classpath: " + resource);
            JsonElement json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }
}
