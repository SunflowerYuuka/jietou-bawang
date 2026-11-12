package com.astrum.pcrd.impl.growth;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * 送料理: one right-click with a dish raises the affection of the characters that are actually with the
 * player (spec §6.1, §6.2).
 *
 * <h2>Who is fed</h2>
 * The characters <b>in the party</b>, and only those the player owns and the running pack still
 * defines. That is the 养成 reading of §6.1 — affection is something you give the character you are
 * carrying, not a currency you spend on the whole book — and it needs no character selector, which is
 * what makes a plain right-click a complete interaction.
 *
 * <p>The player's own inventory is the only place the item can be: a slot that names someone the
 * roster does not own is skipped (the same ownership rule {@code CombatEvents} applies to hooks), a
 * character the loaded pack no longer defines is skipped, and a character already at the tier-3 cap is
 * skipped rather than being fed for nothing.
 *
 * <h2>The throttle</h2>
 * One dish per game-day per player, enforced by {@link DishThrottle} — keyed on the <b>dish</b>, not on
 * the character, which is what "每物品每天上限 1 次" says: four dishes today is four affection for the
 * whole party, not four per character.
 *
 * <p>The allowance is only spent when something would actually change. Checking the throttle first and
 * then discovering there is nobody to feed would burn the day's sugar for nothing, which a player would
 * read as a broken item.
 *
 * <h2>What this class does not do</h2>
 * No ticking, no persistence, no messages (the caller prints the {@link Outcome}) and no item
 * consumption: whether to shrink the stack is the event handler's business, because a creative-mode
 * player must not lose the item and that is a fact about the click, not about affection.
 */
@ApiStatus.Internal
public final class DishFeed {

    /** What one feeding attempt did, so the caller can speak about it and consume the item. */
    public enum Outcome {
        /** Ate the dish: at least one character gained affection. */
        FED,
        /** The held stack is not a dish. */
        NOT_A_DISH,
        /** This dish was already fed to this player today. */
        ALREADY_TODAY,
        /** Every character in the party is already at the last tier. */
        ALREADY_FULL,
        /** Nothing in the party: no slots filled, or none of them owned and known. */
        NOBODY_TO_FEED,
        /** The character book is unreadable, so affection cannot be written (spec §4.3). */
        BOOK_UNREADABLE
    }

    /**
     * What one feeding attempt did: the outcome, and how many characters it touched.
     *
     * <p>The count is carried rather than recomputed by the caller because only this class knows which
     * party members were owned, known and still below the cap — and a message that said "3 characters"
     * when one was skipped would be worse than no message.
     *
     * @param outcome     what happened
     * @param characters  how many characters gained affection; {@code 0} for every non-{@code FED}
     */
    public record Result(Outcome outcome, int characters) {

        static Result of(Outcome outcome) {
            return new Result(outcome, 0);
        }
    }

    private DishFeed() {
    }

    /**
     * Feeds {@code stack} to the party, if it is a dish and today's allowance for it is unspent.
     *
     * @param player   the player who right-clicked
     * @param stack    the held stack
     * @param throttle the per-session day throttle
     * @param gameDay  the world's current day number
     */
    public static Result feed(ServerPlayer player, ItemStack stack, DishThrottle throttle,
            long gameDay) {
        ResourceLocation dish = Dishes.idOf(stack);
        if (!Dishes.isDishId(dish)) {
            return Result.of(Outcome.NOT_A_DISH);   // the handler filters first; belt to its braces
        }
        Roster roster = player.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            Astrum.LOGGER.warn("astrum: growth: {} tried to feed {} with an unreadable character book — "
                    + "affection not written", player.getGameProfile().getName(), dish);
            return Result.of(Outcome.BOOK_UNREADABLE);
        }
        Optional<Registry<CharacterDef>> characters = AstrumApi.characters();
        if (characters.isEmpty()) {
            return Result.of(Outcome.NOBODY_TO_FEED);
        }
        Map<ResourceLocation, CharacterProgress> raised = affectionRaised(player, roster,
                characters.get(), dish);
        if (raised == null) {
            return Result.of(Outcome.NOBODY_TO_FEED);
        }
        if (raised.isEmpty()) {
            return Result.of(Outcome.ALREADY_FULL);
        }
        UUID id = player.getUUID();
        if (!throttle.allow(id, dish, gameDay)) {
            return Result.of(Outcome.ALREADY_TODAY);
        }
        player.setData(Attachments.ROSTER, roster.withProgress(raised));
        Astrum.LOGGER.debug("astrum: growth: {} fed {} on day {} -> affection raised {}",
                player.getGameProfile().getName(), dish, gameDay, raised.size(), raised);
        return new Result(Outcome.FED, raised.size());
    }

    /**
     * {@return the new progress records a fed dish would write, an empty map when every party member is
     * already at the cap, or {@code null} when there is no reachable character in the party}
     *
     * <p>Pure with respect to the roster: it reads and computes, it never writes. That is what lets the
     * throttle be consulted only once a real change is known to exist.
     */
    private static Map<ResourceLocation, CharacterProgress> affectionRaised(ServerPlayer player,
            Roster roster, Registry<CharacterDef> registry, ResourceLocation dish) {
        Party party = player.getData(Attachments.PARTY);
        Map<ResourceLocation, CharacterProgress> raised = new LinkedHashMap<>();
        boolean anyKnown = false;
        for (var slot : party.slots()) {
            if (slot.isEmpty()) {
                continue;
            }
            ResourceLocation characterId = slot.get();
            CharacterProgress progress = roster.progressOf(characterId);
            CharacterDef def = registry.get(characterId);
            if (progress == null || def == null) {
                continue;   // equipped but not owned, or a character the pack no longer defines
            }
            anyKnown = true;
            int updated = AffectionKernel.clampAffection(progress.affection(), 1,
                    def.affection().tier3Threshold());
            if (updated != progress.affection()) {
                raised.put(characterId,
                        new CharacterProgress(progress.level(), progress.exp(), updated));
            }
        }
        if (!anyKnown) {
            Astrum.LOGGER.debug("astrum: growth: no owned, known character in the party of {} to feed {}",
                    player.getGameProfile().getName(), dish);
            return null;
        }
        return raised;
    }
}
