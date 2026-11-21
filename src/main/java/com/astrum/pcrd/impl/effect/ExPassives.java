package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.ExPassive;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.astrum.pcrd.api.v1.UbDef;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>EX as a real passive</b>: applied when the character is enrolled into a party slot, removed when she
 * leaves.
 *
 * <h2>The lifecycle, and why it has exactly one entry point</h2>
 * An EX skill in the real game is 「战斗开始时提升自身属性」 — a stat buff that is simply <em>there</em> for the
 * whole fight. A 「战斗开始」 trigger now exists ({@code Trigger.BATTLE_START}, fired on the first blow of a
 * fight), but it cannot carry EX: a trigger fires a character's {@code EffectHook} list <b>for a hit</b>, while
 * an EX clause is an <b>ownership</b> — an infinite effect instance or an attribute modifier whose lifetime is
 * the party slot, with no target, no hit and no clock. So the closest honest mechanic is still: <b>while she is
 * in a party slot, the passive is on the player</b>. Enrolment is the moment it becomes true; leaving the slot
 * is the moment it stops.
 *
 * <p>There is exactly <b>one</b> membership-change point in this build —
 * {@code impl.party.PartyService#edit} — and that is where this class is called from. No second watcher, no
 * tick listener, no reconcile-on-every-hit: the party attachment is written in one place, so the passive's
 * lifecycle is driven from the same place. {@code PartyBuffs} already established this contract for the five
 * HUD markers; this class follows it rather than growing a parallel path.
 *
 * <h2>Why a vanilla effect instance and not an attribute write</h2>
 * The same argument {@code AttackBuffEffect} makes: an EX that ended by our remembering to end it would need
 * a clock, i.e. a tick listener. A {@code MobEffectInstance} with
 * {@link MobEffectInstance#INFINITE_DURATION} and an attribute modifier is added and removed by vanilla's own
 * {@code addAttributeModifiers} / {@code removeAttributeModifiers}, so expiry-on-leave is
 * {@code removeEffect} and nothing else. That is also why the passive cannot "stack up" over a long session:
 * a second identical grant is a no-op, and the only writer is a party edit.
 *
 * <h2>The four EX skills, and the two shapes a clause can take</h2>
 * <table border="1">
 *   <caption>What each shipped EX grants, from the bwiki formulas</caption>
 *   <tr><th>character</th><th>rung 1</th><th>rung 2 (专武/5★)</th><th>shape</th></tr>
 *   <tr><td>姬塔</td><td>龙卷的攻刃 【15+15×L】物攻</td><td>龙卷的攻刃+ 【240+15×L】</td>
 *       <td>{@code buff_attack_area}, tier 5 (capped)</td></tr>
 *   <tr><td>佩可莉姆</td><td>公主之力 【75+75×L】生命值</td><td>公主之力+ 【1275+75×L】</td>
 *       <td>{@code minecraft:health_boost} — no effect type of ours expresses max health</td></tr>
 *   <tr><td>可可萝</td><td>精灵的启示 【15+15×L】物攻</td><td>+ 30 魔法防御力</td>
 *       <td>{@code buff_attack_area} tier 5, plus a vanilla armour clause</td></tr>
 *   <tr><td>凯露</td><td>黑暗之蚀 【15+15×L】魔攻</td><td>黑暗之蚀+ 【240+15×L】</td>
 *       <td>{@code buff_attack_area} tier 5 — see the concern in the report</td></tr>
 * </table>
 *
 * <h2>EX gets no level bonus</h2>
 * The human was explicit. A clause resolves its rung from the <b>affection</b> tier alone
 * ({@link ExPassive#rungFor}) and then clamps against the effect's ladder — never through
 * {@code LevelBands}. {@link #tierOf} states that, and {@code ExPassiveTest} pins it across a band boundary.
 */
@ApiStatus.Internal
public final class ExPassives {

    /**
     * How long an EX passive lasts: as long as it is not removed.
     *
     * <p>{@code INFINITE_DURATION} and not a long finite number, for {@code PartyBuffs.DURATION}'s reason: a
     * finite duration would expire mid-session and quietly violate "while she is in the party", and nothing
     * refreshes it (there is no tick listener).
     */
    public static final int DURATION = MobEffectInstance.INFINITE_DURATION;

    private ExPassives() {
    }

    /**
     * {@return the {@code ex} ladder of {@code def}, or empty when she has no EX}
     *
     * @param def the character, possibly {@code null}
     */
    public static Optional<SkillLadder> ladderOf(@Nullable CharacterDef def) {
        if (def == null) {
            return Optional.empty();
        }
        return AffectionKernel.ladderFor(def, SkillLadder.EX_SKILL);
    }

    /**
     * {@return the EX passive {@code def} grants at affection {@code tier}, or empty when it grants nothing}
     *
     * <p>The one answer to "what does her EX do right now", read by the applier, by the book's row and by the
     * traceability test. {@code tier} comes from {@code GrowthFacts.tier} — this method never derives it.
     *
     * @param def  the character
     * @param tier the affection tier, 1–3
     */
    public static Optional<ExPassive.Rung> rungAt(@Nullable CharacterDef def, int tier) {
        Optional<ExPassive> passive = ladderOf(def).flatMap(SkillLadder::passive);
        if (passive.isEmpty() || passive.get().isEmpty()) {
            return Optional.empty();
        }
        return passive.get().rungFor(tier);
    }

    /**
     * {@return the EX skill's display name at affection {@code tier}, or empty when she has no EX}
     *
     * <p>Delegated to {@link SkillLadder#nameAt}, so the name a player reads and the rung the passive applies
     * are answered by the same tier number and the same ladder — "the row says 龙卷的攻刃+ and the buff is the
     * plus one" cannot drift.
     *
     * @param def  the character
     * @param tier the affection tier
     */
    public static Optional<String> nameAt(@Nullable CharacterDef def, int tier) {
        return ladderOf(def).map(ladder -> ladder.nameAt(tier));
    }

    /**
     * {@return the tier an EX clause resolves to, <b>without</b> any level band}
     *
     * <h2>Why this is not {@code TieredAmounts.resolve}</h2>
     * {@code TieredAmounts.resolve(type, params, band)} adds the band before clamping. EX must not: the human
     * said so, and the bwiki agrees — every EX formula's strength is its own {@code +perLevel × 技能等级} term,
     * so a level band on top would be a second, invented growth curve. So the clause's declared tier is
     * clamped against the effect's ladder and nothing else is added.
     *
     * @param clause the clause to resolve
     */
    public static int tierOf(ExPassive.Clause clause) {
        if (clause == null || clause.type().isEmpty()) {
            return EffectTiers.FIRST_TIER;
        }
        return TieredAmounts.tierOf(clause.type().get(), clause.params(), 0);
    }

    /**
     * Makes the player's EX passives match her party <b>exactly</b>.
     *
     * <h2>What it applies, and to whom</h2>
     * Every occupied slot whose character has an EX rung that grants something: its clauses become effect
     * instances on <b>the player</b> (not on her allies — the bwiki text is 「自身」 for all four). The
     * receiver rule is therefore "the caster", which is why this does not go through {@code AllySelector}: a
     * self buff has nobody to select, exactly as {@code HEAL_SELF} documents.
     *
     * <h2>Idempotence</h2>
     * An instance is granted only when the player does not already hold an instance of that effect at that
     * amplifier; a stale instance of ours at a <b>different</b> amplifier is removed, so an affection change
     * that moves the rung produces the new rung rather than leaving the old one. Anything else is left alone,
     * so a re-apply costs no write and the HUD does not blink.
     *
     * <h2>Never throws</h2>
     * Reached from a party edit (both doors) and from login/respawn. One bad clause must not cost the party the
     * rest of her passives, and must not disconnect the player — the same policy {@code PartyBuffs.apply}
     * follows.
     *
     * @param player the player whose passives are reconciled; must be in a level
     * @return how many effect instances were granted or removed, for the caller's log
     */
    public static int apply(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        Party party = player.getData(Attachments.PARTY);
        List<Wanted> wanted = wantedFor(player, party);
        int changes = 0;
        // Grant / refresh what the party wants ...
        for (Wanted one : wanted) {
            boolean changed = one.isFlat() ? reconcileFlat(player, one) : reconcile(player, one);
            if (changed) {
                changes++;
            }
        }
        // ... and take away what it no longer does. This half is what makes "she left the party" work: an
        // infinite instance has no clock to expire it, so leaving a slot is the only event that can end it,
        // and it happens here rather than in a watcher because this class is driven by the one edit point.
        java.util.Set<Holder<MobEffect>> keep = new java.util.HashSet<>();
        java.util.Set<ResourceLocation> flatKeep = new java.util.HashSet<>();
        for (Wanted one : wanted) {
            if (one == null) {
                continue;
            }
            if (one.isFlat()) {
                flatKeep.add(one.characterId());
            } else {
                keep.add(one.effect());
            }
        }
        for (Holder<MobEffect> holder : knownHolders()) {
            if (keep.contains(holder)) {
                continue;
            }
            if (isOurPassive(player, holder) && player.removeEffect(holder)) {
                changes++;
                Astrum.LOGGER.info("astrum: EX passive removed ({}) from {} — no equipped character grants it "
                        + "any more", holder.value().getDescriptionId(), player.getGameProfile().getName());
            }
        }
        changes += revokeFlatNotWanted(player, flatKeep);
        // ... and her own declared 暴击率, which has the same lifetime for the same reason: 「按角色数据配置」 gives
        // each character a crit chance, and an attribute modifier that outlived its owner would be a number on
        // the player with no member of the party to explain it. This is the one membership sweep in the build
        // (party edit, login, respawn all call this method), so the grant is reconciled here rather than by a
        // second listener — see CritChances for the rule and the band table.
        changes += CritChances.reconcile(player);
        return changes;
    }

    /**
     * Removes every flat EX grant from {@code player} whose character is no longer in a party slot.
     *
     * <h2>Why this needs its own sweep</h2>
     * A flat grant is an attribute modifier under an id derived from the character
     * ({@code FlatAttack.modifierId}), so "which characters currently have a grant?" cannot be asked of the
     * player the way {@code getEffect} answers it for an effect type: an attribute modifier has no
     * enumeration-by-owner. So the candidates are <b>the roster</b> plus every character in the party
     * history this build can name, and each one's modifier is removed if the party no longer wants it.
     *
     * <p>The roster is the right source: a character can only have an EX grant if she was once in a party,
     * and this build records a character in the roster on enrolment (「角色卡使用后放进角色册」). A character
     * removed from the party but still owned therefore gets her grant revoked here, which is exactly the
     * "both revert on leaving the party" requirement.
     *
     * @param player   the player to clean
     * @param keepFlat the characters the party still wants a flat grant for
     * @return how many grants were removed
     */
    private static int revokeFlatNotWanted(ServerPlayer player, java.util.Set<ResourceLocation> keepFlat) {
        Roster roster = player.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            // Unreadable, so the set of characters to consider is unknown. Doing nothing is the safe half:
            // a stale grant is a number that is too high, while a wrong removal is a buff the player paid
            // for disappearing. The next readable party edit fixes it.
            return 0;
        }
        int removed = 0;
        for (ResourceLocation characterId : roster.entries().keySet()) {
            if (keepFlat.contains(characterId)) {
                continue;
            }
            for (com.astrum.pcrd.api.v1.EffectType type : com.astrum.pcrd.api.v1.EffectTypes.flat()) {
                if (FlatAttack.revoke(player, characterId, type)) {
                    removed++;
                    Astrum.LOGGER.info("astrum: {} of {} revoked from {} — she holds no party slot any more",
                            type, characterId, player.getGameProfile().getName());
                }
            }
        }
        return removed;
    }

    /**
     * Grants or removes one wanted <b>flat</b> clause, answering whether the player's attributes changed.
     *
     * <h2>Idempotence, and why it is the same rule as the effect half</h2>
     * {@code FlatAttack.grant} answers {@code false} when the modifier is already exactly right, so a
     * re-apply costs no write and the client does not re-sync the attribute sheet. A grant at a
     * <b>different</b> amount is replaced, which is what makes a level-up move the number — the level is an
     * input to the formula, so 姬塔's EX is worth more at level 40 than at 1, and the reconciliation has to
     * be able to say so.
     *
     * <h2>The self-scope is the material's, not a choice</h2>
     * All four EX formulas say 「自身」. A flat grant is therefore on the player only, and no
     * {@code AllySelector} is involved — the same reading {@code HEAL_SELF} documents.
     */
    private static boolean reconcileFlat(ServerPlayer player, @Nullable Wanted one) {
        if (one == null || one.flatType().isEmpty()) {
            return false;
        }
        com.astrum.pcrd.api.v1.EffectType type = one.flatType().get();
        try {
            if (FlatAttack.grant(player, one.characterId(), type, one.points())) {
                Astrum.LOGGER.info("astrum: EX passive {} of {} -> +{} {} points (tier {}), {} total now on {}",
                        one.skillName(), one.characterId(), one.points(), type, one.tier(),
                        FlatAttack.granted(player, one.characterId(), type),
                        player.getGameProfile().getName());
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: EX flat grant {} of {} threw; her other clauses and the party edit "
                    + "are unaffected", one.skillName(), one.characterId(), e);
            return false;
        }
    }

    /**
     * {@return whether the player's instance of {@code effect} is <b>ours</b> — an EX passive, not a skill's buff}
     *
     * <h2>The bug this exists to prevent</h2>
     * An EX attack passive is {@code astrum:attack_area}, and so is a skill's timed party buff
     * ({@code buff_attack_area}). Vanilla keys a player's effects by <b>type</b>, so both live in the same slot
     * and a "remove what the party no longer wants" loop that only checked the type would <b>strip a live
     * 12-second buff the moment a party edit ran</b> — a character's skill silently doing nothing, with no line
     * of data to explain it.
     *
     * <p>The discriminator is the duration, and it is exact rather than heuristic: an EX passive is granted with
     * {@link #DURATION} ({@code INFINITE_DURATION}) because it lasts exactly as long as the character holds a
     * slot, while every timed effect in this build is granted with a finite instance
     * ({@code AreaBuffs.ticksFor}, {@code AttackBuffRules.ticksFor}, {@code Shields}). So an infinite instance of
     * one of the holders in {@link #knownHolders()} can only have come from here.
     */
    private static boolean isOurPassive(ServerPlayer player, Holder<MobEffect> effect) {
        MobEffectInstance held = player.getEffect(effect);
        return held != null && held.getDuration() == DURATION;
    }

    /**
     * Removes <b>every</b> EX passive this build can name from {@code player}.
     *
     * <p>Exists so "she left the party" has one implementation too, rather than a {@code removeEffect} literal
     * at a caller. {@link #apply} already removes what is no longer wanted; this is the belt for a path that
     * has no party to compare against (a probe, a future "the party feature is off" switch).
     *
     * @param player the player to clear
     * @return how many instances were removed
     */
    public static int clear(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        int removed = 0;
        for (Holder<MobEffect> holder : knownHolders()) {
            if (player.removeEffect(holder)) {
                removed++;
            }
        }
        // The flat grants go too. "Remove every EX passive this build can name" must mean all of them, or a
        // path that uses this belt would leave a character's attack points on the player with no effect entry
        // to explain them — the worst of both worlds, because the number is wrong and invisible.
        removed += revokeFlatNotWanted(player, java.util.Set.of());
        // The crit grants go with the rest: "remove every party-slot passive" must mean the chance too, or this
        // belt would leave a character's crit rate on the player with no slot to explain it.
        removed += CritChances.clear(player);
        return removed;
    }

    /**
     * {@return every EX clause the party wants on {@code player}, already resolved}
     *
     * <p>Package-private and pure over its inputs so a test can assert the resolution — "at tier 3 her EX is
     * 剑圣的天赋 at rung 5 and untouched by her level" — without a world. {@link #apply} is then only the
     * effect bookkeeping.
     *
     * @param registry the character registry, or {@code null} when no pack is loaded
     * @param aura     the resolved roster progress reader; {@code null} means "this build cannot say", which
     *                 resolves to tier 1 — the whiteboard — rather than to an invented higher rung
     * @param party    the party
     */
    static List<Wanted> wantedFor(@Nullable Registry<CharacterDef> registry, @Nullable Roster roster,
            Party party) {
        if (registry == null || party == null) {
            return List.of();
        }
        List<Wanted> wanted = new ArrayList<>();
        for (var slot : party.slots()) {
            if (slot.isEmpty()) {
                continue;
            }
            ResourceLocation characterId = slot.get();
            CharacterDef def = registry.get(characterId);
            if (def == null) {
                continue;   // the pack no longer defines it
            }
            int tier = 1;
            int level = 1;
            if (roster != null && !roster.isQuarantined()) {
                CharacterProgress progress = roster.progressOf(characterId);
                if (progress != null) {
                    tier = AffectionKernel.tier(progress.affection(), def.affection());
                    // The level axis. A flat EX clause's points are 【base + perLevel × 技能等级】, so this
                    // is the number the formula multiplies — not a band, and not a reference constant. See
                    // FlatAmount and §15.4.4.
                    level = Math.max(1, progress.level());
                }
            }
            // `final` copies for the lambda below: `tier` and `level` are assigned in the branch above, so
            // they are not effectively final, and capturing the loop's `characterId` directly would capture
            // the loop variable's identity rather than this iteration's value in some Java versions.
            final int resolvedTier = tier;
            final int resolvedLevel = level;
            final ResourceLocation owner = characterId;
            rungAt(def, resolvedTier).ifPresent(rung -> {
                for (ExPassive.Clause clause : rung.clauses()) {
                    Optional<String> problem = clause.problem();
                    if (problem.isPresent()) {
                        Astrum.LOGGER.warn("astrum: EX of {} (tier {}, {}) has a clause that cannot be "
                                + "applied: {}; the other clauses still apply", owner, resolvedTier,
                                def.displayName(), problem.get());
                        continue;
                    }
                    Wanted one = Wanted.of(owner, def, resolvedTier, resolvedLevel, clause);
                    if (one != null) {
                        wanted.add(one);
                    }
                }
            });
        }
        return List.copyOf(wanted);
    }

    /** {@return the same answer as {@link #wantedFor(Registry, Roster, Party)}, read off a live player} */
    static List<Wanted> wantedFor(ServerPlayer player, Party party) {
        Registry<CharacterDef> registry = AstrumApi.characters().orElse(null);
        Roster roster = player.getData(Attachments.ROSTER);
        return wantedFor(registry, roster, party);
    }

    /**
     * One resolved clause: what it grants, for which character.
     *
     * <h2>Two shapes, and why they are one record with a nullable half</h2>
     * A clause is either a <b>{@code MobEffect}</b> to hold at an amplifier (a tiered buff, or a named
     * vanilla effect) or a <b>flat attribute write</b> (an EX attack formula). The two have nothing
     * mechanically in common — one is a state with a clock, the other a modifier with an owner — but every
     * caller handles them together: {@link #apply} reconciles both, and "drop what the party no longer
     * wants" must cover both or a departed character's attack points stay on the player forever.
     *
     * <p>So the record carries both halves and {@link #isFlat()} decides, rather than two lists that a
     * caller could reconcile unevenly. {@code effect} is non-null for the effect shape and {@code null}
     * for the flat one; {@code points} is meaningful only for the flat one.
     *
     * @param characterId the character the passive belongs to, for the log line and the modifier id
     * @param skillName   the EX name at this tier, so the log shows 剑圣的天赋 rather than an id
     * @param effect      the registered effect, or {@code null} when this is a flat grant
     * @param amplifier   the effect level; for a tiered clause this is the ladder's, for a vanilla one the
     *                    author's. Never scaled. Unused by a flat clause
     * @param tier        the resolved affection tier (or the declared amplifier for a vanilla clause)
     * @param points      the flat point amount at this character's level, {@code 0} for the effect shape
     * @param flatType    which flat stat the points go to, empty for the effect shape
     */
    record Wanted(ResourceLocation characterId, String skillName, @Nullable Holder<MobEffect> effect,
                  int amplifier, int tier, double points,
                  Optional<com.astrum.pcrd.api.v1.EffectType> flatType) {

        /** {@return whether this clause is a flat attribute grant rather than a held effect} */
        boolean isFlat() {
            return effect == null;
        }

        static @Nullable Wanted of(ResourceLocation characterId, CharacterDef def, int tier, int level,
                ExPassive.Clause clause) {
            String name = nameAt(def, tier).orElse(def.effectiveSkillName());
            if (clause.isFlat()) {
                // A flat clause needs no registry lookup at all: it writes an attribute, and the attribute's
                // holder is resolved inside FlatAttack. What it does need is the character's level, which is
                // why `level` travels down here rather than being looked up at apply time.
                com.astrum.pcrd.api.v1.FlatAmount amount = clause.amount().orElse(
                        com.astrum.pcrd.api.v1.FlatAmount.NONE);
                double points = amount.amountAt(level);
                if (!(points > 0.0)) {
                    Astrum.LOGGER.warn("astrum: EX clause of {} names {} with a formula worth 0 points at "
                            + "level {}; nothing to grant", characterId,
                            clause.type().map(t -> t.getSerializedName()).orElse("(nothing)"), level);
                    return null;
                }
                return new Wanted(characterId, name, null, 0, tier, points, clause.type());
            }
            Optional<Holder<MobEffect>> effect = holderFor(clause);
            if (effect.isEmpty()) {
                Astrum.LOGGER.warn("astrum: EX clause of {} names {} which this build cannot resolve to a "
                                + "registered effect; skipped", characterId,
                        clause.vanilla().map(ResourceLocation::toString)
                                .orElse(clause.type().map(t -> t.getSerializedName()).orElse("(nothing)")));
                return null;
            }
            int amplifier = clause.vanilla().isPresent()
                    ? clause.amplifier()
                    // A tiered clause's amplifier comes from the effect's own rules class, never from here —
                    // and for the buffs this passive uses, tier N IS amplifier N-1.
                    : AttackAreaRules.amplifierForTier(tierOf(clause));
            return new Wanted(characterId, name, effect.get(), amplifier, tier, 0.0, Optional.empty());
        }
    }

    /**
     * {@return the registered effect a clause names, or empty when this build has no such effect}
     *
     * <p>A tiered clause resolves through {@link AreaBuffs#holderFor}, so the registry plumbing stays in one
     * place. Only the types an EX clause can meaningfully grant have a path: the three buffs an EX actually
     * uses. {@code HEAL_*} and {@code REMOVE_SHIELD} and {@code PERCENT_DAMAGE} have <b>no</b> holder by design
     * (they are an instant {@code heal()}, an absorption write and a damage entry, not states), so an EX clause
     * naming one is refused with a WARN rather than applied as a flickering HUD entry — see {@code PartyEffects}
     * on which types have no effect.
     */
    private static Optional<Holder<MobEffect>> holderFor(ExPassive.Clause clause) {
        if (clause.vanilla().isPresent()) {
            return BuiltInRegistries.MOB_EFFECT.getHolder(clause.vanilla().get())
                    .map(holder -> (Holder<MobEffect>) holder);
        }
        return clause.type().flatMap(type -> AreaBuffs.holderFor(pathOf(type)));
    }

    /**
     * {@return the registry path of a tiered effect's {@code MobEffect}, or {@code ""} when it has none}
     *
     * <p>The reverse of {@code PartyEffects}'s registrations. A switch with no {@code default}, so a new tiered
     * effect has to be given an answer — including the legitimate answer "no holder", which is what the three
     * instantaneous types get.
     */
    private static String pathOf(com.astrum.pcrd.api.v1.EffectType type) {
        return switch (type) {
            case BUFF_ATTACK_AREA -> AttackAreaEffect.PATH;
            case BUFF_MAGIC_ATTACK_AREA -> MagicAttackAreaEffect.PATH;
            // The FLAT type, not the buff's: 「只有 ex 或者 sp 技能里面加物理防御的，才是增加固定的护甲值」.
            // An EX is permanent, so a fraction of armour would be nothing on a naked character; the flat
            // form is worth the same everywhere, which is what a passive has to be.
            case BUFF_PHYS_DEFENCE_AREA -> DefenceRules.PHYS_FLAT_PATH;
            case BUFF_MAGIC_DEFENCE_AREA -> DefenceRules.MAGIC_PATH;
            case HASTE -> "haste";
            // No holder, and none wanted: an instant heal, an absorption write, or a damage entry.
            case HEAL_AREA, HEAL_SELF, REMOVE_SHIELD, PERCENT_DAMAGE, CHARGE_RATE_UP, SHIELD, PANIC,
                 DEFENCE_DOWN, MAGIC_DEFENCE_DOWN -> "";
            // The two flat effects have no holder either, and their emptiness is *load-bearing*: they are
            // attribute writes owned by the party slot (FlatAttack), not states with a clock. An EX clause
            // naming one is applied by applyFlat, never here — see wantedFor.
            case FLAT_ATTACK_UP, FLAT_MAGIC_ATTACK_UP -> "";
            // 召唤精灵 does have a holder, but an EX clause cannot own it: a ring of spirits is a summon with
            // a duration, and EX has no duration ("while she holds the slot" is an ownership, not a clock).
            // COUNTER and CONSUME_COUNTER are a tally and a spend, neither of which is an attribute an EX
            // could grant. All three answer "" — refused with a WARN rather than applied as a flickering
            // entry, the same answer the instantaneous types give.
            case SUMMON_SPIRIT, COUNTER, CONSUME_COUNTER -> "";
            // The six task-49 effects, all "" for the reason above one step further out: a transfer is a
            // one-tick grant to somebody else, and the four over-time types need a duration and a clock. An EX
            // passive is an ownership that lasts while she holds her slot, so none of these has a rung an EX
            // clause could name without inventing a duration it does not have.
            case TRANSFER_CHARGE, TRANSFER_HEAL, REGEN_SELF, REGEN_AREA,
                 CHARGE_REGEN_SELF, CHARGE_REGEN_AREA -> "";
            // Not tiered at all; a clause naming one is already refused by ExPassive.Clause.problem().
            case APPLY_EFFECT, EXTRA_DAMAGE, LIFESTEAL, GAIN_CHARGE, AREA_CHARGE, SPLASH, STACK, DETONATE,
                 ATTACK_BUFF, HEAL, ALLY_EFFECT -> "";
            // The three movement-control mechanics. PULL and KNOCKBACK are writes that finish in the tick they
            // happen and own no effect at all; TAUNT does own one, and still answers "" for the same reason
            // SUMMON_SPIRIT does one line up: an EX is an ownership with no clock, and a <b>permanent</b> taunt
            // would sweep for mobs on every tick of the player's life. A taunt is a timed call, so an EX clause
            // naming it is refused with a WARN rather than turned into a permanent aura nobody asked for.
            case PULL, KNOCKBACK, TAUNT -> "";
            // The two immunity channels. PHYS_IMMUNITY and CONTROL_IMMUNITY both own a real effect with a
            // duration, and an EX is an ownership with no clock — so a clause naming either is refused with
            // the same WARN the movement family gets, rather than becoming a permanent immunity. A character
            // who should be immune for as long as she holds her slot would need its own decision; nothing in
            // the shipped data asks for that.
            case PHYS_IMMUNITY, CONTROL_IMMUNITY -> "";
            // The timed crit-damage buff. It owns a real effect, but an EX passive is an <b>ownership with no
            // clock</b> — it lasts exactly as long as the character holds her slot — while this buff's whole
            // definition is a duration (千歌's 18 seconds). An EX clause naming it is therefore refused with the
            // same WARN the taunt and the immunities get, rather than becoming a permanent crit-damage aura
            // nobody asked for.
            case CRIT_DAMAGE_UP -> "";
            // The three additions of this task. The two typed channels are barriers, so they answer "" for the
            // same reason SHIELD does (an EX has no clock to spend a barrier over); the crit-chance buff is the
            // crit-damage one's twin and answers "" for the same reason (its whole definition is a duration);
            // and the tally-scaled charge regen is a per-second state, which no ownership can be.
            case SHIELD_PHYS, SHIELD_MAGIC, CRIT_CHANCE_UP, COUNTER_CHARGE_REGEN -> "";
            // The two victim-side crit debuffs answer "" as well, and for a reason of their own: their receiver is
            // the <b>enemy the skill hit</b>, so there is no body an ownership on the caster could put them on.
            // (Even if there were, an EX passive has no clock — see the block above.)
            case CRIT_CHANCE_DOWN, CRIT_DAMAGE_DOWN -> "";
        };
    }

    /** {@return every effect an EX passive of the four shipped characters can grant} */
    private static List<Holder<MobEffect>> knownHolders() {
        List<Holder<MobEffect>> holders = new ArrayList<>();
        PartyEffects.attackAreaHolder().ifPresent(holders::add);
        PartyEffects.magicAttackAreaHolder().ifPresent(holders::add);
        PartyEffects.physDefenceHolder().ifPresent(holders::add);
        PartyEffects.magicDefenceHolder().ifPresent(holders::add);
        PartyEffects.hasteHolder().ifPresent(holders::add);
        BuiltInRegistries.MOB_EFFECT.getHolder(HEALTH_BOOST)
                .ifPresent(holder -> holders.add((Holder<MobEffect>) holder));
        return List.copyOf(holders);
    }

    /** 「提升自身…生命值」 — vanilla's own max-health modifier, the one shape this mod has no type for. */
    static final ResourceLocation HEALTH_BOOST =
            ResourceLocation.withDefaultNamespace("health_boost");

    /**
     * Grants or removes one wanted clause, answering whether the player's state changed.
     *
     * <p>A held instance at the wanted amplifier is left alone (no write, no blink). A held instance at a
     * <b>different</b> amplifier is removed first, so an affection change that moves the rung cannot leave the
     * old one behind — vanilla merges two instances of one type by keeping the longer duration, so "just add the
     * new one" would silently keep the weaker amplifier's modifier alongside the stronger, which
     * {@code AttackAreaEffect}'s fixed modifier id turns into the <em>last</em> write winning rather than the
     * best. Removing first is what makes the rung true.
     */
    private static boolean reconcile(ServerPlayer player, @Nullable Wanted one) {
        if (one == null) {
            return false;
        }
        try {
            MobEffectInstance held = player.getEffect(one.effect());
            // "Already right" means an instance of OURS at the wanted amplifier. A finite instance of the same
            // type is somebody's timed skill buff (see isOurPassive) and neither satisfies this check nor is
            // touched by it.
            if (held != null && held.getDuration() == DURATION && held.getAmplifier() == one.amplifier()) {
                return false;
            }
            // A stale instance of OURS at a different rung is removed first. Vanilla merges two instances of one
            // type by <em>taking the longer duration</em> — both are infinite here, so the amplifier that survives
            // would be whichever the merge happened to keep, and an affection change that moved the rung could
            // leave the weaker modifier on the attribute. Removing first is what makes the rung true.
            if (held != null && held.getDuration() == DURATION) {
                player.removeEffect(one.effect());
            }
            player.addEffect(new MobEffectInstance(one.effect(), DURATION, one.amplifier(),
                    false, false, true));
            Astrum.LOGGER.info("astrum: EX passive {} of {} -> {} amp {} (tier {}) on {}", one.skillName(),
                    one.characterId(), one.effect().value().getDescriptionId(), one.amplifier(), one.tier(),
                    player.getGameProfile().getName());
            return true;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: EX passive {} of {} threw; her other clauses and the party edit are "
                    + "unaffected", one.skillName(), one.characterId(), e);
            return false;
        }
    }

    /**
     * {@return the EX name to show in the skill list at {@code tier}, or empty when she has no EX}
     *
     * <p>One call the book's row and the log line share, so "the row and the passive agree" is a property of
     * one implementation. The <b>effects</b> keep a row of their own: the UI renders them later, and the data
     * and the resolution exist now — which is the division of work the brief asks for.
     */
    public static Optional<String> rowName(@Nullable CharacterDef def, int tier) {
        return nameAt(def, tier);
    }
}
