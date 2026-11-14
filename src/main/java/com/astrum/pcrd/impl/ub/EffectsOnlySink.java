package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.UbTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a UB cast does now: <b>effects only</b> — it settles the damage and spawns the burst on the
 * bodies it hit, and puts <b>no entity</b> in the world.
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
 * <h2>The visible half: real particles, on the victims, in world space</h2>
 * The cast's effect is spawned by {@link UbCastParticles}: {@code ServerLevel.sendParticles} sends the
 * custom {@code astrum:ub_spark}/{@code astrum:ub_mote} types to every client within range, where they
 * tick in world space. There is no effect payload any more, and there is no rectangle: the server call
 * is the whole cue.
 *
 * <p><b>The order matters and is the fix of 2026-11-14.</b> The damage runs <b>first</b> and records
 * the bodies it actually hit in a per-cast {@link UbBurstVictims.Ledger}; the emitter then reads that
 * ledger. The old order was "settle, then burst around the caster", which had no way to know who was
 * hit and therefore drew on the attacker — 打对面的攻击，特效却显示在自己身上. Reading the victims
 * off the settlement's own {@code hurt()} results is also the only way to be sure the two agree: a
 * target that dies or walks out of reach between the plan and the hit gets no burst.
 *
 * <p>The client-side {@code [effect] enableUbCastEffect} toggle is still applied by the particle
 * provider, which spawns nothing when it is off. Damage and the effect remain independent: turning the
 * toggle off does not change the cast or its damage.
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
                + "settles on the cast; the caster sees real particles in world space)");
    }

    /** {@return the installed sink, or {@code null} before {@link #install()} */
    public static EffectsOnlySink installed() {
        return installed;
    }

    /**
     * Settles one accepted ultimate: damage first, then the burst on whatever it hit, and nothing in
     * the world.
     *
     * <p>Never throws. {@code UbService.settle} already wraps a throwing sink, but a cast has spent the
     * player's gauge by the time this runs, so both halves are guarded here too and a failure is reported
     * at WARN rather than escaping.
     *
     * <p>The ledger is created <b>here</b>, per cast: it is the channel between the two halves and its
     * lifetime is this method's. Nothing static, so there is nothing to clear on logout or shutdown.
     */
    @Override
    public void settle(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier, boolean enhanced) {
        UbBurstVictims.Ledger landed = new UbBurstVictims.Ledger();
        settleWith(
                UbDamageSettlement.once(level, caster, characterId, def, tier, landed),
                () -> emitParticles(level, caster, characterId, enhanced, def, landed));
    }

    /**
     * One cast, as the two things it is made of, in order — the seam a unit test drives.
     *
     * <p>Split out because the two halves have different reasons to exist and both must run in this
     * order: the damage because a double settlement reads as extra hits, the particles because a second
     * emission would double the burst. Neither touches an entity, which is the whole point of the swap —
     * and {@code EffectsOnlySinkTest} holds that as a fact about the <b>class file</b>: the constant pool
     * of {@code EffectsOnlySink} mentions no type from {@code impl.showcase}, so no future edit can
     * quietly make this sink spawn the entity again.
     *
     * <p>The order is also what makes the burst land on the right bodies at all: the first half is
     * where the victims are discovered.
     *
     * @param damage    this cast's once-guarded settlement, which fills the ledger
     * @param particles this cast's particle burst, which reads it
     */
    static void settleWith(Runnable damage, Runnable particles) {
        damage.run();
        particles.run();
    }

    /**
     * Spawns the burst on the bodies the settlement hit.
     *
     * <p>{@link UbCastParticles#emit} already catches its own failures for the same reason this sink
     * does: the damage has landed by the time the effect runs, so a cosmetic failure must not surface
     * as a failed cast. This method adds the cast's identity to the DEBUG trail, not a second try/catch.
     */
    private static void emitParticles(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            boolean enhanced, CharacterDef def, UbBurstVictims.Ledger landed) {
        UbCastParticles.emit(level, caster, landed.landed(), enhanced);
        Astrum.LOGGER.debug("astrum: UB particle burst for {} ({}) — {} landed hit(s) on the ledger, "
                        + "damage already settled", displayName(def, characterId),
                enhanced ? "enhanced" : "base", landed.landed().size());
    }

    /** {@return the name used in log lines, falling back to the id when the pack has no definition} */
    private static String displayName(CharacterDef def, ResourceLocation characterId) {
        return def == null ? String.valueOf(characterId) : def.displayName();
    }
}

