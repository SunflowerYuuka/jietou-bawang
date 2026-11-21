package com.astrum.pcrd.api.v1;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Locale;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>「提升自身【base + perLevel × 技能等级】物攻」</b> — a point formula, evaluated at the character's level.
 *
 * <h2>Why a formula and not one resolved number</h2>
 * The bwiki gives every EX attack boost in this shape:
 *
 * <pre>
 *   姬塔     龙卷的攻刃        15 + 15×L      龙卷的攻刃+ / 剑圣的天赋   240 + 15×L
 *   可可萝   精灵的启示        15 + 15×L      精灵的启示+                + 30 魔法防御力
 *   凯露     黑暗之蚀          15 + 15×L      黑暗之蚀+                 240 + 15×L
 * </pre>
 *
 * <p>Two ways to put that in a datapack: resolve it once at the reference level (208) and store the number,
 * or store the two coefficients and let the code multiply by the player's level. This record is the
 * <b>second</b>, for one reason: the level axis is real. A Lv1 character and a Lv100 character read the
 * same JSON, and a stored number would make her EX worth the same at both — which is exactly the property
 * the formula exists to express. It also keeps {@code docs/balance-derivation.md} honest: the document
 * holds the coefficients from the wiki, and the JSON holds the same two numbers, so a retune is one edit and
 * the two cannot disagree.
 *
 * <h2>Why it is not a {@link TieredParams} tier</h2>
 * Because a tier ladder is the <em>other</em> axis. {@code EffectTiers} answers "what is rung N worth", and
 * {@code LevelBands} adds a band to it; an EX is explicitly exempt from both (the human's
 * 「EX 技能…不参与等级提升效果」, and §15.4.4 of the derivation document). An EX's strength comes from
 * {@code perLevel × level} and nothing else. Mixing the two would be a second, competing growth curve.
 *
 * <h2>The level used</h2>
 * {@link #amountAt(int)} is called with the <b>character's</b> level from the roster
 * ({@link CharacterProgress#level()}), not with a level band and not with a reference constant. A level at
 * or below zero is treated as 1, and a non-finite coefficient contributes nothing, so a hostile save cannot
 * produce {@code NaN} points on a player's attribute.
 *
 * @param base     the flat part of the formula, in attribute points
 * @param perLevel the part multiplied by the character's level
 */
@ApiStatus.Experimental
public record FlatAmount(double base, double perLevel) {

    /**
     * The codec: {@code base} and {@code perLevel}, both optional so a pack can write a pure constant
     * ({@code {"base": 40}}) or a pure per-level term.
     */
    public static final Codec<FlatAmount> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.optionalFieldOf("base", 0.0).forGetter(FlatAmount::base),
            Codec.DOUBLE.optionalFieldOf("perLevel", 0.0).forGetter(FlatAmount::perLevel)
    ).apply(instance, FlatAmount::new));

    /** Nothing at all — the shape a malformed clause degrades to rather than throwing. */
    public static final FlatAmount NONE = new FlatAmount(0.0, 0.0);

    public FlatAmount {
        // A hostile coefficient must not become NaN points on an attribute: sanitize at construction, so
        // every reader downstream sees a number it can add.
        base = Double.isFinite(base) ? Math.max(0.0, base) : 0.0;
        perLevel = Double.isFinite(perLevel) ? Math.max(0.0, perLevel) : 0.0;
    }

    /**
     * {@return the points this formula is worth at {@code level}}
     *
     * @param level the character's level; {@code <= 1} is treated as 1, so the base still applies
     */
    public double amountAt(int level) {
        int effective = Math.max(EffectTiers.FIRST_TIER, level);
        double amount = base + perLevel * effective;
        return Double.isFinite(amount) ? Math.max(0.0, amount) : 0.0;
    }

    /** {@return whether this formula is worth nothing at any level} */
    public boolean isEmpty() {
        return base == 0.0 && perLevel == 0.0;
    }

    /**
     * {@return the formula in the wiki's own notation, for a log line}
     *
     * <p>{@code "15+15xL"} rather than a resolved number, so a log reads like the source it came from and a
     * reader can check it against {@code docs/balance-derivation.md} without arithmetic.
     */
    public String formula() {
        return String.format(Locale.ROOT, "%s+%sxL", trim(base), trim(perLevel));
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }
}
