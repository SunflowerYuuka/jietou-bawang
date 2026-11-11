package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.api.v1.CharacterProgress;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The player's character book: every owned character and that character's progress.
 *
 * <h2>Why this is a separate attachment from {@link Party} and {@link UbState}</h2>
 * Not for layout reasons — for sync cost. {@link UbState} changes every tick; if it shared a record
 * with this map, every charge tick would re-serialize the whole book to reach the client. Spec §4.3.
 *
 * <h2>The data version is not decoration</h2>
 * This record is written into {@code playerdata/<uuid>.dat} and read back by future builds of the
 * mod, so it is a persistent structure and gets a version field (spec §9.4). The rule the codec
 * enforces is the safe direction of that rule: a file this build does not understand is
 * <b>rejected</b>, not mined for the fields that happen to look familiar. Guessing at a newer
 * schema is how a mod silently rewrites a player's progress; failing the parse leaves the
 * attachment at its default and leaves the original tag untouched on disk.
 *
 * <p>{@link #CODEC} therefore applies the version check on <b>both</b> directions: reading an
 * unknown version fails, and writing one fails too. The second half matters because
 * {@code AttachmentType.Builder.serialize} maps an encode error to an exception, which
 * {@code AttachmentHolder.serializeAttachments} logs and skips for that attachment — so a bad
 * write loses this tag rather than writing a version no reader can read back.
 *
 * <h2>Append-only</h2>
 * Per spec §9.4 the codec's fields are append-only and a rename must be done with
 * {@code optionalFieldOf} carrying the old name as a fallback, so an older file still reads. That
 * rule is not exercised yet (version 1 is the first schema) but the class holds the only
 * {@code dataVersion} constant in the mod, so the migration branch lands here.
 *
 * @param entries     owned characters; never null, never mutable
 * @param dataVersion schema version of {@code entries}, must equal {@link #CURRENT_DATA_VERSION}
 */
@ApiStatus.Internal
public record Roster(Map<ResourceLocation, CharacterProgress> entries, int dataVersion) {

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
     */
    public static final Codec<Roster> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DATA_VERSION_CODEC.fieldOf("dataVersion").forGetter(Roster::dataVersion),
            Codec.unboundedMap(ResourceLocation.CODEC, CharacterProgress.CODEC)
                    .fieldOf("entries").forGetter(Roster::entries)
    ).apply(instance, (Integer version, Map<ResourceLocation, CharacterProgress> entries) ->
            new Roster(entries, version)));

    /**
     * Copies the map and freezes it.
     *
     * <p>A record holding a caller's map would let a later {@code put} on that map mutate saved
     * player state behind the attachment's back. Copying at construction makes the component's
     * "never mutable" promise real rather than a comment.
     */
    public Roster {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** {@return an empty book at the current schema version} */
    public static Roster empty() {
        return new Roster(Map.of(), CURRENT_DATA_VERSION);
    }

    /**
     * {@return a roster with {@code character} added, or this roster unchanged if it is already owned}
     *
     * <p>Deliberately not a {@code put}: owning a character is what the roster records, so a second
     * grant of the same character must not reset the level, exp and affection the player earned.
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
        return new Roster(copy, dataVersion);
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
