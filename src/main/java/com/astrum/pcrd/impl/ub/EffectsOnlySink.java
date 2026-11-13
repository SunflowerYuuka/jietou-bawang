package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.network.UbCastEffectPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a UB cast does now: <b>effects only</b> — it settles the damage and tells the caster's client to
 * draw the effect, and puts <b>no entity</b> in the world.
 *
 * <h2>Why this replaced the entity sink</h2>
 * 使用UB仅展示特效 / 不需要实体，但是实体的相关代码保留. The showcase entity
 * ({@code impl.showcase.ShowcaseEntity} and everything around it) is <b>retained, compiling and
 * registered</b>, but nothing in the cast path spawns it. This is the second implementation
 * {@link ShowcaseSink} was written for; {@code impl.showcase.ShowcaseManager} is the first and is now
 * installed by nobody. See {@code ShowcaseManager.installEntitySink()}'s javadoc for how to switch back.
 *
 * <h2>Where the damage settles now, and why there</h2>
 * <b>Immediately, on the cast.</b> The old moment was the entity's strike frame (tick 14 of 28) — a
 * <em>tick source</em> the entity provided and which no longer exists. The two candidates were "settle
 * now" and "schedule it on the server tick"; this one is "settle now", because:
 *
 * <ol>
 *   <li>Spec §5.6's rule is 伤害在服务端一次性结算，<b>不等动画播完</b>. Settling at the cast instant is the
 *       most literal reading of it, and the old tick-14 choice existed only to line the numbers up with a
 *       swing that is no longer drawn.</li>
 *   <li>A scheduled settlement needs a clock, and a clock means either a tick listener (this project
 *       forbids one: the guard check is "entity/level tick polling") or a side table of pending casts
 *       with its own pruning, its own logout/unload paths and its own "what if the caster left" rule.
 *       That is a whole subsystem bought for 0.7 s of delay.</li>
 *   <li>Nothing depends on the delay. The reach check, the ally policy and the contribution ledger all
 *       read the world at the moment they run; a target that walks out of reach in 0.7 s is <b>less</b>
 *       likely to be hit by an immediate settlement, which is the honest outcome, not a regression.</li>
 * </ol>
 *
 * <p>The once-only guarantee is unchanged and still one {@link SettleOnce} per cast — it is now created
 * by {@link UbDamageSettlement#once} so the retained entity sink and this one cannot disagree about it.
 *
 * <h2>The client half</h2>
 * The cast is announced with {@link UbCastEffectPayload}, a <b>success</b> payload. The existing
 * {@code UbCastFeedbackPayload} is the <em>refusal</em> channel and is only sent when a cast is
 * rejected ("success sends nothing back: the gauge sync already carries it"), so there was no existing
 * success channel to reuse; the effect had to be told to the client somehow. The payload goes to the
 * caster only — a screen-space effect is unavoidable and personal, and broadcasting a full-screen flash
 * to bystanders is a decision this task deliberately does not make. Each player can also switch it off
 * locally ({@code [effect] enableUbCastEffect}).
 */
@ApiStatus.Internal
public final class EffectsOnlySink implements ShowcaseSink {

    /** The one instance {@link #install()} created, or {@code null} before it. */
    private static volatile EffectsOnlySink installed;

    /**
     * Installs this sink as the cast path's strategy. Called once, from {@code ShowcaseTypes.register}.
     *
     * <p>Idempotent: a second call replaces the instance and says so, because the only caller that could
     * do it twice is a test that forgot to clean up.
     */
    public static void install() {
        EffectsOnlySink sink = new EffectsOnlySink();
        installed = sink;
        UbService.installShowcase(sink);
        Astrum.LOGGER.info("astrum: UB cast sink installed = effects only (no entity is spawned; damage "
                + "settles on the cast; the caster's client is told to draw the effect)");
    }

    /** {@return the installed sink, or {@code null} before {@link #install()} */
    public static EffectsOnlySink installed() {
        return installed;
    }

    /**
     * Settles one accepted ultimate: damage first, then the client's cue, and nothing in the world.
     *
     * <p>Never throws. {@code UbService.settle} already wraps a throwing sink, but a cast has spent the
     * player's gauge by the time this runs, so both halves are guarded here too and a failure is reported
     * at WARN rather than escaping.
     */
    @Override
    public void settle(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier, boolean enhanced) {
        settleWith(
                UbDamageSettlement.once(level, caster, characterId, def, tier),
                () -> notifyClient(caster, characterId, enhanced, def, tier));
    }

    /**
     * One cast, as the two things it is made of, in order — the seam a unit test drives.
     *
     * <p>Split out because the two halves have different reasons to exist and both must run in this
     * order: the damage because a double settlement reads as extra hits, the cue because a double cue
     * would restart the fade. Neither touches an entity, which is the whole point of the swap — and
     * {@code EffectsOnlySinkTest} holds that as a fact about the <b>class file</b>: the constant pool of
     * {@code EffectsOnlySink} mentions no type from {@code impl.showcase}, so no future edit can quietly
     * make this sink spawn the entity again.
     *
     * @param damage   this cast's once-guarded settlement
     * @param feedback this cast's client cue
     */
    static void settleWith(Runnable damage, Runnable feedback) {
        damage.run();
        feedback.run();
    }

    /**
     * Tells the caster's client to draw the effect.
     *
     * <p>Swallows and reports a send failure: a player who left in the same tick, or a connection that is
     * already closing, must not turn a settled cast into a logged error with a stack trace. The damage is
     * already applied by the time this runs.
     */
    private static void notifyClient(ServerPlayer caster, ResourceLocation characterId, boolean enhanced,
            CharacterDef def, UbTier tier) {
        try {
            PacketDistributor.sendToPlayer(caster, new UbCastEffectPayload(characterId, enhanced));
            Astrum.LOGGER.debug("astrum: UB effect cue for {} ({}) sent to {} — no entity, damage already "
                            + "settled", displayName(def, characterId), enhanced ? "enhanced" : "base",
                    caster.getGameProfile().getName());
        } catch (RuntimeException e) {
            Astrum.LOGGER.warn("astrum: could not send the UB effect cue to {}; the cast itself is "
                    + "unaffected and its damage is settled", caster.getGameProfile().getName(), e);
        }
    }

    /** {@return the name used in log lines, falling back to the id when the pack has no definition} */
    private static String displayName(CharacterDef def, ResourceLocation characterId) {
        return def == null ? String.valueOf(characterId) : def.displayName();
    }
}
