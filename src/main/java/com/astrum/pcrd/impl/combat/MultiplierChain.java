package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>one</b> multiplier chain of the attachment pipeline (spec §5.2, §6.1, §6.2).
 *
 * <h2>The formula</h2>
 * <pre>
 * effective(slot, character) = diminishing(slot) × level(character) × mode(character, now)
 * </pre>
 *
 * <ul>
 *   <li>{@code diminishing(slot)} — {@code PartyMultipliers}, per <b>wheel slot</b>: the 5th
 *       same-dimension character is worth less (spec §5.2). Clamped into {@code [0.1, 1.0]} there.</li>
 *   <li>{@code level(character)} — {@code ExperienceKernel.levelMultiplier}, per <b>character</b>:
 *       {@code 1 + 0.02 × (level − 1)} (spec §6.1's 数值).</li>
 *   <li>{@code mode(character, now)} — the multiplier a <b>live combat mode</b> adds, {@code 1.0} when none is
 *       running. It is the third factor for the same reason the second one is: 矛依未's 「装备期间所有技能伤害都会增加
 *       物理伤害（大）」 is a per-hit bonus conditioned on a state, and this chain is the mod's <b>one</b> place a
 *       per-hit factor is composed. Applied anywhere else it would be a second multiplication, and then "is the
 *       bonus in twice?" would be unanswerable. It carries no growth: the state is read per hit by the caller
 *       ({@code CombatEvents.collect} → {@code SecondRotationRuntime}), and this method only multiplies.</li>
 * </ul>
 *
 * <h2>What left this chain, and why that is the point of task 35</h2>
 * There used to be a third factor, {@code affectionHook(character, hookIndex)}: an affection-tier multiplier
 * applied <b>per hook</b> from the pack's {@code tier2HookModifiers}. It is <b>gone</b>, and its removal is
 * the change the whole affection rework consists of. The real game's 专武 and 6★ rungs do not scale a skill,
 * they <b>replace</b> it — different coefficient, different constant, sometimes an extra clause (姬塔's 技能1
 * gains a TP grant at 专武 and doubles its coefficient again at 6★). {@code AffectionKernel.hooksAt} now
 * resolves which hook list a tier runs, so by the time this chain is asked for a number there is nothing
 * left for a tier to multiply: the hook <em>is</em> the tier's version.
 *
 * <p>Two consequences worth stating, because both look like omissions:
 * <ul>
 *   <li><b>The function no longer takes a hook index.</b> That parameter existed only for the per-hook
 *       modifier. Keeping it would be a parameter the body does not read — the kind of signature a later
 *       author "restores" a multiplier into.</li>
 *   <li><b>Affection still reaches combat</b>, through two doors: which hooks run
 *       ({@code AffectionKernel.hooksAt}) and which ultimate is casted ({@code AffectionKernel.usesEnhancedUb}
 *       → {@code UbService}). Neither is a multiplication, and neither belongs here.</li>
 * </ul>
 *
 * <h2>Why exactly one place</h2>
 * Two factors with two different scopes (slot, character) invite two separate multiplications sprinkled
 * along the pipeline — one at the party table, one at the effect executor — and then a reviewer has to prove
 * that no path applies two of them twice or skips one. So the product is computed here and in exactly one
 * call site: {@code HookContext.forCharacter}. Everything downstream — every effect executor — reads the
 * single number {@code HookContext.multiplier()} and multiplies by nothing else.
 *
 * <h2>Hostile data</h2>
 * Config, saves and datapacks are all editable by hand. A non-finite or non-positive product (a {@code NaN}
 * from a hand-edited save, an infinity from absurd data) is <b>not</b> passed on: the diminishing factor
 * alone is returned, so broken growth data leaves a character exactly as strong as the party table says
 * instead of <em>silently switching its hooks off</em> (§5.5).
 */
@ApiStatus.Internal
public final class MultiplierChain {

    private MultiplierChain() {
    }

    /**
     * {@return the multiplier one character's hooks run with}
     *
     * @param diminishing this slot's diminishing multiplier, from
     *                    {@link PartyMultipliers#all(java.util.List, java.util.Map, double, boolean)}
     * @param growth      the character's level factor <b>and</b> the multiplier of any combat mode that is live for
     *                    her right now, from {@link GrowthMultiplier#of(
     *                    com.astrum.pcrd.api.v1.CharacterProgress, com.astrum.pcrd.api.v1.CharacterDef)} and
     *                    {@link GrowthMultiplier#inMode(double)}
     */
    public static double effective(double diminishing, GrowthMultiplier growth) {
        if (growth == null) {
            return diminishing;
        }
        // The mode factor is folded into the same factor before composing, rather than multiplying the product
        // afterwards: `compose` already answers the "unusable factor" question for a level, and the mode is the same
        // kind of number (a factor at or above 1). `GrowthMultiplier` has already normalised a hostile mode to 1.
        return compose(diminishing, growth.level() * growth.modeBonus());
    }

    /**
     * {@return {@code diminishing × level}, or {@code diminishing} when the level factor is unusable}
     *
     * <p>Split out so the arithmetic — including the two rejection rules — is testable without a character
     * definition, and so {@link #effective} stays one line of policy.
     */
    static double compose(double diminishing, double level) {
        if (!(diminishing > 0.0) || !Double.isFinite(diminishing)) {
            return 0.0;
        }
        if (!(level > 0.0) || !Double.isFinite(level)) {
            Astrum.LOGGER.debug("astrum: level multiplier {} is unusable, keeping the party multiplier {} "
                    + "alone", level, diminishing);
            return diminishing;
        }
        double product = diminishing * level;
        if (!(product > 0.0) || !Double.isFinite(product)) {
            return diminishing;
        }
        return product;
    }
}
