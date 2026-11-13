package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.network.UbCastPayload;
import com.astrum.pcrd.impl.ub.WheelInputMachine;
import com.astrum.pcrd.impl.ub.WheelInputMachine.Decision;
import com.astrum.pcrd.impl.ub.WheelInputMachine.Purpose;
import com.astrum.pcrd.impl.ub.WheelInputMachine.State;
import com.astrum.pcrd.impl.ub.WheelSlots;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

/**
 * The client's UB input path: <b>two</b> keys, one {@link WheelInputMachine} per key, and what each
 * decision turns into (spec §5.5, 2026-11-12: 拆成两个按键).
 *
 * <h2>The split, in one table</h2>
 * <pre>
 *   key            default  tap (&lt;200 ms)            hold (≥200 ms)         digit 1–5 while up
 *   cast key       V        cast the current preset    raise the cast pad      cast that slot, close
 *   preset wheel   B        nothing (hold-only)        open the ring wheel     set preset, close, no cast
 * </pre>
 * Neither key can do the other's job: the cast key has no decision that changes the preset, and the wheel
 * key has no decision that casts. That mutual exclusivity is enforced in the machine (two purposes, two
 * disjoint decision sets) and asserted in {@code WheelInputMachineTest}.
 *
 * <h2>The split of responsibility</h2>
 * <b>When</b> a gesture is a tap, an overlay or a slot pick is decided by {@link WheelInputMachine}, which
 * is pure and tested on its own. This class only feeds it and obeys it:
 *
 * <pre>
 *   InputEvent.Key (press)    → machine.onPress(nowMillis)
 *   InputEvent.Key (release)  → machine.onRelease(nowMillis)  → act(decision)
 *   ClientTickEvent.Post      → machine.poll(nowMillis)       → act(decision)
 *   InputEvent.Key (1–5)      → machine.onDigit(n)            → act(decision)
 * </pre>
 *
 * <p>The digits stay {@code 1}–{@code 5} all the way down to the one place that casts, which converts
 * them once — through {@link WheelSlots#slotForDigit} — into the 0-based slot the payload and the server
 * use. That single conversion point is deliberate: it used to be missing, and a missing conversion sends
 * the wrong segment or (for {@code 5}) nothing at all.
 *
 * <h2>No {@code Screen} — and how the two overlays are shown instead</h2>
 * Both are GUI layers ({@link WheelOverlay}, {@link CastPadOverlay}) registered above the hotbar like
 * {@link UbHud}, not a {@code Screen}: {@code setScreen} releases every held key and stops vanilla feeding
 * the player's movement (spec §5.5, verified on a real client — see {@code task-12-report.md} §12 fix
 * round 1, I5). Nothing in this class calls {@code setScreen}.
 *
 * <h2>How the two allowed interceptions actually work — and why not through {@code InputEvent.Key}</h2>
 * Spec §5.5 allows exactly two: the hotbar digits {@code 1}–{@code 5} must not switch the hotbar while
 * <b>either</b> overlay is up, and {@code Esc} must close it without opening the pause menu. On NeoForge
 * 21.1.265 both are decided inside vanilla <b>before</b> any mod sees them, and {@code InputEvent.Key} is
 * a non-cancellable notification fired at the end of {@code KeyboardHandler.keyPress}. So the two rules
 * are enforced at the two points that are genuinely before vanilla:
 *
 * <ul>
 *   <li><b>Digits</b>: {@code ClientTickEvent.Pre} runs at the top of {@code Minecraft.tick}, before
 *       vanilla's {@code handleKeybinds()} (which is what turns a queued click into
 *       {@code inventory.selected = i}) in the same tick. While an overlay is up this class drains the
 *       five hotbar click queues there, so vanilla finds nothing to consume
 *       ({@link #drainHotbarClicks}). Nothing is released, nothing else is touched — the digit still
 *       reaches {@link WheelDigits}/{@code onDigit} through the key event, so the <em>overlay</em> sees it
 *       and the hotbar does not.</li>
 *   <li><b>Escape</b>: {@code KeyboardHandler} calls {@code Minecraft.pauseGame} which goes through
 *       {@code setScreen}, and NeoForge fires the cancellable {@code ScreenEvent.Opening} from
 *       {@code setScreen} <em>before</em> the screen is installed (and before the release-everything part
 *       runs). Cancelling that for a {@link PauseScreen} while an overlay is up closes it and never
 *       installs the pause menu. {@code pauseGame} pauses the sound <em>after</em> {@code setScreen}
 *       returns, so the next client tick resumes it; without that the game would fall silent after one
 *       Escape.</li>
 * </ul>
 *
 * <p>Everything else passes through untouched: movement, attack, use, inventory, chat, {@code F3} — no
 * other key is intercepted, which is the spec's requirement.
 *
 * <h2>Why the clock is wall-clock milliseconds</h2>
 * The 200 ms hold threshold is a duration a human feels, so it must not be counted in game ticks: a
 * lagging client (or one at 15 tps) would otherwise turn a 200 ms hold into a 400 ms one and open the
 * overlay late. {@link System#nanoTime()} is monotonic (immune to the user changing the system clock) and
 * needs no game objects, so it also works before any world is loaded.
 *
 * <h2>Why {@code InputEvent.Key}, not {@code KeyMapping.consumeClick}</h2>
 * {@code consumeClick()} is consumed by vanilla on the next tick and, while a screen is open, vanilla
 * never even records the click — the press and the release of the gesture would then be seen on different
 * ticks, or not at all. {@code InputEvent.Key} is fired from {@code KeyboardHandler} for every physical
 * key event on both edges, which is exactly what a press/release gesture needs. The {@link KeyMapping}s
 * are still consulted, through {@link ClientKeyBindings#matchesCast} / {@code matchesPreset}, so a
 * rebound key works.
 *
 * <h2>What this class does not do</h2>
 * It never decides whether a cast is allowed: it sends a slot number and the server answers
 * ({@link com.astrum.pcrd.impl.ub.CastGate}, spec §5.5/§9.5). A refusal comes back as
 * {@link ClientCastFeedback}, is made audible by {@link RefusalSound} on the client tick — independent of
 * any display setting — and shakes the gauge when the optional HUD is actually drawn ({@link UbHud}).
 */
