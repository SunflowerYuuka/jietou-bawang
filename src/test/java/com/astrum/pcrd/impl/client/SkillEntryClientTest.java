package com.astrum.pcrd.impl.client;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.impl.effect.SkillEffectTypes;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.neoforged.neoforge.client.extensions.common.IClientMobEffectExtensions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The skill entry's visible half: it IS shown in vanilla's lists, it wears the skill's name, and its
 * duration phrase is one phrase.</b>
 *
 * <h2>Why the visibility assertions are the interesting half</h2>
 * The party family answers {@code false} to both predicates — that is asserted in
 * {@code PartyRowLayerRegistrationTest}, because a party member belongs in our own row. This family answers
 * {@code true}: 「会在药水栏出现持续时间的，是技能提供的状态」 says the bar is <em>where</em> a skill's state is reported.
 * So the two families are opposites on exactly this predicate, and a copy-paste of the party class would hide
 * the whole feature while every other test still passed. That is what this checks.
 *
 * <h2>The skill is addressed by tag, not by index</h2>
 * The entry's effect <b>type</b> is the (character, skill) identity, so every name and picture question below is
 * asked with a skill tag — the same key {@code SkillRows} groups by. The old tests walked a row index carried in
 * the instance's amplifier; that coupling is gone (see {@code SkillEffectTypes}), and a test that still passed an
 * index would be asserting a design that no longer exists.
 *
 * <h2>What it cannot check</h2>
 * Nothing here opens a GL context or a window: {@code renderGuiIcon} needs a {@code GuiGraphics} and a real
 * screen. The picture's <em>resolution</em> (which texture {@link SkillArt} picks) is asserted without a client
 * in {@code SkillArtTest}, and the drawing itself is a human's check. The report says so.
 */
class SkillEntryClientTest {

    /**
     * <b>A skill entry is drawn in both of vanilla's effect lists; its effect list is not.</b>
     *
     * <p>The row came back after the human corrected an over-deletion: 「我要的不是技能行彻底消失，我要的是
     * 效果的数值描述消失。」 So the predicates answer {@code true} — a fired skill must appear in the inventory's
     * effect page, where its name is how a player knows what just happened — while the second line underneath
     * carries only the clock, not the derived list of effects.
     *
     * <p>One instance serves every type now: the identity arrives on the instance, so the object no longer has to
     * be asked "which slot am I?".
     */
    @Test
    void theSkillEntriesAreVisibleInBothVanillaEffectLists() {
        IClientMobEffectExtensions client = SkillEntryClient.instance();
        assertTrue(client.isVisibleInGui(null),
                "a fired skill appears in the HUD column again — only its effect list was removed");
        assertTrue(client.isVisibleInInventory(null),
                "and in the inventory's effect page, where its name is read");
    }

    /**
     * <b>The two families differ again, as they did before the over-deletion.</b>
     *
     * <p>A party member is 「who is with me」 and belongs in our own row, so its five effects stay hidden. A
     * fired skill is 「what just happened」 and belongs on vanilla's bar.
     */
    @Test
    void theTwoFamiliesAreOppositesOnTheVisibilityPredicate() {
        IClientMobEffectExtensions skill = SkillEntryClient.instance();
        IClientMobEffectExtensions party = PartyEffectClient.forSlot(0);
        assertTrue(skill.isVisibleInGui(null), "the skill row is the bar's content");
        assertFalse(party.isVisibleInGui(null), "the party rune is our own row's content");
        assertNotEquals(skill.getClass(), party.getClass(),
                "two families with opposite predicates must not be one class");
    }

