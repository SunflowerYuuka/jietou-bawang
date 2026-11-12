package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.ub.CastGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.ApiStatus;

/**
 * The audible half of a refusal — played from the client tick, so it cannot be switched off by a
 * display setting.
 *
 * <h2>Why this is not part of {@link UbHud}</h2>
 * Spec §7 lets a player turn the optional UB HUD off ({@code [hud] enableUbHud}) or hide the whole
 * overlay with {@code F1}, and spec §5.4 forbids a silent failure <b>without</b> conditions
 * (禁止静默失败). When the sound lived inside {@link UbHud#render}, both of those switches turned every
 * refusal — not charged, still cooling, empty slot — into exactly the silent failure the spec forbids:
 * the server had answered, the player pressed a legal key, and nothing happened. So the sound is
 * produced here, from {@link ClientEvents}' tick, which runs whether or not any overlay is drawn; the
 * HUD keeps only the shake and the flash, which are things it cannot draw while hidden anyway.
 *
 * <h2>Once per refusal</h2>
 * A refusal is identified by the game time it arrived, so a new refusal is always a new sound and one
 * refusal never repeats on later ticks. No counter and no timer: the cache is the state
 * ({@link ClientCastFeedback}).
 */
@ApiStatus.Internal
@OnlyIn(Dist.CLIENT)
public final class RefusalSound {

    /**
     * Game time of the refusal whose sound has already been played.
     *
     * <p>The edge detector: {@link Long#MIN_VALUE} is "nothing played yet", and every refusal carries
     * its own arrival time.
     */
    private static volatile long soundedRefusalTick = Long.MIN_VALUE;

    private RefusalSound() {
    }

    /**
     * Plays the refusal sound once per refusal, for a refusal still inside its window.
     *
     * <p>Called from the client tick, before any overlay is drawn, so the sound is independent of
     * {@code enableUbHud} and of {@code F1}.
     *
     * @param now the client's current game time
     */
    public static void tick(long now) {
        ClientCastFeedback.active(now, RefusalFeedback.WINDOW_TICKS).ifPresent(refusal -> {
            if (soundedRefusalTick == refusal.gameTime()) {
                return;
            }
            soundedRefusalTick = refusal.gameTime();
            play(refusal.reason());
        });
    }

    /**
     * Plays one refusal note.
     *
     * <p>The pitch encodes the reason ({@link RefusalFeedback#pitchFor}), so the two refusals a player
     * actually meets — not charged, and still on cooldown — are distinguishable by ear.
     */
    private static void play(CastGate.Reason reason) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, RefusalFeedback.pitchFor(reason)));
    }
}