@ApiStatus.Internal
public final class ClientEvents {

    /** The HUD layer's id, and the fact that it draws just above vanilla's hotbar layer. */
    private static final ResourceLocation HUD_LAYER = InternalIds.id("ub_hud");

    /** The wheel overlay's id: above the HUD layer, so the wheel is never painted under the gauge. */
    private static final ResourceLocation WHEEL_LAYER = WheelOverlay.LAYER_ID;

    /** The cast pad's id: above the wheel layer, as the pad is the smaller, closer-to-the-action hint. */
    private static final ResourceLocation CAST_PAD_LAYER = CastPadOverlay.LAYER_ID;

    /**
     * The UB effect's id: directly above the hotbar and <b>below</b> every widget of this mod.
     *
     * <p>That order is a decision, not an accident. The effect is a full-screen flash; the gauge, the wheel
     * and the pad are the things a player reads <em>while</em> it happens, so they are drawn over it. Above
     * the hotbar because the effect belongs over the world, not under the HUD, and below this mod's own
     * layers so one second of golden light cannot hide the cooldown the player is watching.
     */
    private static final ResourceLocation EFFECT_LAYER = UbEffectOverlay.LAYER_ID;

    /** The machine behind the cast key: tap casts, hold raises the pad, a digit casts that slot. */
    private static final WheelInputMachine CAST_MACHINE = new WheelInputMachine(Purpose.CAST);

    /** The machine behind the preset wheel key: hold opens the ring, a digit sets the preset. */
    private static final WheelInputMachine PRESET_MACHINE = new WheelInputMachine(Purpose.PRESET);

    /** The pick state of the ring that is up: flick accumulation and the selected segment. */
    private static final WheelSession SESSION = new WheelSession();

    /** Whether the camera has been sampled yet for the wheel that is currently up. */
    private static boolean cameraSampled;

    /** Camera orientation when the wheel was opened, and each tick after that. */
    private static float lastYaw;
    private static float lastPitch;

    /**
     * Set when Escape's pause menu is suppressed: {@code Minecraft.pauseGame} pauses the sound
     * <em>after</em> {@code setScreen} returns, so the resume has to happen on a later tick.
     */
    private static boolean resumeSoundNextTick;

    private ClientEvents() {
    }

