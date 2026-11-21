package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>How many marks of {@link EffectType#COUNTER} an ultimate grants.</b>
 *
 * <h2>Why this is a block and not a rung</h2>
 * 雪菲's two ultimates both say 「对自身追加【5+计数器枚数】个『冰龙之印』」 — the number granted is a
 * <b>formula over the tally she is already holding</b>, evaluated at cast time, plus a cap the wiki states
 * separately (「最大10个」 / 「最多可获得20个」). {@link TieredParams} carries a rung, a clock and a reach, and
 * none of the three can hold that formula: writing 「5+计数」 as a rung would be exactly the defect task 39
 * removed from the EX skills (a formula replaced by a percentage, with 「技能等级」 disappearing from the
 * mechanic). So the grant travels in its own block and {@code Counters} evaluates it.
 *
 * <p>{@code base + perMark × <marks held>} is the whole of it: 雪菲's base ultimate is
 * {@code base 5, perMark 1}, her 6★ is {@code base 7, perMark 1}, and a constant grant (「追加4个」) is
 * {@code perMark 0}.
 *
 * <h2>The cap is the number the source states, not the number applied</h2>
 * The applied cap is the ladder rung's, read from {@link EffectTiers} through
 * {@code Counters.tierForStatedCap} — so the ladder keeps its one copy, and a grant that would pass the rung's
 * cap clamps and says so once. The stated number stays in the data because it is the <em>provenance</em> of the
 * rung choice: a reader must be able to check that 「最大10个」 really became rung 3 (cap 12) and not rung 2
 * (cap 8, which would cut her off two marks early).
 *
 * @param base    marks granted before the per-mark part; a non-positive value grants none of this part
 * @param perMark marks granted per mark already held; {@code 0} for a constant grant
 * @param cap     the cap as the source material states it, in marks
 */
@ApiStatus.Experimental
public record UbCounterGrant(int base, int perMark, int cap) {

    public static final Codec<UbCounterGrant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("base", 1).forGetter(UbCounterGrant::base),
            Codec.INT.optionalFieldOf("perMark", 0).forGetter(UbCounterGrant::perMark),
            Codec.INT.fieldOf("cap").forGetter(UbCounterGrant::cap)
    ).apply(instance, UbCounterGrant::new));

    /**
     * {@return how many marks this grant is worth to a caster holding {@code held} marks}
     *
     * @param held how many marks the caster holds right now; negative is treated as none
     */
    public int marksFor(int held) {
        int already = Math.max(0, held);
        return Math.max(0, base) + Math.max(0, perMark) * already;
    }
}
