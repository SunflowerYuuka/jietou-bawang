package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.attach.UbCooldown;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Which</b> cooldown the gauge's circle is showing, and how much of it is left.
 *
 * <h2>What changed, and why this class had to</h2>
 * It used to track one shared cooldown, and it had to <em>observe</em> its total: the packet carried an
 * absolute end tick and nothing else, so the denominator was a high-water mark of the remaining ticks
 * this client had happened to see. 「每个角色UB的CD不共享」 made that model wrong twice over — there is no
 * single cooldown any more, and the payload now carries each cooldown's own length
 * ({@link UbCooldown#totalTicks}), so there is nothing left to observe. Everything the old span
 * arithmetic did is now {@link UbCooldown#remainingFraction}, which is also what the server-side gate
 * compares against, and this class does not repeat it: it answers <em>whose</em> cooldown, and delegates
 * the <em>how much</em>.
 *
 * <h2>The three inputs, and why the join is here</h2>
 * "Which character is the circle showing" is the preset slot's character — the same answer
 * {@link PresetFace} gives, because drawing her face and reading her cooldown must not disagree about
 * who "she" is. So this class composes three existing single sources and owns no rule of its own:
 *
 * <ul>
 *   <li>the preset slot — {@link WheelPreset#slot()}, the wheel's in-flight selection;</li>
 *   <li>that slot's character — {@link PresetFace#characterOf}, the same join the face uses;</li>
 *   <li>her deadline — {@link ClientUbCache#cooldownOf}, the server's own report.</li>
 * </ul>
 *
 * <h2>What it is not allowed to do</h2>
 * It never feeds a decision: {@code CastGate} on the server is the only thing that decides whether a
 * cast may happen (spec §5.5). A shade drawn slightly long after a client joins mid-cooldown is a
 * cosmetic approximation, not a rule. {@link #cooldownShown} is a pure function of plain values, so the
 * join is tested without a client, a registry or a {@code Minecraft}.
 */
@ApiStatus.Internal
public final class CooldownTracker {

    private CooldownTracker() {
    }

    /**
     * {@return the cooldown the circle shows for a given preset, party and payload}
     *
     * <p>Empty when the preset names nobody: an empty slot, a party the client has not received, or an
     * out-of-range preset all mean there is no character whose cooldown could be drawn, and drawing
     * <em>somebody else's</em> would be a wrong answer to "who will this fire".
     *
     * @param presetSlot the wheel's current preset, from {@link WheelPreset#slot()}
     * @param slots      the party, in slot order, from {@link ClientPartyCache#latest()}
     * @param deadlines  the server's per-character cooldowns, from {@link ClientUbCache#latest()}
     */
    public static Optional<UbCooldown> cooldownShown(int presetSlot,
            List<Optional<ResourceLocation>> slots, Map<ResourceLocation, UbCooldown> deadlines) {
        Optional<ResourceLocation> character = PresetFace.characterOf(presetSlot, slots);
        if (character.isEmpty() || deadlines == null) {
            return Optional.empty();
        }
        UbCooldown cooldown = deadlines.get(character.get());
        return cooldown == null ? Optional.empty() : Optional.of(cooldown);
    }

    /**
     * {@return how much of the shown cooldown is left, {@code 1.0} at its start and {@code 0.0} when
     *         there is nothing to draw}
     *
     * <p>The single quantity the ring sweeps with and the face shade is computed from — one call, so
     * the two drawings cannot disagree about the remaining time.
     *
     * @param shown   the cooldown from {@link #cooldownShown}, empty when the circle names nobody
     * @param nowTick the client's game time
     */
    public static double fraction(Optional<UbCooldown> shown, long nowTick) {
        return shown.map(cooldown -> cooldown.remainingFraction(nowTick)).orElse(0.0);
    }

    /**
     * {@return the cooldown the circle is showing right now, from the client's own caches}
     *
     * <p>The game-side half; the tests use {@link #cooldownShown} directly.
     */
    public static Optional<UbCooldown> shown() {
        return cooldownShown(WheelPreset.slot(), ClientPartyCache.latest().slots(),
                ClientUbCache.latest().deadlines());
    }

    /** {@return how much of the circle's cooldown is left right now} */
    public static double shownFraction(long nowTick) {
        return fraction(shown(), nowTick);
    }

    /** {@return the character whose cooldown the circle is showing, if any} */
    public static Optional<ResourceLocation> shownCharacter() {
        return PresetFace.characterOf(WheelPreset.slot(), ClientPartyCache.latest().slots());
    }
}
