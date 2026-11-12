package com.astrum.pcrd.impl.attach;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * Which attacker, which stack kind — the key half of a per-attacker stack (spec §4.4).
 *
 * <p>The attacker uuid is <b>in the key</b> rather than being a field of the value: two players
 * hitting the same target must keep separate counts, or the second player's hits would top up the
 * first player's stacks and a shared mob would become a shared combo meter.
 *
 * @param attacker the player whose wheel added the stack
 * @param stackId  the stack kind named by the hook's {@code effectParams.stackId}
 */
@ApiStatus.Internal
public record StackKey(UUID attacker, ResourceLocation stackId) {

    public StackKey {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(stackId, "stackId");
    }
}
