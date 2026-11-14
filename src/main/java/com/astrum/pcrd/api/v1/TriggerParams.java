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
 */
@ApiStatus.Experimental
public record TriggerParams(int hits, Optional<ResourceLocation> stackId, Optional<String> skill) {
    public static final TriggerParams NONE = new TriggerParams(0, Optional.empty(), Optional.empty());

    public static final Codec<TriggerParams> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("hits", 0).forGetter(TriggerParams::hits),
            ResourceLocation.CODEC.optionalFieldOf("stackId").forGetter(TriggerParams::stackId),
            Codec.STRING.optionalFieldOf("skill").forGetter(TriggerParams::skill)
    ).apply(instance, TriggerParams::new));

    /**
     * The two-field form every pre-{@code skill} caller uses: no skill tag, so this hook rotates on
     * its own.
     *
     * <p>Kept as a constructor rather than making callers pass {@code Optional.empty()} so that the
     * meaning of the new field is stated once, here, instead of at a dozen call sites.
     */
    public TriggerParams(int hits, Optional<ResourceLocation> stackId) {
        this(hits, stackId, Optional.empty());
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
}
