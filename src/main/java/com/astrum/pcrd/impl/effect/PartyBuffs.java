package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.world.effect.MobEffect;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The server-side lifecycle of the five party effects: <b>derived from the party, never from a tick</b>.
 *
 * <h2>When this runs</h2>
 * There are exactly three places where a player's equipped characters can change, and each one calls
 * {@link #apply}:
 *
 * <ul>
 *   <li>a party edit — {@code PartyService.edit}, the single implementation behind both the
 *       character-book payload and {@code /astrum party set|clear}; this includes clearing a slot, so
 *       leaving the party removes the effect;</li>
 *   <li>logging in — {@code PlayerEvent.PlayerLoggedInEvent}, because the party is saved state and
 *       the effects are not: a player who re-enters a world must get them back;</li>
 *   <li>respawning — {@code PlayerEvent.PlayerRespawnEvent}, because death clears every effect and the
 *       player must get their party's entries back without editing the party.</li>
 * </ul>
 *
 * <p>No tick listener and no polling: the world already fires those three events, and a per-tick
 * reconcile would be a fourth, redundant source of the same truth (spec §4.4's red line). If a future
 * feature changes the party <b>without</b> going through {@link com.astrum.pcrd.impl.party.PartyService},
 * it would have to call this class itself — that is the contract, stated here rather than discovered
 * later as "the buff sometimes does not appear".
 *
 * <h2>Why the duration is infinite</h2>
 * The effect must last exactly as long as the character is in the party, and nothing refreshes it on
 * a timer (no tick listener). A finite long duration would therefore expire during a long session
 * and quietly violate "while in the party"; {@link MobEffectInstance#INFINITE_DURATION} cannot expire,
 * and adding the same instance again is a no-op, so a re-apply can never flicker.
 *
 * <p>The instances are invisible as particles and ambient-free; they are <em>visible</em> with an
 * icon, which is the entire point.
 */
@ApiStatus.Internal
public final class PartyBuffs {

    /** Duration in ticks: infinite, see the class note. */
    public static final int DURATION = MobEffectInstance.INFINITE_DURATION;

    private PartyBuffs() {
    }

    /**
     * Makes the player's five party effects match the party attachment exactly.
     *
     * <p>Idempotent: a slot whose character did not change keeps its instance (the instance is already
     * infinite, so it is not re-added and the HUD does not blink), an occupied slot with no effect
     * gains one, and an empty slot with a leftover effect loses it.
     *
     * @param player the player whose effects are reconciled; must be in a level
     * @return how many effects were granted or removed, for the caller's log
     */
    public static int apply(ServerPlayer player) {
        Party party = player.getData(Attachments.PARTY);
        int changes = 0;
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            DeferredHolder<MobEffect, MobEffect> holder = PartyEffects.holderFor(slot);
            Optional<ResourceLocation> character = party.slots().get(slot);
            if (character.isPresent()) {
                if (player.getEffect(holder) == null) {
                    player.addEffect(new MobEffectInstance(holder, DURATION, 0,
                            false, false, true));
                    changes++;
                    log(player, slot, character.get(), holder.getId(), "granted");
                }
            } else if (player.hasEffect(holder)) {
                player.removeEffect(holder);
                changes++;
                log(player, slot, null, holder.getId(), "removed");
            }
        }
        return changes;
    }

    /**
     * Removes every party effect, whatever the party says.
     *
     * <p>Not called by production code today: it exists so a future "the party feature is turned off"
     * path (or a test) has one place to undo {@link #apply} rather than five {@code removeEffect}
     * literals scattered around.
     *
     * @param player the player to clear
     * @return how many effects were removed
     */
    public static int clear(ServerPlayer player) {
        int removed = 0;
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            DeferredHolder<MobEffect, MobEffect> holder = PartyEffects.holderFor(slot);
            if (player.removeEffect(holder)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Logs one grant/removal together with the name the HUD will show.
     *
     * <p>The skill name is resolved here with the same {@link PartyBuffName} the client uses, so the
     * log line is evidence that the display name really is the character's skill name rather than the
     * character's display name.
     */
    private static void log(ServerPlayer player, int slot, @Nullable ResourceLocation characterId,
                            ResourceLocation effectId, String what) {
        String who = player.getGameProfile().getName();
        if (characterId == null) {
            Astrum.LOGGER.info("astrum: party effect slot {} {} for {} ({})", slot + 1, what, who,
                    effectId);
            return;
        }
        CharacterDef definition = definition(characterId);
        Astrum.LOGGER.info("astrum: party effect slot {} {} for {} -> {} ({}, skill \"{}\")",
                slot + 1, what, who, characterId, effectId,
                definition == null ? "(unknown character)" : definition.effectiveSkillName());
    }

    /** {@return the loaded definition of a character, or {@code null} outside a world/none} */
    static @Nullable CharacterDef definition(ResourceLocation characterId) {
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        return registry.map(characters -> characters.get(characterId)).orElse(null);
    }
}