    /**
     * Wires the client half: key bindings, HUD and both overlays on the mod bus, input and tick on the
     * game bus.
     *
     * <p>Called only from a {@code Dist.CLIENT} branch in {@code Astrum}, so no client class is ever
     * loaded on a dedicated server (spec §7 端隔离).
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, ClientKeyBindings::onRegisterKeyMappings);
        modBus.addListener(RegisterGuiLayersEvent.class, ClientEvents::onRegisterGuiLayers);
        ShowcaseRenderer.register(modBus);
        NeoForge.EVENT_BUS.addListener(InputEvent.Key.class, ClientEvents::onKey);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, ClientEvents::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, ClientEvents::onClientTick);
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class, ClientEvents::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
                ClientEvents::onLoggingOut);
        Astrum.LOGGER.info(
                "astrum: client UB input wired (cast key {} / preset wheel key {}, threshold {} ms, both overlays on GUI layers, config screen on {})",
                ClientKeyBindings.UB_CAST_KEY, ClientKeyBindings.UB_PRESET_KEY,
                WheelInputMachine.HOLD_THRESHOLD_MILLIS, ClientKeyBindings.CONFIG_KEY);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, EFFECT_LAYER, UbEffectOverlay::render);
        event.registerAbove(EFFECT_LAYER, HUD_LAYER, UbHud::render);
        event.registerAbove(HUD_LAYER, WHEEL_LAYER, WheelOverlay::render);
        event.registerAbove(WHEEL_LAYER, CAST_PAD_LAYER, CastPadOverlay::render);
        Astrum.LOGGER.info("astrum: UB layers registered above the hotbar ({}, {}, {}, {})",
                EFFECT_LAYER, HUD_LAYER, WHEEL_LAYER, CAST_PAD_LAYER);
    }

    /**
     * One physical key event, press, repeat or release.
     *
     * <p>Presses of other keys are ignored while another GUI is open — the inventory's keys are not ours
     * to take — but releases are always delivered, because a press that armed a machine before the GUI
     * opened still has to settle, and settling is what prevents a stuck {@code PENDING} state.
     *
     * <p>While an overlay is up, two kinds of key are consumed here: {@code 1}–{@code 5} become a pick
     * ({@link WheelDigits}) for whichever overlay is up, and {@code Esc} closes it. The {@code Esc} case
     * is a fallback — normally {@link #onScreenOpening} has already closed the overlay while cancelling
     * the pause menu — and it costs nothing when it is not needed, because closing is derived from the
     * machine state.
     */
    static void onKey(InputEvent.Key event) {
        int action = event.getAction();
        boolean down = action != InputConstants.RELEASE;

        // The config key is observed, never intercepted: InputEvent.Key is a notification at the end of
        // vanilla's keyboard handling, so there is nothing here to cancel and nothing we need to.
        // Repeats and releases are ignored; open() itself refuses while another GUI is up, which is what
        // keeps a K typed into chat or into a config text box from opening a second screen.
        if (ClientKeyBindings.matchesConfig(event.getKey(), event.getScanCode())) {
            if (action == InputConstants.PRESS) {
                ClientConfigScreen.open(Minecraft.getInstance());
            }
            return;
        }

        WheelInputMachine active = activeMachine();
        if (down && active != null) {
            int digit = WheelDigits.digitForKey(event.getKey());
            if (digit != WheelDigits.NO_DIGIT) {
                // The digit goes to the overlay that is actually up: on the pad it casts, on the wheel it
                // only moves the preset. The machine, not this method, decides which (spec §5.5).
                act(active, active.onDigit(digit));
                return;
            }
            if (event.getKey() == GLFW.GLFW_KEY_ESCAPE) {
                closeOverlay("escape");
                return;
            }
        }

        if (ClientKeyBindings.matchesCast(event.getKey(), event.getScanCode())) {
            if (action == InputConstants.PRESS) {
                onPress(CAST_MACHINE);
            } else if (action == InputConstants.RELEASE) {
                act(CAST_MACHINE, CAST_MACHINE.onRelease(nowMillis()));
            }
            return;
        }
        if (ClientKeyBindings.matchesPreset(event.getKey(), event.getScanCode())) {
            if (action == InputConstants.PRESS) {
                onPress(PRESET_MACHINE);
            } else if (action == InputConstants.RELEASE) {
                act(PRESET_MACHINE, PRESET_MACHINE.onRelease(nowMillis()));
            }
        }
    }

