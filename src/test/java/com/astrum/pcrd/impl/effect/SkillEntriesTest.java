package com.astrum.pcrd.impl.effect;

import com.astrum.pcrd.api.v1.CharacterDef;
import com.astrum.pcrd.api.v1.EffectHook;
import com.astrum.pcrd.api.v1.EffectType;
import com.astrum.pcrd.api.v1.RegistryKeys;
import com.astrum.pcrd.api.v1.TieredParams;
import com.astrum.pcrd.api.v1.Trigger;
import com.astrum.pcrd.api.v1.TriggerParams;
import com.astrum.pcrd.impl.growth.AffectionKernel;
import com.astrum.pcrd.impl.party.SkillRows;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What one fired skill leaves in the effect bar: one entry, the longest duration of its states, or none at
 * all.</b>
 *
 * <h2>The three sentences this file is about</h2>
 * <ol>
 *   <li>「在药水栏上显示的图标也只有一个技能图标。」 — one entry per skill, however many effects it has.</li>
 *   <li>「一个技能里的多个效果，等到全部效果结束了，这个图标再删除。」 — it lasts until the <b>last</b> one ends, so
 *       the duration is the <b>max</b>.</li>
 *   <li>「瞬发技能就不要显示持续时间了，会在药水栏出现持续时间的，是技能提供的状态，不是技能。」 — a skill with no persisting
 *       state produces <b>no entry</b>. That is the case the first draft of this feature got wrong by giving
 *       instantaneous skills a short entry, which would have put a duration on 广域斩击.</li>
 * </ol>
 *
 * <h2>Why the shipped datapack is read and not retyped</h2>
 * The "which skills produce an entry" list is a fact about the files in {@code data/}, and a test that restated
 * it would keep passing while a pack edit changed the answer. {@link #shipped(String)} parses the real json
 * through the same {@link CharacterDef#CODEC} the registry uses, so the four characters' answers below are the
 * build's answers.
 */
class SkillEntriesTest {

    // -----------------------------------------------------------------------------------------
    // 1. The max, not the first, not the sum.
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The entry lasts as long as the last effect, not the first one.</b>
     *
     * <p>「等到全部效果结束了，这个图标再删除」 read as arithmetic: 8 s haste + 12 s party attack up must produce a
     * 240-tick entry. Taking the first hook's clock (160) is the natural mistake — it is what "the skill's
     * duration" would mean if you only looked at one effect — and it is exactly the defect the human described,
     * where the icon vanished while the buff it reported was still running.
     */
    @Test
    void theEntryLastsAsLongAsTheLastEffect() {
        EffectHook shortFirst = haste(8.0);
        EffectHook longLast = buffAttackArea(12.0);

        Optional<SkillEntries.Entry> entry = SkillEntries.of(List.of(shortFirst, longLast));

        assertTrue(entry.isPresent(), "a skill with two timed buffs leaves an entry");
        assertEquals(240, entry.get().ticks(), "12 s is the answer, not the 8 s that fired first");
        assertEquals("加速", entry.get().skill(), "the longer effect's skill tag names the entry");
    }

    /** The same rule with the order reversed, so the answer cannot be "the last one in the list". */
    @Test
    void theOrderOfTheHooksDoesNotDecideTheDuration() {
        assertEquals(240, SkillEntries.of(List.of(buffAttackArea(12.0), haste(8.0))).orElseThrow().ticks());
        assertEquals(240, SkillEntries.of(List.of(haste(8.0), buffAttackArea(12.0))).orElseThrow().ticks());
    }

    /** It is a maximum, not a sum: 8 + 12 is 400 and 12 s is 240. */
    @Test
    void theDurationsAreNotSummed() {
        assertEquals(240, SkillEntries.of(List.of(haste(8.0), buffAttackArea(12.0))).orElseThrow().ticks());
        assertFalse(SkillEntries.of(List.of(haste(8.0), buffAttackArea(12.0))).orElseThrow().ticks() == 400,
                "the entry must not add two concurrent buffs together");
    }

    // -----------------------------------------------------------------------------------------
    // 2. The zero case is NO ENTRY — the correction the lead issued.
    // -----------------------------------------------------------------------------------------

    /**
     * <b>A skill whose effects are all instantaneous produces no entry.</b>
     *
     * <p>姬塔's 广域斩击 is {@code extra_damage} and 剑意迸发 is {@code gain_charge}: both leave the player in no
     * state, and 「会在药水栏出现持续时间的，是技能提供的状态，不是技能」 means the bar must stay empty for them. This is
     * the assertion that fails if anybody reintroduces a minimum duration.
     */
    @Test
    void aSkillWithNothingPersistingProducesNoEntry() {
        assertTrue(SkillEntries.of(List.of(extraDamage(20.0), gainCharge(15.0))).isEmpty(),
                "damage and charge are events, not states; there is nothing for the bar to report");
        assertTrue(SkillEntries.of(List.of(healSelf())).isEmpty(), "a heal is instantaneous");
        assertTrue(SkillEntries.of(List.of(splash(10.0, 4.0))).isEmpty(), "a splash lands on other bodies");
        assertTrue(SkillEntries.of(List.of(panic())).isEmpty(), "panic is on the body that was hit");
        assertTrue(SkillEntries.of(List.of(defenceDown(2))).isEmpty(), "a debuff on the enemy is not my state");
    }

    /** An empty or absent list is the same answer as "all instantaneous", and never throws. */
    @Test
    void noFiredHooksIsNoEntry() {
        assertTrue(SkillEntries.of(List.of()).isEmpty());
        assertTrue(SkillEntries.of(null).isEmpty());
    }

    /** A null element mid-list is skipped rather than crashing a damage path. */
    @Test
    void aNullHookIsSkipped() {
        List<EffectHook> hooks = new ArrayList<>();
        hooks.add(null);
        hooks.add(haste(8.0));
        assertEquals(160, SkillEntries.of(hooks).orElseThrow().ticks());
    }

    // -----------------------------------------------------------------------------------------
    // 3. The durations are the owners' — no second copy of a default.
    // -----------------------------------------------------------------------------------------

    /**
     * <b>Each effect's clock comes from the class that owns it.</b>
     *
     * <p>So a tuned default moves the entry with the buff: {@code AreaBuffs.DEFAULT_SECONDS} for a tier block with
     * no {@code seconds}, {@code Shields.DEFAULT_SECONDS} for a barrier, {@code PanicRules.DURATION_TICKS} for the
     * one fixed duration in the mod. This asserts the <b>delegation</b>, not the numbers.
     */
    @Test
    void eachDurationIsDelegatedToTheClassThatOwnsIt() {
        assertEquals(AreaBuffs.ticksFor(TieredParams.FIRST),
                SkillEntries.of(List.of(haste(null))).orElseThrow().ticks(),
                "a haste with no seconds must use AreaBuffs' default, not one of its own");
        assertEquals(Shields.ticksFor(TieredParams.FIRST),
                SkillEntries.of(List.of(shieldOfTier(2, null))).orElseThrow().ticks(),
                "a barrier's clock is Shields'");
        assertEquals(Spirits.ticksFor(TieredParams.FIRST),
                SkillEntries.of(List.of(summonSpirit(3, null))).orElseThrow().ticks(),
                "a summon's clock is Spirits'");
        assertEquals(AttackBuffRules.ticksFor(9.0),
                SkillEntries.of(List.of(attackBuff(9.0))).orElseThrow().ticks(),
                "attack_buff declares bare seconds and AttackBuffRules converts them");
    }

    /**
     * <b>A permanent flat grant produces no entry; a timed one does.</b>
     *
     * <p>That is the one family with two forms, and {@link com.astrum.pcrd.api.v1.GrantDuration} is where the
     * difference is decided — an EX passive is owned by the party slot and has no clock, so it is not a state
     * with a duration and the bar must not pretend it is.
     */
    @Test
    void aPermanentGrantIsNotATimedState() {
        assertTrue(SkillEntries.of(List.of(flatAttackUp(com.astrum.pcrd.api.v1.GrantDuration.permanent())))
                        .isEmpty(),
                "an EX passive never expires on a clock, so there is nothing to count down");
        assertEquals(240, SkillEntries.of(List.of(
                flatAttackUp(com.astrum.pcrd.api.v1.GrantDuration.ofSeconds(12.0)))).orElseThrow().ticks(),
                "a timed flat grant is a state and does last 12 s");
    }

    /**
     * <b>The one duration the multiplier scales is scaled the same way the effect was applied.</b>
     *
     * <p>{@code ally_effect} is the only hook whose duration is a quantity (§5.2: a tier is the strength and is
     * never scaled, a duration is a quantity and is). If the entry reported the authored 200 ticks while the
     * vanilla effect it describes got 100, the icon would outlive the buff by ten seconds — the exact defect
     * the whole feature exists to fix, one layer down.
     */
    @Test
    void theAllyEffectDurationTravelsThroughTheMultiplier() {
        EffectHook hook = allyEffect(200);
        assertEquals(200, SkillEntries.of(List.of(hook)).orElseThrow().ticks());
        assertEquals(100, SkillEntries.of(List.of(hook), 0.5).orElseThrow().ticks(),
                "a half multiplier halves the duration, as EffectExecutors does");
    }

    // -----------------------------------------------------------------------------------------
    // 4. The name is the SKILL's, not the effect's.
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The entry is named by the skill that fired, at her tier.</b>
     *
     * <p>「在JEI这种mod，显示的药水图标就是这个技能的名字」 — so the row's name is
     * {@code SkillLadder.nameAt(tier)}, which is what changes with 「+」 upgrades, and the picture is that same
     * row's {@code icon}. This asserts the <b>join</b>: the row {@code SkillRows} builds for a skill tag carries
     * that skill's own name, so a client reading the row cannot show an effect's name instead.
     */
    @Test
    void theEntryWearsTheSkillsNameAtHerTier() {
        CharacterDef djeeta = shipped("djeeta");
        SkillRows tier1 = SkillRows.of(djeeta, 1);
        SkillRows tier2 = SkillRows.of(djeeta, 2);

        SkillRows.Row firstAtTier1 = rowOf(tier1, "wide_slash");
        SkillRows.Row firstAtTier2 = rowOf(tier2, "wide_slash");

        assertNotNull(firstAtTier1);
        assertEquals("广域斩击", firstAtTier1.name());
        assertEquals("广域斩击+", firstAtTier2.name(),
                "the tier's own name is what the bar must wear, not the base one");
        assertFalse(firstAtTier1.name().equals(EffectType.EXTRA_DAMAGE.getSerializedName()),
                "the entry is never named after the effect that happens to have no clock");
    }

    /** The picture the entry resolves is the row's own skill icon, which is the chain's first link. */
    @Test
    void theEntrysPictureIsTheSkillIconNotAFallback() {
        CharacterDef djeeta = shipped("djeeta");
        SkillRows.Row row = rowOf(SkillRows.of(djeeta, 1), "wide_slash");
        assertNotNull(row);
        assertTrue(row.icon().isPresent(), "the pack ships a picture per skill; the entry must read it");
        assertTrue(row.icon().get().getPath().startsWith("textures/gui/skillicon/"),
                "the picture is the skill-icon family, not a portrait or an effect sprite: " + row.icon().get());
    }

    // -----------------------------------------------------------------------------------------
    // 5. The four shipped characters: which skills produce an entry, and which do not.
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The answer, for every skill of the four shipped characters, read from the shipped json.</b>
     *
     * <p>This is the table the report quotes:</p>
     * <pre>
     *   姬塔      广域斩击  ×      剑意迸发  ×      龙卷的攻刃 ×     疾风剑雨 ▲ (enhanced only)
     *   可可萝    掠阵三段击 ×    加速      ✔      精灵的启示 ×     极光      ✔
     *   佩可莉姆  午餐时间  ✔      沉重一击  ×      公主之力  ×     公主突袭  ✔
     *   凯露      闪电球    ×/✔    护甲削弱  ×      黑暗之蚀  ×     格林炸裂  ▲ (enhanced only)
     * </pre>
     *
     * <p>「×」 is a skill whose every effect is instantaneous or lands on the enemy, so the bar stays empty;
     * 「✔」 is one that leaves a state, so exactly one row appears. 「▲」 is an ultimate whose base tier leaves
     * nothing and whose enhanced tier leaves a party buff — and since the enhanced tier is what tier 3 casts, the
     * answer depends on the tier, which is why the assertion is per tier rather than per skill.
     */
    @Test
    void theShippedCharactersLeaveEntriesOnlyWhereTheyLeftAState() {
        // 姬塔: both small skills are purely instantaneous at every tier she can be at. This is the character the
        // human's 「瞬发技能就不要显示了」 sentence was written about.
        CharacterDef djeeta = shipped("djeeta");
        assertTrue(skillLeavesNothing(djeeta, 1, "wide_slash"), "广域斩击 deals damage and nothing else at t1");
        assertTrue(skillLeavesNothing(djeeta, 3, "wide_slash"),
                "t3 adds area_charge, which is still instantaneous");
        assertTrue(skillLeavesNothing(djeeta, 1, "weapon_burst"), "剑意迸发 restores charge and nothing else");
        assertTrue(skillLeavesNothing(djeeta, 3, "weapon_burst"), "t3 adds damage to it, still no state");

        // 可可萝: 加速 is a real haste; 掠阵三段击 is damage.
        CharacterDef kokkoro = shipped("kokkoro");
        assertFalse(skillLeavesNothing(kokkoro, 1, "speed_up"), "加速 leaves a 12 s haste");
        assertTrue(skillLeavesNothing(kokkoro, 1, "triple_slash"), "掠阵三段击 is damage only");

        // 佩可莉姆: 午餐时间 heals AND raises magic defence; 沉重一击 is damage.
        CharacterDef pecorine = shipped("pecorine");
        assertFalse(skillLeavesNothing(pecorine, 1, "lunch_time"), "午餐时间 leaves a magic-defence buff");
        assertTrue(skillLeavesNothing(pecorine, 1, "heavy_blow"), "沉重一击 is damage only");

        // 凯露: 护甲削弱 puts a debuff on the ENEMY, so the caster's bar stays empty — the case a naive
        // "does it apply an effect?" rule would get wrong.
        CharacterDef kyaru = shipped("kyaru");
        assertTrue(skillLeavesNothing(kyaru, 1, "armour_break"),
                "a debuff on the victim is that body's state, not the caster's");
        assertTrue(skillLeavesNothing(kyaru, 1, "lightning_orb"), "闪电球 t1 is damage only");
        assertTrue(skillLeavesNothing(kyaru, 2, "lightning_orb"),
                "t2 adds splash and magic_defence_down — both land on the enemy, so the caster still has no "
                        + "state of her own and the bar stays empty");
    }

    /**
     * <b>The ultimate's answer depends on the tier, because the two tiers declare different effects.</b>
     *
     * <p>Worth its own test because it is the one place a reader could think the rule is inconsistent: 姬塔's base
     * ultimate is pure damage (four hits, no effects) and her enhanced one is a party attack buff. Read through
     * {@link SkillEntries#ticksOfUltimate}, the first answers zero and the second 360 ticks — so a cast shows a
     * row only when the cast really left something running.
     */
    @Test
    void anUltimateShowsARowOnlyWhenItsTierLeavesAState() {
        CharacterDef djeeta = shipped("djeeta");
        assertEquals(0, ultimateTicks(djeeta, AffectionKernel.usesEnhancedUb(1)),
                "base 疾风剑雨 is damage only");
        assertEquals(0, ultimateTicks(djeeta, false), "and asking about base explicitly agrees");
        assertEquals(360, ultimateTicks(djeeta, true),
                "the enhanced tier is a party attack buff for 18 s");

        CharacterDef kyaru = shipped("kyaru");
        assertEquals(0, ultimateTicks(kyaru, true),
                "格林炸裂's enhanced clause is panic ON THE VICTIM, so the caster shows nothing");
    }

    // -----------------------------------------------------------------------------------------
    // 6. The registry shape is no longer asserted here.
    //
    // It used to be five types, one per party slot, and this section pinned that shape. Task 59 replaced the
    // family with one type per (character, skill) — the defect being 「他只会显示冻结之湖或者雪鞋，不会两个同时存在」 — so
    // the shape is now a data-driven declaration of 91 identities. Its assertions live where the class does:
    // SkillEffectTypesTest (every skill of every shipped character has a type; the ids are disjoint from the
    // party family; the one name key per character exists in both languages) and SkillEntryCoexistenceTest (two
    // skills coexist; the same skill refreshes). This pointer is kept rather than deleting the section
    // silently, so a reader knows where the family went.
    // -----------------------------------------------------------------------------------------

    @Test
    void anEntryCannotBeWrittenWithNoDuration() {
        assertThrows(IllegalArgumentException.class, () -> new SkillEntries.Entry("加速", 0));
        assertThrows(IllegalArgumentException.class, () -> new SkillEntries.Entry("加速", -20));
    }

    // -----------------------------------------------------------------------------------------
    // 7. The classification itself: 「state」 is a property of the effect, declared once.
    // -----------------------------------------------------------------------------------------

    /**
     * <b>The persisting set is exactly the effects that leave a timed state on the caster.</b>
     *
     * <p>Written as three explicit sets rather than as a count, because the failure this guards is a new effect
     * joining the wrong one: a debuff on the victim landing in the "persists" set would put 「护甲削弱」 on the
     * caster's bar for something she is not under.
     */
    @Test
    void thePersistingClassificationIsTheStateNotTheEvent() {
        Set<EffectType> persists = EffectType.persistingOnCaster();
        assertEquals(EnumSet.of(EffectType.ATTACK_BUFF, EffectType.ALLY_EFFECT,
                EffectType.BUFF_ATTACK_AREA, EffectType.BUFF_MAGIC_ATTACK_AREA,
                EffectType.BUFF_PHYS_DEFENCE_AREA, EffectType.BUFF_MAGIC_DEFENCE_AREA,
                EffectType.HASTE, EffectType.CHARGE_RATE_UP, EffectType.SHIELD,
                EffectType.SUMMON_SPIRIT, EffectType.COUNTER,
                EffectType.FLAT_ATTACK_UP, EffectType.FLAT_MAGIC_ATTACK_UP,
                // task 49: the four over-time types ARE states — a regeneration and a charge-regen are
                // things the player carries for a while, which is exactly what the bar lists.
                EffectType.REGEN_SELF, EffectType.REGEN_AREA,
                EffectType.CHARGE_REGEN_SELF, EffectType.CHARGE_REGEN_AREA,
                // task 56: a taunt is a timed state the *caster* carries — she is the one calling the mobs.
                EffectType.TAUNT,
                // task 55: the two immunities are timed states on the caster, and their own constants were
                // put in PERSISTS_ON_CASTER by the agent that added them; this ledger follows that decision.
                EffectType.PHYS_IMMUNITY, EffectType.CONTROL_IMMUNITY,
                // task 60: the two typed barriers are the shared barrier's states with one half each, so they
                // are rows like it; the timed crit-chance buff is the crit-damage one's twin; and the
                // tally-scaled charge regen is a per-second state, i.e. exactly what the bar lists.
                EffectType.SHIELD_PHYS, EffectType.SHIELD_MAGIC, EffectType.CRIT_CHANCE_UP,
                EffectType.COUNTER_CHARGE_REGEN,
                // task 58: the timed crit-damage buff is a state a player carries for its 18 seconds, so it joins
                // the set — and the skill that cast it gets one bar entry lasting exactly that long.
                EffectType.CRIT_DAMAGE_UP), persists);

        for (EffectType instant : new EffectType[] {EffectType.EXTRA_DAMAGE, EffectType.PERCENT_DAMAGE,
                EffectType.GAIN_CHARGE, EffectType.AREA_CHARGE, EffectType.SPLASH, EffectType.LIFESTEAL,
                EffectType.STACK, EffectType.DETONATE, EffectType.HEAL, EffectType.HEAL_AREA,
                EffectType.HEAL_SELF, EffectType.CONSUME_COUNTER,
                // task 49: a transfer credits charge or restores health in the tick it fires, so it leaves
                // nothing behind — the same answer GAIN_CHARGE and HEAL get.
                EffectType.TRANSFER_CHARGE, EffectType.TRANSFER_HEAL}) {
            assertFalse(instant.persistsOnCaster(), instant + " is an event, not a state");
        }
        for (EffectType onVictim : new EffectType[] {EffectType.DEFENCE_DOWN, EffectType.MAGIC_DEFENCE_DOWN,
                EffectType.PANIC, EffectType.APPLY_EFFECT, EffectType.REMOVE_SHIELD,
                // task 56: a pull and a knockback act on the body they land on and finish in that tick.
                EffectType.PULL, EffectType.KNOCKBACK}) {
            assertFalse(onVictim.persistsOnCaster(),
                    onVictim + " puts its state on the body it lands on, so it is not the caster's row");
        }
    }

    /**
     * <b>The party family stays hidden; the skill family does not.</b>
     *
     * <p>The one predicate difference between the two families, asserted here so a later edit cannot quietly
     * give the party effects a row back or hide the skill entries. The party half is checked through
     * {@code PartyEffectClient.forSlot} in {@code PartyRowLayerRegistrationTest} (it needs a client class); the
     * skill half is checked in {@code SkillEntryClientTest} for the same reason. What is checked here is the
     * <b>seam</b>: the two registries classify disjoint sets, which is what lets one be hidden and the other
     * shown without either predicate consulting a name.
     */
    @Test
    void theTwoFamiliesClassifyDisjointSets() {
        for (int slot = 0; slot < 5; slot++) {
            assertTrue(PartyEffectSlots.slotOf(PartyEffectSlots.idFor(slot)).isPresent());
            assertTrue(SkillEffectTypes.identityOf(PartyEffectSlots.idFor(slot)).isEmpty(),
                    "a party rune is not a skill identity");
        }
        // The skill half is a (character, skill) identity now, not a slot: the old per-slot ids are gone, so a
        // declared type answers an identity and no slot of either family. SkillEffectTypesTest owns the full
        // set; this is the seam between the two families.
        for (SkillEffectTypes.Identity identity : SkillEffectTypes.declared()) {
            assertTrue(SkillEffectTypes.identityOf(identity.id()).isPresent());
            assertFalse(PartyEffectSlots.slotOf(identity.id()).isPresent(),
                    "a skill type must never be classified as a party slot: " + identity.id());
        }
    }

    /**
     * <b>A row position means the same skill at every affection tier — for every shipped character.</b>
     *
     * <h2>Why this is still load-bearing, now that the bar no longer keys on an index</h2>
     * It used to be the bar's invariant: the entry's amplifier was a row index, so a tier-dependent reorder
     * would have made the bar draw one skill's picture for another skill's firing. Task 59 moved the identity
     * into the effect <b>type</b> (one type per (character, skill)), so the bar no longer reads a position at
     * all — but two other readers still do, and both are the reason the row order must not depend on the tier:
     *
     * <ul>
     *   <li>the <b>book</b> draws the list top to bottom, and a player reading it reads her rotation;</li>
     *   <li>{@code SkillRotation} counts <em>skills</em>, so a reorder would change which skill fires next at a
     *       tier the cursor was already advanced on.</li>
     * </ul>
     *
     * <p>A 「+」 upgrade renames a row without adding one, an EX row appears only at the rung that declares it,
     * and the ultimate swaps base for enhanced. Any of those reordering the list would move a skill out from
     * under the rotation, so the sequence of skill <b>tags</b> is asserted to be identical at tiers 1, 2 and 3 —
     * against the real datapack, for all 22 characters.
     */
    @Test
    void aRowPositionMeansTheSameSkillAtEveryTier() {
        int characters = 0;
        for (String name : shippedCharacterNames()) {
            CharacterDef def = shipped(name);
            List<String> atOne = tagsAt(def, 1);
            assertEquals(atOne, tagsAt(def, 2), name + "'s row order must not change from tier 1 to tier 2");
            assertEquals(atOne, tagsAt(def, 3), name + "'s row order must not change from tier 1 to tier 3");
            characters++;
        }
        assertTrue(characters >= 20, "the shipped pack must be walked, not a sample: saw " + characters);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers: the real datapack, and hook construction that mirrors what the shipped json says.
    // -----------------------------------------------------------------------------------------

    /** {@return the skill tags of {@code def}'s rows at {@code tier}, in row order} */
    private static List<String> tagsAt(CharacterDef def, int tier) {
        return SkillRows.of(def, tier).rows().stream().map(SkillRows.Row::skill).toList();
    }

    /** {@return the names of every character json the pack ships} */
    private static List<String> shippedCharacterNames() {
        String dir = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS);
        try {
            java.net.URL url = SkillEntriesTest.class.getResource(dir);
            assertNotNull(url, "the shipped characters must be reachable on the test classpath: " + dir);
            java.nio.file.Path root = java.nio.file.Path.of(url.toURI());
            try (var stream = java.nio.file.Files.list(root)) {
                List<String> names = stream.map(path -> path.getFileName().toString())
                        .filter(file -> file.endsWith(".json"))
                        .map(file -> file.substring(0, file.length() - ".json".length()))
                        .sorted()
                        .toList();
                assertFalse(names.isEmpty(), "a pack with no characters would make this test vacuous");
                return names;
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("the pack directory is not a file URL: " + dir, e);
        }
    }

    /** {@return the shipped definition for {@code name}, parsed through the real codec} */
    static CharacterDef shipped(String name) {
        String path = "/data/astrum/" + Registries.elementsDirPath(RegistryKeys.CHARACTERS) + "/" + name
                + ".json";
        try (InputStream in = SkillEntriesTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(name + ".json is not on the test classpath: " + path);
            }
            var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return CharacterDef.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(
                    error -> new IllegalStateException(name + ".json does not decode: " + error));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** {@return whether every effect of {@code skill} (at {@code tier}) leaves the caster in no state} */
    private static boolean skillLeavesNothing(CharacterDef def, int tier, String skill) {
        List<EffectHook> hooks = AffectionKernel.hooksAt(def, tier).stream()
                .filter(hook -> hook.triggerParams().skillTag().filter(skill::equals).isPresent())
                .toList();
        assertFalse(hooks.isEmpty(), "the pack must define " + skill + " at tier " + tier);
        return SkillEntries.of(hooks).isEmpty();
    }

    /** {@return the ticks an ultimate of {@code def} at the given tier leaves on the caster} */
    private static int ultimateTicks(CharacterDef def, boolean enhanced) {
        com.astrum.pcrd.api.v1.UbTier cast = enhanced ? def.ub().enhanced() : def.ub().base();
        if (cast == null) {
            return 0;
        }
        List<EffectType> types = new ArrayList<>();
        List<TieredParams> params = new ArrayList<>();
        for (com.astrum.pcrd.api.v1.UbEffect effect : cast.effects()) {
            if (effect.scope() != com.astrum.pcrd.api.v1.UbEffect.Scope.ALLY) {
                continue;
            }
            types.add(effect.type());
            params.add(effect.params());
        }
        return SkillEntries.ticksOfUltimate(types, params);
    }

    /** {@return the row of {@code skill} in {@code rows}, or {@code null}} */
    private static SkillRows.@org.jetbrains.annotations.Nullable Row rowOf(SkillRows rows, String skill) {
        for (SkillRows.Row row : rows.rows()) {
            if (row.skill().equals(skill)) {
                return row;
            }
        }
        return null;
    }

    // --- hook construction, mirroring the shipped shapes -------------------------------------

    private static TriggerParams tagged(String skill) {
        return new TriggerParams(0, Optional.empty(), Optional.of(skill));
    }

    private static EffectHook haste(@org.jetbrains.annotations.Nullable Double seconds) {
        return new EffectHook.Haste(Trigger.MELEE_HIT, tagged("加速"), 1.0,
                new TieredParams(1, seconds == null ? OptionalDouble.empty() : OptionalDouble.of(seconds),
                        OptionalDouble.empty()));
    }

    private static EffectHook buffAttackArea(double seconds) {
        return new EffectHook.BuffAttackArea(Trigger.MELEE_HIT, tagged("加速"), 1.0,
                new TieredParams(1, OptionalDouble.of(seconds), OptionalDouble.empty()));
    }

    private static EffectHook attackBuff(double seconds) {
        return new EffectHook.AttackBuff(Trigger.MELEE_HIT, tagged("加速"), 1.0, 1.2, seconds,
                OptionalDouble.empty());
    }

    private static EffectHook shieldOfTier(int tier, @org.jetbrains.annotations.Nullable Double seconds) {
        return new EffectHook.Shield(Trigger.MELEE_HIT, tagged("午餐时间"), 1.0,
                new TieredParams(tier, seconds == null ? OptionalDouble.empty() : OptionalDouble.of(seconds),
                        OptionalDouble.empty()));
    }

    private static EffectHook summonSpirit(int tier, @org.jetbrains.annotations.Nullable Double seconds) {
        return new EffectHook.SummonSpirit(Trigger.MELEE_HIT, tagged("精灵的启示"), 1.0,
                new TieredParams(tier, seconds == null ? OptionalDouble.empty() : OptionalDouble.of(seconds),
                        OptionalDouble.empty()));
    }

    private static EffectHook flatAttackUp(com.astrum.pcrd.api.v1.GrantDuration duration) {
        return new EffectHook.FlatAttackUp(Trigger.KILL, TriggerParams.NONE, 1.0,
                new com.astrum.pcrd.api.v1.FlatAmount(10, 1.0), duration);
    }

    private static EffectHook allyEffect(int ticks) {
        return new EffectHook.AllyEffect(Trigger.MELEE_HIT, tagged("加速"), 1.0,
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("speed"), ticks, 0,
                OptionalDouble.empty());
    }

    private static EffectHook extraDamage(double amount) {
        return new EffectHook.ExtraDamage(Trigger.MELEE_HIT, tagged("广域斩击"), 1.0, amount,
                Optional.empty());
    }

    private static EffectHook gainCharge(double amount) {
        return new EffectHook.GainCharge(Trigger.MELEE_HIT, tagged("剑意迸发"), 1.0, amount);
    }

    private static EffectHook healSelf() {
        return new EffectHook.HealSelf(Trigger.MELEE_HIT, tagged("午餐时间"), 1.0,
                new TieredParams(1, OptionalDouble.empty(), OptionalDouble.empty()));
    }

    private static EffectHook splash(double damage, double radius) {
        return new EffectHook.Splash(Trigger.MELEE_HIT, tagged("闪电球"), 1.0, radius, damage, Optional.empty());
    }

    private static EffectHook panic() {
        return new EffectHook.Panic(Trigger.MELEE_HIT, tagged("格林炸裂"), 1.0,
                new TieredParams(1, OptionalDouble.empty(), OptionalDouble.empty()));
    }

    private static EffectHook defenceDown(int tier) {
        return new EffectHook.DefenceDown(Trigger.MELEE_HIT, tagged("护甲削弱"), 1.0,
                new TieredParams(tier, OptionalDouble.of(8.0), OptionalDouble.empty()));
    }
}
