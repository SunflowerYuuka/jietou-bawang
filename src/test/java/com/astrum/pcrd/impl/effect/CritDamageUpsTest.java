package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.UbEffect;
import com.astrum.pcrd.api.v1.UbTier;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「新增效果类型，修复。」: 千歌's timed crit-damage buff as a real effect type</b>, not a sentence in {@code extra}.
 *
 * <h2>What this file pins</h2>
 * <ol>
 *   <li>The <b>percent-to-amplifier encoding</b>. A {@code MobEffect} declares its modifier once, on the type, and
 *       the only per-instance knob is the byte-sized amplifier — so {@code 28%} has to survive the round trip
 *       through an {@code int}. The registered curve is {@code fractionFor(amplifier)}: one amplifier point is one
 *       percent, linear, so {@code 28 → 0.28}.</li>
 *   <li>The <b>number the player ends up with</b>: {@code ADD_MULTIPLIED_TOTAL} on a {@code 1.5} crit damage
 *       attribute, so {@code +28%} is {@code 1.92} rather than {@code 1.78} (which {@code ADD_VALUE} would give)
 *       and rather than ignoring another crit source ({@code ADD_MULTIPLIED_BASE}).</li>
 *   <li>The <b>shipped data</b>: 千歌's 6★ ultimate really carries the hook, at the wiki's own numbers, and the
 *       {@code extra} sentence that recorded the gap is <b>gone</b> — the brief is explicit that a closed gap must
 *       be deleted, because {@code extra} renders as 「尚未实现」.</li>
 * </ol>
 *
 * <p>What a unit test cannot do is apply the effect to a live player and watch the modifier appear and disappear at
 * the 18-second mark: that needs a server, and it is on the "only a human can confirm" list in
 * {@code task-58-report.md}. What is asserted instead is that the class constructs (so its
 * {@code addAttributeModifier} call with the curve is valid), that the modifier id is a constant distinct from
 * every other effect's, and that the arithmetic the curve feeds is exact.
 */
class CritDamageUpsTest {

    private static CharacterDef load(String id) throws Exception {
        try (InputStream in = CritDamageUpsTest.class.getResourceAsStream(jsonPath(id))) {
            assertNotNull(in, id + ".json must be packaged at " + jsonPath(id));
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        }
    }

    private static String jsonPath(String id) {
        return "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + id + ".json";
    }

    // ------------------------------------------------------------------ the encoding

    /**
     * <b>One amplifier point is one percent, linearly.</b>
     *
     * <p>The registered effect uses NeoForge's level-curve overload, so the fraction is exactly
     * {@code amplifier × 0.01} — not the {@code amount × (amplifier + 1)} a plain template would produce. That is
     * what makes 28 mean 28%.
     */
    @Test
    void theAmplifierIsThePercent() {
        assertEquals(0.28, CritDamageUps.fractionFor(28), 1e-12);
        assertEquals(28, CritDamageUps.amplifierFor(28.0));
        assertEquals(0, CritDamageUps.fractionFor(0), 1e-12, "amplifier 0 is +0%, the no-op");
        assertEquals(1.0, CritDamageUps.fractionFor(100), 1e-12, "100 amplifier points is +100%");
        assertEquals(2.55, CritDamageUps.fractionFor(255), 1e-12, "and 255 is the largest a byte can hold");
        assertEquals(255, CritDamageUps.amplifierFor(1000.0), "a hostile percent clamps to the amplifier ceiling");
        assertEquals(0, CritDamageUps.amplifierFor(-5.0), "a negative increase is not a buff");
        assertEquals(0, CritDamageUps.amplifierFor(Double.NaN), "a broken pack value is a logged no-op");
        assertEquals(0, CritDamageUps.amplifierFor(Double.POSITIVE_INFINITY));
    }

