package com.astrum.pcrd.api.v1;

import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>The three axes an {@link EffectType} can be on</b> — the questions a caller asks about one, in one
 * place, so no switch has to be written twice with the risk of the two disagreeing.
 *
 * <h2>Why these sets live beside {@link EffectType} rather than in each consumer</h2>
 * {@link EffectType} already answers "who does this reach" ({@link EffectType#reachesParty()},
 * {@link EffectType#reachesVictim()}). The two questions added with the flat EX effects are of the same
 * kind — a property of what the effect <em>is</em>, not of who applies it:
 *
 * <ul>
 *   <li><b>flat</b> — the effect writes a number of attribute points computed from a level, and has no
 *       tier ladder and no {@code MobEffect} ({@link EffectType#FLAT_ATTACK_UP},
 *       {@link EffectType#FLAT_MAGIC_ATTACK_AREA}… see {@link #flat()});</li>
 *   <li><b>tiered</b> — the effect has a rung ladder in {@link EffectTiers}; this is just a readable
 *       delegation of {@link EffectTiers#isTiered} so a caller does not need two classes.</li>
 * </ul>
 *
 * <p>Writing them here means {@code ExPassive.Clause}, the EX applier and the tests all read the same
 * answer, which is the "do not duplicate a rule" requirement applied to a classification rather than a
 * number.
 */
@ApiStatus.Experimental
public final class EffectTypes {

    /**
     * The effects whose magnitude is a <b>flat number of attribute points</b>, not a fraction and not a
     * rung.
     *
     * <p>Two members, and they differ only in which attribute they write. Both take an
     * {@link FlatAmount} formula evaluated at the character's level, because the source formulas are
     * 「base + perLevel × 技能等级」 — a point count, never a percentage. See
     * {@link EffectType#FLAT_ATTACK_UP} for why a percentage cannot express them.
     */
    private static final Set<EffectType> FLAT = Set.of(EffectType.FLAT_ATTACK_UP,
            EffectType.FLAT_MAGIC_ATTACK_UP);

    private EffectTypes() {
    }
    /** {@return the flat-amount effects, for a message or an assertion} */
    public static Set<EffectType> flat() {
        return FLAT;
    }

    /**
     * {@return whether {@code type} is a flat-amount effect}
     *
     * @param type the effect, may be {@code null}
     */
    public static boolean isFlat(EffectType type) {
        return type != null && FLAT.contains(type);
    }

    /**
     * {@return whether {@code type} has a tier ladder}
     *
     * @param type the effect, may be {@code null}
     */
    public static boolean isTiered(EffectType type) {
        return EffectTiers.isTiered(type);
    }

    /**
     * {@return whether a flat effect writes the <b>magic</b> attribute rather than the physical one}
     *
     * <p>The one question the applier needs in order to pick an attribute, asked here so the mapping
     * {@code type → attribute} exists once. A type that is not flat answers {@code false}, which is the
     * harmless direction: the caller has already decided it is flat.
     *
     * @param type the effect, may be {@code null}
     */
    public static boolean isMagicFlat(EffectType type) {
        return type == EffectType.FLAT_MAGIC_ATTACK_UP;
    }
}
