package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.UbCooldown;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Which</b> cooldown the UB gauge's circle is showing — the join between "the preset slot names her"
 * and "the server said her deadline is X".
 *
 * <h2>Why this join is asserted rather than the drawing</h2>
 * 「每个角色UB的CD不共享」 made "the cooldown" an ambiguous phrase: a wheel of five characters can have
 * five different remaining times. The circle draws one face, so it must read one cooldown — that
 * character's — and the failure mode of getting this wrong is subtle: the ring and the shade would
 * count down somebody else's timer, which looks like a cooldown that never matches when the cast is
 * refused.
 *
 * <p>The drawing itself cannot be checked from here (that is the human's half of the verification), but
 * this join can, and it is the part that can be silently wrong. The inputs are plain values — a slot
 * index, the party list the client already holds, and the payload's map — so no client, registry or
 * {@code Minecraft} is involved.
 */
class CooldownTrackerTest {

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KYARU = InternalIds.id("kyaru");

    @Test void theCircleShowsThePresetCharactersOwnCooldown() {
        List<Optional<ResourceLocation>> party = party(DJEETA, KYARU);
        Map<ResourceLocation, UbCooldown> deadlines = Map.of(
                DJEETA, new UbCooldown(1_100L, 100L),
                KYARU, new UbCooldown(1_900L, 900L));

        assertEquals(Optional.of(new UbCooldown(1_100L, 100L)),
                CooldownTracker.cooldownShown(0, party, deadlines));
        assertEquals(Optional.of(new UbCooldown(1_900L, 900L)),
                CooldownTracker.cooldownShown(1, party, deadlines),
                "slot 1 is キャル, so the circle must show HER cooldown, not the first entry in the map");
    }

    /** The fraction comes from the shown cooldown and nothing else, so ring and shade cannot disagree. */
    @Test void theFractionIsThatCooldownsOwn() {
        Optional<UbCooldown> shown =
                CooldownTracker.cooldownShown(1, party(DJEETA, KYARU),
                        Map.of(KYARU, new UbCooldown(1_900L, 900L)));
        assertEquals(1.0, CooldownTracker.fraction(shown, 1_000L), 1e-9);
        assertEquals(0.5, CooldownTracker.fraction(shown, 1_450L), 1e-9);
        assertEquals(0.0, CooldownTracker.fraction(shown, 1_900L), 1e-9);
    }

    /**
     * Every "nobody to name / nothing to draw" case answers empty or zero rather than borrowing a
     * deadline from another slot.
     */
    @Test void nothingToNameAnswersEmptyRatherThanACooldown() {
        List<Optional<ResourceLocation>> party = party(DJEETA, null);
        Map<ResourceLocation, UbCooldown> deadlines = Map.of(DJEETA, new UbCooldown(2_000L, 100L));

        assertEquals(Optional.empty(), CooldownTracker.cooldownShown(1, party, deadlines),
                "an empty preset slot names nobody");
        assertEquals(Optional.empty(), CooldownTracker.cooldownShown(9, party, deadlines),
                "an out-of-range preset names nobody");
        assertEquals(Optional.empty(), CooldownTracker.cooldownShown(-1, party, deadlines));
        assertEquals(Optional.empty(), CooldownTracker.cooldownShown(0, null, deadlines),
                "a party the client has not received yet");
        assertEquals(Optional.empty(), CooldownTracker.cooldownShown(0, party, null));
        assertEquals(Optional.empty(), CooldownTracker.cooldownShown(0, party, Map.of()),
                "a character who has never cast has no entry — that is 'ready', not 'cooling'");
        assertEquals(0.0, CooldownTracker.fraction(Optional.empty(), 1_000L));
    }

    /**
     * A character in a second slot is unaffected by the first being on cooldown.
     *
     * <p>Stated at this level too, because it is the join the HUD makes every frame: switching the
     * preset from A to B must immediately show B's (empty) cooldown, which is what makes "swap and cast
     * again" visibly possible — the point of the human's request.
     */
    @Test void switchingThePresetSwitchesWhichCooldownIsDrawn() {
        List<Optional<ResourceLocation>> party = party(DJEETA, KYARU);
        Map<ResourceLocation, UbCooldown> deadlines = Map.of(DJEETA, new UbCooldown(2_000L, 100L));

        // ディジェータ ends at 2_000 with a 100-tick total: at 1_950 she has half of it left.
        assertEquals(0.5, CooldownTracker.fraction(
                CooldownTracker.cooldownShown(0, party, deadlines), 1_950L), 1e-9);
        assertEquals(0.0, CooldownTracker.fraction(
                CooldownTracker.cooldownShown(1, party, deadlines), 1_950L), 1e-9,
                "キャル has no deadline, so moving the preset to her shows a clear face at once");
        assertTrue(deadlines.containsKey(DJEETA), "and ディジェータ is still cooling");
    }

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
}