    /**
     * <b>A whole percent survives the round trip exactly.</b>
     *
     * <p>The amplifier's resolution is one percent, so that is the granularity the data must be written at — and
     * every shipped clause is: 千歌's 28 is a whole number. The tolerance check is {@code AttackBuffRules}' shape
     * (within half a step) and therefore only ever refuses a value that quantises to <b>no</b> increase or to a
     * number outside the byte, which is what makes a hostile pack a WARN rather than a silently different buff.
     */
    @Test
    void aWholePercentIsRepresentable() {
        for (int percent = 1; percent <= 100; percent++) {
            assertTrue(CritDamageUps.isRepresentable(percent),
                    percent + "% must round-trip exactly through the amplifier");
            assertEquals(percent / 100.0, CritDamageUps.fractionFor(CritDamageUps.amplifierFor(percent)), 1e-12);
        }
        assertTrue(CritDamageUps.isRepresentable(28.0), "千歌's own number");
        assertFalse(CritDamageUps.isRepresentable(0.0), "no increase is not a buff");
        assertFalse(CritDamageUps.isRepresentable(-1.0));
        // Below one percent there is no amplifier to hold it: the increase would be granted at +0%.
        assertEquals(0, CritDamageUps.amplifierFor(0.4),
                "a sub-percent increase is not expressible, so it quantises to no grant");
        assertEquals(0.0, CritDamageUps.fractionFor(CritDamageUps.amplifierFor(0.4)), 1e-12);
    }

    /**
     * <b>The number the player ends up with: 1.5 → 1.92.</b>
     *
     * <p>{@code ADD_MULTIPLIED_TOTAL} makes the modifier a fraction of the attribute's computed total, so the
     * shipped default crit multiplier (1.5) becomes {@code 1.5 × 1.28 = 1.92}. Read through the same
     * {@link Crit#DEFAULT_CRIT_DAMAGE} the attribute is registered with, so the two numbers cannot drift.
     */
    @Test
    void theBuffMultipliesTheCritDamageAttribute() {
        double shipped = Crit.DEFAULT_CRIT_DAMAGE;
        assertEquals(1.92, shipped * (1.0 + CritDamageUps.fractionFor(28)), 1e-12,
                "+28% of a 1.5 crit multiplier is 1.92");
        // ... and the replacement is not an addition of absolute multiplier points, which is the reading the
        // channel must not have: 1.5 + 0.28 would be 1.78, a smaller buff than the source describes.
        assertTrue(1.92 > shipped + 0.28);
    }

    /** The effect class itself: constructible headlessly, BENEFICIAL, and under its own modifier id. */
    @Test
    void theEffectDeclaresOneModifierUnderItsOwnId() {
        assertEquals(ResourceLocation.parse("astrum:crit_damage_up"), CritDamageUps.ID);
        assertEquals(ResourceLocation.parse("astrum:crit_damage_up"), CritDamageUpEffect.MODIFIER_ID);
        assertEquals("effect.astrum.crit_damage_up", CritDamageUps.NAME_KEY);
        assertFalse(CritDamageUpEffect.MODIFIER_ID.equals(AttackBuffEffect.MODIFIER_ID),
                "a shared id would make the crit buff and the attack buff cancel each other");
        // Constructible with no registry: the constructor's addAttributeModifier(...) call must not throw, which is
        // what a client-side or test-time construction of the effect would do.
        var effect = new CritDamageUpEffect();
        assertNotNull(effect);
        assertEquals(net.minecraft.world.effect.MobEffectCategory.BENEFICIAL, effect.getCategory());
        assertEquals(CritDamageUpEffect.COLOUR, effect.getColor());
    }

    /** {@code apply} with no caster is an answer, not an exception. */
    @Test
    void aNullCasterAppliesNothing() {
        assertEquals(0, CritDamageUps.apply(null, 28.0, 18.0, ResourceLocation.parse("astrum:chika"),
                java.util.OptionalDouble.of(8.0)));
    }

    // ------------------------------------------------------------------ the shipped data

