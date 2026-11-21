package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>「随损失的生命值提升」 — a clause whose magnitude grows as the caster's health falls.</b>
 *
 * <h2>The clauses this exists for</h2>
 * 咲恋's whole kit is built on it: 「凤凰之剑 … 外加【0.2*损失的生命值】」, 「凤凰终焉 … +【2.5*损失的生命值】」, and her
 * 专属 烈焰斩击+ carries 【0.4*损失的生命值】. Until this class those terms were recorded as unimplementable and the
 * shipped values were the ones at <b>full health</b>, where every such term is zero — so the clause did nothing at
 * all in play, which is the same as not implementing it.
 *
 * <h2>The unit, and why the data does not store a bwiki coefficient</h2>
 * The source's coefficient multiplies <b>game HP</b> (0.2 HP of damage per HP lost). This build's amounts are in
 * its own units, where {@code 20 units = 22140 game HP} — the reference bar {@code docs/balance-derivation.md}
 * derives everything at. So the data stores <b>units added over one full bar of lost health</b>, and the two are
 * related by {@link #UNITS_PER_BAR}:
 *
 * <pre>
 *   declared = bwiki coefficient × 22140 / 1107        (1107 = 22140 / 20, i.e. game HP per unit)
 *   0.2  →  4.0      0.4  →  8.0      2.5  →  50.0
 * </pre>
 *
 * <p>Storing the converted number rather than the coefficient keeps every other number in the data in the same
 * units as the damage it is added to; a coefficient would make this one field the single place in the codebase
 * that speaks game HP.
 *
 * <h2>Why the fraction is of <em>lost</em> health, and why it can never be negative</h2>
 * {@code missing = 1 - health / maxHealth}, clamped into {@code [0, 1]}: a body at full health gets nothing (which
 * is exactly the full-health value the old notes shipped), and a body that is somehow above its maximum — a
 * modifier race, a healing overflow — gets nothing rather than a negative bonus that would <em>reduce</em> the
 * damage. The dead cannot cast, so the top of the range is never reached in practice; the arithmetic still has to
 * be defined for it.
 */
@ApiStatus.Internal
public final class LostHealth {

    /**
     * Game HP per this build's unit: {@code 22140 / 20}. The divisor that turns a bwiki coefficient into a
     * declared value — see the class javadoc's arithmetic.
     */
    public static final double HP_PER_UNIT = 1107.0;

    /** Game HP in the reference bar, the numerator of the same conversion. */
    public static final double REFERENCE_BAR = 22140.0;

    private LostHealth() {
    }

    /**
     * {@return the fraction of its maximum health {@code caster} has lost, in {@code [0, 1]}}
     *
     * <p>Never throws and never answers outside the range: a null body, a non-positive maximum, a NaN or an
     * infinite health all answer {@code 0}, i.e. "no bonus", because this is read off live entity state.
     *
     * @param caster the body whose health is read; {@code null} answers {@code 0}
     */
    public static double missingFraction(@Nullable LivingEntity caster) {
        if (caster == null) {
            return 0.0;
        }
        float max = caster.getMaxHealth();
        float health = caster.getHealth();
        if (!(max > 0.0F) || !Float.isFinite(max) || !Float.isFinite(health)) {
            return 0.0;
        }
        double missing = 1.0 - ((double) health / (double) max);
        if (!(missing > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, missing);
    }

    /**
     * {@return the units to add for a clause that declared {@code unitsPerBar} over one full lost bar}
     *
     * <p>The whole rule, and the only copy of it: {@code declared × missingFraction}. A declared value of {@code 0}
     * (the default the codec gives an absent field) answers {@code 0} for every body, so a clause that does not
     * scale behaves exactly as it did before the field existed.
     *
     * @param unitsPerBar the declared amount, in this build's units, at a fully depleted health bar
     * @param caster      the body whose lost health is read
     */
    public static float bonusUnits(double unitsPerBar, @Nullable LivingEntity caster) {
        if (!Double.isFinite(unitsPerBar) || unitsPerBar <= 0.0) {
            return 0.0F;
        }
        double fraction = missingFraction(caster);
        if (fraction <= 0.0) {
            return 0.0F;
        }
        float bonus = (float) (unitsPerBar * fraction);
        if (!Float.isFinite(bonus) || bonus <= 0.0F) {
            return 0.0F;
        }
        Astrum.LOGGER.debug("astrum: lost-health bonus {} of a declared {} at {} missing", bonus, unitsPerBar,
                fraction);
        return bonus;
    }

    /**
     * {@return the bwiki coefficient expressed in this build's units} — the conversion the class javadoc states.
     *
     * <p>Here so a future datapack author reading a bwiki line can produce the number the data needs without
     * re-deriving {@link #HP_PER_UNIT}.
     *
     * @param bwikiCoefficient the source's 「X * 损失的生命值」 coefficient, e.g. 咲恋's {@code 0.2}
     */
    public static double unitsForBwikiCoefficient(double bwikiCoefficient) {
        if (!Double.isFinite(bwikiCoefficient)) {
            return 0.0;
        }
        return bwikiCoefficient * REFERENCE_BAR / HP_PER_UNIT;
    }
}
