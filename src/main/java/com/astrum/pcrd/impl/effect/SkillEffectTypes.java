package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.Astrum;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * <b>One {@code MobEffect} type per <em>skill</em>: the (character, skill) identity, its registry id, and the
 * data-driven declaration that makes 91 types exist without a 91-line literal.</b>
 *
 * <h2>The defect this replaced, in the human's words</h2>
 * 「buff给予了后，给予新buff不是覆盖，而是同时存在，给予同一个buff才是刷新持续cd。我只装上雪菲，他只会显示冻结之湖或者雪鞋，
 * 不会两个同时存在。」
 *
 * <p>Vanilla keys a player's effects by effect <b>type</b> ({@code LivingEntity.activeEffects}) and
 * {@code MobEffectInstance.update} merges a second application of the same type. The old family was five
 * inert types, {@code astrum:skill_1..5}, one per party <b>slot</b>, so a character could only ever hold one
 * row: her second skill overwrote her first. Identity now lives in the type — one type per
 * {@code (characterId, skillTag)} — and vanilla's own keying then gives both halves of his sentence for free:
 * <b>two different skills are two types, so they coexist</b>; <b>the same skill re-fired is one type, so
 * vanilla refreshes it</b> rather than stacking.
 *
 * <h2>Where the list comes from, and why it is not written here</h2>
 * {@code DeferredRegister} needs every entry <em>before</em> the registry freezes, i.e. before any datapack
 * (and therefore any character) is read. So the identity set cannot be derived at runtime; it is derived at
 * <b>build</b> time from the same JSON the game loads, by {@code tools/gen_skill_effect_types.py}, and
 * committed as {@code assets/astrum/skill_effect_types.json}. {@link #declared()} reads that manifest, and
 * {@code PartyEffects} registers one holder per entry in a single loop — the data decides, and no list of 91
 * is hand-maintained. {@code SkillEffectTypesTest} re-derives the set from the decoded {@code CharacterDef}s
 * of every shipped JSON and fails if the manifest is stale, so adding a character costs one script run rather
 * than a silent missing row.
 *
 * <p><b>The honest limitation</b>: a character added by a third-party datapack — not shipped in this jar —
 * has no registered type, because its id could not be known before the registry froze. Her skills still fire
 * and her buffs still apply; only her skill <em>rows</em> are missing. Nothing here can fix that: vanilla's
 * mob-effect registry is static and frozen before data packs load.
 *
 * <h2>Identity, and the amplifier that no longer carries it</h2>
 * The old design travelled the skill in the instance's <b>amplifier</b> (a row index into
 * {@code SkillRows}), because one type served five slots and could not say which skill it was. That coupling
 * is gone: the type knows {@link Identity#character()} and {@link Identity#skill()}, so the amplifier is
 * unused and the client asks the type what it is looking at ({@link #identityOf(MobEffect)}) instead of
 * indexing a list. {@code getDescriptionId} is a pure function of the type for the same reason.
 *
 * <h2>No per-type language key</h2>
 * A skill's name is <b>data</b> ({@code SkillLadder.baseName}, the {@code +} rung, the 6★ rename), not a
 * translation, so 91 translation keys could not hold it. The name a player reads comes from the row
 * {@code SkillRows} resolves for {@link Identity#skill()} at her tier; the only key involved is
 * {@link #nameKeyFor(ResourceLocation)}, one per <b>character</b> (22, already in both language files), which
 * is what vanilla's own per-type text path can answer before the client knows her tier. Nothing new is
 * translated for the 91 types.
 *
 * <p>Nothing here touches a registry, so this class and its test run without a game; the manifest is read
 * from the classpath, which is what the jar carries.
 */
@ApiStatus.Internal
public final class SkillEffectTypes {

    /** Registry path prefix: the type of one skill is {@code astrum:skill/<ns>/<character>/<skill>}. */
    public static final String PATH_PREFIX = "skill";

    /**
     * The reserved skill tag that names a character's <b>ultimate</b> row.
     *
     * <p>The same string {@code AffectionKernel.ubKey()} answers, and for the same reason: the ultimate is not
     * a hook, so it has no {@code triggerParams.skill} of its own, and the row it writes needs one identity.
     * Duplicated as a literal rather than imported because that would drag {@code impl.growth} — and through
     * it the whole codec tree — into a class that is also loaded on the client's effect-render path;
     * {@code SkillEffectTypesTest} asserts the two strings are one.
     */
    public static final String UB_SKILL = "ub";

    /**
     * The <b>one</b> translation key a row falls back to when the character is not known at all.
     *
     * <p>「技能效果」 — no slot number and no level in it. The old {@code effect.astrum.skill_1..5} keys are
     * gone with the per-slot family: they carried a slot digit, and vanilla appended its own effect-level
     * suffix to them, which described our row index as if it were a strength (「技能效果 1 二级」).
     */
    public static final String FALLBACK_NAME_KEY = "astrum.effect.skill_row";

    /** One skill of one character — the whole identity a skill-entry type carries. */
    public record Identity(ResourceLocation character, String skill) {

        public Identity {
            if (character == null) {
                throw new IllegalArgumentException("a skill identity needs a character");
            }
            if (skill == null || skill.isBlank()) {
                throw new IllegalArgumentException("a skill identity needs a skill tag, got " + skill);
            }
            // The registry path joins the two with "/", so neither part may contain one: an identity that did
            // would produce a path this class could not parse back, and the row would silently stop resolving.
            // The generator refuses the same shapes (tools/gen_skill_effect_types.py, SEGMENT), so a pack that
            // hits this is one the build already rejected.
            if (character.getPath().indexOf('/') >= 0 || skill.indexOf('/') >= 0) {
                throw new IllegalArgumentException(
                        "a skill identity cannot contain '/': " + character + " / " + skill);
            }
        }

        /** {@return the registry id of this type} */
        public ResourceLocation id() {
            return idOf(this);
        }

        /** {@return the registry path of this type, which is also its sprite path under {@code mob_effect/}} */
        public String path() {
            return id().getPath();
        }

        /** {@return how a log line names this type: {@code astrum:sheffy/snow_boots}} */
        public String label() {
            return character + "/" + skill;
        }
    }

    private SkillEffectTypes() {
    }

    // ------------------------------------------------------------------------------------------------
    // The declaration, read from the manifest the build writes
    // ------------------------------------------------------------------------------------------------

    /**
     * Every declared type, in the manifest's order (character id, then the character's own skill order).
     *
     * <p>A static container, and deliberately the <b>only</b> one this class holds: the set comes from the
     * pack's data and cannot be a compile-time constant, and 91 individual fields would be the literal list
     * this design exists to avoid. It is immutable and built once at class-init.
     */
    /**
     * The skill tags that are <b>personal markers</b>: their row wears the character's portrait rather than a
     * dedicated icon.
     *
     * <h2>Why a list and not a rule</h2>
     * 「冰龙之印这种类型的效果使用角色头像」 — decided per skill, by the human, not derivable from the data. A
     * marker skill's content is a tally that belongs to her alone, so a face says "this is 雪菲's mark" where a
     * generic icon says nothing. It is spelled out here so {@code SkillArtTest} can tell "a marker, on purpose"
     * from "somebody forgot the icon", which are indistinguishable if you only look at the field being absent.
     *
     * <p><b>One entry today.</b> 雪菲's 冰龙之印 is the only shipped skill of this kind; 矛依未's SP1/SP2 are
     * attacks and 怜's 风之刃 is a state the UB grants rather than a skill of its own. Adding a character with a
     * marker skill means adding her tag here, and {@code SkillArtTest} will then require her to carry no icon.
     */
    private static final java.util.Set<String> MARKER_SKILLS = java.util.Set.of("sp1");

    /** {@return whether {@code skill}'s row is a personal marker and wears her portrait instead of an icon} */
    public static boolean isMarkerSkill(@Nullable String skill) {
        return skill != null && MARKER_SKILLS.contains(skill);
    }

    private static final List<Identity> DECLARED = load();

    /** {@return every declared type, in manifest order; immutable} */
    public static List<Identity> declared() {
        return DECLARED;
    }

    // ------------------------------------------------------------------------------------------------
    // The paths
    // ------------------------------------------------------------------------------------------------

    /** {@return the registry id of {@code identity}: {@code astrum:skill/<character ns>/<character>/<skill>}} */
    public static ResourceLocation idOf(Identity identity) {
        if (identity == null) {
            throw new IllegalArgumentException("no identity");
        }
        return ResourceLocation.fromNamespaceAndPath(Astrum.MOD_ID,
                PATH_PREFIX + "/" + identity.character().getNamespace() + "/"
                        + identity.character().getPath() + "/" + identity.skill());
    }

    /** {@return the identity a skill-entry registry id names, empty for anything else} */
    public static Optional<Identity> identityOf(@Nullable ResourceLocation id) {
        if (id == null || !Astrum.MOD_ID.equals(id.getNamespace())) {
            return Optional.empty();
        }
        String[] parts = id.getPath().split("/");
        // skill / <character namespace> / <character path> / <skill tag>. Strict about the round trip, like
        // PartyEffectSlots.slotOf: a path that merely starts with "skill" is not one of ours, and a blank
        // segment is rejected rather than accepted because "skill/astrum//snow_boots" would rebuild itself
        // character for character (an empty character path) and pass a round-trip-only check.
        if (parts.length != 4 || !PATH_PREFIX.equals(parts[0])
                || parts[1].isEmpty() || parts[2].isEmpty() || parts[3].isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation character;
        try {
            character = ResourceLocation.fromNamespaceAndPath(parts[1], parts[2]);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        Identity identity;
        try {
            identity = new Identity(character, parts[3]);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return idOf(identity).equals(id) ? Optional.of(identity) : Optional.empty();
    }

    /**
     * {@return the identity of a <b>registered</b> skill-entry effect, empty for every other effect}
     *
     * <p>Answered by {@code instanceof}, not by scanning the registry: the type <b>is</b> the identity, so a
     * cast is exact and costs nothing — which is what lets the client ask it about a hovered instance on a
     * render path. The old design had to loop over five holders and then read an amplifier out of the
     * instance; both are gone.
     *
     * @param effect the effect to classify, may be {@code null}
     */
    public static Optional<Identity> identityOf(@Nullable MobEffect effect) {
        return effect instanceof AstrumSkillEffect skill ? Optional.of(skill.identity()) : Optional.empty();
    }

    /**
     * {@return the translation key vanilla's own text paths resolve this type through}
     *
     * <p>One key per <b>character</b>, {@code astrum.effect.skill_row.<character path>} — the 22 keys both
     * language files already carry. A per-type key is impossible in the sense that matters: the skill's name
     * is data (a ladder that renames at 6★), so a translation cannot hold it. JEED calls
     * {@code getDescriptionId} directly and cannot see the instance, so this is the best a type-level answer
     * can say, and it at least names <em>her</em>. The exact name comes from {@link AstrumSkillEffect
     * #getDisplayName} and the tooltip, which resolve the row.
     *
     * @param character the character whose row this is
     */
    public static String nameKeyFor(ResourceLocation character) {
        return FALLBACK_NAME_KEY + "." + character.getPath();
    }

    /** {@return every fallback key this registration can produce, for {@code LangParityTest}} */
    public static Set<String> langKeys() {
        Set<String> keys = new LinkedHashSet<>();
        keys.add(FALLBACK_NAME_KEY);
        for (Identity identity : DECLARED) {
            keys.add(nameKeyFor(identity.character()));
        }
        return Set.copyOf(keys);
    }

    // ------------------------------------------------------------------------------------------------
    // The manifest reader
    // ------------------------------------------------------------------------------------------------

    /** Where the build's generator writes the declaration; read from the mod's own classpath. */
    private static final String MANIFEST = "/assets/astrum/skill_effect_types.json";

    /**
     * {@return the declaration, or an empty list when the manifest cannot be read}
     *
     * <h2>Why this does not throw</h2>
     * It runs in a static initialiser. A jar whose manifest was stripped is already broken, and throwing here
     * would take the whole mod down at class-load — including every unrelated feature. So a failure is an
     * ERROR line naming the resource and the consequence (「no skill row will appear」), and the empty answer
     * makes the failure visible rather than fatal. {@code SkillEffectTypesTest} is the build-time guard: it
     * asserts the real count against the character data, so a stripped manifest fails the build instead.
     */
    private static List<Identity> load() {
        try (InputStream stream = SkillEffectTypes.class.getResourceAsStream(MANIFEST)) {
            if (stream == null) {
                Astrum.LOGGER.error("astrum: {} is not on the classpath, so no skill effect type is registered "
                        + "and no skill row will appear. Rerun tools/gen_skill_effect_types.py", MANIFEST);
                return List.of();
            }
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            JsonArray types = parsed.getAsJsonObject().getAsJsonArray("types");
            if (types == null) {
                Astrum.LOGGER.error("astrum: {} has no 'types' array; no skill row will appear", MANIFEST);
                return List.of();
            }
            List<Identity> identities = new ArrayList<>(types.size());
            Set<ResourceLocation> ids = new LinkedHashSet<>();
            for (JsonElement element : types) {
                JsonObject entry = element.getAsJsonObject();
                Identity identity;
                try {
                    identity = new Identity(
                            ResourceLocation.parse(entry.get("character").getAsString()),
                            entry.get("skill").getAsString());
                } catch (RuntimeException e) {
                    Astrum.LOGGER.error("astrum: {} has an unreadable entry, skipped: {}", MANIFEST, entry, e);
                    continue;
                }
                if (!ids.add(idOf(identity))) {
                    Astrum.LOGGER.error("astrum: {} declares {} twice; the second one is ignored",
                            MANIFEST, identity.label());
                    continue;
                }
                identities.add(identity);
            }
            Astrum.LOGGER.info("astrum: {} skill effect types declared from {}", identities.size(), MANIFEST);
            return List.copyOf(identities);
        } catch (RuntimeException | java.io.IOException e) {
            Astrum.LOGGER.error("astrum: could not read {}, so no skill row will appear", MANIFEST, e);
            return List.of();
        }
    }
}