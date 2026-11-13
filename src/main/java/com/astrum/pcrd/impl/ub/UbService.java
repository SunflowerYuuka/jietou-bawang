package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.astrum.pcrd.api.v1.UbTier;
import com.astrum.pcrd.api.v1.event.UbActivatedEvent;
import com.astrum.pcrd.impl.attach.Attachments;
import com.astrum.pcrd.impl.attach.Party;
import com.astrum.pcrd.impl.attach.Roster;
import com.astrum.pcrd.impl.attach.UbState;
import com.astrum.pcrd.impl.combat.WarnOnce;
import com.astrum.pcrd.impl.config.ServerConfig;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.ApiStatus;

/**
 * The server side of one ultimate: decide, charge, arm the cooldown, announce, hand off.
 *
 * <h2>Everything is resolved here, nothing is trusted from the client</h2>
 * The client sends a slot number and nothing else ({@code UbCastPayload}). This class reads the party,
 * the character registry and the player's own gauge and cooldown, then asks {@link CastGate} about the
 * lot. That is spec §9.5 (服务端权威，客户端只读) made concrete: a client that names a slot it does not
 * own, or casts with an empty bar, is answered with a typed refusal rather than obeyed.
 *
 * <h2>Why the gauge is written directly instead of through {@code UbCharge.apply}</h2>
 * {@code UbCharge} posts a cancellable {@code UbChargeEvent} before writing, which is right for
 * <em>earning</em> charge — a listener may want to scale or veto a gain. Spending is not that: by the
 * time this method accepts a cast the ultimate is committed, so a listener must not be able to cancel
 * the payment and leave the player with a free cast. The spend is therefore unconditional, and the
 * cooldown and the event follow it in the same call.
 *
 * <h2>Where the transaction itself lives</h2>
 * The rule "an accepted cast empties the bar, arms the cooldown at {@code now + cooldown}, writes that
 * state, then announces, then settles" is {@link UbTransaction}, so it is a unit-tested value rather
 * than a sequence of statements in a method that needs a player to run. This class only gathers the
 * facts, asks, and plugs the real player, bus and sink into it.
 */
@ApiStatus.Internal
public final class UbService {

    /**
     * The installed cast strategy: what a paid-for ultimate does to the world.
     *
     * <p>Today that is {@link EffectsOnlySink} — no entity, damage settled on the cast, the client told to
     * draw the effect — installed by {@code ShowcaseTypes.register}. It is the seam that made this swap a
     * one-line change: the entity sink ({@code ShowcaseManager}) is a second implementation of the same
     * interface and is still in the tree, unused.
     *
     * <p>The placeholder {@link ShowcaseSink#NO_SHOWCASE_YET} settles <b>nothing</b> at all, and is what a
     * test or a deliberately sink-less caller gets; no shipped path leaves it installed, because
     * {@code ShowcaseTypes.register} runs from the mod constructor.
     *
     * <p>A mutable static, which this project otherwise forbids — deliberately, and narrowly: it is one
     * process-wide strategy rather than per-entity state, and it is written once at setup by
     * {@link #installShowcase}. Routing every call site through an interface here buys nothing for a
     * single implementation.
     */
    private static volatile ShowcaseSink showcase = ShowcaseSink.NO_SHOWCASE_YET;

    /**
     * Players already told that their character book is quarantined, so the warning is one line per
     * player instead of one line per cast attempt (the name of {@link #warnQuarantinedOnce} is the
     * contract). Bounded by the players who actually have an unreadable save.
     */
    private static final WarnOnce QUARANTINE_WARNED = new WarnOnce();

    private UbService() {
    }

    /**
     * Installs the cast strategy. {@code ShowcaseTypes.register} calls this once at setup.
     *
     * @param sink the implementation; {@code null} restores {@link ShowcaseSink#NO_SHOWCASE_YET}
     */
    @ApiStatus.Internal
    public static void installShowcase(ShowcaseSink sink) {
        showcase = sink == null ? ShowcaseSink.NO_SHOWCASE_YET : sink;
    }

    /** {@return the installed cast strategy, for tests and for a probe — never {@code null}} */
    public static ShowcaseSink installedShowcase() {
        return showcase;
    }

