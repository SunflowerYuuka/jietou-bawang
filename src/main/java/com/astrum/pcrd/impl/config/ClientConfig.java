package com.astrum.pcrd.impl.config;

import com.astrum.pcrd.impl.client.WheelGeometry;
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

    /** {@code [hud]} — where the UB gauge sits, and whether it is drawn at all. */
    public static final HudOptions HUD;

    /** {@code [effect]} — whether a UB cast draws its screen effect. */
    public static final EffectOptions EFFECT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        WHEEL = new WheelOptions(builder);
        CAMERA = new CameraOptions(builder);
        HUD = new HudOptions(builder);
        EFFECT = new EffectOptions(builder);
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

        /**
         * Layout of the wheel (spec §5.5): a hollow {@code RING} or one complete {@code DISC}.
         *
         * <p>Read lazily at draw time like the other wheel values, so switching it in the config
         * screen changes the next frame instead of the next launch.
         */
        public final ModConfigSpec.EnumValue<WheelMode> wheelMode;

        /** Ring radius as a fraction of screen height (spec §5.5: 20–33%, default 27%). */
        public final ModConfigSpec.DoubleValue wheelScale;

        /** Ring background alpha (spec §5.5: 0.00–1.00, default 0.75); icons and labels stay opaque. */
        public final ModConfigSpec.DoubleValue wheelOpacity;

        private WheelOptions(ModConfigSpec.Builder builder) {
            builder.push("wheel");
            enableScrollSwitching = builder
                    .comment(" 是否允许用滚轮切换预设（服务端 scrollSwitchPolicy 另有一道许可）")
                    .define("enableScrollSwitching", false);
            // The declared range is WheelGeometry's own clamp window, not a wider guess: the spec's
            // 20–33% is a design decision (spec §5.5), so the config must declare exactly it. Declaring
            // 0.0 ~ 1.0 while the clamp rewrote everything into 0.20–0.33 is the defect the previous round
            // fixed for wheelOpacity — typing 0.8 silently became 0.33 with nothing to say why. Reading the
            // bounds from the clamp's constants means the two cannot drift apart again.
            wheelScale = builder
                    .comment(" 轮盘半径 = 屏高比例（规格 §5.5：0.20 ~ 0.33，默认 0.27）；"
                            + "超出范围的值会被拒绝，WheelGeometry 用同一组常数再夹一次")
                    .defineInRange("wheelScale", WheelGeometry.RADIUS_FRACTION_DEFAULT,
                            WheelGeometry.RADIUS_FRACTION_MIN, WheelGeometry.RADIUS_FRACTION_MAX);
            wheelOpacity = builder
                    .comment(" 环带背景透明度（0.00 ~ 1.00，默认 0.75，即规格 §5.5 的窗口；1.00 = 完全不透明）；"
                            + "图标与文字不受影响")
                    .defineInRange("wheelOpacity", WheelGeometry.BAND_ALPHA_DEFAULT,
                            WheelGeometry.BAND_ALPHA_MIN, WheelGeometry.BAND_ALPHA_MAX);
            wheelMode = builder
                    .comment(" 轮盘布局：DISC = 中心填充的完整圆盘（默认），RING = 中心镂空、只画环带",
                            " 两种模式选择逻辑完全一致，都保留中心死区；RING 露出更多战场视野")
                    .defineEnum("wheelMode", WheelMode.DISC);
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

    /**
     * {@code [hud]} section (spec §7: HUD 位置可配置、可整体关闭、默认避开 hotbar 与右上角).
     *
     * <p>Not in spec §8.3's sketch, which stops at {@code [wheel]}/{@code [camera]}; the positional
     * requirement in §7 needs somewhere to live, and a client file is the only place it can: the
     * server must not decide where another player's gauge is drawn.
     *
     * <p>The default anchor is the bottom-left, which is clear of the hotbar (bottom centre) and of
     * the top-right corner (Jade/AppleSkin). The offsets exist for players whose own HUD already owns
     * the corners; {@code impl.client.HudLayout} still keeps the widget on screen and above the
     * hotbar whatever they type.
     */
    public static final class HudOptions {

        /** Draw the UB gauge at all; {@code false} removes it entirely. */
        public final ModConfigSpec.BooleanValue enableUbHud;

        /** Screen corner the gauge is pinned to. */
        public final ModConfigSpec.EnumValue<HudAnchor> hudAnchor;

        /** Extra horizontal offset from the anchor, in GUI pixels; positive is right. */
        public final ModConfigSpec.IntValue hudOffsetX;

        /** Extra vertical offset from the anchor, in GUI pixels; positive is down. */
        public final ModConfigSpec.IntValue hudOffsetY;

        private HudOptions(ModConfigSpec.Builder builder) {
            builder.push("hud");
            enableUbHud = builder
                    .comment(" 是否显示 UB 充能条与冷却环（关掉则完全不绘制）")
                    .define("enableUbHud", true);
            hudAnchor = builder
                    .comment(" 贴哪个角：BOTTOM_LEFT / BOTTOM_RIGHT / TOP_LEFT / TOP_RIGHT"
                            + "（默认左下，避开快捷栏与右上角 Jade/AppleSkin）")
                    .defineEnum("hudAnchor", HudAnchor.BOTTOM_LEFT);
            hudOffsetX = builder
                    .comment(" 相对锚点的水平偏移（GUI 像素，正数向右）")
                    .defineInRange("hudOffsetX", 0, -1000, 1000);
            hudOffsetY = builder
                    .comment(" 相对锚点的垂直偏移（GUI 像素，正数向下）")
                    .defineInRange("hudOffsetY", 0, -1000, 1000);
            builder.pop();
        }
    }

    /**
     * {@code [effect]} section: the visual a UB cast produces (规格 2026-11-13: 使用UB仅展示特效).
     *
     * <h2>Why the switch is client-side</h2>
     * The effect is drawn on the caster's own screen and affects nothing else: the damage, the cooldown
     * and the gauge are decided on the server whether or not this is on. A full-screen flash is also
     * exactly the kind of thing a player with photosensitivity, or one who records video, must be able
     * to turn off without asking a server operator — so it lives in {@code astrum-client.toml}.
     *
     * <p>Default on, because "使用UB仅展示特效" means the effect <em>is</em> the ultimate's visible half;
     * a default of off would make a cast look like nothing happened.
     */
    public static final class EffectOptions {

        /** Draw the UB's screen effect when a cast is accepted. */
        public final ModConfigSpec.BooleanValue enableUbCastEffect;

        private EffectOptions(ModConfigSpec.Builder builder) {
            builder.push("effect");
            enableUbCastEffect = builder
                    .comment(" 释放 UB 时是否绘制全屏特效（金白剑光 + 爆发 + 角色切入图）",
                            " 关掉只是不画：伤害、冷却、充能都由服务端决定，不受影响",
                            " 特效约 1 秒淡出，不挡输入、不暂停游戏")
                    .define("enableUbCastEffect", true);
            builder.pop();
        }
    }
}
