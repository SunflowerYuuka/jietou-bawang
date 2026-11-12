package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.network.UbCastPayload;
import com.astrum.pcrd.impl.ub.WheelInputMachine;
import com.astrum.pcrd.impl.ub.WheelInputMachine.Decision;
import com.astrum.pcrd.impl.ub.WheelInputMachine.State;
import com.astrum.pcrd.impl.ub.WheelSlots;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;

/**
 * The client's UB input path: one key, one {@link WheelInputMachine}, and the four things a decision
 * can turn into (spec §5.5).
 *
 * <h2>The split of responsibility</h2>
 * <b>When</b> a gesture is a tap, an open wheel or a slot pick is decided by {@link WheelInputMachine},
 * which is pure and tested on its own. This class only feeds it and obeys it:
 *
 * <pre>
 *   InputEvent.Key (press)   → machine.onPress(nowMillis)
 *   InputEvent.Key (release) → machine.onRelease(nowMillis) → act(decision)
 *   ClientTickEvent.Post     → machine.poll(nowMillis)      → act(decision)
 *   WheelScreen digit 1–5    → machine.onDigit(n)           → act(decision)
 * </pre>
 *
 * <p>The digits stay {@code 1}–{@code 5} all the way down to {@link #castDigit()}, which converts them
 * once — through {@link WheelSlots#slotForDigit} — into the 0-based slot the payload and the server
 * use. That single conversion point is deliberate: it used to be missing, and a missing conversion
 * sends the wrong segment or (for {@code 5}) nothing at all.
 *
 * <h2>Why the clock is wall-clock milliseconds</h2>
 * The 200 ms hold threshold is a duration a human feels, so it must not be counted in game ticks: a
 * lagging client (or one at 15 tps) would otherwise turn a 200 ms hold into a 400 ms one and open the
 * wheel late. {@link System#nanoTime()} is monotonic (immune to the user changing the system clock)
 * and needs no game objects, so it also works before any world is loaded.
 *
 * <h2>Why {@code InputEvent.Key}, not {@code KeyMapping.consumeClick}</h2>
 * {@code consumeClick()} is consumed by vanilla on the next tick and, while a screen is open, vanilla
 * never even records the click — the press and the release of the gesture would then be seen on
 * different ticks, or not at all. {@code InputEvent.Key} is fired from {@code KeyboardHandler} for
 * every physical key event on both edges, screen or no screen, which is exactly what a press/release
 * gesture needs. The {@link KeyMapping} is still consulted, through {@link ClientKeyBindings#matches},
 * so a rebound key works.
 *
 * <h2>What this class does not do</h2>
 * It never decides whether a cast is allowed: it sends a slot number and the server answers
 * ({@link com.astrum.pcrd.impl.ub.CastGate}, spec §5.5/§9.5). A refusal comes back as
 * {@link ClientCastFeedback}, is made audible by {@link RefusalSound} on the client tick — independent
 * of any display setting — and shakes the gauge when the optional HUD is actually drawn
 * ({@link UbHud}).
 */
@ApiStatus.Internal
public final class ClientEvents {

    /** The HUD layer's id, and the fact that it draws just above vanilla's hotbar layer. */
    private static final ResourceLocation HUD_LAYER = InternalIds.id("ub_hud");

    private static final WheelInputMachine MACHINE = new WheelInputMachine();

    private ClientEvents() {
    }

    /**
     * Wires the client half: key binding and HUD layer on the mod bus, input and tick on the game bus.
     *
     * <p>Called only from a {@code Dist.CLIENT} branch in {@code Astrum}, so no client class is ever
     * loaded on a dedicated server (spec §7 端隔离).
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, ClientKeyBindings::onRegisterKeyMappings);
        modBus.addListener(RegisterGuiLayersEvent.class, ClientEvents::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.addListener(InputEvent.Key.class, ClientEvents::onKey);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, ClientEvents::onClientTick);
        Astrum.LOGGER.info("astrum: client UB input wired (press/hold/release {}, threshold {} ms)",
                ClientKeyBindings.UB_WHEEL_KEY, WheelInputMachine.HOLD_THRESHOLD_MILLIS);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, HUD_LAYER, UbHud::render);
        Astrum.LOGGER.info("astrum: UB HUD layer registered above the hotbar ({})", HUD_LAYER);
    }

    /**
     * One physical key event, press or release, screen or no screen.
     *
     * <p>Presses are ignored while another GUI is open — the inventory's keys are not ours to take —
     * but releases are always delivered, because a press that armed the machine before the GUI opened
     * still has to settle, and settling is what prevents a stuck {@code PENDING} state.
     */
    static void onKey(InputEvent.Key event) {
        if (!ClientKeyBindings.matches(event.getKey(), event.getScanCode())) {
            return;
        }
        int action = event.getAction();
        if (action == InputConstants.PRESS) {
            onPress();
        } else if (action == InputConstants.RELEASE) {
            act(MACHINE.onRelease(nowMillis()));
        }
    }

