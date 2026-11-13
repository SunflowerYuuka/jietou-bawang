package com.astrum.pcrd.impl.showcase;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The despawn arithmetic, the placement maths and the active-entity cap — the three rules of spec §5.6
 * that can be wrong in a way nobody notices until an entity is standing in a world forever.
 *
 * <h2>Why these are unit tests and not a live run</h2>
 * "The entity goes away" is the property most likely to be broken by a future edit and the least
 * visible: a stuck showcase in an unloaded chunk does not appear in any screenshot, and one extra
 * {@code return} in a {@code tick()} can silently pin an entity in the world for a session. Making the
 * predicate a value means the boundaries (119 vs 120, animation done vs not, cap reached vs not) are
 * asserted rather than trusted.
 */
class ShowcaseLifetimeTest {

    // ------------------------------------------------------------------ the hard timeout

    /** The plan's own two assertions: the cap is reached exactly at the cap, not after it. */
    @Test
    void despawnsAtMaxLifetime() {
        // An animation longer than the cap, so only the timeout half can answer.
        assertFalse(ShowcaseLifetime.shouldDespawn(119, 200, 120),
                "one tick short of the cap is still alive");
        assertTrue(ShowcaseLifetime.shouldDespawn(120, 200, 120),
                "the cap tick itself is the last tick");
    }

    /**
     * The backstop's whole reason to exist: a lag spike, a chunk unload or a teleport can stop the
     * animation from ever reporting done, and {@code age} then just keeps climbing.
     */
    @Test
    void despawnsWhenTheAnimationNeverReportsDone() {
        assertTrue(ShowcaseLifetime.shouldDespawn(600, 120, 120),
                "a stuck animation must not keep the entity alive past its cap");
        assertTrue(ShowcaseLifetime.shouldDespawn(121, 10_000, 120),
                "a declared animation longer than the cap does not extend the cap");
    }

    /** A cap of zero or less is "no budget", which must not mean "unbounded". */
    @Test
    void aCapOfZeroOrDefaultsToImmediateDespawn() {
        assertTrue(ShowcaseLifetime.shouldDespawn(0, 100, 0), "a zero cap despawns on the first tick");
        assertTrue(ShowcaseLifetime.shouldDespawn(0, 100, -5), "a negative cap cannot mean forever");
        assertTrue(ShowcaseLifetime.timedOut(5, 0));
        assertFalse(ShowcaseLifetime.timedOut(5, 6));
    }

    /** The shipped cap is the plan's 120 ticks — a silent bump would outlive the animation by minutes. */
    @Test
    void theShippedCapIsThePlansNumber() {
        assertEquals(120, ShowcaseLifetime.MAX_LIFETIME_TICKS);
        assertEquals(4, ShowcaseLifetime.DESPAWN_GRACE_TICKS);
        assertTrue(ShowcaseManager.castAnimationTicks() + ShowcaseLifetime.DESPAWN_GRACE_TICKS
                        < ShowcaseLifetime.MAX_LIFETIME_TICKS,
                "the ordinary path must end well before the backstop, or every showcase dies by timeout");
    }

    // ------------------------------------------------------------------ the animation path

    /** The ordinary path: alive while the animation runs, gone once it plus the grace has elapsed. */
    @Test
    void despawnsAfterTheAnimationPlusItsGrace() {
        int animation = 28;
        int total = animation + ShowcaseLifetime.DESPAWN_GRACE_TICKS;
        assertFalse(ShowcaseLifetime.shouldDespawn(total - 1, animation, 120),
                "the last frame is still on screen one tick before the end");
        assertTrue(ShowcaseLifetime.shouldDespawn(total, animation, 120));
        // The animation's own last tick is not enough on its own — the grace is what shows the frame —
        // so the boundary is the grace, not the animation length.
        assertFalse(ShowcaseLifetime.shouldDespawn(animation, animation, 120),
                "the animation's last tick still gets its grace ticks");
    }

    /**
     * An unknown animation length ({@code 0}) must <b>not</b> be read as "already finished", or the
     * entity would vanish the tick it spawned and the timeout path would never be reachable. It means
     * "nothing to wait for", so the cap owns the lifetime.
     */
    @Test
    void anUnknownAnimationLengthLeavesTheCapInCharge() {
        assertFalse(ShowcaseLifetime.shouldDespawn(0, 0, 120));
        assertFalse(ShowcaseLifetime.shouldDespawn(50, 0, 120),
                "an entity with no animation lives to its cap, it does not pop");
        assertTrue(ShowcaseLifetime.shouldDespawn(120, 0, 120), "and the cap still ends it");
        assertFalse(ShowcaseLifetime.animationFinished(500, 0));
        assertFalse(ShowcaseLifetime.animationFinished(500, -1));
    }

