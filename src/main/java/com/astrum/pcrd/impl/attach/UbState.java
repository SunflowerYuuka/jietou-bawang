package com.astrum.pcrd.impl.attach;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

/**
 * UB runtime state: how charged the bar is, which wheel slot is selected, and <b>when each character's
 * own cooldown ends</b>.
 *
 * <h2>Why it is its own attachment</h2>
 * These values change every tick while the player fights, while {@link Roster} changes a few times per
 * session. Merging them would re-serialize the entire character book on every charge tick. Spec §4.3
 * splits them for exactly this reason. The split also means a change here can be synced with a tiny
 * packet without touching progress data.
 *
 * <h2>One cooldown per character, not one shared timer</h2>
 * 「每个角色UB的CD不共享」 — the human asked for each character to cool down on her own, which
 * <b>supersedes</b> the earlier controller ruling on spec §4.3/§5.4 that this record used to record
 * (a single {@code cdEndTick} shared by the whole wheel). His sentence is the reason the field changed
 * shape, and it is quoted here so the ruling cannot be cited against the code again without meeting
 * the sentence that replaced it.
 *
 * <p>What that buys: casting A arms A's cooldown and <b>nothing else</b>, so B is castable the moment
 * the gauge is full again even while A still cools. What it costs: {@link #charge} stays a single
 * shared bar (he asked about the cooldown, not the gauge), so casting A still drains the bar for
 * everybody — see {@code UbCharge}'s javadoc for why the gauge was deliberately left alone.
 *
 * <h2>Why the cooldowns are a map inside this record and not a second attachment</h2>
 * A second {@code AttachmentType} would need its own serializer, its own sync path and its own
 * copy-on-death decision, and it would let the two halves of one fact drift apart: the cast path writes
 * "the bar is empty" and "she is on cooldown" in <b>one</b> {@code setData} today, which is what makes a
 * refused cast cost nothing and an accepted one atomic. Splitting them would open a window in which the
 * bar is spent and the cooldown is not armed. So the map lives here, and the serialized shape grows by
 * one field.
 *
 * <h2>The migration, stated exactly</h2>
 * The old shape was {@code {charge, selectedSlot, cdEndTick}}. It decodes into this shape with
 * <b>no cooldowns</b>, because {@code cooldowns} is an <em>optional</em> field defaulting to an empty
 * map; the old {@code cdEndTick} field is simply not read any more (a codec ignores keys it does not
 * name, so it is discarded on the next save rather than misread as somebody's deadline). An absent
 * entry is the normal state, not an error: <b>no entry means "this character has never cast, so she is
 * ready"</b>. The one thing an upgrade loses is a cooldown that was in flight at the moment of the
 * save — at most {@code ubGlobalCooldownTicks} (100 ticks by default, 5 s), once, per player. It is
 * deliberately not migrated onto any character, because the old timer did not record <em>whose</em>
 * cast armed it, and inventing a character to blame would be worse than a player who can cast once.
 *
 * <h2>Why the codec validates instead of trusting</h2>
 * {@code playerdata} is a file on the player's disk, so these numbers arrive from an untrusted
 * source even when the mod wrote them. Two of them can hurt:
 *
 * <ul>
 *   <li>A non-finite {@code charge} ({@code NaN}, infinity) makes every comparison false, so the bar
 *       would neither fill nor reset — a permanent, silent loss of the character's core mechanic.</li>
 *   <li>An out-of-range {@code selectedSlot} is an index the wheel would eventually use, i.e. an
 *       {@code IndexOutOfBoundsException} during rendering rather than a clean failure.</li>
 * </ul>
 *
 * <p>Both are <b>rejected</b> rather than clamped. Clamping would quietly invent a state the player
 * never had; a decode error makes the attachment fall back to its default, which is a known-good
 * state, and {@code AttachmentHolder.deserializeAttachments} logs the failure instead of taking the
 * world down. The upper bound of {@code charge} is deliberately <b>not</b> checked here: the maximum
 * is a server config value and this codec must not depend on config being loaded.
 *
 * <p>Each {@link UbCooldown} inside the map is a different case and is <b>clamped, not rejected</b> —
 * the reasoning is on {@link UbCooldown}'s own javadoc, and the short version is that a nonsense
 * deadline cannot disable a mechanic or throw, so failing the whole attachment over one would cost the
 * player her charge bar to punish a timestamp.
 *
 * @param charge       charge accumulated toward the UB, finite and non-negative
 * @param selectedSlot index of the currently selected wheel slot, in {@code [0, Party.SLOTS)}
 * @param cooldowns    per-character cooldowns; a character with no entry has never cast and is ready
 */
@ApiStatus.Internal
public record UbState(float charge, int selectedSlot, Map<ResourceLocation, UbCooldown> cooldowns) {

    /** {@return the state a player who has never cast anything starts from} */
    public static UbState fresh() {
        return new UbState(0.0f, 0, Map.of());
    }

