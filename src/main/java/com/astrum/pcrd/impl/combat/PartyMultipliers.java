package com.astrum.pcrd.impl.combat;

import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.impl.config.Balance;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The diminishing-return multipliers for one party, in wheel order (spec §5.2).
 *
 * <h2>What this class is, and is not</h2>
 * It is a pure function from "the party + each character's dimension" to "one multiplier per slot".
 * It is <b>not</b> the place where an effect decides whether to fire: spec §5.2 draws that line at
 * 数值 vs 机制 — diminishing returns scale the <em>quantity</em> (damage, healing, charge, duration)
 * and never the <em>mechanism</em> (whether the hook runs, stack limits, detonation). So the kernel
 * emits numbers only, and a caller that receives {@code 0.1} still runs the effect; it just runs it
 * smaller. Keeping the decision out of here is what makes "5 人同开不爆炸" compatible with "第 5 名
 * 角色放了不等于没放".
 *
 * <h2>Why no config and no {@code Level}/{@code Player}/{@code Entity}</h2>
 * Same reason as {@link Balance}: the config values are read by the caller at the point of use
 * (spec §8.4 guardrail 1) and handed in as {@code double base} / {@code boolean enabled}. A kernel
 * that could read a config or a level would have to be tested with a running game, and the ordering
 * rule — the thing that is actually easy to get wrong — would stop being testable arithmetic.
 *
 * <h2>What "rank" means</h2>
 * The zero-based count of <b>earlier</b> non-empty slots that share this slot's dimension. Slot
 * order is priority ({@code PARTY} order = wheel position), so the first {@code output} character
 * keeps {@code 1.0}, the second takes {@code base}, the third {@code base²}, and a different
 * dimension restarts at {@code 1.0}. An empty slot is not a character and therefore consumes no
 * rank.
 *
 * <h2>Degrading on broken data instead of throwing</h2>
 * Datapack content and save files are hostile input: a party slot can name a character the loaded
 * data pack no longer defines. An unknown dimension means "this character cannot be ranked", which
 * answers the neutral multiplier {@code 1.0} — never {@code 0} (which would silently disable a
 * player's character) and never an exception (which would escape into a damage event and, at worst,
 * take the server down).
 *
 * <p>That tolerance is deliberately limited to <b>data</b>: an unknown character id. A malformed
 * <em>call</em> — a null list, a slot index off the wheel — still fails loudly, because there is no
 * sane number to answer and hiding the mistake would leave it in the game for the rest of the
 * session.
 *
 * @see Balance#diminishingMultiplier(int, double, boolean)
 */
@ApiStatus.Internal
public final class PartyMultipliers {

    /**
     * Wheel positions. Mirrors {@code Party.SLOTS} deliberately: the kernel takes a plain list, not
     * a {@code Party}, because it must not depend on the attachment layer — a test asserts the two
     * constants stay equal instead.
     */
    public static final int SLOTS = 5;

    private PartyMultipliers() {
    }

    /**
     * {@return the neutral multipliers: {@code 1.0} for every slot}
     *
     * <p>This is the answer for "no characters this session" — diminishing returns switched off, an
     * empty party, no known dimensions, or a {@code Roster} that is quarantined and therefore cannot
     * be read at all.
     *
     * <p>A fresh array on every call rather than a shared constant: the result is handed to callers
     * that own it, and one of them writing into a shared array would corrupt every later read.
     */
    public static double[] neutral() {
        double[] multipliers = new double[SLOTS];
        Arrays.fill(multipliers, Balance.MAX_MULTIPLIER);
        return multipliers;
    }

    /**
     * {@return one multiplier per wheel slot, in slot order}
     *
     * <p>One left-to-right pass, remembering how many characters of each dimension have been seen so
     * far. The result is always exactly {@link #SLOTS} long: a list shorter than the wheel leaves the
     * remaining slots neutral, and anything past the last wheel position is ignored — a caller's list
     * length must be unable to shift either the ranks or the array.
     *
     * @param party      per-slot characters, index = wheel position; may be shorter than
     *                   {@link #SLOTS}; an empty entry is an unused slot
     * @param dimensions dimension of each character, from datapack definitions; a character absent
     *                   from this map is unknown and receives {@code 1.0}
     * @param base       configured decay factor, clamped by {@link Balance#clampBase(double)}
     * @param enabled    value of the {@code enableDiminishingReturns} config option
     */
    public static double[] all(List<Optional<ResourceLocation>> party,
            Map<ResourceLocation, Dimension> dimensions, double base, boolean enabled) {
        double[] multipliers = neutral();
        Map<Dimension, Integer> rankSeen = new EnumMap<>(Dimension.class);
        int wheel = Math.min(SLOTS, party.size());
        for (int slot = 0; slot < wheel; slot++) {
            Optional<ResourceLocation> value = party.get(slot);
            if (value.isEmpty()) {
                continue;   // an unused slot is not a character: it consumes no rank
            }
            Dimension dimension = dimensions.get(value.get());
            if (dimension == null) {
                continue;   // unknown character: stays neutral and consumes no rank
            }
            int rank = rankSeen.getOrDefault(dimension, 0);
            multipliers[slot] = Balance.diminishingMultiplier(rank, base, enabled);
            rankSeen.put(dimension, rank + 1);
        }
        return multipliers;
    }

    /**
     * {@return the multiplier for one wheel slot, read off the same table {@link #all} builds}
     *
     * <p>For callers that resolve a single event and have no use for the other four numbers. It is
     * deliberately "one entry of the table" rather than a second scan: two independent rank
     * implementations could drift, and the symptom would be one slot disagreeing with its own
     * party — a bug that only shows up as a wrong number in a fight.
     *
     * @param slot wheel position in {@code [0, SLOTS)}
     * @throws IllegalArgumentException if {@code slot} is outside the wheel — that is a caller bug
     *                                  rather than bad data, so it fails loudly like
     *                                  {@code Party.with} does, instead of degrading to a number
     * @see #all(List, Map, double, boolean)
     */
    public static double forSlot(List<Optional<ResourceLocation>> party,
            Map<ResourceLocation, Dimension> dimensions, int slot, double base, boolean enabled) {
        if (slot < 0 || slot >= SLOTS) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        return all(party, dimensions, base, enabled)[slot];
    }
}
