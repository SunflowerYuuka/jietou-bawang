package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.combat.AllyCandidates;
import com.astrum.pcrd.impl.combat.AllyMode;
import com.astrum.pcrd.impl.combat.AllySelector;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * Putting the attack buff on its receivers: the one place an {@code ATTACK_BUFF} hook becomes a real
 * effect, and the only place that decides <b>whether</b> it should.
 *
 * <h2>Who receives it: the caster <em>and</em> the party around her</h2>
 * The 6★ ultimate's 「特大幅度提升<em>自己</em>的物理攻击」 reads as a self buff, and for years that is
 * how it was implemented here — caster only. It is not what the game does. Verified against gamewith's
 * 星河一天 entry: the party-wide attack boost is <b>part of the ultimate itself</b>, not a CHAIN effect,
 * so casting it raises the physical attack of the caster and of every ally inside the ultimate's effect
 * radius. The human's reading is the same: 「游戏里也就是范围内的效果」 — an area effect, and the area is
 * what decides who it lands on.
 *
 * <p>So the receiver set is {@link AllyCandidates}' — the same adapter, over the same
 * {@code AllySelector} kernel, that {@code area_charge} and the ultimate's own damage plan use. It is
 * deliberately <b>not</b> a second "who is an ally" rule: this project has shipped three bugs from
 * having two answers to that question, and a fourth copy written here would drift the moment somebody
 * changed {@code multiplayer.allyMode}.
 *
 * <h2>Why the caster is always in the set</h2>
 * {@code includeSelf = true}, because the game's own text is 自己 (herself) <em>and</em> her party. A
 * radius of zero, a caster with no level, or a solo world therefore degrades to exactly the old
 * behaviour rather than to "no buff at all".
 *
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
 * All three are checked <b>once</b>, before any receiver is touched, so a malformed buff cannot be
 * half-applied to the party.
 *
 * <h2>Refresh, never compound</h2>
 * Re-applying is safe by construction and is the documented behaviour: vanilla merges an instance of the
 * same type into the existing one (taking the longer duration) and replaces the attribute modifier under
 * its fixed id. So a recast — to the same player, or from a different caster — <b>refreshes</b>; it never
 * stacks. See {@link AttackBuffRules} for the two mechanisms and why both are needed.
 *
 * <h2>One receiver's failure never costs the others</h2>
 * Each application is wrapped: a player whose effect handling throws loses their own entry, and the
 * party keeps theirs. This runs on the cast path, so the same rule the damage flush follows applies here.
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

    /**
     * How far the buff reaches when the ultimate declares **no** {@code radius}, in blocks.
     *
     * <p>Eight blocks, which is the shipped {@code area_charge} radius
     * ({@code djeeta.json}'s own {@code "radius": 8.0}) and the same order of magnitude as
     * {@code UbDamage.SINGLE_TARGET_RANGE = 6}. A party buff has to cover a party that is fighting the
     * same pack of mobs, and melee reach (~3 blocks) would only ever reach the caster — so the number is
     * a party spacing, not a sword length.
     *
     * <p>A tier that declares a radius wins over this: the buff is an effect of the ultimate, so it
     * reaches exactly as far as the ultimate does. This constant is only the answer to "the pack did not
     * say".
     */
    public static final double PARTY_RADIUS_DEFAULT = 8.0;

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
     * Applies the buff to the caster and to every allied player around her.
     *
     * @param caster      the player whose ultimate (or hook) asked for it
     * @param multiplier  attack multiplier, {@code 1.5} = +50%
     * @param seconds     how long it lasts
     * @param characterId the character whose ultimate asked for it, for the log line
     * @param radius      the ultimate's effect radius, empty for {@link #PARTY_RADIUS_DEFAULT}
     * @return how many receivers hold the buff after the call — a refresh counts, see {@link #applyTo}
     */
    public static int apply(LivingEntity caster, double multiplier, double seconds,
            ResourceLocation characterId, OptionalDouble radius) {
        if (caster == null) {
            return 0;
        }
        if (!(multiplier > AttackBuffRules.MIN_MULTIPLIER) || !Double.isFinite(multiplier)) {
            Astrum.LOGGER.warn("astrum: attack_buff of {} wants multiplier {}, which is not a buff "
                            + "(it must be above {}); hook skipped. Check the character's effectParams.",
                    characterId, multiplier, AttackBuffRules.MIN_MULTIPLIER);
            return 0;
        }
        int ticks = AttackBuffRules.ticksFor(seconds);
        if (ticks <= 0) {
            Astrum.LOGGER.warn("astrum: attack_buff of {} wants {} seconds, so there is nothing to "
                    + "apply; hook skipped.", characterId, seconds);
            return 0;
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
            return 0;
        }
        int amplifier = AttackBuffRules.amplifierFor(multiplier);
        double reach = radiusFor(radius);
        int applied = 0;
        int considered;
        if (caster instanceof ServerPlayer player) {
            AllyCandidates.Pool pool = AllyCandidates.around(player);
            considered = pool.size();
            for (AllySelector.Candidate candidate : receivers(pool.candidates(), player.getUUID(),
                    AllyCandidates.configuredMode(), reach)) {
                ServerPlayer receiver = pool.players().get(candidate.id());
                if (receiver != null && applyTo(receiver, effect.get(), ticks, amplifier, seconds,
                        characterId)) {
                    applied++;
                }
            }
        } else {
            // A non-player caster is not a party: the mob effect is on the entity that asked for it,
            // and there is no ally rule that could name a player for it.
            considered = 1;
            if (applyTo(caster, effect.get(), ticks, amplifier, seconds, characterId)) {
                applied++;
            }
        }
        Astrum.LOGGER.debug("astrum: attack_buff of {} -> x{} for {}s on {} of {} player(s) within {} "
                        + "blocks (amplifier {}, {} ticks; an existing instance is refreshed, never "
                        + "stacked)", characterId, AttackBuffRules.multiplierFor(amplifier), seconds,
                applied, considered, reach, amplifier, ticks);
        return applied;
    }

    /**
     * {@return who receives the buff: the caster and every ally within {@code radius}}
     *
     * <p>The <b>whole</b> receiver rule, in one call: {@link AllySelector#select} with
     * {@code includeSelf = true}. Public and free of game types so the party-wide promise — "a caster
     * and two allies inside the radius all gain it, one outside gains nothing" — is assertable without
     * a world, and so no caller can be tempted to write the rule again.
     *
     * <p>{@code includeSelf = true} is not a parameter because it is not a choice: the game's text is
     * 自己 (herself) <em>and</em> her party, wherever their party happens to be.
     *
     * @param candidates every player around the caster, as {@link AllyCandidates} prepares them
     * @param casterId   the caster's UUID
     * @param mode       the configured ally policy; {@code null} means {@link AllyMode#ALL_PLAYERS}
     * @param radius     how far the buff reaches, in blocks, inclusive
     */
    public static List<AllySelector.Candidate> receivers(List<AllySelector.Candidate> candidates,
            UUID casterId, AllyMode mode, double radius) {
        return AllySelector.select(candidates, casterId, mode, radius, true);
    }

    /**
     * {@return the reach the buff uses for a declared radius}
     *
     * <p>Pure and public so the "declared wins, otherwise the named default" rule — the part a reader
     * would otherwise have to infer from a call site — is assertable without a game.
     *
     * <p>Three answers, in the order they apply:
     * <ol>
     *   <li><b>empty</b> — the pack said nothing, so {@link #PARTY_RADIUS_DEFAULT} is used. An
     *       ultimate that declares no radius still buffs its party; that is what the game does.</li>
     *   <li><b>zero or more</b> — used as written. {@code 0} is a legitimate value, not a broken one:
     *       it means 「自分」, the caster alone, and {@code AllySelector} already produces exactly that
     *       from an inclusive {@code distance <= 0} test with no extra branch here. This is how
     *       技能2 ウェポンバースト's self attack up is expressed.</li>
     *   <li><b>negative or {@code NaN}</b> — not a radius anybody meant; the default is used and the
     *       WARN names the value, rather than silently buffing nobody. That is deliberately the
     *       <em>opposite</em> of {@code UbDamage.plan}'s reading of a nonsense <b>damage</b> radius,
     *       where "this tier reaches nobody" is the correct fail-closed answer: a buff that reaches
     *       nobody is a broken feature with no symptom, while an over-wide buff is immediately
     *       visible and correctable.</li>
     * </ol>
     *
     * @param declared the tier's or hook's radius, if it declared one
     */
    public static double radiusFor(OptionalDouble declared) {
        if (declared == null || declared.isEmpty()) {
            return PARTY_RADIUS_DEFAULT;
        }
        double value = declared.getAsDouble();
        if (Double.isNaN(value) || value < 0.0 || !Double.isFinite(value)) {
            Astrum.LOGGER.warn("astrum: attack_buff was given radius {}, which is not one; using the "
                    + "{} block party default instead. Write 0 for a self-only buff.",
                    value, PARTY_RADIUS_DEFAULT);
            return PARTY_RADIUS_DEFAULT;
        }
        return value;
    }

    /**
     * {@return whether this receiver holds the buff after the call}
     *
     * <p>Asking {@code hasEffect} rather than returning {@code addEffect}'s answer is what makes a
     * <b>refresh</b> count. Vanilla's {@code addEffect} answers {@code false} when an instance of the
     * same type and amplifier with an equal-or-longer duration is already there — i.e. on exactly the
     * recast that is working as designed. Reporting that as "0 receivers" would make the log of a
     * successful party-wide recast look like a failure.
     *
     * <p>Wrapped per receiver: one player's failure must not cost the party, the same rule the damage
     * flush follows.
     */
    private static boolean applyTo(LivingEntity receiver, Holder<MobEffect> effect, int ticks,
            int amplifier, double seconds, ResourceLocation characterId) {
        try {
            MobEffectInstance instance = new MobEffectInstance(effect, ticks, amplifier,
                    false, false, true);
            boolean added = receiver.addEffect(instance);
            boolean holds = receiver.hasEffect(effect);
            Astrum.LOGGER.debug("astrum: attack_buff on {} -> x{} for {}s ({} ticks, amplifier {}) "
                            + "added={} holds={} (an existing instance is refreshed, never stacked)",
                    receiver.getName().getString(),
                    AttackBuffRules.multiplierFor(amplifier), seconds, ticks, amplifier, added, holds);
            return holds;
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: attack_buff of {} threw on {}; the other receivers still run",
                    characterId, receiver.getName().getString(), e);
            return false;
        }
    }
}
