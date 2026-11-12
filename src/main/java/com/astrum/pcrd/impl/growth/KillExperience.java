package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Roster;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.ApiStatus;

/**
 * A kill's experience: what it is worth, who earned it, and how the four growth pieces are put
 * together to pay it (spec §6.1).
 *
 * <h2>The formula, stated once</h2>
 * <pre>
 * pool      = ExperienceKernel.killExp(victim.getMaxHealth())   = round(maxHealth × 0.5), floor 1
 * per char  = ExperienceKernel.distribute(pool, contributions)   = floor(pool × share / Σshares), floor 1
 * progress  = ExperienceKernel.add(progress, perChar, def.growth())
 * </pre>
 *
 * <p>The victim's <b>maximum</b> health — not the health it had when it died — is the base, and that is
 * the whole reason a chicken is not worth a wither. It is read from the entity's attribute rather than
 * from a per-mob table, so a datapack or a mod that gives a mob more health makes it worth more
 * experience without a second data file. Both the curve and the split already live in
 * {@link ExperienceKernel}; this class only chooses the victim, reads the roster and writes it back.
 *
 * <h2>Who gets it, and why the ledger is consumed exactly once</h2>
 * {@link ContributionTracker} holds "character → damage it dealt to that victim" for the pair
 * (attacker, victim). {@link #settle} <b>takes</b> that entry at the moment the victim dies, which is
 * the kill settling: a second death event for the same victim (a totem, a mod reviving it) finds an
 * empty ledger and pays nothing, and a mob that despawns without dying is dropped by the tracker's own
 * staleness prune on its next recorded hit. The tracker is also cleared wholesale on
 * {@code ServerStoppingEvent} from {@code CombatEvents}, together with that class's other session
 * state.
 *
 * <p>Only characters the player <b>owns</b> are written ({@code Roster.withProgress} ignores keys the
 * book does not hold, which is the second line of defence), and only the party ever contributes damage,
 * so a character sitting in the book but not on the wheel earns nothing — §6.1's 带出战.
 *
 * <h2>What this class deliberately does not do</h2>
 * No damage recording (that is the combat entry point's, next to charge), no level-up maths
 * ({@link ExperienceKernel#add}) and no words of its own: the one sentence a player sees comes from
 * {@code GrowthText}, so {@code LangParityTest} can prove it is translated.
 */
@ApiStatus.Internal
public final class KillExperience {

    /** Vanilla green: "you gained something". */
    private static final int COLOR_LEVEL_UP = 0x55FF55;

    private KillExperience() {
    }

    /**
     * {@return the experience each contributing character earns from one kill}
     *
     * <p>Pure: the composition of {@link ExperienceKernel#killExp(double)} with
     * {@link ExperienceKernel#distribute(int, Map)}. Kept as its own method so the one design decision
     * this class adds — "the pool comes from the victim's maximum health" — is asserted without a
     * victim, a level or a roster.
     *
     * @param victimMaxHealth the killed entity's maximum health
     * @param contributions   character id to damage contributed; empty pays nobody
     */
    public static Map<ResourceLocation, Integer> award(double victimMaxHealth,
            Map<ResourceLocation, Double> contributions) {
        if (contributions == null || contributions.isEmpty()) {
            return Map.of();
        }
        int pool = ExperienceKernel.killExp(victimMaxHealth);
        if (pool <= 0) {
            return Map.of();
        }
        return ExperienceKernel.distribute(pool, contributions);
    }

    /**
     * Pays one kill: reads the ledger, splits the pool, writes the book, and tells the player about
     * every level it crossed.
     *
     * <p>The ledger is taken <b>first</b>, so every later early-return still ends the pair's meaning —
     * a quarantined book or an empty registry must not leave the damage behind to be paid to the next
     * kill.
     *
     * @param killer the player credited with the death
     * @param victim the entity that died
     */
    public static void settle(ServerPlayer killer, LivingEntity victim) {
        if (killer == null || victim == null) {
            return;
        }
        Map<ResourceLocation, Double> contributions =
                ContributionTracker.take(killer.getUUID(), victim.getUUID());
        if (contributions.isEmpty()) {
            // No single character of this wheel touched the victim: a kill by a plain sword, or a mob
            // that died to something else and was credited to the player. Nothing to divide.
            Astrum.LOGGER.debug("astrum: growth: {} killed {} but no character contributed damage — "
                    + "no experience", name(killer), name(victim));
            return;
        }
        Map<ResourceLocation, Integer> shares = award(victim.getMaxHealth(), contributions);
        if (shares.isEmpty()) {
            return;
        }
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        if (registry.isEmpty()) {
            return;   // no world loaded: nothing can be written
        }
        Roster roster = killer.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            Astrum.LOGGER.warn("astrum: growth: {} killed {} but the character book is quarantined — "
                    + "experience for {} character(s) not written", name(killer), name(victim),
                    shares.size());
            return;
        }
        Map<ResourceLocation, CharacterProgress> updated = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Integer> share : shares.entrySet()) {
            ResourceLocation characterId = share.getKey();
            CharacterDef def = registry.get().get(characterId);
            CharacterProgress before = roster.progressOf(characterId);
            if (def == null || before == null) {
                continue;   // the pack no longer defines it, or the book does not own it
            }
            CharacterProgress after = ExperienceKernel.add(before, share.getValue(), def.growth());
            if (after.equals(before)) {
                continue;
            }
            updated.put(characterId, after);
            Astrum.LOGGER.debug("astrum: growth: {} gains {} exp from {} (level {} exp {} -> level {} "
                    + "exp {})", characterId, share.getValue(), victim.getType(),
                    before.level(), before.exp(), after.level(), after.exp());
            if (after.level() > before.level()) {
                speakLevelUp(killer, def, after.level());
            }
        }
        if (updated.isEmpty()) {
            return;
        }
        killer.setData(Attachments.ROSTER, roster.withProgress(updated));
        Astrum.LOGGER.debug("astrum: growth: kill of {} paid {} character(s) from a pool of {} exp",
                name(victim), updated.size(), ExperienceKernel.killExp(victim.getMaxHealth()));
    }

    /**
     * Tells the player one character levelled.
     *
     * <p>One line per character that crossed a level, in the same order the shares were paid, so a
     * kill that levels two characters says both things rather than only the last. The roster write that
     * follows is what the 角色册 screen draws from (it reads {@code GrowthFacts} through the roster sync
     * payload), so this line and that bar can never disagree — there is no second level number here.
     */
    private static void speakLevelUp(ServerPlayer player, CharacterDef def, int level) {
        player.displayClientMessage(Component.translatable(GrowthText.KEY_LEVEL_UP, def.displayName(),
                level).withColor(COLOR_LEVEL_UP), false);
    }

    /** {@return a name for a log line; never {@code null} for a live entity} */
    private static String name(LivingEntity entity) {
        return entity.getName().getString();
    }
}
