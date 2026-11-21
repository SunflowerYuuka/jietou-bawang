package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.effect.AttackBuffs;
import com.astrum.pcrd.impl.effect.PartyEffectSlots;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The effect HUD's icon resolves at runtime</b>, asked the way the client asks.
 *
 * <h2>Why a classpath check was not enough</h2>
 * {@code SkillIconsTest} proves the PNG is on the classpath at
 * {@code assets/astrum/textures/mob_effect/attack_buff.png}. That is necessary and not sufficient: the client
 * does not open that path as a classpath resource. It goes through the {@code ResourceManager} (which sees the
 * mod's resources <em>as a pack</em>, and would silently answer empty for a file the mod did not declare in
 * {@code pack.mcmeta}) and then through the {@code mob_effects} atlas, which indexes
 * {@code textures/mob_effect/<name>.png} as sprite {@code <namespace>:<name>} — see
 * {@code DirectoryLister}/{@code FileToIdConverter} and {@code assets/minecraft/atlases/mob_effects.json}.
 * A file that is in the jar but not reachable that way still renders as the purple checkerboard, which is the
 * exact defect the human photographed.
 *
 * <h2>So this opens the real pack</h2>
 * A {@link MultiPackResourceManager} over {@code src/main/resources} — the actual mod resource tree, not a
 * fixture — and asks for the path vanilla will ask for, built from the <b>registry id</b> so a rename cannot
 * silently pass. {@code getResource} rather than {@code getResourceStack}: one answer is enough and it is the
 * one the texture loader takes.
 *
 * <p><b>What this cannot prove.</b> It does not open a GL context, so it proves the resource is reachable and
 * decodable, not that it stitches into an atlas without a seam. Only a client run shows that, and the report
 * says so.
 */
class EffectSpriteResourceTest {

    /** The mod's own resource tree: what ships inside the jar, read as a pack. */
    private static MultiPackResourceManager resources;

