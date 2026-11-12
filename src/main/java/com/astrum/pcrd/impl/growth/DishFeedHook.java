package com.astrum.pcrd.impl.growth;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The 养成 view's dish-feeding entry point, and the seam Task 15 fills.
 *
 * <h2>Why a seam and not a button that does nothing</h2>
 * Spec §6.3.1 requires the growth view to carry 「喂料理入口」, while the dish itself and its effect are
 * Task 15's (§6.2: feeding a dish raises affection, with a per-item daily cap). Building the effect
 * here would be implementing another task's math; building a button that silently does nothing would
 * be exactly the silent failure §5.5 forbids. So the screen asks this class whether an entry point
 * exists:
 *
 * <ul>
 *   <li><b>No sink installed</b> (this build, until Task 15 wires one): the growth view draws the
 *       feeding <em>route</em> as text — the spec's own answer, "hold a dish and right-click" — which
 *       is true of the game as it ships and needs no handler.</li>
 *   <li><b>A sink installed</b> (Task 15, or an addon): the growth view draws it as an actionable
 *       entry that calls {@link Sink#feed} with the selected character.</li>
 * </ul>
 *
 * <p>That is also why the sink takes no player: the screen is client-side, and the server is the only
 * side that may touch a roster. A sink is expected to send its own request; nothing here decides
 * anything.
 *
 * <h2>Why the sink is installed rather than hard-wired</h2>
 * One line in the task that owns dish mechanics, instead of an interface the book would have to grow
 * an import for. Null means "nobody can feed dishes yet", and both states are handled explicitly, so
 * neither is a crash and neither is a lie.
 */
@ApiStatus.Internal
public final class DishFeedHook {

    /** Handles one "feed a dish to this character" request, on the client, with the selection given. */
    @FunctionalInterface
    public interface Sink {
        /**
         * Asks for a dish to be fed to {@code characterId}.
         *
         * @param characterId the character the growth view has selected
         */
        void feed(ResourceLocation characterId);
    }

    /** The installed sink, or {@code null} when nothing can feed dishes yet. */
    private static volatile Sink sink;

    private DishFeedHook() {
    }

    /**
     * Installs (or with {@code null}, removes) the feeding entry point.
     *
     * @param newSink the handler, or {@code null} to remove it
     */
    public static void install(@Nullable Sink newSink) {
        sink = newSink;
    }

    /** {@return the installed sink, empty until the dish task wires one} */
    public static Optional<Sink> sink() {
        return Optional.ofNullable(sink);
    }

    /** {@return whether the growth view can offer a working feeding entry today} */
    public static boolean available() {
        return sink != null;
    }
}
