package com.astrum.pcrd.impl.growth;

import org.jetbrains.annotations.ApiStatus;

/**
 * <b>How many effect tiers a level grants</b> — the four bands, in one place.
 *
 * <h2>What the human asked for</h2>
 * 「Level-granted effect tiers」, with {@code maxLevel} raised to 100: a character's <em>level</em> is worth
 * effect tiers, on top of the tier her skill declares in the datapack. The four bands:
 *
 * <pre>
 *   level   1–30   +0
 *   level  31–60   +1
 *   level  61–90   +2
 *   level  91–100  +3
 * </pre>
 *
 * <p>The bonus is <b>added to the declared tier and then clamped to the effect type's own maximum</b>
 * ({@link com.astrum.pcrd.api.v1.EffectTiers#resolve}). So a {@code buff_attack_area} declared at tier 4 on
 * a level-95 character is {@code min(4 + 3, 5) = 5}: the ladder's ceiling wins, and the character is not
 * given a sixth rung that does not exist. That clamp is the reason this class answers a <em>bonus</em> rather
 * than a tier: the ceiling belongs to the effect, not to the level.
 *
 * <h2>Why one implementation</h2>
 * Three surfaces want this number — the hook executor, the ultimate's effect settlement, and anything that
 * later shows a resolved tier — and this project has paid three times for one rule with two copies. So the
 * table lives here, {@link GrowthFacts#bandBonus(int)} and {@link AffectionKernel#bandBonus(int)} delegate to
 * it, and the one place that composes it into a tier is
 * {@code impl.effect.TieredAmounts#resolve(EffectType, TieredParams, int)}.
 *
 * <p>It is <b>not</b> a growth multiplier: a tier is a strength, not a quantity, so nothing here multiplies
 * anything. It is the same 质/量 split {@code docs/balance-derivation.md} §5.2 draws for the diminishing
 * chain — the level <em>factor</em> multiplies quantities ({@code ExperienceKernel.levelMultiplier}), and the
 * level <em>band</em> raises tiers.
 *
 * <h2>EX is deliberately not in here</h2>
 * The human was explicit: 「EX gets no level bonus」. An EX skill's strength comes from its own formula's
 * {@code +perLevel × 技能等级} term and from nothing else, so the EX path
 * ({@code impl.effect.ExRungs#tierAt}) resolves its rung without ever calling this class. A test pins that:
 * two levels that straddle a band boundary produce the same EX rung.
 *
 * <h2>Hostile input</h2>
 * Saves and datapacks are hand-editable, so a level below 1 answers {@code 0} (there is no level 0 and no
 * penalty band), and a level above the last band answers {@link #BONUS_AT_TOP} rather than extrapolating —
 * as far as this rule is concerned, level 400 is a very high level-91 character.
 */
@ApiStatus.Internal
public final class LevelBands {

    /** The first level of the second band: {@code +1}. */
    public static final int BAND_ONE_FROM = 31;

    /** The first level of the third band: {@code +2}. */
    public static final int BAND_TWO_FROM = 61;

    /** The first level of the last band: {@code +3}, and the level {@code maxLevel} now reaches. */
    public static final int BAND_THREE_FROM = 91;

    /** What the last band is worth. Anything above {@link #BAND_THREE_FROM} answers the same. */
    public static final int BONUS_AT_TOP = 3;

    /** What the first three bands are worth: nothing, the white board every skill starts as. */
    public static final int BONUS_AT_BOTTOM = 0;

    /**
     * The level every shipped character is now capped at.
     *
     * <p>Named here rather than only in the four character JSONs because the bands above stop at exactly
     * this number: a curve that reached level 140 would leave three bands unreachable and the last one
     * meaningless. {@code docs/balance-derivation.md} §15.1 states the curve that reaches it.
     */
    public static final int MAX_LEVEL = 100;

    private LevelBands() {
    }

    /**
     * {@return how many tiers a character at {@code level} adds to every tiered effect she applies}
     *
     * <p>Never throws and never answers a negative: this is reached from a damage sequence, where a hostile
     * level in a hand-edited save must not become a lost swing.
     *
     * @param level the character's level; anything below 1 is read as the first band
     */
    public static int bonusAt(int level) {
        if (level >= BAND_THREE_FROM) {
            return BONUS_AT_TOP;
        }
        if (level >= BAND_TWO_FROM) {
            return 2;
        }
        if (level >= BAND_ONE_FROM) {
            return 1;
        }
        return BONUS_AT_BOTTOM;
    }

    /**
     * {@return {@code declared + }{@link #bonusAt}, <b>unclamped</b>}
     *
     * <p>The addition only. The clamp belongs to the effect type's ladder
     * ({@code EffectTiers.resolve}), because a bonus cannot know how many rungs an effect has, and clamping
     * twice would be two answers to "what is the highest rung".
     *
     * @param declared the tier the datapack declared, as written
     * @param level    the character's level
     */
    public static int tierFor(int declared, int level) {
        return declared + bonusAt(level);
    }
}