    @BeforeAll
    static void openTheModPack() throws Exception {
        Path root = packRoot();
        PackLocationInfo location = new PackLocationInfo("astrum",
                net.minecraft.network.chat.Component.literal("astrum resources"),
                net.minecraft.server.packs.repository.PackSource.DEFAULT, Optional.empty());
        resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                List.of(new PathPackResources(location, root)));
    }

    /**
     * {@return the directory that holds this mod's packaged resources}
     *
     * <h2>Why this is found rather than assumed</h2>
     * The first version hard-coded {@code src/main/resources} relative to the working directory, and it failed
     * its own precondition: the unit-test JVM does not run with the repository as its working directory, so the
     * "is this the repo root" guard fired before any assertion about the sprite. A test that only passes when
     * run from one particular directory is a test that will be run from somewhere else.
     *
     * <p>So the root is read from the classpath: {@code /pack.mcmeta} is packaged at the root of exactly the
     * resource tree the jar carries, and its parent is the pack directory. That also makes the assertion
     * sharper — this opens the resource tree the build produces, not a source directory beside it.
     */
    private static Path packRoot() throws Exception {
        URL meta = EffectSpriteResourceTest.class.getResource("/pack.mcmeta");
        assertNotNull(meta, "the mod's pack.mcmeta must be on the test classpath");
        Path root = Path.of(meta.toURI()).getParent();
        assertTrue(Files.isDirectory(root.resolve("assets/astrum/textures/mob_effect")),
                "the pack root must be the resource tree; looked in " + root.toAbsolutePath());
        return root;
    }

    @AfterAll
    static void closeThePack() {
        if (resources != null) {
            resources.close();
        }
    }


    /**
     * The atlas lists it under the id the effect HUD looks up.
     *
     * <p>{@code DirectoryLister} turns every {@code textures/mob_effect/<name>.png} into the sprite
     * {@code <namespace>:<name>}, and {@code MobEffectTextureManager.get} asks for exactly the effect's registry
     * id. So the sprite id is the effect id — this asserts the two spellings agree, which is the pair that broke:
     * the file was absent, and nothing tied "which sprite the atlas has" to "which id the effect registers".
     */
    @Test
    void theAtlasSpriteIdIsTheEffectId() {
        for (ResourceLocation id : effectIds()) {
            assertEquals(id, spriteIdOf(spritePathOf(id)),
                    "the sprite the atlas builds must be the id the effect HUD asks for");
        }
    }

    /** The 6★ still, and the 3★ one, are reachable too — a tier swap must not land on a missing file. */
    @Test
    void bothStillRungsAreReachable() {
        for (String rung : new String[] {"3", "6"}) {
            ResourceLocation path = ResourceLocation.fromNamespaceAndPath("astrum",
                    "textures/gui/still/djeeta_" + rung + ".png");
            assertTrue(resources.getResource(path).isPresent(),
                    "the tier-" + rung + " still must be in the pack, or the panel silently keeps the old "
                            + "picture: " + path);
        }
    }


    /**
     * <b>The 64x64 attack-buff sprite is what vanilla's atlas config resolves, at 64x64.</b>
     *
     * <h2>Why reading the PNG was not enough</h2>
     * The human asked for a sharper effect icon (「药水效果栏的图标，分辨率有点低了」). Raising the file from
     * 18x18 to 64x64 is only worth something if the atlas accepts it — a pipeline that rescaled sprites or
     * rejected non-conforming sizes would make the larger file either useless or a crash, and the picture
     * would look the same.
     *
     * <h2>What is actually run, and the one step that cannot be</h2>
     * This mounts the <b>real</b> packs — vanilla's {@code client-extra} jar (which carries
     * {@code assets/minecraft/atlases/mob_effects.json}) plus this mod's {@code src/main/resources} — and
     * runs vanilla's own {@link SpriteSourceList#load} over that atlas id, then the same
     * {@code DirectoryLister.run} the client performs. That yields the exact
     * {@code astrum:attack_buff} → file mapping the client will use, and the file it names is decoded here
     * and asserted to be 64x64.
     *
     * <p>The final GPU step ({@code SpriteLoader.stitch}, which decodes every sprite into a
     * {@code NativeImage} and packs them) is <b>not</b> run: it needs LWJGL's native libraries, and this
     * JVM is a plain unit-test process with no {@code lwjgl.dll} ({@code UnsatisfiedLinkError}). That step
     * is size-agnostic anyway — {@code SpriteLoader.stitch} builds one {@code Stitcher} from
     * {@code maxSupportedTextureSize} and neither resizes nor rejects a sprite, which is read from its
     * bytecode — so the atlas mapping is the part that could have gone wrong, and it is asserted.
     */
    /**
     * {@return vanilla's resource pack, then this mod's — the order the game uses}
     *
     * <p>Vanilla comes from the {@code client-extra} jar the build already downloaded (it carries
     * {@code assets/minecraft/atlases/mob_effects.json}) via {@link VanillaPackResourcesBuilder}
     * {@code pushJarResources}. The mod pack is the same {@code src/main/resources} tree the other tests
     * open, resolved through {@code /pack.mcmeta} rather than a hard-coded path.
     */
    private static List<net.minecraft.server.packs.PackResources> vanillaAndModPacks() {
        net.minecraft.server.packs.VanillaPackResources vanilla =
                new net.minecraft.server.packs.VanillaPackResourcesBuilder()
                        .pushJarResources()
                        .exposeNamespace("minecraft")
                        .build(new PackLocationInfo("vanilla",
                                net.minecraft.network.chat.Component.literal("vanilla"),
                                net.minecraft.server.packs.repository.PackSource.DEFAULT,
                                Optional.empty()));
        PackLocationInfo location = new PackLocationInfo("astrum",
                net.minecraft.network.chat.Component.literal("astrum resources"),
                net.minecraft.server.packs.repository.PackSource.DEFAULT, Optional.empty());
        return List.of(vanilla, new PathPackResources(location, packRootUnchecked()));
    }

    /** {@return {@link #packRoot()} without the checked exception, for a record initialiser} */
    private static Path packRootUnchecked() {
        try {
            return packRoot();
        } catch (Exception e) {
            throw new IllegalStateException("the mod's resource tree must be on the test classpath", e);
        }
    }

    // -----------------------------------------------------------------------------------------
    // The two path rules, written once
    // -----------------------------------------------------------------------------------------

    /**
     * {@return every registered effect id: the five party runes, the mechanic effects, and every skill type}
     *
     * <p>Built from the registries ({@link PartyEffectSlots#ids()}, {@code PartyEffects.mechanicHolders()} and
     * {@code PartyEffects.skillHolders()}) rather than from a literal list, so a newly registered effect cannot
     * be forgotten the way the seventh one was.
     *
     * <p>The 91 skill types joined this list in task 59. The extension still draws the <em>pack's</em> skill icon
     * on the HUD, but the atlas is what JEI and every other sprite consumer asks, and a type with no file there
     * is the magenta checkerboard — so each one ships a self-drawn placeholder at
     * {@code textures/mob_effect/skill/<character>/<skill>.png}. See {@code SkillEffectTypes} for why the jar and
     * the pack carry different pictures for the same row.
     */
    private static List<ResourceLocation> effectIds() {
        List<ResourceLocation> ids = new java.util.ArrayList<>(PartyEffectSlots.ids());
        for (var holder : com.astrum.pcrd.impl.effect.PartyEffects.mechanicHolders()) {
            ids.add(holder.getKey().location());
        }
        for (var holder : com.astrum.pcrd.impl.effect.PartyEffects.skillHolders()) {
            ids.add(holder.getKey().location());
        }
        assertFalse(ids.isEmpty());
        return ids;
    }

    /**
     * {@return the file the client samples for {@code effectId}}
     *
     * <p>Vanilla's rule, from {@code MobEffectTextureManager}: the effect's registry id is the sprite's id, and
     * the {@code mob_effects} atlas maps the sprite id {@code <ns>:<path>} to
     * {@code assets/<ns>/textures/mob_effect/<path>.png}.
     */
    private static ResourceLocation spritePathOf(ResourceLocation effectId) {
        return ResourceLocation.fromNamespaceAndPath(effectId.getNamespace(),
                "textures/mob_effect/" + effectId.getPath() + ".png");
    }

    /** {@return the sprite id {@code DirectoryLister} builds from a texture path} */
    private static ResourceLocation spriteIdOf(ResourceLocation texturePath) {
        String path = texturePath.getPath();
        String prefix = "textures/mob_effect/";
        assertTrue(path.startsWith(prefix) && path.endsWith(".png"), "not a mob_effect texture: " + path);
        return ResourceLocation.fromNamespaceAndPath(texturePath.getNamespace(),
                path.substring(prefix.length(), path.length() - ".png".length()));
    }

    /** Guard against a fixture that would pass on an empty pack: the manager must see more than one file. */
    /**
     * The party-slot runes are still packaged. They are the one {@code mob_effect} family this mod still
     * ships: a generic effect stopped having a picture ("通用效果现在不需要图标，只作为代码引用"), but the row
     * above the UB gauge still draws a rune per slot when a character has no face art, so those five files
     * must stay.
     */
    @Test
    void thePartySlotRunesAreStillPackaged() {
        Map<ResourceLocation, Resource> listed = resources.listResources("textures/mob_effect",
                path -> path.getPath().endsWith(".png"));
        for (ResourceLocation id : PartyEffectSlots.ids()) {
            String path = "textures/mob_effect/" + id.getPath() + ".png";
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
            assertTrue(listed.containsKey(key),
                    "the party slot rune " + path + " must stay packaged; saw " + listed.keySet());
        }
    }

    /**
     * <b>Every skill type has a placeholder sprite in the pack, at the path its registry id names.</b>
     *
     * <p>The 91 types are nested one directory deeper than every other effect
     * ({@code textures/mob_effect/skill/<character>/<skill>.png}), which is the part that could plausibly go
     * wrong: vanilla's {@code DirectoryLister} walks the directory recursively and builds the sprite id from the
     * relative path, so a nested file <em>is</em> addressable — but only if the file is really there. A missing
     * one is the checkerboard in JEI, and the extension that draws the pack's art never touches this sprite, so
     * nothing else in the build would notice.
     *
     * <p>The same {@code listResources} call the client's pack stack makes, over the real resource tree. What it
     * cannot prove is the GL stitch (no {@code lwjgl} in this JVM), which the class javadoc already states.
     */
    @Test
    void everySkillTypeHasAPlaceholderInThePack() {
        Map<ResourceLocation, Resource> listed = resources.listResources("textures/mob_effect",
                path -> path.getPath().endsWith(".png"));
        List<ResourceLocation> missing = new java.util.ArrayList<>();
        int checked = 0;
        for (var holder : com.astrum.pcrd.impl.effect.PartyEffects.skillHolders()) {
            ResourceLocation id = holder.getKey().location();
            String path = "textures/mob_effect/" + id.getPath() + ".png";
            ResourceLocation key = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
            if (!listed.containsKey(key)) {
                missing.add(key);
            }
            checked++;
        }
        assertEquals(List.of(), missing,
                "every skill type needs a placeholder at textures/mob_effect/<path>.png, or the atlas draws the "
                        + "missing-texture checkerboard; run tools/gen_skill_effect_types.py");
        assertEquals(91, checked, "one placeholder per type: 22 characters x (their skills + their UB)");
    }

}
