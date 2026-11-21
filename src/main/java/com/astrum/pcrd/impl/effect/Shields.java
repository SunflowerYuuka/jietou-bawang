package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.ShieldKind;
import com.astrum.pcrd.impl.attach.ShieldState;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Granting <b>our</b> shield: the one place a {@code SHIELD} tier becomes absorption somebody carries.
 *
 * <h2>The three steps, and why the order matters</h2>
 * <ol>
 *   <li><b>add to our pool</b> ({@link ShieldState#granted}) — the layers are the truth; nothing here writes the
 *       vanilla float without first knowing how much of it is ours;</li>
 *   <li><b>apply our {@code astrum:shield} effect</b>, whose duration is the shield's lifetime and whose
 *       {@code MAX_ABSORPTION} modifier is what stops vanilla's expiry from clamping our part away;</li>
 *   <li><b>re-mirror the float</b> to {@code ours + everybody else's} ({@link #reflect}), which is the step
 *       that makes every source <em>add</em> rather than replace.</li>
 * </ol>
 *
 * <h2>「覆盖只会覆盖自身」</h2>
 * Step 3 used to write {@code ours + vanillaShareOf(entity)} — the golden apple was the only other source it
 * could name, so any other mod's absorption was replaced by our number on the next mirror. What a grant may
 * replace is <b>our share</b> and nothing else, so the write is now {@code ours + everyone else's}, where
 * everyone else's is read off the float itself (see {@link ShieldPool#foreignShare}). A source this code has
 * never heard of is therefore preserved by construction rather than by being enumerated.
 *
 * <h2>The two channels, and the one thing vanilla cannot do for them</h2>
 * 「物理护盾和魔法护盾…做一下2个单独的通道。」 A grant now names a {@link ShieldKind} — the channel it fills —
 * and the ledger holds one layer per kind, so 由衣's physical-only barrier and 望's shared one coexist. The
 * <em>kind</em> is a property of the effect type ({@code SHIELD} = both, {@code SHIELD_PHYS} = physical,
 * {@code SHIELD_MAGIC} = magic), which is why the three are three vanilla instances and never merge.
 *
 * <p>Vanilla's absorption spends the whole float on <b>any</b> damage, so the split cannot be left to it:
 * {@link #parkForTypedDamage} is called from {@code LivingDamageEvent.Pre}, takes the layers a hit cannot use
 * <b>out</b> of the float for that hit, and lets vanilla spend only the covering ones plus everybody else's
 * share ({@code ShieldState.parkedFor}). The next tick's mirror puts the parked layers back, and the ledger
 * derives what the hit really cost from the float itself — no estimate, no second classifier
 * ({@link DamageReduction#isMagic} is the project's only one).
 *
 * <h2>The receiver rule is {@code AllySelector}'s, not a sixth copy</h2>
 * 「对自身展开…护盾」 is a self shield, and {@code radius: 0} means exactly that — the same reading
 * {@code AttackBuffs.radiusFor} gives it. A pack that writes no radius gets the documented party default,
 * so a future "shield the whole party" skill is data rather than code. Either way the <em>set</em> comes
 * from the one ally kernel through {@link AllyCandidates}, so this class never decides who is an ally.
 *
 * <h2>Never throws</h2>
 * Every receiver is wrapped: this runs from a damage sequence and from a cast path where the gauge has
 * already been spent, so one body's failure must not cost the others or the rest of the skill.
 */
@ApiStatus.Internal
public final class Shields {

    /** Registry path of the effect: {@code astrum:shield}. */
    public static final String PATH = "shield";

    /** The effect's registry id, for log lines and for the client-side sprite lookup. */
    public static final ResourceLocation ID = com.astrum.pcrd.impl.InternalIds.id(PATH);

    /** The translation key vanilla resolves for the effect's name, {@code effect.astrum.shield}. */
    public static final String NAME_KEY = "effect." + ID.toLanguageKey();

    /** How long a shield lasts when the hook names no clock, in seconds. The bwiki says 8 for ペコリーヌ. */
    public static final double DEFAULT_SECONDS = 8.0;

    private Shields() {
    }

    /** {@return the registered effect holder, or empty when the registry is not up yet} */
    public static Optional<Holder<MobEffect>> holder() {
        return PartyEffects.shieldHolder();
    }

    /**
     * Grants {@code amount} absorption to the caster and her party inside {@code radius}.
     *
     * @param caster      the player whose hook or ultimate asked
     * @param kind        which channel the grant fills — {@code SHIELD} / {@code SHIELD_PHYS} / {@code SHIELD_MAGIC}
     * @param amount      absorption to add, in health points (already the tier's fraction of max health)
     * @param seconds     how long it lasts; a non-positive value falls back to {@link #DEFAULT_SECONDS}
     * @param characterId the character that asked, for the log line
     * @param radius      the reach; {@code 0} is self-only, empty is the party default
     * @return how many receivers gained shield
     */
    public static int apply(LivingEntity caster, ShieldKind kind, float amount, double seconds,
            ResourceLocation characterId, OptionalDouble radius) {
        if (caster == null) {
            return 0;
        }
        if (!ShieldPool.isGrantable(amount)) {
            Astrum.LOGGER.debug("astrum: shield of {} computed {}, which is not a grantable amount for {}; "
                    + "nothing was applied", characterId, amount, name(caster));
            return 0;
        }
        Optional<Holder<MobEffect>> effect = holder();
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: astrum:shield is not registered yet, so the shield of {} was not "
                    + "applied; the mob effect registry missed {}.", characterId, ID);
            return 0;
        }
        int ticks = secondsToTicks(seconds);
        if (ticks <= 0) {
            Astrum.LOGGER.warn("astrum: shield of {} wants {} seconds, so there is nothing to apply; "
                    + "hook skipped.", characterId, seconds);
            return 0;
        }
        double reach = AreaBuffs.radiusFor(radius);
        if (!(caster instanceof ServerPlayer player)) {
            // A non-player caster is not a party: the shield is on the entity that asked for it, the same
            // degradation AttackBuffs and Heals document.
            return grant(caster, effect.get(), kind, amount, ticks, characterId) ? 1 : 0;
        }
        AllyCandidates.Pool pool = AllyCandidates.around(player);
        int granted = 0;
        for (AllySelector.Candidate candidate : AllySelector.select(pool.candidates(), player.getUUID(),
                AllyCandidates.configuredMode(), reach, true)) {
            ServerPlayer receiver = pool.players().get(candidate.id());
            if (receiver != null && grant(receiver, effect.get(), kind, amount, ticks, characterId)) {
                granted++;
            }
        }
        Astrum.LOGGER.debug("astrum: shield {} ({}) for {}s of {} reached {} of {} player(s) within {} blocks",
                amount, kind, ticks / 20.0, characterId, granted, pool.size(), reach);
        return granted;
    }

    /**
     * Grants the shield to <b>one</b> body: pool, effect, mirror.
     *
     * <p>Public so a server probe and a test can drive one receiver without a party. Never throws.
     *
     * @param receiver    the body that gains the shield
     * @param effect      the registered {@code astrum:shield} holder
     * @param kind        which channel the grant fills
     * @param amount      absorption to add
     * @param ticks       duration in ticks
     * @param characterId the character that asked, for the log line
     * @return whether the receiver holds more absorption afterwards
     */
    public static boolean grant(LivingEntity receiver, Holder<MobEffect> effect, ShieldKind kind, float amount,
            int ticks, ResourceLocation characterId) {
        if (receiver == null || !receiver.isAlive() || receiver.isRemoved()) {
            return false;
        }
        try {
            long now = receiver.level().getGameTime();
            float floatBefore = ShieldPool.absorptionOf(receiver);
            float vanillaBefore = ShieldPool.vanillaShareOf(receiver);
            ShieldState before = stateAt(receiver, now);
            ShieldState after = before.granted(kind, amount, now, ticks);
            receiver.setData(Attachments.SHIELD_POOL, after);
            // One registered type for all three kinds, deliberately: the duration of the instance is only the
            // <em>envelope</em> of our layers (it is the longest grant), while each layer's deadline is in the
            // ledger. That is what lets 由衣's 8 s physical barrier and 望's 19.5 s shared one coexist on one
            // body without either one's clock being overwritten — and the HUD still shows one 護盾 row, which
            // is what a barrier is.
            receiver.addEffect(new MobEffectInstance(effect, ticks, 0, false, true, true));
            float theirs = before.foreignShare(floatBefore, vanillaBefore);
            float mirrored = reflect(receiver, after);
            Astrum.LOGGER.debug("astrum: shield of {} ({}) on {} -> ours {} + others {} = {} absorption "
                            + "({}t, was {})", characterId, kind, name(receiver), after.total(), theirs, mirrored,
                    ticks, floatBefore);
            return mirrored > floatBefore;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: shield of {} threw on {}; the other receivers still run",
                    characterId, name(receiver), e);
            return false;
        }
    }

    /**
     * Writes the entity's absorption float so it shows {@code our pool + everybody else's share}.
     *
     * <p>This is the method that makes stacking true rather than claimed: called after a grant and from the
     * effect's own tick, so vanilla's own {@code max} write is repaired on the very next tick.
     *
     * <p><b>It only ever replaces our own share.</b> The other side is read from the float — the part of it that
     * our ledger does not attribute to us — so a golden apple, a second mod's absorption, or the two of them at
     * once are all preserved without this class knowing what they are. The three numbers the ledger needs for
     * that are {@code lastWritten} (what rise counts from), {@code foreign} (the other side so far) and
     * {@code vanillaSeen} (whose apple was already counted).
     *
     * @param entity the body to re-mirror
     * @param state  its shield pool, already reconciled
     * @return the absorption the entity now has
     */
    public static float reflect(LivingEntity entity, ShieldState state) {
        if (entity == null || state == null) {
            return 0.0F;
        }
        float observed = ShieldPool.absorptionOf(entity);
        float vanilla = ShieldPool.vanillaShareOf(entity);
        float theirs = state.foreignShare(observed, vanilla);
        float wanted = state.totalWith(theirs);
        if (wanted != observed) {
            entity.setAbsorptionAmount(wanted);
        }
        // Remember what we wrote and who else owns what, so the next read can measure damage as a difference
        // and can add a foreign grant without mistaking our own share for one. Without this the pool cannot
        // tell 「the apple clobbered the float」 from 「damage took our shield」 — the bug the dedicated-server
        // probe measured as FAIL our pool survived vanilla's max() write: 0.0 — and cannot tell 「another mod
        // added six」 from 「our shield is six bigger」, which is the clobbering this version removes.
        entity.setData(Attachments.SHIELD_POOL, state.written(wanted, theirs, vanilla));
        return wanted;
    }

    /**
     * {@return {@code entity}'s shield pool as of now}, reconciled against its absorption and its clock
     *
     * <h2>Two reasons a stored pool can be stale, and both are handled here</h2>
     * <ol>
     *   <li><b>The clock</b> — {@link ShieldState#reconciledAgainst} answers {@link ShieldState#NONE} past the
     *       deadline, so a pool cannot outlive the duration it was granted for.</li>
     *   <li><b>The effect is gone</b> — {@code /effect clear}, milk, a totem, or an earlier expiry all remove
     *       our instance without a hook of ours running. This asks the registry, so a pool is only ever
     *       honoured while the effect that granted it is actually on the entity. That is what stops a stale
     *       pool from silently eating a later golden apple: a stored pool of 10 reconciled against the apple's
     *       float of 4 would claim all four of its hearts as its own.</li>
     * </ol>
     * Both checks are on the read path rather than driven by an expiry event, so there is no listener to
     * forget and no ordering to get wrong.
     *
     * @param entity the body to read
     * @param now    the current game time, in level ticks
     */
    public static ShieldState stateAt(LivingEntity entity, long now) {
        if (entity == null) {
            return ShieldState.NONE;
        }
        ShieldState stored = entity.getData(Attachments.SHIELD_POOL);
        if (stored == null || !stored.isPresent()) {
            return ShieldState.NONE;
        }
        Optional<Holder<MobEffect>> effect = holder();
        if (effect.isPresent() && !entity.hasEffect(effect.get())) {
            // The granting instance is gone; whatever absorption is there now is vanilla's.
            return ShieldState.NONE;
        }
        return stored.reconciledAgainst(ShieldPool.absorptionOf(entity), now);
    }

    /** {@return {@code entity}'s shield pool, using its level's clock} */
    public static ShieldState stateOf(LivingEntity entity) {
        return entity == null ? ShieldState.NONE : stateAt(entity, entity.level().getGameTime());
    }

    /** {@return the channel an effect type grants: {@code SHIELD} is both, the other two are one half each} */
    public static ShieldKind kindOf(@Nullable EffectType type) {
        return ShieldKind.of(type);
    }

    /**
     * <b>The typed half of the shield: for one hit, the layers it cannot use come out of the float.</b>
     *
     * <h2>Why the damage path has to be involved at all</h2>
     * Vanilla spends absorption on <b>any</b> damage — {@code LivingEntity.actuallyHurt} does
     * {@code amount = max(0, amount - getAbsorptionAmount())} before health, with no notion of a damage type.
     * A physical-only barrier mirrored into that float would therefore absorb the first magic bolt that landed,
     * which is precisely what 由衣's 「物理伤害的护盾」 must not do. There is no vanilla mechanism to restrict it,
     * and the human asked for two separate channels in as many words (「做一下2个单独的通道」).
     *
     * <h2>What this does, in one paragraph</h2>
     * Called from {@code LivingDamageEvent.Pre} — post-armour, pre-absorption, on the server — it asks the
     * classifier ({@link DamageReduction#isMagic}, the project's one answer to 「is this magic」) which half the
     * hit is, and writes the float as the absorption the <b>covering</b> layers hold plus everybody else's
     * share. Vanilla then spends exactly those, so a magic bolt finds a physical barrier's points nowhere in
     * the float and reaches health. The ledger records which kinds are inside the float
     * ({@code ShieldState.parkedFor}), and the next read puts the others back — see
     * {@link ShieldState#reconciledAgainst} for the arithmetic that recovers what the hit really cost.
     *
     * <p>When nothing needs parking — a shared barrier, the ordinary case — this writes nothing at all, so the
     * shipped behaviour is untouched: no extra packet to the client, no extra float write.
     *
     * <p>Never throws: it runs inside a damage sequence, where an exception would cost the rest of the hit.
     *
     * @param entity the body being hit, possibly {@code null}
     * @param source the damage being applied, possibly {@code null} (read as physical, like the classifier)
     */
    public static void parkForTypedDamage(@Nullable LivingEntity entity, @Nullable DamageSource source) {
        if (entity == null || entity.level().isClientSide) {
            return;
        }
        try {
            ShieldState state = stateOf(entity);
            if (!state.isPresent()) {
                return;
            }
            boolean magic = DamageReduction.isMagic(source);
            if (!state.parksFor(magic)) {
                return;   // every layer covers this hit: vanilla's own absorption is already correct
            }
            float observed = ShieldPool.absorptionOf(entity);
            float vanilla = ShieldPool.vanillaShareOf(entity);
            float theirs = state.foreignShare(observed, vanilla);
            float visible = state.coveredAmount(magic) + theirs;
            if (visible != observed) {
                entity.setAbsorptionAmount(visible);
            }
            entity.setData(Attachments.SHIELD_POOL, state.parkedFor(magic, visible, theirs, vanilla));
            Astrum.LOGGER.debug("astrum: {} hit on {} -> only the covering barrier is in the float "
                            + "({} of {} absorption, others {})", magic ? "magic" : "physical", name(entity),
                    state.coveredAmount(magic), state.total(), theirs);
        } catch (RuntimeException e) {
            Astrum.LOGGER.warn("astrum: could not park the typed shield for a hit on {}; the hit lands "
                    + "against the ordinary absorption", entity.getName().getString(), e);
        }
    }

    /**
     * Clears our pool and takes <b>only our share</b> back out of the float.
     *
     * <p>Called when the effect ends, so a shield that expires leaves exactly the absorption the player would
     * have had without it — never a silent extra, and never a silent <em>loss</em> either. The old version wrote
     * {@code min(observed, vanillaShare)}, which dropped every point that was neither ours nor the golden
     * apple's: another mod's absorption was destroyed the moment our shield ended. The remainder is now the
     * ledger's own answer for 「what of this float is everybody else's」.
     *
     * <p>Deliberately reads the stored ledger directly instead of {@link #stateAt}: the two hooks that call this
     * ({@code MobEffectEvent.Expired} and {@code .Remove}) fire while the instance is still on the entity, and
     * {@code stateAt}'s deadline check would answer {@link ShieldState#NONE} on the very expiry this exists to
     * clean up. The caller has already established whose effect is ending.
     *
     * @param entity the body whose shield ended
     */
    public static void clear(LivingEntity entity) {
        if (entity == null) {
            return;
        }
        try {
            ShieldState stored = entity.getData(Attachments.SHIELD_POOL);
            if (stored == null || !stored.isPresent()) {
                return;
            }
            long now = entity.level().getGameTime();
            float observed = ShieldPool.absorptionOf(entity);
            float vanilla = ShieldPool.vanillaShareOf(entity);
            ShieldState state = stored.reconciledAgainst(observed, now);
            float ours = state.total();
            float theirs = state.foreignShare(observed, vanilla);
            entity.setData(Attachments.SHIELD_POOL, ShieldState.NONE);
            entity.setAbsorptionAmount(ShieldPool.total(0.0F, theirs));
            Astrum.LOGGER.debug("astrum: shield on {} expired -> absorption {} -> {} (our {} released, "
                    + "everyone else's {} kept)", name(entity), observed, theirs, ours, theirs);
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: clearing the shield on {} threw", name(entity), e);
        }
    }

    /**
     * Grants a <b>fraction of each receiver's own max health</b> as shield — the tiered form.
     *
     * <h2>Why the receiver set is walked here rather than in a helper that takes one amount</h2>
     * A shield's size is per-receiver: 20% of a 20-health player is 4 and 20% of a 3377-health boss is 675.
     * {@code TieredEffects.applyTimed} takes one number for the whole party, so it cannot express this —
     * the same reason {@code HEAL_AREA} goes through {@code PercentHeals} rather than {@code Heals}. The
     * <em>set</em> still comes from the one ally kernel, so this class never decides who an ally is.
     *
     * @param caster      the player whose hook or ultimate asked
     * @param kind        which channel the grant fills
     * @param fraction    the share of each receiver's max health to grant, e.g. {@code 0.4} for tier 2
     * @param params      the tier block: the clock and the reach
     * @param characterId the character that asked, for the log line
     * @return how many receivers gained shield
     */
    public static int applyFraction(LivingEntity caster, ShieldKind kind, double fraction, TieredParams params,
            ResourceLocation characterId) {
        if (caster == null || !(fraction > 0.0) || !Double.isFinite(fraction)) {
            return 0;
        }
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        Optional<Holder<MobEffect>> effect = holder();
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: astrum:shield is not registered yet, so the shield of {} was not "
                    + "applied", characterId);
            return 0;
        }
        // One reading of the three-way rule (absent / declared / nonsense), shared with ticksFor so a caller
        // asking "how long does this barrier last" cannot get a different answer from the one granted.
        int ticks = ticksFor(tiered);
        if (ticks <= 0) {
            return 0;
        }
        double reach = AreaBuffs.radiusFor(tiered.radius());
        if (!(caster instanceof ServerPlayer player)) {
            return grant(caster, effect.get(), kind, (float) (caster.getMaxHealth() * fraction), ticks,
                    characterId) ? 1 : 0;
        }
        AllyCandidates.Pool pool = AllyCandidates.around(player);
        int granted = 0;
        for (AllySelector.Candidate candidate : AllySelector.select(pool.candidates(), player.getUUID(),
                AllyCandidates.configuredMode(), reach, true)) {
            ServerPlayer receiver = pool.players().get(candidate.id());
            if (receiver == null) {
                continue;
            }
            float amount = (float) (receiver.getMaxHealth() * fraction);
            if (grant(receiver, effect.get(), kind, amount, ticks, characterId)) {
                granted++;
            }
        }
        return granted;
    }

    /** {@return the duration in ticks, floored at 0; a non-positive declaration is the default} */
    static int secondsToTicks(double seconds) {
        double usable = seconds > 0.0 && Double.isFinite(seconds) ? seconds : DEFAULT_SECONDS;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(usable * 20.0));
    }

    /**
     * {@return the duration in ticks a {@link TieredParams} asks a barrier for}
     *
     * <h2>Why this is a method and not a call to {@link #secondsToTicks}</h2>
     * The two differ on one case and it is the case that matters: {@code secondsToTicks} sees only a number and
     * cannot tell an absent clock from a declared zero, while this one sees the {@link OptionalDouble} and
     * knows. Its answer is therefore the duration {@link #applyFraction} will actually use, which is what a
     * caller reporting "how long does this barrier last" has to have — the skill-entry family
     * ({@code SkillEntries}) asks it for exactly that, and a second reading of the same three-way rule would be
     * a second chance to disagree with the barrier that was really granted.
     *
     * @param params the hook's tier block; {@code null} means the same as absent seconds
     */
    public static int ticksFor(TieredParams params) {
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        double seconds = tiered.seconds().isPresent() ? tiered.seconds().getAsDouble() : DEFAULT_SECONDS;
        return AttackBuffRules.ticksFor(seconds);
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
