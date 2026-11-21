package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Puts the row a fired skill leaves in vanilla's effect bar onto the caster — the <b>only</b> place a skill
 * entry is ever applied.
 *
 * <h2>The division of labour with {@link SkillEntries}</h2>
 * {@link SkillEntries} answers <b>whether</b> there is an entry and <b>how long</b> it lasts, as a pure
 * function of the fired hooks. This class does the two things that need a world: find the <b>type</b> for the
 * (character, skill) that fired — {@link PartyEffects#skillHolderFor} over {@link SkillEffectTypes} — and put
 * the instance on the body. Nothing here re-decides a duration, and nothing here decides which skill fired: the
 * skill tag is {@link SkillEntries.Entry#skill()}, already resolved.
 *
 * <h2>Two different skills coexist; the same skill refreshes — for free</h2>
 * 「buff给予了后，给予新buff不是覆盖，而是同时存在，给予同一个buff才是刷新持续cd。」 Both halves are vanilla's own
 * behaviour once the type <b>is</b> the skill, and this method relies on it rather than re-implementing it:
 *
 * <ul>
 *   <li><b>Different skills of one character</b> are different {@code MobEffect} types. Vanilla keeps a
 *       player's effects in a map keyed by type, so 雪菲's 雪靴 and 冻结之湖 are two live instances and two
 *       rows. Nothing is removed, and no row can overwrite another.</li>
 *   <li><b>The same skill re-fired</b> is the same type, so {@code LivingEntity.addEffect} hands the new
 *       instance to {@code MobEffectInstance.update}, which for an equal amplifier takes the <b>longer</b>
 *       remaining duration: a re-fire refreshes the clock and never stacks a second row.</li>
 * </ul>
 *
 * <h2>What was removed, and why nothing replaced it</h2>
 * The old applier removed the slot's instance before adding the new one. That was necessary only because one
 * type served five slots and the skill lived in the <b>amplifier</b> as a row index: vanilla's merge treats a
 * differing amplifier as a second, <em>hidden</em> instance and keeps the old row, so "remove then add" was the
 * only way to make the newest skill's picture win. With identity in the type there is no amplifier to disagree
 * about and no removal to do — {@code removeEffect} here would be the one way to <em>break</em> the human's
 * rule, because it would also throw away the remaining time of the skill he just refreshed.
 *
 * <p>{@code SkillEntryCoexistenceTest} asserts both branches against real {@link MobEffectInstance}s —
 * vanilla's merge is the mechanism, so a future MC upgrade that changed it fails there instead of quietly
 * changing what a player sees.
 *
 * <h2>The instantaneous case writes nothing — on purpose</h2>
 * An empty {@link SkillEntries#of} answer means the skill left the player in no state, and the correct action is
 * <b>no action</b>: not "clear the row", because the row on the bar may be a previous skill's effect that is
 * still really running, and not "a short entry", because the human's rule is that the bar lists states and a
 * damage skill is not one. See {@link EffectType#persistsOnCaster()}.
 *
 * <h2>Why there is no tick listener anywhere in this</h2>
 * The instance's life <b>is</b> vanilla's effect expiry. Nothing re-applies, extends or repairs it, which is
 * what keeps this feature inside the project's 「no entity/level tick polling」 red line and why the entry's
 * duration can only be wrong if the arithmetic in {@link SkillEntries} is wrong — a property
 * {@code SkillEntriesTest} asserts.
 */
@ApiStatus.Internal
public final class SkillEntryBuffs {

    /**
     * The instance's amplifier. Vanilla's merge keys off it, and with one type per skill it means nothing: the
     * old value was a row index into {@code SkillRows}, and carrying it forward would print a fake 「二级」 on
     * the name again. Zero everywhere, so a re-fire always takes the equal-amplifier branch (refresh).
     */
    /**
     * The amplifier a skill row is written with: <b>her tally, minus one</b>, or {@code 0} when she holds none.
     *
     * <h2>Why a row would carry a level at all</h2>
     * 「给冰龙之印增加等级，作为层数标记。」 A skill whose whole content is a stack count should say how many
     * stacks are up, and vanilla prints an effect's level beside its name for free — so the row's amplifier is
     * the tally's, and 「冰龙之印 IV」 is four marks without any client code.
     *
     * <p>{@code Counters.countOf} answers 1-based (it adds one to the stored amplifier), so the row's amplifier
     * is {@code count - 1}: four marks is amplifier 3, which vanilla prints as IV. A skill that leaves no tally
     * gets amplifier 0, i.e. no numeral — the ordinary case for every character but 雪菲.
     */
    private static int amplifierFor(ServerPlayer caster) {
        int marks = Counters.countOf(caster);
        return marks <= 0 ? 0 : marks - 1;
    }

    private SkillEntryBuffs() {
    }

    /**
     * Applies the row for a <b>small-skill</b> firing, or does nothing when the skill was instantaneous.
     *
     * <p>The production entry point, called once per landed hit from {@code HookEvaluator.evaluate} — the same
     * method that already owns 「which skill fired」 for the rotation, so the bar cannot disagree with which
     * skill the cursor advanced over.
     *
     * @param caster      the player whose wheel was evaluated
     * @param characterId which character fired, for the type lookup and the log line
     * @param fired       the hooks that actually ran, in firing order
     * @param multiplier  this slot's composed multiplier, for the one duration it scales
     * @return the entry that was written, or empty when the skill left no state
     */
    public static Optional<SkillEntries.Entry> apply(ServerPlayer caster, ResourceLocation characterId,
            @Nullable List<EffectHook> fired, double multiplier) {
        if (caster == null || characterId == null) {
            return Optional.empty();
        }
        try {
            Optional<SkillEntries.Entry> entry = SkillEntries.of(fired, multiplier);
            if (entry.isEmpty()) {
                // The ordinary case for most characters — 姬塔's whole kit is instantaneous — so there is
                // deliberately no log line here: a message per swing would bury the log it is written for.
                return Optional.empty();
            }
            return write(caster, characterId, entry.get());
        } catch (RuntimeException e) {
            // An effect row is a report about a hit that has already landed; it must never cost the hit.
            Astrum.LOGGER.error("astrum: could not show the skill entry of {} on {}; the hit is unaffected",
                    characterId, caster.getGameProfile().getName(), e);
            return Optional.empty();
        }
    }

    /**
     * Applies the row for an <b>ultimate</b> cast, or does nothing when the cast leaves no state.
     *
     * <h2>Why the ultimate is a separate door and not a second rule</h2>
     * The duration is still {@link SkillEntries}' answer — {@link SkillEntries#ticksOfUltimate} walks the tier's
     * own {@code ALLY}-scoped {@link UbEffect}s through the same per-type table a hook goes through. What
     * differs is only where the effects come from: a cast has a {@link UbTier}, not a hook list. The skill the
     * row names is the ultimate's own identity, {@link SkillEffectTypes#UB_SKILL} — the same tag
     * {@link AffectionKernel#ubKey()} answers for the book's last row, so the bar and the book name one row.
     *
     * <p>The type is {@code (characterId, "ub")}, i.e. <b>its own</b> type: a cast while her small skill's row
     * is still running is two rows, not a merge. That is the case the old per-slot design traded away.
     *
     * @param caster      the caster; must be server-side
     * @param characterId which character cast
     * @param cast        the tier that was cast (base or enhanced)
     * @return the entry that was written, or empty when the cast left no state on the caster's side
     */
    public static Optional<SkillEntries.Entry> applyUltimate(ServerPlayer caster, ResourceLocation characterId,
            @Nullable UbTier cast) {
        if (caster == null || characterId == null || cast == null) {
            return Optional.empty();
        }
        try {
            List<EffectType> types = new ArrayList<>();
            List<TieredParams> params = new ArrayList<>();
            for (UbEffect effect : cast.effects()) {
                // A cast's ALLY half is what lands on the caster's side; a VICTIM clause is a debuff on the
                // bodies it hit, and a mob has no effect bar to report on.
                if (effect == null || effect.mismatch().isPresent()
                        || effect.scope() != UbEffect.Scope.ALLY) {
                    continue;
                }
                types.add(effect.type());
                params.add(effect.params());
            }
            int ticks = SkillEntries.ticksOfUltimate(types, params);
            if (ticks <= 0) {
                // A pure-damage ultimate (姬塔's base 疾风剑雨) is an ordinary cast, not a defect, so this is
                // not logged: a line per cast would be noise on the one surface an operator reads casts in.
                return Optional.empty();
            }
            return write(caster, characterId, new SkillEntries.Entry(SkillEffectTypes.UB_SKILL, ticks));
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: could not show the ultimate's skill entry for {}; the cast has already "
                    + "been paid for", characterId, e);
            return Optional.empty();
        }
    }

    /**
     * Writes the row: one instance of the (character, skill) type, and vanilla merges a repeat of it.
     *
     * <p>Deliberately one {@code addEffect} and nothing else — see the class javadoc for why the old
     * {@code removeEffect} is gone, and why it would now break the refresh rule rather than fix a picture.
     */
    private static Optional<SkillEntries.Entry> write(ServerPlayer caster, ResourceLocation characterId,
            SkillEntries.Entry entry) {
        Optional<Holder<MobEffect>> holder = PartyEffects.skillHolderFor(characterId, entry.skill());
        if (holder.isEmpty()) {
            // Not an error at ERROR level: a pack can declare a skill this jar has no type for (the third-party
            // datapack case SkillEffectTypes documents). The skill's buffs still applied; only its row is
            // missing, and the build-time test is what keeps it from happening to a shipped character.
            Astrum.LOGGER.debug("astrum: no effect type for {} skill '{}', so its row was not shown",
                    characterId, entry.skill());
            return Optional.empty();
        }
        boolean added = caster.addEffect(new MobEffectInstance(holder.get(), entry.ticks(), amplifierFor(caster),
                false, false, true));
        Astrum.LOGGER.debug("astrum: skill entry for {} ('{}') -> {} for {}t added={}", characterId,
                entry.skill(), holder.get().unwrapKey().map(key -> key.location().toString()).orElse("?"),
                entry.ticks(), added);
        return Optional.of(entry);
    }
}