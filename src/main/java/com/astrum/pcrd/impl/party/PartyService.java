package com.astrum.pcrd.impl.party;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.effect.PartyBuffs;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The one server-side party edit: resolve, decide, write.
 *
 * <h2>Both callers go through here, and that is the point</h2>
 * {@code /astrum party set|clear} and the 角色册 screen's {@code astrum:party_edit} payload are two
 * doors into the same room. Spec §6.3.1 requires them to behave identically, and the cheap way to
 * guarantee that is for neither of them to contain a rule: this method resolves the roster and the
 * registry, asks {@link PartyRules#refusal} (pure, unit-tested), writes the attachment on success, and
 * returns a typed verdict either way.
 *
 * <p>The verdict is also <b>logged with the same shape from both doors</b>, so a run of the headless
 * probe can show the two paths answering the same request the same way, rather than asserting that
 * they would because the source looks similar:
 *
 * <pre>
 *   astrum: party edit refused for Dev slot 9 -&gt; astrum:djeeta: SLOT_OUT_OF_RANGE
 *   astrum: party edit accepted for Dev slot 0 -&gt; astrum:djeeta (moved from -1, 1 characters equipped)
 * </pre>
 *
 * <h2>Quarantine is a refusal, not an exception</h2>
 * {@link Roster#entries()} and {@link Roster#progressOf} <b>throw</b> while the book is quarantined.
 * A payload handler that let that escape would disconnect the player; so does the whole
 * {@link PartyRules.Reason#BOOK_UNREADABLE} branch: nothing is read from a book this build cannot
 * parse, nothing is written over the tag kept on disk, and the player is told.
 *
 * <p>This is server code only — it takes a {@link ServerPlayer} and writes an attachment. The decision
 * half it delegates to is free of Minecraft state, which is what makes it testable without a server.
 */
@ApiStatus.Internal
public final class PartyService {

    /**
     * The outcome of one edit attempt.
     *
     * @param reason     why it was refused, or {@code null} when it was accepted
     * @param definition the character's definition when the pack defines it (for a name in a message),
     *                   otherwise {@code null}
     * @param edit       what the edit was, or {@code null} when it was refused
     */
    public record Result(@Nullable PartyRules.Reason reason, @Nullable CharacterDef definition,
                         @Nullable PartyRules.Edit edit) {

        /** {@return whether the edit was applied} */
        public boolean accepted() {
            return reason == null;
        }
    }

    private PartyService() {
    }

    /**
     * Puts {@code characterId} in {@code slot} (or clears the slot when it is {@code null}).
     *
     * <p>Writes {@code Attachments.PARTY} only on acceptance and only when something actually changed,
     * so a request for a state the player is already in costs no save and no sync.
     *
     * @param player      whose party is edited
     * @param slot        0-based slot, from the wire or from {@code WheelSlots.slotForDigit}
     * @param characterId the character to place, or {@code null} to clear the slot
     */
    public static Result edit(ServerPlayer player, int slot, @Nullable ResourceLocation characterId) {
        Roster roster = player.getData(Attachments.ROSTER);
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();

        boolean bookReadable = !roster.isQuarantined();
        boolean registryLoaded = registry.isPresent();
        CharacterDef definition = null;
        boolean known = true;
        boolean owned = true;
        if (characterId != null) {
            if (registry.isPresent()) {
                definition = registry.get().get(characterId);
                known = definition != null;
            }
            // Only asked of a readable book: progressOf() throws while quarantined, and the
            // BOOK_UNREADABLE refusal below is the answer for that case anyway.
            owned = bookReadable && roster.progressOf(characterId) != null;
        }

        Optional<PartyRules.Reason> refusal = PartyRules.refusal(
                new PartyRules.Facts(slot, bookReadable, registryLoaded, known, owned));
        String who = player.getGameProfile().getName();
        if (refusal.isPresent()) {
            Astrum.LOGGER.info("astrum: party edit refused for {} slot {} -> {}: {}", who, slot,
                    characterId, refusal.get());
            return new Result(refusal.get(), definition, null);
        }

        Party party = player.getData(Attachments.PARTY);
        PartyRules.Edit edit = PartyRules.place(party, slot, characterId);
        if (edit.changed()) {
            player.setData(Attachments.PARTY, edit.after());
        }
        // The visible half of the party: one HUD effect per occupied slot, removed for a cleared one.
        // Idempotent and cheap (an existing instance is left alone), so it runs on every accepted edit
        // and not only on a change — a party written by an older build is then repaired by the next
        // edit instead of waiting for the next login.
        PartyBuffs.apply(player);
        Astrum.LOGGER.info("astrum: party edit accepted for {} slot {} -> {} (previous slot {}, "
                        + "changed {}, {} characters equipped)", who, slot, characterId,
                edit.previousSlot().isPresent() ? edit.previousSlot().getAsInt() : -1,
                edit.changed(), edit.after().equipped().size());
        return new Result(null, definition, edit);
    }
}
