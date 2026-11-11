package com.astrum.pcrd.impl.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Client-side configuration (spec §8.3), registered as {@link ModConfig.Type#CLIENT}.
 *
 * <h2>Why CLIENT and not COMMON</h2>
 * Every option here is pure presentation — wheel size and opacity, whether the wheel scrolls,
 * whether casting nudges the camera. The server never reads them, so putting them in a server file
 * would let a server operator change another player's screen; {@code CLIENT} puts
 * {@code config/astrum-client.toml} in each player's own install (spec §8.1, §7.4).
 *
 * <h2>Lazy reads, same rule as the server file</h2>
 * These are handles, not copies (spec §8.4 guardrail 1). A renderer calls
 * {@code ClientConfig.WHEEL.wheelScale.get()} on the frame it draws, so an edit in the config
 * screen takes effect without restarting, and a value read before the file loads is never frozen.
 */
@ApiStatus.Internal
public final class ClientConfig {

    /** The whole {@code astrum-client.toml} spec. */
    public static final ModConfigSpec SPEC;

    /** {@code [wheel]} — appearance and scroll behaviour. */
    public static final WheelOptions WHEEL;

    /** {@code [camera]} — optional feedback during a cast. */
    public static final CameraOptions CAMERA;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        WHEEL = new WheelOptions(builder);
        CAMERA = new CameraOptions(builder);
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    /**
     * Registers this file for the given mod.
     *
     * @param container the mod whose {@code config/astrum-client.toml} this becomes
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    /**
     * {@code [wheel]} section.
     *
     * <p>{@code enableScrollSwitching} defaults to {@code false} while the server's
     * {@code scrollSwitchPolicy} defaults to {@code ALLOW}: the server grants permission for the
     * wheel to scroll, and the player still opts in locally (spec §5.5, §8.2-8.3).
     */
    public static final class WheelOptions {

        /** Player-side opt-in for using the mouse wheel to change preset while the wheel is open. */
        public final ModConfigSpec.BooleanValue enableScrollSwitching;

        /** Ring radius as a fraction of screen height (spec §5.5: 35–45%). */
        public final ModConfigSpec.DoubleValue wheelScale;

        /** Ring background alpha (spec §5.5: 0.25–0.35); icons and labels stay opaque. */
        public final ModConfigSpec.DoubleValue wheelOpacity;

        private WheelOptions(ModConfigSpec.Builder builder) {
            builder.push("wheel");
            enableScrollSwitching = builder
                    .comment(" 是否允许用滚轮切换预设（服务端 scrollSwitchPolicy 另有一道许可）")
                    .define("enableScrollSwitching", false);
            wheelScale = builder
                    .comment(" 轮盘半径 = 屏高百分比（规格建议 0.35 ~ 0.45；定义域 0 ~ 1 防止越界）")
                    .defineInRange("wheelScale", 0.40, 0.0, 1.0);
            wheelOpacity = builder
                    .comment(" 环带背景透明度（规格建议 0.25 ~ 0.35）；图标与文字不受影响")
                    .defineInRange("wheelOpacity", 0.30, 0.0, 1.0);
            builder.pop();
        }
    }

    /**
     * {@code [camera]} section.
     *
     * <p>Off by default: spec §5.6 keeps the player unlocked during a cast, and an unrequested
     * camera push on top of that reads as a bug rather than as feedback.
     */
    public static final class CameraOptions {

        /** Slight camera push during a UB cast; default off. */
        public final ModConfigSpec.BooleanValue ubCameraPush;

        private CameraOptions(ModConfigSpec.Builder builder) {
            builder.push("camera");
            ubCameraPush = builder
                    .comment(" 演出时轻微镜头反馈，默认关")
                    .define("ubCameraPush", false);
            builder.pop();
        }
    }
}
