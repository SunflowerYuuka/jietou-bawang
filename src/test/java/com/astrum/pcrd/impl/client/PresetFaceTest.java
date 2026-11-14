package com.astrum.pcrd.impl.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Which character's face goes in the UB gauge's left circle</b> (task 26 A).
 *
 * <h2>The defect, in the human's words</h2>
 * 「UB条最左边的圆圈，显示的应该是当前预设的角色的头像」 — the circle was an empty ring, so the gauge never
 * said whose ultimate the bar was charging, even though the preset decides which character a tap cast
 * fires.
 *
 * <h2>What is asserted, and what deliberately is not</h2>
 * The <em>join</em> is asserted: a preset slot plus the party list answers the character in that slot,
 * and every "nothing to name" case answers empty rather than inventing a face. The drawing is not
 * asserted (it cannot be, without eyes) and neither is the cache plumbing — that is
 * {@link WheelPreset} and {@link ClientPartyCache}'s, and duplicating it here would be a second test of
 * somebody else's single copy.
 *
 * <p>The input is the plain {@code List<Optional<ResourceLocation>>} the party sync already carries, so
 * this runs with no client, no registry and no {@code AstrumApi}.
 */
class PresetFaceTest {

    private static final ResourceLocation DJEETA = texture("djeeta");
    private static final ResourceLocation OTHER = texture("other");

    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath("astrum", "textures/gui/portrait/" + path + ".png");
    }

    /** {@return a five-slot party with {@code filled} in the given slots} */
    private static List<Optional<ResourceLocation>> party(ResourceLocation... filled) {
        List<Optional<ResourceLocation>> slots = new ArrayList<>();
        for (ResourceLocation id : filled) {
            slots.add(Optional.ofNullable(id));
        }
        while (slots.size() < 5) {
            slots.add(Optional.empty());
        }
        return slots;
    }

    /**
     * The headline: the preset slot names the character, and the circle shows <b>that</b> character.
     *
     * <p>Breaks if: the slot index is ignored (every slot would answer the same face), the slot is read
     * off by one, or the party is re-sorted on the way in (order is meaning, spec §4.3).
     */
    @Test
    void thePresetSlotNamesItsOwnCharacter() {
        List<Optional<ResourceLocation>> slots = party(DJEETA, null, OTHER);

        assertEquals(Optional.of(DJEETA), PresetFace.characterOf(0, slots));
        assertEquals(Optional.of(OTHER), PresetFace.characterOf(2, slots),
                "slot 2 is the third entry, not the second — the index is the wheel position");
        assertEquals(Optional.empty(), PresetFace.characterOf(1, slots),
                "an empty slot in the middle is still empty, not 'the next one along'");
    }

    /**
     * Every "nothing to name" case answers empty.
     *
     * <p>The rule matters more than it looks: the circle exists to say <em>who will cast</em>, so a
     * wrong face is worse than an empty one. An empty preset slot, a party the client has not received
     * (a null or short list), and an out-of-range preset (a stale wheel selection after a party edit)
     * all have to resolve to "draw nothing".
     */
    @Test
    void nothingToNameAnswersEmptyRatherThanAFace() {
        assertEquals(Optional.empty(), PresetFace.characterOf(0, null), "no party synced yet");
        assertEquals(Optional.empty(), PresetFace.characterOf(0, List.of()),
                "an empty party list is not a party of one empty slot");
        assertEquals(Optional.empty(), PresetFace.characterOf(-1, party(DJEETA)),
                "a negative preset is a bug, not slot 0");
        assertEquals(Optional.empty(), PresetFace.characterOf(5, party(DJEETA)),
                "slot 5 does not exist: five slots are 0..4");
        assertEquals(Optional.empty(), PresetFace.characterOf(3, party(DJEETA, DJEETA)),
                "a short list must not be read past its end");
        assertEquals(Optional.empty(), PresetFace.characterOf(0, Arrays.asList(
                        Optional.empty(), null, Optional.empty(), Optional.empty(), Optional.empty())),
                "a null entry is 'unknown', not a crash on the HUD's render path");
    }

    /**
     * The face is the character's {@link com.astrum.pcrd.api.v1.CharacterDef#face()}, through the same
     * caches the rest of the HUD reads — asserted on the <b>compiled class file</b>.
     *
     * <p>Structural rather than behavioural, and for the reason {@code EffectsOnlySinkTest} gives for the
     * same technique: the claim is "this class reaches for <em>these</em> members and not for a private
     * copy of the rule", which no test that drives a method can see. The constant pool names every type
     * and method {@code PresetFace} references, so the claim is checkable without a registry, a client or
     * a world — and re-implementing "who is the preset" here would have been the second copy of exactly
     * the rule this class exists to keep single.
     */
    @Test
    void theFaceIsResolvedThroughTheSharedDefinition() throws Exception {
        String pool = classFileText(PresetFace.class);
        assertTrue(pool.contains("face"),
                "PresetFace must resolve the face through CharacterDef.face(), not a raw field");
        assertTrue(pool.contains("com/astrum/pcrd/api/v1/AstrumApi"),
                "…through the same public facade the book uses, so an addon's character works too");
        assertTrue(pool.contains("WheelPreset"),
                "…through the wheel's own preset, so the circle follows the wheel as it is swept");
        assertTrue(pool.contains("ClientPartyCache"),
                "…through the party push the wheel already reads");
        assertTrue(pool.contains("CharacterDef"),
                "…and the definition it reads the face from");
    }

    /** {@return a class file's bytes decoded as ISO-8859-1, so a constant-pool name can be searched} */
    private static String classFileText(Class<?> type) throws Exception {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = PresetFace.class.getResourceAsStream(path)) {
            assertNotNull(in, "the compiled class file must be on the test classpath: " + path);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }
}
