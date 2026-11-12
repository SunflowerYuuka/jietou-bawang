package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.ub.ChargeKernel;
import com.astrum.pcrd.impl.ub.UbCharge;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.ApiStatus;

/**
 * The attachment entry point: one place where a landed hit is turned into extra damage and UB
 * charge (spec §5.1). Every rule in this class is one of the three the spec calls non-negotiable.
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
 *       ({@code setNewDamage})</td><td>no</td><td>after armour/effects, before absorption</td></tr>
 *   <tr><td>{@code LivingDamageEvent.Post}</td><td>final health loss, 4.920</td><td>no (immutable
 *       copy)</td><td>no</td><td>after health is subtracted — and only when the sequence got that
 *       far</td></tr>
 * </table>
 *
 * <p>{@code LivingDamageEvent} itself is an abstract base class with a private constructor: there
 * is no such event to listen to, only {@code Pre} and {@code Post}.
 *
 * <p><b>Why Post.</b> Rule 1 forbids touching the amount, so the writability of the other two
 * stages is a liability rather than a feature: rewriting {@code amount} is exactly how the spec's
 * "armour/crit re-scaling" failure happens, and the run measured it — asking for 7.0 through
 * {@code setAmount} landed as 6.888 after the armour step. Post has nothing to rewrite, reports the
 * health that was actually lost (the number charge must be based on), and fires only for hits that
 * dealt damage, so the attachment pipeline never runs for a hit the iframe check ate.
 *
 * <h2>Rule 2 — the recursion fuse</h2>
 * The first statement of both damage handlers is {@link OwnDamage#isOurs}: damage in the
 * {@code astrum} namespace is ours, and ours must not come back through here. Without it the extra
 * damage would trigger the attachment logic which would deal more extra damage, forever.
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

    /** The one damage type attachment damage uses (spec §5.1 rule 3). */
    private static final ResourceLocation HOOK_DAMAGE = InternalIds.id("hook_damage");

    /** Batches waiting for the end of this tick, keyed by attacker and target. */
    private final Map<PendingKey, Pending> pending = new LinkedHashMap<>();

    /** Players already warned about a quarantined roster, so the warning is one line, not per hit. */
    private final Set<UUID> warnedQuarantine = new java.util.HashSet<>();

    private CombatEvents() {
    }

    /** Subscribes this listener to the game bus. Called once, from the mod constructor. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new CombatEvents());
    }

    // ------------------------------------------------------------------ damage landing

    /**
     * A hit landed: credit charge for it, and queue the attachment damage it earned.
     *
     * <p>Order matters. The fuse comes first, so our own merged damage neither charges the gauge a
     * second time nor grows another batch.
     */
    @SubscribeEvent
    public void onDamageLanded(LivingDamageEvent.Post event) {
        DamageSource source = event.getSource();
        ResourceLocation typeId = damageTypeId(source);
        if (OwnDamage.isOurs(typeId)) {
            Astrum.LOGGER.debug("astrum: own damage {} on {} skipped by the attachment entry (recursion fuse)",
                    typeId, event.getEntity().getName().getString());
            return;
        }

        float dealt = event.getNewDamage();
        if (dealt <= 0.0F) {
            return;   // nothing was lost: nothing to charge, nothing to attach
        }
        LivingEntity target = event.getEntity();

        if (target instanceof ServerPlayer victim) {
            credit(victim, ChargeKernel.addTaken(ChargeKernel.MIN_CHARGE, dealt,
                    ServerConfig.UB.ubChargePerDamageTaken.get()));
        }

        Entity attackerEntity = source.getEntity();
        if (attackerEntity instanceof ServerPlayer attacker && attacker != target) {
            credit(attacker, ChargeKernel.addDealt(ChargeKernel.MIN_CHARGE, dealt,
                    ServerConfig.UB.ubChargePerDamage.get()));
            queue(attacker, target, source);
        }
    }

    /**
     * A mob died. Kill charge is credited to the player the game credits the kill to.
     *
     * <p>Deliberately <b>not</b> short-circuited on our own damage type: a death is a single event
     * whichever damage type finished the target, and crediting it once cannot recurse because
     * nothing here deals damage. (The entry point's fuse exists to protect the attachment
     * pipeline, not the kill counter.)
     */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            credit(killer, ChargeKernel.addKill(ChargeKernel.MIN_CHARGE,
                    ServerConfig.UB.ubChargePerKill.get()));
        }
    }

    /** Posts the charge change through {@link UbCharge}, which owns the write. */
    private static void credit(ServerPlayer player, double delta) {
        if (delta > 0.0) {
            UbCharge.apply(player, NO_CHARACTER, delta);
        }
    }

    // ------------------------------------------------------------------ the flush

    /**
     * Drains the batches queued during this tick, one {@code hurt()} per damage type.
     *
     * <p>Runs after everything else in the tick has had its chance to queue, which is what makes
     * the merge a same-tick merge rather than a same-event one.
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (pending.isEmpty()) {
            return;
        }
        List<Pending> ready = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending entry : ready) {
            flush(entry);
        }
    }

    private void flush(Pending pending) {
        LivingEntity target = pending.target();
        if (target.isRemoved() || !target.isAlive()) {
            Astrum.LOGGER.debug("astrum: extra damage for {} dropped, target is gone", target.getName().getString());
            return;
        }
        Registry<DamageType> damageTypes = target.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        for (DamageBatch.Entry entry : pending.batch().entries()) {
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
            DamageSource applied = new DamageSource(holder, pending.attacker(), pending.attacker());
            boolean landed = target.hurt(applied, entry.amount());
            Astrum.LOGGER.debug("astrum: extra {} damage {} on {} landed={} hp={}", entry.type(),
                    entry.amount(), target.getName().getString(), landed, target.getHealth());
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

    // ------------------------------------------------------------------ hook evaluation

    /**
     * Fills {@code batch} from one player's wheel: {@code PARTY} × the character definitions, each
     * {@code extra_damage} hook scaled by its slot's diminishing multiplier.
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
            evaluate(def, multipliers[slot], attacker, target, source, batch);
        }
    }

    /**
     * Runs one character's hooks for one hit.
     *
     * <p>Spec §5.3: "each character, each trigger kind, at most once per attack". The dedupe lives
     * here rather than per effect type, because the rule is about the event being counted twice —
     * a character listing two {@code melee_hit} hooks must not double-dip.
     */
    private void evaluate(CharacterDef def, double multiplier, ServerPlayer attacker, LivingEntity target,
            DamageSource source, DamageBatch batch) {
        Set<Trigger> fired = EnumSet.noneOf(Trigger.class);
        for (EffectHook hook : def.effects()) {
            if (fired.contains(hook.trigger())) {
                continue;
            }
            if (!observes(hook.trigger(), source, attacker)) {
                continue;
            }
            if (!roll(hook.chance(), attacker)) {
                continue;
            }
            fired.add(hook.trigger());
            dispatch(hook, multiplier, target, batch);
        }
    }

    /**
     * The effect seam. Task 9 implements {@code extra_damage} only; the other seven effect types
     * are Task 10's, and say so at DEBUG rather than silently doing nothing.
     *
     * <p>Exhaustive on purpose (no {@code default}): a ninth {@link EffectType} stops compiling
     * here, which is the reminder to give it an executor. Task 10 widens this signature when its
     * executors need more of the hit — {@code lifesteal} and {@code gain_charge} need the attacker,
     * {@code splash} the radius around the target — rather than guessing the parameter list now.
     *
     * @param multiplier this slot's diminishing multiplier; it scales <b>quantities only</b>
     *                   (spec §5.2) — never whether the effect runs
     */
    private void dispatch(EffectHook hook, double multiplier, LivingEntity target, DamageBatch batch) {
        switch (hook.type()) {
            case EXTRA_DAMAGE -> {
                EffectHook.ExtraDamage extra = (EffectHook.ExtraDamage) hook;
                batch.add(HOOK_DAMAGE, (float) (extra.amount() * multiplier));
            }
            case APPLY_EFFECT, LIFESTEAL, GAIN_CHARGE, AREA_CHARGE, SPLASH, STACK, DETONATE ->
                    Astrum.LOGGER.debug("astrum: effect {} is not implemented yet (Task 10); hook on {} skipped",
                            hook.type(), target.getName().getString());
        }
    }

    /**
     * {@return whether this damage sequence can be the trigger a hook waits for}
     *
     * <p>Only the triggers a landed damage event can observe are implemented. The rest belong to
     * other sources — {@code kill} to the death path, {@code block} to the shield path, {@code
     * combo_n}/{@code stack_full} to counters Task 10 keeps — and answering {@code false} is the
     * honest answer here rather than pretending they fired.
     */
    private static boolean observes(Trigger trigger, DamageSource source, ServerPlayer attacker) {
        return switch (trigger) {
            case MELEE_HIT -> source.getDirectEntity() == attacker;
            case PROJECTILE_HIT -> source.is(DamageTypeTags.IS_PROJECTILE);
            case CRIT, KILL, LOW_HEALTH, COMBO_N, BLOCK, STACK_FULL -> false;
        };
    }

    /** {@return the chance roll for one hook, in {@code [0, 1]}} */
    private static boolean roll(double chance, ServerPlayer attacker) {
        if (chance >= 1.0) {
            return true;
        }
        if (chance <= 0.0) {
            return false;
        }
        return attacker.getRandom().nextFloat() < chance;
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
