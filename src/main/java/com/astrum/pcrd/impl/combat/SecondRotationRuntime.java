package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.SecondRotation;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>The second loop, wired: which hook list runs, when the mode starts, and what a kill does to it.</b>
 *
 * <h2>The one question this class answers, and the two it delegates</h2>
 * 「两套独立的循环」 needs exactly three rules, and this class is where they meet so that no caller has to
 * recombine them:
 *
 * <ol>
 *   <li><b>Which loop runs right now</b> — {@link #hooksFor}. Reads the store's deadline and, when the mode is
 *       live, answers the character's <em>second</em> skill set instead of her first. The first set is still
 *       resolved by {@code AffectionKernel.hooksAt} and the second by {@link SecondRotation#hooksAt}, so
 *       neither resolver is reimplemented here; this method only chooses between two answers.</li>
 *   <li><b>When the mode starts</b> — {@link #activate}, called from the one place an ultimate is accepted
 *       ({@code UbService.tryCast}).</li>
 *   <li><b>What a kill does</b> — {@link #extendOnKill}, called from {@code CombatEvents.onDeath}, the event
 *       that already pays kill charge. There is deliberately <b>no second "did something die" rule</b>: no
 *       listener of its own, no HP comparison, no scan for dead bodies. The kill this class reacts to is the
 *       kill the mod already recognised.</li>
 * </ol>
 *
 * <h2>The charge is emptied by the cast, not here</h2>
 * 「① 直接消耗掉全部」. Every accepted ultimate already writes {@code charge = 0} in
 * {@code UbTransaction.decide}, and that stays the single copy of "a cast empties the bar". So this class never
 * touches the gauge: the mode's lifetime is a <b>duration</b> the pack declares, and the emptying happens
 * whether or not the character has a second loop at all. A character with no second loop therefore behaves
 * exactly as she did.
 *
 * <h2>Two things it deliberately does not do</h2>
 * <ul>
 *   <li><b>No tick listener.</b> Nothing here runs on a clock; the deadline is compared when somebody asks.
 *       See {@link SecondRotationStore}.</li>
 *   <li><b>No per-second drain.</b> 「每秒消耗充能」 in the original game is what makes a full bar last the
 *       declared number of seconds; with the whole bar spent at once, that sentence has already been spent by
 *       {@code UbTransaction}, and modelling it again would drain a bar that is already empty.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class SecondRotationRuntime {

    private SecondRotationRuntime() {
    }

    /**
     * {@return the hooks this character runs for this event}
     *
     * <h2>Why the decision is here and not inside either resolver</h2>
     * {@code AffectionKernel.hooksAt} answers "which rung of <b>her</b> skills" — one question, one
     * implementation, and it must not learn about deadlines, because the book, the cast pad and the effect bar
     * all ask it for a set that does not depend on the fight. {@link SecondRotation#hooksAt} answers the same
     * question for the second loop. What depends on the fight is <b>which of the two belongs to this swing</b>,
     * and that is a fact about a player and a moment — this method's inputs.
     *
     * <p>The mode being live is asked through {@link SecondRotationStore#isActive}, the one deadline
     * comparison, so a test that moves {@code now} moves every reader at once.
     *
     * @param def the character whose hooks are wanted
     * @param ctx the hit being evaluated, carrying the attacker and the character id
     */
    public static List<EffectHook> hooksFor(@Nullable CharacterDef def, HookContext ctx) {
        if (def == null) {
            return List.of();
        }
        int tier = ctx.growth() == null ? GrowthFacts.FIRST_TIER : ctx.growth().tier();
        boolean second = SecondRotationStore.isActive(ctx.attacker().getUUID(), ctx.characterId(),
                ctx.attacker().level().getGameTime());
        return loopFor(def, tier, second);
    }

    /**
     * {@return the hook list one character runs, given whether her second loop is live}
     *
     * <h2>Why this is split out of {@link #hooksFor}</h2>
     * It is the whole of the two-loops rule and it holds <b>no game types</b>: a definition, a tier and a
     * boolean. {@link #hooksFor} adds the two live facts (this player's deadline, this moment) and calls
     * this. So the rule — including "a character with no second loop is unaffected" and "the rung picks the
     * second loop's own skill set" — is a unit test's subject with no server, no level and no clock.
     *
     * <p>A character whose pack declares no loop answers exactly what she answered before this feature
     * existed: {@code AffectionKernel.hooksAt} and nothing else.
     *
     * @param def    the character
     * @param tier   her affection tier
     * @param second whether her second loop is running right now
     */
    public static List<EffectHook> loopFor(@Nullable CharacterDef def, int tier, boolean second) {
        if (def == null) {
            return List.of();
        }
        if (!second) {
            return AffectionKernel.hooksAt(def, tier);
        }
        Optional<SecondRotation> spec = secondRotationOf(def);
        // A live deadline for a character with no declared loop cannot happen (only activate() writes one, and
        // it refuses without a declaration), but the fallback is the first loop rather than an empty list: the
        // one thing that must never happen is a character who suddenly has no skills at all.
        return spec.map(value -> value.hooksAt(tier)).orElseGet(() -> AffectionKernel.hooksAt(def, tier));
    }

    /**
     * {@return the damage multiplier this character's <b>live</b> second loop grants, {@code 1.0} when it is not running}
     *
     * <h2>「装备期间所有技能伤害都会增加物理伤害（大）」, and why the condition is not optional</h2>
     * The clause is a <b>conditional global bonus</b>: while she is in 行动模式2 every skill's damage is higher, and
     * outside it nothing changes. {@code SecondRotation.damageBonus} carries the number; this method answers
     * <em>whether it applies right now</em>, from the one deadline comparison every other reader of the mode uses
     * ({@link SecondRotationStore#isActive}), so a cast, a swing and a kill cannot disagree about whether the mode
     * was live. Writing the bonus unconditionally — "she owns the character, so +50%" — is exactly the 凭空变强 the
     * {@code extra} note refused, and it is what this method exists to make impossible.
     *
     * <p>The multiplier is composed by {@code MultiplierChain}, the mod's single per-hit composition point, so the
     * bonus is one more input to that chain rather than a second multiplication at a settlement site. The arithmetic
     * half — "what does the mode grant" — is {@link #modeBonusOf}.
     *
     * @param player      the player whose hit is being evaluated
     * @param characterId the character the hit is being attributed to
     * @param def         that character's loaded definition, {@code null} when the pack no longer defines it — the
     *                    caller already holds it ({@code CombatEvents.collect} resolves one per wheel slot), so this
     *                    method does not add a second registry read to the damage path
     */
    public static double modeBonusFor(@Nullable ServerPlayer player, @Nullable ResourceLocation characterId,
            @Nullable CharacterDef def) {
        if (player == null || characterId == null) {
            return GrowthMultiplier.NO_MODE_BONUS;
        }
        Optional<SecondRotation> spec = secondRotationOf(def);
        if (spec.isEmpty() || !(spec.get().damageBonus() > GrowthMultiplier.NO_MODE_BONUS)) {
            return GrowthMultiplier.NO_MODE_BONUS;   // no mode, or a mode that grants none: no lookup needed
        }
        return modeBonusOf(def,
                SecondRotationStore.isActive(player.getUUID(), characterId, player.level().getGameTime()));
    }

    /**
     * {@return the bonus {@code def}'s mode grants, given whether that mode is live right now}
     *
     * <h2>Why this is split out of {@link #modeBonusFor}</h2>
     * It is the whole of the conditional rule and it holds <b>no game types</b>: a definition and a boolean. So the
     * property the clause really is — <b>the bonus applies inside the mode and not outside it</b> — is a unit test's
     * subject with no server, no level and no clock, exactly as {@link #loopFor} is for the two-loops rule.
     * {@code modeBonusFor} adds the one live fact (this player's deadline, this moment) and calls this.
     *
     * @param def    the character, possibly {@code null}
     * @param active whether her second loop is running right now
     */
    public static double modeBonusOf(@Nullable CharacterDef def, boolean active) {
        if (!active) {
            return GrowthMultiplier.NO_MODE_BONUS;
        }
        Optional<SecondRotation> spec = secondRotationOf(def);
        if (spec.isEmpty()) {
            return GrowthMultiplier.NO_MODE_BONUS;
        }
        return spec.get().damageBonus();
    }

    /**
     * Starts this character's second loop after her ultimate resolved.
     *
     * <h2>Why the rotation cursor is reset here</h2>
     * 「入场2 → SP1」: entering the second loop starts at its <b>first</b> skill. The cursor is
     * {@link SkillRotation}'s and lives in {@link SkillRotationStore} — one copy, one period rule — so this
     * method does not create a second cursor; it puts that store's {@link SkillRotation#START} back, which is
     * the same write a logout performs. A character who enters the loop therefore fires SP1 on her very next
     * swing rather than continuing at an index that meant something in the other loop's period.
     *
     * <p>Called <b>after</b> the cast has been paid for and settled: the mode is a consequence of the
     * ultimate, so an ultimate that was refused must not start one. A cast whose pack declares no second loop,
     * or declares one with no duration, is an ordinary cast and returns {@code false}.
     *
     * @param caster      the player who cast
     * @param characterId which character cast
     * @param def         her definition, already resolved by the caller
     * @return whether a mode was started
     */
    public static boolean activate(ServerPlayer caster, ResourceLocation characterId,
            @Nullable CharacterDef def) {
        Optional<SecondRotation> second = secondRotationOf(def);
        if (second.isEmpty()) {
            return false;   // every character but one: an ordinary ultimate
        }
        SecondRotation spec = second.get();
        long now = caster.level().getGameTime();
        if (!SecondRotationStore.begin(caster.getUUID(), characterId, now, spec.seconds())) {
            // A declared loop with no usable clock is a pack defect, not a runtime one: report it once per
            // (character) through WarnOnce, and leave the player on her first loop rather than in a mode
            // that never ends.
            if (REPORTED.first("second_rotation/" + characterId)) {
                Astrum.LOGGER.warn("astrum: {} declares ub.secondRotation but its seconds is {} — she stays on "
                        + "her first loop; write a positive duration", characterId, spec.seconds());
            }
            return false;
        }
        SkillRotationStore.put(caster.getUUID(), characterId, SkillRotation.START);
        return true;
    }

    /**
     * Extends every running second loop the killer's party is in — 「+2 秒 / 击杀，无上限」.
     *
     * <h2>Why the party is walked rather than one character named</h2>
     * A kill is the <b>player's</b>, not one character's: every character on the wheel rides the same hit
     * (that is the frame {@code CombatEvents.collect} evaluates all five slots in), so a kill pays every
     * character who is in her second loop at that moment. Naming a single character here would need a rule
     * for "which one", and no rule was given.
     *
     * <p>{@link SecondRotationStore#extend} refuses a character whose mode is not live, so a kill by a player
     * whose muimi is on her first loop extends nothing — 「击杀单位增加持续时间」 is about the loop's duration,
     * not about banking time.
     *
     * @param killer the player who killed, already server-side
     * @return how many characters' loops were extended, for the log line
     */
    public static int extendOnKill(ServerPlayer killer) {
        var registry = com.astrum.pcrd.api.v1.AstrumApi.characters();
        if (registry.isEmpty()) {
            return 0;
        }
        long now = killer.level().getGameTime();
        int extended = 0;
        Party party = killer.getData(Attachments.PARTY);
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            Optional<ResourceLocation> value = party.slots().get(slot);
            if (value.isEmpty()) {
                continue;
            }
            ResourceLocation characterId = value.get();
            CharacterDef def = registry.get().get(characterId);
            Optional<SecondRotation> second = secondRotationOf(def);
            if (second.isEmpty()) {
                continue;
            }
            if (SecondRotationStore.extend(killer.getUUID(), characterId, now,
                    second.get().secondsPerKill())) {
                extended++;
            }
        }
        return extended;
    }

    /**
     * {@return {@code def}'s declared second loop, or empty}
     *
     * <p>The null-safe door for the whole feature. {@code CharacterDef.ub()} is non-null in a decoded
     * definition, but {@code CharacterRegistry.INVALID} and test fixtures are not the only callers that can
     * hand a partial shape in, and this runs inside a damage sequence where a {@code NullPointerException}
     * would cost the hit. Whether a block describes a loop <em>at all</em> is {@code UbDef.secondRotationOf}'s
     * answer, so that decision exists once.
     */
    public static Optional<SecondRotation> secondRotationOf(@Nullable CharacterDef def) {
        if (def == null || def.ub() == null) {
            return Optional.empty();
        }
        return def.ub().secondRotationOf();
    }

    /**
     * Hooks already reported as unable to start a loop, keyed {@code second_rotation/<character>}.
     *
     * <p>Bounded by the loaded pack's characters, like {@code HookEvaluator.REPORTED}, and for the same
     * reason: a malformed declaration belongs to the datapack, not to the cast that revealed it.
     */
    private static final WarnOnce REPORTED = new WarnOnce();
}