    /**
     * <b>The name the row wears is the skill's name at her tier, not the effect's and not her own.</b>
     *
     * <p>「在JEI这种mod，显示的药水图标就是这个技能的名字」 — so for 可可萝's 加速 the row must read 「加速」, and at tier 2
     * it must read whatever her ladder says that rung is called. The lookup is by skill tag, which is the same
     * join the drawing hooks make.
     */
    @Test
    void theRowIsNamedAfterTheSkillNotTheEffect() {
        CharacterDef kokkoro = shipped("kokkoro");
        assertEquals("加速", SkillEntryText.title(kokkoro, 1, "speed_up").getString());
        assertFalse("加速".equals(kokkoro.displayName()),
                "and it must not be the character's own name, which would make the check vacuous");
    }

    /**
     * <b>A skill tag her pack no longer defines names the character, never a slot number or a level.</b>
     *
     * <p>His screenshot read 「技能效果 1 二级」: a slot number from the generic lang key, and a 「二级」 that was
     * vanilla's effect-level suffix describing our <em>row index</em> as if it were a strength. Both are gone. A
     * type whose skill the pack has since renamed falls back to <b>her name</b>, which is true and useful —
     * 「是她的哪个技能我不知道，但我知道是她」.
     */
    @Test
    void anUnknownSkillTagIsNamedAfterTheCharacterNotASlotNumber() {
        CharacterDef kokkoro = shipped("kokkoro");
        String unknown = SkillEntryText.title(kokkoro, 1, "no_such_skill").getString();
        assertEquals(kokkoro.displayName(), unknown, "a skill the pack does not define falls back to her name");
        assertFalse(unknown.contains("技能效果"), "nor to the generic key: " + unknown);
        assertEquals(kokkoro.displayName(), SkillEntryText.title(kokkoro, 1, null).getString(),
                "and a missing tag answers the same way rather than throwing");
    }

    /**
     * <b>Not even an unknown character produces vanilla's numbered fallback.</b>
     *
     * <p>The last link of the chain: no character at all answers one generic sentence with <b>no digit and no
     * level</b> in it. That is the string the human photographed, and this is the assertion that it cannot come
     * back — including through the {@code icon} hook, which now also answers {@code true} rather than declining
     * into vanilla's row.
     */
    @Test
    void anUnknownCharacterFallsBackToOneSentenceWithNoDigits() {
        String fallback = SkillEntryText.title(null, 1, "speed_up").getString();
        // Not resolved through the lang file here (no client), so the key is what we see — the point is that it
        // is ONE key with nothing interpolated into it.
        assertEquals(SkillEffectTypes.FALLBACK_NAME_KEY, fallback,
                "an unknown character answers the one shared key");
        assertFalse(fallback.contains("skill_1"),
                "and not the per-slot key that produced 「技能效果 1」: " + fallback);
        List<net.minecraft.network.chat.Component> lines = SkillEntryText.lines(null, 1, "speed_up");
        assertEquals(1, lines.size(), "a tooltip for an unknown character is still one name line");
    }

    /**
     * <b>The tooltip is the skill's combined effect: one line per state it left, each with its own clock.</b>
     *
     * <p>佩可莉姆's 午餐时间 at tier 1 heals and raises magic defence. The heal is instantaneous, so it is not a
     * state and does not get a line; the defence buff does. That is 「效果是这个技能的统合效果」 read literally:
     * the effect text describes what the skill leaves running, not every mechanism it runs.
     */
    @Test
    void theTooltipListsTheStatesTheSkillLeft() {
        CharacterDef pecorine = shipped("pecorine");

        List<net.minecraft.network.chat.Component> lines = SkillEntryText.lines(pecorine, 1, "lunch_time");
        assertFalse(lines.isEmpty(), "a skill with a state must describe itself");
        assertEquals("午餐时间", lines.get(0).getString(), "line 0 is the skill's name, as vanilla's name line");

        List<com.astrum.pcrd.api.v1.EffectType> states = SkillEntryText.effectsOf(pecorine, 1,
                SkillEntryText.rowOf(pecorine, 1, "lunch_time"));
        assertFalse(states.isEmpty());
        assertFalse(states.contains(com.astrum.pcrd.api.v1.EffectType.HEAL_SELF),
                "a heal is instantaneous: it is not a state, so it gets no line");
        assertTrue(states.contains(com.astrum.pcrd.api.v1.EffectType.BUFF_MAGIC_DEFENCE_AREA),
                "the magic-defence buff is the state the row exists to report");
        assertEquals(states.size() + 1, lines.size(),
                "the lines are the name plus exactly one per state — no line for a mechanism that left nothing");
    }

