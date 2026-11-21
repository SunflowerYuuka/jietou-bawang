package com.astrum.pcrd.impl.item;

import com.astrum.pcrd.Astrum;
import com.astrum.pcrd.impl.InternalIds;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>Which {@code custom_model_data} value draws which character's face on a rolled card.</b>
 *
 * <h2>Why the mapping is a file and not a field of {@link com.astrum.pcrd.api.v1.CharacterDef}</h2>
 * A card's icon is decided by vanilla's model override system, which keys on a number
 * ({@code custom_model_data}) and nothing else. The obvious home for that number would be the character's
 * own definition — but {@code CharacterDef} is at <b>exactly sixteen</b> {@code RecordCodecBuilder}
 * components and {@code Products.P16} has no {@code .and()}, so a seventeenth component is a compile error,
 * not a style choice. So the number lives in
 * {@code assets/astrum/card_index.json}, one line per character:
 *
 * <pre>
 *   { "astrum:djeeta": 1, "astrum:kokkoro": 2, "astrum:pecorine": 3, "astrum:kyaru": 4 }
 * </pre>
 *
 * <p>Adding a character is therefore one line here, one {@code models/item/card_<id>.json}, and one entry in
 * {@code character_card.json}'s {@code overrides} array. {@code CardIndexResourceTest} fails if those three
 * disagree, so a typo cannot ship.
 *
 * <h2>Where it is loaded, and why there</h2>
 * Read <b>once, lazily, off this mod's own classpath</b> ({@link #RESOURCE}) — not through a resource-pack
 * reload listener. The distinction is the point: a reload listener exists for files that a
 * <em>resource pack</em> can override ({@code ArtFallbackReloadListener} invalidates a texture cache for
 * exactly that reason), while this file ships inside the mod jar and no pack can change it. A reload
 * listener would buy nothing and would add a mutable field written from a reload thread.
 *
 * <p>It is a plain classpath read rather than a client-only one because the value is <b>written on the
 * server</b>: the roll happens at craft time in {@code CardRollOnCraft}, and a component is written where
 * the stack is created. Only the <em>rendering</em> of the resulting model is client-side, and that is
 * authored JSON which needs no code at all. So this holder lives in {@code impl.item} — reachable from both
 * sides, no {@code net.minecraft.client} import, and the guard's "client classes only under impl/client"
 * line stays true.
 *
 * <h2>Why the values must be non-zero, and why ascending</h2>
 * <ul>
 *   <li><b>Non-zero:</b> a stack with no {@code custom_model_data} component reads {@code 0.0f} through
 *       {@code ItemProperties}, so an override authored at {@code 0} would repaint <em>every</em> blank
 *       card. {@link #indexOf} answers {@code 0} for "no index", which is that same value, so 0 must never
 *       be a real index — {@link Table} refuses it.</li>
 *   <li><b>Ascending:</b> {@code ItemOverrides} bakes its list <b>in reverse</b>
 *       ({@code for (j = size - 1; j >= 0; j--)}) and matches with a <b>{@code >=} threshold</b>
 *       ({@code BakedOverride.test}), so the last match in bake order wins and the highest matching
 *       threshold wins overall. Authoring 1, 2, 3, 4 in ascending order is what makes index 4 select the
 *       fourth model rather than the first.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class CardIndex {

    /** The index file's id inside the mod's own assets, for a log line and for the test. */
    public static final ResourceLocation ID = InternalIds.id("card_index.json");

    /**
     * The classpath location the file is read from.
     *
     * <p>Absolute and spelled once: the loader and {@code CardIndexResourceTest} must look at the same
     * thing, or the test can pass while the game reads nothing.
     */
    public static final String RESOURCE = "/assets/astrum/card_index.json";

    /**
     * The loaded table, or {@code null} before the first read.
     *
     * <p>A field of an immutable record type rather than a {@code Map} field: the guard flags a static
     * mutable container because such a field survives a world reload. This one is replaced as a whole,
     * never mutated, and holds no entity, player or world — only this mod's own ids and four integers.
     */
    private static volatile Table loaded;

    private CardIndex() {
    }

    /**
     * {@return the {@code custom_model_data} value that draws {@code characterId}'s face, or {@code 0}}
     *
     * <p>{@code 0} is also what an absent component reads, which is the correct answer for a character this
     * file does not list: a card that names somebody the art pack has no picture for must keep the generic
     * card art rather than borrow somebody else's face. {@code CardRollOnCraft} then writes no component at
     * all, so the stack stays exactly as a blank card.
     *
     * @param characterId the character the card names, possibly {@code null}
     */
    public static int indexOf(@Nullable ResourceLocation characterId) {
        return characterId == null ? 0 : table().indexOf(characterId);
    }

    /**
     * {@return the loaded table, reading the file on first use}
     *
     * <p>Lazy rather than eager so a class-load order problem cannot make the mod fail to boot: this is read
     * from the craft path, which runs long after the mod constructor.
     */
    public static Table table() {
        Table current = loaded;
        if (current == null) {
            current = read();
            loaded = current;
        }
        return current;
    }

    /**
     * Drops the cached table so the next {@link #table()} re-reads the file.
     *
     * <p>For tests only: the shipped file cannot change while the game runs, so no production path calls
     * this. It exists because a test that reads the file through the *production* loader must be able to
     * start from a known state.
     */
    static void reloadForTest() {
        loaded = null;
    }

    /**
     * {@return the table read from {@link #RESOURCE}, empty when the file is missing or unreadable}
     *
     * <p>Never throws: this runs on the craft path, where an exception would eat the player's ingredients.
     * A missing file means "no character has an index", which degrades to every rolled card keeping the
     * generic art — visibly wrong in a way a test catches, rather than a crash in a way a player eats.
     */
    private static Table read() {
        try (InputStream in = CardIndex.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                Astrum.LOGGER.error("astrum: {} is missing from the mod jar, so no rolled card can draw a "
                        + "character face; every card will keep the generic art", RESOURCE);
                return Table.EMPTY;
            }
            JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(root);
        } catch (Exception e) {
            Astrum.LOGGER.error("astrum: could not read {}, so no rolled card can draw a character face",
                    RESOURCE, e);
            return Table.EMPTY;
        }
    }

    /**
     * {@return the table {@code root} describes, dropping entries that cannot be used}
     *
     * <p>Package-private and pure so a test can feed it a bad document without writing a file. The policy
     * matches the datapack loader's: one bad <em>entry</em> is skipped with a WARN, and the rest of the file
     * still loads. A bad <em>file</em> (not JSON, not an object) is empty rather than fatal.
     *
     * <p>Keys starting with {@code _} are documentation ({@code _notes}) and are skipped without a warning —
     * the same convention the character JSONs use.
     *
     * @param root the parsed document
     */
    static Table parse(@Nullable JsonElement root) {
        if (root == null || !root.isJsonObject()) {
            Astrum.LOGGER.warn("astrum: {} must be a JSON object of character id to index; ignoring it",
                    RESOURCE);
            return Table.EMPTY;
        }
        JsonObject object = root.getAsJsonObject();
        Map<ResourceLocation, Integer> byCharacter = new LinkedHashMap<>();
        Map<Integer, ResourceLocation> byIndex = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("_")) {
                continue;   // _notes and future documentation keys
            }
            ResourceLocation characterId = ResourceLocation.tryParse(key);
            if (characterId == null) {
                Astrum.LOGGER.warn("astrum: {} names {} which is not a resource location; skipped",
                        RESOURCE, key);
                continue;
            }
            int index = asIndex(entry.getValue());
            if (index <= 0) {
                // 0 is what an absent component reads, so an override at 0 would repaint blank cards too.
                Astrum.LOGGER.warn("astrum: {} gives {} the index {}; an index must be a positive integer "
                        + "(0 reads as 'no override' and would repaint blank cards); skipped",
                        RESOURCE, characterId, index);
                continue;
            }
            ResourceLocation clash = byIndex.get(index);
            if (clash != null) {
                // Two characters sharing one face: the second would silently draw the first one's picture.
                Astrum.LOGGER.warn("astrum: {} gives {} the same index {} as {}; the later entry is "
                        + "skipped", RESOURCE, characterId, index, clash);
                continue;
            }
            byCharacter.put(characterId, index);
            byIndex.put(index, characterId);
        }
        return new Table(byCharacter, byIndex);
    }

    /** {@return {@code value} as an index, or {@code 0} when it is not an integer} */
    private static int asIndex(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return 0;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            // A string such as "one", or a boolean. Reported by the caller, which knows the key.
            return 0;
        }
    }

    /**
     * The loaded mapping, in both directions.
     *
     * <p>A record over two immutable maps rather than one map and a linear search: the loader needs
     * "character → index" and the resource test wants "index → character" (to check that every authored
     * override has a character behind it). Both are built once, in {@link #parse}.
     *
     * @param byCharacter character id to its {@code custom_model_data} value
     * @param byIndex     that value back to the character, so a duplicate can be refused while loading
     */
    public record Table(Map<ResourceLocation, Integer> byCharacter,
            Map<Integer, ResourceLocation> byIndex) {

        /** The empty table: what a missing or unreadable file degrades to. */
        public static final Table EMPTY = new Table(Map.of(), Map.of());

        public Table {
            byCharacter = byCharacter == null ? Map.of() : Map.copyOf(byCharacter);
            byIndex = byIndex == null ? Map.of() : Map.copyOf(byIndex);
        }

        /**
         * {@return the index of {@code characterId}, or {@code 0} when the file does not list it}
         *
         * @param characterId the character
         */
        public int indexOf(@Nullable ResourceLocation characterId) {
            Integer index = characterId == null ? null : byCharacter.get(characterId);
            return index == null ? 0 : index;
        }

        /** {@return the characters this table lists, in ascending index order} */
        public java.util.List<ResourceLocation> charactersInIndexOrder() {
            return byIndex.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList();
        }

        /** {@return whether every index present is a positive integer} */
        public boolean everyIndexIsPositive() {
            return byIndex.keySet().stream().allMatch(index -> index > 0);
        }
    }
}