    /**
     * Arms one key's gesture, if the gesture is ours to begin.
     *
     * @param machine the machine of the key that was pressed
     */
    private static void onPress(WheelInputMachine machine) {
        if (machine.state() != State.IDLE) {
            // The overlay is up or the gesture is pending: a repeat event must not restart it.
            return;
        }
        if (overlayUp()) {
            // The other key's overlay owns the keyboard. Pressing the other key while it is up is either a
            // slip or an attempt to have two overlays at once; one overlay at a time is the whole point of
            // having separated the keys, so it is refused rather than stacked.
            Astrum.LOGGER.debug("astrum: {} press ignored — the {} overlay is already up",
                    machine.purpose(), activeMachine().purpose());
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (minecraft.screen != null) {
            // Somebody else's GUI owns the keyboard; the key is not ours until it closes.
            return;
        }
        machine.onPress(nowMillis());
    }

    /**
     * The start of a client tick: the one point that is <b>before</b> vanilla's own key handling.
     *
     * <p>Vanilla's {@code Minecraft.tick} runs {@code handleKeybinds()} (which switches the hotbar slot
     * for a queued digit click) later in the same tick, and {@code ClientTickEvent.Pre} is posted at the
     * very top of it. So the two things that have to win against vanilla live here: draining the hotbar
     * digits while an overlay is up, and noticing that the world the overlay was opened in is gone.
     */
    static void onClientTickPre(ClientTickEvent.Pre event) {
        if (resumeSoundNextTick) {
            resumeSoundNextTick = false;
            Minecraft.getInstance().getSoundManager().resume();
            Astrum.LOGGER.debug("astrum: sound resumed after a suppressed pause menu");
        }
        if (!overlayUp()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || minecraft.getConnection() == null || minecraft.screen != null) {
            // Left the world, disconnected, or a GUI took over without going through the opening event:
            // the overlay must not survive any of those.
            closeOverlay("the world went away");
            return;
        }
        drainHotbarClicks(minecraft);
        if (presetWheelUp()) {
            // Only the ring selects by flick; the pad has no selection to move.
            sampleCamera(minecraft);
        }
    }

    /**
     * One client tick's end: the only place the hold threshold can be crossed, and the home of the
     * refusal sound.
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
        for (WheelInputMachine machine : new WheelInputMachine[] {CAST_MACHINE, PRESET_MACHINE}) {
            if (machine.state() != State.PENDING) {
                continue;
            }
            if (minecraft.player == null) {
                // Left the world mid-gesture: settle it rather than carry it into the next one.
                act(machine, machine.onRelease(nowMillis()));
                continue;
            }
            act(machine, machine.poll(nowMillis()));
        }
    }

    /**
     * A screen is about to open: an overlay must not survive it, and Escape must not open the pause menu.
     *
     * <p>Escape reaches vanilla before any mod's key listener, and vanilla's answer is
     * {@code pauseGame → setScreen(new PauseScreen(...))}. NeoForge fires this cancellable event from
     * {@code setScreen} <em>before</em> the screen is installed, which makes it the one place the pause
     * menu can be refused without a {@code Screen} ever being set — and therefore without
     * {@code KeyMapping.releaseAll()} running (spec §5.5).
     *
     * @param event the cancellable opening event
     */
    static void onScreenOpening(ScreenEvent.Opening event) {
        if (!overlayUp()) {
            return;
        }
        boolean pauseMenu = event.getNewScreen() instanceof PauseScreen;
        closeOverlay(pauseMenu ? "escape (pause menu suppressed)" : "another screen opened");
        if (pauseMenu) {
            event.setCanceled(true);
            // pauseGame() pauses the sound after setScreen returns; undo that on the next tick, or one
            // Escape would leave the game silent.
            resumeSoundNextTick = true;
        }
    }

    /** {@return whether the cast pad is up, i.e. the cast key is held past its threshold} */
    static boolean castPadUp() {
        return CAST_MACHINE.state() == State.ACTIVE;
    }

    /** {@return whether the ring wheel is up, i.e. the preset wheel key is held past its threshold} */
    static boolean presetWheelUp() {
        return PRESET_MACHINE.state() == State.ACTIVE;
    }

    /**
     * The client left a world: forget any effect that was running.
     *
     * <p>The effect's fade is a difference of two <b>game times</b>, and a new world starts its own clock.
     * A timestamp carried across would therefore be either far in the future (the effect never starts) or
     * far in the past (it starts again on rejoin) — both look like a rendering fault and neither is worth
     * debugging from a screenshot.
     *
     * <p>It also runs when the connection drops, which is the interesting case: a player who quits mid-cast
     * would otherwise still hold a live effect for the join screen.
     *
     * @param event the disconnect event
     */
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientCastEffect.clear();
    }

