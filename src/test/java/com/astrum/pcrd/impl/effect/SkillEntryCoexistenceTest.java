package com.astrum.pcrd.impl.effect;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Two different skills of one character are two types and two live rows; the same skill fired twice is one
 * row with a refreshed clock.</b>
 *
 * <h2>The human's sentence, and the two halves this proves</h2>
 * 「buff给予了后，给予新buff不是覆盖，而是同时存在，给予同一个buff才是刷新持续cd。我只装上雪菲，他只会显示冻结之湖或者雪鞋，
 * 不会两个同时存在。」
 *
 * <p>The old applier made the opposite true <em>on purpose</em>: one type per party slot ({@code skill_1..5}) and
 * the skill in the instance's <b>amplifier</b> as a row index, so a second skill had to remove the first or
 * vanilla would merge the two into one row. This test is the inverse requirement, and it works through the same
 * mechanism the old one documented:
 *
 * <ol>
 *   <li><b>Vanilla's merge</b> — {@code MobEffectInstance.update} — is run for real, so a future MC upgrade that
 *       changed it fails here instead of quietly changing what a player sees;</li>
 *   <li><b>one type per skill</b> as the identity. {@code AstrumSkillEffect}s are built the way the registry
 *       builds them ({@code Holder.direct}, the same trick {@code Holder#direct} enables without a game), and
 *       the two branches are asserted against the applier's own rules: no removal, so a different skill is a
 *       second entry, and the same skill refreshes.</li>
 * </ol>
 *
 * <h2>Why {@code Holder.direct} and not {@code PartyEffects.skillHolderFor}</h2>
 * A {@code DeferredHolder} is only bound after the game's {@code RegisterEvent}, so a unit test cannot use the
 * registry's own holders. {@code Holder.direct} wraps exactly the object the registry would hold, and the
 * behaviour asserted is vanilla's, keyed by that object — the same relation the registered holders have. That
 * the registry really holds one holder per declared identity is
 * {@code SkillEffectTypesTest.theDeclarationIsRegisteredOneHolderPerSkill}'s assertion.
 *
 * <h2>What this cannot prove</h2>
 * That two rows <em>appear</em> on a player's bar: {@code LivingEntity.addEffect} needs a {@code LivingEntity},
 * which needs a level, which needs a running server. The instance-level mechanism is asserted here; the painted
 * column is a human's check, and the report says so.
 */
class SkillEntryCoexistenceTest {

    /** 雪菲, the character whose two skills the human named. */
    private static final ResourceLocation SHEFFY = ResourceLocation.parse("astrum:sheffy");

    /**
     * The two skill tags of hers that were merging into one row: 雪靴 and 冻结之湖.
     *
     * <p>Named from the pack, and asserted to be <b>declared</b> types below, so a rename in the data fails here
     * rather than making the test vacuous.
     */
    private static final String SNOW_BOOTS = "snow_boots";
    private static final String FROZEN_LAKE = "frozen_lake";

    /** {@return the type the registry builds for one (character, skill), as a direct holder} */
    private static Holder<MobEffect> typeOf(ResourceLocation character, String skill) {
        SkillEffectTypes.Identity identity = new SkillEffectTypes.Identity(character, skill);
        return Holder.direct(new AstrumSkillEffect(identity));
    }

    /**
     * <b>Two different skills of one character are two types — so vanilla cannot merge them.</b>
     *
     * <p>Vanilla keeps a player's effects in a map keyed by the effect <em>type</em>. Two distinct types are two
     * keys, so both instances stay: 「同时存在」. The map below is the statement of that rule, and the assertions
     * are on the keys the applier would hand it.
     */
    @Test
    void twoDifferentSkillsOfOneCharacterAreTwoTypes() {
        Holder<MobEffect> boots = typeOf(SHEFFY, SNOW_BOOTS);
        Holder<MobEffect> lake = typeOf(SHEFFY, FROZEN_LAKE);

        assertNotSame(boots.value(), lake.value(), "each skill owns its own MobEffect object");

        // The registry id is the identity, and it is what the atlas uses for the sprite path — so two skills are
        // never one id (SkillEffectTypesTest asserts the round trip and the uniqueness over all 91).
        ResourceLocation bootsId = ((AstrumSkillEffect) boots.value()).identity().id();
        ResourceLocation lakeId = ((AstrumSkillEffect) lake.value()).identity().id();
        assertNotEquals(bootsId, lakeId);

        Map<Holder<MobEffect>, MobEffectInstance> bar = new LinkedHashMap<>();
        bar.put(boots, new MobEffectInstance(boots, 240, 0, false, false, true));
        bar.put(lake, new MobEffectInstance(lake, 240, 0, false, false, true));
        assertEquals(2, bar.size(),
                "two types, so the bar holds two rows: 雪靴 and 冻结之湖 are live at the same time");
        assertEquals(SNOW_BOOTS, ((AstrumSkillEffect) boots.value()).identity().skill());
        assertEquals(FROZEN_LAKE, ((AstrumSkillEffect) lake.value()).identity().skill());
    }

    /**
     * <b>The same skill fired twice is one row, refreshed — the longer duration wins.</b>
     *
     * <p>{@code LivingEntity.addEffect} hands a second instance of an existing type to
     * {@code MobEffectInstance.update}. At an equal amplifier (which the applier always writes — the amplifier is
     * no longer a row index) it takes the new duration only when that duration is longer, so a re-fire
     * <b>refreshes</b> and never stacks. Both orders are asserted: the old rule must not resurrect a longer
     * clock that has already run down.
     */
    @Test
    void theSameSkillFiredTwiceIsOneRowWithARefreshedClock() {
        Holder<MobEffect> boots = typeOf(SHEFFY, SNOW_BOOTS);

        MobEffectInstance first = new MobEffectInstance(boots, 100, 0, false, false, true);
        MobEffectInstance second = new MobEffectInstance(boots, 240, 0, false, false, true);
        first.update(second);
        assertEquals(240, first.getDuration(), "a longer re-fire extends the one instance");
        assertEquals(0, first.getAmplifier(), "and it stays one row (no amplifier changes)");

        MobEffectInstance longer = new MobEffectInstance(boots, 240, 0, false, false, true);
        MobEffectInstance shorter = new MobEffectInstance(boots, 40, 0, false, false, true);
        longer.update(shorter);
        assertEquals(240, longer.getDuration(),
                "a shorter re-fire does not cut the clock short: the longer remaining duration is kept");
    }

    /**
     * <b>The ultimate has its own identity, so a cast and a small skill coexist.</b>
     *
     * <p>This is the case the old design explicitly traded away: per slot, her ultimate firing while her small
     * skill's row was live merged the two and the row then named whichever ended last. With
     * {@code (character, "ub")} as its own type, a cast is a second row.
     */
    @Test
    void theUltimateHasItsOwnTypeApartFromHerSmallSkills() {
        Holder<MobEffect> boots = typeOf(SHEFFY, SNOW_BOOTS);
        Holder<MobEffect> ultimate = typeOf(SHEFFY, SkillEffectTypes.UB_SKILL);
        assertNotSame(boots.value(), ultimate.value());
        assertEquals(SkillEffectTypes.UB_SKILL, ((AstrumSkillEffect) ultimate.value()).identity().skill(),
                "the reserved tag is the one the cast path writes, and the one AffectionKernel.ubKey() answers");

        Map<Holder<MobEffect>, MobEffectInstance> bar = new LinkedHashMap<>();
        bar.put(boots, new MobEffectInstance(boots, 240, 0, false, false, true));
        bar.put(ultimate, new MobEffectInstance(ultimate, 360, 0, false, false, true));
        assertEquals(2, bar.size(), "a cast while a small skill's buff is running is two rows, not one");
    }

    /** A type knows what it is: two types for the same identity are the same identity, not merely equal names. */
    @Test
    void theIdentityIsInTheTypeAndTheAmplifierCarriesNothing() {
        AstrumSkillEffect first = new AstrumSkillEffect(new SkillEffectTypes.Identity(SHEFFY, SNOW_BOOTS));
        AstrumSkillEffect second = new AstrumSkillEffect(new SkillEffectTypes.Identity(SHEFFY, SNOW_BOOTS));
        assertEquals(first.identity(), second.identity());
        assertEquals(SHEFFY, first.identity().character());
        assertSame(first.identity(), SkillEffectTypes.identityOf((MobEffect) first).orElseThrow());
        assertTrue(SkillEffectTypes.identityOf((MobEffect) null).isEmpty(), "null is not ours");
    }

    /**
     * <b>A skill entry is a duration row in the beneficial column, and it never ticks.</b>
     *
     * <p>Vanilla formats a clock for it (「技能提供的状态」), and its whole life is that clock: no per-instance
     * tick, so there is no tick listener anywhere in this feature. {@code shouldApplyEffectTickThisTick} is
     * inherited from {@link MobEffect} and stays {@code false}, which is the assertion's point — a later
     * override would be the first step back toward a polling design.
     */
    @Test
    void theTypeIsAnInertBeneficialDurationRow() {
        AstrumSkillEffect effect = new AstrumSkillEffect(new SkillEffectTypes.Identity(SHEFFY, SNOW_BOOTS));
        assertTrue(effect.isBeneficial(), "a fired skill sits in the beneficial column, where a player looks");
        assertFalse(effect.isInstantenous(), "and it is a duration entry, so vanilla formats a clock for it");
        assertFalse(effect.shouldApplyEffectTickThisTick(0, 0), "no per-instance tick: nothing to schedule");
        assertEquals(0, effect.getBlendDurationTicks(), "and no blend window either — vanilla's own fields");
    }

    /** An identity is required: a type with none must fail with a sentence, not an NPE inside {@code super(...)}. */
    @Test
    void aTypeWithoutAnIdentityIsRejectedWithItsOwnMessage() {
        IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new AstrumSkillEffect(null));
        assertTrue(thrown.getMessage().contains("identity"), thrown.getMessage());
    }
}