package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbSegment;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.LandedHit;
import com.astrum.pcrd.impl.combat.WarnOnce;
import com.astrum.pcrd.impl.effect.AreaBuffs;
import com.astrum.pcrd.impl.effect.ArmourPierce;
import com.astrum.pcrd.impl.effect.Counters;
import com.astrum.pcrd.impl.effect.LostHealth;
import com.astrum.pcrd.impl.effect.Crit;
import com.astrum.pcrd.impl.effect.CritDamageUps;
import com.astrum.pcrd.impl.effect.CritDowns;
import com.astrum.pcrd.impl.effect.MagicAttack;
import com.astrum.pcrd.impl.effect.PartyVanillaEffects;
import com.astrum.pcrd.impl.effect.CritChanceUps;
import com.astrum.pcrd.impl.effect.OverTimeEffects;
import com.astrum.pcrd.impl.effect.TieredEffects;
import com.astrum.pcrd.impl.growth.ContributionTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
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
import org.jetbrains.annotations.Nullable;

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
 * entity is the caster, of type {@link UbDamage#DAMAGE_TYPE} — or the tier's own {@code damageType} when
 * it names one (ｷｬﾙ's グリムバースト is 「敵全体に<em>魔法</em>中ダメージ」, so her tier declares
 * {@code astrum:magic}). That single choice makes three existing paths fire without touching them:
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
 * <h2>The effects: {@code UbEffect}, applied through the generic kernel</h2>
 * A tier's {@link UbTier#effects()} is a list of {@link UbEffect}: a {@link UbEffect.Scope}, a generic
 * {@link EffectType} and a tier. This class only decides <b>which set of bodies</b> each scope means and
 * <b>when</b> it is applied; every number, rung and amplifier is {@code TieredEffects}' — the same code
 * the hook path runs, which is what makes "a tier means one thing" true across both paths.
 *
 * <ul>
 *   <li><b>{@code ALLY}</b> effects are settled <b>before</b> the damage, because they are buffs and
 *       heals the receivers keep: they must be live for whatever follows the cast, and a cast that hits
 *       nothing still buffs — which is what a healer's ultimate doing no damage yet restoring the party
 *       means.</li>
 *   <li><b>{@code VICTIM}</b> effects are settled <b>inside</b> the hit loop, gated on {@code hurt()}
 *       answering {@code true} — the same gate the burst ledger uses. An ultimate a target dodged, was
 *       immune to, or that walked out of reach therefore leaves no debuff behind.</li>
 * </ul>
 *
 * <h2>Clauses: one damage type and one crit multiplier per clause, one effect list per clause</h2>
 * A tier's damage is a list of {@link com.astrum.pcrd.api.v1.UbSegment}s ({@code UbTier.damageSegments()}), and the
 * loop below is one loop over the plan's applications either way — a tier that declares no clauses arrives here as
 * one implicit clause, so nothing about the shipped characters' settlement changed. What the clauses add is
 * <b>per-clause facts</b>: the damage type a hit is filed under and the crit multiplier it uses are read off the
 * clause rather than the tier, and a clause's own {@code effects} are applied to the bodies <em>that clause</em>
 * selected, on the same "the hit really landed" gate the tier-level list has always used.
 *
 * <p>And one shape is new: a clause that deals <b>no</b> damage at all. 「将最远的敌人拉近 300」 selects a body and
 * moves it; there is no damage number in the source to apply, so it contributes no {@code Hit} and its effects are
 * applied to its own target list after the damage loop — a body that died to the damage half of the same ultimate
 * is not a body to move.
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
 * No multiplier chain, and no second copy of a tier's arithmetic. See {@link UbDamage}'s javadoc for why
 * the tier's {@code damage} is applied verbatim and why that is the correct reading of spec §5.4/§6.2
 * rather than an omission — and note that the same §5.4 exclusion is why {@code TieredEffects} is called
 * with a magnitude multiplier of {@code 1.0} here, while a hook passes its own composed one.
 * {@code teamChargeBonus} is still unimplemented, and is left visible in the schema rather than silently
 * dropped.
 */
@ApiStatus.Internal
public final class UbDamageSettlement {

    /**
     * Mismatches between a scope and its effect, already reported — once per (character, tier, effect).
     *
     * <p>Keyed by what is wrong rather than by who noticed it, so the set is bounded by the loaded
     * datapack: an operator sees one line per malformed clause, not one per cast.
     */
    private static final WarnOnce REPORTED = new WarnOnce();

    /**
     * The magnitude multiplier the effects of an ultimate are applied with.
     *
     * <p>{@code 1.0}, and it is a decision rather than a placeholder: spec §5.4 makes the ultimate exempt
     * from diminishing returns (一次只能放一个，天然互斥), so the per-slot factor has no meaning on this
     * path, and {@code UbDamage}'s javadoc gives the same reasoning for the damage itself. The value is
     * named so the call site says "no scaling, on purpose" instead of looking like an omission.
     */
    public static final double NEUTRAL_MULTIPLIER = 1.0;

    private UbDamageSettlement() {
    }

    /**
     * Applies one cast's damage and effects.
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
     * @param enhanced    whether that tier is the 6★ one — which of {@code ub.landHooks} fires, exactly as it
     *                    decides which {@code UbTier} fired
     * @param landed      where to record every body a hit actually landed on; {@code null} discards
     *                    them, which is the honest answer for a caller with no visual to draw
     */
    public static void apply(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier, boolean enhanced, UbBurstVictims.Ledger landed) {
        if (level == null || caster == null || tier == null || characterId == null) {
            return;
        }
        try {
            applyGuarded(level, caster, characterId, def, tier, enhanced, landed);
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
     * @param enhanced    whether that tier is the 6★ one, selecting {@code ub.landHooks}' rung
     * @param landed      where the settlement records the victims it actually hit; the particle
     *                    burst reads it, so the two cannot disagree about who was struck
     */
    public static Runnable once(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier, boolean enhanced, UbBurstVictims.Ledger landed) {
        return once(() -> apply(level, caster, characterId, def, tier, enhanced, landed));
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
            ResourceLocation characterId, CharacterDef def, UbTier tier, boolean enhanced,
            UbBurstVictims.Ledger landed) {
        // The caster's level, resolved once per cast: the same number a swing's level band uses
        // (LevelBands.bonusAt), so a level-95 character's ultimate reaches its effects one rung higher than a
        // level-1 character's would. Read from the roster here rather than passed in, because every way an
        // ultimate is settled (the cast itself, the showcase's strike frame, the effects-only sink) ends up
        // in this method and none of them has a HookContext. The level itself (not only the band) is kept
        // because the UB-land hooks go through the same collapse a swing does, and that collapse adds the
        // band from the level.
        int charLevel = levelOf(caster, characterId);
        int band = com.astrum.pcrd.impl.growth.LevelBands.bonusAt(charLevel);
        // Before the damage: the ALLY effects are buffs and heals the receivers keep, so they must be
        // live for whatever follows this cast. Applying them first also means a cast whose damage hits
        // nothing (no target in reach) still buffs the party, which is what the wiki describes — the buff
        // is a property of casting the ultimate, not of connecting with it.
        settlePartyEffects(caster, characterId, tier, band);
        List<UbEffect> victimEffects = victimEffects(characterId, tier);
        // 「大招命中时」: the rung's own hooks, fired per body the damage really lands on. Read once per cast
        // rather than per victim, and from the same rung the damage came from, so a base cast can never fire
        // the 6★ debuff.
        List<com.astrum.pcrd.api.v1.EffectHook> landHooks = def == null || def.ub() == null
                ? List.of() : def.ub().landHooksFor(enhanced);

        double reach = UbDamage.range(tier);
        List<UbDamage.Target> candidates = candidates(level, caster, reach);
        UbDamage.Plan plan = UbDamage.plan(tier,
                new UbDamage.Caster(caster.getUUID(), alive(caster)), candidates,
                AllyCandidates.configuredMode());
        // A cast that selected nobody logs and stops; a cast that selected somebody is settled even when its
        // clauses deal no damage at all — 紬希's 「将最远的敌人拉近 300」 is a clause whose whole effect is a
        // position write, and "no hits" is not "no ultimate". `isEmpty()` (no applications) therefore no longer
        // ends the method: `plan.segments()` is what the cast did.
        if (plan.segments().isEmpty()) {
            Astrum.LOGGER.debug("astrum: UB {} of {} reached nobody ({} candidates within {} blocks, {} "
                    + "clause(s) declared)", characterId, name(caster), candidates.size(), reach,
                    tier.damageSegments().size());
            return;
        }

        String display = def == null ? characterId.toString() : def.displayName();
        long nowTick = level.getGameTime();
        int applied = 0;
        for (UbDamage.Hit hit : plan.hits()) {
            UbDamage.Strike strike = strikeOf(plan, hit);
            Entity found = level.getEntity(hit.target());
            if (!(found instanceof LivingEntity victim) || victim.isRemoved() || !victim.isAlive()) {
                Astrum.LOGGER.debug("astrum: UB hit {}/{} on {} skipped: the target is gone",
                        hit.ordinal() + 1, hitsOf(strike), hit.target());
                continue;
            }
            // Each clause files its own damage: a blast typed `astrum:magic` next to a physical strike is a
            // clause's fact, not the tier's (see UbSegment#damageType). Resolved per clause because a Holder
            // belongs to one world's registry — the same reason this method resolves the tier's.
            Holder<DamageType> holder = damageType(level, strike == null ? null : strike.segment(), tier);
            if (holder == null) {
                Astrum.LOGGER.warn("astrum: damage type {} is not loaded, so clause {} of the ultimate of {} "
                        + "dealt no damage; is data/astrum/damage_type/{}.json in the pack?",
                        typeIdOf(tier, strike == null ? null : strike.segment()), hit.segment() + 1,
                        name(caster), typeIdOf(tier, strike == null ? null : strike.segment()).getPath());
                continue;
            }
            DamageSource source = new DamageSource(holder, caster, caster);
            // The attacker's stats, read on the amount about to be dealt — the same two calls
            // CombatEvents.flush makes for a hook batch entry, in the same order, and for the same reasons.
            //
            // MagicAttack.scaleFor is exact 1.0 for a physical type, so 姬塔's sword ultimate is
            // byte-identically unchanged; Crit.factorFor is one roll per hit off this level's RandomSource and
            // is exact 1.0 (and draws nothing) for a caster with no crit chance. Scaling here, before hurt(),
            // means nothing downstream multiplies a second time.
            //
            // `tier.critMultiplier()` is the ultimate's OWN multiplier: it REPLACES the caster's global
            // `astrum:crit_damage` for this tier's hits (鏡華's 6★ 「从 2 倍提升至 4 倍」, 璃乃's 3.5 倍), and
            // an absent one keeps the global. The per-skill channel exists because the global is a player
            // attribute — one number for every skill she owns — and "this one ultimate crits for 4×" had
            // nowhere to live. See Crit#factorFor for the replacement rule.
            //
            // `forcedCrit` is the second new per-clause fact: 「第一次 Hit 必定暴击」 / 「必定命中与必定暴击」 is a
            // statement about <b>this hit</b>, not about the caster's chance, so the clause asks for
            // Crit.forcedFactorFor — the same multiplier a rolled crit uses, with no roll and no draw. The summary
            // stays honest: the number really is a crit's number, and nothing downstream multiplies again.
            boolean forcedCrit = strike != null && strike.segment().forcedCrit();
            // 「随损失的生命值提升」: 咲恋's 【0.2*损失的生命值】 / 【2.5*损失的生命值】. Added to the clause's own
            // amount BEFORE the stat and crit factors, so the extra behaves like damage the clause declared
            // rather than like a separate hit — the source says the term is part of the same blow. At full
            // health the term is zero, which is the value those notes used to ship as a constant.
            float scaling = strike == null || strike.segment().lostHealthBonus().isEmpty()
                    ? 0.0F
                    : LostHealth.bonusUnits(strike.segment().lostHealthBonus().getAsDouble(), caster);
            float amount = (hit.amount() + scaling)
                    * (float) MagicAttack.scaleFor(caster, source)
                    * (float) (forcedCrit
                            ? Crit.forcedFactorFor(caster, critOf(strike, tier))
                            : Crit.factorFor(caster, level.getRandom(), critOf(strike, tier)));
            // Before the hit, and it has to be: a killing blow settles this ledger from <b>inside</b> hurt(), and
            // a credit noted afterwards would arrive after the experience was already shared. But that means the
            // number here is a <b>projection</b> — hurt() can refuse the damage entirely (invulnerability, a
            // cancelled event, an already-dead body) or reduce it (armour, absorption, resistance) — so it is
            // corrected below with the health the victim really lost. See ContributionTracker#correct.
            float creditable = LandedHit.healthLost(amount, victim.getHealth());
            if (creditable > 0.0F) {
                ContributionTracker.note(caster.getUUID(), victim.getUUID(), characterId, creditable,
                        nowTick);
            }
            // 「无视 50 物理防御力」: the third per-clause fact, and the one that has to happen to the <b>amount</b>
            // rather than to the victim. Vanilla's armour curve is non-linear and depends on the amount itself, so
            // "half the armour" is not "a fixed percentage more damage": ArmourPierce inverts that same curve and
            // hands hurt() the pre-armour amount that lands what this hit would have landed against a body with
            // half its armour. Nothing downstream sees a different world — the armour step runs on the inflated
            // number exactly as it runs on any other.
            double pierce = strike == null ? 0.0 : strike.segment().armourPierce().orElse(0.0);
            if (pierce > 0.0) {
                float pierced = ArmourPierce.amountFor(victim, source, amount, pierce);
                if (pierced > 0.0F && Float.isFinite(pierced)) {
                    amount = pierced;
                }
            }
            float before = victim.getHealth();
            boolean result;
            try {
                result = victim.hurt(source, amount);
            } catch (RuntimeException e) {
                Astrum.LOGGER.error("astrum: UB hit {}/{} on {} threw; the remaining hits still run",
                        hit.ordinal() + 1, hitsOf(strike), name(victim), e);
                continue;
            }
            float lost = Math.max(0.0F, before - victim.getHealth());
            // The projection above is replaced by what actually happened. On a killing blow the ledger is already
            // gone (take() ran inside hurt()) and this is a no-op by construction — the settled payout is not
            // rewritten. On a hit that landed harmlessly it removes the credit entirely, which is the bug this
            // exists for: a UB that took no health must not share the experience of somebody else's kill.
            if (!victim.isDeadOrDying()) {
                ContributionTracker.correct(caster.getUUID(), victim.getUUID(), characterId, lost);
            }
            if (result) {
                applied++;
                // The VICTIM effects, applied only where the hit was real — the same fact that gates the
                // burst below. ｷｬﾙ's 6★ アビスバースト is 「敵全体に…防御力大ダウン + 恐慌」 and both are
                // states on the body that took the damage, so `hurt()` saying yes is the right gate.
                settleVictimEffects(caster, characterId, victimEffects, victim, band);
                // And the clause's own effects, on the same fact: 「将最远的敌人拉近 300」 names a body, so the
                // pull is a property of that clause, not of the tier. The tier-wide list above still reaches
                // every body the cast hit, which is what it has always meant.
                if (strike != null) {
                    settleVictimEffects(caster, characterId, victimEffects(characterId, strike.segment()),
                            victim, band);
                }
                // Then the rung's UB-land hooks, on the same fact. They run after the tier's own victim
                // effects so a hook and a `UbEffect` of one clause keep the declaration order the pack wrote
                // at the top level, and both are gated on the hit having landed.
                UbLandHooks.fire(caster, victim, source, lost, characterId, charLevel, tier, landHooks);
                // The visual half, read off the one fact that says the hit was real: `hurt()`
                // answered true. Recorded here rather than re-derived by the emitter, so a target
                // that died, went invulnerable or stepped out of reach between `plan` and `hurt`
                // never gets a burst it did not earn.
                if (landed != null) {
                    landed.record(victimOf(victim));
                }
            }
            Astrum.LOGGER.debug("astrum: UB {} hit {}/{} on {} -> {} damage ({}) from {} authored, lost {} hp, "
                            + "landed={} (hp {}/{})", display, hit.ordinal() + 1, hitsOf(strike), name(victim),
                    amount, typeIdOf(tier, strike == null ? null : strike.segment()), hit.amount(), lost,
                    result, victim.getHealth(), victim.getMaxHealth());
        }
        // The clauses that deal no damage, after the ones that do: a clause 「将最远的敌人拉近」 moves a body, and
        // a body that died to the damage half of the same ultimate is not a body to move. Nothing gates these on
        // a hit landing, because they are not a consequence of one — the rule that selected the body is.
        int displaced = 0;
        for (int index = 0; index < plan.segments().size(); index++) {
            UbDamage.Strike strike = plan.segments().get(index);
            if (!strike.effectOnly()) {
                continue;
            }
            List<UbEffect> effects = victimEffects(characterId, strike.segment());
            for (UUID target : strike.targets()) {
                Entity found = level.getEntity(target);
                if (!(found instanceof LivingEntity victim) || victim.isRemoved() || !victim.isAlive()) {
                    continue;
                }
                settleVictimEffects(caster, characterId, effects, victim, band);
                displaced++;
                Astrum.LOGGER.debug("astrum: UB {} clause {} ({}) applied to {} without dealing damage",
                        characterId, index + 1, strike.segment().target().getSerializedName(),
                        name(victim));
            }
        }
        Astrum.LOGGER.debug("astrum: UB {} of {} settled {} of {} declared hits on {} target(s), "
                        + "{} damage asked in total, {} effect-only clause(s) on {} body(ies)", display,
                name(caster), applied, plan.hits().size(), plan.targets().size(), plan.total(),
                plan.segments().stream().filter(UbDamage.Strike::effectOnly).count(), displaced);
    }

    /** {@return the clause that produced {@code hit}, or {@code null} when the plan is not from this cast} */
    private static UbDamage.@Nullable Strike strikeOf(UbDamage.Plan plan, UbDamage.Hit hit) {
        int index = hit.segment();
        return index < 0 || index >= plan.segments().size() ? null : plan.segments().get(index);
    }

    /** {@return how many times {@code strike}'s clause lands, for the log lines; {@code 1} when unknown} */
    private static int hitsOf(UbDamage.@Nullable Strike strike) {
        return strike == null ? 1 : Math.max(1, strike.segment().hits());
    }

    /**
     * {@return the crit multiplier for {@code strike}'s hits — the clause's own, else the tier's}
     *
     * <p>One rule, one place: {@link UbSegment} carries the tier's value onto the implicit clause
     * ({@code UbTier.damageSegments()}), so this fallback is only reached by a plan that is not from a tier at
     * all. A pack's clause may still name a different multiplier (「本技能暴击时 4 倍」 on the blast alone).
     */
    private static OptionalDouble critOf(UbDamage.@Nullable Strike strike, UbTier tier) {
        if (strike != null && strike.segment().critMultiplier().isPresent()) {
            return strike.segment().critMultiplier();
        }
        return tier == null ? OptionalDouble.empty() : tier.critMultiplier();
    }

    /**
     * {@return the VICTIM-scope effects of one clause, with an illegal pair reported and dropped}
     *
     * <p>The same filter the tier's own list goes through, applied to a clause's list: an {@code ALLY} effect
     * written on a clause would buff the enemy the clause selected, so it is dropped and the pack is told once
     * ({@link UbEffect#mismatch} cannot see that mistake — the pair is legal in the abstract, wrong here).
     */
    private static List<UbEffect> victimEffects(ResourceLocation characterId, UbSegment segment) {
        if (segment == null || segment.effects().isEmpty()) {
            return List.of();
        }
        List<UbEffect> wanted = new ArrayList<>();
        for (UbEffect effect : segment.effects()) {
            if (effect == null) {
                continue;
            }
            if (effect.scope() != UbEffect.Scope.VICTIM) {
                Optional<String> problem = Optional.of("is declared on a damage clause, whose receivers are "
                        + "enemies it selected, so a party-scoped effect there would buff them; use the "
                        + "tier-level effects list for those");
                reportMismatch(characterId, effect, problem.get());
                continue;
            }
            Optional<String> problem = effect.mismatch();
            if (problem.isPresent()) {
                reportMismatch(characterId, effect, problem.get());
                continue;
            }
            wanted.add(effect);
        }
        return List.copyOf(wanted);
    }

    /**
     * {@return how many marks {@code grant} is worth to a caster holding her tally}
     *
     * <p>「【5+计数器枚数】个」 is a formula over the marks she is <b>holding at cast time</b>, so this read
     * comes before the grant. Both numbers are needed and neither is derivable from the other — see
     * {@code UbCounterGrant}.
     */
    private static int grantMarks(ServerPlayer caster, com.astrum.pcrd.api.v1.UbCounterGrant grant) {
        return grant.marksFor(Counters.countOf(caster));
    }

    /** {@return the victim's pose as the burst needs it — a value, so the emitter holds no entity} */
    private static UbBurstVictims.Victim victimOf(LivingEntity victim) {
        return new UbBurstVictims.Victim(victim.getUUID(), victim.getX(), victim.getY(),
                victim.getZ(), victim.getBbHeight());
    }

    /**
     * {@return the caster's level for {@code characterId}, or {@code 0} when this build cannot say}
     *
     * <p>{@code 0} is the honest answer for a quarantined roster and for a character the book does not hold: a
     * band can only add, so "this build cannot say what level that is" must not hand out a rung the player has
     * not earned — the same reasoning {@code GrowthMultiplier.NEUTRAL} documents. Callers turn it into a band
     * through {@code LevelBands.bonusAt}, the one band table, rather than subtracting one here.
     */
    private static int levelOf(ServerPlayer caster, ResourceLocation characterId) {
        try {
            com.astrum.pcrd.impl.attach.Roster roster = caster.getData(
                    com.astrum.pcrd.impl.attach.Attachments.ROSTER);
            if (roster.isQuarantined()) {
                return 0;
            }
            com.astrum.pcrd.api.v1.CharacterProgress progress = roster.progressOf(characterId);
            return progress == null ? 0 : progress.level();
        } catch (RuntimeException e) {
            Astrum.LOGGER.debug("astrum: could not read the level of {} for {}; using no band",
                    characterId, name(caster), e);
            return 0;
        }
    }

    /**
     * Applies every {@code ALLY} effect of the tier, before the damage.
     *
     * <p>One loop over the tier's list, and the receiver rule is entirely {@code TieredEffects}' — the
     * caster plus her party through {@code AllySelector}, or the caster alone for a self-only effect. So
     * this method never asks "who is an ally", which is the property that keeps a party buff from
     * acquiring a second definition here.
     *
     * <p>Guarded per effect: one clause that cannot be applied must not cost the others, and the cast has
     * already been paid for by the time this runs.
     */
    private static void settlePartyEffects(ServerPlayer caster, ResourceLocation characterId,
            UbTier tier, int band) {
        for (UbEffect effect : tier.effects()) {
            if (effect == null || effect.scope() != UbEffect.Scope.ALLY) {
                continue;
            }
            Optional<String> problem = effect.mismatch();
            if (problem.isPresent()) {
                reportMismatch(characterId, effect, problem.get());
                continue;
            }
            if (!rolls(caster, effect)) {
                Astrum.LOGGER.debug("astrum: UB {} skipped a {} clause with chance {} — the roll missed",
                        characterId, effect.type(), effect.chance());
                continue;
            }
            try {
                // 「名指しの効果」: an `ally_effect` names a mob effect id (怜's 风之刃 marker is one) instead of
                // a rung. It goes through PartyVanillaEffects — the same applier the hook form uses — so the
                // receiver set, the radius rule and the duration floor are not re-decided here.
                if (effect.vanillaEffect().isPresent()) {
                    int reached = PartyVanillaEffects.apply(caster, effect.vanillaEffect().get(),
                            AreaBuffs.ticksFor(effect.params()), effect.amplifier(), characterId,
                            effect.params().radius());
                    Astrum.LOGGER.debug("astrum: UB {} applied named effect {} amp {} for {}t to {} "
                                    + "receiver(s)", characterId, effect.vanillaEffect().get(),
                            effect.amplifier(), AreaBuffs.ticksFor(effect.params()), reached);
                    continue;
                }
                // The crit-<b>chance</b> twin of the arm below: its percentage is the wiki's own number, so it goes
                // through CritChanceUps — the same applier the hook form uses, with the same receiver set and
                // refresh-not-compound rule.
                if (effect.type() == com.astrum.pcrd.api.v1.EffectType.CRIT_CHANCE_UP
                        && effect.percent().isPresent()) {
                    int reached = CritChanceUps.apply(caster, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), characterId, effect.params().radius());
                    Astrum.LOGGER.debug("astrum: UB {} applied crit_chance_up +{}% for {}s to {} receiver(s)",
                            characterId, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), reached);
                    continue;
                }
                // 「每秒恢复自身【10+「冰龙之印」】技能值」: the formula's two numbers travel in the chargeRate block,
                // because a rung cannot express a rate that follows a live tally.
                if (effect.type() == com.astrum.pcrd.api.v1.EffectType.COUNTER_CHARGE_REGEN
                        && effect.chargeRate().isPresent()) {
                    boolean applied = OverTimeEffects.applyCounterChargeRegen(caster,
                            effect.chargeRate().get().base(), effect.chargeRate().get().perMark(),
                            effect.params().seconds().orElse(0.0), characterId);
                    Astrum.LOGGER.debug("astrum: UB {} applied counter_charge_regen {} + {} a mark a second "
                            + "for {}s to {} -> applied={}", characterId, effect.chargeRate().get().base(),
                            effect.chargeRate().get().perMark(),
                            effect.params().seconds().orElse(0.0), caster.getName().getString(), applied);
                    continue;
                }
                // The timed crit-damage buff is the one party effect whose magnitude is NOT a rung: the wiki gives
                // 「28%」 as a plain percentage, and a ladder would quantise it (the nearest rungs are 20% and 30%).
                // So the number travels in its own field and the effect goes through CritDamageUps — the same
                // applier the hook form uses, so receiver set, reach and refresh-not-compound are not decided here.
                if (effect.type() == com.astrum.pcrd.api.v1.EffectType.CRIT_DAMAGE_UP
                        && effect.percent().isPresent()) {
                    int reached = CritDamageUps.apply(caster, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), characterId, effect.params().radius());
                    Astrum.LOGGER.debug("astrum: UB {} applied crit_damage_up +{}% for {}s to {} receiver(s)",
                            characterId, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), reached);
                    continue;
                }
                // 计数 is the one party effect whose magnitude is a FORMULA over the caster's tally rather
                // than a rung, so it does not go through TieredEffects at all: the grant block is read here,
                // the cap rung is derived from the cap the source states, and Counters moves the tally one
                // mark at a time so a grant of five and five grants of one are the same state.
                if (effect.type() == com.astrum.pcrd.api.v1.EffectType.COUNTER
                        && effect.counter().isPresent()) {
                    int marks = grantMarks(caster, effect.counter().get());
                    int rung = Counters.tierForStatedCap(effect.counter().get().cap());
                    int after = Counters.addMarks(caster, marks,
                            rung, AreaBuffs.ticksFor(effect.params()));
                    Astrum.LOGGER.debug("astrum: UB {} granted {} mark(s) at cap rung {} (cap {}) -> {}/{}",
                            characterId, marks, rung, Counters.capForTier(rung), after,
                            Counters.capForTier(rung));
                    continue;
                }
                int receivers = TieredEffects.applyParty(caster, effect.type(), effect.params(),
                        characterId, band);
                Astrum.LOGGER.debug("astrum: UB {} applied {} tier {} to {} receiver(s)", characterId,
                        effect.type(), effect.params().tier(), receivers);
            } catch (RuntimeException e) {
                Astrum.LOGGER.error("astrum: applying the {} of {} threw; the cast's damage and its other "
                        + "effects are unaffected", effect.type(), characterId, e);
            }
        }
    }

    /**
     * Applies every {@code VICTIM} effect of the tier to one body the ultimate actually hit.
     *
     * <p>Called from the settlement loop <b>after</b> {@code hurt()} answered true, so an ultimate that
     * a target dodged, is immune to, or that walked out of reach does not leave a debuff behind — the
     * same gate the burst's ledger uses, and the reason this is here rather than in the plan.
     *
     * <p>Never throws: one victim's effect handling must not cost the remaining hits' debuffs.
     */
    private static void settleVictimEffects(ServerPlayer caster, ResourceLocation characterId,
            List<UbEffect> effects, LivingEntity victim, int band) {
        for (UbEffect effect : effects) {
            // The roll is per clause per <b>victim</b>: 铃莓's UB splits her defence debuff into a 33% and a 67%
            // branch, and the two are mutually exclusive on any one body only if each is rolled there. Rolling
            // once per cast would hand the whole enemy group the same branch, which is a different (and
            // lopsided) mechanic.
            if (!rolls(caster, effect)) {
                Astrum.LOGGER.debug("astrum: UB {} skipped a {} clause on {} with chance {} — the roll missed",
                        characterId, effect.type(), name(victim), effect.chance());
                continue;
            }
            try {
                // The two victim-side crit debuffs: their magnitude is the wiki's own percentage, not a rung, so
                // they carry it in `percent` and go straight to CritDowns — the same applier the hook form uses, on
                // the one body this settlement already knows was hit. `params.seconds` is the clock both shapes
                // read, so a cast and a swing cannot disagree about how long the debuff lasts.
                if (effect.type() == com.astrum.pcrd.api.v1.EffectType.CRIT_CHANCE_DOWN
                        && effect.percent().isPresent()) {
                    boolean applied = CritDowns.applyChance(victim, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), characterId);
                    Astrum.LOGGER.debug("astrum: UB {} applied crit_chance_down -{}% for {}s to {} -> applied={}",
                            characterId, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), name(victim), applied);
                    continue;
                }
                if (effect.type() == com.astrum.pcrd.api.v1.EffectType.CRIT_DAMAGE_DOWN
                        && effect.percent().isPresent()) {
                    boolean applied = CritDowns.applyDamage(victim, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), characterId);
                    Astrum.LOGGER.debug("astrum: UB {} applied crit_damage_down -{}% for {}s to {} -> applied={}",
                            characterId, effect.percent().getAsDouble(),
                            effect.params().seconds().orElse(0.0), name(victim), applied);
                    continue;
                }
                // NEUTRAL_MULTIPLIER: §5.4 exempts the ultimate from diminishing returns, so a debuff it
                // applies keeps its authored duration (see this field's javadoc).
                //
                // The <b>caster</b> is named, not inferred. `TieredEffects.sourceOf` reads who last damaged the
                // victim, which is the caster on the damage path — but a clause that deals no damage (紬希's
                // pull on the farthest body, her knockback on the nearest) is settled without any hit having
                // landed on that body, so inferring the source there would either find nothing or find whoever
                // hit it last. The cast knows who cast it; saying so is both shorter and true.
                boolean changed = TieredEffects.applyVictim(victim, caster, effect.type(), effect.params(),
                        characterId, NEUTRAL_MULTIPLIER, band, effect.launch());
                Astrum.LOGGER.debug("astrum: UB {} applied {} tier {} to {} -> changed={}",
                        characterId, effect.type(), effect.params().tier(), name(victim), changed);
            } catch (RuntimeException e) {
                Astrum.LOGGER.error("astrum: the {} of {} threw on {}; the other hits still run",
                        effect.type(), characterId, name(victim), e);
            }
        }
    }

    /**
     * {@return the tier's {@code VICTIM} effects, with an illegal pair reported and dropped}
     *
     * <p>Filtered once per cast rather than per victim, so a scope mistake produces one line and not one
     * per body hit. A mismatched pair is <b>dropped</b> rather than applied to the wrong side: putting
     * 「味方全体の攻撃力アップ」 on the enemy is worse than not applying it, and the WARN names the file's
     * clause so it can be fixed.
     */
    private static List<UbEffect> victimEffects(ResourceLocation characterId, UbTier tier) {
        List<UbEffect> wanted = new ArrayList<>();
        for (UbEffect effect : tier.effects()) {
            if (effect == null || effect.scope() != UbEffect.Scope.VICTIM) {
                continue;
            }
            Optional<String> problem = effect.mismatch();
            if (problem.isPresent()) {
                reportMismatch(characterId, effect, problem.get());
                continue;
            }
            wanted.add(effect);
        }
        return List.copyOf(wanted);
    }

    /**
     * {@return whether {@code effect}'s declared chance fires}
     *
     * <p>{@code 1.0} — the default, and every clause before this field existed — always fires, without touching
     * the random source at all. Otherwise one draw from the caster's own {@code RandomSource}. A {@code 0}
     * chance is refused by {@link UbEffect#mismatch} before this is reached, so the answer here is never a
     * permanent "never" nobody can see.
     *
     * @param caster  the player whose cast is settling
     * @param effect  the clause being applied
     */
    private static boolean rolls(ServerPlayer caster, UbEffect effect) {
        double chance = effect.chance();
        if (chance >= 1.0) {
            return true;
        }
        if (caster == null) {
            return true;
        }
        return caster.getRandom().nextDouble() < chance;
    }

    /** Logs one scope/effect mismatch once per (character, effect, message). */
    private static void reportMismatch(ResourceLocation characterId, UbEffect effect, String problem) {
        String key = characterId + "|" + effect.type() + "|" + problem;
        if (REPORTED.first(key)) {
            Astrum.LOGGER.warn("astrum: UB effect of {} skipped: {}; reported once.", characterId,
                    problem);
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

    /**
     * {@return one entity as the plan sees it}
     *
     * <p>Two facts about its place in the world, and they answer two different questions: {@code distance} is
     * the caster-relative number every caster-relative rule ranks and filters by (the game measured it with
     * {@code distanceToSqr}, unchanged since the first version of this class), and {@code position} is where the
     * body <em>is</em>, which one clause needs — 美美 6★'s blast is centred on the second-nearest body, and a
     * distance from the caster cannot say how far one candidate is from another. Neither is recomputed from the
     * other here.
     */
    private static UbDamage.Target target(ServerPlayer caster, LivingEntity entity) {
        boolean self = entity.getUUID().equals(caster.getUUID());
        double distance = self ? 0.0 : Math.sqrt(entity.distanceToSqr(caster));
        boolean player = entity instanceof ServerPlayer;
        Optional<String> team = entity instanceof ServerPlayer holder ? teamName(holder)
                : Optional.empty();
        return new UbDamage.Target(entity.getUUID(), player, self, team,
                Double.isFinite(distance) ? distance : Double.MAX_VALUE, untouchable(entity),
                new UbDamage.Point(entity.getX(), entity.getY(), entity.getZ()));
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

    /**
     * {@return the damage type the tier fires, resolved from the level's registry}
     *
     * <p>The tier's own {@code damageType} when it declares one, {@link UbDamage#DAMAGE_TYPE} otherwise.
     * Resolved here rather than stored because a {@code Holder} belongs to one world's registry: a
     * definition may be re-read across a datapack reload, and holding a resolved holder would pin a
     * registry object past its lifetime.
     *
     * <p>{@code null} means the type is not in the pack, which the caller reports with the id so the
     * operator knows which json file to add.
     */
    private static Holder<DamageType> damageType(ServerLevel level, UbSegment segment, UbTier tier) {
        ResourceLocation wanted = typeIdOf(tier, segment);
        Registry<DamageType> types =
                level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return types.getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, wanted)).orElse(null);
    }

    /** {@return the id of the damage type {@code tier} files its hits under, its clauses overriding it} */
    public static ResourceLocation typeIdOf(UbTier tier, UbSegment segment) {
        if (segment != null && segment.damageType().isPresent()) {
            return segment.damageType().get();
        }
        return typeIdOf(tier);
    }

    /** {@return the id of the damage type {@code tier} files its hits under} */
    public static ResourceLocation typeIdOf(UbTier tier) {
        return tier == null ? UbDamage.DAMAGE_TYPE
                : tier.damageType().orElse(UbDamage.DAMAGE_TYPE);
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
                candidates(level, caster, UbDamage.range(tier)), AllyCandidates.configuredMode()).targets();
    }
}
