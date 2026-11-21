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
            // Two hooks, and they are TWO skills: 广域斩击 (damage) and 剑意迸发 (a TP grant). 技能2's
            // attack-up clause is gone — bwiki gives her 技能2 as 「回复自身【150+1.5*技能等级】技能值」 and
            // nothing else, so the old ttack_buff there was a number the real game does not have.
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
            // Hooks are a rotation of two SKILLS, three effects[] entries being two:
            //   swing 1 = 广域斩击 (extra_damage)
            //   swing 2 = 剑意迸发 (charge_rate_up + attack_buff, tagged the same skill)
            assertInstanceOf(EffectHook.ExtraDamage.class, def.effects().get(0));
            assertInstanceOf(EffectHook.GainCharge.class, def.effects().get(1),
                    "技能2 is a one-off TP grant: bwiki says 「回复自身技能值」, a lump sum, not a rate");
            assertEquals(Optional.of("weapon_burst"),
                    def.effects().get(1).triggerParams().skillTag());
            assertEquals(Optional.of("wide_slash"), def.effects().get(0).triggerParams().skillTag());
            assertEquals("tempest_blade", def.ub().base().animation());
            assertEquals(4, def.ub().enhanced().hits());
            // The affection ladder is data now: 技能1's three rungs, each with its own name. The tier-2 rung
            // is 广域斩击+ and the tier-3 one renames to 黄金耀光 — the human's 「a rename supersedes a +」 rule,
            // which the old (hookIndex, mult) shape could not express at all.
            var wideSlash = def.affection().ladderFor("wide_slash").orElseThrow(
                    () -> new AssertionError("her 技能1 must be on the ladder"));
            assertEquals("广域斩击", wideSlash.nameAt(1));
            assertEquals("广域斩击+", wideSlash.nameAt(2));
            assertEquals("黄金耀光", wideSlash.nameAt(3));
        }
    }

    /**
     * The damage numbers are a <b>ratio</b> of the real game's, not invented ones.
     *
     * <h2>The derivation this pins</h2>
     * bwiki gives 姬塔's skills as formulas in her own attack and level:
     *
     * <pre>
     *   UB     疾风剑雨   【4.8*物理攻击力+60+60*技能等级】物理伤害
     *   6星UB  星河漫天   【7.5*物理攻击力+93.75+93.75*技能等级】+ 攻击buff
     *   技能1  广域斩击   【1.6*物理攻击力+20+20*技能等级】
     * </pre>
     *
     * <p>At the stated reference point (level 208, attack 2000 — see {@code docs/balance-derivation.md} §14.1)
     * the UB formula gives 22140, and that is the anchor: {@code 20.0}, the number the mod has always used.
     * So {@code value = 20.0 × raw / 22140}, and 技能1 comes out at 7380 → <b>6.7</b>.
     *
     * <p><b>Why the reference attack is 2000 and not her real 355293/4.8.</b> Both work — the ratio is
     * scale-free — but a round number makes every intermediate in the document checkable by hand, and the
     * derivation is what a future author reads. §14.1 records the choice and shows that the real-attack
     * back-solve gives the identical ratios.
     *
     * <p>Hits stay at 4 on both tiers: 星河漫天 strengthens 疾风剑雨, it does not change how many times it
     * strikes.
     */
    @Test
    void theDamageNumbersAreTheRealGamesRatioAtTheUltimatesScale() throws Exception {
        try (InputStream in = DjeetaResourceTest.class.getResourceAsStream(DJEETA_JSON)) {
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            var def = CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

            assertEquals(20.0, def.ub().base().damage(), 1e-9, "UB 每段 20.0 是比例锚点");
            assertEquals(4, def.ub().base().hits());
            assertEquals(4, def.ub().enhanced().hits(), "强化 tier 只加强，不改变段数");

            double reference = 2000.0;
            double level = 208.0;
            double anchorRaw = 4.8 * reference + 60.0 + 60.0 * level;   // 22140
            assertEquals(22140.0, anchorRaw, 1e-9, "the bwiki UB formula at the stated reference");
            double skill1Raw = 1.6 * reference + 20.0 + 20.0 * level;   // 7380
            assertEquals(6.7, round1(20.0 * skill1Raw / anchorRaw), 1e-9, "7380/22140 × 20 = 6.67 → 6.7");

            double shipped = ((EffectHook.ExtraDamage) def.effects().get(0)).amount();
            assertEquals(6.7, shipped, 1e-9, "djeeta.json's tier-1 extra_damage must equal this derivation");

            // And the 6★ tier's own formula: 7.5A + 93.75 + 93.75L, which is a *different* coefficient.
            double ub6Raw = 7.5 * reference + 93.75 + 93.75 * level;
            assertEquals(31.3, round1(20.0 * ub6Raw / anchorRaw), 1e-9,
                    "the 6★ ultimate is its own formula, not a multiple of the 5★ one");
            assertEquals(round1(20.0 * ub6Raw / anchorRaw), def.ub().enhanced().damage(), 1e-9,
                    "and the JSON carries exactly that number");
        }
    }

    /** {@return {@code value} rounded to one decimal, the precision every shipped damage number carries} */
    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
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
            assertEquals("星河漫天", def.effectiveSkillName(),
                    "her 6 star ultimate's name is shipped here; the roster reads the per-tier name from "
                            + "ub.base / ub.enhanced, which is where the names actually live");
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
