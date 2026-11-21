package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * What one character's <b>level and affection tier</b> are worth to the combat pipeline, ready to be composed
 * with the party's diminishing multiplier (spec §6.1, §6.2).
 *
 * <h2>Why this is a value and not three calls at the composition site</h2>
 * The pipeline needs one number per hook, and it needs it while it is evaluating a wheel slot. Asking "what
 * level is this character" and "what tier is this" there would be the second implementation of both rules —
 * the exact duplication {@code GrowthFacts}' javadoc warns about. So the answers are resolved once, from the
 * stored progress and the datapack definition, and travel as this record:
 *
 * <ul>
 *   <li>{@link #level} — {@link ExperienceKernel#levelMultiplier(CharacterProgress, com.astrum.pcrd.api.v1.GrowthDef)},
 *       i.e. {@code 1 + 0.02 × (level − 1)}. Already clamped to {@code maxLevel} by the kernel. This is the
 *       <b>only</b> factor that multiplies.</li>
 *   <li>{@link #tier} — {@link AffectionKernel#tier(int, com.astrum.pcrd.api.v1.AffectionDef)}. Carried
 *       because affection no longer scales anything: it <b>selects</b> which skill set runs
 *       ({@code AffectionKernel.hooksAt}) and which ultimate is cast. The combat pipeline needs the tier for
 *       that selection, not for arithmetic.</li>
 * </ul>
 *
 * <h2>What this record used to carry, and why it no longer does</h2>
 * It held the character's whole {@code AffectionDef}, because the old tier-2 model scaled <em>per hook
 * index</em> and the factor therefore could not be one number for the slot. With the multiplier gone there is
 * nothing per-hook left to look up, so holding the definition would be holding data the pipeline does not
 * read — and the {@code HookModifier} type it needed is deleted.
 *
 * <p>{@link #NEUTRAL} is the "this build cannot say" answer — an unreadable book, a party slot the roster
 * does not own, a character the loaded pack no longer defines. It is {@code 1.0}, never {@code 0}: a zero
 * would switch a character's hooks off, which is the fail-visible half of the same mistake a default level
 * would make (the same reasoning {@code ExperienceKernel.levelMultiplier} documents for its {@code null}
 * case).
 *
 * <p>The <b>composition</b> with the diminishing multiplier does not happen here: it is one expression in
 * {@code impl.combat.MultiplierChain}, so there is exactly one chain in the game.
 *
 * @param level the level factor, {@code >= 1.0}
 * @param tier  the affection tier, 1–3, for the skill-set and ultimate selection
 * @param bandBonus how many effect tiers this level grants, 0–3, from {@link LevelBands#bonusAt}
 * @param modeBonus the multiplier a <b>live combat mode</b> adds on top of the level, {@code 1.0} when none is
 *                  running — 矛依未's 行动模式2 「装备期间所有技能伤害都会增加物理伤害（大）」. It is <b>not</b> growth: it
 *                  has no level behind it and it ends when the mode does. It travels here because this record is
 *                  what {@code MultiplierChain} — the mod's one composition point — is fed, so the bonus is an input
 *                  to the single chain instead of a second multiplication beside it
 */
@ApiStatus.Internal
public record GrowthMultiplier(double level, int tier, int bandBonus, double modeBonus) {

    /**
     * The mode factor of "no combat mode is running": exactly {@code 1.0}, i.e. the identity of the multiplier
     * chain. Named so the dozens of places that construct a growth without a mode say so, instead of passing a
     * bare {@code 1.0} that reads like a level.
     */
    public static final double NO_MODE_BONUS = 1.0;

    /** Nothing known about the character, or a character with no growth at all: exactly {@code 1.0}. */
    public static final GrowthMultiplier NEUTRAL =
            new GrowthMultiplier(1.0, GrowthFacts.FIRST_TIER, LevelBands.BONUS_AT_BOTTOM, NO_MODE_BONUS);

    /**
     * Normalises hostile input: a level factor that is non-finite or below 1 is read as {@code 1.0}
     * (growth can only add), the tier is clamped into the ladder of {@link GrowthFacts}, and the band is
     * clamped into the band ladder so a hand-edited save cannot ask for a fourth band that does not exist.
     */
    public GrowthMultiplier {
        if (!Double.isFinite(level) || level < 1.0) {
            level = 1.0;
        }
        tier = Math.clamp(tier, GrowthFacts.FIRST_TIER, GrowthFacts.MAX_TIER);
        bandBonus = Math.clamp(bandBonus, LevelBands.BONUS_AT_BOTTOM, LevelBands.BONUS_AT_TOP);
        // 「+50% while the mode runs」 can only ever add: a value below 1, a NaN or an infinity is read as "no bonus"
        // rather than as a nerf no pack asked for. Same one-directional reading as the level factor above.
        if (!Double.isFinite(modeBonus) || modeBonus < 1.0) {
            modeBonus = 1.0;
        }
    }

    /**
     * The two-field form: a growth whose <b>level</b> factor is known but whose band is not.
     *
     * <p>Used by callers that construct a growth from a tier alone (a test seam, a character with no stored
     * progress). It reads as "the first band", which is what a level-1 character has — and it is deliberately
     * <b>not</b> a made-up level, because inventing one would grant bands nobody earned.
     */
    public GrowthMultiplier(double level, int tier) {
        this(level, tier, LevelBands.BONUS_AT_BOTTOM, NO_MODE_BONUS);
    }

    /**
     * The pre-{@code modeBonus} shape: a growth whose level, tier and band are known and which carries no combat
     * mode. Every caller that predates 矛依未's 行动模式2 bonus uses this, and it means exactly what it meant then.
     */
    public GrowthMultiplier(double level, int tier, int bandBonus) {
        this(level, tier, bandBonus, NO_MODE_BONUS);
    }

    /**
     * {@return the same growth with a live <b>combat-mode</b> bonus folded in}
     *
     * <h2>What this is for, and why it lives here rather than in a second multiplier</h2>
     * 矛依未's 6★ 「装备期间所有技能伤害都会增加物理伤害（大）」 is a global damage bonus that exists only
     * <b>while her second loop runs</b>. The pipeline already has exactly one place where per-hit factors are
     * composed ({@link com.astrum.pcrd.impl.combat.MultiplierChain#effective}), and this record is what travels
     * into it, so the bonus is one more <em>input</em> to the one chain rather than a second multiplication at
     * the settlement sites — the shape that would make "is it applied twice?" unanswerable.
     *
     * <p><b>The condition is the caller's.</b> This method knows nothing about modes; it takes the factor a caller
     * <em>measured</em> from a live mode. That keeps the "when is it live" rule in
     * {@code SecondRotationStore.isActive} and the arithmetic here, so a test can assert either without the other.
     *
     * @param bonus the mode's damage multiplier, {@code 1.0} for "not in a mode"; anything below 1 or non-finite
     *              is read as {@code 1.0}, because a mode can only grant
     */
    public GrowthMultiplier inMode(double bonus) {
        return new GrowthMultiplier(level, tier, bandBonus, bonus);
    }


    /**
     * {@return the growth of one owned character, or {@link #NEUTRAL} when either half is unknown}
     *
     * <p>{@code null} on either argument means the same thing: this build cannot say what that character is
     * worth, so it changes nothing. Notably {@code progress == null} is the quarantined roster and the
     * "equipped but not owned" party slot.
     *
     * @param progress the stored progress, or {@code null} when unknown
     * @param def      the character's definition, or {@code null} when the pack does not define it
     */
    public static GrowthMultiplier of(@Nullable CharacterProgress progress, @Nullable CharacterDef def) {
        if (progress == null || def == null) {
            return NEUTRAL;
        }
        return new GrowthMultiplier(ExperienceKernel.levelMultiplier(progress, def.growth()),
                AffectionKernel.tier(progress.affection(), def.affection()),
                LevelBands.bonusAt(progress.level()), NO_MODE_BONUS);
    }
}
