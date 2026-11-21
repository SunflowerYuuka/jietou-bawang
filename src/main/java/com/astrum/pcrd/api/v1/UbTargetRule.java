package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Who one damage segment of an ultimate lands on</b> — the one thing an ultimate's clause could not say.
 *
 * <h2>The gap this closes</h2>
 * A {@link UbTier} used to carry exactly one damage shape: {@code hits}, {@code damage} and an optional
 * {@code radius}. That is one <em>number</em> on one <em>kind</em> of receiver — "everything in the radius"
 * when a radius was present, "the single nearest target" when it was not. Six shipped clauses could not be
 * written at all, and all six were recorded as unimplemented in {@code extra} (which the book then renders as
 * 「尚未实现」 — see {@code docs/balance-derivation.md}'s not-implemented tables):
 *
 * <ul>
 *   <li>日和莉 「对范围半径400内全部敌人造成 4.5」 <b>and</b> the nearest target for 10.0 — two numbers on two
 *       kinds of receiver, and one {@code damage} field can hold one of them;</li>
 *   <li>美美 「对<b>第二近</b>的一名敌人造成 15.0」 — only <em>nearest</em> was selectable;</li>
 *   <li>美美 6★ 「以<b>前方第2名角色</b>为中心、对范围275内 18.3」 — the blast's centre is another body, not the
 *       caster;</li>
 *   <li>紬希 「将<b>最远</b>的敌人拉近 300」/「击退<b>最近</b>的敌人 300」 — no farthest/nearest selection, so her
 *       pull and her knockback could only be applied to <em>every</em> body the ultimate hit;</li>
 *   <li>紬希 6★ 「伤害随目标数量提升」 — a number that multiplies by how many were hit, which a single amount
 *       cannot express. That one is {@link UbSegment#perExtraTarget()}, because it scales <em>one clause's</em>
 *       amount by the size of <em>that clause's</em> own receiver set.</li>
 * </ul>
 *
 * <h2>Why an enum and not a distance expression</h2>
 * Every rule here is a question the planner can answer from data it already holds — the eligible candidate
 * list, each candidate's distance from the caster, and each candidate's position. Naming the five answers the
 * source actually uses keeps the planner a {@code switch} the compiler can check, instead of a mini-language a
 * datapack could write nonsense in. A rule a future pack needs that is not here is a value to append, not a
 * formula to parse.
 *
 * <h2>What each rule means, exactly</h2>
 * "Eligible" always means {@code UbDamage.eligible}: the caster is out, an untouchable body (spectator,
 * creative in flight) is out, a body beyond the clause's reach is out, and a player the ally policy calls an
 * ally is out. <b>This enum does not restate any of that</b> — it only chooses <em>which</em> of the eligible
 * bodies a clause lands on.
 *
 * <p>Appended values keep their positions: the enum's names are the wire format
 * ({@link StringRepresentable#fromEnum} reads an unknown name as an error), so a pack written against this
 * build cannot be silently reinterpreted by a later one.
 */
@ApiStatus.Experimental
public enum UbTargetRule implements StringRepresentable {

    /** 「最近的敌人」: the single eligible body closest to the caster. The rule every tier without a radius used. */
    NEAREST,

    /**
     * 「第二近的敌人」: the <b>second</b> closest eligible body — 美美's 「对第二近的一名敌人造成」.
     *
     * <p>Empty when fewer than two bodies are eligible: "the second nearest" of one candidate is nobody, and
     * hitting the nearest instead would silently apply a clause twice to the same body.
     */
    SECOND_NEAREST,

    /** 「最远的敌人」: the single eligible body furthest from the caster — 紬希's 「将最远的敌人拉近」. */
    FARTHEST,

    /**
     * 「范围内的全部敌人」: every eligible body within {@code radius} blocks <b>of the caster</b> — the rule
     * every tier with a radius used, and 日和莉's 「对范围半径400内全部敌人」.
     *
     * <p>Requires a radius: see {@link #needsRadius()}.
     */
    ALL_IN_RADIUS,

    /**
     * 「以第2名角色为中心、对范围内」: every eligible body within {@code radius} blocks <b>of the
     * second-nearest eligible body</b> — 美美 6★'s 「以前方第2名角色为中心、对范围275内的敌人」.
     *
     * <h2>Why this could not be {@link #ALL_IN_RADIUS}</h2>
     * Its centre is a <em>body</em>, not the caster. A distance from the caster cannot say how far one
     * candidate is from another, so the planner reads {@code UbDamage.Target.position()} for this rule alone —
     * which is why a candidate carries one. The anchor is itself included (its distance from itself is 0).
     *
     * <p><b>The honest approximation:</b> 「前方第2名」 is "the second body in front of her", and this build has
     * no facing test in the planner, so the anchor is the second <em>nearest</em> eligible body — the same
     * reading 美美's other clause (「第二近」, {@link #SECOND_NEAREST}) uses for the same phrase family. A
     * directional filter is a separate rule, not a defect of this one.
     *
     * <p>Empty when fewer than two bodies are eligible, or when the radius is unusable.
     */
    AROUND_SECOND_NEAREST;

    public static final Codec<UbTargetRule> CODEC = StringRepresentable.fromEnum(UbTargetRule::values);

    /**
     * {@return whether a clause with this rule cannot work without a {@code radius}}
     *
     * <p>The two area rules do, and the three rank rules do not — a rank rule <em>may</em> declare one, and
     * then it ranks only the bodies inside it, which is the "within this reach, the nearest" reading. Stated
     * here rather than in the planner so "which rules need a radius" has one home; the planner asks this and
     * never re-lists the values.
     */
    public boolean needsRadius() {
        return switch (this) {
            case ALL_IN_RADIUS, AROUND_SECOND_NEAREST -> true;
            case NEAREST, SECOND_NEAREST, FARTHEST -> false;
        };
    }

    /**
     * {@return whether the centre of this clause is another body rather than the caster}
     *
     * <p>Only {@link #AROUND_SECOND_NEAREST}. The planner reads it to decide whether it needs positions at all:
     * every other rule is answered by distances from the caster, so a plan that never sees this value never
     * looks at {@code Target.position()}.
     */
    public boolean centredOnAnotherBody() {
        return this == AROUND_SECOND_NEAREST;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
