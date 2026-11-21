package com.astrum.pcrd.impl.network;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.attach.UbCooldown;
import com.astrum.pcrd.impl.attach.UbState;
import com.astrum.pcrd.impl.client.ClientBookFeedback;
import com.astrum.pcrd.impl.client.ClientCastFeedback;
import com.astrum.pcrd.impl.client.ClientPartyCache;
import com.astrum.pcrd.impl.client.ClientRosterCache;
import com.astrum.pcrd.impl.client.ClientUbCache;
import com.astrum.pcrd.impl.growth.GrowthFacts;
import com.astrum.pcrd.impl.party.PartyService;
import com.astrum.pcrd.impl.ub.CastGate;
import com.astrum.pcrd.impl.ub.SyncGate;
import com.astrum.pcrd.impl.ub.UbService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The mod's payloads, all registered here, on both sides: the charge channel (one clientbound
 * payload, pushed to a player only when the server's copy of their gauge actually changed), the party
 * channel ({@link PartySyncPayload}, clientbound, the ordered slot contents plus their tier-3 bits, so
 * the client can draw the wheel), the UB cast request ({@link UbCastPayload}, serverbound) and its typed
 * refusal ({@link UbCastFeedbackPayload}, clientbound, back to that one player).
 *
 * <h2>The cast pair: one request up, one reason down</h2>
 * The client says only <em>which slot</em>; {@link UbService#tryCast} re-decides everything on the
 * server and answers through {@link CastGate.Reason}. A <b>refusal</b> is answered on the same connection,
 * to the asking player only ({@code PacketDistributor.sendToPlayer}), never broadcast — spec §5.4's
 * 禁止静默失败 without letting one player's cooldown shake another's slot.
 *
 * <p>A <b>success</b> sends nothing on this channel: the gauge push carries the cleared bar and the new
 * cooldown, and the visible half is <b>not a custom payload</b>. It is vanilla particles — see
 * {@code UbCastParticles}, which calls {@code ServerLevel.sendParticles} from the server. That is why
 * {@code ub_cast_effect} no longer exists: the effect needs no mod protocol to describe it. The one
 * clientbound mod message that remains in the cast path is the refusal, and a refused cast sends it
 * alone.
 *
 * <h2>Why the registration is one call, on the mod bus, in common code</h2>
 * {@link RegisterPayloadHandlersEvent} is an {@code IModBusEvent} fired by
 * {@code NetworkRegistry.setup()} through {@code ModLoader.postEvent}, on every physical side that
 * loads the mod. Subscribing from {@link #register(IEventBus)} — called from the {@code @Mod}
 * constructor, i.e. from common code — therefore registers the payload in the client's
 * {@code NetworkRegistry} <em>and</em> the dedicated server's. That is what spec §9.5's
 * "payload 两端都注册" requires: a payload registered only in a client-only class would be unknown to
 * the server that has to encode it, and vice versa. There is deliberately no
 * {@code FMLEnvironment.dist} check here, because skipping the call on the server would break
 * exactly that.
 *
 * <p>The handlers are references to common methods whose bodies touch only {@link ClientUbCache},
 * {@link ClientPartyCache} and {@link ClientCastFeedback} — classes with no {@code net.minecraft.client}
 * imports — so loading this class on a dedicated server cannot pull client-only types in. The three
 * {@code playToClient} handlers are only ever invoked on the client, for that reason; the
 * {@code playToServer} one runs on the server and touches only server types.
 *
 * <h2>Why the push is here and not at the mutation site</h2>
 * Charge is mutated from several places (damage dealt, damage taken, a kill, later the
 * {@code gain_charge} effect). Sending from each of them would mean N packets for one tick's worth
 * of state, and would put the "is this worth a packet" question in N places. Instead the gauge is
 * written locally and this listener observes it: at most one push per player per 5-tick window,
 * only when {@link SyncGate} says the client does not already hold the value.
 */
@ApiStatus.Internal
public final class AstrumNetwork {

    /**
     * Negotiated payload version; bump when any payload registered here changes shape.
     *
     * <p>Bumped 3 -> 4 for the per-character cooldowns: {@code charge_sync} now carries a map of
     * deadlines (each with its own total) instead of one shared {@code cdEndTick}, so a 3.x client and a
     * 4.x server would misread each other's frames. The version string is handed to
     * {@code PayloadRegistrar}, which is what makes the mismatch a connect-time refusal rather than a
     * misparsed packet.
     */
    public static final String PROTOCOL_VERSION = "5";

    /**
     * How often the party's tier-3 mask is re-resolved when the party itself has not changed.
     *
     * <p>An affection level-up changes the mask without changing the party, and nothing fires an event
     * for it, so it has to be polled — but once a second is enough for an icon (see {@code pushParty}).
     * Twenty ticks is also the cheapest sensible window: the mask costs five registry lookups and five
     * roster reads, and doing that every tick per player for data that cannot change that fast is the
     * cost this constant removes.
     */
    private static final int TIER_MASK_INTERVAL_TICKS = 20;

    /**
     * What each online player's client was last told, keyed by UUID.
     *
     * <p>Transient bookkeeping, not player state: the authoritative gauge lives in the
     * {@code UB_STATE} attachment and is persisted with the player. This map only answers "would
     * re-sending be redundant", so it is intentionally <b>not</b> saved and is dropped on logout
     * (a re-joining client starts from the default and must be re-sent). Only touched from server
     * callbacks, so a plain {@link HashMap} is enough.
     */
    private static final Map<UUID, Sent> LAST_SENT = new HashMap<>();

    /**
     * What each online player's client was last told about their party, keyed by UUID.
     *
     * <p>Same idea as {@link #LAST_SENT} one field up, and for the same reason: the party is mutated
     * from outside this class (a command, a UI, a save load), so the push is a diff against the last
     * thing sent rather than a call at the mutation site. The tier-3 mask is part of the compared
     * value because the wheel's icon depends on it: an affection level-up must reach the client even
     * though the party itself did not change. Dropped on logout, so a re-join is always re-sent.
     */
    private static final Map<UUID, SentParty> LAST_PARTY = new HashMap<>();

    /**
     * What each online player's client was last told about their character book, keyed by UUID.
     *
     * <p>Third of the same pattern. The book is mutated from outside this class (a character card, the
     * command, later the growth task), and every one of those writes a <b>new {@link Roster}</b>
     * instance whose entry map is a fresh immutable copy — so comparing the map <em>by reference</em> is
     * enough to notice a change, and costs one reference compare per player per tick instead of a deep
     * comparison of the whole book. On login the map holds nothing for this UUID, so the first tick
     * after joining always sends, which is the "send on login" half without a separate listener.
     *
     * <p>Dropped on logout, so a re-join is always re-sent.
     */
    private static final Map<UUID, SentRoster> LAST_ROSTER = new HashMap<>();

    private AstrumNetwork() {
    }

    /**
     * Wires this subsystem: payload registration on the mod bus, plus the server-side push loop on
     * the game bus.
     *
     * @param modBus the mod-specific event bus
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, AstrumNetwork::onRegisterPayloads);
        NeoForge.EVENT_BUS.addListener(PlayerTickEvent.Post.class, AstrumNetwork::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, AstrumNetwork::onLoggedOut);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // One registrar, both flows, no dist check: this call must run on the client and on the
        // dedicated server alike (see the class javadoc).
        event.registrar(PROTOCOL_VERSION)
                .playToClient(ChargeSyncPayload.TYPE, ChargeSyncPayload.STREAM_CODEC,
                        AstrumNetwork::handleChargeSync)
                .playToClient(PartySyncPayload.TYPE, PartySyncPayload.STREAM_CODEC,
                        AstrumNetwork::handlePartySync)
                .playToServer(UbCastPayload.TYPE, UbCastPayload.STREAM_CODEC,
                        AstrumNetwork::handleUbCast)
                .playToClient(UbCastFeedbackPayload.TYPE, UbCastFeedbackPayload.STREAM_CODEC,
                        AstrumNetwork::handleUbCastFeedback)
                .playToClient(RosterSyncPayload.TYPE, RosterSyncPayload.STREAM_CODEC,
                        AstrumNetwork::handleRosterSync)
                .playToServer(PartyEditPayload.TYPE, PartyEditPayload.STREAM_CODEC,
                        AstrumNetwork::handlePartyEdit)
                .playToClient(PartyEditResultPayload.TYPE, PartyEditResultPayload.STREAM_CODEC,
                        AstrumNetwork::handlePartyEditResult);
        Astrum.LOGGER.info("astrum: payloads registered (charge_sync, party_sync, ub_cast, "
                + "ub_cast_feedback, roster_sync, party_edit, party_edit_result) — no UB effect "
                + "payload; the burst is vanilla ServerLevel.sendParticles, protocol {}",
                PROTOCOL_VERSION);
    }

    /**
     * Receives the server's gauge on the client.
     *
     * <p>No {@code context.enqueueWork} is needed: {@code PayloadRegistrar} wraps handlers in
     * {@code MainThreadPayloadHandler} by default ({@code HandlerThread.MAIN}), so this already runs
     * on the client's main thread.
     */
    private static void handleChargeSync(ChargeSyncPayload payload, IPayloadContext context) {
        ClientUbCache.accept(payload);
    }

    /**
     * Receives the ordered party on the client, so the wheel can be drawn.
     *
     * <p>Display data only: nothing here decides anything. A wheel opened before the first sync
     * arrives shows five empty slots rather than guessing an order (spec §5.5 — order is meaning).
     */
    private static void handlePartySync(PartySyncPayload payload, IPayloadContext context) {
        ClientPartyCache.accept(payload);
    }

    /**
     * Receives the client's cast request on the server and answers a refusal on the same connection.
     *
     * <p>Only the slot is taken from the packet: {@link UbService#tryCast} re-resolves the party, the
     * registry, the gauge and the cooldown, so nothing the client says here is trusted beyond "which
     * slot the player picked" (spec §9.5).
     *
     * <p>The refusal goes to <b>this</b> player and nobody else. It also needs no
     * {@code enqueueWork}: same {@code HandlerThread.MAIN} default as above, so the cast is decided on
     * the server thread, in the same tick the packet arrived.
     */
    private static void handleUbCast(UbCastPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            // Unreachable for a playToServer payload; if it ever happens the registration is wrong,
            // so say so rather than throwing into the packet handler.
            Astrum.LOGGER.warn("astrum: ub_cast arrived without a ServerPlayer (slot {}) — dropped",
                    payload.slot());
            return;
        }
        UbService.tryCast(player, payload.slot())
                .ifPresent(reason -> PacketDistributor.sendToPlayer(player,
                        new UbCastFeedbackPayload(reason)));
    }

    /**
     * Receives the server's refusal on the client.
     *
     * <p>Stamps the refusal with the client's own game time here rather than inside
     * {@link ClientCastFeedback}: the cache must stay a plain value holder so this class can be loaded
     * on a dedicated server without dragging a level (let alone a client level) into its types.
     */
    private static void handleUbCastFeedback(UbCastFeedbackPayload payload, IPayloadContext context) {
        ClientCastFeedback.accept(payload, context.player().level().getGameTime());
    }

    /**
     * Receives the server's copy of the character book on the client.
     *
     * <p>Display data only: the screen draws what the server says the player owns, and the two rules it
     * would otherwise have to reimplement — the experience curve and the affection tier ladder — arrive
     * as the numbers {@code expToNext} and {@code tier} on each entry.
     */
    private static void handleRosterSync(RosterSyncPayload payload, IPayloadContext context) {
        ClientRosterCache.accept(payload);
    }

    /**
     * Receives the client's party-edit intent and answers a refusal on the same connection.
     *
     * <p>Only the slot and the requested character are taken from the packet. {@link PartyService#edit}
     * re-resolves the roster, the registry and the current party, and applies exactly the rules
     * {@code /astrum party set} applies — that shared call is the whole point of spec §6.3.1: a client
     * cannot name a character it does not own, a slot that does not exist, or a book it cannot read,
     * and it cannot create a party the data layer would reject.
     *
     * <p>Success sends nothing: the party push diffs the attachment every tick and the roster push
     * diffs the book, so the new state reaches this client in the same tick. A refusal goes to
     * <b>this</b> player and nobody else — spec §5.4's 禁止静默失败 without letting one player's
     * mistyped click shake another's screen.
     */
    private static void handlePartyEdit(PartyEditPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            // Unreachable for a playToServer payload; say so rather than throwing into the handler.
            Astrum.LOGGER.warn("astrum: party_edit arrived without a ServerPlayer (slot {}) — dropped",
                    payload.slot());
            return;
        }
        PartyService.Result result = PartyService.edit(player, payload.slot(),
                payload.characterId().orElse(null));
        if (!result.accepted()) {
            PacketDistributor.sendToPlayer(player,
                    new PartyEditResultPayload(payload.slot(), result.reason()));
        }
    }

    /** Receives the server's refusal for the 角色册 screen to draw. */
    private static void handlePartyEditResult(PartyEditResultPayload payload, IPayloadContext context) {
        ClientBookFeedback.accept(payload, context.player().level().getGameTime());
    }

    /**
     * The push loop. Runs once per player per tick on both logical sides, so the server check is the
     * {@code instanceof}: only a {@link ServerPlayer} owns an authoritative gauge.
     */
    private static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        pushCharge(player);
        pushParty(player);
        pushRoster(player);
    }

    private static void pushCharge(ServerPlayer player) {
        // getData, not getExistingData: a player who has not charged yet still owns a gauge, and its
        // value is the documented default. Reading it materialises that default, which is what makes
        // the first window after login push the real state — otherwise a client whose cache still
        // holds the previous world's charge (the same JVM switches worlds) would never be corrected.
        UbState state = player.getData(Attachments.UB_STATE);
        // Only the cooldowns still running are sent: an expired deadline is a fact the client can also
        // derive, but sending it would leave a stale entry on a client that reconnects, and the map's
        // size is what bounds the frame. The same filtered map is what SyncGate compares, so "an entry
        // expired" is itself a change and produces the push that tells the client she is castable.
        Map<ResourceLocation, UbCooldown> running =
                state.activeCooldowns(player.level().getGameTime());
        Sent last = LAST_SENT.getOrDefault(player.getUUID(), Sent.NEVER);
        if (!SyncGate.shouldSync(player.tickCount, last.tick(), state.charge(), running,
                last.charge(), last.deadlines())) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new ChargeSyncPayload(state.charge(), running));
        LAST_SENT.put(player.getUUID(),
                new Sent(player.tickCount, state.charge(), running));
    }

    /**
     * Pushes the ordered party whenever it — or any slot's tier-3 status — actually changed.
     *
     * <h2>Why this is a per-tick diff and what it costs per tick</h2>
     * The party is mutated from outside this class (a command, a UI, a save load), and there is no
     * change notification to subscribe to, so the push is a diff against the last thing sent rather
     * than a call at the mutation site. What it must <b>not</b> be is expensive on the ticks where
     * nothing changed, which used to be the case: the tier-3 mask (five registry lookups plus five
     * roster reads) was resolved <em>before</em> the comparison, so every player paid for it every tick
     * to discover that the answer was unchanged.
     *
     * <p>Now the common tick costs one attachment read and one record comparison. The mask is resolved
     * only in two cases: the party itself changed (then the push happens in this very tick, so a
     * reordering or an equip reaches the wheel immediately), or
     * {@link #TIER_MASK_INTERVAL_TICKS} has elapsed, because an affection level-up changes the mask
     * without touching the party and there is nothing to observe it on. One second of lag on a tier-3
     * icon is invisible to a player — affection moves on level-up, not tick to tick — and the cast path
     * never consults this mask at all (it re-reads the roster itself).
     *
     * <p>On login the map holds nothing for this UUID, so the first tick after joining always sends:
     * that is the "send on login" half of the requirement, without needing a separate login listener.
     *
     * <p>The tier-3 mask is resolved here, not on the client, because affection lives in the server's
     * {@code ROSTER}; {@link UbService#atTier3} is the same rule the cast path uses.
     */
    private static void pushParty(ServerPlayer player) {
        Party party = player.getData(Attachments.PARTY);
        SentParty last = LAST_PARTY.get(player.getUUID());
        boolean unchanged = last != null && last.party().equals(party);
        if (unchanged && player.tickCount % TIER_MASK_INTERVAL_TICKS != 0) {
            return;
        }
        int mask = PartySyncPayload.maskOf(party, id -> isTier3(player, id));
        if (unchanged && last.enhancedMask() == mask) {
            return;
        }
        PacketDistributor.sendToPlayer(player, PartySyncPayload.of(party, mask));
        LAST_PARTY.put(player.getUUID(), new SentParty(party, mask));
        Astrum.LOGGER.debug("astrum: party synced to {} ({} characters, tier-3 mask {})",
                player.getGameProfile().getName(), party.equipped().size(), mask);
    }

    /**
     * {@return whether {@code characterId} is at affection tier 3 for this player}
     *
     * <p>A character the running pack no longer defines cannot be at any tier, and an unknown id is
     * not worth a warning here: {@code CastGate} already reports {@code CHARACTER_UNKNOWN} when the
     * player tries to cast it.
     */
    private static boolean isTier3(ServerPlayer player, ResourceLocation characterId) {
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        if (registry.isEmpty()) {
            return false;
        }
        CharacterDef def = registry.get().get(characterId);
        return def != null && UbService.atTier3(player, characterId, def);
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
        LAST_PARTY.remove(event.getEntity().getUUID());
        LAST_ROSTER.remove(event.getEntity().getUUID());
    }

    /**
     * Pushes the character book whenever it actually changed.
     *
     * <h2>Why a diff and not a call at the mutation site</h2>
     * The book is written by the character card, by {@code /astrum affection}, and later by the growth
     * task — three places today and more later. Sending from each would be N packets per change and N
     * copies of "is this worth a packet". Instead this listener observes the attachment, like the two
     * pushes above.
     *
     * <h2>What the common tick costs</h2>
     * One attachment read, two field reads and a reference compare. The payload — which needs the
     * character registry, one lookup and a little arithmetic per owned character — is built only when
     * the book has actually been replaced. That is why the map is compared by <b>reference</b>:
     * {@link Roster} freezes a fresh immutable copy on every write, so a new instance is the signal,
     * and an equal-but-copied book costs exactly one redundant packet on the tick it appears.
     *
     * <h2>Quarantine</h2>
     * A quarantined book is never read here ({@code entries()} throws), and the client is told the one
     * thing that can be said truthfully: the save exists and this build cannot read it. That is what the
     * screen turns into 存档读取失败, and it is why "empty book" and "unreadable book" are distinguishable
     * at all (spec §6.3.1).
     */
    private static void pushRoster(ServerPlayer player) {
        Roster roster = player.getData(Attachments.ROSTER);
        boolean quarantined = roster.isQuarantined();
        Map<ResourceLocation, CharacterProgress> entries = quarantined ? null : roster.entries();
        SentRoster last = LAST_ROSTER.get(player.getUUID());
        if (last != null && last.quarantined() == quarantined && last.entries() == entries) {
            return;
        }
        List<RosterSyncPayload> payloads = buildRosterPayloads(roster);
        for (RosterSyncPayload payload : payloads) {
            PacketDistributor.sendToPlayer(player, payload);
        }
        LAST_ROSTER.put(player.getUUID(), new SentRoster(entries, quarantined));
        Astrum.LOGGER.debug("astrum: character book synced to {} ({} characters, {} packet(s){})",
                player.getGameProfile().getName(), roster.isQuarantined() ? 0 : entries.size(),
                payloads.size(), quarantined ? ", quarantined save" : "");
    }

    /**
     * Snapshot of a readable book as the screen needs it, or the quarantine marker.
     *
     * <p>The two numbers the client cannot compute — {@code expToNext} and {@code tier} — are resolved
     * here through {@link GrowthFacts}, the single implementation of the curve and the tier ladder, so
     * the client never holds a second copy of either rule.
     */
    private static List<RosterSyncPayload> buildRosterPayloads(Roster roster) {
        if (roster.isQuarantined()) {
            return List.of(RosterSyncPayload.QUARANTINED);
        }
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        List<RosterSyncPayload.Entry> entries = new ArrayList<>(roster.entries().size());
        for (Map.Entry<ResourceLocation, CharacterProgress> owned : roster.entries().entrySet()) {
            ResourceLocation id = owned.getKey();
            CharacterProgress progress = owned.getValue();
            // A character the running pack no longer defines is still owned and still shown; it just has
            // no curve to draw, so its bar is empty rather than invented.
            CharacterDef def = registry.map(loaded -> loaded.get(id)).orElse(null);
            int expToNext = def == null ? 0 : GrowthFacts.expToNext(progress.level(), def.growth());
            int tier = def == null
                    ? GrowthFacts.FIRST_TIER
                    : GrowthFacts.tier(progress.affection(), def.affection());
            entries.add(new RosterSyncPayload.Entry(id, progress.level(), progress.exp(), expToNext,
                    progress.affection(), tier));
        }
        return split(entries);
    }

    /**
     * {@return {@code entries} cut into packets that each fit the receiver's ceiling, in order}
     *
     * <h2>The defect this closes</h2>
     * The server used to put <b>every owned row</b> into one frame while the client refuses more than
     * {@link RosterSyncPayload#MAX_ENTRIES}. The two limits were only ever consistent by luck: a large character
     * pack, or a save that kept rows for characters the current pack no longer defines — those are deliberately
     * <em>kept</em> and still shown — could cross 256, and then the client threw on decode and <b>reconnecting did
     * not help</b>, because the next sync sent the same oversized frame.
     *
     * <p>Batching keeps the whole book instead of the alternatives this build rejected: trimming would delete a
     * player's progress from her screen to fit a buffer, and raising the cap would move the same cliff without a
     * bound. Each part carries at most {@code MAX_ENTRIES} rows, and the receiver reassembles them in order.
     */
    private static List<RosterSyncPayload> split(List<RosterSyncPayload.Entry> entries) {
        if (entries.isEmpty()) {
            return List.of(RosterSyncPayload.EMPTY);
        }
        int parts = (entries.size() + RosterSyncPayload.MAX_ENTRIES - 1) / RosterSyncPayload.MAX_ENTRIES;
        List<RosterSyncPayload> payloads = new ArrayList<>(parts);
        for (int part = 0; part < parts; part++) {
            int from = part * RosterSyncPayload.MAX_ENTRIES;
            int to = Math.min(entries.size(), from + RosterSyncPayload.MAX_ENTRIES);
            payloads.add(new RosterSyncPayload(entries.subList(from, to), false, part, parts));
        }
        return payloads;
    }

    /**
     * What one client was last told.
     *
     * @param tick      the player's tick counter at the moment of the push
     * @param charge    the charge that was pushed
     * @param deadlines the per-character cooldowns that were pushed, or {@code null} for "never
     *                  pushed" — see {@link SyncGate#shouldSync} for why null is the sentinel rather
     *                  than an empty map
     */
    private record Sent(int tick, float charge,
            @Nullable Map<ResourceLocation, UbCooldown> deadlines) {
        /** The "never pushed" value: tick {@code -1}, a {@code NaN} charge and no map, so the first window always sends. */
        static final Sent NEVER = new Sent(-1, Float.NaN, null);
    }

    /**
     * What one client was last told about its party.
     *
     * @param party        the slot contents that were pushed
     * @param enhancedMask the tier-3 bits that were pushed with them
     */
    private record SentParty(Party party, int enhancedMask) {
    }

    /**
     * What one client was last told about its character book.
     *
     * <p>The entry map is held <b>by reference</b>, not copied: {@link Roster} replaces it wholesale and
     * never mutates it, so the reference identifies the exact book that was sent. A quarantined book has
     * no readable map, hence the separate flag rather than an empty map — the two states must not
     * compare equal.
     *
     * @param entries     the entry map that was pushed, or {@code null} for a quarantined book
     * @param quarantined whether the pushed payload was the quarantine marker
     */
    private record SentRoster(@Nullable Map<ResourceLocation, CharacterProgress> entries,
                              boolean quarantined) {
    }
}

