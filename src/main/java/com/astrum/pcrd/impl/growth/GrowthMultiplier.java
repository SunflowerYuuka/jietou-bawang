package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * What one character's <b>level and affection</b> are worth as a multiplier, ready to be composed with
 * the party's diminishing multiplier (spec §6.1, §6.2).
 *
 * <h2>Why this is a value and not two calls at the composition site</h2>
 * The combat pipeline needs one number per hook, and it needs it while it is evaluating a wheel slot.
 * Asking "what level is this character" and "what tier is this" there would be the second
 * implementation of both rules — the exact duplication {@code GrowthFacts}' javadoc warns about. So the
 * answers are resolved once, from the stored progress and the datapack definition, and travel as this
 * record:
 *
 * <ul>
 *   <li>{@link #level} — {@link ExperienceKernel#levelMultiplier(CharacterProgress, com.astrum.pcrd.api.v1.GrowthDef)},
 *       i.e. {@code 1 + 0.02 × (level − 1)}. Already clamped to {@code maxLevel} by the kernel.</li>
 *   <li>{@link #affection} — the character's definition, kept as data because the tier-2 modifier is
 *       <b>per hook index</b>: only the hooks the pack named are scaled, so the factor cannot be one
 *       number for the whole slot.</li>
 *   <li>{@link #tier} — {@link AffectionKernel#tier(int, AffectionDef)}, so a tier-1 character scales
 *       nothing and a tier-3 character keeps its tier-2 numbers <em>and</em> swaps in the enhanced
 *       ultimate.</li>
 * </ul>
 *
 * <p>{@link #NEUTRAL} is the "this build cannot say" answer — an unreadable book, a party slot the
 * roster does not own, a character the loaded pack no longer defines. It is {@code 1.0}, never
 * {@code 0}: a zero would switch a character's hooks off, which is the fail-visible half of the same
 * mistake a default level would make (the same reasoning {@code ExperienceKernel.levelMultiplier}
 * documents for its {@code null} case).
 *
 * <p>The <b>composition</b> with the diminishing multiplier does not happen here: it is one expression
 * in {@code impl.combat.MultiplierChain}, so there is exactly one chain in the game.
 */
@ApiStatus.Internal
public record GrowthMultiplier(double level, @Nullable AffectionDef affection, int tier) {

    /** Nothing known about the character, or a character with no growth at all: exactly {@code 1.0}. */
    public static final GrowthMultiplier NEUTRAL = new GrowthMultiplier(1.0, null, GrowthFacts.FIRST_TIER);

    /**
     * Normalises hostile input: a level factor that is non-finite or below 1 is read as {@code 1.0}
     * (growth can only add), and the tier is clamped into the ladder of {@link GrowthFacts}.
     */
    public GrowthMultiplier {
        if (!Double.isFinite(level) || level < 1.0) {
            level = 1.0;
        }
        tier = Math.clamp(tier, GrowthFacts.FIRST_TIER, GrowthFacts.MAX_TIER);
    }

    /**
     * {@return the growth of one owned character, or {@link #NEUTRAL} when either half is unknown}
     *
     * <p>{@code null} on either argument means the same thing: this build cannot say what that
     * character is worth, so it changes nothing. Notably {@code progress == null} is the quarantined
     * roster and the "equipped but not owned" party slot.
     *
     * @param progress the stored progress, or {@code null} when unknown
     * @param def      the character's definition, or {@code null} when the pack does not define it
     */
    public static GrowthMultiplier of(@Nullable CharacterProgress progress, @Nullable CharacterDef def) {
        if (progress == null || def == null) {
            return NEUTRAL;
        }
        return new GrowthMultiplier(ExperienceKernel.levelMultiplier(progress, def.growth()),
                def.affection(), AffectionKernel.tier(progress.affection(), def.affection()));
    }
}
