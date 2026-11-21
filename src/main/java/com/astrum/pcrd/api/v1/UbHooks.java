package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The hooks an ultimate fires on the bodies its damage lands on</b> — one list per tier.
 *
 * <h2>Why this is not a field on {@link UbTier}</h2>
 * It is the same information (which hooks does this rung of the ultimate carry), and the honest home would
 * have been the tier. But {@code UbTier} is a shared, concurrently edited schema record in this change
 * window, and a second writer appending a field there is exactly the silent-overwrite this project's
 * concurrent-work rules forbid. {@link UbDef} is a different record and a different question — it already
 * owns "the two rungs and the icon shared by them" — so the per-tier lists live here, keyed by which rung,
 * and {@link UbTier} stays untouched.
 *
 * <h2>Why the lists are {@link EffectHook}s at all</h2>
 * The alternative was a handful of bespoke fields ({@code victimEffect}, {@code victimDuration}, …), and that
 * is the shape this project replaced once already: an ultimate effect used to be four fields with four
 * executors and four answers to "what does casting this do". A hook is the existing vocabulary for "a
 * trigger plus an effect", and {@link Trigger#UB_LAND} is the trigger an ultimate was missing. So a UB debuff
 * is written exactly like a swing's debuff — {@code apply_effect} with a vanilla effect id — and it runs
 * through the same executor, the same collapse, the same tier rules and the same chance roll. Nothing about
 * {@code apply_effect} is duplicated here.
 *
 * <h2>When it fires, and why that is not the hook list on {@link CharacterDef}</h2>
 * These hooks are <b>not</b> part of the rotating small-skill list: {@code effects[]} is read by
 * {@code SkillGroups} and advanced by the rotation cursor, and putting a UB clause there would give the
 * character an extra small skill that never comes up. They fire from {@code UbLandHooks}, once per body the
 * ultimate's own hits landed on, after {@code hurt()} answered true. See {@link Trigger#UB_LAND}.
 *
 * <h2>Additive, like every other optional block</h2>
 * A pack written before this record existed declares no {@code ub.landHooks} and keeps loading with two
 * empty lists, which is exactly what it did before. A declared hook is validated by {@link #mismatch()} —
 * its trigger must be {@link Trigger#UB_LAND} — and a mismatch is reported and dropped rather than silently
 * never firing.
 *
 * @param base     hooks fired by the base ultimate's landed hits; empty for none
 * @param enhanced hooks fired by the 6★ ultimate's landed hits; empty for none
 */
@ApiStatus.Experimental
public record UbHooks(List<EffectHook> base, List<EffectHook> enhanced) {

    /** No hooks at either tier: what a pack that declares none means. */
    public static final UbHooks NONE = new UbHooks(List.of(), List.of());

    public static final Codec<UbHooks> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            EffectHook.CODEC.listOf().optionalFieldOf("base", List.of()).forGetter(UbHooks::base),
            EffectHook.CODEC.listOf().optionalFieldOf("enhanced", List.of()).forGetter(UbHooks::enhanced)
    ).apply(instance, UbHooks::new));

    public UbHooks {
        base = copy(base);
        enhanced = copy(enhanced);
    }

    /**
     * {@return the hooks one rung fires, in declaration order}
     *
     * @param enhanced whether the 6★ rung fired
     */
    public List<EffectHook> forTier(boolean enhanced) {
        return enhanced ? enhanced() : base();
    }

    /** {@return every hook declared on either rung, for validation and for a resource test} */
    public List<EffectHook> all() {
        if (base.isEmpty()) {
            return enhanced;
        }
        if (enhanced.isEmpty()) {
            return base;
        }
        List<EffectHook> both = new ArrayList<>(base.size() + enhanced.size());
        both.addAll(base);
        both.addAll(enhanced);
        return List.copyOf(both);
    }

    /** {@return whether neither rung declares a hook} */
    public boolean isEmpty() {
        return base.isEmpty() && enhanced.isEmpty();
    }

    /**
     * {@return why a declared hook cannot work here, or empty when every hook is legal}
     *
     * <h2>Why the check is here and not in the codec</h2>
     * A wrong trigger is individually decodable (every {@link Trigger} is a legal value) and only wrong
     * <b>in this position</b>: a {@code melee_hit} hook under {@code ub.landHooks} would never fire because
     * the ultimate's facts observe {@code ub_land} and nothing else. That is the silent no-op this project
     * forbids, so it is reported by the reader (once) instead of being applied as if it worked. The message
     * names the requirement because its two readers — a pack author reading a WARN and a test's failure
     * output — need the fix, not the verdict.
     */
    public Optional<String> mismatch() {
        for (int index = 0; index < base.size(); index++) {
            Optional<String> problem = problemOf(base.get(index));
            if (problem.isPresent()) {
                return Optional.of("landHooks.base[" + index + "] " + problem.get());
            }
        }
        for (int index = 0; index < enhanced.size(); index++) {
            Optional<String> problem = problemOf(enhanced.get(index));
            if (problem.isPresent()) {
                return Optional.of("landHooks.enhanced[" + index + "] " + problem.get());
            }
        }
        return Optional.empty();
    }

    /**
     * {@return why one hook cannot fire from an ultimate, or empty when it can}
     *
     * <p>Total and never throwing: this is reached from a decode-and-log path and from a settlement, where a
     * throw would be worse than a wrong hook.
     */
    public static Optional<String> problemOf(EffectHook hook) {
        if (hook == null) {
            return Optional.of("is null");
        }
        if (hook.trigger() != Trigger.UB_LAND) {
            return Optional.of("declares trigger " + hook.trigger().getSerializedName()
                    + ", but a hook under ub.landHooks is evaluated when the ultimate lands and only "
                    + Trigger.UB_LAND.getSerializedName() + " is observed there; it would never fire");
        }
        return Optional.empty();
    }

    /** {@return {@code hooks} with {@code null} entries dropped; a null list is empty} */
    private static List<EffectHook> copy(List<EffectHook> hooks) {
        if (hooks == null || hooks.isEmpty()) {
            return List.of();
        }
        List<EffectHook> copied = new ArrayList<>(hooks.size());
        for (EffectHook hook : hooks) {
            if (hook != null) {
                copied.add(hook);
            }
        }
        return List.copyOf(copied);
    }
}