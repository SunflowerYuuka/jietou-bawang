package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.AffectionDef;
import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.GrowthDef;
import com.astrum.pcrd.api.v1.UbDef;
import com.astrum.pcrd.api.v1.UbTier;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The buff's display name: skill name first, display name when the pack has no skill name, the
 * effect's own key when nothing is known.
 *
 * <h2>The distinction this pins</h2>
 * "The name is her skill name" is easy to implement as "the name is the character's displayName" and
 * nobody would notice without the character in front of them. The first case below is the one that
 * makes the difference visible in text: a character named 姬塔 whose skill is 广域斩击 must show
 * 广域斩击, not 姬塔.
 */
class PartyBuffNameTest {

    private static final String DISPLAY_NAME = "姬塔";
    private static final String SKILL_NAME = "广域斩击";

    @Test
    void aCharacterWithASkillNameShowsTheSkillNameNotHerName() {
        Component name = PartyBuffName.nameFor(definition(Optional.of(SKILL_NAME)), 0);
        assertEquals(SKILL_NAME, name.getString());
        assertNotEquals(DISPLAY_NAME, name.getString(),
                "the buff name is the skill, not the character name");
    }

    @Test
    void aPackWithoutASkillNameFallsBackToTheDisplayName() {
        assertEquals(DISPLAY_NAME,
                PartyBuffName.nameFor(definition(Optional.empty()), 0).getString());
    }

    @Test
    void aBlankSkillNameIsTreatedAsAbsent() {
        assertEquals(DISPLAY_NAME,
                PartyBuffName.nameFor(definition(Optional.of("   ")), 0).getString(),
                "an empty label is worse than the character's name");
    }

    @Test
    void anUnknownCharacterShowsTheEffectsOwnKey() {
        // No language manager is installed in a unit test, so a translatable component's string is its
        // key: this asserts which key, without needing a client.
        assertEquals("effect.astrum.party_3", PartyBuffName.nameFor(null, 2).getString());
    }

    /**
     * {@return a character definition whose only interesting field is the skill name}
     *
     * <p>Built by hand because {@code CharacterDef} has nine required components; the ones this test
     * does not exercise are the smallest values that satisfy the codec's types.
     */
    private static CharacterDef definition(Optional<String> skillName) {
        UbTier tier = new UbTier("tempest_blade", 1, 20.0, OptionalDouble.empty(), OptionalDouble.empty(),
                0, OptionalDouble.empty(), Optional.empty());
        return new CharacterDef(1, DISPLAY_NAME, Dimension.CHARGE, Optional.empty(), Optional.empty(),
                List.of(), new UbDef(tier, tier), new GrowthDef(100, 1.15, 30), new AffectionDef(3, 8, List.of()),
                skillName, Optional.empty());
    }
}