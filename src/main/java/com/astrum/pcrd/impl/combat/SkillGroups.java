package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.Trigger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.ApiStatus;

/**
 * Which hooks are <b>one skill</b> — the grouping the rotation counts in.
 *
 * <h2>The problem this closes</h2>
 * A skill is not always one effect. Djeeta's 技能2 ウェポンバースト is 「自分の物理攻撃力を小アップし、
 * TPを特大回復」 — two effects on one swing — and modelling that as two {@code melee_hit} hooks would give
 * her a three-entry rotation ({@code 广域斩击, 攻击力上升, 充能}) instead of two, so a swing that should
 * cast <em>one</em> skill would cast half of two different ones. The rotation's unit is therefore the
 * <b>skill</b>, and this class is the one place that says which hooks make up which skill.
 *
 * <h2>The rule</h2>
 * <ol>
 *   <li>Only {@link Trigger#MELEE_HIT} hooks rotate at all (see {@link HookPlan} for why: a {@code crit}
 *       or {@code kill} hook is a different event, not a swing skill).</li>
 *   <li>Rotating hooks with the same {@code triggerParams.skill} tag are <b>one skill</b>: they fire
 *       together on that skill's turn and share one advance of the cursor.</li>
 *   <li>A rotating hook with no tag is a skill of its own — the pre-tag shape, unchanged, so every pack
 *       written before this field existed keeps its exact rotation.</li>
 *   <li>Group order is the order each tag first appears in {@code effects[]}, and tags are matched
 *       <b>exactly</b>, so a hook may sit anywhere in the file without changing the rotation.</li>
 * </ol>
 *
 * <h2>Why this is derived and not stored</h2>
 * Same reason as {@link SkillRotation}: a stored group count can outlive the JSON that produced it.
 * {@link #of} is pure and cheap, the hook list is already in hand at the call site, and a pack edit
 * therefore re-derives on the very next swing.
 *
 * <p>It holds no game types and no state, so every branch here is one unit test away
 * ({@code SkillGroupsTest}).
 *
 * @param members for each skill, the {@code effects[]} indices of its hooks, in list order
 */
@ApiStatus.Internal
public record SkillGroups(List<List<Integer>> members) {

    /** A hook that does not rotate; {@link #skillOf} answers this for it. */
    public static final int NOT_ROTATING = -1;

    /** No rotating hooks: the cursor has a period of zero and nothing advances. */
    public static final SkillGroups NONE = new SkillGroups(List.of());

    public SkillGroups {
        List<List<Integer>> copy = new ArrayList<>(members.size());
        for (List<Integer> skill : members) {
            copy.add(List.copyOf(skill));
        }
        members = List.copyOf(copy);
    }

    /**
     * {@return the skill groups of {@code hooks}}
     *
     * <p>Never throws: a {@code null} list or a {@code null} element is skipped, because this runs
     * inside a damage sequence where a throwing guard would cost more than the rotation it describes.
     *
     * @param hooks the character's hook list, indexed by position
     */
    public static SkillGroups of(List<EffectHook> hooks) {
        if (hooks == null) {
            return NONE;
        }
        Map<String, List<Integer>> byTag = new LinkedHashMap<>();
        List<List<Integer>> skills = new ArrayList<>();
        for (int index = 0; index < hooks.size(); index++) {
            EffectHook hook = hooks.get(index);
            if (hook == null || hook.trigger() != Trigger.MELEE_HIT) {
                continue;
            }
            String tag = hook.triggerParams().skillTag().orElse(null);
            if (tag == null) {
                // An untagged hook is a skill of its own. A group of one, rather than a shared key,
                // is what keeps two untagged hooks from being merged into a single skill.
                skills.add(List.of(index));
                continue;
            }
            List<Integer> skill = byTag.get(tag);
            if (skill == null) {
                skill = new ArrayList<>();
                byTag.put(tag, skill);
                skills.add(skill);
            }
            skill.add(index);
        }
        return new SkillGroups(skills);
    }

    /** {@return how many skills rotate; the period of the cursor} */
    public int count() {
        return members.size();
    }

    /**
     * {@return which skill {@code hookIndex} belongs to, or {@link #NOT_ROTATING}}
     *
     * @param hookIndex the hook's position in {@code effects[]}
     */
    public int skillOf(int hookIndex) {
        for (int skill = 0; skill < members.size(); skill++) {
            if (members.get(skill).contains(hookIndex)) {
                return skill;
            }
        }
        return NOT_ROTATING;
    }

    /** {@return the hook indices of one skill, empty when {@code skill} is out of range} */
    public List<Integer> hooksOf(int skill) {
        if (skill < 0 || skill >= members.size()) {
            return List.of();
        }
        return members.get(skill);
    }
}
