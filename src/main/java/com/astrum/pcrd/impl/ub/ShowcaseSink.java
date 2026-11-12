package com.astrum.pcrd.impl.ub;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.UbTier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a UB cast does once it has been accepted and paid for.
 *
 * <h2>Why this seam exists</h2>
 * The演出 (showcase) entity is Task 13. Everything in this task — validation, spending the gauge,
 * arming the cooldown, posting the event — is provable without a single entity in the world, so the
 * entity is kept behind this one interface. Task 13 supplies the real implementation. Until it does,
 * the placeholder below ({@link #NO_SHOWCASE_YET}) <b>settles nothing at all</b> — no entity, no
 * damage — and the single truthful statement about it is in its own javadoc. It exists so the cast
 * pipeline is complete and testable in the meantime (the same shape Task 10 used for
 * {@code AreaChargeSink}).
 *
 * <h2>The contract a replacement must obey</h2>
 * <ol>
 *   <li><b>Idempotent per cast.</b> One accepted cast calls this exactly once. It must not be called
 *       again for the same cast when the animation finishes.</li>
 *   <li><b>Damage is settled here, once, immediately — not when an animation ends.</b> Spec §5.6: 伤害在
 *       服务端一次性结算，不等动画播完. Otherwise the damage looks delayed and is lost if the
 *       animation is interrupted.</li>
 *   <li><b>Server authority.</b> Only the logical server may call it; no client state is read.</li>
 *   <li><b>Spawn nothing beyond the budget.</b> A cast may be reduced to "damage only" when
 *       {@code maxActiveShowcaseEntities} is reached — the spec allows dropping the <i>entity</i>, never
 *       the <i>effect</i> (功能不断，画面不炸).</li>
 *   <li><b>Must not throw.</b> A failing showcase must not take down the cast path, which has already
 *       spent the player's gauge by the time it runs.</li>
 * </ol>
 */
@ApiStatus.Internal
public interface ShowcaseSink {

    /**
     * Settles one accepted ultimate.
     *
     * @param level    the level the caster is in
     * @param caster   the player whose ultimate fired
     * @param def      the caster's character definition
     * @param tier     the tier that fired — {@code ub.base()} or {@code ub.enhanced()}
     * @param enhanced whether {@code tier} is the enhanced form
     */
    void settle(ServerLevel level, ServerPlayer caster, CharacterDef def, UbTier tier, boolean enhanced);

    /**
     * The placeholder for Task 13: it settles <b>nothing</b> — no damage, no entity — and is named so
     * that no reader can mistake it for a working default.
     *
     * <p>It exists so the cast pipeline is complete and testable without an entity: gauge spent,
     * cooldown armed, event posted, seam called exactly once. The <b>damage half is still missing</b>,
     * because settling it needs a target, and choosing the target (the tier's {@code radius}, or a
     * single one when it is empty) is the 演出's own rule — inventing it here would mean writing that
     * rule twice and having Task 13 disagree with it.
     *
     * <p>Consequence, stated plainly so it is not mistaken for a bug or for a finished feature: until
     * Task 13 installs a real sink, an accepted cast empties the bar, arms the cooldown, announces
     * itself — and changes nothing in the world. That is acceptable only because no player-facing
     * build ships before Task 13; the first thing Task 13 must do is replace this constant through
     * {@code UbService.installShowcase}.
     */
    ShowcaseSink NO_SHOWCASE_YET = (level, caster, def, tier, enhanced) -> {
        // Task 13 replaces this with the演出 entity, which owns both the visuals and the one-shot
        // settlement. Kept as a single place so Task 13 does not have to find four scattered
        // settlements.
    };
}
