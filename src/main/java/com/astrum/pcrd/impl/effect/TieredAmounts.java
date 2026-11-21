package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.combat.WarnOnce;
import org.jetbrains.annotations.ApiStatus;

/**
 * Turning a hook's {@link TieredParams} into the number an executor needs — <b>once</b>, for all ten tiered
 * effects.
 *
 * <h2>Why this exists</h2>
 * Every tiered executor needs the same three things: a <em>legal</em> tier, the <em>fraction</em> that tier
 * is worth for its own effect, and <em>one</em> warning the first time a pack's tier had to be clamped. Ten
 * copies of that would be ten places to forget the clamp and ten identical log lines. So it is one call.
 *
 * <h2>Lenient decode, one warning</h2>
 * A bad tier never fails the hook and never throws: {@link EffectTiers#resolve} answers the nearest legal
 * rung and says whether it had to move, and {@link #resolve} logs the clamp <b>once per (effect type)</b>
 * through {@link WarnOnce}. Once, not per hit, because the defect belongs to the loaded datapack rather than
 * to the swing that revealed it — the same policy {@code HookEvaluator} applies to a malformed hook.
 *
 * <p>The key is the effect type alone rather than a character and hook index, because this class is called
 * from the executors where neither is in hand; a pack that clamps {@code defence_down} is told once, which is
 * enough to find the file.
 */
@ApiStatus.Internal
public final class TieredAmounts {

    /** Clamps already reported, keyed by the effect type that reported them. */
    private static final WarnOnce REPORTED = new WarnOnce();

    private TieredAmounts() {
    }

    /**
     * A tier after clamping, with the number it is worth.
     *
     * @param tier     the legal tier, 1-based
     * @param fraction what one rung of this effect is worth, multiplied by the tier
     */
    @ApiStatus.Internal
    public record Amount(int tier, double fraction) {
    }

    /**
     * {@return the legal tier and its fraction for {@code type}, with <b>no</b> level band}
     *
     * <p>The two-argument form is the one for a caller that has no character in hand — a probe, a test, an
     * ultimate settled without a roster. It is <b>not</b> "the band is zero": it is "this caller cannot say
     * what level the character is", and a build that quietly treated that as level 1 would silently drop the
     * bands on any path somebody forgot to thread. The production call sites all use
     * {@link #resolve(EffectType, TieredParams, int)}.
     *
     * @param type   the effect whose ladder applies
     * @param params the hook's declared tier
     */
    public static Amount resolve(EffectType type, TieredParams params) {
        return resolve(type, params, 0);
    }

    /**
     * {@return the legal tier and its fraction for {@code type}, after the level band is added and clamped}
     *
     * <h2>The composition, written once</h2>
     * <pre>
     *   tier = clamp(declared + bandBonus, 1, EffectTiers.maxTier(type))
     * </pre>
     *
     * <p>The <b>clamp is the effect's</b>, not the band's: {@link com.astrum.pcrd.impl.growth.LevelBands}
     * answers how many rungs a level adds and this class is the only place that knows how many rungs the
     * effect has. That split is why the band lives in {@code growth} and the ceiling lives in
     * {@code api.v1.EffectTiers}, and why neither of them has to grow a second copy of the other's rule.
     *
     * <p>Order matters and is deliberate: add, <b>then</b> clamp. Clamping first would let a level-95
     * character on a tier-5-declared buff lose her band entirely, which is the opposite of "level grants
     * tiers".
     *
     * @param type      the effect whose ladder applies
     * @param params    the hook's declared tier
     * @param bandBonus how many rungs the character's level adds, from
     *                  {@code LevelBands.bonusAt}; {@code 0} for EX, which gets no band by the human's rule
     */
    public static Amount resolve(EffectType type, TieredParams params, int bandBonus) {
        TieredParams tiered = params == null ? TieredParams.FIRST : params;
        EffectTiers.Resolved resolved = resolveTier(type, tiered, bandBonus);
        EffectTiers.Rule rule = EffectTiers.ruleOf(type);
        double step = rule == null ? 0.0 : rule.stepPerTier();
        return new Amount(resolved.tier(), step * resolved.tier());
    }

    /**
     * {@return the legal tier alone, for the effects that encode it in an amplifier}
     *
     * @param type   the effect whose ladder applies
     * @param params the hook's declared tier
     */
    public static int tierOf(EffectType type, TieredParams params) {
        return tierOf(type, params, 0);
    }

    /**
     * {@return the legal tier alone, for the effects that encode it in an amplifier}
     *
     * @param type      the effect whose ladder applies
     * @param params    the hook's declared tier
     * @param bandBonus how many rungs the character's level adds; {@code 0} for EX
     */
    public static int tierOf(EffectType type, TieredParams params, int bandBonus) {
        return resolveTier(type, params, bandBonus).tier();
    }

    /**
     * {@return the band-composed tier, clamped by the effect's own ladder, with the clamp reported once}
     *
     * <p>One clamp path for both public forms above, so the warning cannot name a different pair of numbers
     * than the one actually used.
     */
    private static EffectTiers.Resolved resolveTier(EffectType type, TieredParams params, int bandBonus) {
        int declared = params == null ? EffectTiers.FIRST_TIER : params.tier();
        int raised = declared + bandBonus;
        EffectTiers.Resolved clamped = EffectTiers.resolve(type, raised);
        if (clamped.clamped()) {
            // Two causes, and the line says which: a pack wrote an absurd tier, or the level band + the
            // declared tier went past the top of the ladder (which is the normal, intended outcome for a
            // high-level character on a strong skill — hence "band" in the message rather than "bad data").
            reportClamp(type, declared, bandBonus, clamped.tier());
        }
        return clamped;
    }

    /**
     * Logs a clamp once per effect type, naming the band so "the ladder ran out" is distinguishable from
     * "the pack wrote nonsense".
     *
     * @param type     the effect whose ladder refused the value
     * @param declared the tier as written
     * @param band     the level band that was added
     * @param used     the tier actually used
     */
    private static void reportClamp(EffectType type, int declared, int band, int used) {
        if (!REPORTED.first(String.valueOf(type))) {
            return;
        }
        Astrum.LOGGER.warn("astrum: {} was given tier {} plus a level band of {} = {}, which is outside its "
                        + "1..{} ladder; tier {} is used instead. Reported once per effect type — the top "
                        + "rung of a ladder is a real ceiling, not a rounding error.",
                type, declared, band, declared + band, EffectTiers.maxTier(type), used);
    }
}
