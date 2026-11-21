package com.astrum.pcrd.impl.effect;

import org.jetbrains.annotations.ApiStatus;

/**
 * 「恐慌」: what "cannot act" means on a server, and the one duration it lasts.
 *
 * <h2>What a server can and cannot do</h2>
 * <b>A server cannot press or unpress a client's keys.</b> The client reads its own keyboard and sends
 * packets; nothing the server does changes what a player's fingers do. So "panic" cannot honestly mean
 * "the keys are locked". What it <em>can</em> mean — and what this mod implements — is that the actions
 * those keys would perform have <b>no effect</b>:
 *
 * <table border="1">
 *   <caption>What panic actually does, per entity kind</caption>
 *   <tr><th>action</th><th>{@code Mob}</th><th>{@code ServerPlayer}</th></tr>
 *   <tr><td>move</td><td>AI does not tick ({@code noAi} for the duration, restored after)</td>
 *       <td>movement input zeroed each tick; the server also refuses the resulting motion</td></tr>
 *   <tr><td>left-click attack</td><td>no AI goal runs, so it never chooses a target</td>
 *       <td>{@code AttackEntityEvent} cancelled — the swing never lands</td></tr>
 *   <tr><td>right-click interaction</td><td>ditto</td>
 *       <td>{@code PlayerInteractEvent} cancelled (block, item and entity forms)</td></tr>
 * </table>
 *
 * <p>So the honest sentence is: <b>the entity cannot act</b>. It is not "the player cannot press keys", and
 * a modified client can still send whatever packets it likes — what stops it is the server-side
 * cancellation above, which is why the cancellation is the mechanism and not a courtesy. A client that
 * bypasses the UI is still held to the same three answers.
 *
 * <h2>How it lasts its declared time without a polling loop</h2>
 * The duration is carried by a real {@code MobEffectInstance} ({@link #DEFAULT_SECONDS} = 1.5 s unless the pack
 * says otherwise), so <b>vanilla's own effect clock</b> ends it — the same mechanism {@link AttackBuffEffect} and
 * {@link DefenceDownEffect} use, and the reason none of this needs an expiry sweep. The two things a
 * server must do per tick are done from hooks the engine already fires for <b>one entity</b>:
 *
 * <ul>
 *   <li>{@code MobEffect.applyEffectTick} — vanilla calls it for the instance's holder and nobody else. It
 *       is not an entity walk and not a level poll, which is the distinction this project's red line draws
 *       ({@code PassiveCharge} makes the same argument for {@code PlayerTickEvent}).</li>
 *   <li>{@code PlayerTickEvent.Post} — a per-player event, again with no enumeration in it.</li>
 * </ul>
 *
 * <h2>The duration is data, and it used to be a constant</h2>
 * Panic shipped with a fixed 1.5 s because the first brief fixed it there, and four characters' skills say
 * something else: 绫音's 束缚 is 2.5 s (and 3 s on her 二专), 胡桃's 击晕 5.5 s, 凯露's 击晕 8 s. A constant could
 * not say any of them, so each character recorded the difference in her {@code extra} — which renders as
 * 「尚未实现」 in the book for a mechanic that <em>was</em> implemented, only at the wrong length.
 *
 * <p>So {@code seconds} is honoured now: the hook's own {@code TieredParams.seconds} is the duration, the
 * {@value #DEFAULT_SECONDS}-second value is what an author who declares none gets, and the rung is still one
 * (a panic is a state, not a magnitude). {@link #MAX_SECONDS} is the one guard kept from the constant era: a
 * pack that writes 600 would turn 「恐慌」 into a minute-long disable no character's text describes, so the
 * duration is clamped to {@value #MAX_SECONDS} s with a warning rather than applied — a silent difference
 * would be the worse of the two failures.
 */
@ApiStatus.Internal
public final class PanicRules {

    /** Registry path: {@code astrum:panic}. */
    public static final String PATH = "panic";

    /** 1.5 s at Minecraft's fixed 20 ticks a second — what an author who declares no duration gets. */
    public static final int DURATION_TICKS = 30;

    /** The default duration in seconds: the brief's original number, kept as the fallback. */
    public static final double DEFAULT_SECONDS = 1.5;

    /** The longest duration a declared {@code seconds} may ask for: 30 s. See the class javadoc. */
    public static final double MAX_SECONDS = 30.0;

    /** Minecraft's fixed tick rate, the one place the 20 appears in this file. */
    private static final int TICKS_PER_SECOND = 20;

    /** How many rungs panic has: one. It is a state, not a magnitude. */
    public static final int MAX_TIER = 1;

    /** Particle tint: sickly dark violet, the "cannot act" colour, not the defence debuff's blue-violet. */
    public static final int COLOUR = 0x4A2E5A;

    private PanicRules() {
    }

    /**
     * {@return the duration in ticks for a declared one, or {@link #DURATION_TICKS} when none was declared}
     *
     * <p>The three-way reading every clock in this mod uses ({@code AttackBuffRules.ticksFor}): an <b>absent</b>
     * duration is the default, a declared positive one is honoured, and a declared zero or a hostile number is
     * the default rather than an exception — the caller passes {@code 0} for "the pack wrote none".
     *
     * @param declaredSeconds what the pack wrote, {@code 0} or a non-finite value for none
     */
    public static int ticksFor(double declaredSeconds) {
        if (!Double.isFinite(declaredSeconds) || declaredSeconds <= 0.0) {
            return DURATION_TICKS;
        }
        double capped = Math.min(declaredSeconds, MAX_SECONDS);
        return Math.max(1, (int) Math.round(capped * TICKS_PER_SECOND));
    }

    /**
     * {@return whether a declared duration had to be clamped to {@link #MAX_SECONDS}}
     *
     * <p>Asked by the applier only to log one line per out-of-range clause; the answer is not used to decide
     * anything else, so the clamp and its report cannot disagree about the number.
     *
     * @param declaredSeconds what the pack wrote
     */
    public static boolean isClamped(double declaredSeconds) {
        return Double.isFinite(declaredSeconds) && declaredSeconds > MAX_SECONDS;
    }
}