    /**
     * Attempts to fire the ultimate in {@code slot}.
     *
     * @param player the caster; must be server-side
     * @param slot   the requested wheel slot
     * @return empty on success, or the typed reason the cast was refused (spec §5.4: the client needs
     *         a reason to shake the slot and play a sound for — 禁止静默失败)
     */
    public static Optional<CastGate.Reason> tryCast(ServerPlayer player, int slot) {
        Party party = player.getData(Attachments.PARTY);
        UbState state = player.getData(Attachments.UB_STATE);
        long now = player.level().getGameTime();

        ResourceLocation characterId = slot >= 0 && slot < Party.SLOTS
                ? party.slots().get(slot).orElse(null)
                : null;
        Optional<Registry<CharacterDef>> registry = AstrumApi.characters();
        CharacterDef def = characterId == null || registry.isEmpty()
                ? null
                : registry.get().get(characterId);

        CastGate.Facts facts = new CastGate.Facts(
                slot,
                characterId != null,
                def != null,
                state.charge(),
                now,
                state.cdEndTick());

        // The decision and the state it pays: a refusal means nothing is written at all.
        UbTransaction.Outcome outcome =
                UbTransaction.decide(facts, ServerConfig.UB.ubGlobalCooldownTicks.get());
        if (outcome.refusal().isPresent()) {
            CastGate.Reason reason = outcome.refusal().get();
            Astrum.LOGGER.debug("astrum: UB cast refused for {} slot {} ({})",
                    player.getGameProfile().getName(), slot, reason);
            return Optional.of(reason);
        }
        UbState paid = outcome.paid().orElseThrow(() -> new IllegalStateException(
                "astrum: UbTransaction decided to accept without a state to write"));

        boolean enhanced = isEnhanced(player, characterId, def);
        UbTier tier = enhanced ? def.ub().enhanced() : def.ub().base();

        // Write, announce, settle — in that order, once each, and all of it inside UbTransaction so
        // the order is asserted by a test rather than by this comment.
        UbTransaction.commit(paid,
                next -> player.setData(Attachments.UB_STATE, next),
                () -> announce(new UbActivatedEvent(characterId, slot, enhanced)),
                () -> settle(player, characterId, def, tier, enhanced));
        Astrum.LOGGER.debug("astrum: UB {} fired by {} from slot {}{}",
                characterId, player.getGameProfile().getName(), slot, enhanced ? " (enhanced)" : "");

        // Hand-off to the sink is the third effect inside commit(): the effects-only sink settles the
        // tier's damage once (there is no entity and no tick source any more) and tells the caster's
        // client to draw the effect. See EffectsOnlySink for why the damage lands on the cast itself.
        return Optional.empty();
    }

    /**
     * Posts the activation event, swallowing a listener failure.
     *
     * <p>Wrapped for the same reason {@link #settle} is: by the time this runs the player has already
     * paid, so an exception thrown by a listener must not escape {@link #tryCast} — it would skip the
     * cast hand-off and turn a completed cast into a reported failure, with the gauge still gone.
     */
    private static void announce(UbActivatedEvent event) {
        try {
            NeoForge.EVENT_BUS.post(event);
        } catch (RuntimeException e) {
            Astrum.LOGGER.error("astrum: a UbActivatedEvent listener failed for slot {} — the cast has "
                    + "already been paid for and is not rolled back", event.slot(), e);
        }
    }

    private static void settle(ServerPlayer player, ResourceLocation characterId, CharacterDef def,
            UbTier tier, boolean enhanced) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        try {
            showcase.settle(level, player, characterId, def, tier, enhanced);
        } catch (RuntimeException e) {
            // A failing sink must not take down a cast whose gauge is already spent.
            Astrum.LOGGER.error("astrum: the cast sink failed for {} — damage may not have been settled",
                    def.displayName(), e);
        }
    }

    /**
     * {@return whether this character is at affection tier 3 for this player}
     *
     * <p>Split out of {@link #isEnhanced} so the party sync ({@code AstrumNetwork}) can put the
     * enhanced bit on the wheel without a second implementation of the rule — two answers to "is this
     * tier 3?" would eventually disagree, and the wheel would show the base icon for a character whose
     * ultimate is already the enhanced one.
     *
     * <p>Silent by design: {@code AstrumNetwork} asks once per player per (now one-second) tier-mask
     * check, so the quarantine warning belongs to the cast path ({@link #isEnhanced}), not here.
     *
     * @param player      the player whose affection is being read
     * @param characterId the character being asked about
     * @param def         that character's definition, already resolved by the caller
     */
    public static boolean atTier3(ServerPlayer player, ResourceLocation characterId, CharacterDef def) {
        int threshold = def.affection().tier3Threshold();
        if (threshold <= 0) {
            return false;
        }
        Roster roster = player.getData(Attachments.ROSTER);
        if (roster.isQuarantined()) {
            return false;
        }
        CharacterProgress progress = roster.progressOf(characterId);
        return progress != null && progress.affection() >= threshold;
    }

    /**
     * {@return whether the caster's affection has reached the tier-3 threshold}
     *
     * <p>Tier 3 swaps in the enhanced ultimate and its own animation (spec §6.2). Affection lives in
     * {@code ROSTER}, which is quarantine-aware: while it is unreadable every accessor throws, so a
     * quarantined book answers "tier 1" with a single warning instead of taking the cast path down.
     * That is a deliberate fail-closed choice — the player keeps a working ultimate, just the base one.
     */
    private static boolean isEnhanced(ServerPlayer player, ResourceLocation characterId,
            CharacterDef def) {
        if (def.affection().tier3Threshold() <= 0) {
            return false;
        }
        if (player.getData(Attachments.ROSTER).isQuarantined()) {
            warnQuarantinedOnce(player);
            return false;
        }
        return atTier3(player, characterId, def);
    }

    /** Says "this player's book is unreadable" once per player, not once per cast attempt. */
    private static void warnQuarantinedOnce(ServerPlayer player) {
        if (!QUARANTINE_WARNED.first(player.getUUID().toString())) {
            return;
        }
        Astrum.LOGGER.warn("astrum: character book of {} is quarantined (an unreadable save) — "
                + "ultimate tier falls back to base until it can be read", player.getGameProfile().getName());
    }
}
