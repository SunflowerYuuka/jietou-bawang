package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.config.ClientConfig;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import org.jetbrains.annotations.ApiStatus;

/**
 * The in-game configuration screen (spec §8.0): the one NeoForge screen that reads our
 * {@code ModConfigSpec} and turns every registered option into a widget, reached two ways.
 *
 * <h2>1. The mod list's Config button</h2>
 * {@code ModListScreen.updateCache} enables that button only when
 * {@code IConfigScreenFactory.getForMod(selectedMod)} is present, and that lookup is
 * {@code ModList.get().getModContainerById(...).flatMap(m -> m.getCustomExtension(IConfigScreenFactory.class))}
 * — i.e. <b>our</b> container must carry the extension point. {@code ClientNeoForgeMod} registers
 * {@code ConfigurationScreen::new} for the {@code neoforge} mod only, which is why a mod that just
 * calls {@code registerConfig(...)} ships files and no button. So this class registers the factory on
 * our container in {@link #register}.
 *
 * <h2>2. A rebindable key</h2>
 * {@link #open} installs the same screen from inside a world ({@link ClientKeyBindings#CONFIG_SCREEN},
 * default {@code K}). A {@code Screen} is the right shape here and the wrong shape for the wheel: the
 * wheel must not take the mouse or the movement keys (combat, spec §5.5), while a config screen is a
 * deliberate modal action whose whole point is that the player stops playing for a moment.
 *
 * <h2>Why the screen is not written by hand</h2>
 * {@code ConfigurationScreen} walks {@code ModConfigSpec.getValues()}: {@code EnumValue} and
 * {@code BooleanValue} become cycle buttons, ranged numbers become sliders, strings become text
 * boxes. Every option we register therefore appears with no per-option code, and stays in step when a
 * new option is added. {@code ConfigScreenLabelsTest} pins the half that is ours: the label and title
 * translation keys the screen asks for.
 *
 * <h2>Client-only by construction</h2>
 * {@link ConfigurationScreen} and {@link Minecraft} are client classes, so this class lives in
 * {@code impl/client} (spec §7 端隔离) and is only reached from the {@code Dist.CLIENT} branch of
 * {@link Astrum}.
 */
@ApiStatus.Internal
public final class ClientConfigScreen {

    private ClientConfigScreen() {
    }

    /**
     * Registers the config screen factory for this mod, which is what makes the mod list's Config
     * button active.
     *
     * <p>{@code ConfigurationScreen::new} is the two-argument constructor
     * {@code ConfigurationScreen(ModContainer, Screen)} used as
     * {@link IConfigScreenFactory#createScreen(ModContainer, Screen)}; the parent passed to it is the
     * mod list screen, so the screen's own back path returns there.
     *
     * @param container this mod's container
     */
    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // No config value is read here: this runs in the mod constructor, *before* the CLIENT file is
        // loaded, and ConfigValue.get() throws until then ("Cannot get config value before config is
        // loaded"). The values are logged in open(), where the file is loaded for sure.
        Astrum.LOGGER.info("astrum: config screen factory registered on mod {} ({})",
                container.getModId(), IConfigScreenFactory.class.getSimpleName());
    }

    /**
     * Opens the configuration screen from inside the game.
     *
     * <p>The screen is built through the extension point, with
     * {@link IConfigScreenFactory#getForMod} — the exact lookup {@code ModListScreen} uses to decide
     * whether its Config button is active — so opening it here proves the same path the button takes,
     * and an unregistered factory is visible in the log instead of being papered over. The parent is
     * {@code null}: closing returns to the world instead of to a menu that is not there.
     *
     * <p>Every value is read from the spec at the moment it is drawn
     * ({@code ClientConfig.WHEEL.wheelMode.get()} in the renderer), and the screen writes through the
     * same {@code ConfigValue.set}, so a CLIENT edit is visible on the next frame. The SERVER edit
     * writes {@code astrum-server.toml} on close; the file is read again when a world is entered, which
     * is what the screen's own section titles say (NeoForge 21.1.265 says nothing for the SERVER type —
     * {@code ConfigurationSectionScreen.needsRestart} is only set for {@code STARTUP}).
     *
     * @param minecraft the client
     * @return whether a screen was opened; {@code false} when another GUI already owns the keyboard
     */
    public static boolean open(Minecraft minecraft) {
        if (minecraft.screen != null) {
            return false;
        }
        var maybeContainer = ModList.get().getModContainerById(Astrum.MOD_ID);
        if (maybeContainer.isEmpty()) {
            // Cannot happen in a running game: the mod whose code this is, is loaded.
            Astrum.LOGGER.error("astrum: config screen not opened — mod {} is not in the mod list", Astrum.MOD_ID);
            return false;
        }
        ModContainer container = maybeContainer.get();
        Optional<IConfigScreenFactory> factory = IConfigScreenFactory.getForMod(container.getModInfo());
        if (factory.isEmpty()) {
            // The extension point is missing, so the mod list's Config button is disabled too. Fall back
            // to the screen class itself: a mod-binding bug must not cost the player the key as well.
            Astrum.LOGGER.error("astrum: no IConfigScreenFactory on our container — the mod list Config button is disabled; opening {} directly",
                    ConfigurationScreen.class.getSimpleName());
        }
        Screen screen = factory
                .map(registered -> registered.createScreen(container, null))
                .orElseGet(() -> new ConfigurationScreen(container, null));
        minecraft.setScreen(screen);
        Astrum.LOGGER.info("astrum: config screen opened ({}, via extension point={}), {}",
                minecraft.screen == null ? "null" : minecraft.screen.getClass().getName(),
                factory.isPresent(),
                currentValues());
        return true;
    }

    /**
     * {@return the three player-facing values, for the log}
     *
     * <p>Guarded because a log line must never be the thing that breaks the screen:
     * {@code ConfigValue.get()} throws {@code IllegalStateException} until the file is loaded, and the
     * open path is reached from a key press, where an exception would be a crash rather than a line.
     */
    private static String currentValues() {
        try {
            return "wheelMode=" + ClientConfig.WHEEL.wheelMode.get()
                    + ", wheelOpacity=" + ClientConfig.WHEEL.wheelOpacity.get()
                    + ", enableUbHud=" + ClientConfig.HUD.enableUbHud.get()
                    + ", hudAnchor=" + ClientConfig.HUD.hudAnchor.get();
        } catch (IllegalStateException | NullPointerException notLoadedYet) {
            return "client config not loaded yet";
        }
    }
}
