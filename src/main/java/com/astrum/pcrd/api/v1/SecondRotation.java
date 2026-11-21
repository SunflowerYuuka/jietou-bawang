package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The second small-skill loop a character switches into, and the condition that switches her.</b>
 *
 * <h2>What the human described</h2>
 * 「入场2 → SP1；循环2 → SP2, SP1 —— 这是开了 UB 之后的变化。<b>两套独立的循环</b>。开了 UB 之后，UB 槽的充能
 * <b>不是立刻消耗光</b>，而是<b>进入第二套循环开始每秒消耗充能</b>，直到完全消耗完毕，再切换第一套循环。我们可以做成
 * <b>持续一定时间，击杀单位增加持续时间</b>。」
 *
 * <p>Two of his rulings decide the shape of everything below:
 * <ol>
 *   <li><b>① 直接消耗掉全部</b> — the gauge is emptied <b>at once</b> and the loop switch is decided by
 *       whether the mode is still running, not by how much charge is left. So the lifetime is a
 *       <b>duration</b> ({@link #seconds()}) and there is no per-second drain anywhere: the "per second"
 *       of the original game only decides how long a full bar lasts, which is what {@link #seconds()}
 *       already says. The emptying itself is not this record's business — every ultimate already spends
 *       the whole bar in {@code UbTransaction.decide}, which stays the one copy of that rule.</li>
 *   <li><b>② +2 秒 / 击杀，无上限</b> — {@link #secondsPerKill()}, with <b>no cap</b> (原作游戏内就是无上限).</li>
 * </ol>
 *
 * <h2>Why the skills are here and not in {@code CharacterDef.effects}</h2>
 * A hook in {@code effects[]} fires on every {@code melee_hit}, and {@code SkillGroups} counts it into the
 * character's <b>first</b> rotation. The second loop's skills must fire <b>instead of</b> the first loop's,
 * so they cannot live in the same list: one list would either make her rotation four long (two skills that
 * can never be reached) or merge the two loops into one period. They live here, and while the mode runs
 * {@code HookEvaluator} hands this list to the same {@code HookPlan} the first loop goes through — same
 * rotation kernel, same collapse, same executors, one implementation of each.
 *
 * <h2>Two rungs, like the ultimate itself</h2>
 * {@link #upgradedHooks()} is to this loop what {@code ub.enhanced} is to the ultimate: 矛依未's 特殊技能1
 * has a 无专武 formula and a 有专武/六星 one (天楼回刃斩 / 天楼回刃斩+), and the second is the 专武 rung. Tier
 * <b>2 and above</b> runs it, exactly as {@link SkillLadder#upgradeAt} keeps a {@code +} rung at tier 3 when
 * there is no 6★ rename. Empty means "this loop is the same at every tier", which is 怜's case.
 *
 * <h2>What was generalised, and what was not</h2>
 * {@link Condition} is the seam that makes this more than "after her ultimate": the loop is selected by a
 * declared condition, so a character whose second pattern is a different normal-attack <em>cadence</em>
 * (100301 怜's {@code start=[] loop=[1,1001,1,1002]} — <b>not</b> a drain mode) can later be expressed by
 * adding one enum value and declaring the same two skills in the other order. It is deliberately
 * <b>not</b> implemented for her now: she has one loop today, and giving her a second one would invent a
 * mechanic the data does not ask for.
 *
 * <h2>Why {@code seconds} and not ticks</h2>
 * The datapack vocabulary is the wiki's, and every other authored clock in this schema is in seconds
 * ({@code TieredParams.seconds}, {@code AttackBuff.seconds}) for the same reason. The one seconds-to-ticks
 * conversion is {@code AttackBuffRules.ticksFor}, which the runtime calls; this record stays a plain value
 * so an {@code api.v1} type never has to reach into {@code impl}.
 *
 * @param condition      what activates the loop. Today only {@link Condition#AFTER_ULTIMATE}
 * @param seconds        how long the loop lasts once activated, in seconds. This is a <b>duration</b>, not
 *                       a charge budget — see ① above
 * @param secondsPerKill how much longer each kill makes it, in seconds; {@code 0} means "kills do not
 *                       extend it", and there is <b>no upper bound</b> (②)
 * @param hooks          the loop's base skills, in firing order. Grouped into skills by
 *                       {@code triggerParams.skill} exactly as {@code effects[]} is, so the loop's period is
 *                       the number of distinct tags here — 矛依未's is 2 (SP1, SP2)
 * @param upgradedHooks  the 专武 / 5★ rung's skill set, or empty when the loop never changes. A non-empty
 *                       list <b>replaces</b> {@link #hooks()} from tier 2 up
 * @param extra          free-form tag for a clause of this mode this build still cannot express — the same
 *                       "keep the gap visible in the data" field {@link UbTier} has, and for the same reason:
 *                       a clause that is neither implemented nor written down is a clause that was silently
 *                       dropped. 矛依未's carries the remaining 「标记 125 层数 ≥ 1 时追加一段」 branch and the
 *                       6★ 「追加 1 个标记」 clause here ({@link #damageBonus()} closed the third)
 * @param damageBonus    「装备期间所有技能伤害都会增加物理伤害（大）」: how much <b>every skill's damage</b> is multiplied
 *                       by while this mode is running — {@code 1.5} for 矛依未, and {@code 1.0} (the default) for a
 *                       mode that grants none. It is <b>conditional by construction</b>: the caller reads
 *                       {@code SecondRotationStore.isActive} for the slot it is about to evaluate and composes this
 *                       into that hit's multiplier ({@code GrowthMultiplier} → {@code MultiplierChain.effective}),
 *                       so outside the mode the factor is exactly 1 and the character deals exactly her authored
 *                       damage. An unconditional "+50% for the whole fight" is the 凭空变强 this field exists to
 *                       avoid — the condition is the point.
 *                       <p><b>What it multiplies, stated plainly.</b> The multiplier chain scales the <em>amounts</em>
 *                       a character's hooks author, before the damage type decides physical from magic, so the bonus
 *                       lands on her skill damage whichever stat a clause reads. Every clause 矛依未 ships is
 *                       physical, so for her this is faithful to 「增加物理伤害」; a mode-2 character who dealt magic
 *                       damage would find the bonus on that too. That is the one approximation in this channel, and
 *                       it is deliberate: a per-entry physical filter would be a second multiplier path beside the
 *                       chain this project keeps single.
 *                       <p><b>The number is a design value.</b> The source says 「大」 and no percentage;
 *                       {@code docs/balance-derivation.md} §8 fixes that adjective at +50% (小 = +10%, 中 = +30%,
 *                       大 = +50%), the same ladder every other 「大アップ」 clause in this build reads.
 */
@ApiStatus.Experimental
public record SecondRotation(Condition condition, double seconds, double secondsPerKill,
                             List<EffectHook> hooks, List<EffectHook> upgradedHooks,
                             java.util.Optional<String> extra, double damageBonus) {

    /**
     * What puts a character into her second loop.
     *
     * <p>One value today, and it is not a placeholder: 矛依未's own text says the switch happens when the
     * ultimate equips 天楼霸断剑 and switches back when it ends, so "after the ultimate" <em>is</em> the
     * condition the source material gives. The enum exists rather than a boolean because the next
     * character's second pattern is a cadence variant with a different cause, and a boolean would have to
     * be renamed — a decode-time break for every pack — to say so.
     */
    public enum Condition implements StringRepresentable {
        /** The loop begins when an accepted ultimate of this character resolves. */
        AFTER_ULTIMATE;

        /** The wire form, lowercase, like {@link Trigger} and {@link EffectType}. */
        public static final Codec<Condition> CODEC = StringRepresentable.fromEnum(Condition::values);

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final Codec<SecondRotation> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Condition.CODEC.optionalFieldOf("condition", Condition.AFTER_ULTIMATE)
                    .forGetter(SecondRotation::condition),
            Codec.DOUBLE.fieldOf("seconds").forGetter(SecondRotation::seconds),
            Codec.DOUBLE.optionalFieldOf("secondsPerKill", 0.0).forGetter(SecondRotation::secondsPerKill),
            EffectHook.CODEC.listOf().optionalFieldOf("hooks", List.of()).forGetter(SecondRotation::hooks),
            EffectHook.CODEC.listOf().optionalFieldOf("upgradedHooks", List.of())
                    .forGetter(SecondRotation::upgradedHooks),
            Codec.STRING.optionalFieldOf("extra").forGetter(SecondRotation::extra),
            // 「装备期间所有技能伤害都会增加物理伤害（大）」 — the mode's own damage bonus, additive and last for the
            // reason every field above is: a pack written before it decodes with 1.0, i.e. "this mode grants no
            // bonus", which is exactly what the field's absence meant.
            Codec.DOUBLE.optionalFieldOf("damageBonus", 1.0).forGetter(SecondRotation::damageBonus)
    ).apply(instance, SecondRotation::new));

    public SecondRotation {
        condition = condition == null ? Condition.AFTER_ULTIMATE : condition;
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
        upgradedHooks = upgradedHooks == null ? List.of() : List.copyOf(upgradedHooks);
        extra = extra == null ? java.util.Optional.empty() : extra;
        // Hostile data: a NaN or negative clock is not a duration. Zeroing it here (rather than throwing)
        // matches how every other authored number in this schema degrades — the hook still loads, and the
        // runtime refuses to start a mode it cannot time (see SecondRotationStore.begin).
        seconds = seconds > 0.0 && Double.isFinite(seconds) ? seconds : 0.0;
        secondsPerKill = secondsPerKill > 0.0 && Double.isFinite(secondsPerKill) ? secondsPerKill : 0.0;
        // The bonus can only ever help: a value at or below 1, a NaN or an infinity is read as "no bonus" rather
        // than as a nerf the data never asked for. That is the same one-directional reading
        // `GrowthMultiplier` gives its own factors, and it is what makes the *absence* of the field and a
        // mistyped one the same state.
        damageBonus = Double.isFinite(damageBonus) && damageBonus > 1.0 ? damageBonus : 1.0;
    }

    /**
     * {@return the skills this loop runs at affection {@code tier}, in firing order}
     *
     * <h2>The rung rule, and where it is borrowed from</h2>
     * Tier 2 and up runs {@link #upgradedHooks()} when the pack declares one, and tier 1 never does —
     * the same {@code >= 2} ordering {@link SkillLadder#upgradeAt} uses, and the same reason: 专武 is the
     * rung that adds the {@code +} version, and a tier above the last declared rung keeps the top one
     * rather than falling back to the base. A loop with no upgrade answers {@link #hooks()} at every tier.
     *
     * @param tier the affection tier, 1–3; anything else is read as "at least" the rung it names
     */
    public List<EffectHook> hooksAt(int tier) {
        return tier >= 2 && !upgradedHooks.isEmpty() ? upgradedHooks : hooks;
    }

    /** {@return whether this loop has any skill at all — a mode with none would switch to nothing} */
    public boolean isDeclared() {
        return !hooks.isEmpty() || !upgradedHooks.isEmpty();
    }

    /**
     * The five-argument form, for a loop that names no unimplemented clause — the ordinary case, and the
     * shape a test fixture wants. Stating it once here is cheaper than {@code Optional.empty()} at every
     * call site, and it is the form a pack that writes no {@code extra} decodes to.
     */
    public SecondRotation(Condition condition, double seconds, double secondsPerKill,
            List<EffectHook> hooks, List<EffectHook> upgradedHooks) {
        this(condition, seconds, secondsPerKill, hooks, upgradedHooks, java.util.Optional.empty(), 1.0);
    }

    /**
     * The six-argument form: a loop that records a clause it cannot express but grants no damage bonus.
     *
     * <p>Stating {@code 1.0} once here is cheaper than at every call site and every test fixture, and it is the
     * shape a pack without {@code damageBonus} decodes to.
     */
    public SecondRotation(Condition condition, double seconds, double secondsPerKill,
            List<EffectHook> hooks, List<EffectHook> upgradedHooks, java.util.Optional<String> extra) {
        this(condition, seconds, secondsPerKill, hooks, upgradedHooks, extra, 1.0);
    }
}
