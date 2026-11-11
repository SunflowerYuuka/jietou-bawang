package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.api.v1.CharacterProgress;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The player's character book: every owned character and that character's progress.
 *
 * <h2>Why this is a separate attachment from {@link Party} and {@link UbState}</h2>
 * Not for layout reasons — for sync cost. {@link UbState} changes every tick; if it shared a record
 * with this map, every charge tick would re-serialize the whole book to reach the client. Spec §4.3.
 *
 * <h2>The data version, and what actually happens to an unreadable save</h2>
 * This record is written into {@code playerdata/<uuid>.dat} and read back by future builds of the
 * mod, so it is a persistent structure and gets a version field (spec §9.4). Two rules apply:
 *
 * <ol>
 *   <li><b>Do not reinterpret an unknown schema.</b> {@link #CODEC} rejects any {@code dataVersion}
 *       other than {@link #CURRENT_DATA_VERSION}; mining a newer tag for fields that happen to look
 *       familiar is how a mod silently rewrites a player's progress.</li>
 *   <li><b>Do not destroy it either.</b> Rejecting the parse is only half the job, and the naive
 *       version of it is actively destructive. {@code AttachmentHolder.deserializeAttachments}
 *       swallows a read failure and simply never puts the entry into the in-memory map; later,
 *       {@code serializeAttachments} rebuilds the attachment compound <b>from that map alone</b>
 *       ({@code CompoundTag tag = null} then one {@code put} per entry). So a rejected tag is
 *       absent from the rebuilt compound, and the next autosave writes a playerdata without it —
 *       the character book is silently wiped, not preserved. That fires exactly when
 *       {@link #CURRENT_DATA_VERSION} is bumped and a player downgrades.</li>
 * </ol>
 *
 * <p>{@link #SERIALIZER} therefore quarantines instead of merely refusing: when the version is
 * unknown it keeps the original tag and writes it back <b>verbatim</b>, so a downgrade round-trips
 * the newer file byte-for-byte and re-upgrading loses nothing. See {@link #quarantined()}.
 *
 * <h2>Why the version is validated at construction as well</h2>
 * {@link #CURRENT_DATA_VERSION} is enforced in the canonical constructor, so a {@code Roster} that
 * cannot be encoded cannot be built either. That matters because the two serialization paths react
 * to an encode failure very differently: {@code serializeAttachments} logs and skips, but the
 * death-copy path ({@code AttachmentInternals.copyAttachments} → the copy handler) has <b>no</b>
 * try/catch, so an encode throw there would propagate out of {@code PlayerList.respawn} and take
 * the server down mid-respawn. An unencodable value is simply not constructible.
 *
 * <h2>Append-only</h2>
 * Per spec §9.4 the codec's fields are append-only and a rename must be done with
 * {@code optionalFieldOf} carrying the old name as a fallback, so an older file still reads. That
 * rule is not exercised yet (version 1 is the first schema) but the class holds the only
 * {@code dataVersion} constant in the mod, so the migration branch lands here.
 *
 * @param entries     owned characters; never null, never mutable
 * @param dataVersion schema version of {@code entries}, always equal to {@link #CURRENT_DATA_VERSION}
 * @param quarantine  raw tag of a save this build could not read, or {@code null}; written back
 *                    verbatim in place of {@code entries}
 */
@ApiStatus.Internal
public record Roster(Map<ResourceLocation, CharacterProgress> entries, int dataVersion,
                     @Nullable Tag quarantine) {

    /** Schema version written by this build. Bump only with a migration branch in {@link #CODEC}. */
    public static final int CURRENT_DATA_VERSION = 1;

    /**
     * The version check, shared by encode and decode.
     *
     * <p>Returns the value unchanged on success so it can be used as a {@code Codec.validate}
     * function; the message is what a player would see only indirectly (it ends up in a skipped
     * attachment log line), so it names the field and both versions.
     */
    private static final Codec<Integer> DATA_VERSION_CODEC = Codec.INT.validate(version ->
            version == CURRENT_DATA_VERSION
                    ? DataResult.success(version)
                    : DataResult.error(() -> "Unsupported astrum roster dataVersion " + version
                            + " (this build reads " + CURRENT_DATA_VERSION + "). Refusing to reinterpret it."));

    /**
     * Disk codec. Field order is deliberate: {@code dataVersion} is read before {@code entries}, so
     * an unknown version fails before the map is even looked at. That is why the fields are grouped
     * in the opposite order to the record components and joined by a lambda.
     *
     * <p>This is the <b>schema</b>, not the whole disk contract: quarantine handling lives in
     * {@link #SERIALIZER}, because a codec can only report failure and failure is what loses the
     * tag.
     */
    public static final Codec<Roster> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DATA_VERSION_CODEC.fieldOf("dataVersion").forGetter(Roster::dataVersion),
            Codec.unboundedMap(ResourceLocation.CODEC, CharacterProgress.CODEC)
                    .fieldOf("entries").forGetter(Roster::entries)
    ).apply(instance, (Integer version, Map<ResourceLocation, CharacterProgress> entries) ->
            new Roster(entries, version)));

    /**
     * What {@link Attachments#ROSTER} actually persists with.
     *
     * <p>Reads through {@link #CODEC}. On failure the raw tag is captured into
     * {@link #quarantined()} rather than dropped, and writes of a quarantined roster emit that tag
     * unchanged — which is the only way to survive {@code serializeAttachments} rebuilding the
     * compound from the in-memory map. A quarantined roster has empty {@code entries}, so gameplay
     * sees a player who owns nothing until they run a build that understands the file; that is a
     * visible, loggable degradation, and it leaves the real book intact on disk.
     */
    public static final IAttachmentSerializer<Tag, Roster> SERIALIZER =
            new IAttachmentSerializer<>() {
                @Override
                public Roster read(net.neoforged.neoforge.attachment.IAttachmentHolder holder,
                        Tag tag, HolderLookup.Provider provider) {
                    DataResult<Roster> parsed =
                            CODEC.parse(provider.createSerializationContext(NbtOps.INSTANCE), tag);
                    var result = parsed.result();
                    if (result.isPresent()) {
                        return result.get();
                    }
                    Astrum.LOGGER.error("astrum: unreadable roster attachment kept as-is and left "
                            + "untouched on disk ({})", parsed.error().map(DataResult.Error::message)
                                    .orElse("no detail"));
                    return quarantined(tag);
                }

                @Override
                public @Nullable Tag write(Roster roster, HolderLookup.Provider provider) {
                    if (roster.quarantine != null) {
                        return roster.quarantine.copy();
                    }
                    return CODEC.encodeStart(provider.createSerializationContext(NbtOps.INSTANCE), roster)
                            .getOrThrow(msg -> new IllegalStateException(
                                    "astrum: could not encode roster: " + msg));
                }
            };

    /**
     * Copies the map, freezes it, and refuses a version this build could not write back.
     *
     * <p>A record holding a caller's map would let a later {@code put} on that map mutate saved
     * player state behind the attachment's back, so the map is copied and frozen.
     *
     * <p>The version check is here, not only in the codec, because an unencodable value must not be
     * constructible: the death-copy path encodes without a try/catch (see the class comment), so a
     * throw there would crash respawn. Quarantined values deliberately carry the current version and
     * an empty map, so they satisfy this check while still writing the foreign tag back.
     *
     * @throws IllegalArgumentException if {@code dataVersion} is not {@link #CURRENT_DATA_VERSION}
     */
    public Roster {
        if (dataVersion != CURRENT_DATA_VERSION) {
            throw new IllegalArgumentException("astrum: roster dataVersion must be "
                    + CURRENT_DATA_VERSION + ", got " + dataVersion
                    + " (an unreadable save is quarantined, not constructed)");
        }
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * Convenience for the ordinary, readable case.
     *
     * @param entries     owned characters
     * @param dataVersion must be {@link #CURRENT_DATA_VERSION}
     */
    public Roster(Map<ResourceLocation, CharacterProgress> entries, int dataVersion) {
        this(entries, dataVersion, null);
    }

    /** {@return an empty book at the current schema version} */
    public static Roster empty() {
        return new Roster(Map.of(), CURRENT_DATA_VERSION);
    }

    /**
     * {@return a roster standing in for a save this build cannot read, holding {@code originalTag}
     * to write back unchanged}
     *
     * <p>{@code entries} are empty because nothing was readable; {@code dataVersion} is the current
     * one because that is the only value this build may hold, and it is not what gets written —
     * {@link #SERIALIZER} emits {@code originalTag} instead.
     *
     * @param originalTag the tag exactly as it was on disk
     */
    public static Roster quarantined(Tag originalTag) {
        return new Roster(Map.of(), CURRENT_DATA_VERSION, originalTag.copy());
    }

    /** {@return {@code true} if this stands in for a save this build could not read} */
    public boolean isQuarantined() {
        return quarantine != null;
    }

    /**
     * {@return a roster with {@code character} added, or this roster unchanged if it is already owned}
     *
     * <p>Deliberately not a {@code put}: owning a character is what the roster records, so a second
     * grant of the same character must not reset the level, exp and affection the player earned.
     *
     * <p>A quarantined roster stays quarantined: the character is added to this in-memory view but
     * the original tag is still what gets written, so a downgraded session cannot overwrite the
     * newer book with a partial one.
     *
     * @param character the character to grant
     * @param initial   progress to use if the character is not owned yet
     */
    public Roster addOrIgnore(ResourceLocation character, CharacterProgress initial) {
        if (entries.containsKey(character)) {
            return this;
        }
        Map<ResourceLocation, CharacterProgress> copy = new LinkedHashMap<>(entries);
        copy.put(character, initial);
        return new Roster(copy, dataVersion, quarantine);
    }

    /**
     * {@return progress for {@code character}, or {@code null} if not owned}
     *
     * <p>Nullable on purpose: "not owned" and "owned at level 0" are different states and a
     * default value would collapse them.
     */
    public @Nullable CharacterProgress progressOf(ResourceLocation character) {
        return entries.get(character);
    }
}
