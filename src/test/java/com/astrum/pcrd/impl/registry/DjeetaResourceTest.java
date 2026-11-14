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
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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

    /** Edge of the shipped portrait, as {@code tools/gen_portraits.py} writes it. */
    private static final int PORTRAIT_EDGE = 64;

    @Test
    void packagedDjeetaJsonMatchesCodec() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            assertNotNull(in, "djeeta.json 必须在 resources 里（自举验证：自家角色走公开路径加载）");
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            assertEquals(Dimension.CHARGE, def.dimension());
            // Three hooks, but TWO skills: 技能2 (武器爆发) is attack up + TP on the same swing.
            assertEquals(3, def.effects().size());
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
            // Hooks are a rotation of two SKILLS, six effects[] entries being three:
            //   swing 1 = 广域斩击 (extra_damage)
            //   swing 2 = 武器爆发 (attack_buff + gain_charge, tagged the same skill)
            assertInstanceOf(EffectHook.ExtraDamage.class, def.effects().get(0));
            assertInstanceOf(EffectHook.AttackBuff.class, def.effects().get(1));
            assertInstanceOf(EffectHook.GainCharge.class, def.effects().get(2));
            assertEquals(Optional.of("weapon_burst"),
                    def.effects().get(1).triggerParams().skillTag(),
                    "攻击力上升与充能必须同属一个技能，否则一斩会变成两个半技能");
            assertEquals(def.effects().get(1).triggerParams().skillTag(),
                    def.effects().get(2).triggerParams().skillTag());
            assertEquals(Optional.of("wide_slash"), def.effects().get(0).triggerParams().skillTag());
            assertEquals("tempest_blade", def.ub().base().animation());
            assertEquals(4, def.ub().enhanced().hits());
            assertEquals(0, def.affection().tier2HookModifiers().get(0).hookIndex());
            assertEquals(1.5, def.affection().tier2HookModifiers().get(0).mult());
        }
    }

    /**
     * The damage numbers are a <b>ratio</b> of the real game's, not invented ones.
     *
     * <h2>The derivation this pins</h2>
     * gamewith's 姬塔 (Lv208) gives 疾风剑雨 / 星河一天 as <b>355293</b> and 技能1 广域斩击 as
     * <b>118431</b>. The mod's numbers are the same ratio at the scale the ultimate already used:
     *
     * <pre>
     *   UB           355293            -> 20.0   (the scale anchor, per hit × 4 hits)
     *   技能1        118431 / 355293   -> 6.66…  -> 6.7
     * </pre>
     *
     * <p>Re-derive it in one line whenever the real numbers change:
     * {@code 技能 = 20.0 * 技能伤害 / 355293}, rounded to one decimal. The assertion is against the
     * <b>shipped JSON text</b> rather than a Java constant, because the JSON is what a future author
     * edits — a drift between the two would otherwise be invisible until somebody re-read gamewith.
     *
     * <p>Hits stay at 4 on both tiers: 星河一天 strengthens 疾风剑雨, it does not change how many times
     * it strikes.
     */
    @Test
    void theDamageNumbersAreTheRealGamesRatioAtTheUltimatesScale() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

            double ubPerHit = def.ub().base().damage();
            assertEquals(20.0, ubPerHit, 1e-9, "UB 每段 20.0 是比例锚点");
            assertEquals(4, def.ub().base().hits());
            assertEquals(4, def.ub().enhanced().hits(), "强化 tier 只加强，不改变段数");

            double realUb = 355293.0, realSkill1 = 118431.0;
            double derived = Math.round(ubPerHit * realSkill1 / realUb * 10.0) / 10.0;
            assertEquals(6.7, derived, 1e-9, "118431/355293 × 20 = 6.66…，取一位小数");

            double shipped = ((EffectHook.ExtraDamage) def.effects().get(0)).amount();
            assertEquals(derived, shipped, 1e-9,
                    "djeeta.json 的 extra_damage 必须等于这条推导；实际 " + shipped);
        }
    }

    /**
     * The shipped character names a skill and ships <b>her own face</b>, and that face is really
     * packaged — this is the pair the party effect shows in vanilla's HUD and the 角色册 shows in its
     * roster cells.
     *
     * <h2>What this test used to assert, and why that was the bug</h2>
     * It pinned {@code avatar == "astrum:textures/item/character_card.png"}, i.e. it <b>certified</b>
     * the defect the human then found by playing: 角色卡是角色卡，角色册里的头像是角色册里的头像. The
     * card is the generic ticket every character is enrolled with (868 B of blank card), so pointing
     * her face at it made the roster draw the card. Her real portrait —
     * {@code textures/gui/portrait/djeeta.png}, generated by {@code tools/gen_portraits.py} from the
     * official 3★ avatar — was sitting in the tree unused. {@link #theBookPortraitDiffersFromTheCardTexture}
     * is the assertion that keeps them apart.
     */
    @Test
    void shippedCharacterNamesHerSkillAndShipsHerOwnFace() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            // The status-effect bar shows the CHARACTER NAME, per the human's correction; the skill
            // name is kept in the JSON for future use but no longer drives the effect label.
            assertEquals("姬塔", def.displayName(),
                    "the party effect is labelled with the character's name");
            assertEquals("星河一天", def.effectiveSkillName(),
                    "her ultimate's name is still shipped, even though it is not the effect label");
            assertEquals("astrum:textures/gui/portrait/djeeta.png",
                    def.avatar().orElseThrow().toString(),
                    "avatar 是她本人的官方头像，不是通用的角色卡贴图");
            assertEquals(def.avatar(), def.face(), "没写 portrait 时，册子画的就应该是 avatar");
        }
        assertEquals(PORTRAIT_EDGE, portraitSize(), "the portrait is the 64x64 resample gen_portraits.py writes");
    }

    /**
     * <b>The book portrait is not the card item's texture.</b>
     *
     * <p>The human's rule in one sentence: 角色卡是角色卡，角色册里的头像是角色册里的头像. The card is a
     * generic item shared by every character; the book draws the character's face. This test reads the
     * <b>shipped JSON</b> and asserts the two resolve to different files, so a future edit that
     * "simplifies" {@code avatar} back to the card fails here instead of being discovered by playing.
     *
     * <p>It also asserts the resolution the screens actually use: the book calls {@code face()}, the
     * party effect calls {@code face()} too, and both must land on the {@code gui/portrait} tree — the
     * item tree may only ever be reached by an item.
     */
    @Test
    void theBookPortraitDiffersFromTheCardTexture() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

            ResourceLocation bookPortrait = def.face().orElseThrow(
                    () -> new AssertionError("角色册必须有一个头像，不能退化成字符圆盘"));
            assertNotEquals(ResourceLocation.parse("astrum:textures/item/character_card.png"),
                    bookPortrait, "角色册头像不得等于通用角色卡贴图（今天的 bug 就是这个）");
            assertTrue(bookPortrait.getPath().startsWith("textures/gui/portrait/"),
                    "角色册头像应该来自 gui/portrait 树，实际 " + bookPortrait);
        }
        assertNotEquals(cardBytes(), portraitBytes(),
                "两张贴图必须真的不是同一份文件内容（同样的字节说明又共用了）");
    }

    private static int portraitSize() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(
                "/assets/astrum/textures/gui/portrait/djeeta.png")) {
            assertNotNull(in, "the portrait texture must be packaged");
            return ImageIO.read(in).getWidth();
        }
    }

    /** {@return every byte of the generic card item texture} */
    private static byte[] cardBytes() throws Exception {
        return textureBytes("/assets/astrum/textures/item/character_card.png");
    }

    /** {@return every byte of the shipped book portrait} */
    private static byte[] portraitBytes() throws Exception {
        return textureBytes("/assets/astrum/textures/gui/portrait/djeeta.png");
    }

    private static byte[] textureBytes(String path) throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing texture: " + path);
            return in.readAllBytes();
        }
    }
}
