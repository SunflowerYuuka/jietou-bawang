package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/**
 * The three key bindings this mod adds: the UB <b>cast</b> key, the UB <b>preset wheel</b> key, and the
 * key that opens the config screen.
 *
 * <h2>Why casting and the wheel are two keys (spec §5.5, 2026-11-12)</h2>
 * One key with a long-press overload forced every quick cast to be held for 200 ms first, and made
 * changing the preset feel like aiming a shot. Split in two, each key does one thing and neither can be
 * triggered by accident: {@link #UB_CAST} taps to cast, holds to raise the slot pad, and its digits cast;
 * {@link #UB_PRESET} holds to open the ring and its digits only change the preset. The two machines that
 * implement that are {@code impl.ub.WheelInputMachine}'s {@code CAST} and {@code PRESET} purposes.
 *
 * <h2>Why {@code V} and {@code B}, and not {@code R} or middle mouse</h2>
 * Spec §5.5 is explicit: {@code R} is JEI's recipe key and middle mouse is pick-block, so either would
 * fight something the player already uses every minute, and the two keys must not share a default.
 * {@code V} and {@code B} are unbound in vanilla and in the common HUD mods, they sit under the left hand
 * next to the hotbar digits both overlays use, and both are free to be rebound — which is the real
 * requirement, since server packs will collide with them eventually. The config key follows the same
 * rules and takes {@code K}; see {@link #CONFIG_SCREEN}.
 *
 * <h2>Why the conflict context is {@link KeyConflictContext#IN_GAME}</h2>
 * The keys only mean anything while the player is in a world and no GUI is up, so a conflict with an
 * inventory-screen binding is not a conflict the player should have to resolve.
 *
 * <h2>Why this class is client-only and loads nothing else</h2>
 * {@link KeyMapping} is under {@code net.minecraft.client}; this class therefore lives in
 * {@code impl/client} (spec §7 端隔离) and is only touched from a {@code Dist.CLIENT} branch. It
 * registers through {@link RegisterKeyMappingsEvent} on the mod bus so the bindings appear in
 * Options → Controls and survive as normal rebinds.
 */
@ApiStatus.Internal
public final class ClientKeyBindings {

    /**
     * Translation key of the cast key's name, defined in {@code assets/astrum/lang}.
     *
     * <p>The identifier stays {@code ub_wheel} on purpose: it is what existing players' rebinds and
     * already-shipped configs are keyed by, and renaming it would silently forget their binding. What
     * changed is the <em>label</em>, which now describes only casting.
     */
    public static final String UB_CAST_KEY = "key.astrum.ub_wheel";

    /** Translation key of the preset wheel key's name. */
    public static final String UB_PRESET_KEY = "key.astrum.ub_preset_wheel";

    /** Translation key of the config binding's name. */
    public static final String CONFIG_KEY = "key.astrum.config";

    /** Translation key of the controls category these bindings are filed under. */
    public static final String CATEGORY = "key.categories.astrum";

    /**
     * The UB cast key: tap casts the current preset, hold raises the slot pad, a digit casts that slot.
     * Default {@code V}, rebindable.
     */
    public static final KeyMapping UB_CAST = new KeyMapping(
            UB_CAST_KEY,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    /**
     * The UB preset wheel key: tap does nothing, hold opens the ring, a digit makes that slot the preset
     * without casting. Default {@code B}, rebindable.
     */
    public static final KeyMapping UB_PRESET = new KeyMapping(
            UB_PRESET_KEY,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            CATEGORY);

    /**
     * The configuration key: opens the in-game config screen (spec §8.0). Default {@code K}.
     *
     * <h2>Why {@code K}</h2>
     * The keys a config binding must not take are already taken: {@code V} and {@code B} are this mod's
     * two UB keys, {@code R} is JEI's recipe key, and middle mouse is pick-block. {@code K} is unbound in
     * vanilla, is under the left hand next to the movement keys — the hand that is already off the mouse
     * when a player stops to change a setting — and, like every binding here, is rebindable, which is
     * what matters when a pack collides with it.
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
        event.register(UB_CAST);
        event.register(UB_PRESET);
        event.register(CONFIG_SCREEN);
        Astrum.LOGGER.info("astrum: key bindings registered ({} default {}, {} default {}, {} default {}, category {})",
                UB_CAST_KEY, keyName(GLFW.GLFW_KEY_V),
                UB_PRESET_KEY, keyName(GLFW.GLFW_KEY_B),
                CONFIG_KEY, keyName(GLFW.GLFW_KEY_K),
                CATEGORY);
    }

    /**
     * {@return whether a raw key event belongs to the cast key}
     *
     * <p>Asks the {@link KeyMapping} rather than comparing against {@code GLFW_KEY_V}: the player may
     * have rebound it, and {@code matches} is the only comparison that knows that (it also honours a
     * configured modifier).
     *
     * @param key      GLFW key code from the event
     * @param scanCode platform scan code from the event
     */
    public static boolean matchesCast(int key, int scanCode) {
        return UB_CAST.matches(key, scanCode);
    }

    /**
     * {@return whether a raw key event belongs to the preset wheel key} (same reasoning as
     * {@link #matchesCast})
     *
     * @param key      GLFW key code from the event
     * @param scanCode platform scan code from the event
     */
    public static boolean matchesPreset(int key, int scanCode) {
        return UB_PRESET.matches(key, scanCode);
    }

    /**
     * {@return whether a raw key event belongs to the config key} (same reasoning as
     * {@link #matchesCast})
     *
     * @param key      GLFW key code from the event
     * @param scanCode platform scan code from the event
     */
    public static boolean matchesConfig(int key, int scanCode) {
        return CONFIG_SCREEN.matches(key, scanCode);
    }

    /** {@return the platform name of a GLFW key, for the registration log line} */
    private static String keyName(int glfwKey) {
        return InputConstants.Type.KEYSYM.getOrCreate(glfwKey).getDisplayName().getString();
    }
}
