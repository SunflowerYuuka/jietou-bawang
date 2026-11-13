package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The name a party effect shows: the character's <b>skill name</b>, not her display name.
 *
 * <h2>One rule, two call sites</h2>
 * The inventory's effect list ({@code PartyEffectClient.renderInventoryText}) and the hover tooltip
 * ({@code GatherEffectScreenTooltipsEvent}) must agree, or the same entry would be called two things
 * in the same window. Both call this method, and it is plain enough to unit-test without a client:
 * {@link PartyBuffNameTest} pins all three outcomes.
 *
 * <h2>The three outcomes</h2>
 * <ul>
 *   <li>a known character with a {@code skillName} → that skill name;</li>
 *   <li>a known character without one (an older pack) → {@link CharacterDef#effectiveSkillName()}'s
 *       fallback, her display name — never an empty label;</li>
 *   <li>no character known yet (no party sync, or the pack no longer defines her) → the effect's own
 *       translation key, so the entry still says something rather than showing nothing.</li>
 * </ul>
 *
 * <p>The avatar is deliberately not part of this class: it is the client renderer's concern, and
 * keeping the name rule free of textures is what makes it testable here.
 */
@ApiStatus.Internal
public final class PartyBuffName {

    private PartyBuffName() {
    }

    /**
     * {@return the component to show for {@code slot}'s effect}
     *
     * @param definition the character currently in that slot, or {@code null} when the client does not
     *                   know it
     * @param slot       0-based party slot, used only for the fallback key
     */
    public static Component nameFor(@Nullable CharacterDef definition, int slot) {
        if (definition == null) {
            return Component.translatable(PartyEffectSlots.nameKey(slot));
        }
        return Component.literal(definition.effectiveSkillName());
    }
}