package com.astrum.pcrd.impl.ub;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cast swap of 2026-11-13: a UB settles its damage <b>once</b> and spawns <b>no entity</b>.
 *
 * <h2>What was inverted, and why it is not a deleted test</h2>
 * Task 13's acceptance was "a cast spawns exactly one showcase entity, plays its clip and settles the
 * damage at the strike frame". That is now the opposite of what should happen, so the claim is inverted
 * rather than dropped — and the entity's own rules (lifetime, cap, placement, clip names) are still
 * covered by {@code ShowcaseLifetimeTest}, which tests the retained implementation directly. Nothing lost
 * coverage; the <em>direction</em> of one claim changed.
 *
 * <h2>Two kinds of assertion here, deliberately</h2>
 * <ol>
 *   <li><b>Behaviour</b>: the once-latch runs the settlement exactly once however many times its
 *       {@link Runnable} is invoked, and {@link EffectsOnlySink#settleWith} runs the damage before the
 *       client cue and runs both once.</li>
 *   <li><b>Structure</b>: the shipped sink's own class file mentions no type from
 *       {@code impl.showcase}, and no class in the install path reaches {@code ShowcaseManager}. This is
 *       the half a behavioural test cannot see — a future edit could reintroduce a spawn inside a branch
 *       this suite never takes, and only the class file records that the dependency exists at all.</li>
 * </ol>
 *
 * <p>What is <b>not</b> provable here: that a real cast on a real server puts zero entities in a real
 * level. That needs a world, and it is proven by the probe run recorded in the task report
 * ({@code cast accepted} × N, {@code astrum:showcase} entities observed = 0, damage logged per cast).
 */
class EffectsOnlySinkTest {

    // ------------------------------------------------------------------ damage settles once

    /**
     * The one-shot latch: three attempts, one settlement.
     *
     * <p>This is the property the old design got from the entity's {@code SettleOnce} and the new design
     * still needs, because "settle" applies real {@code hurt()} calls: a second run would read as extra
     * hits, throw nothing, and log nothing wrong.
     */
    @Test
    void theGuardedSettlementRunsExactlyOnceHoweverOftenItIsCalled() {
        List<String> runs = new ArrayList<>();
        Runnable once = UbDamageSettlement.once(() -> runs.add("hit"));

        once.run();
        once.run();
        once.run();

        assertEquals(List.of("hit"), runs,
                "one cast settles its damage once — a second run would double every hit");
    }

    /** A throwing settlement still counts as spent: what landed before the failure is already applied. */
    @Test
    void aThrowingSettlementIsNotRetried() {
        int[] attempts = {0};
        Runnable once = UbDamageSettlement.once(() -> {
            attempts[0]++;
            throw new IllegalStateException("a damage listener blew up");
        });

        try {
            once.run();
        } catch (IllegalStateException expected) {
            // The first run's failure is the caller's problem; the latch's job is only to not re-run it.
        }
        try {
            once.run();
        } catch (IllegalStateException unexpected) {
            throw new AssertionError("the guarded settlement ran a second time after failing", unexpected);
        }

        assertEquals(1, attempts[0],
                "a partially applied ultimate is a bug to log, not a bug to complete");
    }

    /**
     * The sink's two halves, in order, once each — the composition {@code settle} performs.
     *
     * <p>The damage must go first: the client cue draws a picture of a hit that has to have already
     * happened (and its log line says so).
     */
    @Test
    void theCastSettlesDamageBeforeTellingTheClient() {
        List<String> order = new ArrayList<>();
        EffectsOnlySink.settleWith(
                UbDamageSettlement.once(() -> order.add("damage")),
                () -> order.add("effect cue"));

        assertEquals(List.of("damage", "effect cue"), order,
                "damage is settled first, then the client is told to draw it");
    }

    /** Called once per accepted cast, the composition settles exactly once and cues exactly once. */
    @Test
    void oneAcceptedCastSettlesOnceAndCuesOnce() {
        int[] damage = {0};
        int[] cues = {0};
        Runnable settlement = UbDamageSettlement.once(() -> damage[0]++);

        // What UbTransaction.commit does for one accepted cast: the sink runs exactly once.
        EffectsOnlySink.settleWith(settlement, () -> cues[0]++);

        assertEquals(1, damage[0], "exactly one settlement for one cast");
        assertEquals(1, cues[0], "exactly one client cue for one cast");
    }

    // ------------------------------------------------------------------ no entity, structurally

    /**
     * The shipped sink's class file names nothing in {@code impl.showcase}.
     *
     * <p>This is the assertion that makes "the cast path cannot spawn an entity" a fact about the
     * <em>code</em> rather than about the branch a test happened to take. A constant-pool reference means
     * the class can construct, compare or call a showcase type; with none present, no code path in this
     * sink can produce one, however it is edited later.
     */
    @Test
    void theEffectsOnlySinkCannotReferenceTheRetainedEntityTypes() throws IOException {
        String constantPool = classFileText(EffectsOnlySink.class);
        assertFalse(constantPool.contains("impl/showcase/"),
                "EffectsOnlySink must not name any impl.showcase type — that is what makes "
                        + "'a cast spawns no entity' structural rather than incidental");
        assertFalse(constantPool.contains("showcase/ShowcaseManager"),
                "the effects-only sink must not reach the entity implementation");
    }

    /**
     * The install path ({@code ShowcaseTypes}) does not reach {@code ShowcaseManager} either.
     *
     * <p>{@code ShowcaseManager.installEntitySink()} is the documented switch back, and it must be
     * <b>called by nobody</b> in the shipped build. Asserting it here means flipping the entity on again
     * cannot happen by accident — the task that does it will also have to change this test, on purpose.
     */
    @Test
    void theRegistrationPathDoesNotInstallTheEntitySink() throws IOException {
        Class<?> showcaseTypes = null;
        try {
            showcaseTypes = Class.forName("com.astrum.pcrd.impl.showcase.ShowcaseTypes");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("ShowcaseTypes must still exist: the entity type stays registered",
                    e);
        }
        String constantPool = classFileText(showcaseTypes);
        assertTrue(constantPool.contains("showcase/ShowcaseEntity")
                        || constantPool.contains("ShowcaseEntity"),
                "sanity: this is the file that registers the entity type, so it must name it");
        assertFalse(constantPool.contains("ShowcaseManager"),
                "ShowcaseTypes must install EffectsOnlySink, not the entity sink");
    }

    /**
     * The retained entity sink is still a {@link ShowcaseSink} with a working switch-back entry point.
     *
     * <p>The task's constraint is that the entity code must stay <b>compilable and registrable</b>; this
     * asserts the one API a later task will call rather than trusting a comment. Reflection rather than a
     * direct call, so the test itself does not become the thing that installs it.
     */
    @Test
    void theEntitySinkIsRetainedWithASwitchBackEntryPoint() throws Exception {
        Class<?> manager = Class.forName("com.astrum.pcrd.impl.showcase.ShowcaseManager");
        assertTrue(ShowcaseSink.class.isAssignableFrom(manager),
                "ShowcaseManager must still implement ShowcaseSink: that interface is the swap");
        var install = manager.getMethod("installEntitySink");
        assertNotNull(install, "the documented way back must exist");
        assertTrue(java.lang.reflect.Modifier.isStatic(install.getModifiers()),
                "it is installed once, statically — the same shape the shipped path uses");
        assertEquals(void.class, install.getReturnType());
    }

    /**
     * The placeholder and the shipped sink are different objects, so a test that installs nothing cannot
     * silently pass as if the real sink were running.
     */
    @Test
    void thePlaceholderIsNotTheShippedSink() {
        assertFalse(EffectsOnlySink.class.isInstance(ShowcaseSink.NO_SHOWCASE_YET),
                "NO_SHOWCASE_YET is the 'installs nothing' value, never the shipped sink");
        assertSame(ShowcaseSink.NO_SHOWCASE_YET, ShowcaseSink.NO_SHOWCASE_YET);
    }

    /** {@return a class file's bytes decoded as ISO-8859-1, so a constant-pool name can be searched} */
    private static String classFileText(Class<?> type) throws IOException {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(path)) {
            assertNotNull(in, "the compiled class file must be on the test classpath: " + path);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
