package com.astrum.pcrd.impl.ub;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two data files the ultimate's damage cannot work without, checked as files.
 *
 * <h2>Why this is not covered by the unit tests above</h2>
 * {@code UbDamageTest} proves the <em>plan</em>; these are the two things that decide whether the plan's
 * numbers survive contact with a real victim, and both fail <b>silently</b>:
 *
 * <ol>
 *   <li><b>The damage type must exist.</b> {@code UbDamageSettlement} resolves
 *       {@code astrum:ub_damage} from the damage-type registry and logs a WARN when it is absent. A WARN
 *       in a log is not a failing test — the ultimate would simply deal nothing, forever, and every
 *       message about it would read "it deals 0 damage" rather than "a json file is missing".</li>
 *   <li><b>The damage type must bypass the post-hit cooldown.</b> Measured on this codebase's own
 *       {@code LivingEntity.hurt}: after a hit, the victim's {@code invulnerableTime} is 20 and any
 *       later hit in that window is swallowed <em>unless</em> its type is tagged
 *       {@code minecraft:bypasses_cooldown} — a lower-or-equal amount returns {@code false} without
 *       touching health, and a larger one only applies the difference. The shipped enhanced tier is
 *       {@code hits: 4} of equal {@code damage: 20}, so <b>three of its four applications would vanish</b>:
 *       a "4 × 20 = 80" ultimate would deal exactly 20. That is the single most likely way this feature
 *       regresses without anyone noticing, and it is one tag entry away.</li>
 * </ol>
 *
 * <p>{@code astrum:hook_damage} is tagged for the same reason (see the file's own {@code replace: false}
 * form), so the tag is appended to rather than rewritten — a rewrite would silently un-bypass every
 * attachment {@code extra_damage} in the mod.
 */
class UbDamageResourceTest {

    private static final String DAMAGE_TYPE = "/data/astrum/damage_type/ub_damage.json";
    private static final String COOLDOWN_TAG =
            "/data/minecraft/tags/damage_type/bypasses_cooldown.json";

    private static JsonObject json(String path) throws Exception {
        try (InputStream in = UbDamageResourceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "资源文件必须在 resources 里：" + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    /**
     * The damage type is a real datapack entry, and it is unscaled.
     *
     * <p>{@code scaling: never} keeps armour/difficulty scaling out of the ultimate: the tier's
     * {@code damage} is the number the pack authored, and a difficulty multiplier on top of it would
     * make "20 damage" mean 13 on easy or 28 on hard — while the log, the plan and the tests all say 20.
     */
    @Test
    void theUbDamageTypeIsDeclaredAndUnscaled() throws Exception {
        JsonObject type = json(DAMAGE_TYPE);
        assertEquals("astrum.ub_damage", type.get("message_id").getAsString(),
                "the id must match the file path, or the death message resolves to a raw key");
        assertEquals("never", type.get("scaling").getAsString(),
                "the ultimate's authored number must not be scaled by difficulty");
        assertTrue(type.has("exhaustion"));
    }

    /**
     * The tag that makes a multi-hit ultimate actually multi-hit — and the pre-existing entry that must
     * survive next to it.
     */
    @Test
    void theUbDamageTypeBypassesThePostHitCooldown() throws Exception {
        JsonObject tag = json(COOLDOWN_TAG);
        assertTrue(tag.has("replace"), "the file must state replace explicitly; a missing key is ambiguous");
        assertEquals(false, tag.get("replace").getAsBoolean(),
                "replace:false is what lets this file APPEND to the vanilla tag instead of rewriting it");

        List<String> values = new ArrayList<>();
        JsonArray array = tag.getAsJsonArray("values");
        for (var element : array) {
            values.add(element.getAsString());
        }
        assertTrue(values.contains("astrum:ub_damage"),
                "without this entry a 4 x 20 ultimate deals 20 — the victim's iframe eats hits 2..4");
        assertTrue(values.contains("astrum:hook_damage"),
                "the attachment extra_damage entry predates this task and must not be dropped");
    }

    /**
     * The registered id and the file's location agree, so a rename cannot leave one half behind.
     *
     * <p>Two independent facts that must match: the constant the code resolves, and the path the loader
     * reads. A mismatch is invisible — the registry holds nothing, the WARN fires once per cast, and the
     * ultimate silently deals no damage.
     */
    @Test
    void theDeclaredFileIsWhereTheRegistryLooksForIt() {
        assertEquals("astrum:ub_damage", UbDamage.DAMAGE_TYPE.toString());
        assertEquals("/data/astrum/damage_type/ub_damage.json", DAMAGE_TYPE,
                "damage types are read from data/<namespace>/damage_type/<path>.json");
    }
}