    /** {@code animationTicks(seconds)} is what turns the animation file's length into the predicate's. */
    @Test
    void animationLengthConvertsAtTwentyTicksPerSecond() {
        assertEquals(28, ShowcaseLifetime.animationTicks(1.4), "1.4 s is 28 ticks");
        assertEquals(1, ShowcaseLifetime.animationTicks(0.01), "a floor of one tick, never zero");
        assertEquals(20, ShowcaseLifetime.animationTicks(1.0));
        assertEquals(0, ShowcaseLifetime.animationTicks(0.0), "no length is 'unknown', not 'zero ticks'");
        assertEquals(0, ShowcaseLifetime.animationTicks(-3.0));
        assertEquals(0, ShowcaseLifetime.animationTicks(Double.NaN));
        assertEquals(0, ShowcaseLifetime.animationTicks(Double.POSITIVE_INFINITY));
    }

    // ------------------------------------------------------------------ the cap

    /** 规格 §5.6: 超限只结算伤害不生成实体 — the entity is dropped at the cap, never the effect. */
    @Test
    void theCapForbidsOneMoreEntity() {
        assertTrue(ShowcaseLifetime.maySpawn(0, 8));
        assertTrue(ShowcaseLifetime.maySpawn(7, 8), "the 8th may spawn");
        assertFalse(ShowcaseLifetime.maySpawn(8, 8), "the 9th may not");
        assertFalse(ShowcaseLifetime.maySpawn(9, 8));
        assertFalse(ShowcaseLifetime.maySpawn(99, 8));
    }

    /** {@code maxActive = 0} switches the entity off; reading it as "unlimited" would be the opposite. */
    @Test
    void aCapOfZeroSwitchesTheEntityOff() {
        assertFalse(ShowcaseLifetime.maySpawn(0, 0));
        assertFalse(ShowcaseLifetime.maySpawn(0, -1));
    }

    // ------------------------------------------------------------------ placement

    /**
     * The placement maths: in front of the caster, at their feet height, one fixed short hop out.
     *
     * <p>Minecraft's yaw convention is the thing to get right and the reason this is tested rather
     * than eyeballed: yaw 0 faces <b>+Z</b> (south), not +X, and the offset is
     * {@code (-sin(yaw), 0, cos(yaw))} for that reason.
     */
    @Test
    void placementIsOneHopInFrontOfTheCaster() {
        Vec3 south = ShowcaseEntity.placement(10.0, 64.0, 10.0, 0.0F);
        assertEquals(10.0, south.x, 1e-6, "facing south (+Z) does not move x");
        assertEquals(10.0 + ShowcaseEntity.PLACEMENT_DISTANCE, south.z, 1e-6);
        assertEquals(64.0 + ShowcaseEntity.PLACEMENT_HEIGHT, south.y, 1e-6);

        Vec3 north = ShowcaseEntity.placement(10.0, 64.0, 10.0, 180.0F);
        assertEquals(10.0 - ShowcaseEntity.PLACEMENT_DISTANCE, north.z, 1e-6, "yaw 180 faces -Z");

        Vec3 west = ShowcaseEntity.placement(10.0, 64.0, 10.0, 90.0F);
        assertEquals(10.0 - ShowcaseEntity.PLACEMENT_DISTANCE, west.x, 1e-6, "yaw 90 faces -X");
        assertEquals(10.0, west.z, 1e-9);

        Vec3 east = ShowcaseEntity.placement(10.0, 64.0, 10.0, -90.0F);
        assertEquals(10.0 + ShowcaseEntity.PLACEMENT_DISTANCE, east.x, 1e-6, "yaw -90 faces +X");
    }

    /** The distance is always the same, whatever the yaw: a fixed hop, not a direction-dependent one. */
    @Test
    void placementDistanceIsConstant() {
        Vec3 origin = new Vec3(0.0, 0.0, 0.0);
        for (float yaw = -180.0F; yaw <= 180.0F; yaw += 15.0F) {
            Vec3 spot = ShowcaseEntity.placement(0.0, 0.0, 0.0, yaw);
            double horizontal = new Vec3(spot.x - origin.x, 0.0, spot.z - origin.z).length();
            assertEquals(ShowcaseEntity.PLACEMENT_DISTANCE, horizontal, 1e-6, "yaw " + yaw);
        }
    }

    /** A showcase is placed at the caster's feet height, not eye height: it is a body, not a camera. */
    @Test
    void placementKeepsTheCastersFeetHeight() {
        assertEquals(0.0, ShowcaseEntity.PLACEMENT_HEIGHT, 1e-9,
                "a foot-anchored model must not be lifted or sunk");
    }

