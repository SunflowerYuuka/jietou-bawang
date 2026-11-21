package com.astrum.pcrd.impl.attach;

import com.astrum.pcrd.impl.InternalIds;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Each character's ultimate cooldown is her own</b> — the value-level half of
 * 「每个角色UB的CD不共享」 — plus the migration of the save shape that change required.
 *
 * <h2>Why the migration is asserted here and not left to a manual upgrade</h2>
 * {@code UbState} is written to {@code playerdata/<uuid>.dat} on every charge change, so <em>every</em>
 * installed player has a file in the old shape {@code {charge, selectedSlot, cdEndTick}}. If that
 * failed to decode, {@code AttachmentHolder} would drop the attachment and each player would silently
 * lose her charge bar on the first login after the update — a data loss with no error visible to her.
 * So the old shape is written out here <b>as a literal</b>, exactly as the previous build wrote it, and
 * decoded through the current codec.
 *
 * <h2>What "absent entry" means</h2>
 * No entry for a character is the normal state — she has never cast — and every reader answers "ready".
 * That is why the migration needs no back-fill: the old shared timer is discarded (see
 * {@link UbState}'s javadoc for why it cannot be attributed to anybody) and every character starts
 * ready, which is one castable moment per player, once, and never a crash.
 */
class UbCooldownPerCharacterTest {

    private static final ResourceLocation DJEETA = InternalIds.id("djeeta");
    private static final ResourceLocation KYARU = InternalIds.id("kyaru");

    /**
     * A save written by the previous build decodes with <b>no cooldowns</b>.
     *
     * <p>The literal below is the old on-disk shape, key for key. The old {@code cdEndTick} is not named
     * by the new codec, and a codec ignores keys it does not name, so it is dropped rather than misread.
     */
    @Test
    void aSaveWrittenBeforePerCharacterCooldownsDecodesWithNone() {
        String legacy = "{\"charge\":63.5,\"selectedSlot\":3,\"cdEndTick\":987654}";

        UbState decoded = UbState.CODEC
                .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(legacy))
                .getOrThrow();

        assertEquals(63.5f, decoded.charge(), "the bar survives the upgrade untouched");
        assertEquals(3, decoded.selectedSlot(), "and so does the wheel selection");
        assertTrue(decoded.cooldowns().isEmpty(),
                "the old shared timer is dropped, not guessed onto some character");
        assertEquals(UbCooldown.NONE, decoded.cooldownOf(DJEETA), "so everybody is ready");
        assertTrue(decoded.isReady(DJEETA, 0L));
        assertTrue(decoded.isReady(KYARU, 0L));
    }

    /**
     * The same migration, through <b>NBT</b> — which is the format {@code playerdata} actually uses.
     *
     * <p>The JSON test above proves the codec ignores the old key; this one proves it does so under
     * {@code NbtOps}, where a map's keys must be strings and a mistake would surface as a decode error
     * that drops the attachment (and with it the bar). The compound is built by hand with the old keys
     * and the old types, exactly as {@code UbState.CODEC} wrote them before this change.
     */
    @Test
    void aLegacyNbtSaveDecodesWithNoCooldowns() {
        net.minecraft.nbt.CompoundTag legacy = new net.minecraft.nbt.CompoundTag();
        legacy.putFloat("charge", 63.5f);
        legacy.putInt("selectedSlot", 3);
        legacy.putLong("cdEndTick", 987_654L);

        UbState decoded = UbState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, legacy).getOrThrow();

        assertEquals(63.5f, decoded.charge());
        assertEquals(3, decoded.selectedSlot());
        assertTrue(decoded.cooldowns().isEmpty());
        assertEquals(UbCooldown.NONE, decoded.cooldownOf(DJEETA));
    }

    /**
     * The reverse direction: a state written now is readable by the same codec, and the absent field is
     * genuinely optional rather than merely defaulted on the way in.
     */
    @Test
    void aStateWithCooldownsRoundTripsAndAnAbsentMapIsStillReadable() {
        UbState withCooldowns = new UbState(0.0f, 0, java.util.Map.of(DJEETA,
                new UbCooldown(500L, 100L)));
        var encoded = UbState.CODEC.encodeStart(JsonOps.INSTANCE, withCooldowns).getOrThrow();
        assertEquals(withCooldowns, UbState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());

        // No "cooldowns" key at all: the documented default, which is also what the old save produced.
        String withoutMap = "{\"charge\":0.0,\"selectedSlot\":0}";
        UbState decoded = UbState.CODEC
                .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(withoutMap))
                .getOrThrow();
        assertEquals(java.util.Map.of(), decoded.cooldowns());
    }

    /**
     * Two characters' timers are independent, at the level where the HUD and the gate both read them.
     *
     * <p>This is the assertion the human's sentence translates to: arm one, and the other is ready —
     * then arm the other, and the first one's deadline is <b>unchanged</b>. A map replaced instead of
     * merged would pass the first half and fail the second.
     */
    @Test
    void twoCharactersCooldownIndependently() {
        UbState afterDjeeta = UbState.fresh().withCooldown(DJEETA, new UbCooldown(1_100L, 100L));

        assertFalse(afterDjeeta.isReady(DJEETA, 1_050L), "she is still cooling");
        assertTrue(afterDjeeta.isReady(KYARU, 1_050L), "the other one never cast, so she is ready");

        // A cast of キャル happens 20 ticks later and arms her own deadline.
        UbState afterBoth = afterDjeeta.withCooldown(KYARU, new UbCooldown(1_120L, 100L));
        assertEquals(new UbCooldown(1_100L, 100L), afterBoth.cooldownOf(DJEETA),
                "キャル's cast must not touch ディジェータ's deadline");
        assertEquals(new UbCooldown(1_120L, 100L), afterBoth.cooldownOf(KYARU));

        // Each one becomes castable on her own tick, not on a shared one.
        assertTrue(afterBoth.isReady(DJEETA, 1_100L));
        assertFalse(afterBoth.isReady(KYARU, 1_100L), "20 ticks apart means 20 ticks apart");
        assertTrue(afterBoth.isReady(KYARU, 1_120L));
    }

    /**
     * The gauge stays <b>one</b> shared bar: a cooldown change is not a charge change.
     *
     * <p>Deliberate, and the interesting consequence of his request — casting A drains the bar for B.
     * Asserted so that a later "fix" to per-character gauges has to come with a request for it.
     */
    @Test
    void armingACooldownDoesNotChangeTheSharedGauge() {
        UbState before = new UbState(42.0f, 2, java.util.Map.of());
        UbState after = before.withCooldown(DJEETA, new UbCooldown(900L, 100L));

        assertEquals(42.0f, after.charge(), "the bar is one shared gauge and this change did not split it");
        assertEquals(2, after.selectedSlot());
    }

    /** {@code withCharge} must carry the cooldowns over: the charge path writes on every trickle tick. */
    @Test
    void creditingChargeDoesNotClearCooldowns() {
        UbState withCooldown = new UbState(10.0f, 1, java.util.Map.of(DJEETA,
                new UbCooldown(900L, 100L)));
        UbState credited = withCharge(withCooldown, 55.0f);

        assertEquals(55.0f, credited.charge());
        assertEquals(new UbCooldown(900L, 100L), credited.cooldownOf(DJEETA),
                "a charge credit must not wipe the per-character timers");
    }

    /** A nonsense deadline is clamped, not rejected: see {@link UbCooldown}'s javadoc for why. */
    @Test
    void aCorruptDeadlineIsClampedRatherThanFailingTheWholeAttachment() {
        UbCooldown negative = new UbCooldown(-50L, -20L);
        assertEquals(0L, negative.endTick());
        assertEquals(0L, negative.totalTicks());

        String corrupt = "{\"charge\":10.0,\"selectedSlot\":0,\"cooldowns\":"
                + "{\"astrum:djeeta\":{\"endTick\":-1,\"totalTicks\":-1}}}";
        UbState decoded = UbState.CODEC
                .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(corrupt))
                .getOrThrow();
        assertEquals(10.0f, decoded.charge(),
                "the bar survives a corrupt timestamp — the failure is contained to one number");
        assertTrue(decoded.isReady(DJEETA, 0L), "and that number reads as 'ready', not as 'forever cooling'");
    }

    /** An entry missing its total (a save from a build between the two shapes) still decodes. */
    @Test
    void anEntryWithoutATotalDecodesToNothingToShade() {
        String partial = "{\"charge\":0.0,\"selectedSlot\":0,\"cooldowns\":"
                + "{\"astrum:djeeta\":{\"endTick\":500}}}";
        UbState decoded = UbState.CODEC
                .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(partial))
                .getOrThrow();
        UbCooldown cooldown = decoded.cooldownOf(DJEETA);
        assertEquals(500L, cooldown.endTick(), "the deadline is still honoured by the gate");
        assertEquals(0L, cooldown.totalTicks());
        assertEquals(0.0, cooldown.remainingFraction(400L),
                "with no total there is no fraction — so no shade, rather than a divide-by-zero");
        assertFalse(cooldown.isOver(400L), "but she is still on cooldown: the gate does not need the total");
    }


    /**
     * <b>No shared timer is left anywhere.</b>
     *
     * <p>A source-level assertion, in the same spirit as {@code BattleStartTest}'s "the rule is written
     * once" check: the request was to replace one shared timer with per-character ones, and the failure
     * mode this guards is a leftover accessor — a {@code cdEndTick()} or a {@code sharedCooldown} field
     * that still compiles and quietly reads the wrong thing. It reads the tree rather than a value
     * because "there is no such field" is not expressible any other way.
     *
     * <p>{@code CastGate.Facts.cdEndTick} is deliberately exempt: it is a <em>parameter</em> of the pure
     * decision, filled by the caller with one character's deadline, and giving it a different name would
     * not change what it means. What must not exist is a stored one.
     */
    @Test
    void noSharedCooldownDeadlineIsStoredAnyMore() {
        java.nio.file.Path root =
                com.astrum.pcrd.impl.client.TestProjectRoot.resolve("src/main/java");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null
                && java.nio.file.Files.isDirectory(root),
                "main sources not reachable; this assertion would be vacuous rather than wrong");

        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = java.nio.file.Files.readString(file)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                // A stored/returned shared deadline: a record component, a field, or a getter that names
                // it without being scoped to a character.
                if (source.matches("(?s).*\\b(long|Long)\\s+cdEndTick\\s*[),;(].*")
                        && !source.contains("record Facts(")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("could not walk " + root, e);
        }
        offenders.sort(String::compareTo);
        assertEquals(java.util.List.of(), offenders,
                "a class still stores a shared UB cooldown deadline: " + offenders);
    }

    private static UbState withCharge(UbState state, float charge) {
        return state.withCharge(charge, state.selectedSlot());
    }
}
