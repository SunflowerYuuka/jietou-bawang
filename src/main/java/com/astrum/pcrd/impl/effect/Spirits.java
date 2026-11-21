package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectTiers;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.WarnOnce;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>召唤 / 精灵</b>: the numbers behind {@code astrum:spirit} — how many spirits orbit, how far out, and how
 * many particles that costs.
 *
 * <h2>Why a particle ring and not an entity</h2>
 * The human's instruction is 「为了不制作新实体模型，生成粒子效果当做精灵？环绕在玩家周围，做成buff给玩家，如果有计数要求就用等级来计数。」
 * A summon that is a <b>status effect</b> costs this mod no entity model, no renderer, no AI, no
 * pathfinding and no despawn logic, and it inherits three things for free because vanilla owns them: the HUD
 * entry, the expiry clock, and {@code /effect give}. The spirits are therefore <b>particles</b>, the count is
 * the <b>amplifier</b>, and nothing exists in the world that a chunk must keep loaded.
 *
 * <h2>The tier sets the count, because the amplifier is the count</h2>
 * <pre>
 *   tier  1   2   3   4   5
 *   count 1   2   3   4   5      countForTier(tier) = tier, amplifier = tier - 1
 * </pre>
 *
 * <p>The rung comes from {@link EffectTiers} (5 rungs, one spirit each) like every other tiered effect, so a
 * level-95 character's tier-3 summon really does put five spirits up rather than three — the band and the
 * clamp happen once, in {@code TieredAmounts}, and this class only reads the result.
 *
 * <h2>The particle budget, stated as a number</h2>
 * <pre>
 *   particles per tick = count x {@value #PARTICLES_PER_SPIRIT_PER_TICK}
 *                      = 1 at tier 1 ... 5 at tier 5
 * </pre>
 *
 * <p>So a tier-5 summon on four simultaneous party members is <b>20 particles a tick</b>, and it is drawn by
 * {@code ServerLevel.sendParticles}, which is a broadcast to the clients tracking that chunk and costs the
 * server one packet per call. There is no per-tick entity search anywhere on this path: the effect ticks
 * itself (vanilla's {@code MobEffectInstance.tick}), the entity it orbits is handed in, and the only
 * arithmetic is {@code count} cosines. That is what makes this affordable to leave on indefinitely.
 *
 * <p>The angular speed is {@link #ANGULAR_SPEED} radians a tick, i.e. one full orbit in about 87 ticks
 * (4.4 s), which reads as a slow hover rather than a spin.
 */
@ApiStatus.Internal
public final class Spirits {

    /** Registry path: {@code astrum:spirit}. */
    public static final String PATH = "spirit";

    /** {@code astrum:spirit}. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** Particle tint: a cold violet, so the orbiting motes are legible against grass and snow alike. */
    public static final int COLOUR = 0xB8A6F0;

    /** How far from the caster's centre the spirits orbit, in blocks. */
    public static final double RADIUS = 1.35;

    /**
     * How high above the caster's feet the ring sits, as a fraction of her bounding-box height.
     *
     * <p>{@code 0.55} is roughly chest height, so the ring is visible from the front and does not vanish into
     * the ground when she crouches.
     */
    public static final double HEIGHT_FRACTION = 0.55;

    /** Vertical wobble amplitude, in blocks — a small bob so the ring reads as alive. */
    public static final double BOB = 0.16;

    /** Radians per tick. One turn is {@code 2*PI/0.072} ≈ 87 ticks ≈ 4.4 s. */
    public static final double ANGULAR_SPEED = 0.072;

    /** One particle per spirit per tick. See the class javadoc for the resulting budget. */
    public static final int PARTICLES_PER_SPIRIT_PER_TICK = 1;

    /** Problems already reported, keyed by what was wrong rather than by who noticed. */
    private static final WarnOnce REPORTED = new WarnOnce();

    private Spirits() {
    }

    /**
     * {@return how many spirits orbit for {@code tier}}
     *
     * <p>Read from {@link EffectTiers} like every other rung, so the ladder has one copy. A tier outside the
     * table answers {@code 0} and the effect draws nothing rather than inventing a sixth spirit.
     *
     * @param tier a tier, clamped into {@code [1, maxTier]} on the way in
     */
    public static int countForTier(int tier) {
        EffectTiers.Rule rule = EffectTiers.ruleOf(EffectType.SUMMON_SPIRIT);
        if (rule == null) {
            return 0;
        }
        int clamped = Math.max(EffectTiers.FIRST_TIER, Math.min(rule.maxTier(), tier));
        return (int) Math.round(rule.fractionAt(clamped));
    }

    /**
     * {@return the amplifier that encodes {@code tier}} — {@code tier - 1}, since amplifier 0 is one spirit
     *
     * @param tier a tier; values below 1 clamp to amplifier 0
     */
    public static int amplifierForTier(int tier) {
        return Math.max(0, tier - 1);
    }

    /**
     * {@return the duration in ticks a summon lasts, or {@code 0} when the params ask for none}
     *
     * <p>The one reading of the {@code seconds} rule for this effect: absent means
     * {@link AreaBuffs#DEFAULT_SECONDS} (so a pack that writes only a tier still gets a summon rather than a
     * zero-tick one), a declared {@code 0} means nothing, and nonsense falls back to the default. {@link #apply}
     * converts with it, so a caller asking "how long do these spirits orbit" — the skill-entry family does — gets
     * the duration that was really granted rather than a second interpretation of the same field.
     *
     * @param params the hook's tier block; {@code null} means absent seconds
     */
    public static int ticksFor(com.astrum.pcrd.api.v1.TieredParams params) {
        double seconds = params == null ? AreaBuffs.DEFAULT_SECONDS
                : params.seconds().orElse(AreaBuffs.DEFAULT_SECONDS);
        return AttackBuffRules.ticksFor(seconds);
    }

    /**
     * {@return the number of spirits for an instance's amplifier} — the inverse of
     * {@link #amplifierForTier}
     *
     * <p>Clamped to the ladder's top rung, so a hand-written {@code /effect give ... 200} cannot ask for a
     * hundred spirits.
     *
     * @param amplifier the instance's amplifier
     */
    public static int countForAmplifier(int amplifier) {
        int top = countForTier(Integer.MAX_VALUE);
        return Math.max(0, Math.min(top, amplifier + 1));
    }

    /**
     * {@return the particles this many spirits cost per tick}
     *
     * @param count how many spirits are up
     */
    public static int budgetPerTick(int count) {
        return Math.max(0, count) * PARTICLES_PER_SPIRIT_PER_TICK;
    }

    /**
     * {@return the angle of spirit {@code index} of {@code count} at {@code gameTime}}
     *
     * <p>Pure, so the ring is assertable without a server: evenly spaced ({@code 2*PI*index/count}) and
     * advancing {@link #ANGULAR_SPEED} a tick. {@code count <= 0} answers {@code 0} rather than dividing by
     * zero.
     *
     * @param gameTime the level's tick counter
     * @param index    which spirit, {@code 0}-based
     * @param count    how many spirits are up
     */
    public static double angleAt(long gameTime, int index, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double spread = (Math.PI * 2.0) * (index % count) / count;
        return gameTime * ANGULAR_SPEED + spread;
    }

    /**
     * {@return the vertical offset of spirit {@code index} at {@code gameTime}}
     *
     * <p>Half of the spirits bob up and the other half down, so the ring never pulses as one and the sum of
     * the offsets is zero.
     *
     * @param gameTime the level's tick counter
     * @param index    which spirit, {@code 0}-based
     */
    public static double bobAt(long gameTime, int index) {
        return Math.sin(gameTime * ANGULAR_SPEED * 2.0 + (index % 2 == 0 ? 0.0 : Math.PI)) * BOB;
    }

    /**
     * Puts the summon on the caster, which is the only receiver it can have.
     *
     * <h2>Why the caster alone, and not {@code AreaBuffs}</h2>
     * A ring of spirits orbits the body that owns the buff. Handing this to the party kernel would either put
     * a ring on four people (each of them circling her own head, which is fine but is not what 「环绕在玩家周围」
     * describes) or — worse — put the buff on the party and the particles on the caster. So the set is one
     * entity, resolved here, and {@code radius} is not read at all.
     *
     * <h2>Duration</h2>
     * The declared {@code seconds}, defaulting to the same eight-second scale as every other tiered buff
     * ({@code AreaBuffs.DEFAULT_SECONDS}) so a pack that writes only a tier still gets a summon rather than a
     * zero-tick one that silently does nothing.
     *
     * @param caster      the body the spirits orbit
     * @param tier        the rung, which is the spirit count
     * @param params      the tier block, read only for its {@code seconds}
     * @param characterId the character that asked, for the once-per-defect report
     * @return {@code 1} when the buff was applied, {@code 0} otherwise
     */
    public static int apply(LivingEntity caster, int tier, TieredParams params,
            ResourceLocation characterId) {
        if (caster == null) {
            return 0;
        }
        java.util.Optional<net.minecraft.core.Holder<MobEffect>> holder = PartyEffects.spiritHolder();
        if (holder.isEmpty()) {
            report(characterId, "is not registered yet, so no spirits were summoned");
            return 0;
        }
        int count = countForTier(tier);
        if (count <= 0) {
            report(characterId, "resolved to no spirits, so nothing was summoned");
            return 0;
        }
        int ticks = ticksFor(params);
        if (ticks <= 0) {
            report(characterId, "declared no duration, so the summon was not applied");
            return 0;
        }
        boolean applied = caster.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                holder.get(), ticks, amplifierForTier(count), false, true, true));
        Astrum.LOGGER.debug("astrum: summon_spirit tier {} -> {} spirit(s) for {}t on {} applied={}",
                tier, count, ticks, caster.getName().getString(), applied);
        return applied ? 1 : 0;
    }

    /** Logs one datapack-shaped problem once per (character, message). */
    private static void report(ResourceLocation characterId, String problem) {
        String key = ID + "|" + characterId + "|" + problem;
        if (REPORTED.first(key)) {
            Astrum.LOGGER.warn("astrum: summon_spirit (character {}) {}; reported once.", characterId,
                    problem);
        }
    }
}
