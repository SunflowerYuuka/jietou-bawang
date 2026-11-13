package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.impl.InternalIds;
import com.astrum.pcrd.impl.attach.Party;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The one mapping from a party slot to the {@code MobEffect} that shows that slot in vanilla's effect
 * HUD: slot {@code i} is {@code astrum:party_<i+1>}, five types in total.
 *
 * <h2>Why five types instead of one</h2>
 * Vanilla keeps a player's effects in a map keyed by the effect <em>type</em>, and
 * {@link net.minecraft.world.effect.MobEffectInstance#update} merges two instances of the same type
 * (it refreshes the duration instead of adding a second entry). So one shared effect type for the
 * whole party could only ever produce <b>one</b> HUD entry no matter how many characters are
 * equipped — the other four would silently merge into it. Registering one type per slot makes five
 * simultaneous entries possible by construction, and it does so even when two slots' characters are
 * the <em>same</em> character id: identity here is the slot, not the character.
 *
 * <p>This class holds the names and nothing else it would have to keep in sync: the slot count is
 * {@link Party#SLOTS} (the single copy of "five"), the ids are derived from it, and the lang keys are
 * derived from the ids. {@code MobEffect}s themselves are registered by {@link PartyEffects}.
 *
 * <p>No Minecraft registry is touched here, so {@code PartyEffectSlotsTest} can test the mapping
 * without a game running.
 */
@ApiStatus.Internal
public final class PartyEffectSlots {

    /** Registry path prefix: the effect for slot {@code i} is {@code astrum:party_<i+1>}. */
    public static final String PATH_PREFIX = "party_";

    /**
     * Particle colours, one per slot.
     *
     * <p>Only used as the {@code MobEffect} colour (vanilla's entity-effect particle tint); the HUD
     * icon itself is the character's avatar. Distinct colours keep the five entries distinguishable in
     * the one place vanilla draws them even if two slots hold the same character.
     */
    public static final int[] SLOT_COLORS = {
            0x569CFF, 0xFF7ABE, 0x70D680, 0xFFC648, 0xBA84FF,
    };

    private PartyEffectSlots() {
    }

    /** {@return every slot's effect id, in slot order} */
    public static List<ResourceLocation> ids() {
        List<ResourceLocation> ids = new ArrayList<>(Party.SLOTS);
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            ids.add(idFor(slot));
        }
        return List.copyOf(ids);
    }

    /**
     * {@return the effect id of {@code slot}}
     *
     * @param slot 0-based party slot, {@code 0 <= slot < Party.SLOTS}
     * @throws IllegalArgumentException when the slot is outside the party
     */
    public static ResourceLocation idFor(int slot) {
        if (slot < 0 || slot >= Party.SLOTS) {
            throw new IllegalArgumentException("party slot out of range: " + slot);
        }
        return InternalIds.id(PATH_PREFIX + (slot + 1));
    }

    /**
     * {@return the slot an effect id belongs to, empty for an id that is not a party effect}
     *
     * @param id the registry id to classify, may be {@code null}
     */
    public static OptionalInt slotOf(ResourceLocation id) {
        if (id == null || !NAMESPACE.equals(id.getNamespace())
                || !id.getPath().startsWith(PATH_PREFIX)) {
            return OptionalInt.empty();
        }
        String suffix = id.getPath().substring(PATH_PREFIX.length());
        int oneBased;
        try {
            oneBased = Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
        if (oneBased < 1 || oneBased > Party.SLOTS || !id.equals(idFor(oneBased - 1))) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(oneBased - 1);
    }

    /**
     * {@return the translation key of the slot's fallback effect name}
     *
     * <p>Derived from the id rather than written out: {@code astrum:party_1} is
     * {@code effect.astrum.party_1}, which is exactly the language key vanilla looks up for
     * {@code MobEffect#getDisplayName}. The name a player normally sees is the character's skill name
     * ({@code PartyBuffName}); this key is the name used before the party sync lands, and the fallback
     * when the character's pack entry is missing.
     */
    public static String nameKey(int slot) {
        return "effect." + idFor(slot).toLanguageKey();
    }

    /** {@return every fallback effect-name key, for {@code LangParityTest}} */
    public static Set<String> langKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (int slot = 0; slot < Party.SLOTS; slot++) {
            keys.add(nameKey(slot));
        }
        return Set.copyOf(keys);
    }

    /** {@return the namespace the party effects live in} */
    public static String namespace() {
        return NAMESPACE;
    }

    // Kept as a constant rather than calling Astrum.MOD_ID: this class is loaded on a dedicated
    // server and its unit test runs without the mod; a string is the whole dependency.
    private static final String NAMESPACE = "astrum";
}