package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.astrum.pcrd.impl.growth.GrowthMultiplier;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>one</b> multiplier chain of the attachment pipeline (spec §5.2, §6.1, §6.2).
 *
 * <h2>The formula</h2>
 * <pre>
 * effective(slot, hook) = diminishing(slot) × level(character) × affectionHook(character, hook)
 * </pre>
 *
 * <ul>
 *   <li>{@code diminishing(slot)} — {@code PartyMultipliers}, per <b>wheel slot</b>: the 5th
 *       same-dimension character is worth less (spec §5.2). Clamped into {@code [0.1, 1.0]} there.</li>
 *   <li>{@code level(character)} — {@code ExperienceKernel.levelMultiplier}, per <b>character</b>:
 *       {@code 1 + 0.02 × (level − 1)} (spec §6.1's 数值).</li>
 *   <li>{@code affectionHook(character, hook)} — {@code AffectionKernel.hookMultiplier}, per
 *       <b>hook</b>: {@code 1.0} below tier 2, and at tier 2 only the hooks the pack listed in
 *       {@code tier2HookModifiers} are scaled; tier 3 keeps those numbers and additionally swaps the
 *       ultimate (that swap is {@code UbService.atTier3}, not a multiplier).</li>
 * </ul>
 *
 * <h2>Why exactly one place</h2>
 * Three factors with three different scopes (slot, character, hook) invite three separate
 * multiplications sprinkled along the pipeline — one at the party table, one at the effect executor,
 * one at the charge sink — and then a reviewer has to prove that no path applies two of them twice or
 * skips one. So the product is computed here and in exactly one call site:
 * {@code HookEvaluator.evaluate}, at the moment a hook is about to run (the only moment the hook's
 * index is known). Everything downstream — every one of the eight effect executors — reads the single
 * number {@code HookContext.multiplier()} and multiplies by nothing else.
 *
 * <h2>Hostile data</h2>
 * Config, saves and datapacks are all editable by hand. A non-finite or non-positive product (a
 * {@code NaN} from a hand-edited {@code mult}, an infinity from absurd data) is <b>not</b> passed on:
 * the diminishing factor alone is returned, so broken growth data leaves a character exactly as strong
 * as the party table says instead of <em>silently switching its hooks off</em> (§5.5).
 */
@ApiStatus.Internal
public final class MultiplierChain {

    private MultiplierChain() {
    }

    /**
     * {@return the multiplier one character's hook {@code hookIndex} runs with}
     *
     * @param diminishing this slot's diminishing multiplier, from
     *                    {@link PartyMultipliers#all(java.util.List, java.util.Map, double, boolean)}
     * @param growth      the character's growth, from
     *                    {@link GrowthMultiplier#of(com.astrum.pcrd.api.v1.CharacterProgress,
     *                    com.astrum.pcrd.api.v1.CharacterDef)}
     * @param hookIndex   the hook's index into the character's {@code effects[]}, i.e. the index a
     *                    {@code HookModifier} names
     */
    public static double effective(double diminishing, GrowthMultiplier growth, int hookIndex) {
        if (growth == null) {
            return diminishing;
        }
        double affectionHook = AffectionKernel.hookMultiplier(growth.affection(), hookIndex, growth.tier());
        return compose(diminishing, growth.level(), affectionHook);
    }

    /**
     * {@return {@code diminishing × level × affectionHook}, or {@code diminishing} when the growth
     * factors cannot produce a usable number}
     *
     * <p>Split out so the arithmetic — including the two rejection rules — is testable without a
     * character definition, and so {@link #effective} stays one line of policy.
     */
    static double compose(double diminishing, double level, double affectionHook) {
        if (!(diminishing > 0.0) || !Double.isFinite(diminishing)) {
            return 0.0;
        }
        if (!(level > 0.0) || !Double.isFinite(level)
                || !(affectionHook > 0.0) || !Double.isFinite(affectionHook)) {
            Astrum.LOGGER.debug("astrum: growth multiplier of {} x {} is unusable, keeping the party "
                    + "multiplier {} alone", level, affectionHook, diminishing);
            return diminishing;
        }
        double product = diminishing * level * affectionHook;
        if (!(product > 0.0) || !Double.isFinite(product)) {
            return diminishing;
        }
        return product;
    }
}
