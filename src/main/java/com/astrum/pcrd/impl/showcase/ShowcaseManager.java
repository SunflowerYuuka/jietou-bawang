package com.astrum.pcrd.impl.showcase;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.ub.ShowcaseSink;
import com.astrum.pcrd.impl.ub.SettleOnce;
import com.astrum.pcrd.impl.ub.UbDamageSettlement;
import com.astrum.pcrd.impl.ub.UbService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The one thing that spawns, counts and removes showcase entities (spec §5.6).
 *
 * <h2>Why one object is both the sink and the factory</h2>
 * {@code ShowcaseSink} is the seam Task 12 built so the cast pipeline could be complete and testable
 * with nothing in the world; this class is the implementation Task 13 installs through
 * {@link UbService#installShowcase}. They are the same object because the two halves must not be
 * allowed to disagree: the entity that plays the animation and the entity that counts against
 * {@code maxActiveShowcaseEntities} are the same entity, and the cap has to be enforced at the moment
 * of spawning rather than reconciled afterwards.
 *
 * <h2>The cap: the entity is dropped, the effect never is</h2>
 * Spec §5.6 and the {@link ShowcaseSink} contract both say 功能不断，画面不炸: when the level already
 * holds {@code maxActiveShowcaseEntities} showcases, a new cast settles <b>no entity</b> and the cast
 * still succeeds — <b>and its damage is settled inline</b> ({@code settle} runs the same
 * {@link com.astrum.pcrd.impl.ub.SettleOnce}-guarded callback the entity would have run). So
 * {@link #spawn} answers {@link Optional#empty()} in that case — it does not throw,
 * does not warn, and does not refuse the cast. The count comes from the level's live entities rather
 * than from a counter, because a counter drifts the first time a showcase dies by any other path (a
 * chunk unload, {@code /kill}, a dimension change), and a stuck counter would silently switch the
 * visual half off forever.
 *
 * <h2>Every despawn path</h2>
 * <ol>
 *   <li><b>Animation finished</b> and <b>hard timeout</b> — both in {@code ShowcaseEntity.tick()} via
 *       {@link ShowcaseLifetime#shouldDespawn}. The second is the backstop spec §5.6 insists on.</li>
 *   <li><b>Server stopping</b> — {@link #onServerStopping} discards every showcase in every level.</li>
 *   <li><b>The owner left</b> — {@link #onPlayerLoggedOut} discards the showcases that player owned.
 *       Ownership lives on the entity ({@code ShowcaseEntity.owner()}) rather than in a side table, so
 *       the fact disappears with the entity: a first draft kept a {@code Map<entityId, playerId>} and
 *       was measured holding 13 records after 3 live entities, because a normal despawn has no hook to
 *       prune one.</li>
 * </ol>
 *
 * <h2>What is deliberately not here</h2>
 * No tick listener (the lifetime is the entity's own {@code tick()}, the cap is read at spawn), and no
 * damage arithmetic: the tier's {@code hits} / {@code damage} / {@code radius} are turned into a plan by
 * {@code UbDamage} and into real {@code hurt()} calls by {@code UbDamageSettlement}. What this class
 * contributes to the damage half is the <b>timing and the once-only guarantee</b> — it arms one
 * {@code SettleOnce} per cast and hands the same {@link Runnable} to the entity (which runs it at its
 * strike frame, or at its removal if the clip is cut short) or runs it inline when the entity budget
 * forbids a showcase. So "the ultimate deals 20" is true whether or not anything is drawn.
 */
@ApiStatus.Internal
public final class ShowcaseManager implements ShowcaseSink {

    /**
     * How long the shipped cast animation runs, in seconds — the value
     * {@code animations/djeeta.animation.json} declares for {@code ub_cast.animation_length}.
     *
     * <p>A constant on the server side rather than a number read from disk: the animation file is a
     * client resource, and the server must never load one to decide how long an entity lives. The two
     * cannot drift unnoticed because {@code ShowcaseLifetimeTest} reads the shipped file and asserts
     * {@link #castAnimationTicks()} agrees with its declared length.
     *
     * <p>When a future tier ships a longer animation its length belongs in that tier's data (a story
     * for the task that adds one), not in a second constant here.
     */
    public static final double CAST_ANIMATION_SECONDS = 1.4;

    /** {@return the ticks {@link #CAST_ANIMATION_SECONDS} lasts} */
    public static int castAnimationTicks() {
        return ShowcaseLifetime.animationTicks(CAST_ANIMATION_SECONDS);
    }

    private ShowcaseManager() {
    }

    /**
     * Installs this manager as the showcase sink and subscribes its cleanup listeners.
     *
     * <p>Called once from the mod constructor. Both listeners go on the game bus, because both events
     * are server-lifecycle events rather than registry events.
     */
    public static void install() {
        ShowcaseManager manager = new ShowcaseManager();
        INSTANCE.set(manager);
        UbService.installShowcase(manager);
        NeoForge.EVENT_BUS.addListener(manager::onServerStopping);
        NeoForge.EVENT_BUS.addListener(manager::onPlayerLoggedOut);
        Astrum.LOGGER.info("astrum: showcase sink installed (entity {} at {} blocks in front, cast "
                + "animation {}s = {}t, cap from multiplayer.maxActiveShowcaseEntities)",
                ShowcaseTypes.ENTITY_ID, ShowcaseEntity.PLACEMENT_DISTANCE, CAST_ANIMATION_SECONDS,
                castAnimationTicks());
    }

    /**
     * The instance {@link #install()} created, or {@code null} before it.
     *
     * <p>Not used by any production path — the sink reference in {@code UbService} is what calls
     * {@link #settle} — but it is the one handle a probe or a test can use to confirm the installation
     * actually happened, which is otherwise only visible in a log line.
     */
    private static final java.util.concurrent.atomic.AtomicReference<ShowcaseManager> INSTANCE =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** {@return the installed manager, or {@code null} before {@link #install()} */
    public static @Nullable ShowcaseManager installed() {
        return INSTANCE.get();
    }

    // ------------------------------------------------------------------ the sink

    /**
     * Settles one accepted ultimate: arm the damage, then spawn the showcase — or don't.
     *
     * <p><b>The damage is settled either way.</b> Spec §5.6 and the {@link ShowcaseSink} contract both
     * say 功能不断，画面不炸: when the level is at {@code maxActiveShowcaseEntities} the <i>entity</i> is
     * dropped and the ultimate still lands. So the settlement is not a side effect of a successful
     * spawn — when there is no entity to own it, it runs here, inline, and the cast is indistinguishable
     * to the player except that nothing is drawn.
     *
     * <p>Never throws. {@code UbService.settle} wraps a throwing sink already, but a showcase must not
     * be able to fail a cast whose gauge is spent, so everything is guarded here too and reported at WARN
     * rather than escaping.
     */
    @Override
    public void settle(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier, boolean enhanced) {
        Runnable damage = damageFor(level, caster, characterId, def, tier);
        try {
            int cap = ServerConfig.MULTIPLAYER.maxActiveShowcaseEntities.get();
            int active = activeCount(level);
            Optional<ShowcaseEntity> spawned = spawn(level, caster, tier, active, cap, damage);
            if (spawned.isEmpty()) {
                Astrum.LOGGER.debug("astrum: no showcase for {} ({}) — {} of {} already active; the "
                        + "cast itself still resolved and its damage is settled inline",
                        def.displayName(), enhanced ? "enhanced" : "base", active, cap);
                damage.run();
            }
        } catch (RuntimeException e) {
            Astrum.LOGGER.warn("astrum: the showcase for {} could not spawn; the cast itself is "
                    + "unaffected and its damage was settled inline", def.displayName(), e);
            damage.run();
        }
    }

    /**
     * {@return the one settlement of this cast, shared by every site that may run it}
     *
     * <p>A single {@link SettleOnce} makes the three possible moments — the entity's strike frame, the
     * entity's removal if the clip is cut short, and the inline call above when no entity exists — add
     * up to one settlement rather than up to three.
     */
    private static Runnable damageFor(ServerLevel level, ServerPlayer caster,
            ResourceLocation characterId, CharacterDef def, UbTier tier) {
        SettleOnce once = new SettleOnce();
        return () -> {
            if (!once.claim()) {
                return;
            }
            try {
                UbDamageSettlement.apply(level, caster, characterId, def, tier);
            } finally {
                once.done();
            }
        };
    }

    // ------------------------------------------------------------------ the factory

    /**
     * {@return the showcase that was spawned, or empty when the budget forbids one}
     *
     * <p>The signature the plan names, plus the settlement the spawn must own. Split from
     * {@link #settle} so the budget decision is a value a caller (and a test) can drive with an explicit
     * {@code activeCount}/{@code maxActive}, with no config and no level in play for the decision itself.
     *
     * <p>The damage is armed <b>before</b> {@code addFreshEntity}, so the entity cannot tick without it:
     * an entity that ticked first would miss the strike frame of a very short clip.
     *
     * @param level       the level to spawn into
     * @param caster      the player whose ultimate fired; the showcase stands in front of them
     * @param tier        the tier that fired, whose {@code animation} names what should play
     * @param activeCount how many showcases that level already holds
     * @param maxActive   the configured cap; {@code <= 0} means "never spawn one"
     * @param damage      what settles the cast's damage, or {@code null} for a showcase that only plays
     */
    public static Optional<ShowcaseEntity> spawn(ServerLevel level, ServerPlayer caster, UbTier tier,
            int activeCount, int maxActive, Runnable damage) {
        if (!ShowcaseLifetime.maySpawn(activeCount, maxActive)) {
            return Optional.empty();
        }
        ShowcaseEntity entity = ShowcaseTypes.SHOWCASE.get().create(level);
        if (entity == null) {
            Astrum.LOGGER.warn("astrum: the showcase entity type produced no instance; the cast is "
                    + "unaffected but nothing will be seen");
            return Optional.empty();
        }
        entity.configure(tier.animation(), castAnimationTicks());
        entity.setOwner(caster.getUUID());
        entity.settleDamageOnStrike(damage);
        entity.placeInFrontOf(caster);
        if (!level.addFreshEntity(entity)) {
            Astrum.LOGGER.warn("astrum: the level refused the showcase entity for {}; the cast is "
                    + "unaffected", caster.getGameProfile().getName());
            return Optional.empty();
        }
        Astrum.LOGGER.debug("astrum: showcase {} spawned for {} at {} (declared animation '{}', "
                        + "playing '{}', {}t, strike at {}t, {} of {} active)", entity.getId(),
                caster.getGameProfile().getName(), entity.position(), entity.declaredAnimation(),
                entity.animation(), entity.animationTicks(), entity.strikeTick(), activeCount + 1,
                maxActive);
        return Optional.of(entity);
    }

    /** {@return a showcase that plays {@code tier}'s animation and settles nothing} */
    static Optional<ShowcaseEntity> spawn(ServerLevel level, ServerPlayer caster, UbTier tier,
            int activeCount, int maxActive) {
        return spawn(level, caster, tier, activeCount, maxActive, null);
    }

    /**
     * {@return how many showcase entities {@code level} currently holds}
     *
     * <p>Walks the level's entities of that exact type rather than trusting a counter. Cheap because it
     * runs once per accepted cast (a cooldown-gated event), never per tick.
     */
    public static int activeCount(ServerLevel level) {
        return all(level).size();
    }

    /** {@return every showcase entity in {@code level}, for the two cleanup paths and for probes} */
    public static List<ShowcaseEntity> all(ServerLevel level) {
        List<ShowcaseEntity> found = new ArrayList<>();
        for (Entity candidate : level.getAllEntities()) {
            if (candidate instanceof ShowcaseEntity showcase && showcase.isActive()) {
                found.add(showcase);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ the despawn paths

    /** Discards every showcase in every level when the server stops (spec §5.6). */
    private void onServerStopping(ServerStoppingEvent event) {
        int removed = 0;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ShowcaseEntity showcase : all(level)) {
                showcase.discard();
                removed++;
            }
        }
        Astrum.LOGGER.info("astrum: server stopping — discarded {} showcase {}", removed,
                removed == 1 ? "entity" : "entities");
    }

    /** Discards the showcases one player owns, when that player leaves. */
    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID leaving = event.getEntity().getUUID();
        int removed = 0;
        for (ServerLevel level : event.getEntity().getServer().getAllLevels()) {
            for (ShowcaseEntity showcase : all(level)) {
                if (leaving.equals(showcase.owner())) {
                    showcase.discard();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            Astrum.LOGGER.debug("astrum: {} left — discarded {} of their showcase entities",
                    event.getEntity().getGameProfile().getName(), removed);
        }
    }
}
