package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.AstrumApi;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.impl.attach.Party;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * <b>Which character's face goes in the UB gauge's left circle</b> — the one place that answers it.
 *
 * <h2>What the human saw, and what he asked for</h2>
 * 「UB条最左边的圆圈，显示的应该是当前预设的角色的头像」. The circle at the left end of the charge bar was
 * an empty ring: it showed the cooldown and nothing else, so the gauge did not say <em>whose</em>
 * ultimate the bar was for. That matters because the bar is shared and the preset changes which
 * character a tap cast fires.
 *
 * <h2>The two existing answers this joins, rather than a third</h2>
 * <ol>
 *   <li><b>"Which slot is the preset"</b> is {@link WheelPreset#slot()} — the wheel's own in-flight
 *       selection (spec §5.5: 鼠标移入某格切换预设). It is not re-derived here, and it is deliberately
 *       the <em>client's</em> value rather than {@code UB_STATE.selectedSlot}: the latter is only
 *       written when a cast is committed, while the circle has to follow the wheel as the player sweeps
 *       it.</li>
 *   <li><b>"Who is in that slot"</b> is {@link ClientPartyCache#slot(int)} — the party push the wheel
 *       and the book already read.</li>
 * </ol>
 * <p>Both are single existing sources, so this class owns no rule except the join and the "nothing to
 * draw" cases.
 *
 * <h2>When it draws nothing, and why that is the rule</h2>
 * An empty preset slot, a party the client has not received yet, a character the loaded pack does not
 * define, and a character with no face art all answer {@link Optional#empty()}. Drawing <em>some</em>
 * face would be worse than drawing none: the circle exists to name the character about to cast, and a
 * wrong face is a wrong answer to "who will this fire".
 *
 * <h2>Why the resolution is split from the lookup</h2>
 * {@link #characterOf} is a pure function of the preset slot and the party list, so "an empty preset
 * draws nothing" is unit-testable ({@code PresetFaceTest}) with no client, no registry and no
 * {@code AstrumApi}. {@link #face()} is the thin game-side half that reads the two caches and asks the
 * loaded pack for the definition.
 */
@ApiStatus.Internal
public final class PresetFace {

    /** Side of the face drawn inside the gauge's circle, in GUI pixels. */
    public static final int FACE_INSET = 2;

    private PresetFace() {
    }

    /**
     * {@return the character the preset points at, or empty when there is nobody to name}
     *
     * <p>Never throws: a {@code null} list, a short list, a negative or out-of-range slot and a
     * {@code null} entry all answer {@link Optional#empty()} rather than an exception — this runs on
     * the HUD's own render path, where an exception costs the player their screen.
     *
     * @param presetSlot the wheel's current preset, from {@link WheelPreset#slot()}
     * @param slots      the party, in slot order, from {@link ClientPartyCache#latest()}
     */
    public static Optional<ResourceLocation> characterOf(int presetSlot,
            List<Optional<ResourceLocation>> slots) {
        if (slots == null || presetSlot < 0 || presetSlot >= Party.SLOTS || presetSlot >= slots.size()) {
            return Optional.empty();
        }
        Optional<ResourceLocation> entry = slots.get(presetSlot);
        return entry == null ? Optional.empty() : entry;
    }

    /**
     * {@return the preset character's definition, empty when there is no face to draw}
     *
     * <p>The game-side half: reads the preset and the party from their caches, then the definition from
     * the loaded pack through the same public facade the book uses
     * ({@link AstrumApi#characters()}), and finally the face through {@link TierAvatar#forPartySlot} — the
     * same call {@code CharacterBookScreen}'s party slots and {@code PartyEffectClient} make, so the circle,
     * the roster and the effect row cannot show three different pictures.
     *
     * <p><b>The circle follows the affection tier too</b> (「角色头像要跟随好感度进行变化」): a slot's character
     * wears her 1★ / 3★ / 6★ avatar here exactly as she does in the book. It used to read
     * {@link CharacterDef#face()} directly, which is the avatar field and would have frozen her at whatever
     * single picture the pack named.
     *
     * <p>Empty in a world with no registry (a dedicated server, a disconnect) and for a character
     * whose {@code avatar} and {@code portrait} are both absent.
     */
    public static Optional<CharacterDef> definition() {
        Optional<ResourceLocation> character =
                characterOf(WheelPreset.slot(), ClientPartyCache.latest().slots());
        if (character.isEmpty()) {
            return Optional.empty();
        }
        // No face filter here any more: the picture is chosen per tier by CharacterDef.face(int), which can
        // answer from icon1st/icon3rd/icon6th even when `avatar` is absent, so filtering on face() at this
        // level would hide a character that the tier rule can in fact draw.
        return AstrumApi.characters().map(registry -> registry.get(character.get()))
                .filter(definition -> definition != null);
    }

    /**
     * {@return the texture to blit in the gauge's circle, or empty when it must stay empty}
     *
     * <p>Called once per frame by {@link UbHud}, so it does no logging of its own; the HUD logs the id
     * when it changes, which is the only moment a probe cares about.
     */
    public static Optional<ResourceLocation> face() {
        Optional<CharacterDef> definition = definition();
        if (definition.isEmpty()) {
            return Optional.empty();
        }
        Optional<ResourceLocation> character =
                characterOf(WheelPreset.slot(), ClientPartyCache.latest().slots());
        if (character.isEmpty()) {
            return Optional.empty();
        }
        // The tier join, not a raw field read: the circle follows the affection tier exactly as the book does.
        return TierAvatar.forPartySlot(definition.get(), character.get());
    }
}
