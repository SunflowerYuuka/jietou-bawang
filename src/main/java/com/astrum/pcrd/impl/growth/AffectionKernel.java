package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.astrum.pcrd.api.v1.SkillUpgrade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * The <b>earning half</b> of affection: which tier a number is in, and <b>which skill set that tier runs</b>.
 *
 * <h2>The ladder, in the human's words</h2>
 * <table border="1">
 *   <caption>Three rungs, and what each one buys</caption>
 *   <tr><th>tier</th><th>= in the real game</th><th>effect</th></tr>
 *   <tr><td>1</td><td>白板</td><td>every skill at its base version</td></tr>
 *   <tr><td><b>2</b></td><td><b>专武 (unique weapon) / 5★</b></td>
 *       <td>every skill whose upgraded name ends in {@code +} switches to that version, and the
 *           <b>name shown gains the {@code +}</b>. Skills with no {@code +} version stay as they are.</td></tr>
 *   <tr><td><b>3</b></td><td><b>六星开花 (6★)</b></td>
 *       <td>every skill whose <b>name changes</b> switches to the renamed version. <b>A rename supersedes a
 *           {@code +}</b>.</td></tr>
 * </table>
 *
 * <h2>Why this replaced the multiplier</h2>
 * This class used to answer {@code hookMultiplier(def, hookIndex, tier)}: a factor applied to a hook's
 * numbers. That model cannot express the game. 姬塔's 技能1 has three versions with three different
 * coefficients, three different constants, and — at 专武 and 6★ — a TP grant the base version does not have
 * at all. So a rung now supplies a <b>replacement skill set</b> ({@link SkillLadder} / {@link SkillUpgrade}),
 * and this class is the one place a tier number becomes that set.
 *
 * <p><b>The rotation kernels are untouched.</b> {@code SkillRotation} (the cursor) and {@code SkillGroups}
 * (which hooks are one skill) still operate on a plain hook list; the resolved set is simply what they are
 * handed. So "which skill is next" has exactly one implementation, and a tier swap cannot change the
 * rotation's period by accident: the resolved skills carry the same tags in the same order, with upgraded
 * bodies.
 *
 * <h2>One implementation of "is this tier 3?"</h2>
 * {@code UbService.atTier3} (what the wheel icon and the cast path ask) resolves it as
 * {@link #usesEnhancedUb(int)} over {@link #tier(int, AffectionDef)} — the same two calls the combat
 * pipeline makes. The roster threshold, the wheel icon, the skill names and the ultimate the player gets are
 * four views of one answer.
 *
 * <h2>Hostile data</h2>
 * A datapack can name a ladder for a skill no hook carries (harmless: it simply never matches), a blank
 * name (the base name is used — see {@link SkillLadder#nameAt}) or a {@code tier}, {@code tier2Threshold} of
 * {@code 0} (no such rung). None of it throws: this is reached from a damage path.
 */
@ApiStatus.Internal
public final class AffectionKernel {

    private AffectionKernel() {
    }

    /**
     * {@return the affection tier {@code affection} is in: 1, 2 or 3}
     *
     * <p>A delegation to {@link GrowthFacts#tier(int, AffectionDef)}, which owns the ladder.
     *
     * @param affection the character's affection
     * @param def       that character's affection definition
     */
    public static int tier(int affection, AffectionDef def) {
        return GrowthFacts.tier(affection, def);
    }

    /**
     * {@return how many effect tiers a character of {@code level} adds to every tiered effect}
     *
     * <p>A delegation to {@link GrowthFacts#bandBonus}, which delegates to {@link LevelBands}. Written here
     * because this class is the "what is a character worth at runtime" door the combat pipeline knocks on,
     * and because two delegating lines are cheaper than a caller reaching past both of them. The four bands
     * are stated in exactly one place.
     *
     * @param level the character's level, from {@link com.astrum.pcrd.api.v1.CharacterProgress#level()}
     */
    public static int bandBonus(int level) {
        return GrowthFacts.bandBonus(level);
    }

    /**
     * {@return whether a character at {@code tier} casts the enhanced ultimate}
     *
     * <p>Spec §6.2: tier 3 replaces the ultimate and its animation. The comparison is {@code >=}
     * rather than {@code ==} so a save that somehow holds a tier above the last one still gets the
     * enhanced ultimate rather than falling back to the base one.
     *
     * @param tier the character's tier, from {@link #tier(int, AffectionDef)}
     */
    public static boolean usesEnhancedUb(int tier) {
        return tier >= GrowthFacts.MAX_TIER;
    }

    /**
     * {@return {@code current + delta} affection, floored at 0 and capped at the tier-3 threshold}
     *
     * <p>Moved here from {@code AstrumCommand}, which now delegates to it, because affection is
     * written from three places (the command, a fed dish, and the growth view) and the cap is a
     * rule of the affection system rather than of the command that happens to be the oldest writer.
     *
     * <p>Capped because affection above tier 3 buys nothing (spec §6.2 — tier 3 is the last tier), so
     * a number beyond it would be a value the game can never produce. A threshold of {@code 0} means
     * the pack defines no tier 3, and then there is nothing to cap to.
     *
     * <p>Widened to {@code long} before adding: two large {@code int}s would otherwise overflow into a
     * negative affection, which is the one value a roster should never hold.
     *
     * @param current        affection the player has now
     * @param delta          change requested; may be negative
     * @param tier3Threshold the definition's tier-3 threshold, or {@code 0} for none
     */
    public static int clampAffection(int current, int delta, int tier3Threshold) {
        long updated = (long) current + (long) delta;
        if (updated < 0L) {
            updated = 0L;
        }
        if (tier3Threshold > 0 && updated > tier3Threshold) {
            updated = tier3Threshold;
        }
        return (int) Math.min(Integer.MAX_VALUE, updated);
    }

    // ------------------------------------------------------------------ the tier -> skill set resolution

    /**
     * {@return the hooks a character runs at affection {@code tier}}
     *
     * <h2>The rule, and why the order is what it is</h2>
     * Each hook that carries a {@code triggerParams.skill} tag is looked up in the character's ladders
     * ({@link AffectionDef#ladderFor}); the ladder answers the rung that applies, and that rung's
     * {@code effects} replace the base hook(s) for that tag. Three properties follow, and each is the reason
     * a particular case is written the way it is:
     *
     * <ol>
     *   <li><b>A tag the character has no ladder for is unchanged.</b> That is コッコロ's 技能2 and every
     *       skill of a character whose 6★ only changes her ultimate.</li>
     *   <li><b>The rung replaces the <em>whole</em> skill, not one effect.</b> A skill may be several hooks
     *       ({@code SkillGroups} groups them by tag); the replacement supplies the new set, so a version that
     *       gains a clause gains a hook and a version that loses one loses it. Appending to or filtering the
     *       base hooks could not express 姬塔's 技能1 going from one clause to three.</li>
     *   <li><b>The replacement sits where the base skill sat</b>, and the other skills keep their relative
     *       order. {@code SkillRotation} counts <em>skills</em>, so as long as the tag order is preserved the
     *       cursor's period and meaning survive a tier change — which is what makes "tier 3 is the same
     *       rotation with better skills" true rather than accidental.</li>
     * </ol>
     *
     * <p>An <b>untagged</b> hook is never replaced: it is a skill of its own with no tag for a ladder to
     * match (the pre-tag shape, unchanged since {@code SkillGroups} documented it).
     *
     * <h2>Why it takes the whole definition</h2>
     * The hooks come from {@code CharacterDef.effects()} and the ladders from
     * {@code CharacterDef.affection()}, and both belong to the same character. Passing them separately would
     * let a caller pair one character's hooks with another's ladders — a defect that would look like a
     * balance bug and be very hard to see.
     *
     * @param def  the character
     * @param tier the affection tier, {@code 1}–{@code 3}; anything else is clamped by the ladders
     * @return the hook list to run, in firing order; the same list instance when nothing changes
     */
    public static List<EffectHook> hooksAt(CharacterDef def, int tier) {
        if (def == null) {
            return List.of();
        }
        List<EffectHook> base = def.effects();
        AffectionDef affection = def.affection();
        if (base.isEmpty() || affection == null || affection.skills().isEmpty()) {
            return base;   // nothing to upgrade: the pack declares no ladder
        }
        // Group the base hooks by skill tag, preserving first-appearance order — the same grouping
        // SkillGroups does, and for the same reason: the rotation's unit is the skill.
        Map<String, List<EffectHook>> bySkill = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (EffectHook hook : base) {
            String tag = hook == null ? null : hook.triggerParams().skillTag().orElse(null);
            if (tag == null) {
                // An untagged hook keeps its own position and is never replaced. A unique synthetic key
                // built from the index keeps it in the map without colliding with a real tag.
                String key = "\u0000" + bySkill.size();
                bySkill.put(key, List.of(hook));
                order.add(key);
                continue;
            }
            List<EffectHook> skill = bySkill.get(tag);
            if (skill == null) {
                skill = new ArrayList<>();
                bySkill.put(tag, skill);
                order.add(tag);
            }
            skill.add(hook);
        }
        List<EffectHook> resolved = new ArrayList<>(base.size());
        boolean changed = false;
        for (String key : order) {
            List<EffectHook> skill = bySkill.get(key);
            String tag = key.startsWith("\u0000") ? null : key;
            Optional<SkillUpgrade> upgrade = tag == null
                    ? Optional.empty() : affection.ladderFor(tag).flatMap(l -> l.upgradeAt(tier));
            if (upgrade.isEmpty()) {
                resolved.addAll(skill);
                continue;
            }
            changed = true;
            resolved.addAll(upgrade.get().effects());
        }
        return changed ? List.copyOf(resolved) : base;
    }

    /**
     * {@return the skill names to show at affection {@code tier}: tag → name, in the character's order}
     *
     * <h2>What this answers, and for whom</h2>
     * 「The roster screen must show the current tier's skill names (with {@code +} where applicable) — that
     * is the whole point.」 So this is the one place "what is her 技能1 called right now" is decided, and the
     * book, the log line and anything added later all read it. {@link SkillLadder#nameAt} owns the
     * supersede rule (tier 3 beats tier 2); this method only walks the character's skills in order.
     *
     * <p>Skills with no ladder are included with their base name taken from the hook set itself — there is no
     * other place a name for them could come from, because a name is not part of an {@link EffectHook}. The
     * key of such an entry is its tag, which is what a caller needs to match it to a row.
     *
     * @param def  the character
     * @param tier the affection tier
     */
    public static Map<String, String> skillNamesAt(CharacterDef def, int tier) {
        Map<String, String> names = new LinkedHashMap<>();
        if (def == null || def.affection() == null) {
            return names;
        }
        AffectionDef affection = def.affection();
        Set<String> seen = new LinkedHashSet<>();
        for (SkillLadder ladder : affection.skills()) {
            if (ladder == null || ladder.skill().isBlank() || !seen.add(ladder.skill())) {
                continue;
            }
            names.put(ladder.skill(), ladder.nameAt(tier));
        }
        // The ultimate's name is not a ladder (its two rungs already live in ub.base / ub.enhanced), so it is
        // added here under a reserved key that no hook tag can collide with. It is the name a player reads
        // on the cast pad, and showing it beside the small skills is what makes the tier visible as a whole.
        String ubKey = ubKey();
        if (def.ub() != null) {
            String name = usesEnhancedUb(tier)
                    ? def.ub().enhancedName().orElse(def.ub().baseName().orElse(""))
                    : def.ub().baseName().orElse("");
            if (name != null && !name.isBlank()) {
                names.put(ubKey, name);
            }
        }
        return names;
    }

    /** {@return the reserved skill-name key the ultimate's name travels under} */
    public static String ubKey() {
        return "ub";
    }

    /**
     * {@return {@code def}'s ladder for {@code skillTag}, or empty when that skill never changes}
     *
     * <p>A null-safe front door to {@link AffectionDef#ladderFor}, so a caller on a render path does not have
     * to hold "the definition might have no affection block" as its own rule.
     *
     * @param def      the character, possibly {@code null}
     * @param skillTag the skill tag
     */
    public static Optional<SkillLadder> ladderFor(CharacterDef def, String skillTag) {
        if (def == null || def.affection() == null) {
            return Optional.empty();
        }
        return def.affection().ladderFor(skillTag);
    }
}
