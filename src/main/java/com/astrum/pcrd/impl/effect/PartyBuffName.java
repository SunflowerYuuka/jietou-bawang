package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The name a party effect shows: <b>the character's name</b>.
 *
 * <h2>Why the character's name and not her skill's</h2>
 * 「药水效果栏改成用角色名字，不用技能名字。」 Two reasons, and the second is the one that forced the
 * change: the effect entry answers <em>who is in this slot</em>, which is what five simultaneous entries
 * are for — and once a character's swings rotate through several small skills (see
 * {@code HookPlan}'s rotation), a single skill name is actively misleading, because the entry would
 * name one of her skills while she is on a different one. The character's name is the only label that
 * stays true for the whole party.
 *
 * <p>{@link CharacterDef#skillName()} is therefore <b>not read here</b>. It remains part of the schema
 * and is still shown where a single skill is the right answer (the book's skills section), but it no
 * longer names the buff.
 *
 * <h2>One rule, two call sites</h2>
 * The inventory's effect list ({@code PartyEffectClient.renderInventoryText}) and the hover tooltip
 * ({@code GatherEffectScreenTooltipsEvent}) must agree, or the same entry would be called two things in
 * the same window. Both call this method, and it is plain enough to unit-test without a client:
 * {@link PartyBuffNameTest} pins every outcome.
 *
 * <h2>The two outcomes</h2>
 * <ul>
 *   <li>a known character → {@link CharacterDef#displayName()};</li>
 *   <li>no character known yet (no party sync, or the pack no longer defines her) → the effect's own
 *       translation key, so the entry still says something rather than showing nothing.</li>
 * </ul>
 *
 * <p>The avatar is deliberately not part of this class: it is the client renderer's concern, and keeping
 * the name rule free of textures is what makes it testable here.
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
        return Component.literal(definition.displayName());
    }
}
