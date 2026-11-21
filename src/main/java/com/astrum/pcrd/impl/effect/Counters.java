package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.WarnOnce;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>计数</b>: a self buff whose <b>amplifier is the stack count</b>, and the only effect in this build that
 * legitimately accumulates.
 *
 * <h2>What the human asked for</h2>
 * 「冰龙之印技术就变成自身的buff给玩家，如果有计数要求就用等级来计数，最高的话，按照游戏设定是20层？」
 * — 雪菲's 冰龙之印 is not a new entity, a new subsystem or a new attachment: it is a
 * {@code MobEffectInstance} on her, its <em>level</em> is how many marks she holds, and that level is what her
 * other skills read.
 *
 * <h2>Accumulating is the point — do not "fix" this into a replacement</h2>
 * <p><b>Re-applying this effect ADDS one stack and refreshes the duration.</b> It is the one effect here that
 * accumulates on purpose, and it is deliberately unlike {@code SHIELD}, which was corrected in task 41 to be
 * <em>restored</em> rather than accumulated («ten swings are worth one shield»). The two are not
 * inconsistent; they are opposites because the fictions are opposite:
 *
 * <ul>
 *   <li>A shield is a quantity of health. Ten hits that each grant a shield must not add up to ten shields, or
 *       a defensive skill becomes a stronger defensive skill the more often it fires.</li>
 *   <li>A counter is a <b>tally</b>. 「获得4个『冰龙之印』」 means she now holds four, and the next
 *       「获得5个」 means she holds nine. A tally that replaced instead of added would make every mark-granting
 *       skill except the last one pointless.</li>
 * </ul>
 *
 * <p>So if you are here to make this behave like the shield: <b>no.</b> The accumulate rule is what the effect
 * <em>is</em>, it is pinned by {@code CounterTest.addsOneStackAtATimeAndNeverReplaces}, and the cap below is
 * the only thing that stops it.
 *
 * <h2>The cap comes from {@link EffectTiers}</h2>
 * The ladder is the single tier table, like every other tiered effect: {@code COUNTER}'s rule is
 * <b>5 rungs, 4 marks a rung</b>, so
 *
 * <pre>
 *   tier  1   2   3    4    5
 *   cap   4   8  12   16   20      capForTier(tier) = 4 x tier
 * </pre>
 *
 * <p>20 at the top rung is 雪菲's 6★ 「最多可获得20个」 exactly, which is where the human's 「最高…20层」 comes
 * from. A declared cap that is not on the ladder is mapped to the <b>smallest rung that does not truncate
 * it</b> ({@link #tierForStatedCap}), so her base ultimate's 「最大10个」 becomes tier 3 (cap 12): the ladder's
 * grain is four marks, and a cap of 10 has no rung of its own. The two marks of headroom are recorded in her
 * json's {@code _notes} so a reader does not mistake 12 for a mistyped 10.
 *
 * <h2>Who consumes it</h2>
 * 雪菲's 技能1 / 技能2 / 专属 all begin 「移除自身1个『冰龙之印』」 and then scale with how many she
 * <em>held</em>. That is {@code EffectType.CONSUME_COUNTER}, whose executor calls {@link #consume} and then
 * reads {@link #countOf} <b>before</b> the removal — see {@code EffectHook.ConsumeCounter} for why both numbers
 * are needed.
 *
 * <h2>Failure policy</h2>
 * Nothing here throws. The effect is read from a damage path (a skill consuming a mark mid-swing), where an
 * entity with no effect map must answer "no marks" rather than take the hit down with it.
 */
@ApiStatus.Internal
public final class Counters {

    /** Registry path: {@code astrum:counter}. */
    public static final String PATH = "counter";

    /** {@code astrum:counter}. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** Particle tint: the pale ice-blue of 冰龙之印. */
    public static final int COLOUR = 0x8FD5F0;

    /** The hardest cap any rung allows, {@code capForTier(lastRung)} — 20 for the shipped ladder. */
    public static final int MAX_STACKS = 20;

    /** Clamp notices, once per (entity kind, cap) rather than once per swing. */
    private static final WarnOnce REPORTED = new WarnOnce();

    private Counters() {
    }

    /**
     * {@return how many marks one rung of the ladder allows}
     *
     * <p>{@code 0} for a tier outside the ladder, which only happens if this class is called before the
     * table is consulted at all — a caller then applies nothing rather than inventing a cap.
     *
     * @param tier a tier, clamped into {@code [1, maxTier]} on the way in
     */
    public static int capForTier(int tier) {
        EffectTiers.Rule rule = EffectTiers.ruleOf(EffectType.COUNTER);
        if (rule == null) {
            return 0;
        }
        int clamped = Math.max(EffectTiers.FIRST_TIER, Math.min(rule.maxTier(), tier));
        return (int) Math.round(rule.fractionAt(clamped));
    }

    /**
     * {@return the rung whose cap is the smallest one that does not truncate {@code statedCap}}
     *
     * <p>This is the one conversion between a bwiki number and a rung, so it lives here rather than being
     * re-done by hand in each character's json: 雪菲's 「最大10个」 is rung 3 (cap 12) and her 6★
     * 「最多可获得20个」 is rung 5 (cap 20).
     *
     * @param statedCap the cap as bwiki states it, in marks; a non-positive cap answers the first rung
     */
    public static int tierForStatedCap(int statedCap) {
        EffectTiers.Rule rule = EffectTiers.ruleOf(EffectType.COUNTER);
        if (rule == null) {
            return EffectTiers.FIRST_TIER;
        }
        int wanted = Math.max(1, statedCap);
        for (int tier = EffectTiers.FIRST_TIER; tier <= rule.maxTier(); tier++) {
            if (capForTier(tier) >= wanted) {
                return tier;
            }
        }
        return rule.maxTier();
    }

    /**
     * {@return the effect's holder, or empty before the registry is up}
     *
     * <p>The {@code isBound} test is the only safe question to ask a {@code DeferredHolder} before the
     * {@code RegisterEvent} has run — the same helper shape {@code PartyEffects} uses.
     */
    public static Optional<Holder<MobEffect>> holder() {
        return PartyEffects.counterHolder();
    }

    /**
     * {@return how many marks {@code entity} holds right now, {@code 0} when it has none}
     *
     * <p>The count is the <b>amplifier + 1</b>: a {@code MobEffectInstance} cannot have amplifier
     * {@code -1}, so "one mark" is amplifier {@code 0} and the count is never 0 while the effect is present.
     * This is the reading every consumer uses, so it is written once.
     *
     * @param entity the body to read; {@code null} answers {@code 0}
     */
    public static int countOf(@Nullable LivingEntity entity) {
        OptionalInt amplifier = amplifierOf(entity);
        return amplifier.isPresent() ? amplifier.getAsInt() + 1 : 0;
    }

    /**
     * {@return the instance's amplifier, or empty when there is no counter on this body}
     *
     * @param entity the body to read; {@code null} answers empty
     */
    public static OptionalInt amplifierOf(@Nullable LivingEntity entity) {
        Optional<Holder<MobEffect>> holder = holder();
        if (entity == null || holder.isEmpty()) {
            return OptionalInt.empty();
        }
        MobEffectInstance instance = entity.getEffect(holder.get());
        return instance == null ? OptionalInt.empty() : OptionalInt.of(instance.getAmplifier());
    }

    /**
     * Adds one mark, refreshes the duration, and clamps at the rung's cap.
     *
     * <h2>Why one mark and not "the whole grant"</h2>
     * Because that is what a tally is. 「获得4个」 is four applications, and the arithmetic is the same either
     * way: {@link #add} is the single place a count moves, so a grant of four cannot disagree with four grants
     * of one.
     *
     * <h2>Clamping is logged once</h2>
     * A cap that is reached is an ordinary outcome, not a defect — 雪菲's marks cap out on purpose. So the
     * line is at {@code DEBUG} and it is written once per {@code (effect, cap)} through {@link WarnOnce}:
     * an operator debugging "why did my twentieth mark not arrive" gets the answer without a line per swing.
     *
     * @param entity the body to add to; {@code null} is a no-op
     * @param tier   the rung that sets the cap, from {@link #tierForStatedCap}
     * @param ticks  how long the refreshed instance lasts
     * @return the count after this call
     */
    public static int add(@Nullable LivingEntity entity, int tier, int ticks) {
        Optional<Holder<MobEffect>> holder = holder();
        if (entity == null || holder.isEmpty() || ticks <= 0) {
            return countOf(entity);
        }
        int cap = capForTier(tier);
        if (cap <= 0) {
            return countOf(entity);
        }
        int current = countOf(entity);
        if (current >= cap) {
            // Refresh anyway: a tally at its cap still stops counting down while the source keeps granting.
            applyInstance(entity, holder.get(), ticks, cap - 1);
            if (REPORTED.first(ID + "|cap|" + cap)) {
                Astrum.LOGGER.debug("astrum: {} is at its cap of {} marks; further grants clamp (this line "
                        + "is reported once per cap)", name(entity), cap);
            }
            return cap;
        }
        int next = nextCount(current, cap);
        applyInstance(entity, holder.get(), ticks, next - 1);
        Astrum.LOGGER.debug("astrum: counter {} -> {}/{} for {}t on {}", current, next, cap, ticks,
                name(entity));
        return next;
    }

    /**
     * {@return the count one mark takes {@code current} to, clamped at {@code cap}}
     *
     * <p><b>The accumulating rule, as a pure function.</b> This is the whole of "a tally adds, it does not
     * replace", and it is a separate method so that the rule has a test with no world in it — the same seam
     * {@code EffectExecutors.reachesCap} provides for "once per fill". {@code current >= cap} answers
     * {@code cap}: a grant past the ceiling clamps rather than wrapping or growing.
     *
     * @param current how many marks the body holds now
     * @param cap     the rung's cap; a non-positive cap answers {@code current} unchanged
     */
    static int nextCount(int current, int cap) {
        if (cap <= 0) {
            return Math.max(0, current);
        }
        return Math.min(cap, Math.max(0, current) + 1);
    }

    /**
     * {@return how many marks a request for {@code marks} actually removes from {@code current}}
     *
     * <p>「移除自身1个」 on an empty tally removes nothing; on a tally of five it removes one and leaves four.
     * The other half of the accumulating rule, and pure for the same reason: a consumer that trusted the
     * number it asked for instead of this one would report a spend that did not happen.
     *
     * @param current how many marks the body holds now
     * @param marks   how many the caller wants removed
     */
    static int consumable(int current, int marks) {
        if (marks <= 0 || current <= 0) {
            return 0;
        }
        return Math.min(current, marks);
    }

    /**
     * Adds {@code marks} marks, one at a time, through {@link #add}.
     *
     * @param entity the body to add to
     * @param marks  how many marks the grant is worth; non-positive is a no-op
     * @param tier   the rung that sets the cap
     * @param ticks  how long the refreshed instance lasts
     * @return the count after this call
     */
    public static int addMarks(@Nullable LivingEntity entity, int marks, int tier, int ticks) {
        int count = countOf(entity);
        for (int i = 0; i < marks; i++) {
            count = add(entity, tier, ticks);
        }
        return count;
    }

    /**
     * Removes up to {@code marks} marks and answers how many were actually removed.
     *
     * <p>Removing more than she holds removes what she has and answers the smaller number — that is what
     * 「移除自身1个」 means when she holds none, and it is why the caller must use the returned number rather
     * than the number it asked for. The last mark removes the instance entirely (a count of 0 is not an
     * amplifier of {@code -1}).
     *
     * @param entity the body to consume from; {@code null} answers {@code 0}
     * @param marks  how many to remove; non-positive is a no-op
     * @return how many were removed
     */
    public static int consume(@Nullable LivingEntity entity, int marks) {
        Optional<Holder<MobEffect>> holder = holder();
        if (entity == null || holder.isEmpty() || marks <= 0) {
            return 0;
        }
        int current = countOf(entity);
        if (current <= 0) {
            return 0;
        }
        int removed = consumable(current, marks);
        int left = current - removed;
        if (left <= 0) {
            entity.removeEffect(holder.get());
        } else {
            MobEffectInstance existing = entity.getEffect(holder.get());
            int ticks = existing == null ? 0 : existing.getDuration();
            applyInstance(entity, holder.get(), ticks, left - 1);
        }
        Astrum.LOGGER.debug("astrum: counter {} - {} = {} on {}", current, removed, left, name(entity));
        return removed;
    }

    /**
     * Applies one instance at a given amplifier, keeping the effect's own visibility flags.
     *
     * <p>Vanilla's {@code LivingEntity.addEffect} merges an incoming instance into an existing one by taking
     * the <b>larger amplifier</b> and the <b>longer duration</b>, which is exactly the accumulate-and-refresh
     * rule this class needs — so nothing here fights it. What this method guarantees is the <em>input</em>:
     * the amplifier handed over is the new count minus one, computed by the caller.
     */
    private static void applyInstance(LivingEntity entity, Holder<MobEffect> holder, int ticks, int amplifier) {
        if (ticks <= 0) {
            return;
        }
        entity.addEffect(new MobEffectInstance(holder, ticks, Math.max(0, amplifier), false, true, true));
    }

    /** {@return the name used in log lines; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