    /**
     * Copies the map so a caller cannot mutate the state through an alias it kept.
     *
     * <p>{@code Map.copyOf} rather than a defensive {@code new HashMap<>(...)}: the state is read from
     * another thread's HUD path and from the save thread, and an immutable map is the only shape that
     * cannot be changed underneath either. Iteration order is not meaningful here (the map is keyed by
     * character id, and every reader looks up by key), so losing insertion order costs nothing.
     */
    public UbState {
        cooldowns = cooldowns == null ? Map.of() : Map.copyOf(cooldowns);
    }

    private static final Codec<Float> CHARGE_CODEC = Codec.FLOAT.validate(charge ->
            Float.isFinite(charge) && charge >= 0.0f
                    ? DataResult.success(charge)
                    : DataResult.error(() -> "astrum: UB charge must be finite and non-negative, got " + charge));

    private static final Codec<Integer> SELECTED_SLOT_CODEC = Codec.INT.validate(slot ->
            slot >= 0 && slot < Party.SLOTS
                    ? DataResult.success(slot)
                    : DataResult.error(() -> "astrum: UB selectedSlot out of range [0, " + Party.SLOTS
                            + "), got " + slot));

    /**
     * Disk codec for {@code playerdata}; small on purpose, it is written far more often than the roster.
     *
     * <p>Both new-in-this-shape fields are optional so a save written by the previous build decodes:
     * {@code cooldowns} defaults to empty ("never cast"), and {@code totalTicks} inside each entry
     * defaults to zero (nothing to shade). See the class javadoc for the migration in full.
     */
    public static final Codec<UbState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CHARGE_CODEC.fieldOf("charge").forGetter(UbState::charge),
            SELECTED_SLOT_CODEC.fieldOf("selectedSlot").forGetter(UbState::selectedSlot),
            Codec.unboundedMap(ResourceLocation.CODEC, UbCooldown.CODEC)
                    .optionalFieldOf("cooldowns", Map.of())
                    .forGetter(UbState::cooldowns)
    ).apply(instance, UbState::new));

    /**
     * {@return that character's cooldown, or {@link UbCooldown#NONE} when she has never cast}
     *
     * <p>Never throws and never answers {@code null}: a character absent from the map has simply never
     * been cast, which is the state every character starts in.
     *
     * @param characterId the character, e.g. {@code astrum:djeeta}
     */
    public UbCooldown cooldownOf(ResourceLocation characterId) {
        if (characterId == null) {
            return UbCooldown.NONE;
        }
        UbCooldown cooldown = cooldowns.get(characterId);
        return cooldown == null ? UbCooldown.NONE : cooldown;
    }

    /**
     * {@return {@code true} if that character may cast at {@code nowTick} as far as the cooldown is
     *         concerned}
     *
     * <p>Lazy check, no tick polling — the project's performance rule is zero polling loops, so
     * expiry is only ever asked about at the moment someone wants to cast or draw.
     *
     * @param characterId the character being asked about
     * @param nowTick     the current game time, e.g. {@code level.getGameTime()}
     */
    public boolean isReady(ResourceLocation characterId, long nowTick) {
        return cooldownOf(characterId).isOver(nowTick);
    }

    /**
     * {@return this state with {@code characterId}'s cooldown replaced}
     *
     * <p>A new state rather than a mutation, like every other value here. The charge and the selected
     * slot are carried over untouched, so a caller that only wants to arm a cooldown cannot accidentally
     * reset the bar.
     *
     * @param characterId the character whose cooldown changes
     * @param cooldown    the new cooldown, or {@link UbCooldown#NONE} to clear it
     */
    public UbState withCooldown(ResourceLocation characterId, UbCooldown cooldown) {
        if (characterId == null) {
            return this;
        }
        Map<ResourceLocation, UbCooldown> next = new LinkedHashMap<>(cooldowns);
        if (cooldown == null || UbCooldown.NONE.equals(cooldown)) {
            next.remove(characterId);
        } else {
            next.put(characterId, cooldown);
        }
        return new UbState(charge, selectedSlot, next);
    }

    /**
     * {@return this state with the charge and the selection replaced, cooldowns untouched}
     *
     * <p>The shape the charge commands and {@code UbCharge} need: they change the bar and nothing else,
     * and each of them used to spell out {@code new UbState(next, before.selectedSlot(),
     * before.cdEndTick())}. With a map in the third position that spelling would be an invitation to
     * pass {@code Map.of()} and silently clear every cooldown, so it is written once here instead.
     *
     * @param newCharge       the new charge
     * @param newSelectedSlot the new selected slot
     */
    public UbState withCharge(float newCharge, int newSelectedSlot) {
        return new UbState(newCharge, newSelectedSlot, cooldowns);
    }

    /** {@return the characters on cooldown at {@code nowTick}, for a probe or a log line} */
    public Map<ResourceLocation, UbCooldown> activeCooldowns(long nowTick) {
        Map<ResourceLocation, UbCooldown> active = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, UbCooldown> entry : cooldowns.entrySet()) {
            if (!entry.getValue().isOver(nowTick)) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }
}