    private static void onPress() {
        if (MACHINE.state() == State.WHEEL) {
            // The wheel is up and the key is still held: a repeat event must not restart the gesture.
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        Screen open = minecraft.screen;
        if (open != null && !(open instanceof WheelScreen)) {
            // Somebody else's GUI owns the keyboard; V is not ours until it closes.
            return;
        }
        MACHINE.onPress(nowMillis());
    }

    /**
     * One client tick: the only place the hold threshold can be crossed, and the home of the refusal
     * sound.
     *
     * <p>The sound is played here, not from the HUD layer, so it is audible whether or not the optional
     * HUD is on and whether or not {@code F1} is hiding the overlay ({@link RefusalSound} explains why
     * that matters for 禁止静默失败).
     */
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.level != null) {
            RefusalSound.tick(minecraft.level.getGameTime());
        }
        if (MACHINE.state() != State.PENDING) {
            return;
        }
        if (minecraft.player == null) {
            // Left the world mid-gesture: settle it rather than carry it into the next one.
            act(MACHINE.onRelease(nowMillis()));
            return;
        }
        act(MACHINE.poll(nowMillis()));
    }

    /**
     * A hotbar digit pressed while the wheel is up.
     *
     * <p>Reached only from {@link WheelScreen}, which asks {@link WheelInputMachine#capturesHotbarDigits()}
     * first — the single place that rule lives.
     *
     * @param slot1to5 the digit the player pressed, {@code 1}–{@code 5}
     */
    static void onWheelDigit(int slot1to5) {
        act(MACHINE.onDigit(slot1to5));
    }

    /**
     * The wheel screen was closed by something other than this class (Escape, a disconnect).
     *
     * <p>The decision is deliberately discarded: the screen is already gone, so all that is left is
     * to put the machine back in {@code IDLE} — otherwise {@code capturesHotbarDigits()} would keep
     * claiming the digits after the wheel had vanished.
     */
    static void onWheelScreenClosed() {
        if (MACHINE.state() == State.WHEEL) {
            MACHINE.onRelease(nowMillis());
        }
        // Nothing was recorded while a screen was open, but clear anyway: a click queued in the tick
        // the wheel closed would change the player's hotbar slot for no reason they asked for.
        Minecraft minecraft = Minecraft.getInstance();
        for (KeyMapping hotbar : minecraft.options.keyHotbarSlots) {
            while (hotbar.consumeClick()) {
                // drain
            }
        }
    }

    /** {@return the machine, for the wheel screen's hotbar-capture question} */
    static WheelInputMachine machine() {
        return MACHINE;
    }

    /**
     * Obeys one decision from the machine.
     *
     * @param decision what the gesture became
     */
    private static void act(Decision decision) {
        switch (decision) {
            case TAP_CAST -> requestCast(WheelPreset.slot());
            case OPEN_WHEEL -> openWheel();
            case CAST_SLOT -> {
                castDigit();
                closeWheel();
            }
            case CLOSE -> closeWheel();
            case NONE -> {
                // nothing to do
            }
        }
    }

    /**
     * Sends the slot the player picked with a hotbar digit.
     *
     * <p>This is where the wheel's digit convention ({@code 1}–{@code 5}, what the player presses and
     * what {@link WheelInputMachine#onDigit} stores) becomes the 0-based slot the wire and the server
     * use — through {@link WheelSlots#slotForDigit}, the one implementation of that mapping. Both
     * failures below are programming errors, not player input, and are thrown rather than swallowed:
     * a press that produces nothing is exactly what spec §5.5 forbids (禁止静默失败).
     */
    private static void castDigit() {
        int digit = MACHINE.castSlot().orElseThrow(() -> new IllegalStateException(
                "astrum: CAST_SLOT without a digit — WheelInputMachine.onDigit was not the source"));
        requestCast(WheelSlots.slotForDigit(digit).orElseThrow(() -> new IllegalStateException(
                "astrum: wheel digit " + digit + " has no slot (expected "
                        + WheelSlots.FIRST_DIGIT + "–" + WheelSlots.LAST_DIGIT + ")")));
    }

    private static void openWheel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (minecraft.screen != null) {
            // Something took the screen between the press and the threshold; keep the world as it is.
            MACHINE.onRelease(nowMillis());
            return;
        }
        minecraft.setScreen(new WheelScreen());
        Astrum.LOGGER.debug("astrum: UB wheel opened (preset {})", WheelPreset.slot());
    }

    private static void closeWheel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof WheelScreen) {
            minecraft.setScreen(null);
            Astrum.LOGGER.debug("astrum: UB wheel closed (preset {})", WheelPreset.slot());
        }
    }

    /**
     * Asks the server to fire a slot.
     *
     * <p>Sends the slot and nothing else; the server re-resolves the party, the gauge and the
     * cooldown, and answers a refusal on the same connection (spec §5.5, §9.5).
     *
     * @param slot wheel slot in {@code [0, Party.SLOTS)}
     */
    private static void requestCast(int slot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        if (slot < 0 || slot >= Party.SLOTS) {
            // Unreachable: every caller arrives here through WheelSlots.slotForDigit or
            // WheelPreset.slot(). A bad slot is a programming error, so it is not turned into a
            // silently dropped press (spec §5.5: 禁止静默失败) — it is reported as a bug.
            throw new IllegalArgumentException(
                    "astrum: wheel slot " + slot + " is outside [0, " + Party.SLOTS + ")");
        }
        PacketDistributor.sendToServer(new UbCastPayload(slot));
        Astrum.LOGGER.debug("astrum: UB cast requested for slot {}", slot);
    }

    /** {@return a monotonic millisecond clock, immune to the user changing the system time} */
    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }


}
