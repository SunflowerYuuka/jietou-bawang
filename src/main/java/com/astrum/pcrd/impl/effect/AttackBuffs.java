package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Putting the self attack buff on its caster: the one place an {@code ATTACK_BUFF} hook becomes a real
 * effect, and the only place that decides <b>whether</b> it should.
 *
 * <h2>Why the caster and not the target</h2>
 * The wiki's text is 「提升<em>自己</em>的物理攻击」 — herself. So unlike {@code apply_effect}, which lands
 * on the thing she hit, this one lands on the attacker. It is also why the buff keeps working for the
 * next swing: it is a change to her, not to the hit in flight.

 * <h2>The refusals, and why each is a log line instead of a silent skip</h2>
 * <ul>
 *   <li>A multiplier at or below {@code 1.0} is not a buff — applying it would put a HUD entry on the
 *       player that says "attack up" and changes nothing, or worse, a negative fraction that reads as a
 *       debuff. Refused.</li>
 *   <li>Zero or negative seconds is the author asking for nothing; refused, since a real duration of one
 *       tick would flicker an entry in the HUD.</li>
 *   <li>{@link AttackBuffRules#isRepresentable} catching a multiplier outside 1.00–3.55: the hook still
 *       runs, at the nearest representable value, but the truncation is stated at WARN — an author who
 *       wrote {@code 5.0} must not have to guess why players got {@code +255%}.</li>
 * </ul>
 *
 * <h2>No tick listener, and nothing to clean up</h2>
 * The instance's duration is the whole lifetime. Vanilla's own effect processing expires it, which
 * removes the attribute modifier at the same moment. There is no expiry bookkeeping in this mod, and
 * this class holds no state at all — it is a static function.
 */
@ApiStatus.Internal
public final class AttackBuffs {

    /** Registry path of the effect: {@code astrum:attack_buff}. */
    public static final String PATH = "attack_buff";

    /** The effect's registry id, for log lines and for a client-side lookup. */
    public static final ResourceLocation ID = InternalIds.id(PATH);

    /** The translation key vanilla resolves for the effect's name, {@code effect.astrum.attack_buff}. */
    public static final String NAME_KEY = "effect." + ID.toLanguageKey();

    private AttackBuffs() {
    }

    /**
     * {@return the registered effect holder, or empty when the registry is not up yet}
     *
     * <p>Returns {@link Holder} rather than {@code Holder.Reference}: {@code DeferredHolder} satisfies
     * {@code Holder} but is not a {@code Reference} subtype, and every caller so far only needs
     * something it can hand to {@code new MobEffectInstance(...)} or read an attribute modifier from.
     */
    public static Optional<Holder<MobEffect>> holder() {
        if (!PartyEffects.ATTACK_BUFF.isBound()) {
            return Optional.empty();
        }
        return Optional.of(PartyEffects.ATTACK_BUFF);
    }

    /**
     * Applies the buff to {@code caster}.
     *
     * <p>Re-applying is safe by construction and is the documented behaviour: vanilla merges an instance
     * of the same type into the existing one (taking the longer duration) and replaces the attribute
     * modifier under its fixed id. So a recast <b>refreshes</b> — it never compounds. See
     * {@link AttackBuffRules} for the two mechanisms and why both are needed.
     *
     * @param caster     who the buff lands on: the hook's attacker
     * @param multiplier attack multiplier, {@code 1.5} = +50%
     * @param seconds    how long it lasts
     * @param characterId the character whose hook asked for it, for the log line
     * @return whether an instance was actually added or refreshed
     */
    public static boolean apply(LivingEntity caster, double multiplier, double seconds,
            ResourceLocation characterId) {
        if (caster == null) {
            return false;
        }
        if (!(multiplier > AttackBuffRules.MIN_MULTIPLIER) || !Double.isFinite(multiplier)) {
            Astrum.LOGGER.warn("astrum: attack_buff of {} wants multiplier {}, which is not a buff "
                            + "(it must be above {}); hook skipped. Check the character's effectParams.",
                    characterId, multiplier, AttackBuffRules.MIN_MULTIPLIER);
            return false;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            Astrum.LOGGER.warn("astrum: attack_buff of {} wants {} seconds, so there is nothing to "
                    + "apply; hook skipped.", characterId, seconds);
            return false;
        }
        if (!AttackBuffRules.isRepresentable(multiplier)) {
            Astrum.LOGGER.warn("astrum: attack_buff of {} wants multiplier {}, which this effect can "
                            + "only express as {} (amplifiers are integers, 0-{}); the nearest value is "
                            + "used.", characterId, multiplier,
                    AttackBuffRules.multiplierFor(AttackBuffRules.amplifierFor(multiplier)),
                    AttackBuffRules.MAX_AMPLIFIER);
        }
        Optional<Holder<MobEffect>> effect = holder();
        if (effect.isEmpty()) {
            Astrum.LOGGER.warn("astrum: attack_buff is not registered yet, so the buff of {} was not "
                    + "applied; this means the mob effect registry missed {}.",
                    characterId, ID);
            return false;
        }
        int amplifier = AttackBuffRules.amplifierFor(multiplier);
        MobEffectInstance instance = new MobEffectInstance(effect.get(), ticks, amplifier,
                false, false, true);
        boolean added = caster.addEffect(instance);
        Astrum.LOGGER.debug("astrum: attack_buff on {} -> x{} for {}s ({} ticks, amplifier {}) "
                        + "added={} (an existing instance is refreshed, never stacked)",
                caster.getName().getString(),
                AttackBuffRules.multiplierFor(amplifier), seconds, ticks, amplifier, added);
        return added;
    }
}