    /**
     * The name a tier declares resolves to a clip that exists.
     *
     * <p>Two levels of the same fact: the two names the shipped character declares
     * ({@code tempest_blade} / {@code seiga_itten}) each play their own clip, and anything else — a
     * future datapack, a typo — plays the generic {@code ub_cast} instead of a clip GeckoLib would fail
     * to find on the client. The substitution is visible through {@link
     * ShowcaseEntity#declaredAnimation()} on a real entity, which is what the log line reports.
     */
    @Test
    void aDeclaredAnimationResolvesToAShippedClip() {
        assertEquals("tempest_blade", ShowcaseEntity.declaredClipOrDefault("tempest_blade"),
                "the base tier's own clip is shipped, so it plays");
        assertEquals("seiga_itten", ShowcaseEntity.declaredClipOrDefault("seiga_itten"),
                "the enhanced tier's own clip is shipped, so it plays");
        assertEquals(ShowcaseEntity.ANIMATION_CAST,
                ShowcaseEntity.declaredClipOrDefault("a_pack_named_something_else"),
                "an unknown name falls back rather than asking GeckoLib for a clip that is not there");
        assertEquals(ShowcaseEntity.ANIMATION_CAST, ShowcaseEntity.declaredClipOrDefault(null));
        assertEquals(ShowcaseEntity.ANIMATION_CAST, ShowcaseEntity.declaredClipOrDefault("   "));
    }

    // ------------------------------------------------------------------ the shipped assets agree

    /**
     * The server's constant and the shipped animation file must not drift.
     *
     * <p>{@code ShowcaseManager.CAST_ANIMATION_SECONDS} is what the <b>server</b> uses to decide when
     * the animation path may despawn, and the file is what the <b>client</b> actually plays. Two places,
     * one fact: if a future edit lengthens the animation and forgets the constant, every showcase would
     * be removed mid-swing, and if it shortens the animation the entity would stand idle for a second
     * and a half. Reading the file here is what makes that a test failure instead of a look-and-see.
     */
    @Test
    void theServersAnimationLengthMatchesTheShippedAnimationFile() throws IOException {
        String path = "/assets/astrum/animations/djeeta.animation.json";
        try (InputStream in = ShowcaseLifetimeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "动画文件必须在 resources 里：" + path);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject animations = root.getAsJsonObject("animations");
            assertTrue(animations.has(ShowcaseEntity.ANIMATION_FALLBACK),
                    "the fallback animation name must be an animation the file declares");
            assertTrue(animations.has(ShowcaseEntity.ANIMATION_IDLE),
                    "the idle loop must exist, or a long-lived showcase freezes mid-swing");
            // Every clip the entity will ever ask for must be in the file: an unknown name is a
            // GeckoLib lookup failure at render time, on a client that cannot report it usefully.
            for (String clip : ShowcaseEntity.SHIPPED_CLIPS) {
                assertTrue(animations.has(clip),
                        "ShowcaseEntity.SHIPPED_CLIPS names '" + clip
                                + "' but the animation file does not declare it");
            }
            assertTrue(ShowcaseEntity.SHIPPED_CLIPS.contains(ShowcaseEntity.ANIMATION_CAST),
                    "the generic cast clip has to be one of the shipped clips");
            JsonObject cast = animations.getAsJsonObject(ShowcaseEntity.ANIMATION_FALLBACK);
            double declared = cast.get("animation_length").getAsDouble();
            assertEquals(ShowcaseManager.CAST_ANIMATION_SECONDS, declared, 1e-9,
                    "ShowcaseManager.CAST_ANIMATION_SECONDS must equal the file's ub_cast length");
            assertEquals(ShowcaseLifetime.animationTicks(declared),
                    ShowcaseManager.castAnimationTicks());
        }
    }

    /**
     * The model file must exist and declare the six bones the animations address.
     *
     * <p>A missing bone in a GeckoLib animation is silently ignored, so a typo in either file produces
     * a figure that plays "nothing" rather than an error — the failure mode this test exists to catch.
     */
    @Test
    void theModelDeclaresEveryBoneTheAnimationsUse() throws IOException {        String path = "/assets/astrum/geo/djeeta.geo.json";
        try (InputStream in = ShowcaseLifetimeTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "模型文件必须在 resources 里：" + path);
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("1.12.0", root.get("format_version").getAsString(),
                    "GeckoLib 4.9.3 accepts 1.12.0 and warns about anything else");
            var geometry = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
            var description = geometry.getAsJsonObject("description");
            assertEquals(64, description.get("texture_width").getAsInt());
            assertEquals(64, description.get("texture_height").getAsInt());
            var bones = geometry.getAsJsonArray("bones");
            java.util.Set<String> names = new java.util.TreeSet<>();
            for (var bone : bones) {
                names.add(bone.getAsJsonObject().get("name").getAsString());
            }
            assertEquals(java.util.Set.of("body", "head", "left_arm", "right_arm", "left_leg",
                    "right_leg"), names, "the placeholder is six bones, no more and no fewer");

            // Every bone an animation names must exist, or its keyframes silently do nothing.
            try (InputStream animIn = ShowcaseLifetimeTest.class.getResourceAsStream(
                    "/assets/astrum/animations/djeeta.animation.json")) {
                JsonObject animations = JsonParser.parseReader(
                        new InputStreamReader(animIn, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("animations");
                for (String clip : animations.keySet()) {
                    JsonObject bonesInClip = animations.getAsJsonObject(clip).getAsJsonObject("bones");
                    for (String animated : bonesInClip.keySet()) {
                        assertTrue(names.contains(animated),
                                clip + " animates unknown bone " + animated);
                    }
                }
            }
        }
    }
}
