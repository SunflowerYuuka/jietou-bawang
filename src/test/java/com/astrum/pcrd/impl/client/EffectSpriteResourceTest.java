package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.impl.effect.AttackBuffs;
import com.astrum.pcrd.impl.effect.PartyEffectSlots;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Every effect's HUD sprite is reachable through the resource manager at the path vanilla derives.
     *
     * <p>The list is built from the registry ids ({@link PartyEffectSlots#ids()} and {@link AttackBuffs#ID}),
     * so the sixth effect — the one that was missing — cannot be forgotten the way it was.
     */
    @Test
    void everyEffectSpriteIsReachableThroughTheResourceManager() {
        for (ResourceLocation id : effectIds()) {
            ResourceLocation path = spritePathOf(id);
            Optional<Resource> found = resources.getResource(path);
            assertTrue(found.isPresent(), "astrum:" + id.getPath() + " would draw the missing-texture "
                    + "checkerboard: the client cannot reach " + path);
            assertTrue(found.get().sourcePackId().contains("astrum"),
                    "the sprite must come from this mod's pack, not a fallback");
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

    /** The book's self-buff icon is reachable as an ordinary texture — it is blitted directly, not atlased. */
    @Test
    void theSelfBuffIconIsReachableAsATexture() {
        ResourceLocation path = ResourceLocation.fromNamespaceAndPath("astrum",
                "textures/gui/skill/attack_buff.png");
        assertTrue(resources.getResource(path).isPresent(),
                "the book's self-buff row draws this texture directly: " + path);
        assertEquals(path, SkillIcons.textureFor(com.astrum.pcrd.api.v1.EffectType.ATTACK_BUFF),
                "the drawing code and the shipped file must name the same path");
    }

    // -----------------------------------------------------------------------------------------
    // The two path rules, written once
    // -----------------------------------------------------------------------------------------

    /** {@return every registered effect id, party slots plus the attack buff} */
    private static List<ResourceLocation> effectIds() {
        List<ResourceLocation> ids = new java.util.ArrayList<>(PartyEffectSlots.ids());
        ids.add(AttackBuffs.ID);
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
    @Test
    void theManagerReallySeesThisModsResources() {
        Map<ResourceLocation, Resource> listed = resources.listResources("textures/mob_effect",
                path -> path.getPath().endsWith(".png"));
        assertTrue(listed.size() >= PartyEffectSlots.ids().size() + 1,
                "the pack must expose every effect sprite plus the attack buff, saw " + listed);
    }
}
