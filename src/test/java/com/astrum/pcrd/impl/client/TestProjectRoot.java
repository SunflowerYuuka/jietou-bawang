package com.astrum.pcrd.impl.client;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <b>Finds the project root for tests that have to read a file the classpath does not carry.</b>
 *
 * <h2>Why this exists</h2>
 * The unit-test JVM runs with Gradle's own scratch directory as its working directory
 * ({@code build/minecraft-junit}), <em>not</em> the repository root. So a test that reads
 * {@code tools/gen_tier_avatars.py} or {@code src/main/java/.../WheelIconDraw.java} by a relative path
 * finds nothing — and a test that quietly finds nothing passes, which is worse than no test at all. This
 * project has already been bitten by that: the first run of the avatar-source test failed with
 * {@code ...build/minecraft-junit/tools/gen_tier_avatars.py must exist}, which is exactly the diagnostic
 * working as intended.
 *
 * <h2>How it looks</h2>
 * Walks <b>upwards</b> from the working directory and, failing that, from the classpath location of this
 * class until a directory holding both {@code src/main/java} and {@code tools} appears. Both starting
 * points are tried because a Gradle test worker can report either, and both markers are required so a
 * nested subproject cannot be mistaken for the root.
 *
 * <p>This is the same technique {@code registry/CharacterDirectoryTest} worked out for the same reason;
 * it is repeated here as one shared helper rather than a third copy, and both callers use it. The rule
 * this codebase keeps is "do not duplicate a rule", and "where is the repository root" is one rule.
 *
 * <p><b>Public</b> since task 39, so tests outside this package ({@code item/CardStampTest}) can read a
 * source tree for its "only one class may write this" checks. It is still test-only: nothing in
 * {@code src/main} refers to it.
 */
public final class TestProjectRoot {

    /** How far up to look. The deepest real path here is five levels; eight leaves slack without looping. */
    private static final int MAX_HOPS = 8;

    private TestProjectRoot() {
    }

    /**
     * {@return the project root, or {@code null} when it cannot be found}
     *
     * <p>{@code null} rather than an exception so a caller can skip with a message — an inability to locate
     * the checkout is an environment fact, not a defect in the code under test.
     */
    public static Path find() {
        Path found = searchUpwards(Path.of(System.getProperty("user.dir", ".")));
        if (found != null) {
            return found;
        }
        try {
            // This class's own output directory: <root>/build/classes/java/test.
            Path classes = Path.of(TestProjectRoot.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            return searchUpwards(classes);
        } catch (Exception unresolvable) {
            return null;
        }
    }

    /**
     * {@return {@code relative} resolved against the project root, or {@code null} when there is no root}
     *
     * @param relative a mod-relative path such as {@code tools/gen_tier_avatars.py}
     */
    public static Path resolve(String relative) {
        Path root = find();
        return root == null ? null : root.resolve(relative);
    }

    /** {@return the first ancestor of {@code start} that looks like this project's root, or {@code null}} */
    private static Path searchUpwards(Path start) {
        Path candidate = start.toAbsolutePath();
        for (int hops = 0; hops < MAX_HOPS && candidate != null; hops++) {
            if (Files.isDirectory(candidate.resolve("src").resolve("main").resolve("java"))
                    && Files.isDirectory(candidate.resolve("tools"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
