package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Extra parameters a trigger needs. Everything is optional so that simple triggers stay
 * declarative.
 *
 * @param hits    hit count for {@link Trigger#COMBO_N}, {@code 0} when unused
 * @param stackId stack that {@link Trigger#STACK_FULL} watches, empty when unused
 * @param skill   which <b>skill</b> this hook belongs to, for {@link Trigger#MELEE_HIT}; empty means
 *                "a skill of its own". Two hooks carrying the same tag are two <em>effects of one
 *                skill</em>: they fire together on that skill's swing and share one turn of the
 *                rotation. See {@code HookPlan} for the rotation rule this feeds.
 * @param requiresState a <b>state the caster must be holding</b> for this hook to fire, empty for a hook
 *                that fires unconditionally. See {@link #requiresState()} for what a "state" is here and
 *                why it is a marker effect rather than a second tally.
 */
@ApiStatus.Experimental
public record TriggerParams(int hits, Optional<ResourceLocation> stackId, Optional<String> skill,
                            Optional<ResourceLocation> requiresState) {
    public static final TriggerParams NONE = new TriggerParams(0, Optional.empty(), Optional.empty(),
            Optional.empty());

    public static final Codec<TriggerParams> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("hits", 0).forGetter(TriggerParams::hits),
            ResourceLocation.CODEC.optionalFieldOf("stackId").forGetter(TriggerParams::stackId),
            Codec.STRING.optionalFieldOf("skill").forGetter(TriggerParams::skill),
            // Additive and last: a pack written before this field existed keeps decoding, with no state
            // requirement, which is exactly the behaviour it had.
            ResourceLocation.CODEC.optionalFieldOf("requiresState").forGetter(TriggerParams::requiresState)
    ).apply(instance, TriggerParams::new));

    public TriggerParams {
        stackId = stackId == null ? Optional.empty() : stackId;
        skill = skill == null ? Optional.empty() : skill;
        requiresState = requiresState == null ? Optional.empty() : requiresState;
    }

    /**
     * The two-field form every pre-{@code skill} caller uses: no skill tag and no state requirement, so
     * this hook rotates on its own and fires unconditionally.
     *
     * <p>Kept as a constructor rather than making callers pass {@code Optional.empty()} so that the
     * meaning of the new fields is stated once, here, instead of at a dozen call sites.
     */
    public TriggerParams(int hits, Optional<ResourceLocation> stackId) {
        this(hits, stackId, Optional.empty(), Optional.empty());
    }

    /** The three-field form every pre-{@code requiresState} caller uses. */
    public TriggerParams(int hits, Optional<ResourceLocation> stackId, Optional<String> skill) {
        this(hits, stackId, skill, Optional.empty());
    }

    /**
     * {@return the skill tag, or empty when this hook is a skill of its own}
     *
     * <p>Blank is treated as absent: a pack that writes {@code "skill": ""} means "no grouping", not
     * "a skill named the empty string" — the latter would silently merge every tagged hook into one
     * skill.
     */
    public Optional<String> skillTag() {
        return skill.filter(tag -> !tag.isBlank());
    }

    /**
     * {@return the state the caster must hold for this hook to fire, or empty for an unconditional hook}
     *
     * <h2>What a "state" is, and why it is not a new tally</h2>
     * A state is a <b>real {@code MobEffect} instance on the caster</b>, named here by its registry id — a
     * marker effect such as this mod's {@code astrum:wind_blade} / {@code astrum:tea_pot}, or any vanilla
     * effect a pack wants to gate on. "Am I holding X" is therefore <b>one</b> question with one answer
     * ({@code hasEffect}), the state's lifetime is vanilla's own effect clock, and it shows on the player's
     * effect bar for free.
     *
     * <p>The alternative — a second accumulation mechanism keyed by state id — was rejected: this project
     * already has {@code Counters} for a <em>tally</em> (its amplifier is the count), and a state is not a
     * tally. A separate state book would have needed its own expiry sweep (there is no tick listener here),
     * its own logout/shutdown clearing and its own "what is held right now" question, while a marker effect
     * gets all three from vanilla. The two are deliberately different fictions: {@code counter} answers
     * 「how many marks」, a state marker answers 「is this condition on」.
     *
     * <p><b>Why the gate is a predicate on the hook and not a new trigger.</b> 怜's 反击斩 is still one
     * rotating skill: the state changes <em>what it does</em>, not <em>when it is due</em>. A trigger of its
     * own would put the gated branch on its own turn of the rotation, which is a different skill. So the
     * branch stays inside the same {@code triggerParams.skill} group and only its extra effect is gated.
     *
     * <p><b>The data shape, and why the gated hook carries an increment.</b> A predicate can only add a
     * branch, not replace one (there is no "else" arm), so a pack writes the <em>difference</em> the state
     * unlocks: 怜's 反击斩 has a 6.7 base hook always and a 6.6 gated hook, which sums to the 13.3 the
     * wind-blade branch is worth while she holds 风之刃. Writing both branches in full would double-count.
     *
     * <p>Empty and a blank id are both "no requirement"; a malformed id cannot decode, which is the honest
     * failure for a datapack that names a state that does not exist.
     */
    public Optional<ResourceLocation> requiresState() {
        return requiresState;
    }
}