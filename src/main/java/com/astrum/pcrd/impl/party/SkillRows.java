package com.astrum.pcrd.impl.party;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.SkillLadder;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>One row per skill, named as the current affection tier names it</b> — the list the 角色册 draws.
 *
 * <h2>What the human asked for</h2>
 * 「The roster screen must show the <b>current tier's skill names</b> (with {@code +} where applicable) — that
 * is the whole point of him asking.」 So the book's skills section is not a list of effects: it is a list of
 * <b>skills</b>, each carrying the name it has right now and the hooks it fires right now.
 *
 * <h2>Why this is a class and not four lines in the screen</h2>
 * Two rules have to agree and neither belongs to a renderer:
 * <ol>
 *   <li><b>which hooks run at this tier</b> — {@link AffectionKernel#hooksAt}, the same call the damage
 *       pipeline makes, so the book cannot describe a skill set the game does not run;</li>
 *   <li><b>what that skill is called</b> — {@link SkillLadder#nameAt}, which owns the {@code +} and the
 *       "rename supersedes {@code +}" rule.</li>
 * </ol>
 * Writing both again inside {@code CharacterBookScreen} is how the book ends up showing 广域斩击+ on a
 * character who is actually casting 黄金耀光 — the exact defect the tier work exists to prevent. So the join
 * lives here, is game-free, and {@code SkillRowsTest} pins it for all four shipped characters.
 *
 * <h2>Order, and the ultimate</h2>
 * Rows are in the character's own skill order (the order each tag first appears in {@code effects[]}), which
 * is the rotation's order — so a player reading the list top to bottom reads her rotation. The ultimate is
 * appended last under {@link AffectionKernel#ubKey()} with the name {@code ub.base} / {@code ub.enhanced}
 * gives, because it is a skill a player casts and its name is the one the tier swaps.
 *
 * @param rows every skill, in the character's order, then the ultimate
 */
@ApiStatus.Internal
public record SkillRows(List<SkillRows.Row> rows) {

    public SkillRows {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /** No character selected, or a definition with nothing in it. */
    public static final SkillRows NONE = new SkillRows(List.of());

    /**
     * One skill at one tier.
     *
     * @param skill     the skill tag, {@link AffectionKernel#ubKey()} for the ultimate
     * @param name      the name this skill has at the tier being drawn
     * @param hooks     the hooks it fires at that tier, in firing order; empty for an EX rung this build
     *                  cannot apply (it is a battle-start stat buff and there is no trigger for it)
     * @param ultimate  whether this row is the ultimate rather than a small skill
     * @param icon      this skill's <b>one</b> picture, empty when the pack ships none for it
     *                  <p>One picture per skill, not per effect and not per rung: a generic effect carries no
     *                  image any more, and 六星 renaming 广域斩击 to 黄金耀光 does not change what the skill looks
     *                  like. The roster, the detail page and the effect bar all read this one value.
     */
    public record Row(String skill, String name, List<EffectHook> hooks, boolean ultimate,
                      Optional<ResourceLocation> icon) {

        public Row {
            skill = skill == null ? "" : skill;
            name = name == null ? "" : name;
            hooks = hooks == null ? List.of() : List.copyOf(hooks);
            icon = icon == null ? Optional.empty() : icon;
        }

        /** The four-argument form, for a row whose skill the pack gives no picture. */
        public Row(String skill, String name, List<EffectHook> hooks, boolean ultimate) {
            this(skill, name, hooks, ultimate, Optional.empty());
        }

        /** {@return the first hook, which is the one whose icon the row draws} */
        public Optional<EffectHook> firstHook() {
            return hooks.isEmpty() ? Optional.empty() : Optional.of(hooks.get(0));
        }
    }

    /**
     * {@return the rows for {@code def} at affection {@code tier}}
     *
     * <p>Never throws and never returns {@code null}: a {@code null} definition is {@link #NONE}, because this
     * is called from a render path where "nothing selected yet" is an ordinary state.
     *
     * @param def  the character
     * @param tier the affection tier, 1–3
     */
    public static SkillRows of(CharacterDef def, int tier) {
        if (def == null) {
            return NONE;
        }
        List<EffectHook> resolved = AffectionKernel.hooksAt(def, tier);
        // Group the resolved hooks by tag, preserving first-appearance order — the order the rotation
        // walks, so the list reads as the rotation.
        Map<String, List<EffectHook>> bySkill = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        int untagged = 0;
        for (EffectHook hook : resolved) {
            String tag = hook == null ? null : hook.triggerParams().skillTag().orElse(null);
            String key = tag == null ? "#" + (untagged++) : tag;
            List<EffectHook> group = bySkill.get(key);
            if (group == null) {
                group = new ArrayList<>();
                bySkill.put(key, group);
                order.add(key);
            }
            group.add(hook);
        }
        List<Row> rows = new ArrayList<>(order.size() + 1);
        for (String key : order) {
            Optional<SkillLadder> ladder = AffectionKernel.ladderFor(def, key);
            String name = ladder.map(value -> value.nameAt(tier)).orElse(key);
            rows.add(new Row(key, name, bySkill.get(key), false,
                    ladder.flatMap(SkillLadder::icon)));
        }
        // 「两套独立的循环」: a character whose ultimate switches her into a second skill loop has skills the
        // first loop's list does not contain, and a book that omitted them would hide half her kit — 矛依未's
        // 天楼回刃斩 / 天楼闪薙斩 are only reachable while the mode runs. They are appended in her second loop's
        // own order (SP1, SP2) AFTER the first loop's rows, so every existing row index is unchanged and the
        // skill entry's amplifier, which is a row index, still means the same skill at every tier.
        //
        // The rung is the same tier the first half resolved at: SecondRotation.hooksAt owns that rule, so the
        // book cannot show the un-upgraded 天楼回刃斩 while the game runs 天楼回刃斩+.
        //
        // Read off the *declaration* rather than through the runtime: a row is a fact about the pack, and the
        // book must list these skills even while she is on her first loop and has not cast yet.
        if (def.ub() != null) {
            def.ub().secondRotationOf().ifPresent(spec -> {
                Map<String, List<EffectHook>> secondSkills = new LinkedHashMap<>();
                List<String> secondOrder = new ArrayList<>();
                for (EffectHook hook : spec.hooksAt(tier)) {
                    String tag = hook == null ? null : hook.triggerParams().skillTag().orElse(null);
                    if (tag == null || bySkill.containsKey(tag)) {
                        // Untagged, or a tag the first loop already drew: one row per skill, and the first
                        // loop's row is the one that already names it.
                        continue;
                    }
                    if (!secondSkills.containsKey(tag)) {
                        secondOrder.add(tag);
                    }
                    secondSkills.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(hook);
                }
                for (String tag : secondOrder) {
                    Optional<SkillLadder> ladder = AffectionKernel.ladderFor(def, tag);
                    rows.add(new Row(tag, ladder.map(value -> value.nameAt(tier)).orElse(tag),
                            secondSkills.get(tag), false, ladder.flatMap(SkillLadder::icon)));
                }
                // The second loop's tags are drawn now, so the EX pass below must not draw them again.
                bySkill.putAll(secondSkills);
            });
        }
        // Skills that exist only as a ladder rung this build cannot fire — the EX rungs, whose effects list is
        // empty — still get a row: a player is meant to SEE that her EX skill changed name at 专武, and a list
        // that silently omitted it would hide exactly the tier she paid for.
        //
        // The EX row carries its <b>passive clauses</b> as hooks rather than an empty list, so the data the book
        // renders is the data the enrolment really applies. That is the join this task owes: the row used to say
        // a name and nothing behind it, and a name with no number is what made the gap invisible.
        if (def.affection() != null) {
            for (SkillLadder ladder : def.affection().skills()) {
                if (ladder == null || bySkill.containsKey(ladder.skill())) {
                    continue;
                }
                if (ladder.tier2().isEmpty() && ladder.tier3().isEmpty()
                        && ladder.passive().isEmpty()) {
                    continue;   // a declared but empty ladder: nothing to show
                }
                rows.add(new Row(ladder.skill(), ladder.nameAt(tier),
                        passiveHooks(def, ladder, tier), false, ladder.icon()));
            }
        }
        if (def.ub() != null) {
            boolean enhanced = AffectionKernel.usesEnhancedUb(tier);
            String name = enhanced
                    ? def.ub().enhancedName().orElse(def.ub().baseName().orElse(""))
                    : def.ub().baseName().orElse("");
            if (!name.isBlank()) {
                rows.add(new Row(AffectionKernel.ubKey(), name, List.of(), true,
                        def.ub().icon()));
            }
        }
        return new SkillRows(rows);
    }

    /**
     * {@return the row whose skill tag is {@code skill}, empty when she has no such skill at {@code tier}}
     *
     * <h2>Why a skill tag is the lookup key, and not a row index</h2>
     * A skill entry's effect type <b>is</b> the (character, skill) identity, so the bar asks "which row is this
     * skill" rather than "which row is index 2". That is the whole point of one type per skill: an index was
     * the old coupling (it travelled in the instance's amplifier and broke the moment a pack reordered her
     * skills), while a tag is the same key {@code SkillGroups}, the rotation and {@code AffectionKernel} use.
     *
     * <p>One copy of the join for every caller — the tooltip, the inventory row, the icon chain and
     * {@code AstrumSkillEffect.getDisplayName} — so the bar and the book cannot describe different skills.
     *
     * @param def   the character, or {@code null}
     * @param tier  her affection tier
     * @param skill the skill tag, {@code AffectionKernel.ubKey()} for the ultimate
     */
    public static Optional<Row> rowOf(@Nullable CharacterDef def, int tier, @Nullable String skill) {
        if (def == null || skill == null || skill.isBlank()) {
            return Optional.empty();
        }
        for (Row row : of(def, tier).rows()) {
            if (skill.equals(row.skill())) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** {@return how many rows there are} */
    public int size() {
        return rows.size();
    }

    /** {@return whether there is nothing to draw} */
    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * {@return the hooks an EX row shows: its passive's clauses, converted to the {@link EffectHook} shape a row
     * draws}
     *
     * <h2>Why the conversion, rather than a second row type</h2>
     * A row draws an icon from its effect type and a line of text from its numbers, and both already key off
     * {@link EffectHook}. A {@link com.astrum.pcrd.api.v1.ExPassive.Clause} carries exactly the same two things —
     * an {@link com.astrum.pcrd.api.v1.EffectType} and a {@link com.astrum.pcrd.api.v1.TieredParams} — so
     * converting it here keeps <b>one</b> row type, and therefore one renderer, one icon lookup and one
     * border-colour rule. The alternative (a second row shape for one skill) would be a parallel drawing path for
     * a single line.
     *
     * <p>The hook's trigger is a placeholder that can never fire — {@link com.astrum.pcrd.api.v1.Trigger#KILL} —
     * and that is deliberate and safe: these hooks exist <b>only</b> to be drawn. Nothing dispatches a row's
     * hooks ({@code AffectionKernel.hooksAt} is the only producer the executor sees, and it never emits an EX
     * row's hooks), so a placeholder trigger cannot cause a kill to buff anybody.
     */
    private static List<EffectHook> passiveHooks(CharacterDef def, SkillLadder ladder, int tier) {
        if (!ladder.isEx() || ladder.passive().isEmpty()) {
            return List.of();
        }
        // The SAME resolution the enrolment uses (ExPassives.rungAt → ExPassive.rungFor), so the row cannot
        // describe a rung the passive does not apply. A tier-equality match here would show nothing at tier 3
        // for a character who has only tier 1 and 2 rungs — the row would go blank exactly when she is strongest.
        java.util.Optional<com.astrum.pcrd.api.v1.ExPassive.Rung> rung =
                ladder.passive().get().rungFor(tier);
        if (rung.isEmpty()) {
            return List.of();
        }
        List<EffectHook> hooks = new ArrayList<>();
        for (com.astrum.pcrd.api.v1.ExPassive.Clause clause : rung.get().clauses()) {
            EffectHook hook = passiveHook(clause);
            if (hook != null) {
                hooks.add(hook);
            }
        }
        return List.copyOf(hooks);
    }

    /**
     * {@return the drawable hook for one EX clause, or {@code null} when this build has no picture for it}
     *
     * <p>A clause naming one of our tiered effects becomes that effect's hook; a clause naming a vanilla effect
     * becomes an {@code ally_effect} hook with that id, because {@code ally_effect} is the row type that already
     * renders "a named vanilla effect" and therefore already has an icon and a border colour. A malformed clause
     * is skipped rather than drawn as nothing — {@code ExPassives} warns about it once when it applies.
     */
    private static EffectHook passiveHook(com.astrum.pcrd.api.v1.ExPassive.Clause clause) {
        if (clause.problem().isPresent()) {
            return null;
        }
        com.astrum.pcrd.api.v1.TriggerParams params =
                new com.astrum.pcrd.api.v1.TriggerParams(0, java.util.Optional.empty(),
                        java.util.Optional.of(SkillLadder.EX_SKILL));
        // The vanilla half first: `clause.type()` is empty for 佩可莉妮's 公主之力, and switching on a null
        // selector throws — the NPE a first version of this method really did throw.
        if (clause.type().isEmpty()) {
            return clause.vanilla()
                    .map(id -> (EffectHook) new EffectHook.AllyEffect(com.astrum.pcrd.api.v1.Trigger.KILL,
                            params, 1.0, id,
                            // Only the id matters for picking a picture; a row's hooks are never dispatched
                            // (AffectionKernel.hooksAt is the only producer the executor sees, and it never
                            // emits an EX row's hooks), so the duration is honest rather than meaningful.
                            0, clause.amplifier(), java.util.OptionalDouble.empty()))
                    .orElse(null);
        }
        return switch (clause.type().get()) {
            // The two flat EX effects: the row's picture is the attack-buff icon (it IS an attack buff, just
            // a point one), and its number comes from the formula rather than from a rung. Drawn through the
            // same hook types the executor uses, so the row and the grant cannot describe different things.
            case FLAT_ATTACK_UP -> new EffectHook.FlatAttackUp(
                    com.astrum.pcrd.api.v1.Trigger.KILL, params, 1.0,
                    clause.amount().orElse(com.astrum.pcrd.api.v1.FlatAmount.NONE),
                    clause.duration());
            case FLAT_MAGIC_ATTACK_UP -> new EffectHook.FlatMagicAttackUp(
                    com.astrum.pcrd.api.v1.Trigger.KILL, params, 1.0,
                    clause.amount().orElse(com.astrum.pcrd.api.v1.FlatAmount.NONE),
                    clause.duration());
            case BUFF_ATTACK_AREA -> new EffectHook.BuffAttackArea(
                    com.astrum.pcrd.api.v1.Trigger.KILL, params, 1.0, clause.params());
            case BUFF_MAGIC_ATTACK_AREA -> new EffectHook.BuffMagicAttackArea(
                    com.astrum.pcrd.api.v1.Trigger.KILL, params, 1.0, clause.params());
            case BUFF_PHYS_DEFENCE_AREA -> new EffectHook.BuffPhysDefenceArea(
                    com.astrum.pcrd.api.v1.Trigger.KILL, params, 1.0, clause.params());
            case BUFF_MAGIC_DEFENCE_AREA -> new EffectHook.BuffMagicDefenceArea(
                    com.astrum.pcrd.api.v1.Trigger.KILL, params, 1.0, clause.params());
            case HASTE -> new EffectHook.Haste(
                    com.astrum.pcrd.api.v1.Trigger.KILL, params, 1.0, clause.params());
            // The remaining tiered types are not used by any shipped EX; an EX that names one is drawn with
            // ally_effect's picture rather than given a hook this method would have to invent.
            default -> null;
        };
    }
}
