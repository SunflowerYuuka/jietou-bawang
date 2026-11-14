package com.astrum.pcrd.impl.combat;

import org.jetbrains.annotations.ApiStatus;

/**
 * One character's place in her own small-skill rotation: a cursor into the rotating hook list.
 *
 * <h2>The rule, in the human's words</h2>
 * 「每次挥砍同时触发队伍五个角色各自的小技能，下一次挥砍触发下一个技能。每个角色的轮转是独立的 ——
 * A 有两个技能就 1-2-1-2，B 有三个就 1-2-3-1。第二次挥砍后两人都放过技能 2，第三次挥砍 A 放技能 1
 * 而 B 放技能 3。」
 *
 * <p>So the cursor is <b>per character</b>, not per party slot, and its period is that
 * character's own count. Two characters with two and three skills therefore drift apart on the third
 * swing, which is exactly the behaviour the example describes and exactly what a single shared counter
 * would get wrong.
 *
 * <h2>Why this is a value and not a mutable cursor</h2>
 * A {@code next()} that both reads and writes is two rules in one method, and a test can only see the
 * pair. As a value there is one question ("what fires now?" — {@link #peek}) and one transition
 * ("a skill fired" — {@link #advance}), and the second one is what makes "advance only when a skill
 * actually fires" expressible: the caller runs the skill, and only then replaces the cursor with
 * {@code advance(count)}. A refusal (a chance roll that missed, a hook the pack wrote badly) leaves the
 * cursor untouched, so the player gets that skill on the next swing instead of silently skipping it.
 *
 * <h2>The wrap</h2>
 * {@code next = (index + 1) % count}, computed with {@link Math#floorMod} so a cursor left over from a
 * larger hook list (a pack edited between swings) still lands in range instead of throwing or indexing
 * from the end. {@code count <= 0} is not a rotation: {@link #peek} answers {@code 0} and
 * {@link #advance} is the identity, so a character with no rotating hooks costs nothing and cannot
 * corrupt a cursor she will need again later.
 */
@ApiStatus.Internal
public record SkillRotation(int cursor) {

    /** A character who has not fired a rotating skill yet. */
    public static final SkillRotation START = new SkillRotation(0);

    public SkillRotation {
        cursor = Math.max(0, cursor);
    }

    /**
     * {@return the index into the rotating hook list that fires now}
     *
     * @param count how many hooks rotate for this character; {@code <= 0} answers {@code 0}
     */
    public int peek(int count) {
        if (count <= 0) {
            return 0;
        }
        return Math.floorMod(cursor, count);
    }

    /**
     * {@return the cursor after one rotating skill has fired}
     *
     * <p>Called only once a skill has actually run — see the class javadoc. The result is normalised
     * into {@code [0, count)} rather than merely incremented, so {@link #peek} never has to reason
     * about a stale cursor.
     *
     * @param count how many hooks rotate for this character; {@code <= 0} returns this rotation
     */
    public SkillRotation advance(int count) {
        if (count <= 0) {
            return this;
        }
        return new SkillRotation((peek(count) + 1) % count);
    }
}
