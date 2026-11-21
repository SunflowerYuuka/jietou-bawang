package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.client.TestProjectRoot;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The three movement-control mechanics of task 56</b>, asserted without a running game.
 *
 * <h2>What a unit test can and cannot reach here</h2>
 * A pull, a knockback and a taunt all end in a call that needs a live entity ({@code teleportTo},
 * {@code knockback}, {@code setTarget}), and no entity can be constructed in a plain JVM. So the assertions
 * are made at the two seams each mechanic was built around:
 *
 * <ul>
 *   <li><b>the arithmetic</b> — {@link Pulls#destination} takes the obstruction test as a predicate, so a
 *       test can plant a wall at a chosen coordinate and check where the body stops; the knockback's strength
 *       ladder is {@code EffectTiers}' table, which is data;</li>
 *   <li><b>the decision</b> — {@link Taunts#tick} / {@link Taunts#release} work over {@link Taunts.Focusable}
 *       rather than over {@code Mob}, so "focus the ones who are not looking at me, let go of the ones who
 *       are" is driven by a fake body that counts its calls.</li>
 * </ul>
 *
 * <p><b>What this cannot prove, and the report says so:</b> that the destination is reachable in a real
 * level, that vanilla's knockback <em>feels</em> like the rung the data chose, and that a mob's own target
 * selector does not out-vote the taunt for a tick. Those need a client and a person.
 */
class MovementControlTest {

    // ------------------------------------------------------------------ the pull

    /**
     * <b>A pull moves the victim toward the caster, by the distance its rung names.</b>
     *
     * <p>Ten blocks away, three-block rung, nothing in the way: the body must end three blocks closer, on the
     * line between them. That is the reading of 「拉到身前 300」 this build implements — travel, not "land
     * three blocks from her" — and this test is what pins it.
     */
    @Test
    void aPullMovesTheBodyTowardTheCaster() {
        Vec3 victim = new Vec3(0.0, 64.0, 0.0);
        Vec3 caster = new Vec3(10.0, 64.0, 0.0);
        Vec3 destination = Pulls.destination(victim, caster, 3.0, Pulls.MIN_SEPARATION, null);
        assertEquals(3.0, destination.distanceTo(victim), 1e-9, "the rung is three blocks of travel");
        assertEquals(7.0, destination.distanceTo(caster), 1e-9,
                "and it starts ten blocks away, so it ends seven from her");
        assertEquals(64.0, destination.y, 1e-9, "a flat pull keeps the body on its own level");
    }

    /**
     * <b>A pull never puts the body through a wall, or into one.</b>
     *
     * <p>The obstruction test is a wall at {@code x = 2}: the body starts at 0 and is pulled five blocks, but
     * the last clear point is at or before the wall. The ender-pearl rule this mirrors is "stop where the
     * space ends", not "land on the far side and suffocate".
     */
    @Test
    void aPullStopsAtAWallInsteadOfGoingThroughIt() {
        Vec3 victim = new Vec3(0.0, 64.0, 0.0);
        Vec3 caster = new Vec3(10.0, 64.0, 0.0);
        Vec3 destination = Pulls.destination(victim, caster, 5.0, Pulls.MIN_SEPARATION,
                point -> point.x >= 2.0);
        assertTrue(destination.x <= 2.0, "the body must not cross the wall, got x=" + destination.x);
        assertTrue(destination.x > 0.0, "it should still have moved up to the wall, got x=" + destination.x);
        assertEquals(0.0, destination.z, 1e-9, "a straight pull does not slide sideways");
    }

    /**
     * <b>A pull leaves the caster her own space.</b>
     *
     * <p>A body two blocks away with a five-block rung would otherwise be placed inside her; the separation
     * floor stops it one block out, and a body already inside that floor does not move at all (pushing it
     * away is not a pull).
     */
    @Test
    void aPullStopsAtTheSeparationFloor() {
        Vec3 caster = new Vec3(0.0, 64.0, 0.0);
        Vec3 near = Pulls.destination(new Vec3(2.0, 64.0, 0.0), caster, 5.0, Pulls.MIN_SEPARATION, null);
        assertEquals(Pulls.MIN_SEPARATION, near.distanceTo(caster), 1e-9);
        Vec3 touching = new Vec3(0.5, 64.0, 0.0);
        assertEquals(touching, Pulls.destination(touching, caster, 5.0, Pulls.MIN_SEPARATION, null),
                "already inside the floor: nothing to do, and no push");
    }

    // ------------------------------------------------------------------ the knockback

    /**
     * <b>The kick is vanilla's own strength ladder, and tier 1 is one plain hit.</b>
     *
     * <p>{@code LivingEntity#hurt} passes {@code 0.4F} to {@code knockback} at the end of every damage
     * sequence (verified in 21.1.265), so 0.4 is the unit the human's 「原版不是有吗」 refers to. The ladder
     * is that number times the rung, and the data chooses the rung — which is exactly his 「距离不够就改数值」.
     */
    @Test
    void knockbackUsesTheVanillaStrengthLadder() {
        assertEquals(Knockbacks.VANILLA_STRENGTH, EffectTiers.ruleOf(EffectType.KNOCKBACK).fractionAt(1),
                1e-12, "tier 1 must be exactly the push a hit already gives");
        double previous = 0.0;
        for (int tier = 1; tier <= EffectTiers.maxTier(EffectType.KNOCKBACK); tier++) {
            double strength = EffectTiers.ruleOf(EffectType.KNOCKBACK).fractionAt(tier);
            assertTrue(strength > previous, "the ladder must be strictly increasing at tier " + tier);
            previous = strength;
        }
        assertEquals(0.8, EffectTiers.ruleOf(EffectType.KNOCKBACK).fractionAt(2), 1e-12,
                "绫音's rung: twice a plain hit");
    }

    /** <b>The direction is away from the caster</b>, which is what makes a kick a kick and not a pull. */
    @Test
    void knockbackPointsAwayFromTheCaster() {
        Vec3 away = Knockbacks.direction(new Vec3(3.0, 64.0, 0.0), new Vec3(0.0, 64.0, 0.0));
        assertEquals(1.0, away.x, 1e-9);
        assertEquals(0.0, away.z, 1e-9);
        Vec3 mirrored = Knockbacks.direction(new Vec3(-3.0, 64.0, 0.0), new Vec3(0.0, 64.0, 0.0));
        assertEquals(-1.0, mirrored.x, 1e-9, "the opposite side pushes the opposite way");
        // A vertical offset must not lift the direction: vanilla's knockback is horizontal, and its own
        // vertical component is what gives a body its hop.
        assertEquals(0.0, Knockbacks.direction(new Vec3(0.0, 70.0, 1.0), new Vec3(0.0, 64.0, 0.0)).y,
                1e-9);
        assertEquals(0.0, Knockbacks.direction(new Vec3(1.0, 64.0, 1.0), new Vec3(1.0, 64.0, 1.0))
                        .lengthSqr(), 1e-9,
                "coincident bodies answer zero, and vanilla's own call then draws a random direction");
    }

    /**
     * <b>{@link Knockbacks} keeps vanilla's call, and writes a velocity only for a declared launch.</b>
     *
     * <p>The human's instruction was 「直接调用就好了」, and the risk this guards is the natural "improvement" of
     * hand-rolling an impulse for a longer kick: an unguarded {@code setDeltaMovement} would make the strength
     * ladder a lie and would bypass {@code KNOCKBACK_RESISTANCE} and NeoForge's {@code LivingKnockBackEvent}.
     *
     * <p><b>Task 63 changed the shape of this claim, not its purpose.</b> 绫音's 「击飞高度」 is a height vanilla
     * cannot express — {@code knockback} caps its vertical component at {@code 0.4} whatever the strength — so
     * there is now exactly one velocity write, and it is the launch: it comes <b>after</b> vanilla's call, it
     * touches the y component only (so the horizontal push is still vanilla's), and it is behind a
     * {@code launchHeight > 0.0} guard, so an ordinary knockback writes nothing. A source scan is crude, and it
     * is still the only way to assert an <em>absence</em> without a server.
     */
    @Test
    void theKnockbackClassCallsVanillaAndWritesVelocityOnlyForALaunch() throws Exception {
        Path file = TestProjectRoot.resolve("src/main/java/com/astrum/pcrd/impl/effect/Knockbacks.java");
        assertNotNull(file, "the source tree must be reachable");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(source.contains(".knockback("), "the vanilla call must be what moves the body");
        int vanilla = source.indexOf(".knockback(");
        int launch = source.indexOf("setDeltaMovement");
        assertTrue(launch < 0 || launch > vanilla,
                "any velocity write must come AFTER vanilla's push, or it would erase the strength ladder, "
                        + "the resistance rule and the knockback event");
        assertTrue(source.contains("victim.hasImpulse = true"),
                "and a launch must set hasImpulse, or the client smooths the server's delta away");
    }

    /**
     * <b>A launch height is inverted through the game's own integrator, not through {@code v² = 2gh}.</b>
     *
     * <p>The two constants are {@code LivingEntity#travel}'s: gravity 0.08 a tick, drag 0.98 on the fall. So a body
     * launched at vanilla's own 0.4 peaks at about <b>0.75</b> blocks, not 1.0 — which is exactly why a formula
     * that ignored the drag would answer a velocity that overshoots the height the data declares. The test pins
     * both directions: the integrator at that known point, and the inversion achieving the height it is asked for.
     */
    @Test
    void aLaunchHeightIsInvertedThroughTheGamesOwnIntegrator() {
        assertEquals(0.7531, Knockbacks.apexOf(Knockbacks.VANILLA_STRENGTH), 5e-4,
                "vanilla's own 0.4 pop reaches ~0.75 blocks: the ladder tops out there whatever the strength");
        assertEquals(0.0, Knockbacks.apexOf(0.0), 1e-12, "no velocity, no height");
        assertEquals(0.0, Knockbacks.launchVelocityFor(0.0), 1e-12, "no height, no launch");
        for (double blocks : List.of(0.5, 1.0, 1.5, 2.0, 4.0)) {
            double velocity = Knockbacks.launchVelocityFor(blocks);
            assertEquals(blocks, Knockbacks.apexOf(velocity), 1e-6,
                    "the velocity must reach exactly the height the clause declares");
        }
        assertTrue(Knockbacks.launchVelocityFor(1.0) > Knockbacks.VANILLA_STRENGTH,
                "one block is higher than vanilla's cap, so the launch has to add something: "
                        + Knockbacks.launchVelocityFor(1.0));
    }

    // ------------------------------------------------------------------ the taunt

    /**
     * <b>The taunt sets a mob's target, and clears it when the effect ends.</b>
     *
     * <p>A fake {@link Taunts.Focusable} records which of the two calls it got. The rule under test is
     * {@link Taunts#tick}: a body that is <em>not</em> already on the taunter is focused, one that is is left
     * alone; and {@link Taunts#release}: only a body still on her is let go. {@code finalTick(1)} is the
     * instance's last tick, which is where the effect releases — the one point a {@code MobEffect} can observe
     * its own expiry without a tick listener.
     */
    @Test
    void theTauntFocusesAndThenReleases() {
        FakeMob mob = new FakeMob();
        List<Taunts.Focusable> seen = List.of(mob);
        assertEquals(1, Taunts.tick(seen), "a mob that is not on the taunter is focused");
        assertEquals(1, mob.focuses);
        assertEquals(0, Taunts.tick(seen), "already on her: nothing to re-assert (no packet churn)");
        assertEquals(1, mob.focuses);
        assertEquals(1, Taunts.release(seen), "the clock ran out, so the target is cleared");
        assertEquals(1, mob.releases);
        assertEquals(0, Taunts.release(seen), "and a released mob is not disturbed again");
    }

    /** Vanilla's own tick order is what makes the last tick observable; assert the predicate on it. */
    @Test
    void theLastTickOfTheInstanceIsTheRelease() {
        assertTrue(Taunts.finalTick(1), "one tick left: this is the tick it ends after");
        assertTrue(Taunts.finalTick(0), "the removal tick is final too");
        assertFalse(Taunts.finalTick(2), "two ticks left is not the end");
        assertFalse(Taunts.finalTick(200));
    }

    /** A mob, as the rule sees it: counts the two calls rather than holding a target. */
    private static final class FakeMob implements Taunts.Focusable {
        int focuses;
        int releases;
        boolean onTaunter;

        @Override
        public boolean targetsTaunter() {
            return onTaunter;
        }

        @Override
        public void focus() {
            focuses++;
            onTaunter = true;
        }

        @Override
        public void release() {
            releases++;
            onTaunter = false;
        }
    }

    // ------------------------------------------------------------------ the hooks and the shipped data

    /** The three hooks round-trip through the real hook codec, under the names the JSON writes. */
    @Test
    void theThreeHooksRoundTripThroughTheRealCodec() {
        EffectHook pull = hook("""
                {"trigger":"melee_hit","effect":"pull","effectParams":{"tier":3}}""");
        assertEquals(EffectType.PULL, pull.type());
        assertEquals(3, pull.tieredParams().orElseThrow().tier());

        EffectHook knockback = hook("""
                {"trigger":"melee_hit","effect":"knockback","effectParams":{"tier":2}}""");
        assertEquals(EffectType.KNOCKBACK, knockback.type());
        assertEquals(2, knockback.tieredParams().orElseThrow().tier());

        EffectHook taunt = hook("""
                {"trigger":"melee_hit","effect":"taunt",
                 "effectParams":{"tier":1,"seconds":10.0}}""");
        assertEquals(EffectType.TAUNT, taunt.type());
        assertEquals(OptionalDouble.of(10.0), taunt.tieredParams().orElseThrow().seconds());
    }

    /**
     * <b>纺希's three clauses are wired, and her {@code extra} no longer calls them unimplemented.</b>
     *
     * <p>「一个 extra 描述一个已经补上的缺口，就是一句假话」: the sentence may only name what is still missing.
     * So this asserts the data <em>twice</em> — the {@code pull} effect exists in both tiers and on her skill,
     * and the text does not claim there is no displacement mechanic.
     */
    @Test
    void tsumugiPullsAndHerExtraNoLongerNamesTheGap() {
        CharacterDef def = character("tsumugi");
        for (UbTier tier : List.of(def.ub().base(), def.ub().enhanced())) {
            assertTrue(hasVictim(tier, EffectType.PULL), "both UB tiers must carry the pull");
            assertFalse(tier.extra().orElse("").contains("没有位移"),
                    "the closed gap must not still be described as unimplemented: " + tier.extra());
            assertFalse(tier.extra().orElse("").contains("位移均无机制"));
        }
        assertTrue(def.effects().stream().anyMatch(hook -> hook.type() == EffectType.PULL),
                "her skill's 「吸引 100」 is one block, so it is a pull hook on the skill");
    }

    /**
     * <b>望's ultimates taunt, and her {@code extra} is gone.</b>
     *
     * <p>Both tiers used to carry 「嘲讽自身 10 / 11.5 秒…未实现」. The effect is now declared on each tier
     * with its own duration, and a sentence describing a closed gap has been deleted rather than rewritten.
     */
    @Test
    void nozomiTauntsAndHerExtraIsGone() {
        CharacterDef def = character("nozomi");
        List<UbTier> tiers = List.of(def.ub().base(), def.ub().enhanced());
        double[] seconds = {10.0, 11.5};
        for (int i = 0; i < tiers.size(); i++) {
            UbTier tier = tiers.get(i);
            UbEffect taunt = ally(tier, EffectType.TAUNT);
            assertNotNull(taunt, "tier " + i + " must carry the taunt");
            assertEquals(UbEffect.Scope.ALLY, taunt.scope(),
                    "the taunter carries it, so it is party-scoped");
            assertEquals(OptionalDouble.of(seconds[i]), taunt.params().seconds(),
                    "bwiki's 10 / 11.5 second durations, one per tier");
            assertTrue(tier.extra().isEmpty(), "a closed gap must not leave an `extra` sentence behind");
        }
    }

    /**
     * <b>绫音's two halves are both wired: the kick, and the height it lifts to.</b>
     *
     * <p>Her clause is 「击飞距离 2160 / 击飞高度 800」. The first half is the knockback rung (vanilla's strength,
     * tier 2 = 0.8). The second half used to be an {@code extra} sentence — 「击飞高度 800 未实现」 — because vanilla
     * caps a knockback's vertical component at 0.4; task 63 gave it a channel ({@code launch} on the ultimate's
     * clause), so the sentence is gone and the number is in the data, on both tiers.
     *
     * <p>The number's arithmetic: the pack's own battlefield-to-blocks ratio (13000 → 16, the same one that turned
     * 2160 into about 2.7 blocks) makes 800 units <b>0.985</b> blocks, written as {@code 1.0}.
     */
    @Test
    void ayaneKnocksBackAndLaunchesHerDeclaredHeight() {
        CharacterDef def = character("ayane");
        for (UbTier tier : List.of(def.ub().base(), def.ub().enhanced())) {
            assertTrue(hasVictim(tier, EffectType.KNOCKBACK), "both UB tiers must carry the knockback");
            UbEffect kick = victim(tier, EffectType.KNOCKBACK);
            assertEquals(OptionalDouble.of(2.0), kick.launch(),
                    "「击飞高度」 is 800 × 16 / 13000 = 0.985 blocks from the source, but the human raised it: 「击飞飞2格起步」 — so the data declares 2.0 and the apex really is two blocks. The source number is kept in this comment because it is why the field exists at all.");
            assertEquals(2, kick.params().tier(), "and the distance half is still the tier-2 strength");
            assertTrue(tier.extra().isEmpty(),
                    "a closed gap must not leave a sentence behind — it would render as 「尚未实现」: "
                            + tier.extra().orElse(""));
        }
        // The launch is a real height, not a decoration: at 1.0 block the body leaves the ground faster than
        // vanilla's own cap, and reaches the declared apex.
        assertTrue(Knockbacks.launchVelocityFor(1.0) > Knockbacks.VANILLA_STRENGTH);
        assertEquals(1.0, Knockbacks.apexOf(Knockbacks.launchVelocityFor(1.0)), 1e-6);
    }

    /** {@return the first clause of {@code type} on a tier, or an assertion failure} */
    private static UbEffect victim(UbTier tier, EffectType type) {
        return tier.effects().stream().filter(effect -> effect.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("tier declares no " + type + ": " + tier.effects()));
    }

    // ------------------------------------------------------------------ helpers

    private static EffectHook hook(String json) {
        JsonElement element = JsonParser.parseString(json);
        return EffectHook.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
    }

    private static CharacterDef character(String id) {
        String resource = "/data/astrum/astrum/character/" + id + ".json";
        try (InputStream in = MovementControlTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "the character data must be on the test classpath: " + resource);
            JsonElement json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
    }

    /**
     * {@return whether {@code type} is a victim-scope effect of {@code tier}, on the tier or on one of its clauses}
     *
     * <p>Both lists are asked because both are the same thing to a victim: 纺希's pull moved from the tier's list
     * onto a clause (the {@code farthest} clause, because 「将最远的敌人拉近」 names a body and not the group), and
     * a test that only looked at the tier's list would report the mechanic as missing while it is declared one
     * field deeper.
     */
    private static boolean hasVictim(UbTier tier, EffectType type) {
        if (declaresVictim(tier.effects(), type)) {
            return true;
        }
        for (com.astrum.pcrd.api.v1.UbSegment segment : tier.segments()) {
            if (declaresVictim(segment.effects(), type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresVictim(List<UbEffect> effects, EffectType type) {
        return effects.stream()
                .anyMatch(effect -> effect.scope() == UbEffect.Scope.VICTIM && effect.type() == type);
    }

    private static UbEffect ally(UbTier tier, EffectType type) {
        List<UbEffect> found = new ArrayList<>();
        for (UbEffect effect : tier.effects()) {
            if (effect.scope() == UbEffect.Scope.ALLY && effect.type() == type) {
                found.add(effect);
            }
        }
        assertEquals(1, found.size(), "exactly one " + type + " per tier, got " + found.size());
        return found.get(0);
    }
}