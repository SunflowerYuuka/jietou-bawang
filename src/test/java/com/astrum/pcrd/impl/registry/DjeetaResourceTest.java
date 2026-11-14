package com.astrum.pcrd.impl.registry;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.Dimension;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bootstrap validation: the character this mod ships must load through the public datapack path.
 *
 * <p>The path is not the spec's {@code data/astrum/character/}: NeoForge puts the registry key's own
 * namespace into the directory, so {@code astrum:character} is read from
 * {@code data/<pack namespace>/astrum/character/}. {@link Registries#elementsDirPath} is the
 * function the loader itself uses, so the directory is derived from it rather than hard-coded —
 * that keeps this test honest if {@link RegistryKeys#CHARACTERS} ever changes.
 */
class DjeetaResourceTest {

    private static final String DJEETA_JSON = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS)
            + "/djeeta.json";

    @Test
    void packagedDjeetaJsonMatchesCodec() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            assertNotNull(in, "djeeta.json 必须在 resources 里（自举验证：自家角色走公开路径加载）");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(Dimension.CHARGE, def.dimension());
            // Two rotating small skills now: 广域斩击 and 剑意迸发. She has no splash.
            assertEquals(2, def.effects().size());
            assertEquals(3, def.affection().tier2Threshold());
            assertEquals(8, def.affection().tier3Threshold());
        }
    }

    @Test
    void shippedJsonSitsWhereTheRegistryLoaderLooks() {
        // Guards the failure this test suite caught by accident: a file at data/astrum/character/
        // is silently never read, and the registry comes up empty with no warning at all.
        assertEquals("/data/astrum/astrum/character/djeeta.json", DJEETA_JSON);
        assertNotNull(DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON),
                "角色 JSON 必须放在 NeoForge 实际读取的目录，否则注册表静默为空");
    }

    @Test
    void shippedJsonUsesTheDocumentedHookShape() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            // Hooks are a rotation: swing 1 runs 广域斩击 (single-target damage), swing 2 runs
            // 剑意迸发 (self TP). Order matters because the cursor walks the list.
            assertInstanceOf(EffectHook.ExtraDamage.class, def.effects().get(0));
            assertInstanceOf(EffectHook.GainCharge.class, def.effects().get(1));
            assertEquals("tempest_blade", def.ub().base().animation());
            assertEquals(4, def.ub().enhanced().hits());
            assertEquals(0, def.affection().tier2HookModifiers().get(0).hookIndex());
            assertEquals(1.5, def.affection().tier2HookModifiers().get(0).mult());
        }
    }

    /**
     * The shipped character names a skill and the official avatar, and that avatar is really packaged
     * — this is the pair the party effect shows in vanilla's HUD, so a missing texture here would be
     * the purple checker as a buff icon.
     */
    @Test
    void shippedCharacterNamesHerSkillAndShipsHerAvatar() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            // The status-effect bar shows the CHARACTER NAME, per the human's correction; the skill
            // name is kept in the JSON for future use but no longer drives the effect label.
            assertEquals("姬塔", def.displayName(),
                    "the party effect is labelled with the character's name");
            assertEquals("星河一天", def.effectiveSkillName(),
                    "her ultimate's name is still shipped, even though it is not the effect label");
            assertEquals("astrum:textures/item/character_card.png", def.avatar().orElseThrow().toString());
        }
        assertEquals(32, avatarSize(), "the card/avatar texture is the official 32x32 resample");
    }

    private static int avatarSize() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(
                "/assets/astrum/textures/item/character_card.png")) {
            assertNotNull(in, "the avatar texture must be packaged");
            var image = ImageIO.read(in);
            return image.getWidth();
        }
    }
}
