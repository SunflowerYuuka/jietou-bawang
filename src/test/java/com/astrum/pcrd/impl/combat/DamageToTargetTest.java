package com.astrum.pcrd.impl.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>{@code splash} / {@code detonate}'s secondary damage carries the skill's crit multiplier</b> — the defect
 * {@code agent-30} found.
 *
 * <h2>The bug, stated exactly</h2>
 * Task 55 gave a hook its own crit multiplier (「本技能暴击 4 倍」), and the flush uses it for the amounts filed in
 * the hit's {@link DamageBatch}. But the radius of {@code splash} and {@code detonate} files its damage through
 * {@link DamageToTarget}, which had <b>no</b> multiplier parameter — so the primary hit of a 4× skill crit at 4×
 * while its splash crit at the caster's global 1.5×. A skill's crit is a property of the skill, not of the one body
 * it happened to land on first.
 *
 * <h2>What is asserted, and what cannot be</h2>
 * A real splash needs a {@code ServerPlayer}, a {@code LivingEntity} and a {@code ServerLevel}, none of which a
 * unit test can build, so this file asserts the two pieces the fix is made of:
 *
 * <ol>
 *   <li>the <b>sink's tag</b> ({@link DamageToTarget#tagged}): an amount filed through a hook-tagged sink arrives
 *       with that hook's multiplier, an untagged one arrives with none;</li>
 *   <li>the <b>batch's second key</b> ({@link DamageBatch#tagged}): a tagged amount is filed — and therefore
 *       flushed — under that multiplier, and re-adding it during a merge cannot drop it.</li>
 * </ol>
 *
 * <p>What that leaves for a human is the arithmetic of one splash in a real world; see
 * {@code task-58-report.md}.
 */
class DamageToTargetTest {

    private static final ResourceLocation HOOK_DAMAGE = ResourceLocation.parse("astrum:hook_damage");

    /** One amount as a sink received it; {@code target} is unused by every assertion here. */
    private record Filed(ResourceLocation type, float amount, OptionalDouble critMultiplier) {
    }

    /** {@return the four-argument sink a recording list is behind, so {@code tagged} can wrap it} */
    private static DamageToTarget sink(List<Filed> into) {
        return (target, type, amount, critMultiplier) -> into.add(new Filed(type, amount, critMultiplier));
    }

    /**
     * <b>A tagged sink files every secondary amount under the skill's multiplier.</b>
     *
     * <p>This is the shape {@code HookContext.forHook} hands the executor: the executor still calls
     * {@code add(other, type, damage)} and three bodies get the same 4× the primary hit uses.
     */
    @Test
    void everySecondaryAmountCarriesTheSkillsMultiplier() {
        List<Filed> filed = new ArrayList<>();
        DamageToTarget tagged = sink(filed).tagged(OptionalDouble.of(4.0));
        LivingEntity[] bodies = {null, null, null};
        for (LivingEntity body : bodies) {
            tagged.add(body, HOOK_DAMAGE, 10.0F);
        }
        assertEquals(3, filed.size(), "a splash lands on every body in the radius");
        for (Filed one : filed) {
            assertEquals(OptionalDouble.of(4.0), one.critMultiplier(),
                    "a splash from a 4× skill is 4× on every body it lands on");
            assertEquals(HOOK_DAMAGE, one.type());
            assertEquals(10.0F, one.amount(), 1e-6);
        }
    }

    /**
     * <b>An untagged sink leaves secondary damage on the caster's global multiplier</b> — the pre-task-58
     * behaviour, which is still correct for every hook that declares no value.
     */
    @Test
    void anUntaggedSinkFilesNothingAndTheGlobalApplies() {
        List<Filed> filed = new ArrayList<>();
        DamageToTarget plain = sink(filed);
        assertSame(plain, plain.tagged(OptionalDouble.empty()),
                "the empty tag is the same object: the ordinary hook allocates nothing");
        assertSame(plain, plain.tagged(null), "a null tag is the same as an empty one, not a crash");
        plain.add(null, HOOK_DAMAGE, 5.0F);
        assertEquals(1, filed.size());
        assertTrue(filed.get(0).critMultiplier().isEmpty(),
                "no value means the flush uses the caster's astrum:crit_damage");
    }

    /** A tag fills an <b>absence</b>; it never overwrites a value the caller stated. */
    @Test
    void aTagDoesNotOverwriteAStatedValue() {
        List<Filed> filed = new ArrayList<>();
        DamageToTarget tagged = sink(filed).tagged(OptionalDouble.of(4.0));
        tagged.add(null, HOOK_DAMAGE, 1.0F);
        tagged.add(null, HOOK_DAMAGE, 2.0F, OptionalDouble.of(2.5));
        assertEquals(OptionalDouble.of(4.0), filed.get(0).critMultiplier(), "the tag fills the gap");
        assertEquals(OptionalDouble.of(2.5), filed.get(1).critMultiplier(),
                "an amount that already carries its own multiplier keeps it");
    }

    /**
     * <b>The amount reaches the flush under the skill's multiplier, and a merge cannot downgrade it.</b>
     *
     * <p>{@code CombatEvents.queueDamage} builds a batch exactly this way, and {@code CombatEvents.merge} re-adds a
     * queued entry through {@code tagged(entry.critMultiplier())}. A batch that lost the tag at either step would
     * flush a 4× skill's splash at 1.5× with nothing in the log to say so.
     */
    @Test
    void aSecondaryAmountIsFlushedUnderTheSkillsMultiplier() {
        DamageBatch batch = new DamageBatch();
        batch.tagged(OptionalDouble.of(4.0)).add(HOOK_DAMAGE, 10.0F);
        assertEquals(1, batch.entries().size());
        assertEquals(OptionalDouble.of(4.0), batch.entries().get(0).critMultiplier(),
                "the batch keeps the multiplier the sink was tagged with");

        // The merge step: the same entry re-added into another batch, tagged the way merge() does it.
        DamageBatch merged = new DamageBatch();
        for (DamageBatch.Entry entry : batch.entries()) {
            merged.tagged(entry.critMultiplier()).add(entry.type(), entry.amount());
        }
        assertEquals(OptionalDouble.of(4.0), merged.entries().get(0).critMultiplier(),
                "merging two batches of one tick cannot turn a 4× splash back into a 1.5× one");

        // One type at two multipliers is two entries, not one wrong hit.
        merged.tagged(OptionalDouble.empty()).add(HOOK_DAMAGE, 1.0F);
        assertEquals(2, merged.entries().size(),
                "the same damage type at two crit multipliers cannot be merged into one number");
        assertEquals(List.of(OptionalDouble.of(4.0), OptionalDouble.empty()),
                merged.entries().stream().map(DamageBatch.Entry::critMultiplier).toList());
    }
}