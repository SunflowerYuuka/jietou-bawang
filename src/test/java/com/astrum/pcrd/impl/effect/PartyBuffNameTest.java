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
 * The buff's display name: <b>the character's name</b>, never her skill's.
 *
 * <h2>Why this test now asserts the opposite of what it used to</h2>
 * The effect entry used to show the character's {@code skillName}. The human changed the rule
 * (「药水效果栏改成用角色名字，不用技能名字」), and the reason that matters for correctness rather than
 * taste is the rotation: a character's swings cycle through several small skills, so one skill name on a
 * status entry is a label that is wrong most of the time. The first test below is what makes the change
 * visible in text — 姬塔 whose skill is 广域斩击 must show 姬塔.
 */
class PartyBuffNameTest {

    private static final String DISPLAY_NAME = "姬塔";
    private static final String SKILL_NAME = "广域斩击";

    @Test
    void aCharacterShowsHerNameNotHerSkillName() {
        Component name = PartyBuffName.nameFor(definition(Optional.of(SKILL_NAME)), 0);
        assertEquals(DISPLAY_NAME, name.getString());
        assertNotEquals(SKILL_NAME, name.getString(),
                "the buff names the character; a skill name would be wrong on most swings");
    }

    /** A pack without a skill name is not a special case any more — the name never came from there. */
    @Test
    void aPackWithoutASkillNameShowsTheSameName() {
        assertEquals(DISPLAY_NAME,
                PartyBuffName.nameFor(definition(Optional.empty()), 0).getString());
    }

    @Test
    void aBlankSkillNameChangesNothing() {
        assertEquals(DISPLAY_NAME,
                PartyBuffName.nameFor(definition(Optional.of("   ")), 0).getString(),
                "a blank skill name is irrelevant now that it is not the source of the label");
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
     * <p>Built by hand because {@code CharacterDef} has several required components; the ones this test
     * does not exercise are the smallest values that satisfy the codec's types.
     */
    private static CharacterDef definition(Optional<String> skillName) {
        UbTier tier = new UbTier("tempest_blade", 4, 20.0, OptionalDouble.empty(),
                Optional.empty(), OptionalDouble.empty(), Optional.empty());
        return new CharacterDef(1, DISPLAY_NAME, Dimension.CHARGE, Optional.empty(), Optional.empty(),
                List.of(), new UbDef(tier, tier), new GrowthDef(100, 1.15, 30),
                new AffectionDef(3, 8, List.of()), skillName, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());
    }
}
