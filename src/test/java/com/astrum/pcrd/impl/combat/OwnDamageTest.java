package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.impl.InternalIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnDamageTest {

    /** Spec §5.1 rule 2: our own namespace is the recursion fuse. */
    @Test void ownNamespaceIsOurs() {
        assertTrue(OwnDamage.isOurs(InternalIds.id("hook_damage")));
    }

    @Test void vanillaDamageIsNotOurs() {
        assertFalse(OwnDamage.isOurs(ResourceLocation.parse("minecraft:player_attack")));
    }

    /**
     * An unbound damage type ({@code typeHolder().unwrapKey()} empty) and a {@code null} never
     * short-circuit: treating an unknown source as ours would silently switch the attachment
     * pipeline off for that hit.
     */
    @Test void unknownIsNotOurs() {
        assertFalse(OwnDamage.isOurs(null));
        assertFalse(OwnDamage.isOurs(ResourceLocation.parse("othermod:thing")));
    }
}
