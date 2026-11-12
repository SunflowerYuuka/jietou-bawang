package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.GrowthDef;
import java.util.OptionalInt;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>display half</b> of growth: how full a bar is, and which affection tier a number is.
 *
 * <h2>What this deliberately is not</h2>
 * It is not the level-up math. Nothing here gains exp, carries a remainder, caps a character at
 * {@code maxLevel} or splits kill experience — that is Task 15's {@code ExperienceKernel} /
 * {@code AffectionKernel}. This class exists because the 角色册 screen has to draw an exp bar and say
 * "tier 2" <em>today</em>, and the alternative was to write the curve and the tier ladder a second
 * time inside a renderer — the exact duplication that this project has been bitten by three times
 * (two copies of "may I cast", two copies of a refusal window, two copies of a rank scan).
 *
 * <p>So there is exactly one implementation of the curve ({@link #expToNext}) and of the tier ladder
 * ({@link #tier}), and it lives here, in the package the growth task owns. Task 15's
 * {@code ExperienceKernel.expToNext} and {@code AffectionKernel.tier} are expected to delegate to
 * these two methods rather than restate them.
 *
 * <h2>Why the numbers are computed on the server</h2>
 * The roster sync payload carries {@code expToNext} and {@code tier} per character (see
 * {@code RosterSyncPayload.Entry}) precisely so the client never runs either rule: it receives a
 * number and fills a bar with it. That keeps "one implementation" true for the client too, and it
 * means a client whose pack was reloaded mid-session cannot disagree with the server about a tier.
 *
 * <h2>Fractions are clamped, never assumed</h2>
 * A hand-edited save can hold {@code exp} above the current level's requirement, a pack can set
 * {@code expPerLevel} to 1.0, and a character can reach the last tier and keep the number climbing
 * (the affection clamp is the command's and Task 15's business, not this class's). A bar that divided
 * without clamping would draw past its frame at best and divide by zero at worst, so every fraction
 * here comes back in {@code [0, 1]}.
 */
@ApiStatus.Internal
public final class GrowthFacts {

    /** The last affection tier the design has (spec §6.2: tier 3 is the last tier). */
    public static final int MAX_TIER = 3;

    /** Tier of a character that has not reached the first threshold. */
    public static final int FIRST_TIER = 1;

    /** What {@link #affectionFraction} returns when the pack defines no tier 3: nothing left to earn. */
    public static final double FULLY_GROWN = 1.0;

    /** What {@link #expFraction} returns when there is no requirement to divide by. */
    public static final double NO_PROGRESS = 0.0;

    private GrowthFacts() {
    }

    /**
     * {@return the experience level {@code level} needs to reach the next level}
     *
     * <p>Spec §6.2's curve: {@code expBase * expPerLevel^(level-1)}, rounded. The single
     * implementation — see the class comment.
     *
     * <p>Defensive inputs, because a datapack is data: a level below 1 is read as 1 (there is no level
     * 0), and a non-positive base yields 0 rather than a negative requirement.
     *
     * @param level  the character's current level, normally {@code >= 1}
     * @param growth the character's curve
     */
    public static int expToNext(int level, GrowthDef growth) {
        int current = Math.max(1, level);
        if (growth.expBase() <= 0) {
            return 0;
        }
        double required = growth.expBase() * Math.pow(growth.expPerLevel(), current - 1);
        if (!Double.isFinite(required) || required <= 0.0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.round(required));
    }

    /**
     * {@return the affection tier {@code affection} is in: 1, 2 or 3}
     *
     * <p>A threshold of {@code 0} or less means "the pack defines no such tier", so it can never be
     * reached — the same reading {@code UbService.atTier3} uses for its threshold, which is why a pack
     * without a tier 3 shows a bar as full rather than promising a tier that does not exist.
     *
     * @param affection the character's affection (1 for a freshly enrolled character)
     * @param def       that character's affection definition
     */
    public static int tier(int affection, AffectionDef def) {
        if (def.tier3Threshold() > 0 && affection >= def.tier3Threshold()) {
            return 3;
        }
        if (def.tier2Threshold() > 0 && affection >= def.tier2Threshold()) {
            return 2;
        }
        return FIRST_TIER;
    }

    /**
     * {@return the threshold of the tier above the one {@code affection} is in, or empty at the top}
     *
     * <p>Used by the growth view's text ("goodwill 5, next tier at 8"). Empty means "there is nothing
     * further to reach", which is a different sentence from "0 more needed".
     *
     * @param affection the character's affection
     * @param def       that character's affection definition
     */
    public static OptionalInt nextTierThreshold(int affection, AffectionDef def) {
        int tier = tier(affection, def);
        if (tier == FIRST_TIER && def.tier2Threshold() > 0) {
            return OptionalInt.of(def.tier2Threshold());
        }
        if (tier == 2 && def.tier3Threshold() > 0) {
            return OptionalInt.of(def.tier3Threshold());
        }
        return OptionalInt.empty();
    }

    /**
     * {@return how full the experience bar is, {@code [0, 1]}}
     *
     * @param exp       experience into the current level
     * @param expToNext what that level needs, from {@link #expToNext}
     */
    public static double expFraction(int exp, int expToNext) {
        if (expToNext <= 0) {
            return NO_PROGRESS;
        }
        return clamp((double) exp / (double) expToNext);
    }

    /**
     * {@return how far the affection bar has come from tier 1 towards the last tier, {@code [0, 1]}}
     *
     * @param affection the character's affection
     * @param def       that character's affection definition
     */
    public static double affectionFraction(int affection, AffectionDef def) {
        if (def.tier3Threshold() <= 0) {
            return FULLY_GROWN;
        }
        return clamp((double) affection / (double) def.tier3Threshold());
    }

    /**
     * {@return one character's bar-ready snapshot of a progress record}
     *
     * <p>Convenience for the sync path, so the payload builder does not repeat the pairing of
     * {@link #expToNext} with {@link #expFraction}'s denominator.
     *
     * @param progress the stored progress
     * @param growth   the character's curve
     */
    public static int expToNext(CharacterProgress progress, GrowthDef growth) {
        return expToNext(progress.level(), growth);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
