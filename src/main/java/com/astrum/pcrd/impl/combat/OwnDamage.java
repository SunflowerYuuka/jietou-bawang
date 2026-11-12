package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.Astrum;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Answers "is this damage ours?" — the recursion fuse of spec §5.1 rule 2.
 *
 * <h2>Why the answer is "namespace", not "one id"</h2>
 * The mod will grow more damage types than {@code astrum:hook_damage}: UB damage, splash damage,
 * detonation. Every one of them must be invisible to the attachment pipeline, or the extra damage
 * we just dealt would come straight back through the entry point and deal more. Keying the check
 * on the {@code astrum} namespace means a future damage type is covered the moment it is added,
 * instead of silently becoming a recursion source because someone forgot a list entry.
 *
 * <h2>Why it is a separate class</h2>
 * It is one comparison, but it is the comparison the whole entry point hangs on, and it must be
 * provable without booting a game. Keeping it here also gives the entry point a single place to
 * log the short-circuit from.
 */
@ApiStatus.Internal
public final class OwnDamage {

    /** The namespace every damage type of this mod lives in. */
    public static final String NAMESPACE = Astrum.MOD_ID;

    private OwnDamage() {
    }

    /**
     * {@return {@code true} if {@code damageTypeId} is one of this mod's damage types}
     *
     * <p>{@code null} answers {@code false} on purpose. A damage type whose holder has no key
     * ({@code typeHolder().unwrapKey()} empty) cannot be attributed to this mod, and treating an
     * unknown source as ours would switch the attachment pipeline off for that hit — a silent
     * gameplay bug in exchange for a nicety. Unknown damage is other damage.
     *
     * @param damageTypeId the id of the damage type being applied, or {@code null} if unbound
     */
    public static boolean isOurs(@Nullable ResourceLocation damageTypeId) {
        return damageTypeId != null && NAMESPACE.equals(damageTypeId.getNamespace());
    }
}
