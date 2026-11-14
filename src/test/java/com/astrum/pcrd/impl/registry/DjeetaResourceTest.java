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
