package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.ub.UbCharge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * The attachment entry point: one place where a landed hit is turned into UB charge and extra
 * damage (spec §5.1). Every rule in this class is one of the three the spec calls non-negotiable.
 *
 * <h2>Which event, and why (measured, not assumed)</h2>
 * The listener hangs on {@link LivingDamageEvent.Post}. NeoForge 1.21.1 exposes four stages of one
 * damage sequence, and a real server run pinned down what each one actually offers:
 *
 * <table border="1">
 *   <caption>Measured on a dedicated server with a zombie, 5.0 damage, armour 2</caption>
 *   <tr><th>Event</th><th>Amount</th><th>Writable</th><th>Cancellable</th><th>Fires when</th></tr>
 *   <tr><td>{@code LivingIncomingDamageEvent}</td><td>pre-armour, 5.000</td><td>yes
 *       ({@code setAmount})</td><td>yes</td><td>also for hits the invulnerability window then
 *       swallows entirely (measured: {@code amount=4.0}, health delta 0)</td></tr>
 *   <tr><td>{@code LivingDamageEvent.Pre}</td><td>post-armour, 4.920</td><td>yes
 *       ({@code setNewDamage})</td><td>no</td><td>after armour/effects, before absorption; the
 *       entity's health is still the pre-hit value here</td></tr>
 *   <tr><td>{@code LivingDamageEvent.Post}</td><td>post-absorption damage, 4.920 — <b>not</b>
 *       clamped to the health that was left</td><td>no (immutable copy)</td><td>no</td><td>after
 *       health is subtracted — and only when the sequence got that far</td></tr>
 * </table>
 *
 * <p>{@code LivingDamageEvent} itself is an abstract base class with a private constructor: there
 * is no such event to listen to, only {@code Pre} and {@code Post}.
 *
 * <p><b>Why Post.</b> Rule 1 forbids touching the amount, so the writability of the other two
 * stages is a liability rather than a feature: rewriting {@code amount} is exactly how the spec's
 * "armour/crit re-scaling" failure happens, and the run measured it — asking for 7.0 through
 * {@code setAmount} landed as 6.888 after the armour step. Post has nothing to rewrite, and it fires
 * only for hits that reached health, so the attachment pipeline never runs for a hit the iframe
 * check ate. Its number is not, however, "health lost": see
 * {@link LandedHit#healthLost(float, float)} for the clamp that turns it into one.
 *
 * <h2>Rule 2 — the recursion fuse</h2>
 * Damage in the {@code astrum} namespace is ours, and ours must not come back through hook
 * evaluation or the extra damage would trigger the logic that deals more extra damage, forever. The
 * fuse gates <b>hooks only</b>: the same damage still earns UB charge, because spec §5.4 counts
 * damage dealt with no carve-out for mod-applied damage ({@link LandedHit.Plan}).
 *
 * <h2>Rule 3 — one hurt per tick per target</h2>
 * The batch is not flushed from inside the damage event. It is queued and drained at
 * {@link ServerTickEvent.Post}, which is measurable and safer: a {@code hurt()} nested inside
 * {@code LivingDamageEvent.Post} makes the outer sequence resume on an already-dead entity and
 * fired {@code LivingDeathEvent} <b>twice</b> for one kill (measured: deaths=2, loot=1). Queueing
 * until the tick ends also makes "same tick" literal — several hits on one target in one tick
 * merge into a single {@code hurt()} per damage type.
 *
 * <p>Everything this class does is server-side: {@code hurt()} returns early on the client, so
 * these events are only posted on the logical server (no {@code isDedicatedServer()} branch
 * anywhere, per spec §7).
 */
@ApiStatus.Internal
public final class CombatEvents {

    /** Where the merged extra damage is credited when no single character caused it. */
    private static final ResourceLocation NO_CHARACTER = InternalIds.id("combat");

    /** Batches waiting for the end of this tick, keyed by attacker and target. */
    private final Map<PendingKey, Pending> pending = new LinkedHashMap<>();

    /** Health of each entity when its damage sequence started, consumed by the matching Post. */
    private final Map<UUID, Float> preHitHealth = new HashMap<>();

    /** Players already warned about a quarantined roster, so the warning is one line, not per hit. */
    private final Set<UUID> warnedQuarantine = new HashSet<>();

    private CombatEvents() {
    }

    /** Subscribes this listener to the game bus. Called once, from the mod constructor. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new CombatEvents());
    }

    // ------------------------------------------------------------------ damage landing

    /**
     * Remembers the health a target had before its damage sequence, so the hit can be charged for
     * the health it actually removed rather than for the number the container carries.
     */
    @SubscribeEvent
    public void onDamagePre(LivingDamageEvent.Pre event) {
        preHitHealth.put(event.getEntity().getUUID(), event.getEntity().getHealth());
    }

    /**
     * A hit landed: credit charge for it, and queue the attachment damage it earned.
     *
     * <p>Charge comes first and is unconditional; only hook evaluation is fused. See
     * {@link LandedHit.Plan} for why that order is the correct one and not the other way round.
     */
    @SubscribeEvent
    public void onDamageLanded(LivingDamageEvent.Post event) {
        float applied = event.getNewDamage();
        if (!(applied > 0.0F)) {
            return;   // nothing was applied: no charge to earn, no hooks to run
        }
        LivingEntity target = event.getEntity();
        DamageSource source = event.getSource();
        ResourceLocation typeId = damageTypeId(source);
        LandedHit.Plan plan = LandedHit.plan(typeId);

        if (plan.creditsCharge()) {
            float credited = creditedDamage(target, applied);
            if (credited > 0.0F) {
                if (target instanceof ServerPlayer victim) {
                    credit(victim, credited * ServerConfig.UB.ubChargePerDamageTaken.get());
                }
                if (source.getEntity() instanceof ServerPlayer attacker && attacker != target) {
                    credit(attacker, credited * ServerConfig.UB.ubChargePerDamage.get());
                }
            }
        }

        if (!plan.runsHooks()) {
            Astrum.LOGGER.debug("astrum: own damage {} on {} charged but not re-entering hook evaluation "
                    + "(recursion fuse)", typeId, target.getName().getString());
            return;
        }
        if (source.getEntity() instanceof ServerPlayer attacker && attacker != target) {
            queue(attacker, target, source);
        }
    }

    /**
     * A mob died. Kill charge is credited to the player the game credits the kill to.
     *
     * <p>Deliberately <b>not</b> short-circuited on our own damage type: a death is a single event
     * whichever damage type finished the target, and crediting it once cannot recurse because
     * nothing here deals damage. (The fuse exists to protect hook evaluation, not the kill counter.)
     */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            credit(killer, ServerConfig.UB.ubChargePerKill.get());
        }
    }

    /**
     * {@return the damage to charge for: the part of {@code applied} that the target still had}
     *
     * <p>{@code Pre} always precedes {@code Post} for the same sequence, so the entry is normally
     * there. If it is not (a sequence that began before this listener existed), the raw number is
     * credited rather than zero, and the hole is logged.
     */
    private float creditedDamage(LivingEntity target, float applied) {
        Float preHealth = preHitHealth.remove(target.getUUID());
        if (preHealth == null) {
            Astrum.LOGGER.debug("astrum: no pre-hit health for {}, crediting {} unclamped",
                    target.getName().getString(), applied);
            return applied;
        }
        return LandedHit.healthLost(applied, preHealth);
    }

    /** Posts the charge change through {@link UbCharge}, which owns the write and the clamp. */
    private static void credit(ServerPlayer player, double delta) {
        if (delta > 0.0) {
            UbCharge.apply(player, NO_CHARACTER, delta);
        }
    }

    // ------------------------------------------------------------------ the flush

    /**
     * Drains the batches queued during this tick, one {@code hurt()} per damage type.
     *
     * <p>Runs after everything else in the tick has had its chance to queue, which is what makes the
     * merge a same-tick merge rather than a same-event one.
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        preHitHealth.clear();   // anything left here belonged to a sequence that never reached Post
        if (pending.isEmpty()) {
            return;
        }
        List<Pending> ready = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending queued : ready) {
            try {
                flush(queued);
            } catch (RuntimeException e) {
                // One bad target (or one bad third-party listener) must not cost the rest of the tick.
                Astrum.LOGGER.error("astrum: extra damage for {} threw, the other queued targets still run",
                        queued.target().getName().getString(), e);
            }
        }
    }

    private void flush(Pending pended) {
        LivingEntity target = pended.target();
        if (target.isRemoved() || !target.isAlive()) {
            Astrum.LOGGER.debug("astrum: extra damage for {} dropped, target is gone",
                    target.getName().getString());
            return;
        }
        Registry<DamageType> damageTypes = target.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        for (DamageBatch.Entry entry : pended.batch().entries()) {
            if (entry.amount() <= 0.0F) {
                continue;
            }
            Holder<DamageType> holder = damageTypes
                    .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, entry.type())).orElse(null);
            if (holder == null) {
                Astrum.LOGGER.warn("astrum: damage type {} is not loaded, dropping {} extra damage; "
                        + "is data/astrum/damage_type/{}.json in the pack?", entry.type(), entry.amount(),
                        entry.type().getPath());
                continue;
            }
            DamageSource applied = new DamageSource(holder, pended.attacker(), pended.attacker());
            try {
                boolean landed = target.hurt(applied, entry.amount());
                Astrum.LOGGER.debug("astrum: extra {} damage {} on {} landed={} hp={}", entry.type(),
                        entry.amount(), target.getName().getString(), landed, target.getHealth());
            } catch (RuntimeException e) {
                Astrum.LOGGER.error("astrum: applying {} extra damage to {} threw", entry.amount(),
                        target.getName().getString(), e);
            }
        }
    }

    private void queue(ServerPlayer attacker, LivingEntity target, DamageSource source) {
        DamageBatch batch = new DamageBatch();
        collect(attacker, target, source, batch);
        if (batch.isEmpty()) {
            return;
        }
        PendingKey key = new PendingKey(attacker.getUUID(), target.getUUID());
        Pending already = pending.get(key);
        if (already == null) {
            pending.put(key, new Pending(attacker, target, batch));
        } else {
            for (DamageBatch.Entry entry : batch.entries()) {
                already.batch().add(entry.type(), entry.amount());
            }
        }
    }

    /** Drops all per-session state when the world goes away, so nothing survives a restart. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        pending.clear();
        preHitHealth.clear();
        warnedQuarantine.clear();
    }

    // ------------------------------------------------------------------ hook evaluation

    /**
     * Fills {@code batch} from one player's wheel: {@code PARTY} × the character definitions, each
     * hook evaluated by {@link HookEvaluator}.
     *
     * <h2>Quarantine handling (the trap this task was warned about)</h2>
     * The multipliers are computed from the party and the registry — both readable while the
     * roster is quarantined — so a broken save does <b>not</b> switch the §5.2 anti-stacking
     * guardrail off. The roster is consulted for one thing only: whether the character in a slot is
     * owned. While it is quarantined that question is unanswerable, so the ownership filter is
     * dropped (the party is still readable and player-controlled) and a warning is logged once per
     * player. It is never allowed to throw: {@code Roster.entries()}/{@code progressOf} raise
     * {@code IllegalStateException} in that state, and an exception escaping here would land in the
     * middle of a damage sequence.
     */
    private void collect(ServerPlayer attacker, LivingEntity target, DamageSource source, DamageBatch batch) {
        Optional<Registry<CharacterDef>> characters = AstrumApi.characters();
        if (characters.isEmpty()) {
            return;   // no world loaded: nothing to read
        }
        Registry<CharacterDef> registry = characters.get();
        Party party = attacker.getData(Attachments.PARTY);

        Map<ResourceLocation, Dimension> dimensions = new HashMap<>();
        for (Optional<ResourceLocation> slot : party.slots()) {
            slot.ifPresent(id -> {
                CharacterDef def = registry.get(id);
                if (def != null) {
                    dimensions.put(id, def.dimension());
                }
            });
        }
        double[] multipliers = PartyMultipliers.all(party.slots(), dimensions,
                ServerConfig.BALANCE.diminishingBase.get(),
                ServerConfig.BALANCE.enableDiminishingReturns.get());

        Roster roster = attacker.getData(Attachments.ROSTER);
        boolean ownershipKnown = !roster.isQuarantined();
        if (!ownershipKnown) {
            warnQuarantined(attacker);
        }

        HookEvaluator.Hit hit = new HookEvaluator.Hit(attacker, target, source);
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            Optional<ResourceLocation> slotValue = party.slots().get(slot);
            if (slotValue.isEmpty()) {
                continue;
            }
            ResourceLocation characterId = slotValue.get();
            if (ownershipKnown && roster.progressOf(characterId) == null) {
                continue;   // equipped but not owned: a party slot cannot grant a character
            }
            CharacterDef def = registry.get(characterId);
            if (def == null) {
                continue;   // the pack no longer defines it; PartyMultipliers already ranks it neutral
            }
            HookEvaluator.evaluate(def, multipliers[slot], hit, batch);
        }
    }

    private void warnQuarantined(ServerPlayer attacker) {
        if (warnedQuarantine.add(attacker.getUUID())) {
            Astrum.LOGGER.warn("astrum: {} has an unreadable character book (quarantined). Attachment damage "
                    + "keeps running from the party wheel, but character ownership cannot be verified "
                    + "until a build that understands the save loads it.", attacker.getName().getString());
        }
    }

    /** {@return the damage type id of {@code source}, or {@code null} when the holder has no key} */
    private static ResourceLocation damageTypeId(DamageSource source) {
        return source.typeHolder().unwrapKey().map(ResourceKey::location).orElse(null);
    }

    /** One player attacking one target, keyed so two players' damage never merges. */
    private record PendingKey(UUID attacker, UUID target) {
    }

    /** A batch waiting for the end of the tick, with the entities it belongs to. */
    private record Pending(ServerPlayer attacker, LivingEntity target, DamageBatch batch) {
    }
}