    /**
     * <b>千歌's 6★ ultimate carries the hook, at the wiki's own numbers, and the gap sentence is gone.</b>
     *
     * <p>The clause was 「提升我方全体 28% 物理暴击伤害、持续 18 秒」. Its three numbers are asserted from the shipped
     * file rather than from a constant, so an edit that changed the data without changing this test would be
     * caught: {@code percent 28}, {@code seconds 18}, {@code radius 8} (the party default she declares), scope
     * {@code ally}.
     *
     * <p>And {@code extra} must be <b>empty</b>: it renders as 「尚未实现」 in the book, so leaving the sentence would
     * tell a player the buff does nothing while it is running.
     */
    @Test
    void chikasEnhancedUltimateCarriesTheBuffAndTheGapIsDeleted() throws Exception {
        CharacterDef chika = load("chika");
        UbTier enhanced = chika.ub().enhanced();
        assertNotNull(enhanced, "her 6★ ultimate is the one that grants the buff");
        UbEffect found = null;
        for (UbEffect effect : enhanced.effects()) {
            if (effect.type() == EffectType.CRIT_DAMAGE_UP) {
                found = effect;
            }
        }
        assertNotNull(found, "the 6★ ultimate must carry crit_damage_up; effects were "
                + enhanced.effects().stream().map(UbEffect::type).toList());
        assertEquals(UbEffect.Scope.ALLY, found.scope());
        assertEquals(28.0, found.percent().orElseThrow(), 1e-12,
                "the wiki's own 28%, carried as a free number because it is not a rung");
        assertEquals(18.0, found.params().seconds().orElseThrow(), 1e-12,
                "and the wiki's 18 seconds, in the ONE field TieredParams owns for a lifetime");
        assertEquals(8.0, found.params().radius().orElseThrow(), 1e-12,
                "the party default she declares, so the buff reaches 「我方全体」 and not just her");
        assertTrue(enhanced.extra().isEmpty(),
                "the gap sentence must be deleted, not left: it renders as 「尚未实现」. Was: "
                        + enhanced.extra().orElse("(none)"));
        // The base tier does not have it — the buff is a 6★ clause, and giving it to the 5★ tier would be a
        // different buff on a different ultimate.
        for (UbEffect effect : chika.ub().base().effects()) {
            assertTrue(effect.type() != EffectType.CRIT_DAMAGE_UP,
                    "the 5★ ultimate does not grant crit damage");
        }
    }

    /**
     * The hook's {@code effectParams} decode through the real dispatch, under the name the JSON uses.
     *
     * <p>{@code percent}/{@code seconds}/{@code radius} share one {@code effectParams} object, so a codec that read
     * one of them from a nested block would silently drop the other two — the failure mode the shared-params
     * comment in {@code EffectHook} exists to prevent.
     */
    @Test
    void theHookRoundTripsThroughTheRealCodec() {
        String json = """
                {"trigger":"ub_land","effect":"crit_damage_up",
                 "effectParams":{"percent":28.0,"seconds":18.0,"radius":8.0}}""";
        var node = JsonParser.parseString(json);
        var hook = (com.astrum.pcrd.api.v1.EffectHook.CritDamageUp) com.astrum.pcrd.api.v1.EffectHook.CODEC
                .parse(JsonOps.INSTANCE, node).getOrThrow();
        assertEquals(28.0, hook.percent(), 1e-12);
        assertEquals(18.0, hook.seconds(), 1e-12);
        assertEquals(8.0, hook.radius().orElseThrow(), 1e-12);
        assertEquals(EffectType.CRIT_DAMAGE_UP, hook.type());
        // ... and it belongs to the party's side of the fight, and leaves a state on the caster.
        assertTrue(EffectType.CRIT_DAMAGE_UP.reachesParty(), "「我方全体」 is a receiver set");
        assertFalse(EffectType.CRIT_DAMAGE_UP.reachesVictim());
        assertTrue(EffectType.CRIT_DAMAGE_UP.persistsOnCaster(), "an 18-second buff is a state, not an event");
        assertFalse(com.astrum.pcrd.api.v1.EffectTiers.isTiered(EffectType.CRIT_DAMAGE_UP),
                "the percentage is the wiki's own number, not a rung");
    }

    /** Every hook of the sealed interface has a place in a switch over the effect type — this one included. */
    @Test
    void theTypeIsRegisteredAsAMechanicWithASprite() {
        boolean listed = false;
        for (var holder : PartyEffects.mechanicHolders()) {
            if (holder.getKey().location().equals(CritDamageUps.ID)) {
                listed = true;
            }
        }
        assertTrue(listed, "the sprite manifest and the hider both read mechanicHolders(), so an effect that is "
                + "missing there ships the purple checkerboard");
    }
}