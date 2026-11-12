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
 */
@ApiStatus.Internal
public final class UbService {

    /**
     * The installed showcase strategy. Defaults to damage-only so a cast always settles something.
     *
     * <p>A mutable static, which this project otherwise forbids — deliberately, and narrowly: it is one
     * process-wide strategy rather than per-entity state, and it is written once at setup by
     * {@link #installShowcase}. Routing every call site through an interface here buys nothing for a
     * single implementation.
     */
    private static volatile ShowcaseSink showcase = ShowcaseSink.DAMAGE_ONLY;

    private UbService() {
    }

    /**
     * Installs the real showcase implementation. Task 13 calls this once at setup.
     *
     * @param sink the implementation; {@code null} restores {@link ShowcaseSink#DAMAGE_ONLY}
     */
    @ApiStatus.Internal
    public static void installShowcase(ShowcaseSink sink) {
        showcase = sink == null ? ShowcaseSink.DAMAGE_ONLY : sink;
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

        Optional<CastGate.Reason> refusal = CastGate.refusal(facts);
        if (refusal.isPresent()) {
            Astrum.LOGGER.debug("astrum: UB cast refused for {} slot {} ({})",
                    player.getGameProfile().getName(), slot, refusal.get());
            return refusal;
        }

        boolean enhanced = isEnhanced(player, characterId, def);
        UbTier tier = enhanced ? def.ub().enhanced() : def.ub().base();

        // Pay first, unconditionally: a full bar, no partial spend (spec §5.4).
        player.setData(Attachments.UB_STATE,
                new UbState(0.0f, slot, now + ServerConfig.UB.ubGlobalCooldownTicks.get()));

        NeoForge.EVENT_BUS.post(new UbActivatedEvent(characterId, slot, enhanced));
        Astrum.LOGGER.debug("astrum: UB {} fired by {} from slot {}{}",
                characterId, player.getGameProfile().getName(), slot, enhanced ? " (enhanced)" : "");

        // Damage is settled now, not when an animation ends (spec §5.6). Task 13 supplies the entity;
        // the seam's default settles damage only, so the pipeline is playable before the visual half.
        settle(player, def, tier, enhanced);
        return Optional.empty();
    }

    private static void settle(ServerPlayer player, CharacterDef def, UbTier tier, boolean enhanced) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        try {
            showcase.settle(level, player, def, tier, enhanced);
        } catch (RuntimeException e) {
            // A failing showcase must not take down a cast whose gauge is already spent.
            Astrum.LOGGER.error("astrum: showcase failed for {} — damage may not have been settled",
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
     * <p>Silent by design: {@code AstrumNetwork} asks once per player per tick, so the quarantine
     * warning belongs to the cast path ({@link #isEnhanced}), not here.
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

    private static void warnQuarantinedOnce(ServerPlayer player) {
        Astrum.LOGGER.warn("astrum: character book of {} is quarantined (an unreadable save) — "
                + "ultimate tier falls back to base until it can be read", player.getGameProfile().getName());
    }
}
