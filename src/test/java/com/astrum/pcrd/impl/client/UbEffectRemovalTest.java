package com.astrum.pcrd.impl.client;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the <b>removal</b> of the screen-space UB overlay: no compiled class of this mod may name the
 * {@code astrum:ub_effect} GUI layer.
 *
 * <h2>Why this is a test and not a code-review note</h2>
 * "The layer is gone" is a negative claim, and a negative claim decays: a later edit that re-adds a
 * {@code blit} of a full-screen texture would pass every behavioural test in the suite, because there
 * is nothing to assert against — the overlay had no unit-testable output beyond its schedule maths,
 * which is exactly why it was the wrong shape. A constant-pool scan makes the removal structural: the
 * id can only be registered by code that carries the string, and no shipped class carries it.
 *
 * <p>The scan walks the compiled classes next to {@code Astrum.class}, so a new class cannot opt out
 * of the check by simply not being listed here. Test classes are skipped: this test has to contain the
 * id in order to search for it, and test classes are not shipped.
 */
class UbEffectRemovalTest {

    /** The layer id the deleted overlay registered; no shipped class may name it. */
    private static final String DEAD_LAYER = "ub_effect";

    /** The deleted class names, asserted absent so a half-revert shows up as a failing test. */
    private static final String[] DEAD_CLASSES = {"UbEffectOverlay", "UbEffectSchedule"};

    @Test
    void noCompiledClassNamesTheDeadGuiLayer() throws Exception {
        List<Path> classes = shippedClasses();
        assertTrue(classes.size() > 100, "expected the compiled classes next to Astrum.class, found "
                + classes.size() + " in " + classesRoot());
        List<String> offenders = new ArrayList<>();
        for (Path file : classes) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            if (text.contains(DEAD_LAYER)) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(), "these classes still name the deleted GUI layer "
                + DEAD_LAYER + ": " + offenders + " — the UB effect is world particles, not a blit");
    }

    @Test
    void theDeletedOverlayClassesAreGone() throws Exception {
        List<Path> classes = shippedClasses();
        List<String> present = new ArrayList<>();
        for (Path file : classes) {
            String name = file.getFileName().toString();
            for (String dead : DEAD_CLASSES) {
                if (name.equals(dead + ".class") || name.startsWith(dead + "$")) {
                    present.add(name);
                }
            }
        }
        assertTrue(present.isEmpty(), "class files of the deleted overlay still exist: " + present);
        // ... and the one client entry point that registers GUI layers must not mention them either.
        for (String dead : DEAD_CLASSES) {
            assertFalse(constantPoolOf(ClientEvents.class).contains(dead),
                    "ClientEvents still references " + dead);
        }
    }

    /**
     * {@return every compiled class next to {@code Astrum.class}, minus the test classes}
     *
     * <p>Test classes are excluded rather than the whole test output: under this project's unit-test
     * launch the test and main outputs can share one walk root, and this very test must spell the dead
     * id out in order to look for it. A shipped class is never named {@code *Test}, so the filter
     * cannot hide production code.
     */
    private static List<Path> shippedClasses() throws Exception {
        Path root = classesRootPath();
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !isTestClass(p.getFileName().toString()))
                    .forEach(out::add);
        }
        assertFalse(out.isEmpty(), "no class files under " + root);
        return out;
    }

    /** {@return whether a class file name belongs to a test class (or one of its inner classes)} */
    private static boolean isTestClass(String fileName) {
        return fileName.endsWith("Test.class") || fileName.contains("Test$");
    }

    /** {@return the directory the scan starts at, next to the compiled {@code Astrum.class}} */
    private static Path classesRootPath() throws Exception {
        URL anchor = UbEffectRemovalTest.class.getResource("/com/astrum/pcrd/Astrum.class");
        assertNotNull(anchor, "Astrum.class must be on the test classpath");
        Path root = Paths.get(anchor.toURI()).getParent();
        assertNotNull(root, "could not resolve the compiled classes directory from " + anchor);
        return root;
    }

    /** {@return a human-readable form of the scan root, for failure messages} */
    private static String classesRoot() {
        try {
            return String.valueOf(classesRootPath());
        } catch (Exception e) {
            return "unresolvable: " + e;
        }
    }

    /** {@return a class file's bytes decoded as ISO-8859-1, so a constant-pool name can be searched} */
    private static String constantPoolOf(Class<?> type) throws IOException {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(path)) {
            assertNotNull(in, "the compiled class file must be on the test classpath: " + path);
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}