    /** {@return whether either overlay is up} */
    static boolean overlayUp() {
        return castPadUp() || presetWheelUp();
    }

    /**
     * {@return the overlay that is up, or {@code null} when neither is}
     *
     * <p>At most one can be up: opening an overlay settles the other key's gesture, and a press while one
     * is up is refused ({@link #onPress}).
     */
    private static WheelInputMachine activeMachine() {
        if (castPadUp()) {
            return CAST_MACHINE;
        }
        return presetWheelUp() ? PRESET_MACHINE : null;
    }

    /** {@return the cast key's machine, for tests and probes} */
    static WheelInputMachine castMachine() {
        return CAST_MACHINE;
    }

    /** {@return the preset wheel key's machine, for tests and probes} */
    static WheelInputMachine presetMachine() {
        return PRESET_MACHINE;
    }

    /** {@return the pick state of the ring that is up, read by {@link WheelOverlay}} */
    static WheelSession session() {
        return SESSION;
    }

    /**
     * Obeys one decision from a machine.
     *
     * <p>The machine is passed alongside the decision rather than looked up afterwards: a pick decision
     * arrives <em>after</em> the machine has already returned to {@code IDLE}, so "which machine produced
     * this" cannot be answered from the state any more.
     *
     * @param source   the machine that produced the decision
     * @param decision what the gesture became
     */
    private static void act(WheelInputMachine source, Decision decision) {
        switch (decision) {
            case TAP_CAST -> requestCast(WheelPreset.slot());
            case OPEN_CAST_PAD -> openOverlay(CAST_MACHINE, "cast pad");
            case OPEN_WHEEL -> openOverlay(PRESET_MACHINE, "preset wheel");
            case CAST_SLOT -> {
                requestCast(pickedSlot(source));
                teardown("digit cast");
            }
            case SET_PRESET -> {
                WheelPreset.select(pickedSlot(source));
                Astrum.LOGGER.debug("astrum: preset set by digit -> {} (no cast: the wheel key never casts)",
                        WheelPreset.slot());
                teardown("digit preset");
            }
            case CLOSE -> teardown("release key");
            case NONE -> {
                // nothing to do
            }
        }
    }

    /**
     * Reads the digit the machine just recorded and converts it into the 0-based slot the wire and the
     * server use, through {@link WheelSlots#slotForDigit} — the one implementation of that mapping.
     *
     * <p>Both failures here are programming errors, not player input, and are thrown rather than
     * swallowed: a press that produces nothing is exactly what spec §5.5 forbids (禁止静默失败).
     *
     * @param source the machine that produced the pick
     * @return the 0-based slot
     */
    private static int pickedSlot(WheelInputMachine source) {
        int digit = source.pickedSlot().orElseThrow(() -> new IllegalStateException(
                "astrum: a pick decision without a digit — WheelInputMachine.onDigit was not the source"));
        return WheelSlots.slotForDigit(digit).orElseThrow(() -> new IllegalStateException(
                "astrum: wheel digit " + digit + " has no slot (expected " + WheelSlots.FIRST_DIGIT + "–"
                        + WheelSlots.LAST_DIGIT + ")"));
    }

