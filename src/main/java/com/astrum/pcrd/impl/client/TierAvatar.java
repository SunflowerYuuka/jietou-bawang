package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.network.RosterSyncPayload;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Which avatar a character shows right now</b> — the one place the tier-to-picture join is made.
 *
 * <h2>What the human asked for</h2>
 * 「角色头像要跟随好感度进行变化，符合前面的1* 3* 6*的要求」 — a character's face follows her affection tier,
 * 1★ / 3★ / 6★. Spec §9.4 already said so; it was never implemented, so every surface drew the one
 * {@code avatar} picture forever.
 *
 * <h2>What is decided here, and what is not</h2>
 * The <b>tier</b> is not decided here. It comes from one of two existing single sources:
 *
 * <ul>
 *   <li>{@link RosterSyncPayload.Entry#tier()} — the server's answer, computed by
 *       {@code GrowthFacts.tier} and pushed in the roster frame. Every surface that has a progress row
 *       (the book's roster cells, the growth view) uses this, so a client with a stale pack cannot
 *       disagree with the server about a tier.</li>
 *   <li>{@link GrowthFacts#tier(int, com.astrum.pcrd.api.v1.AffectionDef)} — the same ladder, for a caller
 *       that holds an affection number and a definition but no roster row.</li>
 * </ul>
 *
 * <p>What this class adds is only the <em>join</em>: a character id plus the roster gives that character's
 * entry, entry gives the tier, and {@link CharacterDef#face(int)} turns the tier into a picture. The
 * degradation rule (tier 3 with no 6★ art → 3★ art) lives in {@code CharacterDef.face(int)}, not here, so
 * there is exactly one copy of it.
 *
 * <h2>Where it applies</h2>
 * <ol>
 *   <li>the book's <b>roster cells</b> — via {@link #forRosterEntry};</li>
 *   <li>the book's <b>party slots</b> — via {@link #forPartySlot};</li>
 *   <li>the HUD's <b>gauge circle</b> — via {@link PresetFace#face()} (it owns the preset join and calls
 *       this for the tier);</li>
 *   <li>the <b>party-effect icon</b> — via {@code PartyEffectClient.avatar}, which reads
 *       {@link #forPartySlot} for the same reason.</li>
 *   <li>the book panel's <b>background still</b> — via {@link #stillOf(CharacterDef, ResourceLocation)},
 *       which takes the tier and hands it to {@link CharacterDef#still(int)}. It is the same join and the
 *       same tier source as the avatar; only the picture family differs.</li>
 * </ol>
 * The ring wheel and the cast pad keep the plain {@link CharacterDef#face()} because they are drawn while
 * the wheel is open as a fast overlay: they can hold a definition and a tier-3 bit, but a roster row is a
 * different packet, and asking for it there would make the overlay's cost depend on a sync it does not need.
 *
 * <h2>Why a class and not a method on {@code CharacterDef}</h2>
 * {@code CharacterDef} is {@code api.v1} and must not import {@code impl} (guard rule 4), so it cannot read
 * the roster cache. It owns the pure rule ({@code face(int)}); this class owns the game-side lookup. That
 * split is the same one {@code PresetFace} makes for the HUD.
 */
@ApiStatus.Internal
public final class TierAvatar {

    /**
     * Tier reported for a character with no synced progress row.
     *
     * <p>{@link GrowthFacts#FIRST_TIER}: a character the client has no row for is drawn at the lowest
     * tier, because that is the only tier nobody had to earn. It is also what the roster grid shows for
     * one tick before the first sync lands.
     */
    public static final int UNSYNCED_TIER = GrowthFacts.FIRST_TIER;

    private TierAvatar() {
    }

    /**
     * {@return the tier to draw a character at, from the roster push}
     *
     * @param characterId the character
     */
    public static int tierOf(ResourceLocation characterId) {
        return ClientRosterCache.entry(characterId)
                .map(RosterSyncPayload.Entry::tier)
                .filter(tier -> tier > 0)
                .orElse(UNSYNCED_TIER);
    }

    /**
     * {@return the avatar to draw for one roster row}
     *
     * <p>The row is the authority for its own tier: the server already computed it, so this does not take
     * an affection number and does not run the ladder.
     *
     * @param definition the character, or {@code null} when the pack does not define her anymore
     * @param entry      her progress row, or {@code null} for a character the roster does not hold
     */
    public static Optional<ResourceLocation> forRosterEntry(@Nullable CharacterDef definition,
            @Nullable RosterSyncPayload.Entry entry) {
        if (definition == null) {
            return Optional.empty();
        }
        // An unsynced/absent row answers the lowest tier rather than "no picture": the roster grid must
        // still draw her face, and the 1★ art is the one tier nobody had to earn.
        int tier = entry == null || entry.tier() <= 0 ? UNSYNCED_TIER : entry.tier();
        return definition.face(tier);
    }

    /**
     * {@return the avatar to draw for a party slot}
     *
     * <p>Reads the roster for the tier, because the party frame carries only a tier-3 bitmask — enough for
     * the wheel's gold ring, not enough to pick between 1★, 3★ and 6★. Upgrading the party frame to carry
     * the full tier would duplicate a value the roster already owns.
     *
     * @param definition the character, or {@code null} when the pack does not define her anymore
     * @param characterId the character's id, for the roster lookup
     */
    public static Optional<ResourceLocation> forPartySlot(@Nullable CharacterDef definition,
            ResourceLocation characterId) {
        if (definition == null) {
            return Optional.empty();
        }
        return definition.face(tierOf(characterId));
    }

    /**
     * {@return the avatar for a raw affection number, for tests and for callers holding no roster row}
     *
     * <p>Runs the <b>shared</b> ladder ({@link GrowthFacts#tier}), never a copy of it.
     *
     * @param definition the character
     * @param affection  her affection
     */
    public static Optional<ResourceLocation> forAffection(CharacterDef definition, int affection) {
        return definition.face(GrowthFacts.tier(affection, definition.affection()));
    }

    /**
     * {@return the still to draw as the book panel's background for a character at her <b>current</b> tier}
     *
     * <h2>What changed, and the request behind it</h2>
     * This used to be deliberately tierless — one still per character — on the argument that the dump ships
     * a still per rarity only for some units, so a tier-keyed still would leave a freshly enrolled
     * character with no background at all. That argument was right about the constraint and wrong about the
     * conclusion: the human's rule is 「背景立绘要随着好感的升阶更换背景立绘」, and the constraint is answerable by
     * <b>degradation</b> rather than by freezing the picture. {@link CharacterDef#still(int)} is
     * {@code still} (the 3★ rung, drawn at tier 1 and 2 — no 1★ still exists in the official set) with
     * {@code still6th} taking over at tier 3, so every tier answers a picture and the picture changes at the
     * rung the art set actually has.
     *
     * <p>The tier is read from the roster row, the server's answer, exactly as {@link #forRosterEntry} reads
     * it for the avatar. A character the client has no row for is drawn at
     * {@link #UNSYNCED_TIER} rather than with no background, which is the same promise
     * {@link CharacterDef#face(int)} makes.
     *
     * @param definition the character, or {@code null}
     * @param characterId the character's id, for the roster lookup; {@code null} means "no row"
     */
    public static Optional<ResourceLocation> stillOf(@Nullable CharacterDef definition,
            @Nullable ResourceLocation characterId) {
        if (definition == null) {
            return Optional.empty();
        }
        return definition.still(characterId == null ? UNSYNCED_TIER : tierOf(characterId));
    }

    /**
     * {@return the still for a raw affection number, for tests and for callers holding no roster row}
     *
     * <p>Runs the <b>shared</b> ladder ({@link GrowthFacts#tier}), never a copy of it — the same contract
     * {@link #forAffection} has for the avatar.
     *
     * @param definition the character
     * @param affection  her affection
     */
    public static Optional<ResourceLocation> stillForAffection(CharacterDef definition, int affection) {
        return definition.still(GrowthFacts.tier(affection, definition.affection()));
    }

    /**
     * {@return the first character the book should show as selected}
     *
     * <h2>The rule the human gave</h2>
     * 「默认使用角色1当背景」 — the default selection is <b>party slot 1's</b> character. When that slot is
     * empty the screen must still show something rather than a dark panel, so the fallback chain is:
     *
     * <ol>
     *   <li>the character in party slot 1 ({@code Party.SLOTS} numbered from 0, so slot index 0);</li>
     *   <li>otherwise the first character in the roster (roster order = the order they were enrolled,
     *       which is stable and is what the grid shows first);</li>
     *   <li>otherwise nothing — an empty book, and the panel keeps its plain dark body.</li>
     * </ol>
     *
     * <p>Pure, so all three cases are unit-testable without a client: the two inputs are the party's slot
     * list (already what {@link ClientPartyCache} holds) and the roster rows.
     *
     * @param partySlots the party, in slot order
     * @param roster     the roster, in roster order
     */
    public static Optional<ResourceLocation> defaultSelection(
            List<Optional<ResourceLocation>> partySlots, List<RosterSyncPayload.Entry> roster) {
        if (partySlots != null && !partySlots.isEmpty()) {
            Optional<ResourceLocation> first = partySlots.get(0);
            if (first != null && first.isPresent()) {
                return first;
            }
        }
        if (roster == null || roster.isEmpty()) {
            return Optional.empty();
        }
        RosterSyncPayload.Entry head = roster.get(0);
        return head == null ? Optional.empty() : Optional.of(head.id());
    }

    /** {@return {@link #defaultSelection} applied to the client's two caches} */
    public static Optional<ResourceLocation> defaultSelection() {
        return defaultSelection(ClientPartyCache.latest().slots(), ClientRosterCache.entries());
    }
}
