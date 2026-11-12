package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.config.Balance;
import com.astrum.pcrd.impl.config.ServerConfig;
import com.astrum.pcrd.impl.growth.ContributionTracker;
import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import com.astrum.pcrd.impl.growth.KillExperience;
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
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
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

    /**
     * Hits the engine announced as critical, keyed by attacker and target, consumed by that hit's
     * {@code LivingDamageEvent.Post} in the same tick.
     *
     * <p>{@code CriticalHitEvent} fires <b>before</b> {@code hurt()}, so it cannot carry the damage that
     * was actually removed. Remembering the fact and evaluating {@code crit} hooks on the Post lets a
     * crit {@code lifesteal} heal for real damage instead of a predicted one — the same reason
     * {@link #preHitHealth} exists.
     */
    private final Map<PendingKey, Long> landedCrits = new HashMap<>();

    /** Players already warned about a quarantined roster, so the warning is one line, not per hit. */
    private final Set<UUID> warnedQuarantine = new HashSet<>();

    /**
     * The one {@code area_charge} sink of this server session (spec §7.4).
     *
     * <p>Owned here so its pair table and per-receiver windows die with the session: {@link
     * #onServerStopping} resets it together with the other per-session state. It is not a static
     * field, so the guide's "no per-entity state in a static container" rule holds, and it is not
     * rebuilt per hit, which would silently defeat both guards.
     */
    private final GuardedAreaChargeSink areaCharge = new GuardedAreaChargeSink();

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
     * Remembers that the engine called this swing a critical hit, for the {@code Post} that follows in
     * the same tick.
     *
     * <p>Not `isVanillaCritical()`: a mod that grants crits itself sets {@code isCriticalHit} on this
     * very event, and that is a crit the player experienced. Both flags are the engine's word for it.
     */
    @SubscribeEvent
    public void onCriticalHit(CriticalHitEvent event) {
        if (!event.isCriticalHit() || !(event.getEntity() instanceof ServerPlayer attacker)
                || !(event.getTarget() instanceof LivingEntity target) || attacker == target) {
            return;
        }
        landedCrits.put(new PendingKey(attacker.getUUID(), target.getUUID()),
                attacker.level().getGameTime());
        Astrum.LOGGER.debug("astrum: crit announced: {} -> {}", attacker.getName().getString(),
                target.getName().getString());
    }

    /**
     * {@return whether this hit was announced as a critical one in this tick, consuming the fact}
     *
     * <p>Consumed rather than merely read: a crit flag that lingered would turn the next ordinary hit on
     * the same target into a crit too, and a crit hook that pays charge would pay it twice.
     */
    private boolean takeCrit(ServerPlayer attacker, LivingEntity target) {
        Long tick = landedCrits.remove(new PendingKey(attacker.getUUID(), target.getUUID()));
        return tick != null && tick == target.level().getGameTime();
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
        float dealt = 0.0F;

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
            dealt = credited;
        }

        if (!plan.runsHooks()) {
            Astrum.LOGGER.debug("astrum: own damage {} on {} charged but not re-entering hook evaluation "
                    + "(recursion fuse)", typeId, target.getName().getString());
            return;
        }
        if (source.getEntity() instanceof ServerPlayer attacker && attacker != target) {
            // The hit has landed, so the combo chain advances here — once per hit, whatever the wheel
            // contains, because a chain is the attacker's rhythm and not a character's feature.
            int comboHits = TriggerRuntime.noteHit(attacker);
            TriggerFacts facts = TriggerFacts.forHit(
                    source.getDirectEntity() == attacker,
                    source.is(DamageTypeTags.IS_PROJECTILE),
                    takeCrit(attacker, target),
                    comboHits,
                    TriggerFacts.isLowHealth(attacker.getHealth(), attacker.getMaxHealth()));
            Astrum.LOGGER.debug("astrum: hit facts on {}: melee={} projectile={} crit={} combo={} "
                    + "lowHealth={}", target.getName().getString(), facts.meleeHit(),
                    facts.projectileHit(), facts.crit(), facts.comboHits(), facts.lowHealth());
            queue(attacker, target, source, dealt, facts);
        }
    }

    /**
     * A mob died: credit kill charge, and run the killer's {@code kill} hooks.
     *
     * <p>Deliberately <b>not</b> short-circuited on our own damage type: a death is a single event
     * whichever damage type finished the target, and crediting it once cannot recurse because
     * nothing here deals damage. (The fuse exists to protect hook evaluation, not the kill counter.)
     *
     * <p>The kill hooks are evaluated with {@link TriggerFacts#forKill()}, which observes {@code kill}
     * and nothing else: the killing blow already fired its own {@code melee_hit}/{@code crit} hooks
     * when it landed, and replaying them here would pay twice.
     *
     * <p>{@code dealt} is {@code 0} for this path — the target is already dead, so there is no health
     * to attribute. Effects that need real damage ({@code lifesteal}) therefore do nothing on a kill
     * hook while flat ones ({@code gain_charge}, {@code extra_damage}) work normally, and any damage
     * queued against the corpse is dropped by the flush with a debug line.
     */
    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer)) {
            return;
        }
        credit(killer, ServerConfig.UB.ubChargePerKill.get());
        if (event.getEntity() != killer) {
            // The kill's experience is settled here, on the same event that pays kill charge and runs
            // the killer's `kill` hooks: one combat surface, not two. KillExperience consumes the
            // contribution ledger (so the same victim cannot pay twice) and writes the roster.
            KillExperience.settle(killer, event.getEntity());
            queue(killer, event.getEntity(), event.getSource(), 0.0F, TriggerFacts.forKill());
        }
    }

    /**
     * A player blocked with a shield: run that player's {@code block} hooks.
     *
     * <p>The blocker is the actor, so it takes the attacker role the executors expect: {@code lifesteal}
     * heals the blocker, {@code gain_charge} credits the blocker, and the "target" is the entity whose
     * blow was stopped when there is one. {@code dealt} is the blocked damage, which is what makes a
     * block {@code lifesteal} ("heal for a fraction of what you blocked") mean something.
     *
     * <p>Skipped when the damage came from no entity at all (environmental): there is no target to
     * attribute the effects to, and inventing one would be worse than the debug line.
     */
    @SubscribeEvent
    public void onShieldBlock(LivingShieldBlockEvent event) {
        if (!event.getBlocked() || !(event.getEntity() instanceof ServerPlayer blocker)) {
            return;
        }
        if (!(event.getDamageSource().getEntity() instanceof LivingEntity sourceEntity)
                || sourceEntity == blocker) {
            Astrum.LOGGER.debug("astrum: {} blocked damage with no attributable source, block hooks "
                    + "skipped", blocker.getName().getString());
            return;
        }
        queue(blocker, sourceEntity, event.getDamageSource(),
                Math.max(0.0F, event.getBlockedDamage()), TriggerFacts.forBlock());
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
        landedCrits.clear();    // ditto: a crit whose hit never landed must not bless a later one
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

    private void queue(ServerPlayer attacker, LivingEntity target, DamageSource source, float dealt,
            TriggerFacts facts) {
        DamageBatch batch = new DamageBatch();
        collect(attacker, target, source, dealt, facts, batch);
        if (batch.isEmpty()) {
            return;
        }
        merge(new PendingKey(attacker.getUUID(), target.getUUID()),
                new Pending(attacker, target, batch));
    }

    /**
     * Adds a batch to the end-of-tick queue, merging into whatever is already queued for the same
     * (attacker, target) pair.
     *
     * <p>Merging rather than overwriting is what makes "same tick" literal for the whole pipeline, not
     * just for {@code extra_damage}: two hits on one target in one tick, and a splash from a third hit
     * elsewhere, all end in one {@code hurt()} per damage type (spec §5.1 rule 3).
     */
    private void merge(PendingKey key, Pending fresh) {
        Pending already = pending.get(key);
        if (already == null) {
            pending.put(key, fresh);
            return;
        }
        for (DamageBatch.Entry entry : fresh.batch().entries()) {
            already.batch().add(entry.type(), entry.amount());
        }
    }

    /**
     * Queues damage from an effect that hits a <b>different</b> entity — {@code splash} and
     * {@code detonate}'s radius (see {@link DamageToTarget} for why it cannot be a nested
     * {@code hurt()}).
     *
     * <p>Same queue, same flush, same fuse as the hit's own attachment damage: the target of the splash
     * gets one {@code hurt()} per damage type at the end of the tick, and because the damage type is
     * {@code astrum:hook_damage} it will not re-enter hook evaluation.
     */
    private void queueDamage(ServerPlayer attacker, LivingEntity target, ResourceLocation type,
            float amount) {
        if (!(amount > 0.0F) || target.isRemoved() || !target.isAlive()) {
            return;
        }
        DamageBatch batch = new DamageBatch();
        batch.add(type, amount);
        merge(new PendingKey(attacker.getUUID(), target.getUUID()),
                new Pending(attacker, target, batch));
    }

    /** Drops all per-session state when the world goes away, so nothing survives a restart. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        pending.clear();
        preHitHealth.clear();
        landedCrits.clear();
        warnedQuarantine.clear();
        areaCharge.reset();   // pair timestamps and per-second windows belong to one world's clock
        // One world's fights are not the next world's: the contribution ledger is cleared here, with
        // the rest of this class's session state, exactly as DishThrottle is cleared by GrowthEvents.
        ContributionTracker.clear();
        // An operator-tuned guard must not silently govern the next world (spec §8.4): the override
        // is session state, not a persisted value. Task 14's command sets it; this clears it.
        AreaChargeTuning.clearOverride();
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
    private void collect(ServerPlayer attacker, LivingEntity target, DamageSource source, float dealt,
            TriggerFacts facts, DamageBatch batch) {
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

        // The hit, once; each wheel slot derives its own view (multiplier + character attribution).
        // The area_charge sink is constructed in exactly one place, and it is the guarded one.
        HookContext hit = new HookContext(attacker, target, source, dealt, Balance.MAX_MULTIPLIER,
                GrowthMultiplier.NEUTRAL, NO_CHARACTER, batch,
                (other, type, amount) -> queueDamage(attacker, other, type, amount),
                areaCharge);
        // Experience is paid for damage, so only a hit that removed health records a contribution.
        // The kill and block paths reach this method too — a death has no health left to attribute
        // (`dealt` is 0) and a blocked blow removed none — and recording them would both pay for
        // damage nobody dealt and re-create the ledger entry the kill had just consumed.
        boolean contributes = paysExperience(dealt, facts);
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            Optional<ResourceLocation> slotValue = party.slots().get(slot);
            if (slotValue.isEmpty()) {
                continue;
            }
            ResourceLocation characterId = slotValue.get();
            CharacterProgress progress = ownershipKnown ? roster.progressOf(characterId) : null;
            if (ownershipKnown && progress == null) {
                continue;   // equipped but not owned: a party slot cannot grant a character
            }
            CharacterDef def = registry.get(characterId);
            if (def == null) {
                continue;   // the pack no longer defines it; PartyMultipliers already ranks it neutral
            }
            // Growth is resolved once per slot and travels as a value: the level factor and the
            // affection definition/tier. The composition with multipliers[slot] happens per hook, in
            // HookContext.forHook, so there is exactly one chain (see MultiplierChain).
            GrowthMultiplier growth = GrowthMultiplier.of(progress, def);
            HookContext slotContext = hit.forSlot(multipliers[slot], growth, characterId);
            double before = totalOf(batch);
            HookEvaluator.evaluate(def, slotContext, facts);
            // §6.1 splits kill experience by the damage the player dealt, and the character is what
            // levels — so a slot that was on the wheel for this hit contributed the hit itself, plus
            // whatever extra damage its own hooks queued on top. Counting only the extra damage would
            // pay nothing to a support character: the shipped 姬塔's hooks are gain_charge, splash on
            // combo_n and area_charge, so a damage-only ledger would leave her at level 1 forever
            // while she was demonstrably in the fight.
            if (contributes) {
                noteContribution(attacker, target, characterId, dealt + (totalOf(batch) - before));
            }
        }
    }

    /**
     * Records the damage one character contributed to one victim, for the kill payout.
     *
     * <p>Two halves, both damage the player caused and the character was present for: the landed hit
     * the whole wheel rode on ({@code dealt}, already overkill-clamped by
     * {@code LandedHit.healthLost}), and the extra damage that character's own hooks queued for it.
     * The extra part is measured as the batch's growth across one slot's evaluation, because the batch
     * is shared by the whole wheel — a whole-batch total would credit every slot with every other
     * slot's damage.
     *
     * <p>A slot whose hooks queue nothing still contributes the hit, which is why "contributed a
     * little" and "contributed nothing" stay different states: the latter is a slot that was not on
     * the wheel (or a kill with no landed hit at all, e.g. a fall), and it gets no entry.
     *
     * <p>The ledger is in-memory and is consumed by {@link KillExperience#settle} at the death event;
     * this is the only place that fills it, and {@code onServerStopping} clears it.
     */
    private static void noteContribution(ServerPlayer attacker, LivingEntity target,
            ResourceLocation characterId, double damage) {
        if (damage > 0.0) {
            ContributionTracker.note(attacker.getUUID(), target.getUUID(), characterId, damage,
                    attacker.level().getGameTime());
        }
    }

    /** {@return the positive damage a batch currently holds, summed across its damage types} */
    private static double totalOf(DamageBatch batch) {
        double total = 0.0;
        for (DamageBatch.Entry entry : batch.entries()) {
            if (entry.amount() > 0.0F) {
                total += entry.amount();
            }
        }
        return total;
    }

    /**
     * {@return whether this event may record a growth contribution (spec §6.1: 按玩家造成的伤害占比分摊经验)}
     *
     * <p>Only a hit that removed health may. {@link #collect} serves four entry points and three of
     * them must not feed the ledger:
     *
     * <ul>
     *   <li><b>A kill</b> carries {@code dealt = 0} — the target is already dead — and paying for it
     *       would also re-create the pair {@link KillExperience#settle} has just consumed, so a second
     *       death event for the same victim would pay the same fight out twice.</li>
     *   <li><b>A shield block</b> carries the damage <em>prevented</em>, which is not damage dealt.</li>
     *   <li><b>A zero, negative or {@code NaN} hit</b> removed nothing, so there is no share to
     *       attribute (and {@code ContributionTracker.note} would ignore it, but stating the rule once
     *       here is what keeps the two filters from drifting apart).</li>
     * </ul>
     *
     * <p>Pure and package-private so the rule is asserted without a server; the three branches are
     * exactly the three {@link TriggerFacts} families {@link #collect} is called with.
     *
     * @param dealt what the event actually removed
     * @param facts what just happened; only the kill and block families are excluded
     */
    static boolean paysExperience(float dealt, TriggerFacts facts) {
        if (facts == null || facts.kill() || facts.block()) {
            return false;
        }
        return dealt > 0.0F && Float.isFinite(dealt);
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
