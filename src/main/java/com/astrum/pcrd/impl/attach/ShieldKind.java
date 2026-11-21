package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.api.v1.EffectType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Which half of the damage a shield layer absorbs</b> — the 「两个单独的通道」 the human asked for.
 *
 * <h2>The requirement, in his words</h2>
 * 「物理护盾和魔法护盾，这两个做了吗？没做的话做一下2个单独的通道。」 Before this the mod had one
 * {@code SHIELD} pool that absorbed <b>every</b> damage type, and 由衣's bwiki clause
 * 「无效【45+45*技能等级】<b>物理</b>伤害的护盾」 could not be expressed: her barrier absorbed a magic bolt it
 * was never meant to. The three kinds below are the whole vocabulary the source material needs:
 *
 * <pre>
 *   由衣      「无效【45+45*技能等级】物理伤害的护盾」          -> PHYS
 *   望        「无效【27+27*技能等级】物理·魔法伤害的护盾」    -> BOTH
 *   佩可莉姆   「吸收【22.5+22.5*技能等级】物理·魔法伤害的护盾」 -> BOTH
 *   静流       her bwiki entry names no type                     -> BOTH (status quo)
 * </pre>
 *
 * <h2>Why a kind per <em>layer</em> and not one pool with a flag</h2>
 * Two shields of different kinds can be on one player at once (由衣 and 望 in the same party), and the
 * human's rule for a re-grant is 「覆盖只会覆盖自身」: a grant may replace <b>only its own channel</b>. That
 * needs one layer per kind, which is exactly what {@link ShieldState#layers()} holds — a single pool with a
 * per-grant flag would make the second shield cancel the first, which is the defect the {@code SHIELD} /
 * {@code SHIELD_PHYS} / {@code SHIELD_MAGIC} effect types exist to avoid at the vanilla-instance level
 * too (vanilla merges two instances of one type; three types are three instances).
 *
 * <h2>BOTH is one layer, not two</h2>
 * A 「物理·魔法」 barrier of 27 points is worth <b>27</b>, not 54: 望's own number is the <em>size of the
 * barrier</em>, and a hit of either type spends it. Modelling it as "PHYS 27 and MAGIC 27" would double every
 * both-shield in the game against a mixed fight. So {@link #BOTH} is its own layer whose coverage is both
 * halves, and the two type-restricted layers are for the barriers the source restricts.
 *
 * <h2>Why the specific channel pays first</h2>
 * When two layers both cover a hit (a PHYS barrier and a BOTH one), the type-restricted layer is spent
 * before the shared pool — it is the barrier that can <em>only</em> do this job, so using it first leaves the
 * shared one covering the other type for longer. The order is the enum's own order, which is also the order
 * {@link ShieldState} stores its layers in, so "which one paid" is visible in one place and does not depend
 * on cast order.
 */
@ApiStatus.Internal
public enum ShieldKind {
    /** Absorbs physical damage only — 由衣's 「物理伤害的护盾」. */
    PHYS,
    /** Absorbs magic damage only. No shipped character restricts this way; the channel exists so the split is
     * symmetric and a pack can express 「魔法伤害的护盾」. */
    MAGIC,
    /** Absorbs either half from one pool — 望 / 佩可莉姆 / 静流, and the pre-split behaviour. */
    BOTH;

    /** Every kind, as the coverage mask of a layer set that is fully in the absorption float. */
    public static final int ALL = maskOf(PHYS) | maskOf(MAGIC) | maskOf(BOTH);

    /** {@return the coverage bit of this kind} */
    public int mask() {
        return maskOf(this);
    }

    private static int maskOf(ShieldKind kind) {
        return 1 << kind.ordinal();
    }

    /**
     * {@return whether a layer of this kind absorbs a hit classified as magic / physical}
     *
     * <p>The <b>only</b> place a kind is turned into "does this absorb it"; the split itself is
     * {@code DamageReduction.isMagic}'s, not this enum's, so the project still has one classifier of
     * {@code astrum:magic} / {@code astrum:is_magic} damage.
     *
     * @param magic {@code true} for a hit the damage classifier calls magical
     */
    public boolean covers(boolean magic) {
        return this == BOTH || (this == MAGIC) == magic;
    }

    /** {@return the kind an {@code EffectType} grants} */
    public static ShieldKind of(@Nullable EffectType type) {
        if (type == EffectType.SHIELD_PHYS) {
            return PHYS;
        }
        if (type == EffectType.SHIELD_MAGIC) {
            return MAGIC;
        }
        // SHIELD and anything unknown: the pre-split behaviour, which is the safe reading (a shield that
        // absorbs everything rather than one that silently absorbs nothing).
        return BOTH;
    }

    /** {@return the mask bit for {@code kind}, {@code 0} for {@code null}} */
    public static int maskOfKind(@Nullable ShieldKind kind) {
        return kind == null ? 0 : kind.mask();
    }
}