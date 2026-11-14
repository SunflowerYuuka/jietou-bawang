package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.LandedHit;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.effect.AttackBuffs;
import com.astrum.pcrd.impl.growth.ContributionTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turns {@link UbDamage}'s plan into real damage: the game-bound half of the ultimate's output.
 *
 * <h2>Where this is called from, and why that moment</h2>
 * From the cast path, at the moment the animation reads as having landed — see
 * {@link com.astrum.pcrd.impl.showcase.ShowcaseEntity}: the showcase owns a {@link Runnable} that this
 * class is behind, and it runs it on its own <b>strike frame</b>
 * ({@code UbDamage.strikeTick(animationTicks)} = half of the 1.4 s clip ≈ 14 ticks, which is where both
 * shipped clips put the arm's swing from -170° to +50°). If the clip is cut short — the owner logs out,
 * the chunk unloads, the hard deadline fires — the entity runs the same callback when it leaves the
 * level, so an interrupted ultimate <b>cannot lose its damage</b> (spec §5.6's 动画被打断就丢伤害).
 * And when the showcase budget forbids an entity at all, {@code ShowcaseManager} calls this inline, so
 * the effect is never dropped by a visual limit (功能不断，画面不炸).
 *
 * <p>All three sites share <b>one</b> {@link SettleOnce} per cast, which is what makes "once" true
 * across them rather than per site.
 *
 * <h2>Attribution: the caster owns the hit</h2>
 * Every application is a real {@code hurt()} with a {@code DamageSource} whose causing <b>and</b> direct
 * entity is the caster, of type {@link UbDamage#DAMAGE_TYPE}. That single choice makes three existing
 * paths fire without touching them:
 *
 * <ul>
 *   <li>{@code CombatEvents.onDeath} sees {@code source.getEntity() instanceof ServerPlayer} and credits
 *       {@code ubChargePerKill} (spec §5.4).</li>
 *   <li>{@code CombatEvents.onDamageLanded} credits {@code ubChargePerDamage} for the health the hit
 *       removed — our damage is fused out of <b>hook evaluation</b> but explicitly not out of charge
 *       ({@code LandedHit.Plan}).</li>
 *   <li>{@link ContributionTracker} is filled here, which is what makes {@code KillExperience} pay the
 *       firing character on a kill: without it, a kill by ultimate would log "no character contributed
 *       damage — no experience", because the ultimate never passes through the hook pipeline that
 *       normally writes that ledger.</li>
 * </ul>
 *
 * <h2>Why the ledger entry is written <em>before</em> the hit</h2>
 * A killing blow settles the ledger <b>inside</b> {@code hurt()} ({@code setHealth} → {@code die} →
 * {@code LivingDeathEvent} → {@code KillExperience.settle} → {@code ContributionTracker.take}). A
 * contribution recorded afterwards would therefore always be too late for exactly the hit that matters,
 * and would leave a pair behind for a victim that will never die again. So the entry is written first,
 * with the damage clamped to the health the target still has — the same clamp
 * {@link LandedHit#healthLost} applies to the event's unclamped number, used here so overkill cannot be
 * credited to a character.
 *
 * <h2>What it deliberately does not do</h2>
 * No multiplier chain: see {@link UbDamage}'s javadoc for why the tier's {@code damage} is applied
 * verbatim and why that is the correct reading of spec §5.4/§6.2 rather than an omission.
 * {@code teamChargeBonus} is still unimplemented, and is left visible in the schema rather than silently
 * dropped. {@code attackBuff} is applied here, after the damage — see {@link #settleBuff}.
 */
@ApiStatus.Internal
public final class UbDamageSettlement {

    private UbDamageSettlement() {
    }

    /**
     * Applies one cast's damage, and nothing else.
     *
     * <p>Never throws: it runs on the cast path (the gauge is already spent) and, on the fallback path,
     * inside an entity's own tick. A failure is logged with the target that caused it, and the rest of
     * the plan still runs — one bad victim must not cost the other hits, the same rule the extra-damage
     * flush follows.
     *
     * @param level       the level the caster is in
     * @param caster      the player whose ultimate fired
     * @param characterId the character that fired — attribution for the kill ledger
     * @param tier        the tier that fired
     */
    public static void apply(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier) {
        if (level == null || caster == null || tier == null || characterId == null) {
            return;
        }
        try {
            applyGuarded(level, caster, characterId, def, tier);
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: UB damage settlement for {} threw; the cast itself is "
                    + "unaffected", characterId, e);
        }
    }

    /**
     * {@return this cast's settlement, wrapped so it can run <b>at most once</b>}
     *
     * <p>The one place "one cast settles once" is wired, so the two sinks cannot disagree about it.
     * {@code ShowcaseManager} arms it and hands it to the entity (which runs it at its strike frame, and
     * again — never twice — when it is removed); {@code EffectsOnlySink} runs it inline on the cast.
     * Every site shares the returned {@link SettleOnce}, which is what makes "once" true <em>across</em>
     * sites rather than per site.
     *
     * <p>The latch is taken <b>before</b> the work runs, so a damage listener that reached back into
     * this cast could not re-enter it, and a throwing run still counts as spent: whatever landed before
     * the failure is on the victim's health already, and re-running the plan would double it.
     *
     * @param level       the level the caster is in
     * @param caster      the player whose ultimate fired
     * @param characterId the character that fired — the kill ledger's only way to name it
     * @param def         that character's definition
     * @param tier        the tier that fired
     */
    public static Runnable once(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier) {
        return once(() -> apply(level, caster, characterId, def, tier));
    }

    /**
     * {@return {@code settlement}, wrapped so it can run <b>at most once</b>}
     *
     * <p>The same latch as the six-argument form, over a bare {@link Runnable}: the "once" guarantee is
     * a property of this one method, so it can be asserted with a counter and no world (see
     * {@code UbDamageSettlementTest}). It is also what the entity path's strike-frame and removal
     * fallback share, so those two cannot settle a cast twice.
     *
     * @param settlement what to run at most once; must not be {@code null}
     */
    public static Runnable once(Runnable settlement) {
        SettleOnce latch = new SettleOnce();
        return () -> {
            if (!latch.claim()) {
                return;
            }
            try {
                settlement.run();
            } finally {
                latch.done();
            }
        };
    }

    private static void applyGuarded(ServerLevel level, ServerPlayer caster,
            ResourceLocation characterId, CharacterDef def, UbTier tier) {
        // Before the damage: 「提升自己的物理攻击」 is a buff the caster keeps, so it must be live for
        // whatever follows this cast. Applying it first also means a cast whose damage hits nothing
        // (no target in reach) still grants the buff, which is what the wiki describes — the buff is a
        // property of casting the ultimate, not of connecting with it.
        settleBuff(caster, characterId, tier);
        double reach = UbDamage.range(tier);
        List<UbDamage.Target> candidates = candidates(level, caster, reach);
        UbDamage.Plan plan = UbDamage.plan(tier,
                new UbDamage.Caster(caster.getUUID(), alive(caster)), candidates, allyMode());
        if (plan.isEmpty()) {
            Astrum.LOGGER.debug("astrum: UB {} of {} hit nothing ({} candidates within {} blocks, "
                    + "{} hits declared)", characterId, name(caster), candidates.size(), reach,
                    tier.hits());
            return;
        }
        Holder<DamageType> holder = damageType(level);
        if (holder == null) {
            Astrum.LOGGER.warn("astrum: damage type {} is not loaded, so the ultimate of {} dealt no "
                    + "damage; is data/astrum/damage_type/ub_damage.json in the pack?",
                    UbDamage.DAMAGE_TYPE, name(caster));
            return;
        }

        String display = def == null ? characterId.toString() : def.displayName();
        long nowTick = level.getGameTime();
        int applied = 0;
        for (UbDamage.Hit hit : plan.hits()) {
            Entity found = level.getEntity(hit.target());
            if (!(found instanceof LivingEntity victim) || victim.isRemoved() || !victim.isAlive()) {
                Astrum.LOGGER.debug("astrum: UB hit {}/{} on {} skipped: the target is gone",
                        hit.ordinal() + 1, tier.hits(), hit.target());
                continue;
            }
            // Before the hit: a killing blow consumes this ledger inside hurt(), see the class javadoc.
            float creditable = LandedHit.healthLost(hit.amount(), victim.getHealth());
            if (creditable > 0.0F) {
                ContributionTracker.note(caster.getUUID(), victim.getUUID(), characterId, creditable,
                        nowTick);
            }
            DamageSource source = new DamageSource(holder, caster, caster);
            float before = victim.getHealth();
            boolean landed;
            try {
                landed = victim.hurt(source, hit.amount());
            } catch (RuntimeException e) {
                Astrum.LOGGER.error("astrum: UB hit {}/{} on {} threw; the remaining hits still run",
                        hit.ordinal() + 1, tier.hits(), name(victim), e);
                continue;
            }
            float lost = Math.max(0.0F, before - victim.getHealth());
            if (landed) {
                applied++;
            }
            Astrum.LOGGER.debug("astrum: UB {} hit {}/{} on {} -> {} damage, lost {} hp, landed={} "
                            + "(hp {}/{})", display, hit.ordinal() + 1, tier.hits(), name(victim),
                    hit.amount(), lost, landed, victim.getHealth(), victim.getMaxHealth());
        }
        Astrum.LOGGER.debug("astrum: UB {} of {} settled {} of {} declared hits on {} target(s), "
                        + "{} damage asked in total", display, name(caster), applied, plan.hits().size(),
                plan.targets().size(), plan.total());
    }

    /**
     * Applies the tier's {@code attackBuff} to the caster, if it declares one.
     *
     * <p>Settled here, next to the damage, because it is the other half of what the ultimate <em>does</em>
     * — and because this is already the one place a paid-for cast becomes real, so a second call site
     * would be a second answer to "what does an ultimate apply".
     *
     * <p>Guarded like the damage: a buff that cannot be applied must not cost the hits that follow, and
     * the cast has already been paid for by the time this runs.
     */
    private static void settleBuff(ServerPlayer caster, ResourceLocation characterId, UbTier tier) {
        Optional<UbTier.AttackBuffDef> buff = tier.attackBuff();
        if (buff.isEmpty()) {
            return;
        }
        UbTier.AttackBuffDef wanted = buff.get();
        try {
            AttackBuffs.apply(caster, wanted.multiplier(), wanted.seconds(), characterId);
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: applying the attack buff of {} threw; the cast's damage is "
                    + "unaffected", characterId, e);
        }
    }

    /**
     * {@return every living thing the plan may consider, the caster included}
     *
     * <p>The caster is a candidate on purpose even though they can never be a victim: the ally policy
     * reads the caster's own team name off their candidate, so removing it would make
     * {@link AllyMode#SAME_TEAM_ONLY} spare nobody.
     *
     * <p>The box is a cube and the rule is a sphere, so the caller's kernel does the squared-distance
     * test; here the box is only the broad phase, the same shape {@code EffectExecutors.around} uses.
     */
    private static List<UbDamage.Target> candidates(ServerLevel level, ServerPlayer caster,
            double reach) {
        double usable = Double.isFinite(reach) && reach > 0.0 ? reach : 0.0;
        List<UbDamage.Target> candidates = new ArrayList<>();
        AABB box = caster.getBoundingBox().inflate(usable);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box,
                other -> other != null && other.isAlive() && !other.isRemoved())) {
            candidates.add(target(caster, entity));
        }
        boolean casterListed = candidates.stream().anyMatch(c -> c.id().equals(caster.getUUID()));
        if (!casterListed) {
            candidates.add(target(caster, caster));
        }
        return candidates;
    }

    /** {@return one entity as the plan sees it} */
    private static UbDamage.Target target(ServerPlayer caster, LivingEntity entity) {
        boolean self = entity.getUUID().equals(caster.getUUID());
        double distance = self ? 0.0 : Math.sqrt(entity.distanceToSqr(caster));
        boolean player = entity instanceof ServerPlayer;
        Optional<String> team = entity instanceof ServerPlayer holder ? teamName(holder)
                : Optional.empty();
        return new UbDamage.Target(entity.getUUID(), player, self, team,
                Double.isFinite(distance) ? distance : Double.MAX_VALUE, untouchable(entity));
    }

    /**
     * {@return whether the game would refuse this entity's damage outright}
     *
     * <p>A spectator ({@code noPhysics}, {@code isSpectator()} checked in {@code Player.hurt}) and a
     * creative-mode player ({@code abilities.invulnerable}) are both immune. Asking here rather than
     * relying on {@code hurt()} to say no keeps the plan — and therefore the log line — from claiming a
     * hit that cannot land.
     */
    private static boolean untouchable(LivingEntity entity) {
        return entity.isSpectator()
                || (entity instanceof ServerPlayer player && player.getAbilities().invulnerable);
    }

    /** {@return the {@code minecraft:damage_type} holder {@link UbDamage#DAMAGE_TYPE} resolves to} */
    private static Holder<DamageType> damageType(ServerLevel level) {
        Registry<DamageType> types =
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return types.getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, UbDamage.DAMAGE_TYPE))
                .orElse(null);
    }

    /**
     * {@return the configured ally policy, falling back to the permissive one}
     *
     * <p>The same fallback, for the same reason, as {@code GuardedAreaChargeSink}: an unreadable value
     * must not switch the mechanic off for a table with no teams.
     */
    private static AllyMode allyMode() {
        try {
            return AllyMode.parse(ServerConfig.MULTIPLAYER.allyMode.get());
        } catch (RuntimeException notLoaded) {
            return AllyMode.ALL_PLAYERS;
        }
    }

    /** {@return the player's scoreboard team name, or empty — the name, never {@code isAlliedTo}} */
    private static Optional<String> teamName(ServerPlayer player) {
        PlayerTeam team = player.getTeam();
        return team == null ? Optional.empty() : Optional.of(team.getName());
    }

    private static boolean alive(ServerPlayer caster) {
        return caster.isAlive() && !caster.isRemoved() && !caster.isSpectator();
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(Entity entity) {
        return entity.getName().getString();
    }

    /** {@return the ids the plan would hit, for the probe and for a future debug command} */
    public static List<UUID> preview(ServerLevel level, ServerPlayer caster, UbTier tier) {
        if (level == null || caster == null || tier == null) {
            return List.of();
        }
        return UbDamage.plan(tier, new UbDamage.Caster(caster.getUUID(), alive(caster)),
                candidates(level, caster, UbDamage.range(tier)), allyMode()).targets();
    }
}
