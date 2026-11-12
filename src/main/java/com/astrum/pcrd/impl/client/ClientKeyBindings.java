package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/**
 * The one key binding this mod adds: the UB cast key.
 *
 * <h2>Why {@code V} and not {@code R} or middle mouse</h2>
 * Spec §5.5 is explicit: {@code R} is JEI's recipe key and middle mouse is pick-block, so either
 * would fight something the player already uses every minute. {@code V} is unbound in vanilla and in
 * the common HUD mods, it is under the left hand next to the hotbar digits the wheel then uses, and
 * it is free to be rebound — which is the real requirement, since server packs will collide with it
 * eventually.
 *
 * <h2>Why the conflict context is {@link KeyConflictContext#IN_GAME}</h2>
 * The key only means anything while the player is in a world and no GUI is up, so a conflict with an
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

    /** Translation key of the binding's name, defined in {@code assets/astrum/lang}. */
    public static final String UB_WHEEL_KEY = "key.astrum.ub_wheel";

    /** Translation key of the controls category this binding is filed under. */
    public static final String CATEGORY = "key.categories.astrum";

    /** The UB cast key: tap to cast the preset, hold for the wheel. Default {@code V}, rebindable. */
    public static final KeyMapping UB_WHEEL = new KeyMapping(
            UB_WHEEL_KEY,
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    private ClientKeyBindings() {
    }

    /**
     * Adds the binding to the game's controls list.
     *
     * @param event the mod-bus registration event
     */
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(UB_WHEEL);
        Astrum.LOGGER.info("astrum: ub key binding registered (default {}, category {})",
                InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V).getDisplayName().getString(),
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

}
