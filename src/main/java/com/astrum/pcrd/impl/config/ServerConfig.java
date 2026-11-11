package com.astrum.pcrd.impl.config;

import java.util.List;
import java.util.function.Predicate;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-side configuration (spec §8.2), registered as {@link ModConfig.Type#SERVER}.
 *
 * <h2>Why SERVER and not COMMON</h2>
 * Everything here changes how damage, charge and ally selection are computed, and the server is the
 * only side that computes them. {@code SERVER} is also <b>per-save</b> ({@code <save>/serverconfig/}),
 * which is what the design wants: a pack author can tune one world without touching the client, and
 * the value cannot fork per dimension the way {@code SavedData} would (spec §8.1, §7).
 *
 * <h2>Config values are handles, never copies (spec §8.4 guardrail 1)</h2>
 * The fields below are {@link ModConfigSpec.ConfigValue} handles. They do not hold a number: a
 * caller writes {@code ServerConfig.BALANCE.diminishingBase.get()} <em>where it needs the value</em>,
 * every time. Nothing is copied into a {@code static double}, because that copy is exactly the bug
 * the spec warns about — read before the file loads it would freeze the default forever, and after
 * a reload it would ignore the operator's edit. {@code ConfigValue.get()} is itself reload-aware:
 * NeoForge clears its internal cache whenever the config is (re)loaded.
 *
 * <h2>Two clamp layers, on purpose (spec §8.4 guardrail 2)</h2>
 * Numeric options are declared with {@code defineInRange}, so a hand-edited file cannot carry an
 * absurd value past load. The kernel re-clamps anyway
 * ({@link com.astrum.pcrd.impl.config.Balance#clampBase(double)}), because the kernel is also called
 * from code paths with no config at all — and it is the layer {@code BalanceTest} can prove. There
 * is a second, less obvious reason not to use the plain {@code define(path, 1.0)} overload: its
 * validator demands exactly {@code Double}, so an operator writing the TOML literal {@code 1} would
 * have the option silently reset to its default. {@code defineInRange} accepts any {@code Number}.
 */
@ApiStatus.Internal
public final class ServerConfig {

    /** The whole {@code astrum-server.toml} spec. */
    public static final ModConfigSpec SPEC;

    /** {@code [balance]} — diminishing returns. */
    public static final BalanceOptions BALANCE;

    /** {@code [ub]} — charge gain and the shared cooldown. */
    public static final UbOptions UB;

    /** {@code [wheel]} — wheel input policy. */
    public static final WheelOptions WHEEL;

    /** {@code [multiplayer]} — ally selection and the area-charge guards. */
    public static final MultiplayerOptions MULTIPLAYER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        BALANCE = new BalanceOptions(builder);
        UB = new UbOptions(builder);
        WHEEL = new WheelOptions(builder);
        MULTIPLAYER = new MultiplayerOptions(builder);
        SPEC = builder.build();
    }

    private ServerConfig() {
    }

    /**
     * Registers this file for the given mod.
     *
     * @param container the mod whose {@code serverconfig/astrum-server.toml} this becomes
     */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    /**
     * {@code [balance]} section.
     *
     * <p>Named {@code BalanceOptions} rather than {@code Balance} so it cannot be confused with the
     * arithmetic kernel of the same package; the field keeps the plan's {@code BALANCE} name.
     */
    public static final class BalanceOptions {

        /** Master switch for same-dimension diminishing returns. */
        public final ModConfigSpec.BooleanValue enableDiminishingReturns;

        /** Decay factor; {@code 0.7} means the second same-dimension slot gets 70%. */
        public final ModConfigSpec.DoubleValue diminishingBase;

        private BalanceOptions(ModConfigSpec.Builder builder) {
            builder.push("balance");
            enableDiminishingReturns = builder
                    .comment(" 同维度递减开关（关掉后每个槽位都是 ×1.0）")
                    .define("enableDiminishingReturns", true);
            diminishingBase = builder
                    .comment(" 倍率 = base^(n-1)；范围按规格 §8.2 钉在 [0.1, 1.0]，填 0 或 2.0 会被拉回边界")
                    .defineInRange("diminishingBase", 0.7, Balance.MIN_MULTIPLIER, Balance.MAX_MULTIPLIER);
            builder.pop();
        }
    }

    /**
     * {@code [ub]} section: how fast the gauge fills and how long the shared cooldown lasts.
     */
    public static final class UbOptions {

        /** Cooldown shared by all five slots (spec §5.4: one bar, still charges during cooldown). */
        public final ModConfigSpec.IntValue ubGlobalCooldownTicks;

        /** Charge per point of damage dealt. */
        public final ModConfigSpec.DoubleValue ubChargePerDamage;

        /** Charge per point of damage taken (deliberately about a third of dealt). */
        public final ModConfigSpec.DoubleValue ubChargePerDamageTaken;

        /** Flat charge for a kill. */
        public final ModConfigSpec.DoubleValue ubChargePerKill;

        /** Trickle charge per second, so the gauge is never permanently stuck. */
        public final ModConfigSpec.DoubleValue ubChargePerSecond;

        private UbOptions(ModConfigSpec.Builder builder) {
            builder.push("ub");
            ubGlobalCooldownTicks = builder
                    .comment(" 全局共享冷却（tick）；CD 期间照常充能。0 = 无冷却")
                    .defineInRange("ubGlobalCooldownTicks", 100, 0, 72_000);
            ubChargePerDamage = builder
                    .comment(" 每点造成伤害的充能")
                    .defineInRange("ubChargePerDamage", 1.0, 0.0, 100.0);
            ubChargePerDamageTaken = builder
                    .comment(" 每点受到伤害的充能（约为主系数的 1/3）")
                    .defineInRange("ubChargePerDamageTaken", 0.33, 0.0, 100.0);
            ubChargePerKill = builder
                    .comment(" 击杀固定充能")
                    .defineInRange("ubChargePerKill", 20.0, 0.0, 100.0);
            ubChargePerSecond = builder
                    .comment(" 每秒自然回复充能（兜底）")
                    .defineInRange("ubChargePerSecond", 0.2, 0.0, 100.0);
            builder.pop();
        }
    }

    /**
     * {@code [wheel]} section: what the mouse wheel does while the wheel is open.
     *
     * <p>The value stays a string here; {@code impl.ub} parses it and owns the enum, so this file
     * holds no behaviour and adding a policy later needs no second source of truth.
     */
    public static final class WheelOptions {

        /** Accepted values of {@link #scrollSwitchPolicy}. */
        public static final List<String> SCROLL_SWITCH_POLICIES = List.of("ALLOW", "DENY", "FORCE");

        /** {@code ALLOW} / {@code DENY} / {@code FORCE}. */
        public final ModConfigSpec.ConfigValue<String> scrollSwitchPolicy;

        private WheelOptions(ModConfigSpec.Builder builder) {
            builder.push("wheel");
            scrollSwitchPolicy = builder
                    .comment(" 滚轮切换预设策略：ALLOW / DENY / FORCE")
                    .define("scrollSwitchPolicy", "ALLOW", inList(SCROLL_SWITCH_POLICIES));
            builder.pop();
        }
    }

    /**
     * {@code [multiplayer]} section: who counts as an ally, and the two guards that stop N players
     * carrying the same character from charging each other in a loop (spec §7.4).
     */
    public static final class MultiplayerOptions {

        /** Accepted values of {@link #allyMode}. */
        public static final List<String> ALLY_MODES = List.of("ALL_PLAYERS", "SAME_TEAM_ONLY");

        /** {@code ALL_PLAYERS} / {@code SAME_TEAM_ONLY}; never {@code isAlliedTo()} alone. */
        public final ModConfigSpec.ConfigValue<String> allyMode;

        /** Internal cooldown for one caster charging one receiver. */
        public final ModConfigSpec.IntValue areaChargePairCooldownTicks;

        /** Ceiling on what one receiver can gain from {@code area_charge} per second. */
        public final ModConfigSpec.DoubleValue areaChargeReceiveCapPerSecond;

        /** Concurrent showcase-entity limit; beyond it damage still lands, no entity spawns. */
        public final ModConfigSpec.IntValue maxActiveShowcaseEntities;

        private MultiplayerOptions(ModConfigSpec.Builder builder) {
            builder.push("multiplayer");
            allyMode = builder
                    .comment(" 友方判定：ALL_PLAYERS / SAME_TEAM_ONLY（无计分板队伍时 isAlliedTo 会给 false，故不用它做唯一判据）")
                    .define("allyMode", "ALL_PLAYERS", inList(ALLY_MODES));
            areaChargePairCooldownTicks = builder
                    .comment(" 同一施放者 → 同一接收者的 area_charge 内部 CD（tick）")
                    .defineInRange("areaChargePairCooldownTicks", 20, 0, 72_000);
            areaChargeReceiveCapPerSecond = builder
                    .comment(" 每个接收者每秒从 area_charge 获得的上限")
                    .defineInRange("areaChargeReceiveCapPerSecond", 6.0, 0.0, 100.0);
            maxActiveShowcaseEntities = builder
                    .comment(" 同世界演出实体上限，超限只结算伤害不生成实体")
                    .defineInRange("maxActiveShowcaseEntities", 8, 0, 256);
            builder.pop();
        }
    }

    /**
     * {@return a validator accepting only the given strings}
     *
     * <p>Not {@code Builder#defineInList}, which is the obvious spelling but crashes world start:
     * when the TOML file does not exist yet, {@code ModConfigSpec.correct} probes every option with
     * {@code test(null)}, and {@code defineInList} delegates to {@code acceptableValues::contains}
     * — while {@code List.of(...)} answers {@code contains(null)} with a
     * {@code NullPointerException} instead of {@code false}. The resulting failure is a fatal
     * {@code Exception in server tick loop} during {@code ConfigTracker.createDefaultConfig}, i.e.
     * before the config file is ever written.
     *
     * <p>The {@code instanceof} pattern admits {@code null} and answers {@code false}, which is what
     * the spec's "unknown value falls back" rule wants anyway.
     *
     * @param accepted the permitted strings
     */
    private static Predicate<Object> inList(List<String> accepted) {
        return value -> value instanceof String text && accepted.contains(text);
    }
}
