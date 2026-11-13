package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.UbTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a UB cast does once it has been accepted and paid for.
 *
 * <h2>Why this seam exists</h2>
 * The演出 (showcase) entity is Task 13. Everything in this task — validation, spending the gauge,
 * arming the cooldown, posting the event — is provable without a single entity in the world, so the
 * entity is kept behind this one interface. Task 13 supplies the real implementation
 * ({@code ShowcaseManager}), and {@code UbService.installShowcase} installs it before any player can
 * cast, so a shipped build always settles damage through this seam.
 *
 * <h2>The contract a replacement must obey</h2>
 * <ol>
 *   <li><b>Idempotent per cast.</b> One accepted cast calls this exactly once, and the sink settles
 *       the damage <b>exactly once</b> even though three moments may try: the strike frame, the
 *       entity's removal if the clip is cut short, and the inline call when the entity budget forbids
 *       a showcase. One {@code SettleOnce} per cast makes those add up to one.</li>
 *   <li><b>Damage lands with the animation's strike, and never later than the entity's removal.</b>
 *       Spec §5.6's rule is 伤害在服务端一次性结算，不等动画播完 — its two concerns are that the damage
 *       must not look <em>delayed</em> and must not be <em>lost</em> if the animation is interrupted.
 *       Both are honoured by settling at the strike frame (half way into the 1.4 s clip, where both
 *       shipped clips put the arm's swing) with the removal as a guaranteed fallback: the damage is
 *       never "after the animation", and an interrupted clip still settles. Settling at tick 0 instead
 *       would put the numbers before the figure has moved.</li>
 *   <li><b>Server authority.</b> Only the logical server may call it; no client state is read.</li>
 *   <li><b>Spawn nothing beyond the budget — settle the damage anyway.</b> A cast may be reduced to
 *       "damage only" when {@code maxActiveShowcaseEntities} is reached — the spec allows dropping the
 *       <i>entity</i>, never the <i>effect</i> (功能不断，画面不炸).</li>
 *   <li><b>Must not throw.</b> A failing showcase must not take down the cast path, which has already
 *       spent the player's gauge by the time it runs.</li>
 * </ol>
 */
@ApiStatus.Internal
public interface ShowcaseSink {

    /**
     * Settles one accepted ultimate.
     *
     * @param level       the level the caster is in
     * @param caster      the player whose ultimate fired
     * @param characterId the character that fired, i.e. the registry key the cast resolved — the
     *                    attribution for the kill ledger, which has no other way to name a character
     *                    ({@code CharacterDef} carries no id of its own)
     * @param def         the caster's character definition
     * @param tier        the tier that fired — {@code ub.base()} or {@code ub.enhanced()}
     * @param enhanced    whether {@code tier} is the enhanced form
     */
    void settle(ServerLevel level, ServerPlayer caster, ResourceLocation characterId,
            CharacterDef def, UbTier tier, boolean enhanced);

    /**
     * The placeholder for Task 13: it settles <b>nothing</b> — no damage, no entity — and is named so
     * that no reader can mistake it for a working default.
     *
     * <p>It exists so the cast pipeline is complete and testable without an entity: gauge spent,
     * cooldown armed, event posted, seam called exactly once. It is never installed by a shipped build
     * ({@code ShowcaseManager.install} replaces it before any player can cast), so an accepted cast
     * always reaches a sink that settles damage — the placeholder is what a test or a future caller
     * gets if they deliberately install nothing.
     */
    ShowcaseSink NO_SHOWCASE_YET = (level, caster, characterId, def, tier, enhanced) -> {
        // Kept as a single place so a test can assert the cast pipeline without a world; the real
        // sink owns both the visuals and the one-shot settlement.
    };
}