    /**
     * An overlay is up: nothing to install, because each is drawn from its own machine's state.
     *
     * <p>The work is bookkeeping: settle the other key's gesture (at most one overlay at a time) and
     * reset the ring's pick state, so the wheel always opens with no sector selected and with the camera
     * baseline taken fresh — otherwise the tick that ran between opening and the first sample would count
     * as a flick.
     *
     * @param opened the machine whose hold passed the threshold
     * @param what   short name for the log
     */
    private static void openOverlay(WheelInputMachine opened, String what) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) {
            // Something took the screen between the press and the threshold; keep the world as it is.
            opened.onRelease(nowMillis());
            return;
        }
        WheelInputMachine other = opened == CAST_MACHINE ? PRESET_MACHINE : CAST_MACHINE;
        if (other.state() != State.IDLE) {
            // Both keys held at once, or the other key never settled: one overlay at a time, and this one
            // won the race. The other gesture ends without acting — a CLOSE nobody asked for must not turn
            // into a cast or a preset change.
            other.onRelease(nowMillis());
            Astrum.LOGGER.debug("astrum: {} opened — the other key's gesture was settled", what);
        }
        SESSION.reset();
        cameraSampled = false;
        Astrum.LOGGER.debug("astrum: UB {} opened as an overlay (screen={}, preset {})", what,
                minecraft.screen, WheelPreset.slot());
    }

    /**
     * Closes the overlay from outside the gesture machines (Escape, another GUI, the world going away).
     *
     * <p>Idempotent by construction: it does nothing at all unless an overlay is really up, and "up" is
     * the machines' own state. So two close paths firing in the same tick (Escape's cancelled pause menu,
     * then the key event that followed it) produce <b>one</b> close, not two.
     *
     * @param reason short description for the log, e.g. {@code "escape"}
     */
    private static void closeOverlay(String reason) {
        if (!overlayUp()) {
            return;
        }
        // The decisions are deliberately discarded: the overlay is going away anyway, and a CLOSE that
        // nobody asked for must not become a cast or a preset change.
        for (WheelInputMachine machine : new WheelInputMachine[] {CAST_MACHINE, PRESET_MACHINE}) {
            if (machine.state() != State.IDLE) {
                machine.onRelease(nowMillis());
            }
        }
        teardown(reason);
    }

    /** The single teardown every close path goes through: forget the pick state, drop queued digits. */
    private static void teardown(String reason) {
        SESSION.reset();
        cameraSampled = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            drainHotbarClicks(minecraft);
        }
        Astrum.LOGGER.debug("astrum: UB overlay closed ({}, preset {})", reason, WheelPreset.slot());
    }

    /**
     * Throws away hotbar slot clicks for {@code 1}–{@code 5} while an overlay is up (spec §5.5:
     * 屏蔽 {@code 1}–{@code 5} 的快捷栏切换 — for both keys).
     *
     * <p>Vanilla queues a click in {@code KeyboardHandler.keyPress} and turns it into
     * {@code inventory.selected = i} in {@code Minecraft.handleKeybinds}. Both happen before any mod's key
     * listener is notified, so the only place to win is between them: the client tick's start. The click
     * is dropped, the <em>key event</em> is not, so the overlay still receives its pick.
     *
     * <p>Only the first five of the nine hotbar mappings are drained: {@code 6}–{@code 9} are not wheel
     * digits, and spec §5.5 allows exactly one interception (其余键位一概放行).
     */
    private static void drainHotbarClicks(Minecraft minecraft) {
        for (int slot = 0; slot < WheelSlots.LAST_DIGIT; slot++) {
            KeyMapping hotbarSlot = minecraft.options.keyHotbarSlots[slot];
            while (hotbarSlot.consumeClick()) {
                // intentionally emptied: while an overlay is up a digit picks a slot, it does not switch one
            }
        }
    }

    /**
     * Feeds one tick of camera movement to {@link WheelSession}, which turns its direction into a pick.
     *
     * <p>The cursor is {@code GLFW_CURSOR_DISABLED} in game and {@code MouseHandler}'s accumulators are
     * private, so the game's own record of the mouse movement is the camera turn: vanilla rotates the
     * player by exactly the accumulated mouse delta, scaled by the player's sensitivity. Direction is all
     * {@link WheelFlick} needs, so the scaling drops out.
     *
     * <p>The first tick after opening only records the baseline: movement that happened before the wheel
     * was up must not count as a flick.
     */
    private static void sampleCamera(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (!cameraSampled) {
            cameraSampled = true;
            lastYaw = yaw;
            lastPitch = pitch;
            return;
        }
        double deltaYaw = WheelFlick.yawDelta(lastYaw, yaw);
        double deltaPitch = (double) pitch - (double) lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;

        int picked = SESSION.accept(deltaYaw, deltaPitch);
        if (picked != WheelGeometry.NO_SEGMENT && ClientPartyCache.slot(picked).isPresent()) {
            WheelPreset.select(picked);
            Astrum.LOGGER.debug("astrum: wheel preset -> {} (flick {}°, {}°)", picked,
                    Math.round(deltaYaw), Math.round(deltaPitch));
        }
    }

    /**
     * Asks the server to fire a slot.
     *
     * <p>Sends the slot and nothing else; the server re-resolves the party, the gauge and the cooldown,
     * and answers a refusal on the same connection (spec §5.5, §9.5).
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
            // WheelPreset.slot(). A bad slot is a programming error, so it is not turned into a silently
            // dropped press (spec §5.5: 禁止静默失败) — it is reported as a bug.
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