    /** A skill with no state has an empty effect list, and never a fabricated line. */
    @Test
    void anInstantaneousSkillHasNoStateToDescribe() {
        CharacterDef djeeta = shipped("djeeta");
        assertTrue(SkillEntryText.effectsOf(djeeta, 1, SkillEntryText.rowOf(djeeta, 1, "wide_slash")).isEmpty(),
                "广域斩击 leaves nothing running, so its tooltip is its name alone");
        assertEquals(1, SkillEntryText.lines(djeeta, 1, "wide_slash").size(),
                "the name is still the right answer — a tooltip with no lines at all would be worse than none");
    }

    /**
     * <b>The duration phrase is one phrase, and it reads in seconds.</b>
     *
     * <p>{@link SkillEntryText#secondsOf} is the only conversion on this surface, and 恐慌's 30 ticks is why it
     * has a decimal branch: 「1.5 秒」 is a real duration here while 「1.5」 would be unreadable and 「2」 would be
     * wrong.
     */
    @Test
    void theDurationPhraseIsSecondsWithOneDecimalOnlyWhenNeeded() {
        assertEquals("0", SkillEntryText.secondsOf(0));
        assertEquals("8", SkillEntryText.secondsOf(160));
        assertEquals("12", SkillEntryText.secondsOf(240));
        assertEquals("1.5", SkillEntryText.secondsOf(30));
        assertEquals("0.5", SkillEntryText.secondsOf(10));
        assertEquals("1.3", SkillEntryText.secondsOf(25));
        assertEquals("1", SkillEntryText.secondsOf(20), "a whole second is never printed as 1.0");
    }

    /** The one duration key is a real translation in both languages, so no raw key reaches a tooltip. */
    @Test
    void theDurationKeyIsTheBooksOwnConstant() {
        assertEquals(com.astrum.pcrd.impl.party.BookText.KEY_SKILL_ENTRY_DURATION, SkillEntryText.KEY_DURATION,
                "the tooltip and the row must share one phrase, and the parity test must be able to see it");
    }

    /** The icon the entries draw ends at the skill's own picture, resolved through the one chain. */
    @Test
    void theEntrysPictureComesFromTheSkillsOwnIcon() {
        CharacterDef djeeta = shipped("djeeta");
        SkillRowsRowIcon icon = iconOf(djeeta, 1, "wide_slash");
        assertTrue(icon.present(), "the pack ships a picture for 广域斩击");
        assertTrue(icon.path().startsWith("textures/gui/skillicon/"),
                "so the entry wears it rather than a fallback: " + icon.path());
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** A tiny view of a row's icon, so this test does not depend on {@code Optional} in its assertions. */
    private record SkillRowsRowIcon(boolean present, String path) {
    }

    private static SkillRowsRowIcon iconOf(CharacterDef def, int tier, String skill) {
        var row = SkillEntryText.rowOf(def, tier, skill);
        return row == null || row.icon().isEmpty()
                ? new SkillRowsRowIcon(false, "")
                : new SkillRowsRowIcon(true, row.icon().get().getPath());
    }

    /** {@return the shipped definition for {@code name}, parsed through the real codec} */
    private static CharacterDef shipped(String name) {
        String path = "/data/astrum/" + net.minecraft.core.registries.Registries.elementsDirPath(
                RegistryKeys.CHARACTERS) + "/" + name + ".json";
        try (InputStream in = SkillEntryClientTest.class.getResourceAsStream(path)) {
            assertNotNull(in, name + ".json must be on the test classpath: " + path);
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(
                    error -> new IllegalStateException(name + ".json does not decode: " + error));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}