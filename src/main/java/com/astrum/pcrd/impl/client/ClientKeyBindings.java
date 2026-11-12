package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/**
 * The two key bindings this mod adds: the UB cast key, and the key that opens the config screen.
 *
 * <h2>Why {@code V} and not {@code R} or middle mouse</h2>
 * Spec §5.5 is explicit: {@code R} is JEI's recipe key and middle mouse is pick-block, so either
 * would fight something the player already uses every minute. {@code V} is unbound in vanilla and in
 * the common HUD mods, it is under the left hand next to the hotbar digits the wheel then uses, and
 * it is free to be rebound — which is the real requirement, since server packs will collide with it
 * eventually. The config key follows the same rules and takes {@code K}; see
 * {@link #CONFIG_SCREEN}.
 *
 * <h2>Why the conflict context is {@link KeyConflictContext#IN_GAME}</h2>
 * The keys only mean anything while the player is in a world and no GUI is up, so a conflict with an
 * inventory-screen binding is not a conflict the player should have to resolve.
 *
 * <h2>Why this class is client-only and loads nothing else</h2>
 * {@link KeyMapping} is under {@code net.minecraft.client}; this class therefore lives in
 * {@code impl/client} (spec §7 端隔离) and is only touched from a {@code Dist.CLIENT} branch. It
 * registers through {@link RegisterKeyMappingsEvent} on the mod bus so the binding appears in
 * Options → Controls and survives as a normal rebind.
 */
@ApiStatus.Internal
public final class ClientKeyBindings {

    /** Translation key of the wheel binding's name, defined in {@code assets/astrum/lang}. */
    public static final String UB_WHEEL_KEY = "key.astrum.ub_wheel";

    /** Translation key of the config binding's name. */
    public static final String CONFIG_KEY = "key.astrum.config";

    /** Translation key of the controls category this binding is filed under. */
    public static final String CATEGORY = "key.categories.astrum";

    /** The UB cast key: tap to cast the preset, hold for the wheel. Default {@code V}, rebindable. */
    public static final KeyMapping UB_WHEEL = new KeyMapping(
            UB_WHEEL_KEY,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    /**
     * The configuration key: opens the in-game config screen (spec §8.0). Default {@code K}.
     *
     * <h2>Why {@code K}</h2>
     * The three keys a config binding must not take are already taken: {@code V} is this mod's wheel,
     * {@code R} is JEI's recipe key, and middle mouse is pick-block. {@code K} is unbound in vanilla,
     * is under the left hand next to the movement keys — the hand that is already off the mouse when a
     * player stops to change a setting — and, like every binding here, is rebindable, which is what
     * matters when a pack collides with it.
     */
    public static final KeyMapping CONFIG_SCREEN = new KeyMapping(
            CONFIG_KEY,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY);

    private ClientKeyBindings() {
    }

    /**
     * Adds the bindings to the game's controls list.
     *
     * @param event the mod-bus registration event
     */
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(UB_WHEEL);
        event.register(CONFIG_SCREEN);
        Astrum.LOGGER.info("astrum: key bindings registered ({} default {}, {} default {}, category {})",
                UB_WHEEL_KEY,
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V).getDisplayName().getString(),
                CONFIG_KEY,
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K).getDisplayName().getString(),
                CATEGORY);
    }

    /**
     * {@return whether a raw key event belongs to the UB key}
     *
     * <p>Asks the {@link KeyMapping} rather than comparing against {@code GLFW_KEY_V}: the player may
     * have rebound it, and {@code matches} is the only comparison that knows that (it also honours a
     * configured modifier).
     *
     * @param key      GLFW key code from the event
     * @param scanCode platform scan code from the event
     */
    public static boolean matches(int key, int scanCode) {
        return UB_WHEEL.matches(key, scanCode);
    }

    /**
     * {@return whether a raw key event belongs to the config key} (same reasoning as {@link #matches})
     *
     * @param key      GLFW key code from the event
     * @param scanCode platform scan code from the event
     */
    public static boolean matchesConfig(int key, int scanCode) {
        return CONFIG_SCREEN.matches(key, scanCode);
    }

}
